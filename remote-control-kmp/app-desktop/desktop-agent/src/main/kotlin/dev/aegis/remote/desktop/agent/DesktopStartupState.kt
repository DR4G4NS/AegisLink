package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.DeviceAuthorization
import dev.aegis.remote.core.pairing.LocalPairingQrPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Maps completed startup probes to visible state without performing platform operations. */
internal fun DesktopAgentState.withStartedPairingSession(
    pairingSession: LocalPairingSession,
    startupSettings: DesktopStartupSettings,
    authorizedDevices: List<DeviceAuthorization>,
    activeRelayConnector: DesktopRelayConnector?,
    remoteAccessEnabled: Boolean,
    pairingQrPayload: String,
    clock: () -> Long,
): DesktopAgentState {
    val storedRelayConfig = startupSettings.storedRelayConfig
    val autostart = startupSettings.autostart
    val capabilities = startupSettings.capabilities
    val startedState =
        copy(
            pairingServerRunning = true,
            pairingUrl = pairingSession.url,
            pairingCode = pairingSession.pairingCode,
            agentFingerprint = pairingSession.agentFingerprint,
            pairingQrPayload = pairingQrPayload,
            pairingQrExpiresAtEpochMillis = pairingSession.expiresAtEpochMillis,
            openSshAvailable = startupSettings.openSshAvailable,
            manualConnectionInfo = startupSettings.manualConnectionInfo,
            captureCapability = capabilities.capture,
            inputCapability = capabilities.input,
            linuxPreflight = capabilities.linuxPreflight,
            windowsPreflight = capabilities.windowsPreflight,
            monitors = startupSettings.monitors,
            authorizedDevices = authorizedDevices,
            clipboardSyncEnabled = startupSettings.clipboardSyncEnabled,
            relayUrl = activeRelayConnector?.relayUrl ?: storedRelayConfig?.relayUrl,
            relayDeviceId = storedRelayConfig?.deviceId?.value,
            remoteAccessEnabled = remoteAccessEnabled,
            relayConfigurationMessage =
                if (activeRelayConnector == null) {
                    "Configura un relay para habilitar el acceso remoto"
                } else {
                    "Configuración de relay cargada"
                },
            relayConfigurationMessageCode =
                if (activeRelayConnector == null) {
                    RelayConfigurationMessageCode.NotConfigured
                } else {
                    RelayConfigurationMessageCode.Loaded
                },
            autostartAvailable = autostart.available,
            autostartEnabled = autostart.enabled,
            autostartMessage = autostart.message,
            autostartMessageCode = autostart.messageCode,
            autostartMessageContext = autostart.messageContext,
        ).withLog(
            now = clock(),
            level = "info",
            message = "Local pairing server started at ${pairingSession.url}",
            eventCode = AgentLogEventCode.PairingServerStarted,
            context =
                mapOf(
                    "pairingUrl" to pairingSession.url,
                    "candidateCount" to pairingSession.urls.size.toString(),
                ),
        )
    return startupSettings.warnings.fold(startedState) { current, warning -> current.withLog(clock(), "warn", warning) }
}

internal fun LocalPairingSession.toQrPayloadJson(json: Json): String =
    PairingQrTransport.encode(
        json.encodeToString(
            LocalPairingQrPayload(
                version = 3,
                pairingUrl = url,
                pairingUrls = urls,
                pairingCode = pairingCode,
                agentFingerprint = agentFingerprint,
                pairingSecret = pairingSecret,
                tokenId = tokenId,
                issuedAtEpochMillis = issuedAtEpochMillis,
                expiresAtEpochMillis = expiresAtEpochMillis,
                hostIdentity = hostIdentity,
                signature = hostSignature,
            ),
        ),
    )
