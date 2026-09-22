package dev.aegis.remote.android.security

import dev.aegis.remote.core.model.HostKeyFingerprint
import net.schmizz.sshj.transport.verification.FingerprintVerifier
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Provider
import java.security.Security
import java.util.Base64
import javax.crypto.KeyAgreement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PinnedSshClientTest {
    @Test
    fun `fingerprint uses OpenSSH wire key and verifier accepts exact key only`() {
        val first =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
                .public
        val second =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
                .public
        val fingerprint = openSshSha256Fingerprint(first)
        val incorrectSpkiFingerprint =
            Base64.getEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(first.encoded),
            )

        assertNotEquals(incorrectSpkiFingerprint, fingerprint)
        val verifier = FingerprintVerifier.getInstance("SHA256:$fingerprint")
        assertTrue(verifier.verify("host", 22, first))
        assertTrue(!verifier.verify("host", 22, second))
    }

    @Test
    fun `ssh provider replaces an incompatible Android BC provider with bundled X25519 support`() {
        val original = Security.getProvider("BC")
        if (original != null) Security.removeProvider(original.name)
        Security.insertProviderAt(
            object : Provider("BC", 0.0, "Incompatible Android test provider") {},
            1,
        )
        try {
            AndroidSshSecurityProvider.ensureInstalled()

            val installed = Security.getProvider("BC")
            assertEquals("org.bouncycastle.jce.provider.BouncyCastleProvider", installed.javaClass.name)
            KeyAgreement.getInstance("X25519", installed)
            KeyPairGenerator.getInstance("X25519", installed)
        } finally {
            Security.removeProvider("BC")
            if (original != null) Security.addProvider(original)
        }
    }

    @Test
    fun `client refuses non sha256 or malformed pins before connecting`() {
        assertFailsWith<IllegalArgumentException> {
            createPinnedSshClient(HostKeyFingerprint("MD5", "aa:bb"))
        }
        assertFailsWith<IllegalArgumentException> {
            createPinnedSshClient(HostKeyFingerprint("SHA256", "short"))
        }
    }
}
