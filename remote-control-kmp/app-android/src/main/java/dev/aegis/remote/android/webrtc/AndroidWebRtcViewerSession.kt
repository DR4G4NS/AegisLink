package dev.aegis.remote.android.webrtc

import android.content.Context
import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.ConnectionStats
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.VideoConfig
import dev.aegis.remote.core.model.WebRtcIceTransportPolicy
import dev.aegis.remote.core.nat.IcePathValidator
import dev.aegis.remote.core.nat.ValidatedIceRoute
import dev.aegis.remote.core.webrtc.RemoteVideoSession
import dev.aegis.remote.core.webrtc.SignalingClient
import dev.aegis.remote.core.webrtc.SignalingMessage
import dev.aegis.remote.core.webrtc.VideoSessionState
import dev.aegis.remote.protocol.ControlCommand
import dev.aegis.remote.protocol.JsonProtocolMessageChannel
import dev.aegis.remote.protocol.ProtocolDataChannelKind
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageChannel
import dev.aegis.remote.protocol.ProtocolMessageJsonCodec
import dev.aegis.remote.protocol.preferredDataChannelKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.webrtc.DataChannel
import org.webrtc.DataChannel.Init
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/**
 * Native Android WebRTC viewer behind the shared [RemoteVideoSession] port.
 *
 * Signaling stays outside this adapter: callers supply a LAN, VPN, or relay-backed
 * [SignalingClient]. TURN passwords are resolved immediately before the peer is
 * created and are never retained in UI state or logs.
 */
class AndroidWebRtcViewerSession(
    context: Context,
    private val sessionId: SessionId,
    private val signaling: SignalingClient,
    private val turnCredentialResolver: suspend (credentialRef: String) -> String?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val networkChanges: Flow<Unit> = androidNetworkChanges(context.applicationContext),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RemoteVideoSession {
    private val appContext = context.applicationContext
    private val stateEvents = MutableSharedFlow<VideoSessionState>(replay = 1)
    private val liveStatsEvents = MutableSharedFlow<AndroidRtcLiveStats>(replay = 1)
    private var peerConnection: PeerConnection? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var signalingJob: Job? = null
    private var statsJob: Job? = null
    private var networkChangesJob: Job? = null
    private val protocolChannelLock = Any()
    private val protocolChannels = mutableMapOf<ProtocolDataChannelKind, ProtocolMessageChannel>()
    private val protocolChannelJobs = mutableMapOf<ProtocolDataChannelKind, Job>()

    // Android creates the four reserved labels for each peer generation.
    private val boundProtocolChannelKinds = mutableSetOf<ProtocolDataChannelKind>()
    private var renderer: SurfaceViewRenderer? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var firstFrameSink: VideoSink? = null

    @Volatile
    private var firstFrameGate: AndroidFirstFrameGate? = null
    private var currentConfig: VideoConfig? = null
    private val protocolEvents = MutableSharedFlow<ProtocolMessage>(replay = 1)
    private val protocolCodec = ProtocolMessageJsonCodec()
    private val icePathValidator = IcePathValidator()
    private val mediaStatsSampler = AndroidRtcMediaStatsSampler()
    private val localIceCandidates = AndroidLocalIceCandidateBuffer<IceCandidate> { androidIceCandidateRank(it.sdp) }
    private val remoteIceCandidates = AndroidRemoteIceCandidateBuffer<IceCandidate> { androidIceCandidateRank(it.sdp) }

    @Volatile
    var lastValidatedIceRoute: dev.aegis.remote.core.nat.ValidatedIceRoute? = null
        private set

    override val states: Flow<VideoSessionState> = stateEvents.asSharedFlow()

    // ConnectionStats cannot represent an unavailable metric. Android exposes
    // nullable, measured-only values through liveStats instead of fabricating
    // zeroes or echoing the configured bitrate/fps targets.
    override val stats: Flow<ConnectionStats> = emptyFlow()
    internal val liveStats: Flow<AndroidRtcLiveStats> = liveStatsEvents.asSharedFlow()
    val incomingProtocolMessages: Flow<ProtocolMessage> = protocolEvents.asSharedFlow()

    init {
        stateEvents.tryEmit(VideoSessionState.Idle)
    }

    /** Attach a Compose-hosted SurfaceViewRenderer before starting the session. */
    fun attachRenderer(surface: SurfaceViewRenderer) {
        renderer?.let { previous -> remoteVideoTrack?.removeSink(previous) }
        renderer = surface
        remoteVideoTrack?.addSink(surface)
    }

    fun detachRenderer(surface: SurfaceViewRenderer) {
        remoteVideoTrack?.removeSink(surface)
        if (renderer == surface) {
            renderer = null
        }
    }

    override suspend fun start(
        config: VideoConfig,
        ice: StunTurnConfig,
    ) {
        stopInternal(closeSignaling = false)
        currentConfig = config
        stateEvents.emit(VideoSessionState.Negotiating)
        try {
            val peer = createPeerConnection(ice)
            peerConnection = peer
            peer.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
            ProtocolDataChannelKind.entries.forEach { kind ->
                bindDataChannel(
                    peer.createDataChannel(
                        kind.label,
                        androidDataChannelInit(kind),
                    ),
                )
            }
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
                                    "Android signaling receive failed " +
                                    "(${error.message ?: error::class.simpleName})",
                            ),
                        )
                    }
                }
            startStatsCollection(peer)
            startNetworkChangeRecovery()
            createAndSendOffer(peer)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { stopInternal(closeSignaling = true) }
            throw cancelled
        } catch (error: Throwable) {
            stopInternal(closeSignaling = true)
            stateEvents.emit(
                VideoSessionState.Failed(
                    "${AegisFailureCodes.WEBRTC_START_FAILED}: Android WebRTC viewer start failed " +
                        "(${error.message ?: error::class.simpleName})",
                ),
            )
        }
    }

    override suspend fun selectMonitor(monitorId: String) {
        val selectedMonitor = MonitorId(monitorId)
        currentConfig = currentConfig?.copy(monitorId = selectedMonitor)
        sendProtocolMessageIfAvailable(
            ProtocolMessage.Control(sessionId, ControlCommand.SelectMonitor(selectedMonitor)),
        )
    }

    override suspend fun setQuality(config: VideoConfig) {
        currentConfig = config
        sendProtocolMessageIfAvailable(
            ProtocolMessage.Control(sessionId, ControlCommand.SetQuality(config)),
        )
    }

    suspend fun restartIce(reason: String = "network-change") {
        check(reason.isNotBlank())
        val peer = peerConnection ?: return
        stateEvents.emit(VideoSessionState.Reconnecting)
        peer.restartIce()
        createAndSendOffer(peer)
    }

    suspend fun applyIceServersAndRestartIce(ice: StunTurnConfig) {
        val peer = peerConnection ?: error("${AegisFailureCodes.WEBRTC_TURN_REFRESH_FAILED}: no peer connection")
        val servers = buildIceServers(ice)
        val rtcConfig =
            PeerConnection.RTCConfiguration(servers).apply {
                iceTransportsType = androidIceTransportsType(ice.iceTransportPolicy)
            }
        check(peer.setConfiguration(rtcConfig)) {
            "${AegisFailureCodes.WEBRTC_TURN_REFRESH_FAILED}: PeerConnection.setConfiguration rejected refreshed ICE servers"
        }
        restartIce("turn-refresh")
    }

    override suspend fun stop() {
        stopInternal(closeSignaling = true)
        stateEvents.emit(VideoSessionState.Idle)
    }

    suspend fun sendProtocolMessage(message: ProtocolMessage) {
        require(message.sessionId == sessionId) {
            "Protocol message session ${message.sessionId.value} does not match ${sessionId.value}"
        }
        val channel =
            protocolChannelFor(message)
                ?: error("WebRTC DataChannel is not open for session ${sessionId.value}")
        channel.send(message)
    }

    private suspend fun sendProtocolMessageIfAvailable(message: ProtocolMessage) {
        protocolChannelFor(message)?.send(message)
    }

    private fun protocolChannelFor(message: ProtocolMessage): ProtocolMessageChannel? =
        synchronized(protocolChannelLock) {
            protocolChannels[message.preferredDataChannelKind()]
        }

    private suspend fun createPeerConnection(ice: StunTurnConfig): PeerConnection {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
        )
        val egl = EglBase.create()
        val factory =
            try {
                PeerConnectionFactory
                    .builder()
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                    .createPeerConnectionFactory()
            } catch (error: Throwable) {
                egl.release()
                throw error
            }
        eglBase = egl
        peerConnectionFactory = factory
        val servers = buildIceServers(ice)
        val rtcConfig =
            PeerConnection.RTCConfiguration(servers).apply {
                iceTransportsType = androidIceTransportsType(ice.iceTransportPolicy)
            }
        return requireNotNull(
            factory.createPeerConnection(rtcConfig, observer()),
        ) { "Android WebRTC could not create a peer connection" }
    }

    private suspend fun buildIceServers(config: StunTurnConfig): List<PeerConnection.IceServer> {
        val stun = config.stunUrls.map { url -> PeerConnection.IceServer.builder(url).createIceServer() }
        val turn =
            config.turnConfig
                ?.let { turnConfig ->
                    val credentialRef =
                        turnConfig.credentialRef
                            ?: throw IllegalArgumentException("TURN requires a secure credential reference")
                    val credential =
                        turnCredentialResolver(credentialRef)
                            ?: throw IllegalStateException("TURN credential is unavailable or expired")
                    turnConfig.urls.map { url ->
                        PeerConnection.IceServer
                            .builder(url)
                            .setUsername(turnConfig.username.orEmpty())
                            .setPassword(credential)
                            .createIceServer()
                    }
                }.orEmpty()
        return stun + turn
    }

    private fun observer(): PeerConnection.Observer =
        object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
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

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track() as? VideoTrack ?: return
                stateEvents.tryEmit(VideoSessionState.Negotiating)
                attachRemoteVideoTrack(track)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                scope.launch {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            stateEvents.emit(
                                if (firstFrameGate?.hasObservedFirstFrame == true) {
                                    VideoSessionState.Streaming
                                } else {
                                    VideoSessionState.Negotiating
                                },
                            )
                        }

                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            stateEvents.emit(VideoSessionState.Reconnecting)
                        }

                        PeerConnection.PeerConnectionState.FAILED -> {
                            stateEvents.emit(
                                VideoSessionState.Failed(
                                    "${AegisFailureCodes.WEBRTC_CONNECTION_FAILED}: Android WebRTC connection failed",
                                ),
                            )
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit

            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit

            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit

            override fun onDataChannel(channel: DataChannel) {
                // This peer is the sole creator of the reserved labels. A remotely
                // created channel is an untrusted duplicate and is never rebound.
                closeDataChannel(channel)
            }

            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(
                receiver: org.webrtc.RtpReceiver,
                mediaStreams: Array<org.webrtc.MediaStream>,
            ) = Unit
        }

    private fun bindDataChannel(channel: DataChannel) {
        val kind = ProtocolDataChannelKind.fromLabel(channel.label())
        if (kind == null) {
            closeDataChannel(channel)
            return
        }
        val accepted =
            synchronized(protocolChannelLock) {
                boundProtocolChannelKinds.add(kind)
            }
        if (!accepted) {
            closeDataChannel(channel)
            return
        }
        bindProtocolTransport(
            kind = kind,
            transport =
                AndroidWebRtcDataChannelTextTransport(
                    handle = NativeAndroidDataChannelHandle(channel),
                    onBufferOverflow =
                        if (kind == ProtocolDataChannelKind.Pointer) {
                            BufferOverflow.DROP_OLDEST
                        } else {
                            BufferOverflow.SUSPEND
                        },
                    dropWhenOverCapacity = kind == ProtocolDataChannelKind.Pointer,
                ),
        )
    }

    private fun bindProtocolTransport(
        kind: ProtocolDataChannelKind,
        transport: AndroidWebRtcDataChannelTextTransport,
    ) {
        val protocolChannel = JsonProtocolMessageChannel(transport, protocolCodec)
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    protocolChannel.incoming.collect { message ->
                        if (message.sessionId == sessionId && kind.accepts(message)) {
                            protocolEvents.emit(message)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    stateEvents.emit(
                        VideoSessionState.Failed(
                            "${AegisFailureCodes.SESSION_PROTOCOL_PROCESSING_FAILED}: " +
                                (error.message ?: "WebRTC DataChannel protocol failed"),
                        ),
                    )
                }
            }
        synchronized(protocolChannelLock) {
            check(protocolChannels.put(kind, protocolChannel) == null) { "DataChannel ${kind.label} is already bound" }
            check(protocolChannelJobs.put(kind, job) == null) { "DataChannel ${kind.label} job is already bound" }
        }
        job.start()
    }

    private fun closeDataChannel(channel: DataChannel) {
        channel.close()
        channel.dispose()
    }

    private suspend fun createAndSendOffer(peer: PeerConnection) {
        localIceCandidates.beginDescription()
        val offer = peer.createSdp(SessionDescription.Type.OFFER)
        peer.setLocalSdp(offer)
        signaling.send(SignalingMessage.Offer(sessionId, offer.description))
        localIceCandidates.descriptionSignaled().forEach { candidate -> sendLocalIceCandidate(candidate) }
    }

    private suspend fun applySignaling(
        peer: PeerConnection,
        message: SignalingMessage,
    ) {
        when (message) {
            is SignalingMessage.Answer -> {
                remoteIceCandidates.beginDescription()
                peer.setRemoteSdp(SessionDescription(SessionDescription.Type.ANSWER, message.sdp))
                applyQueuedRemoteIceCandidates(peer)
            }

            is SignalingMessage.Offer -> {
                remoteIceCandidates.beginDescription()
                peer.setRemoteSdp(SessionDescription(SessionDescription.Type.OFFER, message.sdp))
                applyQueuedRemoteIceCandidates(peer)
                localIceCandidates.beginDescription()
                val answer = peer.createSdp(SessionDescription.Type.ANSWER)
                peer.setLocalSdp(answer)
                signaling.send(SignalingMessage.Answer(sessionId, answer.description))
                localIceCandidates.descriptionSignaled().forEach { candidate -> sendLocalIceCandidate(candidate) }
            }

            is SignalingMessage.IceCandidate -> {
                val candidate =
                    IceCandidate(message.candidate.sdpMid, message.candidate.sdpMLineIndex ?: 0, message.candidate.candidate)
                try {
                    remoteIceCandidates.candidate(candidate).forEach { queued -> addRemoteIceCandidate(peer, queued) }
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

    private suspend fun sendLocalIceCandidate(candidate: IceCandidate) {
        signaling.send(
            SignalingMessage.IceCandidate(
                sessionId = sessionId,
                candidate =
                    dev.aegis.remote.core.webrtc.IceCandidateModel(
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                    ),
            ),
        )
    }

    private fun applyQueuedRemoteIceCandidates(peer: PeerConnection) {
        remoteIceCandidates.descriptionApplied().forEach { candidate -> addRemoteIceCandidate(peer, candidate) }
    }

    private fun addRemoteIceCandidate(
        peer: PeerConnection,
        candidate: IceCandidate,
    ) {
        check(peer.addIceCandidate(candidate)) { "Android WebRTC rejected a remote ICE candidate" }
    }

    private fun startStatsCollection(peer: PeerConnection) {
        statsJob?.cancel()
        statsJob =
            scope.launch {
                while (true) {
                    delay(STATS_POLL_INTERVAL_MS)
                    val report = runCatching { peer.awaitStats() }.getOrNull() ?: continue
                    val entries = report.toAndroidRtcStatsEntries()
                    val pairs = mapAndroidRtcStatsToIceCandidatePairs(entries)
                    val validation = pairs.takeIf { it.isNotEmpty() }?.let(icePathValidator::validate)
                    lastValidatedIceRoute = validation?.route
                    val media = mediaStatsSampler.sample(entries, clock())
                    if (validation == null && media == null) continue

                    liveStatsEvents.emit(
                        AndroidRtcLiveStats(
                            rttMs = validation?.rttMs,
                            bitrateKbps = media?.bitrateKbps,
                            bytesReceived = media?.bytesReceived,
                            packetsReceived = media?.packetsReceived,
                            packetsLost = media?.packetsLost,
                            packetLossPercent = media?.packetLossPercent,
                            jitterMs = media?.jitterMs,
                            framesDecoded = media?.framesDecoded,
                            framesDropped = media?.framesDropped,
                            decodeMs = media?.decodeMs,
                            freezeCount = media?.freezeCount,
                            totalFreezeDurationMs = media?.totalFreezeDurationMs,
                            fps = media?.fps,
                            resolution = media?.resolution,
                            networkType = validation?.reason,
                            routeType = validation?.route.toConnectionRouteType(),
                            localCandidateType = validation?.selectedPair?.localCandidateType?.name,
                            remoteCandidateType = validation?.selectedPair?.remoteCandidateType?.name,
                            transportProtocol = validation?.selectedPair?.transportProtocol,
                            turnTransport = validation?.selectedPair?.turnTransport,
                        ),
                    )
                }
            }
    }

    private fun startNetworkChangeRecovery() {
        networkChangesJob?.cancel()
        networkChangesJob =
            scope.launch {
                networkChanges.collect {
                    runCatching { restartIce("network-change") }
                        .onFailure { error ->
                            if (error is CancellationException) throw error
                            stateEvents.emit(
                                VideoSessionState.Failed(
                                    "${AegisFailureCodes.WEBRTC_ICE_RESTART_FAILED}: Android ICE restart failed " +
                                        "(${error.message ?: error::class.simpleName})",
                                ),
                            )
                        }
                }
            }
    }

    private suspend fun stopInternal(closeSignaling: Boolean) {
        signalingJob?.cancel()
        signalingJob = null
        val protocolState =
            synchronized(protocolChannelLock) {
                val channels = protocolChannels.values.toList()
                val jobs = protocolChannelJobs.values.toList()
                protocolChannels.clear()
                protocolChannelJobs.clear()
                boundProtocolChannelKinds.clear()
                channels to jobs
            }
        protocolState.second.forEach { job -> job.cancel() }
        statsJob?.cancel()
        statsJob = null
        networkChangesJob?.cancel()
        networkChangesJob = null
        protocolState.first.forEach { channel -> channel.close() }
        localIceCandidates.clear()
        remoteIceCandidates.clear()
        val track = remoteVideoTrack
        renderer?.let { currentRenderer -> track?.removeSink(currentRenderer) }
        firstFrameSink?.let { sink -> track?.removeSink(sink) }
        firstFrameSink = null
        firstFrameGate = null
        remoteVideoTrack = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase?.release()
        eglBase = null
        if (closeSignaling) signaling.close()
    }

    private fun attachRemoteVideoTrack(track: VideoTrack) {
        val previousTrack = remoteVideoTrack
        renderer?.let { currentRenderer -> previousTrack?.removeSink(currentRenderer) }
        firstFrameSink?.let { sink -> previousTrack?.removeSink(sink) }

        lateinit var gate: AndroidFirstFrameGate
        gate =
            AndroidFirstFrameGate {
                scope.launch {
                    if (firstFrameGate === gate) {
                        stateEvents.emit(VideoSessionState.Streaming)
                    }
                }
            }
        val sink = VideoSink { gate.observeFrame() }
        remoteVideoTrack = track
        firstFrameGate = gate
        firstFrameSink = sink
        track.addSink(sink)
        renderer?.let(track::addSink)
    }
}

internal fun androidIceTransportsType(policy: WebRtcIceTransportPolicy): PeerConnection.IceTransportsType =
    when (policy) {
        WebRtcIceTransportPolicy.All -> PeerConnection.IceTransportsType.ALL
        WebRtcIceTransportPolicy.RelayOnly -> PeerConnection.IceTransportsType.RELAY
    }

private const val STATS_POLL_INTERVAL_MS = 1_000L

private suspend fun PeerConnection.awaitStats(): RTCStatsReport =
    suspendCancellableCoroutine { continuation ->
        getStats { report ->
            if (continuation.isActive) {
                continuation.resume(report) {}
            }
        }
    }

private fun RTCStatsReport.toAndroidRtcStatsEntries(): List<AndroidRtcStatsEntry> =
    statsMap.values.map { stats ->
        AndroidRtcStatsEntry(
            id = stats.id,
            type = stats.type,
            members = stats.members,
        )
    }

private fun ValidatedIceRoute?.toConnectionRouteType(): ConnectionRouteType? =
    when (this) {
        ValidatedIceRoute.LanDirect -> ConnectionRouteType.Lan
        ValidatedIceRoute.InternetDirect -> ConnectionRouteType.StunDirect
        ValidatedIceRoute.TurnRelay -> ConnectionRouteType.TurnRelay
        null -> null
    }

internal fun androidDataChannelInit(kind: ProtocolDataChannelKind): Init =
    Init()
        .apply {
            ordered = kind.ordered
            kind.maxRetransmits?.let { maxRetransmits = it }
        }.also { init ->
            check(kind.matchesNegotiatedProperties(init.ordered, init.maxRetransmits)) {
                "DataChannel ${kind.label} does not satisfy its SCTP contract"
            }
        }

private suspend fun PeerConnection.createSdp(type: SessionDescription.Type): SessionDescription =
    suspendCancellableCoroutine { continuation ->
        val observer =
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = continuation.resume(description) {}

                override fun onCreateFailure(error: String) = continuation.resumeWith(Result.failure(IllegalStateException(error)))

                override fun onSetSuccess() = Unit

                override fun onSetFailure(error: String) = Unit
            }
        when (type) {
            SessionDescription.Type.OFFER -> createOffer(observer, MediaConstraints())
            SessionDescription.Type.ANSWER -> createAnswer(observer, MediaConstraints())
            else -> continuation.resumeWith(Result.failure(IllegalArgumentException("Cannot create SDP for $type")))
        }
    }

private suspend fun PeerConnection.setLocalSdp(description: SessionDescription) {
    suspendSet { observer -> setLocalDescription(observer, description) }
}

private suspend fun PeerConnection.setRemoteSdp(description: SessionDescription) {
    suspendSet { observer -> setRemoteDescription(observer, description) }
}

private suspend fun suspendSet(action: (SdpObserver) -> Unit) {
    suspendCancellableCoroutine { continuation ->
        action(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = Unit

                override fun onSetSuccess() = continuation.resume(Unit) {}

                override fun onCreateFailure(error: String) = Unit

                override fun onSetFailure(error: String) = continuation.resumeWith(Result.failure(IllegalStateException(error)))
            },
        )
    }
}
