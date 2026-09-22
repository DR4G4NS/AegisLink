package dev.aegis.remote.desktop.webrtc

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopIceCandidateBufferTest {
    @Test
    fun localCandidatesFollowTheirDescription() {
        val buffer = DesktopLocalIceCandidateBuffer<String>()

        buffer.beginDescription()
        assertEquals(emptyList(), buffer.candidate("candidate-1"))
        assertEquals(listOf("candidate-1"), buffer.descriptionSignaled())
        assertEquals(listOf("candidate-2"), buffer.candidate("candidate-2"))
    }

    @Test
    fun remoteCandidatesWaitUntilRemoteDescriptionIsApplied() {
        val buffer = DesktopRemoteIceCandidateBuffer<String>()

        buffer.beginDescription()
        assertEquals(emptyList(), buffer.candidate("candidate-1"))
        assertEquals(listOf("candidate-1"), buffer.descriptionApplied())
        assertEquals(listOf("candidate-2"), buffer.candidate("candidate-2"))
    }

    @Test
    fun overflowOfRelayCandidatesFailsClosed() {
        val buffer =
            DesktopLocalIceCandidateBuffer<String> { candidate ->
                dev.aegis.remote.core.webrtc
                    .iceCandidateEvictionRankFromSdp(candidate)
            }
        buffer.beginDescription()
        repeat(64) { index -> buffer.candidate("typ relay $index") }
        val error =
            kotlin.test.assertFailsWith<dev.aegis.remote.core.webrtc.IceCandidateBufferOverflowException> {
                buffer.candidate("typ relay overflow")
            }
        assertEquals(true, error.message?.contains("WEBRTC_ICE_BUFFER_OVERFLOW"))
    }
}
