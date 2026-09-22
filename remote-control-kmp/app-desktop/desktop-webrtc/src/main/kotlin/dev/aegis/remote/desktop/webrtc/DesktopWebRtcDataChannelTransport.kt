package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.webrtc.OutboundSendDisposition
import dev.aegis.remote.core.webrtc.outboundSendDisposition
import dev.aegis.remote.protocol.MAX_PROTOCOL_TEXT_PAYLOAD_BYTES
import dev.aegis.remote.protocol.ProtocolTextTransport
import dev.aegis.remote.protocol.isProtocolTextPayloadWithinLimit
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.nio.ByteBuffer

interface DesktopDataChannelHandle {
    fun register(observer: DesktopDataChannelObserver)

    fun unregister()

    fun sendText(payload: String)

    fun bufferedAmountBytes(): Long = 0L

    fun isOpen(): Boolean = true

    fun close()

    fun dispose() = Unit
}

interface DesktopDataChannelObserver {
    fun onStateChange()

    fun onMessage(payload: String)
}

class NativeDesktopDataChannelHandle(
    private val channel: RTCDataChannel,
) : DesktopDataChannelHandle {
    override fun register(observer: DesktopDataChannelObserver) {
        channel.registerObserver(
            object : RTCDataChannelObserver {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() = observer.onStateChange()

                override fun onMessage(buffer: RTCDataChannelBuffer) {
                    if (buffer.binary) return
                    observer.onMessage(buffer.data.toUtf8String())
                }
            },
        )
    }

    override fun unregister() {
        channel.unregisterObserver()
    }

    override fun sendText(payload: String) {
        channel.send(RTCDataChannelBuffer(ByteBuffer.wrap(payload.toByteArray(Charsets.UTF_8)), false))
    }

    override fun bufferedAmountBytes(): Long = channel.bufferedAmount

    override fun isOpen(): Boolean = channel.state == RTCDataChannelState.OPEN

    override fun close() {
        channel.close()
    }

    override fun dispose() {
        channel.dispose()
    }
}

class DesktopWebRtcDataChannelTextTransport(
    private val handle: DesktopDataChannelHandle,
    onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
    private val dropWhenOverCapacity: Boolean = false,
) : ProtocolTextTransport {
    private val incoming =
        Channel<String>(
            capacity = INBOUND_DATA_CHANNEL_CAPACITY,
            onBufferOverflow = onBufferOverflow,
        )

    override val incomingText: Flow<String> = incoming.receiveAsFlow()

    @Volatile
    private var failedClosed = false

    init {
        handle.register(
            object : DesktopDataChannelObserver {
                override fun onStateChange() = Unit

                override fun onMessage(payload: String) {
                    if (failedClosed) return
                    if (!payload.isProtocolTextPayloadWithinLimit()) {
                        failClosed(IllegalArgumentException("WebRTC DataChannel payload exceeds the protocol byte limit"))
                        return
                    }
                    if (incoming.trySend(payload).isFailure) {
                        failClosed(IllegalStateException("WebRTC DataChannel inbound queue overflow"))
                    }
                }
            },
        )
    }

    override suspend fun sendText(payload: String) {
        require(payload.isProtocolTextPayloadWithinLimit()) {
            "WebRTC DataChannel text payload exceeds $MAX_PROTOCOL_TEXT_PAYLOAD_BYTES bytes"
        }
        check(!failedClosed && handle.isOpen()) { "WebRTC DataChannel is not open" }
        val payloadBytes = payload.toByteArray(Charsets.UTF_8).size
        when (
            outboundSendDisposition(
                bufferedAmountBytes = handle.bufferedAmountBytes(),
                payloadBytes = payloadBytes,
                dropWhenOverCapacity = dropWhenOverCapacity,
            )
        ) {
            OutboundSendDisposition.Send -> handle.sendText(payload)
            OutboundSendDisposition.Drop -> Unit
            OutboundSendDisposition.Reject -> error("WebRTC DataChannel outbound backpressure limit exceeded")
        }
    }

    override suspend fun close() {
        failedClosed = true
        incoming.cancel()
        handle.unregister()
        handle.close()
    }

    private fun failClosed(cause: Throwable) {
        if (failedClosed) return
        failedClosed = true
        incoming.close(cause)
        handle.close()
    }
}

private const val INBOUND_DATA_CHANNEL_CAPACITY = 64

private fun ByteBuffer.toUtf8String(): String {
    val duplicate = slice()
    val bytes = ByteArray(duplicate.remaining())
    duplicate.get(bytes)
    return bytes.toString(Charsets.UTF_8)
}
