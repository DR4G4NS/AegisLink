package dev.aegis.remote.android.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.UnrecoverableKeyException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialInvalidationTest {
    @Test
    fun `recognizes direct and wrapped keystore invalidation`() {
        assertTrue(isCredentialKeyInvalidation(KeyPermanentlyInvalidatedException()))
        assertTrue(isCredentialKeyInvalidation(IllegalStateException("wrapped", UnrecoverableKeyException("invalid"))))
        assertFalse(isCredentialKeyInvalidation(IllegalArgumentException("bad input")))
    }
}
