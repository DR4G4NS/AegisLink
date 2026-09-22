package dev.aegis.remote.desktop.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalProtocolAuthenticatorTest {
    private val authenticator = LocalProtocolAuthenticator()

    @Test
    fun issuedTokensAreUniqueAndMatchOnlyTheirOwnHash() {
        val first = authenticator.issueToken()
        val second = authenticator.issueToken()

        assertNotEquals(first, second)
        assertTrue(authenticator.matches(first, authenticator.hash(first)))
        assertFalse(authenticator.matches(second, authenticator.hash(first)))
        assertFalse(authenticator.matches(null, authenticator.hash(first)))
    }
}
