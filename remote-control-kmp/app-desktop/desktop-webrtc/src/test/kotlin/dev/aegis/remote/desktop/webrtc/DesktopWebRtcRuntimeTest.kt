package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.desktop.capture.CaptureConfig
import dev.onvoid.webrtc.PeerConnectionFactory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopWebRtcRuntimeTest {
    @Test
    fun `native runtime is loaded before standalone screen capture`() {
        val backend = currentNativeDesktopCaptureBackend()
        if (backend == null || backend == NativeDesktopCaptureBackend.LinuxWaylandPortalPipeWire) return

        assertTrue(enumerateDesktopSources(backend).isNotEmpty())
    }

    @Test
    fun `native desktop source emits a frame on the supported display backend`() =
        runBlocking {
            assumeTrue(
                "Set -Daegis.nativeWebRtcIntegration=true to run the native desktop capture harness.",
                System.getProperty("aegis.nativeWebRtcIntegration") == "true",
            )
            assumeTrue(
                "Native desktop capture requires Windows Desktop Duplication or a real X11 session.",
                currentNativeDesktopCaptureBackend() in
                    setOf(NativeDesktopCaptureBackend.WindowsDesktopDuplication, NativeDesktopCaptureBackend.LinuxX11),
            )

            val monitor = DesktopWebRtcMonitorProvider().listMonitors().first()
            val session =
                NativeDesktopFrameSource()
                    .open(CaptureConfig(monitor.id, 640, 480, 5)) as NativeDesktopCaptureSession
            val factory = PeerConnectionFactory()
            val track = factory.createVideoTrack("aegis-native-capture-smoke", session.nativeSource)
            val firstFrame =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(10_000) { session.frames.first() }
                }

            try {
                session.bind(track)
                val capturedFrame = firstFrame.await()
                assertTrue(capturedFrame.width > 0)
                assertTrue(capturedFrame.height > 0)
            } finally {
                firstFrame.cancel()
                session.close()
                track.dispose()
                factory.dispose()
            }
        }

    private fun currentNativeDesktopCaptureBackend(): NativeDesktopCaptureBackend? =
        resolveNativeDesktopCaptureBackend(
            osName = System.getProperty("os.name").orEmpty(),
            sessionType = System.getenv("XDG_SESSION_TYPE"),
            display = System.getenv("DISPLAY"),
            waylandDisplay = System.getenv("WAYLAND_DISPLAY"),
        )
}
