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

internal class DesktopRelayCoordinator(
    private val scope: CoroutineScope,
    private val connector: DesktopRelayConnector,
    private val clock: () -> Long,
    private val reconnectionPolicy: DesktopRelayReconnectPolicy,
    private val remoteAccessEnabled: () -> Boolean,
    private val displayName: () -> String,
    private val onEvent: (RelayDeviceEvent) -> Unit,
    private val updateState: ((DesktopAgentState) -> DesktopAgentState) -> Unit,
) {
    private var eventsJob: Job? = null

    fun stop() {
        eventsJob?.cancel()
        eventsJob = null
    }

    fun start() {
        eventsJob?.cancel()
        eventsJob =
            scope.launch {
                var attempt = 0
                while (true) {
                    val streamResult =
                        runCatching {
                            connector.openDeviceEvents().collect { event ->
                                attempt = 0
                                onEvent(event)
                            }
                        }
                    if (streamResult.isSuccess) {
                        return@launch
                    }
                    val error = checkNotNull(streamResult.exceptionOrNull())
                    if (error is CancellationException) {
                        throw error
                    }
                    updateState {
                        it
                            .copy(relayConnected = false)
                            .withLog(
                                clock(),
                                "warn",
                                "Relay event stream lost: ${error.message}; reconnecting",
                                AgentLogEventCode.RelayConnectionChanged,
                                mapOf("action" to "event-stream-lost", "attempt" to attempt.toString()),
                                error,
                            )
                    }
                    if (!reconnectionPolicy.shouldRetry(attempt)) {
                        updateState {
                            it.withLog(
                                clock(),
                                "error",
                                "Relay event reconnection exhausted",
                                AgentLogEventCode.RelayConnectionChanged,
                                mapOf("action" to "reconnect-exhausted", "attempts" to attempt.toString()),
                            )
                        }
                        return@launch
                    }
                    delay(reconnectionPolicy.nextDelayMillis(attempt))
                    attempt += 1
                    val registration =
                        runCatching {
                            connector.register(
                                displayName = displayName(),
                                remoteAccessEnabled = remoteAccessEnabled(),
                            )
                        }
                    val registrationError = registration.exceptionOrNull()
                    if (registrationError is CancellationException) {
                        throw registrationError
                    }
                    if (registration.isSuccess) {
                        updateState {
                            it
                                .copy(relayConnected = true, remoteAccessEnabled = remoteAccessEnabled())
                                .withLog(
                                    clock(),
                                    "info",
                                    "Relay event stream re-registered",
                                    AgentLogEventCode.RelayConnectionChanged,
                                    mapOf("action" to "reregistered"),
                                )
                        }
                    } else {
                        updateState {
                            it.withLog(
                                clock(),
                                "warn",
                                "Relay re-registration failed: ${registrationError?.message}",
                                AgentLogEventCode.RelayConnectionChanged,
                                mapOf("action" to "reregistration-failed", "attempt" to attempt.toString()),
                                registrationError,
                            )
                        }
                    }
                }
            }
    }
}
