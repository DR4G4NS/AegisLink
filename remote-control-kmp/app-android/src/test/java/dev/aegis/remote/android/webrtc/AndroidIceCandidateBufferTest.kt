package dev.aegis.remote.android.webrtc

import dev.aegis.remote.core.webrtc.IceCandidateBufferOverflowException
import dev.aegis.remote.core.webrtc.iceCandidateEvictionRankFromSdp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidIceCandidateBufferTest {
    @Test
    fun overflowOfRelayCandidatesFailsClosed() {
        val buffer = AndroidLocalIceCandidateBuffer<String>(::iceCandidateEvictionRankFromSdp)
        buffer.beginDescription()
        repeat(64) { index -> buffer.candidate("typ relay $index") }
        val error =
            assertFailsWith<IceCandidateBufferOverflowException> {
                buffer.candidate("typ relay overflow")
            }
        assertEquals(true, error.message?.contains("WEBRTC_ICE_BUFFER_OVERFLOW"))
    }
}
