package dev.aegis.remote.desktop.agent

import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopManualConnectionInfoProviderTest {
    @Test
    fun exposesLanHostUsernameAndRealSha256FingerprintFromPublicHostKey() {
        val directory = Files.createTempDirectory("aegis-openssh-host-key-test")
        val publicKey = directory.resolve("ssh_host_ed25519_key.pub")
        val keyBlob = "test-only-openssh-public-host-key".encodeToByteArray()
        Files.writeString(
            publicKey,
            "ssh-ed25519 ${Base64.getEncoder().encodeToString(keyBlob)} desktop@test\n",
        )
        val provider =
            SystemDesktopManualConnectionInfoProvider(
                hostKeyCandidates = listOf(publicKey),
                usernameProvider = { "Ian" },
            )

        val info = provider.inspect("192.168.1.92")

        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(keyBlob)
        val expectedFingerprint = "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(expectedDigest)}"
        assertEquals(ManualConnectionValueStatus.Available, info.lanHost.status)
        assertEquals("192.168.1.92", info.lanHost.value)
        assertEquals("Ian", info.localUsername.value)
        assertEquals(expectedFingerprint, info.sshHostKeyFingerprint.value)
        assertEquals("ssh-ed25519", info.sshHostKeyAlgorithm)
    }

    @Test
    fun reportsUnavailableWithoutInventingAHostFingerprint() {
        val provider =
            SystemDesktopManualConnectionInfoProvider(
                hostKeyCandidates = listOf(Files.createTempDirectory("aegis-no-key").resolve("missing.pub")),
                usernameProvider = { null },
            )

        val info = provider.inspect(null)

        assertEquals(ManualConnectionValueStatus.NotAvailable, info.lanHost.status)
        assertEquals(ManualConnectionValueStatus.NotAvailable, info.localUsername.status)
        assertEquals(ManualConnectionValueStatus.NotAvailable, info.sshHostKeyFingerprint.status)
        assertEquals("SSH_HOST_KEY_NOT_FOUND", info.sshHostKeyFingerprint.detailCode)
        assertTrue(info.sshHostKeyFingerprint.value == null)
    }

    @Test
    fun structuredPairingLogCanBeLocalizedWithoutParsingItsMessage() {
        val entry =
            AgentLogEntry(
                timestampEpochMillis = 1L,
                level = "info",
                message = "legacy fallback",
                eventCode = AgentLogEventCode.PairingRequestReceived,
                context = mapOf("deviceName" to "OnePlus"),
            )

        assertEquals("Solicitud de vinculación recibida de OnePlus", entry.localizedMessage("es-MX"))
        assertEquals("Pairing request received from OnePlus", entry.localizedMessage("en-US"))
    }

    @Test
    fun structuredRelayAndAutostartMessagesFollowSelectedLanguage() {
        val state =
            DesktopAgentState(
                relayConfigurationMessage = "legacy relay fallback",
                relayConfigurationMessageCode = RelayConfigurationMessageCode.ValidationFailed,
                relayConfigurationMessageContext = mapOf("reason" to RelayValidationReason.UrlRequired.name),
                autostartMessage = "legacy autostart fallback",
                autostartMessageCode = AutostartMessageCode.Unsupported,
            )

        assertEquals("La URL del relay es obligatoria", state.localizedRelayConfigurationMessage("es-MX"))
        assertEquals("Relay URL is required", state.localizedRelayConfigurationMessage("en-US"))
        assertEquals("El inicio automático no está disponible en esta plataforma", state.localizedAutostartMessage("es"))
        assertEquals("Autostart is not available on this platform", state.localizedAutostartMessage("en"))
    }

    @Test
    fun relayActivityEventIsLocalizedWithoutSpanglish() {
        val connectionEntry =
            AgentLogEntry(
                timestampEpochMillis = 1L,
                level = "info",
                message = "legacy English fallback",
                eventCode = AgentLogEventCode.RelayConnectionChanged,
                context = mapOf("action" to "not-configured"),
            )
        val configurationEntry =
            AgentLogEntry(
                timestampEpochMillis = 2L,
                level = "info",
                message = "legacy English fallback",
                eventCode = AgentLogEventCode.RelayConfigurationChanged,
                context = mapOf("action" to "remote-access-changed", "enabled" to "true"),
            )
        val sessionEntry =
            AgentLogEntry(
                timestampEpochMillis = 3L,
                level = "info",
                message = "legacy English fallback",
                eventCode = AgentLogEventCode.RelaySessionChanged,
                context = mapOf("action" to "requested", "sessionId" to "session-1", "sourceLabel" to "OnePlus"),
            )

        assertEquals("Relay sin configurar; el acceso local sigue disponible", connectionEntry.localizedMessage("es-MX"))
        assertEquals("Relay not configured; local access remains available", connectionEntry.localizedMessage("en-US"))
        assertEquals("Acceso remoto activado; actualizando el registro", configurationEntry.localizedMessage("es-MX"))
        assertEquals("Remote access enabled; updating registration", configurationEntry.localizedMessage("en-US"))
        assertEquals("Solicitud de sesión relay recibida de OnePlus", sessionEntry.localizedMessage("es-MX"))
        assertEquals("Relay session requested by OnePlus", sessionEntry.localizedMessage("en-US"))
    }

    @Test
    fun deletionRefusedOutcomesAreLocalizedWithoutEnglishTokens() {
        val entry =
            AgentLogEntry(
                timestampEpochMillis = 4L,
                level = "warn",
                message = "Refused to delete active device record phone-1; revoke it first",
                eventCode = AgentLogEventCode.DeviceRecordDeletionRefused,
                context = mapOf("outcome" to "MustRevokeFirst"),
            )
        assertEquals("No se eliminó el registro: primero revoca el acceso", entry.localizedMessage("es-MX"))
        assertEquals("Device record was not deleted; revoke access first", entry.localizedMessage("en-US"))
    }
}
