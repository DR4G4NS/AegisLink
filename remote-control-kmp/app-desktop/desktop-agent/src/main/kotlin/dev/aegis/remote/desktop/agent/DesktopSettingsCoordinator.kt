package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.AEGIS_P256_AESGCM_V1
import dev.aegis.remote.core.model.DeviceAuthorization
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.DeviceTrustTransport
import dev.aegis.remote.core.model.MonitorInfo
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

internal data class DesktopStartupSettings(
    val clipboardSyncEnabled: Boolean,
    val storedRelayConfig: RelayConfig?,
    val autostart: DesktopAutostartStatus,
    val monitors: List<MonitorInfo>,
    val capabilities: DesktopCapabilityReport,
    val manualConnectionInfo: DesktopManualConnectionInfo,
    val openSshAvailable: Boolean,
    val warnings: List<String>,
)

internal data class DesktopSettingsDependencies(
    val scope: CoroutineScope,
    val settingsRepository: AppSettingsRepository,
    val relayConfigRepository: RelayConfigRepository,
    val monitorProvider: MonitorProvider,
    val capabilityDetector: DesktopCapabilityDetector,
    val manualConnectionInfoProvider: DesktopManualConnectionInfoProvider,
    val openSshProvisioner: AegisOpenSshManager?,
    val autostartManager: DesktopAutostartManager,
    val clock: () -> Long,
    val telemetryBridges: () -> Collection<DesktopProtocolTelemetryBridge>,
    val inputHandlers: () -> Collection<DesktopProtocolMessageHandler>,
    val updateState: ((DesktopAgentState) -> DesktopAgentState) -> Unit,
)

internal class DesktopSettingsCoordinator(
    private val dependencies: DesktopSettingsDependencies,
) {
    private val scope = dependencies.scope
    private val settingsRepository = dependencies.settingsRepository
    private val relayConfigRepository = dependencies.relayConfigRepository
    private val monitorProvider = dependencies.monitorProvider
    private val capabilityDetector = dependencies.capabilityDetector
    private val manualConnectionInfoProvider = dependencies.manualConnectionInfoProvider
    private val openSshProvisioner = dependencies.openSshProvisioner
    private val autostartManager = dependencies.autostartManager
    private val clock = dependencies.clock
    private val telemetryBridges = dependencies.telemetryBridges
    private val inputHandlers = dependencies.inputHandlers
    private val updateState = dependencies.updateState

    suspend fun loadStartupSettings(pairingUrl: String): DesktopStartupSettings {
        val warnings = mutableListOf<String>()
        val clipboardSyncEnabled = loadClipboardSyncSetting(warnings)
        val storedRelayConfig = loadRelayConfig(warnings)
        val autostart = loadAutostartStatus(warnings)
        val monitors = loadMonitors(warnings)
        val capabilities = loadCapabilities(warnings)
        val preferredLanHost = preferredLanHost(pairingUrl)
        val managedOpenSsh = loadManagedOpenSsh(warnings)
        val discoveredManualConnectionInfo = loadManualConnectionInfo(preferredLanHost, warnings)
        val manualConnectionInfo = mergeOpenSshConnectionInfo(discoveredManualConnectionInfo, managedOpenSsh)
        val openSshAvailable = isOpenSshAvailable(managedOpenSsh, manualConnectionInfo)
        return DesktopStartupSettings(
            clipboardSyncEnabled = clipboardSyncEnabled,
            storedRelayConfig = storedRelayConfig,
            autostart = autostart,
            monitors = monitors,
            capabilities = capabilities,
            manualConnectionInfo = manualConnectionInfo,
            openSshAvailable = openSshAvailable,
            warnings = warnings,
        )
    }

    private suspend fun loadClipboardSyncSetting(warnings: MutableList<String>): Boolean =
        runCatching { settingsRepository.getBoolean(DESKTOP_CLIPBOARD_SYNC_ENABLED_KEY, default = false) }
            .getOrElse { error ->
                warnings += "Clipboard setting unavailable: ${error.message ?: error.javaClass.simpleName}"
                false
            }

    private suspend fun loadRelayConfig(warnings: MutableList<String>): RelayConfig? =
        runCatching { relayConfigRepository.getRelayConfig() }
            .getOrElse { error ->
                warnings += "Relay setting unavailable: ${error.message ?: error.javaClass.simpleName}"
                null
            }

    private suspend fun loadAutostartStatus(warnings: MutableList<String>): DesktopAutostartStatus =
        runCatching { autostartManager.status() }
            .getOrElse { error ->
                warnings += "Autostart status unavailable: ${error.message ?: error.javaClass.simpleName}"
                DesktopAutostartStatus(false, false, "Autostart status unavailable")
            }

    private suspend fun loadMonitors(warnings: MutableList<String>): List<MonitorInfo> =
        runCatching { monitorProvider.listMonitors() }
            .getOrElse { error ->
                warnings += "Monitor discovery unavailable: ${error.message ?: error.javaClass.simpleName}"
                emptyList()
            }

    private suspend fun loadCapabilities(warnings: MutableList<String>): DesktopCapabilityReport =
        runCatching { capabilityDetector.detect() }
            .getOrElse { error ->
                warnings += "Desktop capability detection unavailable: ${error.message ?: error.javaClass.simpleName}"
                DesktopCapabilityReport(CapabilityStatus.Unknown, CapabilityStatus.Unknown)
            }

    private fun preferredLanHost(pairingUrl: String): String? = runCatching { URI(pairingUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }

    private suspend fun loadManagedOpenSsh(warnings: MutableList<String>) =
        openSshProvisioner?.let { provisioner ->
            runCatching { provisioner.startAccess() }
                .getOrElse { error ->
                    warnings += "Managed OpenSSH unavailable: ${error.message ?: error.javaClass.simpleName}"
                    null
                }
        }

    private suspend fun loadManualConnectionInfo(
        preferredLanHost: String?,
        warnings: MutableList<String>,
    ): DesktopManualConnectionInfo =
        runCatching { manualConnectionInfoProvider.inspect(preferredLanHost) }
            .getOrElse { error ->
                warnings += "Manual connection data unavailable: ${error.message ?: error.javaClass.simpleName}"
                DesktopManualConnectionInfo(
                    lanHost =
                        preferredLanHost?.let {
                            ManualConnectionValue(it, ManualConnectionValueStatus.Available)
                        } ?: ManualConnectionValue(
                            status = ManualConnectionValueStatus.NotAvailable,
                            detailCode = "LAN_HOST_UNAVAILABLE",
                        ),
                )
            }

    private fun mergeOpenSshConnectionInfo(
        discovered: DesktopManualConnectionInfo,
        managedOpenSsh: AegisOpenSshState?,
    ): DesktopManualConnectionInfo =
        managedOpenSsh?.let { ssh ->
            discovered.copy(
                localUsername = ManualConnectionValue(ssh.authorizedUser, ManualConnectionValueStatus.Available),
                sshHostKeyFingerprint =
                    ssh.hostKeyFingerprint?.let { fingerprint ->
                        ManualConnectionValue(fingerprint, ManualConnectionValueStatus.Available)
                    } ?: ManualConnectionValue(
                        status = ManualConnectionValueStatus.Error,
                        detailCode = "SSH-7318",
                    ),
                sshHostKeyAlgorithm = ssh.hostKeyAlgorithm,
            )
        } ?: discovered

    private fun isOpenSshAvailable(
        managedOpenSsh: AegisOpenSshState?,
        manualConnectionInfo: DesktopManualConnectionInfo,
    ): Boolean =
        managedOpenSsh?.serviceStatus.equals("Running", ignoreCase = true) &&
            manualConnectionInfo.sshHostKeyFingerprint.status == ManualConnectionValueStatus.Available

    fun setClipboardSyncEnabled(enabled: Boolean) {
        updateState {
            it
                .copy(clipboardSyncEnabled = enabled)
                .withLog(clock(), "info", "Desktop-to-phone clipboard sync ${if (enabled) "enabled" else "disabled"}")
        }
        scope.launch {
            runCatching {
                settingsRepository.putBoolean(DESKTOP_CLIPBOARD_SYNC_ENABLED_KEY, enabled)
            }.onFailure { error ->
                updateState {
                    it.withLog(clock(), "error", "Failed to persist clipboard sync setting: ${error.message}")
                }
            }
        }
        val bridges = telemetryBridges()
        if (enabled) {
            bridges.forEach { it.startClipboardForwarding() }
        } else {
            bridges.forEach { it.stopClipboardForwarding() }
        }
    }

    fun setRemoteInputEnabled(enabled: Boolean) {
        updateState {
            it
                .copy(remoteInputEnabled = enabled)
                .withLog(
                    clock(),
                    if (enabled) "info" else "warn",
                    "Remote input ${if (enabled) "resumed" else "paused by local kill switch"}",
                )
        }
        if (!enabled) {
            val handlers = inputHandlers()
            runBlocking {
                withTimeoutOrNull(LOCAL_INPUT_RELEASE_TIMEOUT_MILLIS) {
                    handlers.forEach { handler ->
                        runCatching { handler.close() }
                            .onFailure { error ->
                                updateState { current ->
                                    current.withLog(
                                        now = clock(),
                                        level = "error",
                                        message = "INP-1007 Failed to release held input after local kill switch: ${error.message}",
                                        error = error,
                                    )
                                }
                            }
                    }
                }
            }
        }
    }

    fun setAutostartEnabled(enabled: Boolean) {
        scope.launch {
            runCatching {
                autostartManager.setEnabled(enabled)
            }.onSuccess { status ->
                updateState {
                    it
                        .copy(
                            autostartAvailable = status.available,
                            autostartEnabled = status.enabled,
                            autostartMessage = status.message,
                            autostartMessageCode = status.messageCode,
                            autostartMessageContext = status.messageContext,
                        ).withLog(clock(), "info", "Autostart ${if (status.enabled) "enabled" else "disabled"}")
                }
            }.onFailure { error ->
                updateState {
                    it
                        .copy(
                            autostartMessage = "Failed to update autostart: ${error.message}",
                            autostartMessageCode = AutostartMessageCode.UpdateFailed,
                            autostartMessageContext = mapOf("errorType" to error.javaClass.simpleName),
                        ).withLog(clock(), "error", "Failed to update autostart: ${error.message}")
                }
            }
        }
    }
}
