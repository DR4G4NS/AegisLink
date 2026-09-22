package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.DeviceIdentityCanonicalEncoding
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import dev.aegis.remote.core.pairing.LocalPairingRequestBody
import dev.aegis.remote.core.pairing.localPairingDeviceProofPayload
import dev.aegis.remote.core.security.ExactP256Curve
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal fun verifyLocalPairingDeviceProof(request: LocalPairingRequestBody): Boolean {
    val identity = request.deviceIdentity ?: return false
    if (request.deviceFingerprint != identity.fingerprint) return false
    if (!identity.isSelfConsistent()) return false
    val signature =
        runCatching { Base64.getUrlDecoder().decode(request.deviceIdentitySignature) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return false
    return runCatching {
        val keyAlgorithm =
            when (identity.algorithm) {
                IdentitySignatureAlgorithm.ECDSA_P256_SHA256 -> "EC"
                IdentitySignatureAlgorithm.ED25519 -> "Ed25519"
            }
        val signatureAlgorithm =
            when (identity.algorithm) {
                IdentitySignatureAlgorithm.ECDSA_P256_SHA256 -> "SHA256withECDSA"
                IdentitySignatureAlgorithm.ED25519 -> "Ed25519"
            }
        val publicKey =
            KeyFactory
                .getInstance(keyAlgorithm)
                .generatePublic(X509EncodedKeySpec(identity.publicKeySpki))
        if (identity.algorithm == IdentitySignatureAlgorithm.ECDSA_P256_SHA256) {
            ExactP256Curve.requireSecp256r1(publicKey)
        }
        Signature.getInstance(signatureAlgorithm).run {
            initVerify(publicKey)
            update(localPairingDeviceProofPayload(request.copy(deviceIdentitySignature = "")))
            verify(signature)
        }
    }.getOrDefault(false)
}

private fun DevicePublicIdentity.isSelfConsistent(): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
    val expectedDeviceId =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            digest.digest(DeviceIdentityCanonicalEncoding.deviceIdPreimage(algorithm, publicKeySpki)),
        )
    val expectedFingerprint =
        "SHA256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(publicKeySpki))
    return expectedDeviceId == deviceId.value && expectedFingerprint == fingerprint
}

internal fun isValidOpenSshAuthorizedKey(value: String): Boolean {
    if (value.length !in 80..16_384) return false
    if ('\n' in value || '\r' in value || '\u0000' in value) return false
    val fields = value.trim().split(Regex("\\s+"), limit = 3)
    if (fields.size < 2 || fields[0] !in ALLOWED_SSH_KEY_TYPES) return false
    val decoded = runCatching { Base64.getDecoder().decode(fields[1]) }.getOrNull() ?: return false
    return decoded.size in 32..8_192
}

private val ALLOWED_SSH_KEY_TYPES =
    setOf(
        "ssh-ed25519",
        "ecdsa-sha2-nistp256",
        "rsa-sha2-512",
        "rsa-sha2-256",
        "ssh-rsa",
    )
