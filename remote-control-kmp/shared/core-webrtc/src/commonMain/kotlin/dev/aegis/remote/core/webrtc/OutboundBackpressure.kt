package dev.aegis.remote.core.webrtc

/** Shared SCTP outbound backpressure limit for the four Aegis DataChannels. */
const val MAX_OUTBOUND_BUFFERED_AMOUNT_BYTES: Long = 256L * 1_024L

enum class OutboundSendDisposition {
    Send,
    Drop,
    Reject,
}

/**
 * Pointer is unordered and drop-oldest; control/keyboard must never block
 * behind a burst of trackpad events on the same DTLS association.
 */
fun outboundSendDisposition(
    bufferedAmountBytes: Long,
    payloadBytes: Int,
    dropWhenOverCapacity: Boolean,
    maxBufferedAmountBytes: Long = MAX_OUTBOUND_BUFFERED_AMOUNT_BYTES,
): OutboundSendDisposition {
    require(payloadBytes >= 0)
    if (bufferedAmountBytes + payloadBytes <= maxBufferedAmountBytes) {
        return OutboundSendDisposition.Send
    }
    return if (dropWhenOverCapacity) OutboundSendDisposition.Drop else OutboundSendDisposition.Reject
}
