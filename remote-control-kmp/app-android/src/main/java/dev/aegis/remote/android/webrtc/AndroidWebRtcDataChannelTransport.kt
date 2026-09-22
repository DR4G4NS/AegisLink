package dev.aegis.remote.android.webrtc

import dev.aegis.remote.core.webrtc.OutboundSendDisposition
import dev.aegis.remote.core.webrtc.outboundSendDisposition
import dev.aegis.remote.protocol.MAX_PROTOCOL_TEXT_PAYLOAD_BYTES
import dev.aegis.remote.protocol.ProtocolTextTransport
import dev.aegis.remote.protocol.isProtocolTextPayloadWithinLimit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.webrtc.DataChannel
import java.nio.ByteBuffer

interface AndroidDataChannelHandle {
    fun register(observer: AndroidDataChannelObserver)

    fun unregister()

    fun sendText(payload: String): Boolean

    fun bufferedAmountBytes(): Long = 0L

    fun isOpen(): Boolean = true

    fun close()
}

interface AndroidDataChannelObserver {
    fun onStateChange()

    fun onMessage(payload: String)
}

class NativeAndroidDataChannelHandle(
    private val channel: DataChannel,
) : AndroidDataChannelHandle {
    override fun register(observer: AndroidDataChannelObserver) {
        channel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() = observer.onStateChange()

                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (buffer.binary) return
                    observer.onMessage(buffer.data.toUtf8String())
                }
            },
        )
    }

    override fun unregister() {
        channel.unregisterObserver()
    }

    override fun sendText(payload: String): Boolean = channel.send(DataChannel.Buffer(ByteBuffer.wrap(payload.toByteArray(Charsets.UTF_8)), false))

    override fun bufferedAmountBytes(): Long = channel.bufferedAmount()

    override fun isOpen(): Boolean = channel.state() == DataChannel.State.OPEN

    override fun close() {
        channel.close()
        channel.dispose()
    }
}

class AndroidWebRtcDataChannelTextTransport(
    private val handle: AndroidDataChannelHandle,
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
            object : AndroidDataChannelObserver {
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
            OutboundSendDisposition.Send -> {
                check(handle.sendText(payload)) { "WebRTC DataChannel rejected outgoing text payload" }
            }

            OutboundSendDisposition.Drop -> {
                Unit
            }

            OutboundSendDisposition.Reject -> {
                error("WebRTC DataChannel outbound backpressure limit exceeded")
            }
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
