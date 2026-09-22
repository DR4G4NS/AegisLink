package dev.aegis.remote.android.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddProfileDraftValidationTest {
    @Test
    fun `rejects blank and malformed host pins before saving credentials`() {
        assertEquals("SSH host key fingerprint is required", validDraft("").validationError())
        assertEquals(
            "SSH host key fingerprint must be an OpenSSH SHA256 fingerprint",
            validDraft("SHA256:not-a-real-pin").validationError(),
        )
    }

    @Test
    fun `accepts canonical OpenSSH SHA256 pin`() {
        assertNull(validDraft("SHA256:${"A".repeat(43)}").validationError())
    }

    private fun validDraft(fingerprint: String) =
        AddProfileDraft(
            displayName = "Windows PC",
            localHost = "127.0.0.1",
            sshPort = "22",
            username = "aegis-test",
            sshPassword = "test-only-password",
            hostKeyFingerprint = fingerprint,
        )
}
