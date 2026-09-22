package dev.aegis.remote.relay

import dev.aegis.remote.core.model.RelayDeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurnCredentialIssuerTest {
    @Test
    fun issuesTemporaryHmacCredentialsForRelayDevice() {
        val issuer =
            HmacTurnCredentialIssuer(
                urls = listOf("turn:turn.example.test:3478?transport=udp"),
                sharedSecret = "shared-secret",
                ttlMillis = 600_000,
                clock = { 1_700_000_000_000 },
            )

        val credentials = issuer.issue(RelayDeviceId("pc-1"))
        val otherCredentials = issuer.issue(RelayDeviceId("android-1"))

        assertEquals(listOf("turn:turn.example.test:3478?transport=udp"), credentials.urls)
        assertEquals("1700000600:pc-1", credentials.username)
        assertEquals(1_700_000_600_000, credentials.expiresAtEpochMillis)
        assertTrue(credentials.credential.isNotBlank())
        assertNotEquals(credentials.credential, otherCredentials.credential)
    }

    @Test
    fun createsIssuerFromEnvironmentWhenTurnSettingsExist() {
        val env =
            mapOf(
                "AEGIS_TURN_URLS" to "turn:one.example.test:3478, turn:two.example.test:3478",
                "AEGIS_TURN_SHARED_SECRET" to "shared-secret",
                "AEGIS_TURN_TTL_SECONDS" to "120",
            )

        val issuer = environmentTurnCredentialIssuer(getenv = env::get, clock = { 5_000 })!!
        val credentials = issuer.issue(RelayDeviceId("pc-1"))

        assertEquals(listOf("turn:one.example.test:3478", "turn:two.example.test:3478"), credentials.urls)
        assertEquals("125:pc-1", credentials.username)
        assertEquals(125_000, credentials.expiresAtEpochMillis)
    }

    @Test
    fun leavesTurnDisabledOnlyWhenBothRequiredSettingsAreAbsent() {
        assertNull(environmentTurnCredentialIssuer(getenv = emptyMap<String, String>()::get))
    }

    @Test
    fun rejectsPartialTurnEnvironmentInsteadOfDisablingItSilently() {
        val onlyUrls = mapOf("AEGIS_TURN_URLS" to "turn:one.example.test:3478")
        val onlySecret = mapOf("AEGIS_TURN_SHARED_SECRET" to "shared-secret")

        assertFailsWith<IllegalArgumentException> {
            environmentTurnCredentialIssuer(getenv = onlyUrls::get)
        }
        assertFailsWith<IllegalArgumentException> {
            environmentTurnCredentialIssuer(getenv = onlySecret::get)
        }
    }

    @Test
    fun rejectsInvalidTurnCredentialTtl() {
        val base =
            mapOf(
                "AEGIS_TURN_URLS" to "turn:one.example.test:3478",
                "AEGIS_TURN_SHARED_SECRET" to "shared-secret",
            )

        listOf("not-a-number", "0", "-1", Long.MAX_VALUE.toString()).forEach { ttl ->
            assertFailsWith<IllegalArgumentException>("TTL $ttl should fail") {
                environmentTurnCredentialIssuer(getenv = (base + ("AEGIS_TURN_TTL_SECONDS" to ttl))::get)
            }
        }
    }
}
