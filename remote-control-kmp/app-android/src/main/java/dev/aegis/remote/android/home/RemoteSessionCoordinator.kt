package dev.aegis.remote.android.home

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.aegis.remote.android.R
import dev.aegis.remote.android.clipboard.AndroidClipboardBridge
import dev.aegis.remote.android.clipboard.AndroidClipboardProtocolBridge
import dev.aegis.remote.android.clipboard.AndroidClipboardSyncGate
import dev.aegis.remote.android.input.AndroidInputProtocolBridge
import dev.aegis.remote.android.input.AndroidTrackpadGestureMapper
import dev.aegis.remote.android.input.AndroidVisualPointerMapper
import dev.aegis.remote.android.pairing.AndroidLocalPairingClient
import dev.aegis.remote.android.pairing.AndroidLocalPairingCrypto
import dev.aegis.remote.android.pairing.AndroidLocalPairingQrVerifier
import dev.aegis.remote.android.pairing.AndroidPairingIdentity
import dev.aegis.remote.android.pairing.AndroidPairingIdentityStore
import dev.aegis.remote.android.pairing.LocalPairingPollingEvent
import dev.aegis.remote.android.pairing.LocalPairingPollingOutcome
import dev.aegis.remote.android.pairing.PairingQrTransport
import dev.aegis.remote.android.pairing.awaitLocalPairingDecision
import dev.aegis.remote.android.pairing.isTransientLocalPairingFailure
import dev.aegis.remote.android.pairing.resolveVerifiedLocalPairingEndpoint
import dev.aegis.remote.android.routing.AndroidRouteHealthChecker
import dev.aegis.remote.android.routing.AndroidTurnConfigProvider
import dev.aegis.remote.android.routing.DEFAULT_ANDROID_STUN_URLS
import dev.aegis.remote.android.security.AndroidDeviceIdentityStore
import dev.aegis.remote.android.security.AndroidRemoteOperationException
import dev.aegis.remote.android.security.AndroidSshEnrollmentKeyStore
import dev.aegis.remote.android.security.AndroidSshHealthChecker
import dev.aegis.remote.android.security.encodePrivateKeyCredentials
import dev.aegis.remote.android.service.AegisSessionService
import dev.aegis.remote.android.webrtc.AndroidLocalProtocolClient
import dev.aegis.remote.android.webrtc.AndroidRtcLiveStats
import dev.aegis.remote.android.webrtc.AndroidVisualProtocolBridge
import dev.aegis.remote.android.webrtc.AndroidWebRtcViewerSession
import dev.aegis.remote.android.wol.AndroidWakeOnLanSender
import dev.aegis.remote.core.clipboard.ClipboardSyncDecision
import dev.aegis.remote.core.input.KeyCode
import dev.aegis.remote.core.input.MouseButtonType
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.AuthMethod
import dev.aegis.remote.core.model.ClipboardPayload
import dev.aegis.remote.core.model.ConnectionRoute
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.HostAddress
import dev.aegis.remote.core.model.HostKeyFingerprint
import dev.aegis.remote.core.model.MacAddress
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.MonitorInfo
import dev.aegis.remote.core.model.RelayConfig
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.RouteDiagnostics
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.model.SshCredentialsRef
import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.VideoConfig
import dev.aegis.remote.core.model.WakeOnLanCapability
import dev.aegis.remote.core.model.WakeOnLanConfig
import dev.aegis.remote.core.pairing.LocalPairedProfile
import dev.aegis.remote.core.pairing.LocalPairingQrPayloadParser
import dev.aegis.remote.core.pairing.LocalPairingRequestBody
import dev.aegis.remote.core.pairing.LocalPairingRequestStatus
import dev.aegis.remote.core.pairing.localPairingDeviceProofPayload
import dev.aegis.remote.core.pairing.localPairingProofPayload
import dev.aegis.remote.core.pairing.localProtocolTokenPayload
import dev.aegis.remote.core.relay.RelayDeviceEvent
import dev.aegis.remote.core.routing.BasicConnectionRouteManager
import dev.aegis.remote.core.routing.ConnectionRouteManager
import dev.aegis.remote.core.security.KeyRotationReason
import dev.aegis.remote.core.security.P256SessionE2ee
import dev.aegis.remote.core.security.SecureCredentialStore
import dev.aegis.remote.core.security.canonicalOpenSshSha256Fingerprint
import dev.aegis.remote.core.session.AdaptiveQualitySessionController
import dev.aegis.remote.core.session.ApplicationWakeup
import dev.aegis.remote.core.session.ConnectionFailureKind
import dev.aegis.remote.core.session.ConnectionSupervisor
import dev.aegis.remote.core.session.ExponentialBackoffReconnectionManager
import dev.aegis.remote.core.session.PrepareVisualSessionUseCase
import dev.aegis.remote.core.session.RemoteSessionState
import dev.aegis.remote.core.session.ResolveSshRouteUseCase
import dev.aegis.remote.core.session.SshRoutePlan
import dev.aegis.remote.core.session.VisualReconnectionDecision
import dev.aegis.remote.core.session.VisualReconnectionDecisionContext
import dev.aegis.remote.core.session.VisualReconnectionPolicy
import dev.aegis.remote.core.session.VisualSessionPlan
import dev.aegis.remote.core.session.classifyApplicationWakeup
import dev.aegis.remote.core.sftp.SftpPath
import dev.aegis.remote.core.sftp.SftpSession
import dev.aegis.remote.core.sftp.SftpTransferCancellation
import dev.aegis.remote.core.sftp.TransferProgress
import dev.aegis.remote.core.sftp.TransferResumeCapability
import dev.aegis.remote.core.storage.DeviceProfileRepository
import dev.aegis.remote.core.terminal.TerminalInput
import dev.aegis.remote.core.terminal.TerminalKeyStroke
import dev.aegis.remote.core.terminal.TerminalSession
import dev.aegis.remote.core.webrtc.DataChannelClient
import dev.aegis.remote.core.webrtc.VideoSessionState
import dev.aegis.remote.protocol.ControlCommand
import dev.aegis.remote.protocol.ProtocolDataChannelClient
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageChannel
import dev.aegis.remote.protocol.ProtocolSignalingClient
import dev.aegis.remote.relayclient.KtorRelayClient
import dev.aegis.remote.relayclient.RelayIdentityLifecycleCoordinator
import dev.aegis.remote.relayclient.RelayIdentityRotationPendingException
import dev.aegis.remote.relayclient.openE2eeProtocolMessageChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.SurfaceViewRenderer
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal data class RemoteSessionDependencies(
    val application: Application,
    val repository: DeviceProfileRepository,
    val credentialStore: SecureCredentialStore,
    val deviceIdentityStore: AndroidDeviceIdentityStore,
    val visualProtocolBridge: AndroidVisualProtocolBridge,
    val clipboardBridge: AndroidClipboardBridge,
    val clipboardSyncGate: AndroidClipboardSyncGate,
    val trackpadGestureMapper: AndroidTrackpadGestureMapper,
    val visualPointerMapper: AndroidVisualPointerMapper,
    val turnConfigProvider: AndroidTurnConfigProvider,
    val routeManager: () -> ConnectionRouteManager,
    val relayClientProvider: () -> KtorRelayClient?,
    val sessionCoordinatorProvider: () -> AndroidRemoteSessionCoordinator?,
    val takeRelayE2eeChannel: suspend (SessionId) -> ProtocolMessageChannel,
    val clearRelaySessionArtifacts: suspend (SessionId) -> Unit,
    val onProfileConnected: suspend (DeviceProfileId, dev.aegis.remote.core.model.ConnectionRouteType) -> Unit,
    val onSessionIdle: () -> Unit,
    val onIncomingClipboardText: (String) -> Unit,
    val onHostUnlinked: (DeviceProfileId) -> Unit = {},
)

// One session context (jobs, liveness, reconnect state) is shared by every
// lifecycle path; splitting the owner would split that invariant with it.
@Suppress("LargeClass")
internal class RemoteSessionCoordinator(
    private val state: StateFlow<AndroidHomeUiState>,
    private val scope: CoroutineScope,
    private val dependencies: RemoteSessionDependencies,
    private val updateState: ((AndroidHomeUiState) -> AndroidHomeUiState) -> Unit,
) {
    private val application = dependencies.application
    private val repository = dependencies.repository
    private val credentialStore = dependencies.credentialStore
    private val deviceIdentityStore = dependencies.deviceIdentityStore
    private val visualProtocolBridge = dependencies.visualProtocolBridge
    private val clipboardBridge = dependencies.clipboardBridge
    private val clipboardSyncGate = dependencies.clipboardSyncGate
    private val trackpadGestureMapper = dependencies.trackpadGestureMapper
    private val visualPointerMapper = dependencies.visualPointerMapper
    private val turnConfigProvider = dependencies.turnConfigProvider
    private val routeManager = dependencies.routeManager
    private val relayClientProvider = dependencies.relayClientProvider
    private val sessionCoordinatorProvider = dependencies.sessionCoordinatorProvider
    private val takeRelayE2eeChannel = dependencies.takeRelayE2eeChannel
    private val clearRelaySessionArtifacts = dependencies.clearRelaySessionArtifacts
    private val onProfileConnected = dependencies.onProfileConnected
    private val onSessionIdle = dependencies.onSessionIdle
    private val visualContext = RemoteVisualSessionContext()

    private val visualConnectionCoordinator: RemoteVisualConnectionCoordinator by lazy {
        RemoteVisualConnectionCoordinator(
            state = state,
            scope = scope,
            dependencies = dependencies,
            context = visualContext,
            updateState = updateState,
            closeVisualSession = ::closeVisualSession,
            scheduleVisualReconnect = ::scheduleVisualReconnect,
        )
    }

    val hasActiveSession: Boolean
        get() = visualContext.session != null

    val activeSessionId: SessionId?
        get() = visualContext.sessionId

    fun hasActiveSessionFor(sessionId: SessionId): Boolean = visualContext.sessionId == sessionId && visualContext.session != null

    suspend fun sendNativeInput(
        sessionId: SessionId,
        events: List<RemoteInputEvent>,
    ): Result<Unit> =
        runCatching {
            visualContext.inputMutex.withLock {
                val session = visualContext.session ?: error("Native WebRTC DataChannel is not active.")
                events.forEach { event -> session.sendProtocolMessage(ProtocolMessage.Input(sessionId, event)) }
            }
        }.rethrowCancellation()

    suspend fun sendNativeProtocolMessage(
        message: ProtocolMessage,
    ): Result<Unit> =
        runCatching {
            visualContext.inputMutex.withLock {
                val session = visualContext.session ?: error("Native WebRTC DataChannel is not active.")
                session.sendProtocolMessage(message)
            }
        }.rethrowCancellation()

    fun sendInputEvents(events: List<RemoteInputEvent>) {
        sendVisualInputEvents(events)
    }

    fun trackpadMove(
        deltaX: Float,
        deltaY: Float,
    ) {
        trackpadGestureMapper
            .mapMotion(deltaX, deltaY)
            ?.let { motion -> sendVisualInputEvents(listOf(RemoteInputEvent.MouseMoveRelative(motion.deltaX, motion.deltaY))) }
    }

    fun trackpadScroll(
        deltaX: Float,
        deltaY: Float,
    ) {
        trackpadGestureMapper
            .mapNaturalScroll(deltaX, deltaY)
            ?.let { scroll -> sendVisualInputEvents(listOf(RemoteInputEvent.Scroll(scroll.deltaX, scroll.deltaY))) }
    }

    fun trackpadClick(button: MouseButtonType) {
        sendVisualInputEvents(
            listOf(
                RemoteInputEvent.MouseButton(button, pressed = true),
                RemoteInputEvent.MouseButton(button, pressed = false),
            ),
        )
    }

    fun trackpadSetButton(
        button: MouseButtonType,
        pressed: Boolean,
    ) {
        sendVisualInputEvents(listOf(RemoteInputEvent.MouseButton(button, pressed)))
    }

    fun resetTrackpadGesture() {
        trackpadGestureMapper.reset()
    }

    fun close() {
        closeVisualSession()
    }

    suspend fun shutdown() {
        val session = visualContext.session.also { visualContext.session = null }
        val sessionId = visualContext.sessionId
        visualContext.adaptiveQualityController = null
        visualContext.reconnectJob?.cancel()
        visualContext.stateJob?.cancel()
        visualContext.protocolJob?.cancel()
        visualContext.statsJob?.cancel()
        visualContext.pingJob?.cancel()
        visualContext.turnRefreshJob?.cancel()
        visualContext.capabilityJob?.cancel()
        if (session != null && sessionId != null) {
            runCatching {
                visualContext.inputMutex.withLock {
                    session.sendProtocolMessage(
                        ProtocolMessage.Input(
                            sessionId,
                            RemoteInputEvent.MouseButton(MouseButtonType.Left, pressed = false),
                        ),
                    )
                }
            }.rethrowCancellation()
        }
        runCatching { session?.stop() }.rethrowCancellation()
        if (sessionId != null) {
            runCatching { sessionCoordinatorProvider()?.closed(sessionId) }.rethrowCancellation()
        }
        visualContext.sessionId = null
        visualContext.plan = null
        visualContext.renderer = null
        trackpadGestureMapper.reset()
    }

    fun lastSelectedIceRoute(): dev.aegis.remote.core.nat.ValidatedIceRoute? = visualContext.lastSelectedIceRoute

    fun lastBackgroundedAtEpochMillis(): Long? = visualContext.lastBackgroundedAtEpochMillis

    fun onNetworkOnline(online: Boolean) {
        visualContext.networkOnline = online
    }

    fun onApplicationBackground() {
        visualContext.lastBackgroundedAtEpochMillis = System.currentTimeMillis()
    }

    fun onApplicationForeground(backgroundDurationMillis: Long) {
        val sessionId = visualContext.sessionId ?: return
        val wakeup =
            classifyApplicationWakeup(
                backgroundDurationMillis,
                visualContext.liveness.isFresh(),
            )
        when (wakeup) {
            ApplicationWakeup.Ignore -> {
                Unit
            }

            ApplicationWakeup.Probe -> {
                scope.launch {
                    val session = visualContext.session ?: return@launch
                    val now = System.currentTimeMillis()
                    visualContext.liveness.onPingSent(now)
                    runCatching {
                        session.sendProtocolMessage(
                            ProtocolMessage.Control(sessionId, ControlCommand.Ping(now)),
                        )
                    }.rethrowCancellation()
                }
            }

            ApplicationWakeup.Reconnect -> {
                scheduleVisualReconnect(sessionId, "Application was backgrounded long enough to rebuild", 0L)
            }
        }
    }

    fun prepareVisualSession() = visualConnectionCoordinator.prepareVisualSession()

    fun setStreamingQuality(mode: dev.aegis.remote.core.model.QualityMode) = visualConnectionCoordinator.setStreamingQuality(mode)

    // The reconnect decision tree (skew, offline, backoff, route switch) reads
    // as one policy; the branches share the captured session/plan snapshot.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun scheduleVisualReconnect(
        sessionId: SessionId,
        reason: String? = null,
        iceRestartGraceMillis: Long = 0L,
    ) {
        if (visualContext.reconnectJob?.isActive == true) return
        val plan = visualContext.plan ?: return
        val profile = state.value.profiles.firstOrNull { it.id == state.value.selectedProfileId } ?: return
        val relaySessionId = state.value.relay.approvedSessionIdFor(profile.relayDeviceId)

        visualContext.reconnectJob =
            scope.launch {
                if (visualContext.protocolSkew) {
                    updateState { current ->
                        current.copy(
                            visual =
                                current.visual.copy(
                                    streaming = false,
                                    videoState = "Failed",
                                    message = application.getString(R.string.status_protocol_mismatch),
                                ),
                        )
                    }
                    return@launch
                }
                if (iceRestartGraceMillis > 0L) {
                    delay(iceRestartGraceMillis)
                    if (visualContext.sessionId != sessionId || visualContext.session == null) return@launch
                }
                val manager = routeManager()
                val diagnostics = manager.detectAvailableRoutes(profile)
                val now = System.currentTimeMillis()
                val decision =
                    VisualReconnectionPolicy(
                        reconnectionManager =
                            ExponentialBackoffReconnectionManager(
                                baseDelayMillis = ConnectionSupervisor.DEFAULT_BASE_DELAY_MILLIS,
                                maxDelayMillis = ConnectionSupervisor.DEFAULT_MAX_DELAY_MILLIS,
                                maxAttempts = Int.MAX_VALUE,
                                stableResetAfterMillis = ConnectionSupervisor.DEFAULT_STABLE_RESET_MILLIS,
                            ),
                    ).decide(
                        VisualReconnectionDecisionContext(
                            profile = profile,
                            failedRoute = plan.route,
                            diagnostics = diagnostics,
                            attempt = visualContext.reconnectAttempt,
                            lastFailureEpochMillis = now,
                            lastStableEpochMillis = visualContext.lastStableEpochMillis,
                            networkOnline = visualContext.networkOnline,
                            failureKind =
                                if (visualContext.protocolSkew) {
                                    ConnectionFailureKind.ProtocolSkew
                                } else if (!visualContext.networkOnline) {
                                    ConnectionFailureKind.Offline
                                } else {
                                    ConnectionFailureKind.Transient
                                },
                            lastSelectedPairType = visualContext.lastSelectedPairType,
                            selectedPairProvesCurrentTypeImpossible =
                                visualContext.lastSelectedPairType != null &&
                                    visualContext.lastSelectedPairType != plan.route.type,
                        ),
                    )

                when (decision) {
                    is VisualReconnectionDecision.GiveUp -> {
                        updateState { current ->
                            current.copy(
                                visual =
                                    current.visual.copy(
                                        streaming = false,
                                        videoState = "Failed",
                                        message =
                                            if (decision.code == AegisFailureCodes.SESSION_PROTOCOL_SKEW) {
                                                application.getString(R.string.status_protocol_mismatch)
                                            } else {
                                                application.getString(R.string.visual_reconnect_failed)
                                            },
                                    ),
                            )
                        }
                    }

                    is VisualReconnectionDecision.WaitOffline -> {
                        updateState { current ->
                            current.copy(
                                visual =
                                    current.visual.copy(
                                        streaming = false,
                                        videoState = "Reconnecting",
                                        message = application.getString(R.string.status_reconnecting),
                                    ),
                            )
                        }
                        delay(ConnectionSupervisor.DEFAULT_BASE_DELAY_MILLIS)
                        visualContext.reconnectJob = null
                        scheduleVisualReconnect(sessionId, decision.reason)
                    }

                    is VisualReconnectionDecision.RetrySameRoute,
                    is VisualReconnectionDecision.SwitchRoute,
                    -> {
                        val delayMillis = decision.delayMillis
                        val route = decision.route()
                        updateState { current ->
                            current.copy(
                                visual =
                                    current.visual.copy(
                                        streaming = false,
                                        videoState = "Reconnecting",
                                        message = application.getString(R.string.visual_reconnecting),
                                    ),
                            )
                        }
                        delay(delayMillis)
                        visualContext.reconnectAttempt += 1
                        runCatching {
                            val (reconnectSessionId, reconnectRelaySessionId) =
                                if (relaySessionId == null) {
                                    SessionId("local-visual-${UUID.randomUUID()}") to null
                                } else {
                                    val freshSessionId = createApprovedRelaySessionForReconnect(profile, sessionId)
                                    freshSessionId to freshSessionId.value
                                }
                            restartVisualSession(profile, reconnectSessionId, reconnectRelaySessionId, diagnostics, route)
                        }.rethrowCancellation().onFailure { error ->
                            updateState { current ->
                                current.copy(
                                    visual =
                                        current.visual.copy(
                                            streaming = false,
                                            videoState = "Failed",
                                            message = error.message ?: application.getString(R.string.visual_reconnect_failed),
                                        ),
                                )
                            }
                            visualContext.reconnectJob = null
                            scheduleVisualReconnect(sessionId, error.message ?: "WebRTC reconnection setup failed")
                        }
                    }
                }
            }
    }

    /**
     * A relay signaling/E2EE channel is single-use for the lifetime of its
     * authorized session. Full recovery therefore admits a fresh relay
     * session and waits until the trusted desktop peer has auto-approved it
     * and the new E2EE channel is ready.
     */
    private suspend fun createApprovedRelaySessionForReconnect(
        profile: DeviceProfile,
        previousSessionId: SessionId,
    ): SessionId {
        val targetRelayDeviceId =
            profile.relayDeviceId
                ?: error("${AegisFailureCodes.RELAY_RECONNECT_UNAVAILABLE}: profile has no relay device id")
        val client =
            relayClientProvider()
                ?: error("${AegisFailureCodes.RELAY_RECONNECT_UNAVAILABLE}: relay client is not registered")
        val coordinator =
            sessionCoordinatorProvider()
                ?: error("${AegisFailureCodes.RELAY_RECONNECT_UNAVAILABLE}: session coordinator is unavailable")

        runCatching { coordinator.closed(previousSessionId) }.rethrowCancellation()
        clearRelaySessionArtifacts(previousSessionId)
        updateState { current ->
            current.copy(
                relay =
                    current.relay.copy(
                        lastSessionApproved = false,
                        approvedSessionIdsByRelayDevice =
                            current.relay.approvedSessionIdsByRelayDevice - targetRelayDeviceId.value,
                        message = application.getString(R.string.visual_reconnecting),
                    ),
            )
        }

        val session = client.createSession(targetRelayDeviceId)
        coordinator.created(session, profile.id)
        updateState { current ->
            current.copy(
                relay =
                    current.relay.copy(
                        lastSessionId = session.sessionId.value,
                        lastSessionTargetRelayDeviceId = targetRelayDeviceId.value,
                        message = application.getString(R.string.status_waiting_approval),
                    ),
            )
        }

        val approved =
            withTimeoutOrNull(RELAY_RECONNECT_APPROVAL_TIMEOUT_MILLIS) {
                state.first { current ->
                    current.relay.approvedSessionIdFor(targetRelayDeviceId) == session.sessionId.value
                }
            }
        check(approved != null) {
            "${AegisFailureCodes.RELAY_RECONNECT_APPROVAL_TIMEOUT}: fresh relay session was not approved with E2EE within " +
                "${RELAY_RECONNECT_APPROVAL_TIMEOUT_MILLIS}ms"
        }
        return session.sessionId
    }

    private suspend fun restartVisualSession(
        profile: DeviceProfile,
        sessionId: SessionId,
        relaySessionId: String?,
        diagnostics: RouteDiagnostics,
        route: ConnectionRoute,
    ) {
        runCatching {
            if (relaySessionId != null && visualContext.sessionId == sessionId) {
                sessionCoordinatorProvider()?.let { coordinator ->
                    when (coordinator.state(sessionId)) {
                        RemoteSessionState.Connected,
                        RemoteSessionState.NegotiatingIce,
                        -> coordinator.reconnecting(sessionId)

                        RemoteSessionState.Reconnecting -> Unit

                        else -> error("SESSION_NOT_READY_FOR_RECONNECT")
                    }
                    coordinator.establishingE2ee(sessionId)
                }
            }
            val plan =
                PrepareVisualSessionUseCase(
                    routeManager = routeManager(),
                    turnConfigProvider = turnConfigProvider,
                    stunUrls = DEFAULT_ANDROID_STUN_URLS,
                    clock = { System.currentTimeMillis() },
                ).invoke(profile, diagnostics = diagnostics.onlyRoute(route))
            visualConnectionCoordinator.prepareOrStartVisualSession(sessionId, relaySessionId, plan, profile)
        }.rethrowCancellation()
            .onSuccess { result ->
                updateState { current ->
                    val plan = visualContext.plan
                    current.copy(
                        visual =
                            if (plan != null) {
                                plan
                                    .toVisualUiState(
                                        sessionId = sessionId.value,
                                        dataChannelPayload = result.payload,
                                        application = application,
                                        relaySendSucceeded = result.startedNativeSession,
                                        relaySendError = result.error,
                                        inputEnabled = profile.permissions.input,
                                        clipboardEnabled = profile.permissions.clipboard,
                                    ).copy(message = application.getString(R.string.visual_reconnecting))
                            } else {
                                current.visual.copy(message = application.getString(R.string.visual_reconnecting))
                            },
                    )
                }
            }.onFailure { error ->
                updateState { current ->
                    current.copy(
                        visual =
                            current.visual.copy(
                                streaming = false,
                                videoState = "Failed",
                                message = error.message ?: application.getString(R.string.visual_reconnect_failed),
                            ),
                    )
                }
            }
    }

    private fun VisualReconnectionDecision.route(): ConnectionRoute =
        when (this) {
            is VisualReconnectionDecision.RetrySameRoute -> route

            is VisualReconnectionDecision.SwitchRoute -> route

            is VisualReconnectionDecision.GiveUp,
            is VisualReconnectionDecision.WaitOffline,
            -> error("Decision $this does not carry a route")
        }

    private fun RouteDiagnostics.onlyRoute(route: ConnectionRoute): RouteDiagnostics =
        copy(
            health =
                health.map { item ->
                    item.copy(available = item.routeType == route.type)
                },
            selectedRoute = route.type,
            failureReason = null,
        )

    /**
     * Returns the async teardown job (mouse release + WebRTC stop + remote
     * lifecycle close) so restarts can await the old session before starting
     * a new one instead of racing against its teardown.
     */
    fun closeVisualSession(
        leaveScreen: Boolean = false,
        cancelReconnect: Boolean = true,
        terminateRemoteSession: Boolean = true,
    ): Job {
        val session = visualContext.session
        val sessionId = visualContext.sessionId
        visualContext.session = null
        trackpadGestureMapper.reset()
        onSessionIdle()
        visualContext.adaptiveQualityController = null
        if (cancelReconnect) {
            visualContext.reconnectJob?.cancel()
            visualContext.reconnectJob = null
            visualContext.reconnectAttempt = 0
        }
        visualContext.stateJob?.cancel()
        visualContext.stateJob = null
        visualContext.protocolJob?.cancel()
        visualContext.protocolJob = null
        visualContext.statsJob?.cancel()
        visualContext.statsJob = null
        visualContext.pingJob?.cancel()
        visualContext.pingJob = null
        visualContext.turnRefreshJob?.cancel()
        visualContext.turnRefreshJob = null
        visualContext.capabilityJob?.cancel()
        visualContext.capabilityJob = null
        val teardownJob =
            scope.launch {
                if (session != null && sessionId != null) {
                    runCatching {
                        visualContext.inputMutex.withLock {
                            session.sendProtocolMessage(
                                ProtocolMessage.Input(
                                    sessionId,
                                    RemoteInputEvent.MouseButton(MouseButtonType.Left, pressed = false),
                                ),
                            )
                        }
                    }.rethrowCancellation()
                }
                runCatching { session?.stop() }
                    .rethrowCancellation()
                    .onFailure { error -> Log.e("AegisWebRTC", "visual_stop_failed code=${error.message ?: error::class.simpleName}") }
                if (terminateRemoteSession && sessionId != null) {
                    runCatching { sessionCoordinatorProvider()?.closed(sessionId) }
                        .rethrowCancellation()
                        .onFailure { error ->
                            Log.e("AegisSession", "lifecycle_close_failed code=${error.message ?: error::class.simpleName}")
                            reportVisualInputUnavailable("Session cleanup failed: ${error.message ?: "unknown error"}")
                        }
                }
            }
        updateState {
            it.copy(
                screen = if (leaveScreen) it.screen else AndroidHomeScreenMode.Detail,
                relay =
                    if (terminateRemoteSession && sessionId != null) {
                        it.relay.copy(
                            approvedSessionIdsByRelayDevice =
                                it.relay.approvedSessionIdsByRelayDevice.filterValues { approvedId -> approvedId != sessionId.value },
                            lastSessionApproved =
                                it.relay.lastSessionApproved && it.relay.lastSessionId != sessionId.value,
                        )
                    } else {
                        it.relay
                    },
                visual =
                    if (leaveScreen) {
                        it.visual.copy(busy = false, streaming = false, videoState = "Closed")
                    } else {
                        VisualUiState()
                    },
            )
        }
        visualContext.sessionId = null
        visualContext.plan = null
        return teardownJob
    }

    fun attachVisualRenderer(renderer: SurfaceViewRenderer) {
        visualContext.renderer = renderer
        visualContext.session?.attachRenderer(renderer)
    }

    fun detachVisualRenderer(renderer: SurfaceViewRenderer) {
        visualContext.session?.detachRenderer(renderer)
        if (visualContext.renderer == renderer) {
            visualContext.renderer = null
        }
    }

    fun sendVisualPointer(
        position: VisualPointerPosition,
        phase: VisualPointerPhase,
    ) {
        val plan = visualContext.plan ?: return reportVisualInputUnavailable("The visual session is not active.")
        val current = state.value
        val preferredMonitorId = plan.videoConfig.monitorId
        val monitor =
            current.visual.monitorOptions.firstOrNull { it.id == preferredMonitorId }
                ?: current.visual.monitorOptions.firstOrNull { it.primary }
                ?: current.visual.monitorOptions.firstOrNull()
                ?: MonitorInfo(
                    id = preferredMonitorId ?: MonitorId("primary"),
                    name = "Active display",
                    width = plan.videoConfig.width,
                    height = plan.videoConfig.height,
                    primary = true,
                )
        val remote =
            visualPointerMapper.map(
                touchX = position.touchX,
                touchY = position.touchY,
                viewportWidth = position.viewportWidth,
                viewportHeight = position.viewportHeight,
                monitor = monitor,
            ) ?: return
        val move = RemoteInputEvent.MouseMove(remote.first, remote.second, monitor.id)
        val events =
            when (phase) {
                VisualPointerPhase.Tap -> {
                    listOf(
                        move,
                        RemoteInputEvent.MouseButton(MouseButtonType.Left, pressed = true),
                        RemoteInputEvent.MouseButton(MouseButtonType.Left, pressed = false),
                    )
                }

                VisualPointerPhase.DragStart -> {
                    listOf(move, RemoteInputEvent.MouseButton(MouseButtonType.Left, pressed = true))
                }

                VisualPointerPhase.DragMove -> {
                    listOf(move)
                }
            }
        sendVisualInputEvents(events)
    }

    private fun sendVisualInputEvents(events: List<RemoteInputEvent>) {
        val current = state.value
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId }
        if (profile?.permissions?.input != true) {
            reportVisualInputUnavailable("Remote input is not permitted for this profile.")
            return
        }
        val sessionId = visualContext.sessionId
        val session = visualContext.session
        if (sessionId == null || session == null) {
            reportVisualInputUnavailable("Start the visual session before sending input.")
            return
        }
        scope.launch {
            runCatching {
                visualContext.inputMutex.withLock {
                    events.forEach { event -> session.sendProtocolMessage(ProtocolMessage.Input(sessionId, event)) }
                }
            }.rethrowCancellation().onFailure { error ->
                reportVisualInputUnavailable(error.message ?: "The WebRTC control channel is not ready.")
            }
        }
    }

    fun selectVisualMonitor(monitorId: MonitorId) {
        val current = state.value
        val monitor = current.visual.monitorOptions.firstOrNull { it.id == monitorId }
        if (monitor == null) {
            updateState {
                it.copy(visual = it.visual.copy(message = application.getString(R.string.visual_monitor_need_session)))
            }
            return
        }
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId }
        val session = visualContext.session
        if (profile == null || session == null || visualContext.sessionId == null) {
            updateState {
                it.copy(visual = it.visual.copy(message = application.getString(R.string.visual_monitor_need_session)))
            }
            return
        }
        scope.launch {
            runCatching {
                session.selectMonitor(monitorId.value)
                repository.saveProfile(profile.copy(defaultMonitorId = monitorId))
                visualContext.plan =
                    visualContext.plan?.let { plan ->
                        plan.copy(videoConfig = plan.videoConfig.copy(monitorId = monitorId))
                    }
            }.onSuccess {
                updateState {
                    it.copy(
                        visual =
                            it.visual.copy(
                                selectedMonitorId = monitorId,
                                message = application.getString(R.string.visual_monitor_capturing, monitor.name),
                            ),
                        remoteInput = it.remoteInput.copy(monitorId = monitorId.value),
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                updateState {
                    it.copy(
                        visual =
                            it.visual.copy(
                                message = application.getString(R.string.visual_monitor_need_session),
                            ),
                    )
                }
            }
        }
    }

    private fun reportVisualInputUnavailable(message: String) {
        updateState { current -> current.copy(visual = current.visual.copy(message = message)) }
    }
}
