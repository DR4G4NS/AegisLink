package dev.aegis.remote.relay

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class MainTest {
    @Test
    fun readsRawUtf8TokenHashSecretFromEnvironment() {
        val secret = "0".repeat(32)

        val resolved =
            environmentRelayTokenHashSecret(
                getenv = mapOf("AEGIS_RELAY_TOKEN_HMAC_SECRET" to secret)::get,
            )

        assertContentEquals(secret.encodeToByteArray(), resolved)
    }

    @Test
    fun readsBase64UrlTokenHashSecretFromEnvironment() {
        val secret = ByteArray(32) { index -> index.toByte() }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)

        val resolved =
            environmentRelayTokenHashSecret(
                getenv = mapOf("AEGIS_RELAY_TOKEN_HMAC_SECRET" to "base64url:$encoded")::get,
            )

        assertContentEquals(secret, resolved)
    }

    @Test
    fun rejectsMissingOrShortTokenHashSecret() {
        assertFailsWith<IllegalStateException> {
            environmentRelayTokenHashSecret(getenv = { null })
        }
        assertFailsWith<IllegalArgumentException> {
            environmentRelayTokenHashSecret(
                getenv = mapOf("AEGIS_RELAY_TOKEN_HMAC_SECRET" to "too-short")::get,
            )
        }
    }
}
