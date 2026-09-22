package dev.aegis.remote.desktop.webrtc

import dev.onvoid.webrtc.PeerConnectionFactory

/** Loads the JNI library before using native media classes without a factory instance. */
internal object DesktopWebRtcRuntime {
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            Class.forName(
                PeerConnectionFactory::class.java.name,
                true,
                PeerConnectionFactory::class.java.classLoader,
            )
            loaded = true
        }
    }
}
