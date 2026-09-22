package dev.aegis.remote.desktop.webrtc

import dev.onvoid.webrtc.RTCDataChannel
import java.util.concurrent.locks.ReentrantLock

/** Owns every remotely-created DataChannel for one peer-connection generation. */
internal class DesktopInboundDataChannelGeneration {
    private val lock = ReentrantLock()
    private val callbacksDrained = lock.newCondition()
    private val owners = mutableListOf<DesktopInboundDataChannelOwner>()
    private var activeCallbacks = 0
    private var accepting = true
    private var drained = false

    /** The callback remains counted until admission and observer registration finish. */
    fun receive(
        channel: RTCDataChannel,
        bind: (DesktopInboundDataChannelOwner) -> Unit,
    ) {
        val owner = DesktopInboundDataChannelOwner(NativeDesktopDataChannelHandle(channel))
        val shouldBind =
            lock.run {
                lock()
                try {
                    check(!drained) { "Inbound DataChannel arrived after its peer generation was drained" }
                    owners += owner
                    activeCallbacks++
                    accepting
                } finally {
                    unlock()
                }
            }
        try {
            if (shouldBind) bind(owner) else owner.close()
        } finally {
            lock.lock()
            try {
                activeCallbacks--
                if (activeCallbacks == 0) callbacksDrained.signalAll()
            } finally {
                lock.unlock()
            }
        }
    }

    /** Stops callbacks racing with shutdown from binding new protocol transports. */
    fun seal() {
        lock.lock()
        try {
            accepting = false
        } finally {
            lock.unlock()
        }
    }

    /** Must run after RTCPeerConnection.close and before PeerConnectionFactory.dispose. */
    fun drain() {
        val generationOwners =
            lock.run {
                lock()
                try {
                    while (activeCallbacks != 0) callbacksDrained.awaitUninterruptibly()
                    drained = true
                    owners.toList().also { owners.clear() }
                } finally {
                    unlock()
                }
            }
        var firstUnexpectedError: Error? = null
        generationOwners.forEach { owner ->
            try {
                owner.dispose()
            } catch (error: Error) {
                firstUnexpectedError?.addSuppressed(error) ?: run { firstUnexpectedError = error }
            }
        }
        firstUnexpectedError?.let { throw it }
    }
}

/**
 * Serializes the native observer/close/dispose boundary. Protocol transports may
 * close early, but the generation remains the native owner's final disposal point.
 */
internal class DesktopInboundDataChannelOwner(
    private val delegate: DesktopDataChannelHandle,
) : DesktopDataChannelHandle {
    private val lock = Any()
    private var registered = false
    private var unregistered = false
    private var closed = false
    private var disposed = false

    override fun register(observer: DesktopDataChannelObserver) {
        synchronized(lock) {
            check(!disposed) { "WebRTC DataChannel is already disposed" }
            check(!registered) { "WebRTC DataChannel observer is already registered" }
            delegate.register(observer)
            registered = true
        }
    }

    override fun unregister() {
        synchronized(lock) {
            if (!registered || unregistered) return
            unregistered = true
            delegate.unregister()
        }
    }

    override fun sendText(payload: String) = delegate.sendText(payload)

    override fun bufferedAmountBytes(): Long = delegate.bufferedAmountBytes()

    override fun isOpen(): Boolean = synchronized(lock) { !closed && !disposed } && delegate.isOpen()

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            delegate.close()
        }
    }

    override fun dispose() {
        synchronized(lock) {
            if (disposed) return
            // Mark first so even a native failure cannot cause a second native disposal attempt.
            disposed = true
            if (registered && !unregistered) {
                unregistered = true
                delegate.unregister()
            }
            if (!closed) {
                closed = true
                delegate.close()
            }
            try {
                delegate.dispose()
            } catch (error: Error) {
                // webrtc-java 0.14.0 clears the Java native handle before reporting that
                // another native reference still existed. Disposal is therefore terminal
                // and must never be retried, but unrelated VM/native errors still escape.
                if (error.message != WEBRTC_JAVA_RELEASE_REFERENCE_ERROR) throw error
            }
        }
    }
}

private const val WEBRTC_JAVA_RELEASE_REFERENCE_ERROR =
    "Native object was not deleted. A reference is still around somewhere."
