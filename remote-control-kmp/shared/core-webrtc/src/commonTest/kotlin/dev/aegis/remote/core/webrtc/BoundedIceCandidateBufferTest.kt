package dev.aegis.remote.core.webrtc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedIceCandidateBufferTest {
    @Test
    fun dropsOldestHostBeforeRelayWhenCapped() {
        val buffer =
            BoundedIceCandidateBuffer<String>(maxPending = 2) { candidate ->
                iceCandidateEvictionRankFromSdp(candidate)
            }
        buffer.begin()
        buffer.candidate("typ host 1")
        buffer.candidate("typ srflx 2")
        buffer.candidate("typ relay 3")

        assertEquals(listOf("typ srflx 2", "typ relay 3"), buffer.release())
    }

    @Test
    fun overflowOfOnlyRelayCandidatesFailsClosed() {
        val buffer =
            BoundedIceCandidateBuffer<String>(maxPending = 1) { candidate ->
                iceCandidateEvictionRankFromSdp(candidate)
            }
        buffer.begin()
        buffer.candidate("typ relay 1")
        val error =
            assertFailsWith<IceCandidateBufferOverflowException> {
                buffer.candidate("typ relay 2")
            }
        assertEquals(true, error.message?.contains("WEBRTC_ICE_BUFFER_OVERFLOW"))
    }
}

class OutboundBackpressureTest {
    @Test
    fun pointerDropsWhenOverCapWhileControlRejects() {
        assertEquals(
            OutboundSendDisposition.Drop,
            outboundSendDisposition(
                bufferedAmountBytes = MAX_OUTBOUND_BUFFERED_AMOUNT_BYTES,
                payloadBytes = 16,
                dropWhenOverCapacity = true,
            ),
        )
        assertEquals(
            OutboundSendDisposition.Reject,
            outboundSendDisposition(
                bufferedAmountBytes = MAX_OUTBOUND_BUFFERED_AMOUNT_BYTES,
                payloadBytes = 16,
                dropWhenOverCapacity = false,
            ),
        )
        assertEquals(
            OutboundSendDisposition.Send,
            outboundSendDisposition(
                bufferedAmountBytes = 0,
                payloadBytes = 16,
                dropWhenOverCapacity = false,
            ),
        )
    }
}
