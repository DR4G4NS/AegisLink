package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.protocol.MAX_PROTOCOL_TEXT_PAYLOAD_BYTES
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopWebRtcDataChannelTextTransportTest {
    @Test
    fun emitsIncomingTextMessages() =
        runTest {
            val handle = FakeDesktopDataChannelHandle()
            val transport = DesktopWebRtcDataChannelTextTransport(handle)
            val incoming = async { transport.incomingText.first() }
            runCurrent()

            handle.emit("hello")

            assertEquals("hello", incoming.await())
        }

    @Test
    fun closesChannelOnOversizedIncomingTextMessages() =
        runTest {
            val handle = FakeDesktopDataChannelHandle()
            val transport = DesktopWebRtcDataChannelTextTransport(handle)

            handle.emit("x".repeat(MAX_PROTOCOL_TEXT_PAYLOAD_BYTES + 1))
            handle.emit("must-not-be-processed")

            assertFailsWith<IllegalArgumentException> { transport.incomingText.first() }
            assertEquals(true, handle.closed)
        }

    @Test
    fun rejectsOversizedOutgoingTextMessages() =
        runTest {
            val handle = FakeDesktopDataChannelHandle()
            val transport = DesktopWebRtcDataChannelTextTransport(handle)

            assertFailsWith<IllegalArgumentException> {
                transport.sendText("x".repeat(MAX_PROTOCOL_TEXT_PAYLOAD_BYTES + 1))
            }

            assertEquals(emptyList(), handle.sent)
        }

    @Test
    fun closeUnregistersAndClosesHandle() =
        runTest {
            val handle = FakeDesktopDataChannelHandle()
            val transport = DesktopWebRtcDataChannelTextTransport(handle)

            transport.close()

            assertEquals(true, handle.unregistered)
            assertEquals(true, handle.closed)
        }

    @Test
    fun rejectsOutgoingTextWhenNativeBufferReachedBound() =
        runTest {
            val handle = FakeDesktopDataChannelHandle(bufferedAmount = 256L * 1_024L)
            val transport = DesktopWebRtcDataChannelTextTransport(handle)

            assertFailsWith<IllegalStateException> { transport.sendText("bounded") }

            assertEquals(emptyList(), handle.sent)
        }

    @Test
    fun reliableInboundQueueFailsClosedInsteadOfDroppingProtocolMessages() {
        val handle = FakeDesktopDataChannelHandle()
        DesktopWebRtcDataChannelTextTransport(handle)

        repeat(65) { index -> handle.emit("message-$index") }

        assertEquals(true, handle.closed)
    }
}

private class FakeDesktopDataChannelHandle(
    private val bufferedAmount: Long = 0L,
) : DesktopDataChannelHandle {
    private var observer: DesktopDataChannelObserver? = null
    val sent = mutableListOf<String>()
    var unregistered = false
    var closed = false

    override fun register(observer: DesktopDataChannelObserver) {
        this.observer = observer
    }

    override fun unregister() {
        unregistered = true
    }

    override fun sendText(payload: String) {
        sent += payload
    }

    override fun bufferedAmountBytes(): Long = bufferedAmount

    override fun close() {
        closed = true
    }

    fun emit(payload: String) {
        observer?.onMessage(payload)
    }
}
