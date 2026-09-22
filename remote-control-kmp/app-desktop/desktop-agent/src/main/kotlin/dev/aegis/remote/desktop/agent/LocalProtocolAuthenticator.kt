package dev.aegis.remote.desktop.agent

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LocalProtocolAuthenticator(
    private val random: SecureRandom = SecureRandom(),
) {
    fun issueToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    fun matches(
        token: String?,
        expectedHash: String?,
    ): Boolean {
        if (token.isNullOrBlank() || expectedHash.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            hash(token).toByteArray(Charsets.US_ASCII),
            expectedHash.toByteArray(Charsets.US_ASCII),
        )
    }

    fun deriveToken(
        secret: String,
        payload: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
    }

    fun verifiesProof(
        secret: String,
        payload: String,
        proof: String,
    ): Boolean =
        MessageDigest.isEqual(
            deriveToken(secret, payload).toByteArray(Charsets.US_ASCII),
            proof.toByteArray(Charsets.US_ASCII),
        )

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
