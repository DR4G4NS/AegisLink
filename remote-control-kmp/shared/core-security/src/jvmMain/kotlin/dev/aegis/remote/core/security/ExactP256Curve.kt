package dev.aegis.remote.core.security

import java.math.BigInteger
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp

/** Exact domain-parameter check for the secp256r1 / NIST P-256 suite. */
object ExactP256Curve {
    const val REJECTION_CODE = "EC_KEY_NOT_EXACT_SECP256R1"

    fun isSecp256r1(publicKey: PublicKey): Boolean {
        val key = publicKey as? ECPublicKey ?: return false
        val field = key.params.curve.field as? ECFieldFp ?: return false
        return field.p == FIELD_PRIME &&
            key.params.curve.a == CURVE_A &&
            key.params.curve.b == CURVE_B &&
            key.params.generator.affineX == GENERATOR_X &&
            key.params.generator.affineY == GENERATOR_Y &&
            key.params.order == ORDER &&
            key.params.cofactor == 1
    }

    fun requireSecp256r1(publicKey: PublicKey): ECPublicKey {
        require(isSecp256r1(publicKey)) { REJECTION_CODE }
        return publicKey as ECPublicKey
    }

    private fun hex(value: String): BigInteger = BigInteger(value, 16)

    private val FIELD_PRIME = hex("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff")
    private val CURVE_A = hex("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc")
    private val CURVE_B = hex("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b")
    private val GENERATOR_X = hex("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296")
    private val GENERATOR_Y = hex("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
    private val ORDER = hex("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")
}
