package dev.aegis.remote.android.webrtc

import java.util.concurrent.atomic.AtomicBoolean

/** Emits the streaming transition exactly once for the first decoded frame of a track. */
internal class AndroidFirstFrameGate(
    private val onFirstFrame: () -> Unit,
) {
    private val observed = AtomicBoolean(false)

    val hasObservedFirstFrame: Boolean
        get() = observed.get()

    fun observeFrame() {
        if (observed.compareAndSet(false, true)) {
            onFirstFrame()
        }
    }
}
