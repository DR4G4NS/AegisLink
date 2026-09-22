package dev.aegis.remote.android.security

import dev.aegis.remote.core.model.SshCredentialsRef
import dev.aegis.remote.core.security.SecureCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.common.Buffer
import java.security.KeyPairGenerator
import java.util.Base64

data class AndroidSshEnrollmentKey(
    val credentialsRef: SshCredentialsRef,
    val authorizedKey: String,
)

/**
 * Creates a per-pairing SSH key and stores its PKCS#8 representation inside
 * the existing Android-Keystore-backed encrypted credential store. Only the
 * OpenSSH public line leaves the phone.
 */
class AndroidSshEnrollmentKeyStore(
    private val credentialStore: SecureCredentialStore,
) {
    suspend fun create(label: String): AndroidSshEnrollmentKey =
        withContext(Dispatchers.Default) {
            val pair =
                KeyPairGenerator.getInstance("RSA").run {
                    initialize(RSA_KEY_BITS)
                    generateKeyPair()
                }
            val encodedPrivate = pair.private.encoded ?: error("SSH-7003: Generated SSH private key is not encodable")
            val pem = pkcs8Pem(encodedPrivate)
            encodedPrivate.fill(0)
            val credentialBytes = encodePrivateKeyCredentials(pem, passphrase = null)
            val wireKey = Buffer.PlainBuffer().putPublicKey(pair.public).compactData
            val comment =
                label
                    .lowercase()
                    .replace(Regex("[^a-z0-9._-]"), "-")
                    .take(64)
                    .ifBlank { "aegis-android" }
            val authorizedKey = "ssh-rsa ${Base64.getEncoder().encodeToString(wireKey)} $comment"
            try {
                val reference = credentialStore.putSecret("$comment-ssh-key", credentialBytes)
                AndroidSshEnrollmentKey(reference, authorizedKey)
            } finally {
                credentialBytes.fill(0)
            }
        }
}

private fun pkcs8Pem(value: ByteArray): String {
    val encoded = Base64.getMimeEncoder(64, "\n".encodeToByteArray()).encodeToString(value)
    return "-----BEGIN PRIVATE KEY-----\n$encoded\n-----END PRIVATE KEY-----\n"
}

private const val RSA_KEY_BITS = 3072
