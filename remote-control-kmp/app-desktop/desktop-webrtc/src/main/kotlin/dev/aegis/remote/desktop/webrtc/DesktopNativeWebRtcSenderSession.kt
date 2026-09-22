package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.model.AegisFailure
import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.ConnectionStats
import dev.aegis.remote.core.model.FailureCategory
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.TurnConfig
import dev.aegis.remote.core.model.VideoConfig
import dev.aegis.remote.core.model.WebRtcIceTransportPolicy
import dev.aegis.remote.core.nat.IcePathValidator
import dev.aegis.remote.core.nat.ValidatedIceRoute
import dev.aegis.remote.core.session.TurnCredentialRefreshPolicy
import dev.aegis.remote.core.webrtc.IceCandidateModel
import dev.aegis.remote.core.webrtc.RemoteVideoSession
import dev.aegis.remote.core.webrtc.SignalingClient
import dev.aegis.remote.core.webrtc.SignalingMessage
import dev.aegis.remote.core.webrtc.VideoSessionState
import dev.aegis.remote.desktop.capture.CaptureConfig
import dev.aegis.remote.desktop.capture.CaptureSession
import dev.aegis.remote.desktop.capture.DesktopFrameSource
import dev.aegis.remote.protocol.ControlCommand
import dev.aegis.remote.protocol.JsonProtocolMessageChannel
import dev.aegis.remote.protocol.ProtocolDataChannelKind
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageChannel
import dev.aegis.remote.protocol.preferredDataChannelKind
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceConnectionState
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCIceTransportPolicy
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpReceiver
import dev.onvoid.webrtc.RTCRtpSender
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCSignalingState
import dev.onvoid.webrtc.RTCStatsCollectorCallback
import dev.onvoid.webrtc.RTCStatsReport
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DesktopNativeWebRtcSenderSession(
    private val sessionId: SessionId,
    private val signaling: SignalingClient,
    private val turnCredentialResolver: suspend (TurnConfig) -> DesktopResolvedTurnCredentials?,
    private val frameSource: DesktopFrameSource = NativeDesktopFrameSource(),
    private val networkChanges: Flow<Unit> = desktopNetworkChanges(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RemoteVideoSession {
    private val stateEvents = MutableSharedFlow<VideoSessionState>(replay = 1)
    private val statEvents = MutableSharedFlow<ConnectionStats>(replay = 1)
    private var protocolProcessor: (suspend (ProtocolMessage) -> ProtocolHandlingResult)? = null
    private var protocolCleanup: suspend () -> Unit = {}
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: RTCPeerConnection? = null
    private var inboundDataChannelGeneration: DesktopInboundDataChannelGeneration? = null
    private var captureSession: CaptureSession? = null
    private var videoSender: RTCRtpSender? = null
    private val protocolChannelLock = Any()
    private val protocolChannels = mutableMapOf<ProtocolDataChannelKind, ProtocolMessageChannel>()
    private val protocolJobs = mutableMapOf<ProtocolDataChannelKind, Job>()

    // A label is accepted at most once for the lifetime of this peer generation.
    private val protocolChannelAdmission = DesktopDataChannelAdmission()
    private var signalingJob: Job? = null
    private var statsJob: Job? = null
    private var networkChangesJob: Job? = null
    private var turnRefreshJob: Job? = null
    private var currentConfig: VideoConfig? = null
    private var currentIce: StunTurnConfig? = null
    private var selectedMonitorId: MonitorId? = null
    private val icePathValidator = IcePathValidator()
    private val mediaStatsSampler = DesktopRtcMediaStatsSampler()
    private val localIceCandidates = DesktopLocalIceCandidateBuffer<RTCIceCandidate> { desktopIceCandidateRank(it.sdp) }
    private val remoteIceCandidates = DesktopRemoteIceCandidateBuffer<RTCIceCandidate> { desktopIceCandidateRank(it.sdp) }
    private val routedProtocolChannel =
        object : ProtocolMessageChannel {
            override val incoming: Flow<ProtocolMessage> = emptyFlow()

            override suspend fun send(message: ProtocolMessage) {
                sendProtocolMessage(message)
            }

            override suspend fun close() {
                closeProtocolChannels()
            }
        }

    override val states: Flow<VideoSessionState> = stateEvents.asSharedFlow()
    override val stats: Flow<ConnectionStats> = statEvents.asSharedFlow()

    init {
        stateEvents.tryEmit(VideoSessionState.Idle)
    }

    fun bindProtocolProcessor(
        processor: suspend (ProtocolMessage) -> ProtocolHandlingResult,
        cleanup: suspend () -> Unit = {},
    ) {
        protocolProcessor = processor
        protocolCleanup = cleanup
    }

    fun activeProtocolChannel(): ProtocolMessageChannel? =
        synchronized(protocolChannelLock) {
            routedProtocolChannel.takeIf { protocolChannels.isNotEmpty() }
        }

    override suspend fun start(
        config: VideoConfig,
        ice: StunTurnConfig,
    ) {
        stopInternal(closeSignaling = false)
        currentConfig = config
        currentIce = ice
        selectedMonitorId = config.monitorId
        stateEvents.emit(VideoSessionState.Negotiating)
        try {
            val rtcFactory = PeerConnectionFactory()
            factory = rtcFactory
            val dataChannelGeneration = DesktopInboundDataChannelGeneration()
            inboundDataChannelGeneration = dataChannelGeneration
            val peer = rtcFactory.createPeerConnection(buildRtcConfiguration(ice), observer(dataChannelGeneration))
            peerConnection = peer
            attachDesktopTrack(rtcFactory, peer, config)
            startStatsCollection(peer)
            startNetworkChangeRecovery(peer)
            startTurnRefresh(peer, ice)
            signaling.connect()
            signalingJob =
                scope.launch {
                    try {
                        signaling.incoming().collect { message -> applySignaling(peer, message) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        stateEvents.emit(
                            VideoSessionState.Failed(
                                "${AegisFailureCodes.WEBRTC_NETWORK_OPERATION_FAILED}: " +
                                    "Desktop signaling receive failed " +
                                    "(${error.message ?: error::class.simpleName})",
                            ),
                        )
                    }
                }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { stopInternal(closeSignaling = false) }
            throw cancelled
        } catch (error: Throwable) {
            stopInternal(closeSignaling = false)
            stateEvents.emit(
                VideoSessionState.Failed(
                    "${AegisFailureCodes.WEBRTC_START_FAILED}: Desktop WebRTC sender start failed " +
                        "(${error.message ?: error::class.simpleName})",
                ),
            )
        }
    }

    override suspend fun selectMonitor(monitorId: String) {
        selectedMonitorId = MonitorId(monitorId)
        captureSession?.selectMonitor(selectedMonitorId!!)
    }

    override suspend fun setQuality(config: VideoConfig) {
        currentConfig = config
        selectedMonitorId = config.monitorId ?: selectedMonitorId
        if (frameSource.capabilities.supportsLiveReconfigure) {
            captureSession?.reconfigure(config.toCaptureConfig(selectedMonitorId))
        }
        videoSender?.applyVideoLimits(config)
    }

    override suspend fun stop() {
        stopInternal(closeSignaling = true)
        stateEvents.emit(VideoSessionState.Idle)
    }

    private suspend fun buildRtcConfiguration(ice: StunTurnConfig): RTCConfiguration =
        RTCConfiguration().also { config ->
            config.iceServers.addAll(buildIceServers(ice))
            config.iceTransportPolicy = desktopIceTransportPolicy(ice.iceTransportPolicy)
        }

    private suspend fun buildIceServers(config: StunTurnConfig): List<RTCIceServer> {
        val stun =
            config.stunUrls.map { url ->
                RTCIceServer().also { server -> server.urls.add(url) }
            }
        val turn =
            config.turnConfig
                ?.let { turnConfig ->
                    val credentials =
                        turnCredentialResolver(turnConfig)
                            ?: throw IllegalStateException("TURN credential is unavailable or expired")
                    turnConfig.urls.map { url ->
                        RTCIceServer().also { server ->
                            server.urls.add(url)
                            server.username = credentials.username
                            server.password = credentials.credential
                        }
                    }
                }.orEmpty()
        return stun + turn
    }

    private suspend fun attachDesktopTrack(
        rtcFactory: PeerConnectionFactory,
        peer: RTCPeerConnection,
        config: VideoConfig,
    ) {
        val session = frameSource.open(config.toCaptureConfig(selectedMonitorId))
        captureSession = session
        val nativeSession =
            session as? DesktopWebRtcCaptureSession
                ?: error(
                    "${AegisFailureCodes.CAPTURE_FRAME_SOURCE_INCOMPATIBLE}: " +
                        "Frame source is not compatible with native WebRTC",
                )
        val track = nativeSession.createVideoTrack(rtcFactory)
        nativeSession.bind(track)
        videoSender = peer.addTrack(track, listOf("aegis-desktop")).also { it.applyVideoLimits(config) }
    }

    private fun observer(dataChannelGeneration: DesktopInboundDataChannelGeneration): PeerConnectionObserver =
        object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {
                try {
                    localIceCandidates.candidate(candidate).forEach { queued ->
                        scope.launch { sendLocalIceCandidate(queued) }
                    }
                } catch (overflow: dev.aegis.remote.core.webrtc.IceCandidateBufferOverflowException) {
                    scope.launch {
                        stateEvents.emit(
                            VideoSessionState.Failed(
                                "${AegisFailureCodes.WEBRTC_ICE_BUFFER_OVERFLOW}: ${overflow.message}",
                            ),
                        )
                    }
                }
            }

            override fun onConnectionChange(state: RTCPeerConnectionState) {
                scope.launch {
                    when (state) {
                        RTCPeerConnectionState.CONNECTED -> {
                            stateEvents.emit(VideoSessionState.Streaming)
                        }

                        RTCPeerConnectionState.DISCONNECTED -> {
                            runCatching { protocolCleanup() }
                            stateEvents.emit(VideoSessionState.Reconnecting)
                        }

                        RTCPeerConnectionState.FAILED -> {
                            runCatching { protocolCleanup() }
                            stateEvents.emit(
                                VideoSessionState.Failed(
                                    "${AegisFailureCodes.WEBRTC_CONNECTION_FAILED}: Desktop WebRTC connection failed",
                                ),
                            )
                        }

                        RTCPeerConnectionState.CLOSED -> {
                            runCatching { protocolCleanup() }
                            stateEvents.emit(VideoSessionState.Idle)
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            }

            override fun onDataChannel(channel: RTCDataChannel) {
                dataChannelGeneration.receive(channel) { owner -> bindDataChannel(channel, owner) }
            }

            override fun onSignalingChange(state: RTCSignalingState) = Unit

            override fun onIceConnectionChange(state: RTCIceConnectionState) = Unit

            override fun onIceGatheringChange(state: RTCIceGatheringState) = Unit

            override fun onAddStream(stream: MediaStream) = Unit

            override fun onRemoveStream(stream: MediaStream) = Unit

            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(
                receiver: RTCRtpReceiver,
                mediaStreams: Array<MediaStream>,
            ) = Unit

            override fun onTrack(transceiver: RTCRtpTransceiver) = Unit
        }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun bindDataChannel(
        channel: RTCDataChannel,
        owner: DesktopInboundDataChannelOwner,
    ) {
        val kind = protocolChannelAdmission.admit(channel)
        if (kind == null) {
            // The generation keeps ownership and performs native disposal after peer.close().
            owner.close()
            return
        }
        val protocolChannel =
            JsonProtocolMessageChannel(
                DesktopWebRtcDataChannelTextTransport(
                    handle = owner,
                    onBufferOverflow =
                        if (kind == ProtocolDataChannelKind.Pointer) {
                            BufferOverflow.DROP_OLDEST
                        } else {
                            BufferOverflow.SUSPEND
                        },
                    dropWhenOverCapacity = kind == ProtocolDataChannelKind.Pointer,
                ),
            )
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    protocolChannel.incoming.collect { message ->
                        if (message.sessionId != sessionId) {
                            val failure =
                                protocolDataChannelFailure(
                                    code = AegisFailureCodes.SESSION_INVALID_PROTOCOL_MESSAGE,
                                    operation = "validate-data-channel-session",
                                    stage = kind.label,
                                    category = FailureCategory.AUTHORIZATION,
                                    summary = "The DataChannel message belongs to a different session.",
                                    technicalCause = "The message session identifier did not match the active WebRTC session.",
                                    expected = "Every message is bound to the active authenticated session",
                                    actual = "A different session identifier was received",
                                    nextAction = "Discard the message and recreate the peer connection if the sender session is stale.",
                                )
                            runCatching {
                                sendProtocolMessage(
                                    ProtocolMessage.Control(
                                        sessionId = sessionId,
                                        command =
                                            ControlCommand.Error(
                                                code = failure.code,
                                                message = failure.summary,
                                                failure = failure,
                                            ),
                                    ),
                                )
                            }
                            return@collect
                        }
                        if (!kind.accepts(message)) {
                            val expectedKind = message.preferredDataChannelKind()
                            val failure =
                                protocolDataChannelFailure(
                                    code = AegisFailureCodes.SESSION_INVALID_PROTOCOL_MESSAGE,
                                    operation = "validate-data-channel-route",
                                    stage = kind.label,
                                    category = FailureCategory.PROTOCOL,
                                    summary = "A protocol message arrived on the wrong WebRTC DataChannel.",
                                    technicalCause = "${kind.label} received a message reserved for ${expectedKind.label}.",
                                    expected = "Messages use their declared reliable or lossy channel semantics",
                                    actual = "A cross-channel message was rejected before dispatch",
                                    nextAction = "Route the message through ${expectedKind.label}; do not fall back to aegis-control.",
                                )
                            runCatching {
                                sendProtocolMessage(
                                    ProtocolMessage.Control(
                                        sessionId = message.sessionId,
                                        command =
                                            ControlCommand.Error(
                                                code = failure.code,
                                                message = failure.summary,
                                                failure = failure,
                                            ),
                                    ),
                                )
                            }
                            return@collect
                        }
                        val processor = protocolProcessor ?: return@collect
                        try {
                            when (val result = processor(message)) {
                                is ProtocolHandlingResult.Respond -> sendProtocolMessage(result.message)

                                is ProtocolHandlingResult.Handled,
                                is ProtocolHandlingResult.Ignored,
                                -> Unit
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: DesktopProtocolMessageException) {
                            val failure =
                                error.appError.failure
                                    ?: protocolDataChannelFailure(
                                        code = AegisFailureCodes.SESSION_PROTOCOL_PROCESSING_FAILED,
                                        operation = "process-data-channel-message",
                                        stage = kind.label,
                                        category = FailureCategory.CAUSE_UNCONFIRMED,
                                        summary = "The desktop rejected the protocol request.",
                                        technicalCause = error.appError.message,
                                        expected = "The request passes session policy and backend validation",
                                        actual = "The protocol handler returned a typed error without a causal failure",
                                        nextAction = "Inspect the correlation trace and correct the request before retrying.",
                                        underlyingType = error.appError::class.qualifiedName,
                                    )
                            sendProtocolMessage(
                                ProtocolMessage.Control(
                                    sessionId = message.sessionId,
                                    command =
                                        ControlCommand.Error(
                                            code = failure.code,
                                            message = error.appError.message,
                                            failure = failure,
                                        ),
                                ),
                            )
                        } catch (error: Throwable) {
                            val failure =
                                protocolDataChannelFailure(
                                    code = AegisFailureCodes.SESSION_PROTOCOL_PROCESSING_FAILED,
                                    operation = "process-data-channel-message",
                                    stage = kind.label,
                                    category = FailureCategory.CAUSE_UNCONFIRMED,
                                    summary = "The desktop agent could not process this protocol request.",
                                    technicalCause = "An unexpected exception escaped the protocol processor; the cause is not confirmed.",
                                    expected = "The processor returns a handled, ignored, or response result",
                                    actual = "The processor raised ${error::class.simpleName ?: "an unexpected exception"}",
                                    nextAction = "Inspect the correlation trace and diagnose the exception before retrying.",
                                    underlyingType = error::class.qualifiedName,
                                )
                            sendProtocolMessage(
                                ProtocolMessage.Control(
                                    sessionId = message.sessionId,
                                    command =
                                        ControlCommand.Error(
                                            code = failure.code,
                                            message = failure.summary,
                                            failure = failure,
                                        ),
                                ),
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Transport shutdown and malformed payloads are isolated to
                    // this labelled channel; the other channels remain usable.
                } finally {
                    if (
                        kind == ProtocolDataChannelKind.Pointer ||
                        kind == ProtocolDataChannelKind.Keyboard ||
                        kind == ProtocolDataChannelKind.Control
                    ) {
                        runCatching { protocolCleanup() }
                    }
                }
            }
        synchronized(protocolChannelLock) {
            check(protocolChannels.put(kind, protocolChannel) == null) { "DataChannel ${kind.label} is already bound" }
            check(protocolJobs.put(kind, job) == null) { "DataChannel ${kind.label} job is already bound" }
        }
        job.start()
    }

    private suspend fun sendProtocolMessage(message: ProtocolMessage) {
        require(message.sessionId == sessionId) {
            "Protocol message session ${message.sessionId.value} does not match ${sessionId.value}"
        }
        val expectedKind = message.preferredDataChannelKind()
        val channel =
            synchronized(protocolChannelLock) {
                protocolChannels[expectedKind]
            } ?: error("Required WebRTC DataChannel ${expectedKind.label} is not open for session ${sessionId.value}")
        channel.send(message)
    }

    private fun protocolDataChannelFailure(
        code: String,
        operation: String,
        stage: String,
        category: FailureCategory,
        summary: String,
        technicalCause: String,
        expected: String,
        actual: String,
        nextAction: String,
        underlyingType: String? = null,
    ): AegisFailure =
        AegisFailure(
            code = code,
            component = "desktop-webrtc-data-channel",
            operation = operation,
            stage = stage,
            category = category,
            summary = summary,
            technicalCause = technicalCause,
            expected = expected,
            actual = actual,
            retryable = false,
            correlationId = sessionId.value,
            evidenceRef = null,
            nextAction = nextAction,
            underlyingType = underlyingType,
        )

    private suspend fun applySignaling(
        peer: RTCPeerConnection,
        message: SignalingMessage,
    ) {
        when (message) {
            is SignalingMessage.Offer -> {
                remoteIceCandidates.beginDescription()
                peer.setRemoteSdp(RTCSessionDescription(RTCSdpType.OFFER, message.sdp))
                applyQueuedRemoteIceCandidates(peer)
                localIceCandidates.beginDescription()
                val answer = peer.createAnswer()
                peer.setLocalSdp(answer)
                signaling.send(SignalingMessage.Answer(sessionId, answer.sdp))
                localIceCandidates.descriptionSignaled().forEach { candidate -> sendLocalIceCandidate(candidate) }
            }

            is SignalingMessage.Answer -> {
                remoteIceCandidates.beginDescription()
                peer.setRemoteSdp(RTCSessionDescription(RTCSdpType.ANSWER, message.sdp))
                applyQueuedRemoteIceCandidates(peer)
            }

            is SignalingMessage.IceCandidate -> {
                val candidate =
                    RTCIceCandidate(
                        message.candidate.sdpMid,
                        message.candidate.sdpMLineIndex ?: 0,
                        message.candidate.candidate,
                    )
                try {
                    remoteIceCandidates.candidate(candidate).forEach(peer::addIceCandidate)
                } catch (overflow: dev.aegis.remote.core.webrtc.IceCandidateBufferOverflowException) {
                    stateEvents.emit(
                        VideoSessionState.Failed(
                            "${AegisFailureCodes.WEBRTC_ICE_BUFFER_OVERFLOW}: ${overflow.message}",
                        ),
                    )
                }
            }
        }
    }

    private suspend fun sendLocalIceCandidate(candidate: RTCIceCandidate) {
        signaling.send(
            SignalingMessage.IceCandidate(
                sessionId = sessionId,
                candidate =
                    IceCandidateModel(
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                    ),
            ),
        )
    }

    private fun applyQueuedRemoteIceCandidates(peer: RTCPeerConnection) {
        remoteIceCandidates.descriptionApplied().forEach(peer::addIceCandidate)
    }

    private fun startStatsCollection(peer: RTCPeerConnection) {
        statsJob?.cancel()
        statsJob =
            scope.launch {
                while (true) {
                    delay(STATS_POLL_INTERVAL_MS)
                    val report = runCatching { peer.awaitStats() }.getOrNull() ?: continue
                    val entries = report.toDesktopRtcStatsEntries()
                    val pairs = mapDesktopRtcStatsToIceCandidatePairs(entries)
                    if (pairs.isEmpty()) continue

                    val validation = icePathValidator.validate(pairs)
                    val config = currentConfig ?: continue
                    val media = mediaStatsSampler.sample(entries, clock())
                    statEvents.emit(
                        ConnectionStats(
                            rttMs = validation.rttMs,
                            bitrateKbps =
                                media?.bitrateKbps?.takeIf { it > 0 }
                                    ?: validation.availableOutgoingBitrateKbps,
                            packetLossPercent = media?.packetLossPercent,
                            framesDropped = media?.framesDropped,
                            encodeMs = media?.encodeMs,
                            decodeMs = null,
                            fps = media?.fps?.takeIf { it > 0 },
                            resolution = media?.resolution?.takeIf { it != "unknown" },
                            networkType = validation.reason,
                            routeType = validation.route.toConnectionRouteType(config.routeType),
                            localCandidateType = validation.selectedPair?.localCandidateType?.name,
                            remoteCandidateType = validation.selectedPair?.remoteCandidateType?.name,
                            transportProtocol = validation.selectedPair?.transportProtocol,
                            turnTransport = validation.selectedPair?.turnTransport,
                            availableOutgoingBitrateKbps = validation.availableOutgoingBitrateKbps,
                        ),
                    )
                }
            }
    }

    private fun startTurnRefresh(
        peer: RTCPeerConnection,
        ice: StunTurnConfig,
    ) {
        turnRefreshJob?.cancel()
        val turn = ice.turnConfig ?: return
        val expiresAt = turn.expiresAtEpochMillis ?: return
        val issuedAt = clock()
        turnRefreshJob =
            scope.launch {
                delay(TurnCredentialRefreshPolicy.delayUntilRefreshMillis(clock(), issuedAt, expiresAt))
                val livePeer = peerConnection ?: peer
                val current = currentIce ?: ice
                val credentials =
                    current.turnConfig?.let { config ->
                        runCatching { turnCredentialResolver(config) }.getOrNull()
                    }
                if (credentials == null) {
                    stateEvents.emit(
                        VideoSessionState.Failed(
                            "${AegisFailureCodes.WEBRTC_TURN_REFRESH_FAILED}: TURN credential refresh failed",
                        ),
                    )
                    return@launch
                }
                try {
                    livePeer.setConfiguration(buildRtcConfiguration(current))
                    stateEvents.emit(VideoSessionState.Reconnecting)
                    livePeer.restartIce()
                    localIceCandidates.beginDescription()
                    val offer = livePeer.createOfferForRestart()
                    livePeer.setLocalSdp(offer)
                    signaling.send(SignalingMessage.Offer(sessionId, offer.sdp))
                    localIceCandidates.descriptionSignaled().forEach { candidate -> sendLocalIceCandidate(candidate) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    stateEvents.emit(
                        VideoSessionState.Failed(
                            "${AegisFailureCodes.WEBRTC_TURN_REFRESH_FAILED}: Desktop TURN refresh ICE restart failed " +
                                "(${error.message ?: error::class.simpleName})",
                        ),
                    )
                }
            }
    }

    private fun startNetworkChangeRecovery(peer: RTCPeerConnection) {
        networkChangesJob?.cancel()
        networkChangesJob =
            scope.launch {
                networkChanges.collect {
                    stateEvents.emit(VideoSessionState.Reconnecting)
                    try {
                        peer.restartIce()
                        localIceCandidates.beginDescription()
                        val offer = peer.createOfferForRestart()
                        peer.setLocalSdp(offer)
                        signaling.send(SignalingMessage.Offer(sessionId, offer.sdp))
                        localIceCandidates.descriptionSignaled().forEach { candidate -> sendLocalIceCandidate(candidate) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        stateEvents.emit(
                            VideoSessionState.Failed(
                                "${AegisFailureCodes.WEBRTC_ICE_RESTART_FAILED}: Desktop ICE restart failed " +
                                    "(${error.message ?: error::class.simpleName})",
                            ),
                        )
                    }
                }
            }
    }

    private suspend fun stopInternal(closeSignaling: Boolean) {
        val currentJob = currentCoroutineContext()[Job]
        signalingJob?.cancelAndJoin()
        signalingJob = null
        inboundDataChannelGeneration?.seal()
        closeProtocolChannels()
        if (statsJob != currentJob) {
            statsJob?.cancelAndJoin()
        } else {
            statsJob?.cancel()
        }
        statsJob = null
        if (networkChangesJob != currentJob) {
            networkChangesJob?.cancelAndJoin()
        } else {
            networkChangesJob?.cancel()
        }
        networkChangesJob = null
        if (turnRefreshJob != currentJob) {
            turnRefreshJob?.cancelAndJoin()
        } else {
            turnRefreshJob?.cancel()
        }
        turnRefreshJob = null
        currentIce = null
        localIceCandidates.clear()
        remoteIceCandidates.clear()
        val peer = peerConnection
        videoSender?.let { sender -> peer?.removeTrack(sender) }
        videoSender = null
        peer?.close()
        peerConnection = null
        captureSession?.close()
        captureSession = null
        inboundDataChannelGeneration?.drain()
        inboundDataChannelGeneration = null
        protocolChannelAdmission.reset()
        factory?.dispose()
        factory = null
        if (closeSignaling) signaling.close()
    }

    private suspend fun closeProtocolChannels() {
        val currentJob = currentCoroutineContext()[Job]
        val protocolState =
            synchronized(protocolChannelLock) {
                val channels = protocolChannels.values.toList()
                val jobs = protocolJobs.values.toList()
                protocolChannels.clear()
                protocolJobs.clear()
                channels to jobs
            }
        protocolState.second.forEach { job ->
            if (job != currentJob) {
                job.cancelAndJoin()
            }
        }
        protocolState.first.forEach { channel -> channel.close() }
        runCatching { protocolCleanup() }
    }
}

private const val STATS_POLL_INTERVAL_MS = 1_000L

private fun VideoConfig.toCaptureConfig(selectedMonitorId: MonitorId?): CaptureConfig =
    CaptureConfig(
        monitorId = monitorId ?: selectedMonitorId,
        maxWidth = width.coerceAtLeast(1),
        maxHeight = height.coerceAtLeast(1),
        maxFps = fps.coerceAtLeast(1),
    )

private fun RTCRtpSender.applyVideoLimits(config: VideoConfig) {
    val parameters = parameters
    parameters.encodings.forEach { encoding ->
        encoding.maxBitrate = config.bitrateKbps.coerceAtLeast(1) * 1_000
        encoding.maxFramerate = config.fps.coerceAtLeast(1).toDouble()
        encoding.scaleResolutionDownBy = 1.0
    }
    setParameters(parameters)
}

private suspend fun RTCPeerConnection.awaitStats(): RTCStatsReport {
    val deferred = CompletableDeferred<RTCStatsReport>()
    getStats(
        object : RTCStatsCollectorCallback {
            override fun onStatsDelivered(report: RTCStatsReport) {
                deferred.complete(report)
            }
        },
    )
    return deferred.await()
}

private fun RTCStatsReport.toDesktopRtcStatsEntries(): List<DesktopRtcStatsEntry> =
    stats.values.map { stats ->
        DesktopRtcStatsEntry(
            id = stats.id,
            type = stats.type,
            attributes = stats.attributes,
        )
    }

private fun ValidatedIceRoute?.toConnectionRouteType(default: ConnectionRouteType): ConnectionRouteType =
    when (this) {
        ValidatedIceRoute.LanDirect -> ConnectionRouteType.Lan
        ValidatedIceRoute.InternetDirect -> ConnectionRouteType.StunDirect
        ValidatedIceRoute.TurnRelay -> ConnectionRouteType.TurnRelay
        null -> default
    }

data class DesktopResolvedTurnCredentials(
    val username: String,
    val credential: String,
)

internal fun desktopIceTransportPolicy(policy: WebRtcIceTransportPolicy): RTCIceTransportPolicy =
    when (policy) {
        WebRtcIceTransportPolicy.All -> RTCIceTransportPolicy.ALL
        WebRtcIceTransportPolicy.RelayOnly -> RTCIceTransportPolicy.RELAY
    }

private suspend fun RTCPeerConnection.createAnswer(): RTCSessionDescription {
    val deferred = CompletableDeferred<RTCSessionDescription>()
    createAnswer(
        RTCAnswerOptions(),
        object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) {
                deferred.complete(description)
            }

            override fun onFailure(error: String) {
                deferred.completeExceptionally(IllegalStateException(error))
            }
        },
    )
    return deferred.await()
}

private suspend fun RTCPeerConnection.createOfferForRestart(): RTCSessionDescription {
    val deferred = CompletableDeferred<RTCSessionDescription>()
    createOffer(
        RTCOfferOptions().also { it.iceRestart = true },
        object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) {
                deferred.complete(description)
            }

            override fun onFailure(error: String) {
                deferred.completeExceptionally(IllegalStateException(error))
            }
        },
    )
    return deferred.await()
}

private suspend fun RTCPeerConnection.setLocalSdp(description: RTCSessionDescription) {
    setSdp { observer -> setLocalDescription(description, observer) }
}

private suspend fun RTCPeerConnection.setRemoteSdp(description: RTCSessionDescription) {
    setSdp { observer -> setRemoteDescription(description, observer) }
}

private suspend fun setSdp(action: (SetSessionDescriptionObserver) -> Unit) {
    val deferred = CompletableDeferred<Unit>()
    action(
        object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                deferred.complete(Unit)
            }

            override fun onFailure(error: String) {
                deferred.completeExceptionally(IllegalStateException(error))
            }
        },
    )
    deferred.await()
}
