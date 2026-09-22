package dev.aegis.remote.android.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDeviceIdentityStoreTest {
    @Test
    fun acceptsOnlyEd25519AlgorithmNamesAndRejectsSilentEcDowngrade() {
        assertTrue(AndroidEd25519IdentitySupport.isEd25519Algorithm("Ed25519"))
        assertTrue(AndroidEd25519IdentitySupport.isEd25519Algorithm("EdDSA"))
        assertFalse(AndroidEd25519IdentitySupport.isEd25519Algorithm("EC"))
    }

    @Test
    fun platformSupportFailsClosedBeforeAndroid13() {
        assertFailsWith<AndroidDeviceIdentityUnavailableException> {
            AndroidEd25519IdentitySupport.requirePlatformSupport(26)
        }
        assertFailsWith<AndroidDeviceIdentityUnavailableException> {
            AndroidEd25519IdentitySupport.requirePlatformSupport(32)
        }

        AndroidEd25519IdentitySupport.requirePlatformSupport(33)
    }

    @Test
    fun canonicalSubjectPublicKeyInfoProducesRawEd25519KeyAndDerivedDeviceId() {
        val rawPublicKey = ByteArray(AndroidEd25519IdentitySupport.ED25519_PUBLIC_KEY_BYTES) { it.toByte() }
        val subjectPublicKeyInfo =
            byteArrayOf(
                0x30,
                0x2a,
                0x30,
                0x05,
                0x06,
                0x03,
                0x2b,
                0x65,
                0x70,
                0x03,
                0x21,
                0x00,
            ) + rawPublicKey

        val extracted = AndroidEd25519IdentitySupport.rawPublicKeyFromSubjectPublicKeyInfo(subjectPublicKeyInfo)

        assertContentEquals(rawPublicKey, extracted)
        assertEquals(
            "lcfo_FT9_lj3d5Pkz-nU2QkpC2bLGMgxxUdwr4ZH0pQ",
            AndroidEd25519IdentitySupport.deviceIdFromRawPublicKey(extracted).value,
        )
    }

    @Test
    fun nonCanonicalSubjectPublicKeyInfoAndWrongKeySizeAreRejected() {
        val nonCanonical = ByteArray(44)
        nonCanonical[0] = 0x30
        nonCanonical[1] = 0x2a

        assertFailsWith<IllegalArgumentException> {
            AndroidEd25519IdentitySupport.rawPublicKeyFromSubjectPublicKeyInfo(nonCanonical)
        }
        assertFailsWith<IllegalArgumentException> {
            AndroidEd25519IdentitySupport.rawPublicKeyFromSubjectPublicKeyInfo(ByteArray(43))
        }
        assertFailsWith<IllegalArgumentException> {
            AndroidEd25519IdentitySupport.deviceIdFromRawPublicKey(ByteArray(31))
        }
    }
}
