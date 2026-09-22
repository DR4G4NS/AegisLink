package dev.aegis.remote.relay

import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.relay.RelayTurnCredentials
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface TurnCredentialIssuer {
    fun issue(relayDeviceId: RelayDeviceId): RelayTurnCredentials
}

class HmacTurnCredentialIssuer(
    private val urls: List<String>,
    private val sharedSecret: String,
    private val ttlMillis: Long = 10 * 60 * 1000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : TurnCredentialIssuer {
    init {
        require(urls.isNotEmpty()) { "At least one TURN URL is required" }
        require(sharedSecret.isNotBlank()) { "TURN shared secret is required" }
        require(ttlMillis > 0) { "TURN credential TTL must be positive" }
    }

    override fun issue(relayDeviceId: RelayDeviceId): RelayTurnCredentials {
        val expiresAt = clock() + ttlMillis
        val username = "${expiresAt / 1000}:${relayDeviceId.value}"
        return RelayTurnCredentials(
            urls = urls,
            username = username,
            credential = hmacSha1(username),
            expiresAtEpochMillis = expiresAt,
        )
    }

    private fun hmacSha1(value: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(sharedSecret.encodeToByteArray(), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(value.encodeToByteArray()))
    }
}

fun environmentTurnCredentialIssuer(
    getenv: (String) -> String? = System::getenv,
    clock: () -> Long = { System.currentTimeMillis() },
): TurnCredentialIssuer? {
    val rawUrls = getenv("AEGIS_TURN_URLS")?.trim().orEmpty()
    val secret = getenv("AEGIS_TURN_SHARED_SECRET")?.trim().orEmpty()
    if (rawUrls.isEmpty() && secret.isEmpty()) return null
    require(rawUrls.isNotEmpty() && secret.isNotEmpty()) {
        "AEGIS_TURN_URLS and AEGIS_TURN_SHARED_SECRET must be configured together"
    }

    val urls =
        rawUrls
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    require(urls.isNotEmpty()) { "AEGIS_TURN_URLS must contain at least one URL" }

    val rawTtlSeconds = getenv("AEGIS_TURN_TTL_SECONDS")?.trim()
    val ttlSeconds =
        if (rawTtlSeconds.isNullOrEmpty()) {
            600L
        } else {
            requireNotNull(rawTtlSeconds.toLongOrNull()) {
                "AEGIS_TURN_TTL_SECONDS must be an integer"
            }
        }
    require(ttlSeconds in 1..(Long.MAX_VALUE / 1000L)) {
        "AEGIS_TURN_TTL_SECONDS must be a positive, representable duration"
    }

    return HmacTurnCredentialIssuer(
        urls = urls,
        sharedSecret = secret,
        ttlMillis = ttlSeconds * 1000L,
        clock = clock,
    )
}
