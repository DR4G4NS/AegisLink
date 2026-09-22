package dev.aegis.remote.android.webrtc

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidWebRtcDataChannelTextTransportTest {
    @Test
    fun pointerDropsWhenBufferedAmountExceedsCap() =
        runTest {
            val handle = FakeAndroidDataChannelHandle(bufferedAmount = 256L * 1_024L)
            val transport = AndroidWebRtcDataChannelTextTransport(handle, dropWhenOverCapacity = true)

            transport.sendText("pointer-burst")

            assertEquals(emptyList(), handle.sent)
        }

    @Test
    fun controlRejectsWhenBufferedAmountExceedsCap() =
        runTest {
            val handle = FakeAndroidDataChannelHandle(bufferedAmount = 256L * 1_024L)
            val transport = AndroidWebRtcDataChannelTextTransport(handle, dropWhenOverCapacity = false)

            assertFailsWith<IllegalStateException> { transport.sendText("control") }
            assertEquals(emptyList(), handle.sent)
        }

    @Test
    fun controlSendsWhenBufferHasRoom() =
        runTest {
            val handle = FakeAndroidDataChannelHandle(bufferedAmount = 0)
            val transport = AndroidWebRtcDataChannelTextTransport(handle)

            transport.sendText("control")

            assertEquals(listOf("control"), handle.sent)
        }
}

private class FakeAndroidDataChannelHandle(
    private val bufferedAmount: Long = 0L,
) : AndroidDataChannelHandle {
    private var observer: AndroidDataChannelObserver? = null
    val sent = mutableListOf<String>()

    override fun register(observer: AndroidDataChannelObserver) {
        this.observer = observer
    }

    override fun unregister() = Unit

    override fun sendText(payload: String): Boolean {
        sent += payload
        return true
    }

    override fun bufferedAmountBytes(): Long = bufferedAmount

    override fun close() = Unit
}
