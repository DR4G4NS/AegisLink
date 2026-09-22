package dev.aegis.remote.core.pairing

import dev.aegis.remote.core.model.AuthMethod
import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.HostAddress
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LanRediscoveryPolicyTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun dropsSelfFingerprint() {
        val decision =
            LanRediscoveryPolicy.decide(
                profile = profile(),
                announce = announce(fingerprint = "SHA256:self"),
                localFingerprint = "SHA256:self",
                expectedTlsPin = "pin",
                tlsPinVerified = true,
            )
        assertEquals(LanRediscoveryDecision.DropSelf, decision)
    }

    @Test
    fun pinMismatchDoesNotUpdateProfile() {
        val decision =
            LanRediscoveryPolicy.decide(
                profile = profile(pin = "expected-pin"),
                announce = announce(tlsPin = "other-pin", pairingUrl = "https://192.168.1.50:48291"),
                localFingerprint = "phone",
                expectedTlsPin = "expected-pin",
                tlsPinVerified = false,
            )
        assertIs<LanRediscoveryDecision.PinMismatch>(decision)
    }

    @Test
    fun verifiedPinUpdatesLocalHost() {
        val decision =
            LanRediscoveryPolicy.decide(
                profile = profile(pin = "expected-pin"),
                announce = announce(tlsPin = "expected-pin", pairingUrl = "https://192.168.1.50:48291"),
                localFingerprint = "phone",
                expectedTlsPin = "expected-pin",
                tlsPinVerified = true,
            )
        val update = assertIs<LanRediscoveryDecision.UpdateLocalHost>(decision)
        assertEquals("192.168.1.50", update.host.host)
        assertEquals(48291, update.host.port)
    }

    @Test
    fun revokedAndMissingDevicesMarkTheHostUnlinked() {
        assertTrue(LanAuthorizationStatus.REVOKED.marksHostUnlinked())
        assertTrue(LanAuthorizationStatus.NOT_FOUND.marksHostUnlinked())
        assertFalse(LanAuthorizationStatus.ACTIVE.marksHostUnlinked())
        assertFalse(LanAuthorizationStatus.UNKNOWN.marksHostUnlinked())
    }

    @Test
    fun missingAuthorizationStatusDefaultsToUnknown() {
        val decoded =
            json.decodeFromString<LanRegisterResponse>(
                """{"fingerprint":"f","tlsPin":"p","port":48291,"pairingUrl":"https://192.168.1.10:48291","host":"192.168.1.10"}""",
            )
        assertEquals(LanAuthorizationStatus.UNKNOWN, decoded.authorizationStatus)
    }

    private fun profile(pin: String = "pin") =
        DeviceProfile(
            id = DeviceProfileId("profile-1"),
            displayName = "PC",
            localHost = HostAddress("192.168.1.10", 48291),
            sshPort = 22,
            username = "ian",
            authMethod = AuthMethod.Password,
            localAgentCertificateFingerprint = pin,
            pairedHostIdentity =
                DevicePublicIdentity(
                    deviceId = DeviceId("host"),
                    algorithm = IdentitySignatureAlgorithm.ED25519,
                    publicKeySpki = byteArrayOf(1, 2, 3, 4),
                    fingerprint = "SHA256:host",
                ),
            permissions = DevicePermissions(visual = true),
        )

    private fun announce(
        fingerprint: String = "SHA256:host",
        tlsPin: String = "pin",
        pairingUrl: String = "https://192.168.1.10:48291",
    ) = LanAnnouncePayload(
        fingerprint = fingerprint,
        tlsPin = tlsPin,
        port = 48291,
        pairingUrl = pairingUrl,
    )
}
