package dev.aegis.remote.android.home

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CancellableResultTest {
    @Test
    fun cancellationIsRethrownInsteadOfBecomingUiFailure() {
        assertFailsWith<CancellationException> {
            runCatching { throw CancellationException("screen closed") }.rethrowCancellation()
        }
    }

    @Test
    fun regularFailuresRemainResultFailures() {
        val result = runCatching { error("network failed") }.rethrowCancellation()

        assertIs<IllegalStateException>(result.exceptionOrNull())
        assertEquals("network failed", result.exceptionOrNull()?.message)
    }
}
