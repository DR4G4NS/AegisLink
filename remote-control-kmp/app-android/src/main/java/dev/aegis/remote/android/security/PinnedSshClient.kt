package dev.aegis.remote.android.security

import dev.aegis.remote.core.model.HostKeyFingerprint
import dev.aegis.remote.core.security.canonicalOpenSshSha256Fingerprint
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import javax.crypto.KeyAgreement

internal fun createPinnedSshClient(
    expected: HostKeyFingerprint,
    connectTimeoutMillis: Int = 10_000,
    readTimeoutMillis: Int = 30_000,
): SSHClient = createPinnedSshConnection(expected, connectTimeoutMillis, readTimeoutMillis).client

internal fun createPinnedSshConnection(
    expected: HostKeyFingerprint,
    connectTimeoutMillis: Int = 10_000,
    readTimeoutMillis: Int = 30_000,
): PinnedSshConnection {
    require(expected.algorithm.equals("SHA256", ignoreCase = true)) { "Only SHA256 SSH host-key pins are accepted" }
    val value =
        requireNotNull(canonicalOpenSshSha256Fingerprint(expected.value)) {
            "Invalid SHA256 SSH host-key fingerprint"
        }
    val observation = PinnedHostKeyObservation(value)
    AndroidSshSecurityProvider.ensureInstalled()
    val client =
        SSHClient().apply {
            connectTimeout = connectTimeoutMillis
            timeout = readTimeoutMillis
            addHostKeyVerifier(StrictPinnedHostKeyVerifier(observation))
        }
    return PinnedSshConnection(client, observation)
}

internal object AndroidSshSecurityProvider {
    @Synchronized
    fun ensureInstalled() {
        val current = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (current?.javaClass == BouncyCastleProvider::class.java && supportsX25519(current)) return

        val previousPosition =
            Security
                .getProviders()
                .indexOf(current)
                .takeIf { it >= 0 }
                ?.plus(1)
        if (current != null) Security.removeProvider(current.name)
        val bundled = BouncyCastleProvider()
        try {
            check(Security.insertProviderAt(bundled, 1) == 1) { "Could not install the bundled Bouncy Castle provider" }
            check(supportsX25519(bundled)) { "The bundled Bouncy Castle provider does not support X25519" }
        } catch (error: Throwable) {
            Security.removeProvider(bundled.name)
            if (current != null) Security.insertProviderAt(current, previousPosition ?: 1)
            throw error
        }
    }

    private fun supportsX25519(provider: java.security.Provider): Boolean =
        runCatching {
            KeyAgreement.getInstance("X25519", provider)
            java.security.KeyFactory.getInstance("X25519", provider)
            java.security.KeyPairGenerator.getInstance("X25519", provider)
        }.isSuccess
}

internal data class PinnedSshConnection(
    val client: SSHClient,
    val observation: PinnedHostKeyObservation,
)

internal class PinnedHostKeyObservation(
    val expectedFingerprint: String,
) {
    @Volatile
    var actualFingerprint: String? = null
        internal set

    val mismatch: String?
        get() = actualFingerprint?.takeUnless { fingerprintsEqual(expectedFingerprint, it) }
}

private class StrictPinnedHostKeyVerifier(
    private val observation: PinnedHostKeyObservation,
) : HostKeyVerifier {
    override fun verify(
        hostname: String,
        port: Int,
        key: PublicKey,
    ): Boolean {
        val actual = openSshSha256Fingerprint(key)
        observation.actualFingerprint = actual
        return fingerprintsEqual(observation.expectedFingerprint, actual)
    }

    override fun findExistingAlgorithms(
        hostname: String,
        port: Int,
    ): List<String> = emptyList()
}

internal fun enableSshKeepAlive(
    ssh: SSHClient,
    intervalSeconds: Int = 15,
) {
    ssh.connection.keepAlive.keepAliveInterval = intervalSeconds
}

internal fun openSshSha256Fingerprint(key: PublicKey): String {
    val wireKey = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(wireKey)
    return Base64.getEncoder().withoutPadding().encodeToString(digest)
}

private fun fingerprintsEqual(
    expected: String,
    actual: String,
): Boolean =
    MessageDigest.isEqual(
        expected.toByteArray(Charsets.US_ASCII),
        actual.toByteArray(Charsets.US_ASCII),
    )
