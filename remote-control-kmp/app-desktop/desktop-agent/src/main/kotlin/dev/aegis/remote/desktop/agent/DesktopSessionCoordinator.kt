package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.AEGIS_P256_AESGCM_V1
import dev.aegis.remote.core.model.DeviceAuthorization
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.DeviceTrustTransport
import dev.aegis.remote.core.model.RelayConfig
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.RemoteDeviceId
import dev.aegis.remote.core.model.WakeOnLanCapability
import dev.aegis.remote.core.monitor.MonitorProvider
import dev.aegis.remote.core.pairing.DeviceTrustStore
import dev.aegis.remote.core.pairing.LocalPairedProfile
import dev.aegis.remote.core.pairing.LocalPairingQrPayload
import dev.aegis.remote.core.pairing.localProtocolSessionProofPayload
import dev.aegis.remote.core.relay.RelayDeviceEvent
import dev.aegis.remote.core.security.KeyRotationReason
import dev.aegis.remote.core.storage.AppSettingsRepository
import dev.aegis.remote.core.storage.RelayConfigRepository
import dev.aegis.remote.core.webrtc.VideoSessionState
import dev.aegis.remote.desktop.clipboard.DesktopClipboardBridgeFactory
import dev.aegis.remote.desktop.input.DesktopRemoteInputExecutorFactory
import dev.aegis.remote.desktop.webrtc.DesktopNativeWebRtcSenderSession
import dev.aegis.remote.desktop.webrtc.DesktopProtocolChannelBridge
import dev.aegis.remote.desktop.webrtc.DesktopProtocolMessageHandler
import dev.aegis.remote.desktop.webrtc.DesktopProtocolSignalingClient
import dev.aegis.remote.desktop.webrtc.DesktopProtocolTelemetryBridge
import dev.aegis.remote.desktop.webrtc.DesktopResolvedTurnCredentials
import dev.aegis.remote.desktop.webrtc.DesktopWebRtcMonitorProvider
import dev.aegis.remote.protocol.ControlCommand
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageChannel
import dev.aegis.remote.protocol.openLanE2eeProtocolMessageChannel
import dev.aegis.remote.relayclient.RelayIdentityRotationPendingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class DesktopSessionDependencies(
    val state: StateFlow<DesktopAgentState>,
    val scope: CoroutineScope,
    val monitorProvider: MonitorProvider,
    val inputExecutorFactory: DesktopRemoteInputExecutorFactory,
    val clipboardBridgeFactory: DesktopClipboardBridgeFactory,
    val clock: () -> Long,
    val relayProtocolBridges: ConcurrentHashMap<String, DesktopProtocolChannelBridge>,
    val relayTelemetryBridges: ConcurrentHashMap<String, DesktopProtocolTelemetryBridge>,
    val remoteInputHandlers: ConcurrentHashMap<String, DesktopProtocolMessageHandler>,
    val videoStateJobs: ConcurrentHashMap<String, Job>,
    val localProtocolDeviceByKey: ConcurrentHashMap<String, String>,
    val remoteSessionCoordinatorProvider: () -> DesktopRemoteSessionCoordinator?,
    val updateState: ((DesktopAgentState) -> DesktopAgentState) -> Unit,
)

internal class DesktopSessionCoordinator(
    private val dependencies: DesktopSessionDependencies,
) {
    private val state = dependencies.state
    private val scope = dependencies.scope
    private val monitorProvider = dependencies.monitorProvider
    private val inputExecutorFactory = dependencies.inputExecutorFactory
    private val clipboardBridgeFactory = dependencies.clipboardBridgeFactory
    private val clock = dependencies.clock
    private val relayProtocolBridges = dependencies.relayProtocolBridges
    private val relayTelemetryBridges = dependencies.relayTelemetryBridges
    private val remoteInputHandlers = dependencies.remoteInputHandlers
    private val videoStateJobs = dependencies.videoStateJobs
    private val localProtocolDeviceByKey = dependencies.localProtocolDeviceByKey
    private val remoteSessionCoordinatorProvider = dependencies.remoteSessionCoordinatorProvider
    private val updateState = dependencies.updateState

    suspend fun openProtocolChannel(
        sessionId: dev.aegis.remote.core.model.SessionId,
        permissions: dev.aegis.remote.core.model.DevicePermissions,
        channel: ProtocolMessageChannel,
        mapKey: String,
        label: String,
        turnCredentialResolver: suspend (dev.aegis.remote.core.model.TurnConfig) -> DesktopResolvedTurnCredentials?,
    ): Job {
        relayTelemetryBridges.remove(mapKey)?.let { previous ->
            previous.stopStatsForwarding()
            previous.stopClipboardForwarding()
        }
        videoStateJobs.remove(mapKey)?.cancel()
        relayProtocolBridges.remove(mapKey)?.stop()
        val inputSelection = inputExecutorFactory.create()
        val clipboardSelection = clipboardBridgeFactory.create()
        val signalingClient = DesktopProtocolSignalingClient(sessionId, channel)
        val videoSession =
            DesktopNativeWebRtcSenderSession(
                sessionId = sessionId,
                signaling = signalingClient,
                turnCredentialResolver = turnCredentialResolver,
                scope = scope,
                clock = clock,
            )
        val handler =
            DesktopProtocolMessageHandler(
                sessionId = sessionId,
                permissions = permissions,
                inputExecutor = inputSelection.executor,
                clipboardBridge = clipboardSelection.bridge,
                videoSession = videoSession,
                inputEnabled = { state.value.remoteInputEnabled },
                clock = clock,
                monitorProvider = monitorProvider,
            )
        videoSession.bindProtocolProcessor(handler::handle, handler::close)
        val bridge =
            DesktopProtocolChannelBridge(
                channel = channel,
                scope = scope,
                processor = handler::handle,
                signalingProcessor = signalingClient::receive,
                onClosed = { handleProtocolChannelClosed(sessionId, mapKey, handler, videoSession) },
            )
        val telemetryBridge =
            DesktopProtocolTelemetryBridge(
                sessionId = sessionId,
                monitorProvider = monitorProvider,
                videoSession = videoSession,
                channel = channel,
                scope = scope,
                clipboardBridge = clipboardSelection.bridge.takeIf { permissions.clipboard },
                channelProvider = { videoSession.activeProtocolChannel() ?: channel },
                failureReporter = { failure ->
                    updateState { current ->
                        current.withLog(
                            now = clock(),
                            level = "error",
                            message = "${failure.code} ${failure.summary}",
                            context = mapOf("correlationId" to failure.correlationId, "stage" to failure.stage),
                        )
                    }
                },
            )
        relayProtocolBridges[mapKey] = bridge
        relayTelemetryBridges[mapKey] = telemetryBridge
        remoteInputHandlers[mapKey] = handler
        videoStateJobs[mapKey] =
            scope.launch {
                videoSession.states.collect { videoState ->
                    val description =
                        when (videoState) {
                            VideoSessionState.Idle -> "Idle"
                            VideoSessionState.Negotiating -> "Negotiating"
                            VideoSessionState.Streaming -> "Streaming"
                            VideoSessionState.Reconnecting -> "Reconnecting"
                            is VideoSessionState.Failed -> "Failed: ${videoState.reason}"
                        }
                    updateState { current ->
                        current.withLog(
                            clock(),
                            if (videoState is VideoSessionState.Failed) "error" else "info",
                            "WebRTC state for ${sessionId.value}: $description",
                        )
                    }
                }
            }
        val bridgeJob = bridge.start()
        telemetryBridge.publishMonitorSnapshot()
        telemetryBridge.startStatsForwarding()
        if (state.value.clipboardSyncEnabled) {
            telemetryBridge.startClipboardForwarding()
        }
        updateState {
            it
                .withLog(clock(), "info", "Opened $label control channel for ${sessionId.value}")
                .withLog(
                    clock(),
                    "info",
                    "Desktop backends: capture=NativeWebRtcDesktopSource, input=${inputSelection.backend}, clipboard=${clipboardSelection.backend}",
                ).withLog(clock(), "info", "Published desktop monitor telemetry for ${sessionId.value}")
        }
        return bridgeJob
    }

    private suspend fun handleProtocolChannelClosed(
        sessionId: dev.aegis.remote.core.model.SessionId,
        mapKey: String,
        handler: DesktopProtocolMessageHandler,
        videoSession: DesktopNativeWebRtcSenderSession,
    ) {
        runCatching { handler.close() }
            .onFailure { error ->
                if (error is CancellationException) throw error
                updateState { current ->
                    current.withLog(
                        now = clock(),
                        level = "error",
                        message = "INP-1007 Failed to release held input while closing ${sessionId.value}: ${error.message}",
                        error = error,
                    )
                }
            }
        videoSession.stop()
        videoStateJobs.remove(mapKey)?.cancel()
        relayTelemetryBridges.remove(mapKey)?.let { telemetry ->
            telemetry.stopStatsForwarding()
            telemetry.stopClipboardForwarding()
        }
        relayProtocolBridges.remove(mapKey)
        remoteInputHandlers.remove(mapKey, handler)
        localProtocolDeviceByKey.remove(mapKey)
        if (mapKey.startsWith("relay:")) {
            scope.launch {
                runCatching { remoteSessionCoordinatorProvider()?.closed(sessionId) }
                    .onFailure { error -> if (error is CancellationException) throw error }
            }
        }
    }
}
