package dev.aegis.remote.android.home

import android.app.Application
import android.util.Log
import dev.aegis.remote.android.R
import dev.aegis.remote.android.clipboard.AndroidClipboardBridge
import dev.aegis.remote.android.clipboard.AndroidClipboardSyncGate
import dev.aegis.remote.android.input.AndroidVisualPointerMapper
import dev.aegis.remote.android.routing.AndroidTurnConfigProvider
import dev.aegis.remote.android.routing.DEFAULT_ANDROID_STUN_URLS
import dev.aegis.remote.android.security.AndroidDeviceIdentityStore
import dev.aegis.remote.android.service.AegisSessionService
import dev.aegis.remote.android.ui.components.labelRes
import dev.aegis.remote.android.webrtc.AndroidLocalProtocolClient
import dev.aegis.remote.android.webrtc.AndroidVisualProtocolBridge
import dev.aegis.remote.android.webrtc.AndroidWebRtcViewerSession
import dev.aegis.remote.core.clipboard.ClipboardSyncDecision
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.ClipboardPayload
import dev.aegis.remote.core.model.ConnectionRoute
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.QualityMode
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.model.SessionProtocolCapabilities
import dev.aegis.remote.core.model.SshCredentialsRef
import dev.aegis.remote.core.model.localSessionProtocolCapabilities
import dev.aegis.remote.core.nat.toConnectionRouteType
import dev.aegis.remote.core.session.AdaptiveQualitySessionController
import dev.aegis.remote.core.session.PrepareVisualSessionUseCase
import dev.aegis.remote.core.session.ProtocolCapabilityHandshakeResult
import dev.aegis.remote.core.session.RemoteSessionState
import dev.aegis.remote.core.session.SessionLivenessTracker
import dev.aegis.remote.core.session.TurnCredentialRefreshPolicy
import dev.aegis.remote.core.session.VisualSessionPlan
import dev.aegis.remote.core.webrtc.VideoSessionState
import dev.aegis.remote.protocol.ControlCommand
import dev.aegis.remote.protocol.ProtocolDataChannelClient
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageChannel
import dev.aegis.remote.protocol.ProtocolSignalingClient
import dev.aegis.remote.relayclient.KtorRelayClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

internal class RemoteVisualConnectionCoordinator(
    private val state: kotlinx.coroutines.flow.StateFlow<AndroidHomeUiState>,
    private val scope: CoroutineScope,
    private val dependencies: RemoteSessionDependencies,
    private val context: RemoteVisualSessionContext,
    private val updateState: ((AndroidHomeUiState) -> AndroidHomeUiState) -> Unit,
    private val closeVisualSession: (Boolean, Boolean, Boolean) -> Job,
    private val scheduleVisualReconnect: (SessionId, String?, Long) -> Unit,
) {
    private val application: Application = dependencies.application
    private val credentialStore = dependencies.credentialStore
    private val deviceIdentityStore: AndroidDeviceIdentityStore = dependencies.deviceIdentityStore
    private val visualProtocolBridge: AndroidVisualProtocolBridge = dependencies.visualProtocolBridge
    private val clipboardBridge: AndroidClipboardBridge = dependencies.clipboardBridge
    private val clipboardSyncGate: AndroidClipboardSyncGate = dependencies.clipboardSyncGate
    private val visualPointerMapper: AndroidVisualPointerMapper = dependencies.visualPointerMapper
    private val turnConfigProvider: AndroidTurnConfigProvider = dependencies.turnConfigProvider
    private val routeManager = dependencies.routeManager
    private val relayClientProvider: () -> KtorRelayClient? = dependencies.relayClientProvider
    private val sessionCoordinatorProvider = dependencies.sessionCoordinatorProvider
    private val takeRelayE2eeChannel = dependencies.takeRelayE2eeChannel
    private val onProfileConnected = dependencies.onProfileConnected
    private val onIncomingClipboardText = dependencies.onIncomingClipboardText

    fun prepareVisualSession() {
        val profile = state.value.profiles.firstOrNull { it.id == state.value.selectedProfileId } ?: return
        updateState {
            it.copy(
                screen = AndroidHomeScreenMode.Visual,
                visual = VisualUiState(busy = true, message = application.getString(R.string.visual_preparing)),
                errorMessage = null,
            )
        }
        scope.launch {
            runCatching {
                PrepareVisualSessionUseCase(
                    routeManager = routeManager(),
                    turnConfigProvider = turnConfigProvider,
                    stunUrls = DEFAULT_ANDROID_STUN_URLS,
                    clock = { System.currentTimeMillis() },
                ).invoke(profile)
            }.onSuccess { plan ->
                preparePlannedVisualSession(profile, plan)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                updateState {
                    it.copy(
                        visual = VisualUiState(busy = false, message = error.message ?: application.getString(R.string.visual_prepare_failed)),
                        errorMessage = error.message,
                    )
                }
            }
        }
    }

    fun setStreamingQuality(mode: QualityMode) {
        val session = context.session ?: return
        val plan = context.plan ?: return
        val config = plan.videoConfig.forQualityMode(mode)
        updateState { current ->
            current.copy(
                visual =
                    current.visual.copy(
                        qualityMode = mode,
                        qualityChanging = true,
                        message = application.getString(R.string.visual_quality_applying, application.getString(mode.labelRes())),
                    ),
            )
        }
        scope.launch {
            runCatching { session.setQuality(config) }
                .onSuccess {
                    context.plan = plan.copy(videoConfig = config)
                    updateState { current ->
                        current.copy(
                            visual =
                                current.visual.copy(
                                    qualityMode = mode,
                                    qualityChanging = false,
                                    video = config.label(),
                                    message = application.getString(R.string.visual_quality_applied, application.getString(mode.labelRes())),
                                ),
                        )
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    updateState { current ->
                        current.copy(visual = current.visual.copy(qualityChanging = false, message = application.getString(R.string.visual_quality_failed)))
                    }
                }
        }
    }

    private suspend fun preparePlannedVisualSession(
        profile: DeviceProfile,
        plan: VisualSessionPlan,
    ) {
        val relaySessionId = state.value.relay.approvedSessionIdFor(profile.relayDeviceId)
        val canStartLocal = relaySessionId == null && profile.localProtocolAuthorizedDeviceId() != null && plan.route.supportsLocalProtocol()
        val sessionId = SessionId(relaySessionId ?: if (canStartLocal) "local-visual-${UUID.randomUUID()}" else "visual-preview")
        runCatching { prepareOrStartVisualSession(sessionId, relaySessionId, plan, profile) }
            .onSuccess { result ->
                updateState {
                    it.copy(
                        visual =
                            plan.toVisualUiState(
                                sessionId = sessionId.value,
                                dataChannelPayload = result.payload,
                                application = application,
                                relaySendSucceeded = result.startedNativeSession,
                                relaySendError = result.error,
                                inputEnabled = profile.permissions.input,
                                clipboardEnabled = profile.permissions.clipboard,
                            ),
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                updateState {
                    it.copy(
                        visual =
                            plan
                                .toVisualUiState(
                                    sessionId = sessionId.value,
                                    dataChannelPayload = null,
                                    application = application,
                                    inputEnabled = profile.permissions.input,
                                    clipboardEnabled = profile.permissions.clipboard,
                                ).copy(message = error.message ?: application.getString(R.string.visual_prepare_failed)),
                    )
                }
            }
    }

    internal suspend fun prepareOrStartVisualSession(
        sessionId: SessionId,
        relaySessionId: String?,
        plan: VisualSessionPlan,
        profile: DeviceProfile,
    ): VisualStartResult {
        val payload = visualProtocolBridge.buildStartVisualSessionPayload(sessionId, plan.videoConfig, plan.ice)
        val channel =
            try {
                openVisualProtocolChannel(sessionId, relaySessionId, plan, profile, payload)
                    ?: return VisualStartResult(
                        payload = payload,
                        startedNativeSession = false,
                        error = "Register this phone with the relay before starting WebRTC.",
                    )
            } catch (error: VisualSessionPreparationException) {
                return VisualStartResult(payload = error.payload, startedNativeSession = false, error = error.message)
            }
        // Await the previous session's teardown before wiring the new one so a
        // restart cannot race the old stop (renderer sinks, service state, and
        // remote lifecycle transitions would otherwise interleave).
        closeVisualSession(true, false, false).join()
        ProtocolDataChannelClient(sessionId, channel).startVisualSession(plan.videoConfig, plan.ice)
        val session =
            AndroidWebRtcViewerSession(
                context = application,
                sessionId = sessionId,
                signaling = ProtocolSignalingClient(sessionId, channel),
                turnCredentialResolver = { credentialRef ->
                    credentialStore.getSecret(SshCredentialsRef(credentialRef))?.toString(Charsets.UTF_8)
                },
                scope = scope,
            )
        context.session = session
        AegisSessionService.start(application)
        context.adaptiveQualityController =
            AdaptiveQualitySessionController(
                dataChannelClient = AndroidVisualDataChannelClient(sessionId, session),
            )
        context.sessionId = sessionId
        context.plan = plan
        context.renderer?.let(session::attachRenderer)
        collectVisualSession(session, sessionId)
        if (relaySessionId != null) prepareRelayIce(sessionId)
        session.start(plan.videoConfig, plan.ice)
        return VisualStartResult(payload = payload, startedNativeSession = true)
    }

    private suspend fun openVisualProtocolChannel(
        sessionId: SessionId,
        relaySessionId: String?,
        plan: VisualSessionPlan,
        profile: DeviceProfile,
        payload: String,
    ): ProtocolMessageChannel? {
        if (relaySessionId != null) {
            if (relayClientProvider() == null) return null
            return takeRelayE2eeChannel(sessionId)
        }
        val deviceId =
            profile.localProtocolAuthorizedDeviceId()
                ?: return localChannelFailure(payload, "Local WebRTC requires a profile created by approved PC pairing.")
        val protocolTokenRef =
            profile.localProtocolTokenRef
                ?: return localChannelFailure(payload, "This local pairing predates authenticated channels. Pair this PC again.")
        val protocolToken =
            credentialStore.getSecret(protocolTokenRef)?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
                ?: return localChannelFailure(payload, "The local protocol credential is unavailable. Pair this PC again.")
        val certificateFingerprint =
            profile.localAgentCertificateFingerprint?.takeIf { it.isNotBlank() }
                ?: return localChannelFailure(payload, "The local TLS certificate pin is unavailable. Pair this PC again.")
        val host =
            when (val route = plan.route) {
                is ConnectionRoute.LanRoute -> route.host
                is ConnectionRoute.VpnRoute -> route.host
                else -> return localChannelFailure(payload, "Create or approve a relay session before starting WebRTC on ${route.type.name}.")
            }
        val localIdentity =
            runCatching { deviceIdentityStore.getOrCreate() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrElse { error -> throw IllegalStateException("LAN_IDENTITY_STAGE_FAILED: ${error.message}", error) }
        val hostIdentity = requireNotNull(profile.pairedHostIdentity) { "LAN_HOST_IDENTITY_MISSING: Pair this PC again before opening a local session." }
        return AndroidLocalProtocolClient.createPinned(certificateFingerprint).openProtocolMessageChannel(
            host = host,
            pairingPort = host.port ?: LOCAL_PAIRING_PORT,
            sessionId = sessionId,
            authorizedDeviceId = deviceId,
            protocolToken = protocolToken,
            localIdentity = localIdentity,
            expectedHostIdentity = hostIdentity,
        )
    }

    private fun localChannelFailure(
        payload: String,
        message: String,
    ): ProtocolMessageChannel? = throw VisualSessionPreparationException(payload, message)

    private suspend fun prepareRelayIce(sessionId: SessionId) {
        when (sessionCoordinatorProvider()?.state(sessionId)) {
            RemoteSessionState.EstablishingE2ee -> {
                sessionCoordinatorProvider()?.openingSignaling(sessionId)
                sessionCoordinatorProvider()?.negotiatingIce(sessionId)
            }

            RemoteSessionState.OpeningSignaling -> {
                sessionCoordinatorProvider()?.negotiatingIce(sessionId)
            }

            else -> {
                error("SESSION_NOT_READY_FOR_ICE")
            }
        }
    }

    private fun collectVisualSession(
        session: AndroidWebRtcViewerSession,
        sessionId: SessionId,
    ) {
        context.stateJob?.cancel()
        context.protocolJob?.cancel()
        context.statsJob?.cancel()
        context.stateJob = scope.launch { observeVideoState(session, sessionId) }
        context.statsJob =
            scope.launch {
                session.liveStats.collect { stats ->
                    context.lastSelectedIceRoute = session.lastValidatedIceRoute
                    context.lastSelectedPairType = session.lastValidatedIceRoute?.toConnectionRouteType()
                    updateState { current -> current.copy(visual = current.visual.withNativeStats(stats)) }
                }
            }
        context.protocolJob = scope.launch { observeProtocolMessages(session) }
    }

    private suspend fun observeVideoState(
        session: AndroidWebRtcViewerSession,
        sessionId: SessionId,
    ) {
        session.states.collect { videoState ->
            updateState { current ->
                val presence =
                    if (videoState == VideoSessionState.Streaming) {
                        current.selectedProfileId?.let { id ->
                            HostPresenceTracker.markSeen(current.hostPresence, id, System.currentTimeMillis())
                        } ?: current.hostPresence
                    } else {
                        current.hostPresence
                    }
                current.copy(visual = current.visual.withVideoState(videoState, application), hostPresence = presence)
            }
            when (videoState) {
                VideoSessionState.Streaming -> {
                    transitionRemoteSession("CONNECTED", sessionId) { it.connected(sessionId) }
                    context.plan
                        ?.route
                        ?.type
                        ?.let { routeType -> state.value.selectedProfileId?.let { onProfileConnected(it, routeType) } }
                    context.reconnectAttempt = 0
                    context.iceRestartInFlight = false
                    context.lastStableEpochMillis = System.currentTimeMillis()
                    context.reconnectJob?.cancel()
                    context.reconnectJob = null
                    startSessionLiveness(session, sessionId)
                    startTurnRefresh(session)
                    sendLocalCapabilities(session, sessionId)
                }

                VideoSessionState.Reconnecting -> {
                    context.iceRestartInFlight = true
                    transitionRemoteSession("ICE_RESTART", sessionId) { it.beginIceRestart(sessionId) }
                    scheduleIceRestartProbe(sessionId)
                }

                is VideoSessionState.Failed -> {
                    if (context.iceRestartInFlight && context.liveness.isFresh()) {
                        updateState {
                            it.copy(
                                visual =
                                    it.visual.copy(
                                        message = application.getString(R.string.visual_ice_recovering),
                                    ),
                            )
                        }
                    } else {
                        transitionRemoteSession("RECONNECTING_AFTER_FAILURE", sessionId) { it.reconnecting(sessionId) }
                        context.reconnectJob?.cancel()
                        context.reconnectJob = null
                        scheduleVisualReconnect(sessionId, videoState.reason, 0L)
                    }
                }

                VideoSessionState.Idle,
                VideoSessionState.Negotiating,
                -> {
                    Unit
                }
            }
        }
    }

    private suspend fun observeProtocolMessages(session: AndroidWebRtcViewerSession) {
        session.incomingProtocolMessages.collect { message ->
            if (message is ProtocolMessage.Stats) applyAdaptiveQuality(message)
            if (message is ProtocolMessage.Control) {
                when (val command = message.command) {
                    is ControlCommand.Pong -> {
                        context.liveness.onPong(command.pingSentAtEpochMillis, command.receivedAtEpochMillis)
                    }

                    is ControlCommand.SessionCapabilities -> {
                        handleRemoteCapabilities(command)
                    }

                    is ControlCommand.Error -> {
                        if (command.code.equals("unauthorized", ignoreCase = true) ||
                            command.message.contains("not authorized", ignoreCase = true)
                        ) {
                            state.value.selectedProfileId?.let(dependencies.onHostUnlinked)
                        }
                    }

                    else -> {
                        Unit
                    }
                }
            }
            val clipboardEvent = (message as? ProtocolMessage.Input)?.event as? RemoteInputEvent.ClipboardSync
            if (clipboardEvent != null) handleIncomingClipboard(clipboardEvent.text)
            updateState { current -> current.copy(visual = visualStateForProtocolMessage(current, message)) }
        }
    }

    private fun visualStateForProtocolMessage(
        current: AndroidHomeUiState,
        message: ProtocolMessage,
    ): VisualUiState {
        val updated = current.visual.withProtocolMessage(message)
        if (message !is ProtocolMessage.Monitors) return updated
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId }
        val selectedMonitorId =
            listOfNotNull(updated.selectedMonitorId, profile?.defaultMonitorId)
                .firstOrNull { preferred -> message.monitors.any { monitor -> monitor.id == preferred } }
                ?: message.monitors.firstOrNull { it.primary }?.id
                ?: message.monitors.firstOrNull()?.id
        return updated.copy(selectedMonitorId = selectedMonitorId)
    }

    private suspend fun handleIncomingClipboard(text: String) {
        val allowed =
            state.value.profiles
                .firstOrNull { it.id == state.value.selectedProfileId }
                ?.permissions
                ?.clipboard == true
        if (!allowed) return
        when (clipboardSyncGate.evaluateAutomaticDesktopToAndroid(text)) {
            ClipboardSyncDecision.Allowed -> {
                onIncomingClipboardText(text)
                runCatching { clipboardBridge.write(ClipboardPayload.Text(text)) }.rethrowCancellation()
                updateState {
                    it.copy(clipboard = it.clipboard.copy(text = text, message = application.getString(R.string.clipboard_received)))
                }
            }

            is ClipboardSyncDecision.Blocked -> {
                updateState {
                    it.copy(clipboard = it.clipboard.copy(message = application.getString(R.string.clipboard_blocked)))
                }
            }

            is ClipboardSyncDecision.RequiresConfirmation -> {
                updateState {
                    it.copy(clipboard = it.clipboard.copy(message = application.getString(R.string.clipboard_confirm)))
                }
            }
        }
    }

    private suspend fun transitionRemoteSession(
        event: String,
        sessionId: SessionId,
        transition: suspend (AndroidRemoteSessionCoordinator) -> Unit,
    ) {
        val coordinator = sessionCoordinatorProvider() ?: return
        runCatching { transition(coordinator) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                Log.e("AegisSession", "lifecycle_transition_failed event=$event session=${sessionId.value.take(12)} code=${error.message}")
                updateState {
                    it.copy(visual = it.visual.copy(message = application.getString(R.string.visual_reconnect_failed)))
                }
            }
    }

    private suspend fun applyAdaptiveQuality(message: ProtocolMessage.Stats) {
        val controller = context.adaptiveQualityController ?: return
        val plan = context.plan ?: return
        val profile = state.value.profiles.firstOrNull { it.id == state.value.selectedProfileId }
        val mode = state.value.visual.qualityMode
        val monitorId = profile?.defaultMonitorId ?: plan.videoConfig.monitorId
        runCatching { controller.evaluateAndApply(mode, message.stats, monitorId) }
            .onSuccess { config ->
                if (config != null) {
                    updateState {
                        it.copy(
                            visual = it.visual.copy(video = config.label(), message = application.getString(R.string.visual_adaptive_applied)),
                        )
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                updateState {
                    it.copy(
                        visual = it.visual.copy(message = application.getString(R.string.visual_quality_failed)),
                    )
                }
            }
    }

    private fun startSessionLiveness(
        session: AndroidWebRtcViewerSession,
        sessionId: SessionId,
    ) {
        context.liveness.reset()
        context.pingJob?.cancel()
        context.pingJob =
            scope.launch {
                while (true) {
                    val now = System.currentTimeMillis()
                    if (context.liveness.shouldSendPing(now)) {
                        context.liveness.onPingSent(now)
                        runCatching {
                            session.sendProtocolMessage(
                                ProtocolMessage.Control(sessionId, ControlCommand.Ping(now)),
                            )
                        }.rethrowCancellation()
                    }
                    if (context.liveness.pingTimedOut(System.currentTimeMillis())) {
                        runCatching { session.restartIce("ping-timeout") }.rethrowCancellation()
                        delay(SessionLivenessTracker.DEFAULT_PING_TIMEOUT_MILLIS)
                        if (!context.liveness.isFresh()) {
                            scheduleVisualReconnect(sessionId, application.getString(R.string.visual_reconnect_failed), 0L)
                            return@launch
                        }
                    }
                    delay(SessionLivenessTracker.DEFAULT_PING_INTERVAL_MILLIS)
                }
            }
    }

    private fun startTurnRefresh(session: AndroidWebRtcViewerSession) {
        context.turnRefreshJob?.cancel()
        val turn = context.plan?.ice?.turnConfig ?: return
        val expiresAt = turn.expiresAtEpochMillis ?: return
        val issuedAt = System.currentTimeMillis()
        context.turnRefreshJob =
            scope.launch {
                delay(TurnCredentialRefreshPolicy.delayUntilRefreshMillis(issuedAt, issuedAt, expiresAt))
                val refreshed =
                    runCatching { turnConfigProvider.getTurnConfig() }
                        .rethrowCancellation()
                        .getOrNull()
                if (refreshed?.turnConfig == null) {
                    scheduleVisualReconnect(
                        context.sessionId ?: return@launch,
                        application.getString(R.string.visual_turn_refresh_failed),
                        0L,
                    )
                    return@launch
                }
                runCatching { session.applyIceServersAndRestartIce(refreshed) }
                    .rethrowCancellation()
                    .onFailure { error ->
                        scheduleVisualReconnect(
                            context.sessionId ?: return@onFailure,
                            application.getString(R.string.visual_turn_refresh_failed),
                            0L,
                        )
                    }
            }
    }

    private fun sendLocalCapabilities(
        session: AndroidWebRtcViewerSession,
        sessionId: SessionId,
    ) {
        context.capabilityJob?.cancel()
        context.capabilityJob =
            scope.launch {
                val local = localSessionProtocolCapabilities()
                runCatching {
                    session.sendProtocolMessage(
                        ProtocolMessage.Control(
                            sessionId,
                            ControlCommand.SessionCapabilities(local.protocolRev, local.features),
                        ),
                    )
                }.rethrowCancellation()
            }
    }

    private fun handleRemoteCapabilities(command: ControlCommand.SessionCapabilities) {
        when (
            val result =
                context.capabilityHandshake.evaluate(
                    SessionProtocolCapabilities(command.protocolRev, command.features),
                )
        ) {
            is ProtocolCapabilityHandshakeResult.Compatible -> {
                context.protocolSkew = false
            }

            is ProtocolCapabilityHandshakeResult.Skew -> {
                context.protocolSkew = true
                updateState {
                    it.copy(
                        visual =
                            it.visual.copy(
                                streaming = false,
                                videoState = "Failed",
                                message = application.getString(R.string.status_protocol_mismatch),
                            ),
                    )
                }
                context.reconnectJob?.cancel()
                context.reconnectJob = null
            }
        }
    }

    private fun scheduleIceRestartProbe(sessionId: SessionId) {
        if (context.reconnectJob?.isActive == true) return
        context.reconnectJob =
            scope.launch {
                delay(VISUAL_ICE_RESTART_GRACE_MILLIS)
                if (context.sessionId != sessionId || context.session == null) return@launch
                val session = context.session ?: return@launch
                if (context.liveness.isFresh()) {
                    context.iceRestartInFlight = false
                    return@launch
                }
                val pingAt = System.currentTimeMillis()
                context.liveness.onPingSent(pingAt)
                runCatching {
                    session.sendProtocolMessage(ProtocolMessage.Control(sessionId, ControlCommand.Ping(pingAt)))
                }.rethrowCancellation()
                delay(SessionLivenessTracker.DEFAULT_PING_TIMEOUT_MILLIS)
                if (context.sessionId != sessionId || context.liveness.isFresh()) {
                    context.iceRestartInFlight = false
                    return@launch
                }
                scheduleVisualReconnect(sessionId, application.getString(R.string.visual_reconnect_failed), 0L)
            }
    }

    private class VisualSessionPreparationException(
        val payload: String,
        message: String,
    ) : IllegalStateException(message)
}

private fun dev.aegis.remote.core.model.VideoConfig.forQualityMode(mode: QualityMode): dev.aegis.remote.core.model.VideoConfig =
    when (mode) {
        QualityMode.LowLatency -> copy(width = 1280, height = 720, fps = 60, bitrateKbps = 4_000, qualityMode = mode)
        QualityMode.Balanced -> copy(width = 1920, height = 1080, fps = 30, bitrateKbps = 8_000, qualityMode = mode)
        QualityMode.QualityFirst -> copy(width = 2560, height = 1440, fps = 60, bitrateKbps = 14_000, qualityMode = mode)
        QualityMode.BatterySaver -> copy(width = 1280, height = 720, fps = 24, bitrateKbps = 2_000, qualityMode = mode)
        QualityMode.RelaySaver -> copy(width = 960, height = 540, fps = 30, bitrateKbps = 1_500, qualityMode = mode)
    }
