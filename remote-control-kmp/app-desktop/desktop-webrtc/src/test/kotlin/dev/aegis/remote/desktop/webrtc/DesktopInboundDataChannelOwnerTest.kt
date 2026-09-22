package dev.aegis.remote.desktop.webrtc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopInboundDataChannelOwnerTest {
    @Test
    fun `transport shutdown and generation drain touch native lifecycle exactly once`() {
        val native = CountingDataChannelHandle()
        val owner = DesktopInboundDataChannelOwner(native)
        owner.register(NoOpDesktopDataChannelObserver)

        owner.unregister()
        owner.close()
        owner.unregister()
        owner.close()
        owner.dispose()
        owner.dispose()

        assertEquals(1, native.registerCount)
        assertEquals(1, native.unregisterCount)
        assertEquals(1, native.closeCount)
        assertEquals(1, native.disposeCount)
    }

    @Test
    fun `known webrtc java release error is terminal and not retried`() {
        val native = CountingDataChannelHandle(disposeError = Error("Native object was not deleted. A reference is still around somewhere."))
        val owner = DesktopInboundDataChannelOwner(native)

        owner.dispose()
        owner.dispose()

        assertEquals(1, native.disposeCount)
    }

    @Test
    fun `unrelated native disposal error is not hidden`() {
        val owner = DesktopInboundDataChannelOwner(CountingDataChannelHandle(disposeError = Error("unexpected")))

        assertFailsWith<Error> { owner.dispose() }
    }

    @Test
    fun `rejected unregistered channel is closed early and disposed by generation owner`() {
        val native = CountingDataChannelHandle()
        val owner = DesktopInboundDataChannelOwner(native)

        owner.close()
        owner.dispose()

        assertEquals(0, native.unregisterCount)
        assertEquals(1, native.closeCount)
        assertEquals(1, native.disposeCount)
    }
}

private object NoOpDesktopDataChannelObserver : DesktopDataChannelObserver {
    override fun onStateChange() = Unit

    override fun onMessage(payload: String) = Unit
}

private class CountingDataChannelHandle(
    private val disposeError: Error? = null,
) : DesktopDataChannelHandle {
    var registerCount = 0
    var unregisterCount = 0
    var closeCount = 0
    var disposeCount = 0

    override fun register(observer: DesktopDataChannelObserver) {
        registerCount++
    }

    override fun unregister() {
        unregisterCount++
    }

    override fun sendText(payload: String) = Unit

    override fun close() {
        closeCount++
    }

    override fun dispose() {
        disposeCount++
        disposeError?.let { throw it }
    }
}
