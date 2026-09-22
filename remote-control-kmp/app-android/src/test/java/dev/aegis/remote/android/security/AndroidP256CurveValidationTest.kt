package dev.aegis.remote.android.security

import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AndroidP256CurveValidationTest {
    @Test
    fun acceptsExactSecp256r1AndRejectsSameSizeSubstitution() {
        val key =
            KeyPairGenerator.getInstance("EC").run {
                initialize(ECGenParameterSpec("secp256r1"))
                generateKeyPair().public as ECPublicKey
            }

        requireExactAndroidP256PublicKey(key)
        assertFailsWith<AndroidDeviceIdentityUnavailableException> {
            requireExactAndroidP256PublicKey(AndroidSubstitutedEcPublicKey(key))
        }
    }
}

private class AndroidSubstitutedEcPublicKey(
    private val delegate: ECPublicKey,
) : ECPublicKey {
    override fun getW() = delegate.w

    override fun getParams(): ECParameterSpec =
        ECParameterSpec(
            delegate.params.curve,
            delegate.params.generator,
            delegate.params.order,
            2,
        )

    override fun getAlgorithm(): String = delegate.algorithm

    override fun getFormat(): String = delegate.format

    override fun getEncoded(): ByteArray = delegate.encoded.copyOf()
}
