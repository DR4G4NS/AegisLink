package dev.aegis.remote.android.pairing

import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DeviceIdentityCanonicalEncoding
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.IdentitySecurityLevel
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import dev.aegis.remote.core.pairing.LocalPairingQrPayload
import dev.aegis.remote.core.pairing.localPairingQrSignaturePayload
import dev.aegis.remote.core.security.ExactP256Curve
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidLocalPairingQrVerifierTest {
    @Test
    fun acceptsSignedP256HostIdentityFromASoftwareProvider() {
        val keyPair =
            KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
        val identity = identityFor(keyPair.public.encoded)
        val unsigned =
            LocalPairingQrPayload(
                pairingUrl = "https://192.168.1.20:48291",
                pairingUrls = listOf("https://192.168.1.20:48291"),
                pairingCode = "123456",
                agentFingerprint = "SHA256:agent",
                pairingSecret = "a".repeat(32),
                tokenId = "0".repeat(16),
                issuedAtEpochMillis = 1_000,
                expiresAtEpochMillis = 2_000,
                hostIdentity = identity,
            )
        val signature =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(localPairingQrSignaturePayload(unsigned))
                sign()
            }
        val payload =
            unsigned.copy(
                signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature),
            )

        val verified = AndroidLocalPairingQrVerifier(clock = { 1_500 }).verify(payload)

        assertEquals(payload.tokenId, verified.tokenId)
        assertEquals(payload.pairingUrl, verified.pairingUrl)
    }

    @Test
    fun rejectsNonP256HostIdentityBeforeSignatureVerification() {
        val keyPair =
            KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp384r1")) }
                .generateKeyPair()
        val identity = identityFor(keyPair.public.encoded)
        val payload =
            LocalPairingQrPayload(
                pairingUrl = "https://192.168.1.20:48291",
                pairingUrls = listOf("https://192.168.1.20:48291"),
                pairingCode = "123456",
                agentFingerprint = "SHA256:agent",
                pairingSecret = "a".repeat(32),
                tokenId = "0".repeat(16),
                issuedAtEpochMillis = 1_000,
                expiresAtEpochMillis = 2_000,
                hostIdentity = identity,
                signature = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0)),
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                AndroidLocalPairingQrVerifier(clock = { 1_500 }).verify(payload)
            }

        assertEquals("QRP-7108: Host identity ${ExactP256Curve.REJECTION_CODE}", failure.message)
    }

    private fun identityFor(publicKeySpki: ByteArray): DevicePublicIdentity {
        val algorithm = IdentitySignatureAlgorithm.ECDSA_P256_SHA256
        val digest = MessageDigest.getInstance("SHA-256")
        val deviceId =
            DeviceId(
                Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(digest.digest(DeviceIdentityCanonicalEncoding.deviceIdPreimage(algorithm, publicKeySpki))),
            )
        val fingerprint =
            "SHA256:" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(publicKeySpki))
        return DevicePublicIdentity(
            deviceId = deviceId,
            algorithm = algorithm,
            publicKeySpki = publicKeySpki,
            fingerprint = fingerprint,
            keyGeneration = 1,
            securityLevel = IdentitySecurityLevel.OS_KEYSTORE,
        )
    }
}
