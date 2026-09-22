package dev.aegis.remote.core.security

import dev.aegis.remote.core.model.HostKeyFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostFingerprintValidatorTest {
    private val validator = HostFingerprintValidator()

    @Test
    fun comparesAlgorithmCaseInsensitively() {
        assertTrue(
            validator.matches(
                HostKeyFingerprint("SHA256", "abc"),
                HostKeyFingerprint("sha256", "abc"),
            ),
        )
    }

    @Test
    fun rejectsDifferentFingerprint() {
        assertFalse(
            validator.matches(
                HostKeyFingerprint("SHA256", "abc"),
                HostKeyFingerprint("SHA256", "def"),
            ),
        )
    }

    @Test
    fun acceptsCanonicalOpenSshSha256WithOptionalPrefixAndPadding() {
        val digest = "A".repeat(43)

        assertTrue(validator.isValidFingerprint(digest))
        assertTrue(validator.isValidFingerprint("SHA256:$digest"))
        assertTrue(validator.isValidFingerprint("sha256:$digest="))
        assertEquals(digest, canonicalOpenSshSha256Fingerprint("SHA256:$digest="))
    }

    @Test
    fun rejectsIncompleteWrongAlgorithmAndMalformedSha256Pins() {
        assertFalse(validator.isValidFingerprint(""))
        assertFalse(validator.isValidFingerprint("SHA256:"))
        assertFalse(validator.isValidFingerprint("MD5:${"a".repeat(47)}"))
        assertFalse(validator.isValidFingerprint("SHA256:${"A".repeat(42)}"))
        assertFalse(validator.isValidFingerprint("SHA256:${"A".repeat(44)}"))
        assertFalse(validator.isValidFingerprint("SHA256:${"A".repeat(42)}-"))
    }
}
