package dev.aegis.remote.android.pairing

import dev.aegis.remote.core.model.DeviceIdentityCanonicalEncoding
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import dev.aegis.remote.core.pairing.LocalPairingQrPayload
import dev.aegis.remote.core.pairing.localPairingQrSignaturePayload
import dev.aegis.remote.core.security.ExactP256Curve
import dev.aegis.remote.core.security.JcaSoftwareProviders
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class AndroidLocalPairingQrVerifier(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun verify(payload: LocalPairingQrPayload): LocalPairingQrPayload {
        require(payload.version == 3) { "QRP-7104: Legacy unsigned pairing QR is not accepted" }
        val now = clock()
        require(payload.issuedAtEpochMillis <= now + MAX_CLOCK_SKEW_MILLIS) {
            "QRP-7105: Pairing QR is not valid yet"
        }
        require(now <= payload.expiresAtEpochMillis) { "QRP-7101: Pairing QR expired" }
        require(payload.expiresAtEpochMillis - payload.issuedAtEpochMillis <= MAX_QR_TTL_MILLIS) {
            "QRP-7106: Pairing QR lifetime is too long"
        }
        val identity = requireNotNull(payload.hostIdentity) { "QRP-7102: Pairing QR has no host identity" }
        val digest = MessageDigest.getInstance("SHA-256")
        val expectedDeviceId =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                digest.digest(DeviceIdentityCanonicalEncoding.deviceIdPreimage(identity.algorithm, identity.publicKeySpki)),
            )
        val expectedFingerprint =
            "SHA256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(identity.publicKeySpki))
        require(expectedDeviceId == identity.deviceId.value && expectedFingerprint == identity.fingerprint) {
            "QRP-7102: Pairing host identity is not self-consistent"
        }

        val signature =
            runCatching { Base64.getUrlDecoder().decode(payload.signature) }
                .getOrElse { throw IllegalArgumentException("QRP-7102: Pairing QR signature is malformed", it) }
        val transcript = localPairingQrSignaturePayload(payload.copy(signature = ""))
        val valid =
            when (identity.algorithm) {
                IdentitySignatureAlgorithm.ED25519 -> {
                    verifyEd25519(identity.publicKeySpki, transcript, signature)
                }

                IdentitySignatureAlgorithm.ECDSA_P256_SHA256 -> {
                    val key = parseEcPublicKey(identity.publicKeySpki)
                    require(ExactP256Curve.isSecp256r1(key)) {
                        "QRP-7108: Host identity ${ExactP256Curve.REJECTION_CODE}"
                    }
                    Signature.getInstance("SHA256withECDSA", JcaSoftwareProviders.required("Signature", "SHA256withECDSA")).run {
                        initVerify(key)
                        update(transcript)
                        verify(signature)
                    }
                }
            }
        require(valid) { "QRP-7102: Pairing QR signature is invalid" }
        return payload
    }

    private fun parseEcPublicKey(spki: ByteArray): PublicKey =
        runCatching {
            KeyFactory
                .getInstance("EC", JcaSoftwareProviders.required("KeyFactory", "EC"))
                .generatePublic(X509EncodedKeySpec(spki))
        }.getOrElse {
            throw IllegalArgumentException(
                "QRP-7107: This Android device cannot verify the host identity algorithm",
                it,
            )
        }

    private fun verifyEd25519(
        spki: ByteArray,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val prefix = ED25519_SPKI_PREFIX
        require(spki.size == prefix.size + Ed25519PublicKeyParameters.KEY_SIZE && spki.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            "QRP-7107: This Android device cannot verify the host identity algorithm"
        }
        return Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(spki, prefix.size))
            update(payload, 0, payload.size)
            verifySignature(signature)
        }
    }

    private companion object {
        const val MAX_QR_TTL_MILLIS = 120_000L
        const val MAX_CLOCK_SKEW_MILLIS = 30_000L
        val ED25519_SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    }
}
