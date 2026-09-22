package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.RelayConfig
import dev.aegis.remote.core.monitor.MonitorProvider
import dev.aegis.remote.core.pairing.DeviceTrustStore
import dev.aegis.remote.core.security.LocalDeviceIdentity
import dev.aegis.remote.core.storage.AppSettingsRepository
import dev.aegis.remote.core.storage.RelayConfigRepository
import dev.aegis.remote.desktop.clipboard.DesktopClipboardBridgeFactory
import dev.aegis.remote.desktop.input.DesktopRemoteInputExecutorFactory
import kotlinx.coroutines.CoroutineScope

/** All application-bound collaborators required by [DesktopAgent]. */
data class DesktopAgentDependencies(
    val trustStore: DeviceTrustStore,
    val relayConnector: DesktopRelayConnector?,
    val monitorProvider: MonitorProvider,
    val capabilityDetector: DesktopCapabilityDetector,
    val inputExecutorFactory: DesktopRemoteInputExecutorFactory,
    val clipboardBridgeFactory: DesktopClipboardBridgeFactory,
    val settingsRepository: AppSettingsRepository,
    val relayConfigRepository: RelayConfigRepository,
    val relayConnectorFactory: (RelayConfig) -> DesktopRelayConnector,
    val autostartManager: DesktopAutostartManager,
    val manualConnectionInfoProvider: DesktopManualConnectionInfoProvider,
    val openSshProvisioner: AegisOpenSshManager?,
    val clock: () -> Long,
    val localProtocolAuthenticator: LocalProtocolAuthenticator,
    val relayReconnectionPolicy: DesktopRelayReconnectPolicy,
    val scope: CoroutineScope,
)

/** Manual desktop composition root. Tests can supply the same shape with fakes. */
object DesktopAgentFactory {
    fun create(
        pairingServer: LocalPairingServer,
        dependencies: DesktopAgentDependencies,
    ): DesktopAgent = DesktopAgent(pairingServer, dependencies)

    fun production(
        pairingServer: LocalPairingServer,
        openSshProvisioner: AegisOpenSshManager? = null,
    ): DesktopAgent {
        val settingsRepository = FileDesktopAppSettingsRepository()
        return create(
            pairingServer = pairingServer,
            dependencies =
                DesktopAgentDependencies(
                    trustStore = FileDeviceTrustStore(),
                    relayConnector = KtorDesktopRelayConnector.fromEnvironment(),
                    monitorProvider =
                        dev.aegis.remote.desktop.webrtc
                            .DesktopWebRtcMonitorProvider(),
                    capabilityDetector =
                        DesktopPlatformCapabilityDetector(
                            linuxPreflightDetector = { LinuxPreflightDetector().detect() },
                            windowsPreflightDetector = { WindowsPreflightDetector().detect() },
                        ),
                    inputExecutorFactory = DesktopRemoteInputExecutorFactory(),
                    clipboardBridgeFactory = DesktopClipboardBridgeFactory(),
                    settingsRepository = settingsRepository,
                    relayConfigRepository =
                        (settingsRepository as? RelayConfigRepository) ?: FileDesktopAppSettingsRepository(),
                    relayConnectorFactory = { config ->
                        KtorDesktopRelayConnector(
                            relayUrl = config.relayUrl,
                            relayDeviceId = config.deviceId,
                            remoteAccessEnabled = config.enabled,
                        )
                    },
                    autostartManager = UserDesktopAutostartManager(),
                    manualConnectionInfoProvider = SystemDesktopManualConnectionInfoProvider(),
                    openSshProvisioner = openSshProvisioner,
                    clock = { System.currentTimeMillis() },
                    localProtocolAuthenticator = LocalProtocolAuthenticator(),
                    relayReconnectionPolicy = BoundedDesktopRelayReconnectPolicy(),
                    scope = serviceScope(),
                ),
        )
    }
}

private fun serviceScope(): CoroutineScope =
    kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )
