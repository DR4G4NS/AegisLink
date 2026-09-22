package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.DesktopSessionBackend
import dev.aegis.remote.core.model.DesktopSessionEnvironment
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.selectBackend
import dev.aegis.remote.desktop.capture.CaptureConfig
import dev.aegis.remote.desktop.capture.CaptureSession
import dev.aegis.remote.desktop.capture.DesktopFrameSource
import dev.aegis.remote.desktop.capture.DesktopVideoFrame
import dev.aegis.remote.desktop.capture.FrameSourceCapabilities
import dev.onvoid.webrtc.media.video.VideoDesktopSource
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.VideoTrackSink
import dev.onvoid.webrtc.media.video.desktop.DesktopSource
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The WebRTC native desktop source uses Desktop Duplication on Windows, the
 * upstream X11 capturer on Linux X11, and the explicit xdg-desktop-portal /
 * PipeWire adapter on native Wayland. The same capture session is attached to
 * the outgoing track and observed for frame telemetry, so capture selection
 * and transmitted pixels cannot diverge.
 */
class NativeDesktopFrameSource(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val sessionType: String? = System.getenv("XDG_SESSION_TYPE"),
    private val display: String? = System.getenv("DISPLAY"),
    private val waylandDisplay: String? = System.getenv("WAYLAND_DISPLAY"),
    private val sourceFactory: () -> VideoDesktopSource = ::VideoDesktopSource,
    private val sourceEnumerator: () -> List<DesktopSource> = {
        enumerateDesktopSources(
            requireNotNull(resolveNativeDesktopCaptureBackend(osName, sessionType, display, waylandDisplay)),
        )
    },
    private val waylandCaptureFactory: (CaptureConfig) -> CaptureSession = { config ->
        PortalPipeWireCaptureSession(
            initialConfig = config,
            streamFactory = ProcessPipeWireFrameStreamFactory(),
        )
    },
) : DesktopFrameSource {
    override val capabilities =
        FrameSourceCapabilities(
            backend =
                when (resolveNativeDesktopCaptureBackend(osName, sessionType, display, waylandDisplay)) {
                    NativeDesktopCaptureBackend.WindowsDesktopDuplication -> "windows-desktop-duplication"
                    NativeDesktopCaptureBackend.LinuxX11 -> "linux-x11-native"
                    NativeDesktopCaptureBackend.LinuxWaylandPortalPipeWire -> "linux-wayland-portal-pipewire"
                    null -> "native-desktop-unavailable"
                },
            supportsMonitorSelection =
                resolveNativeDesktopCaptureBackend(osName, sessionType, display, waylandDisplay) !=
                    NativeDesktopCaptureBackend.LinuxWaylandPortalPipeWire,
            supportsLiveReconfigure =
                resolveNativeDesktopCaptureBackend(osName, sessionType, display, waylandDisplay) !=
                    NativeDesktopCaptureBackend.LinuxWaylandPortalPipeWire,
            hardwareAccelerated = osName.contains("windows", ignoreCase = true),
        )

    override suspend fun open(config: CaptureConfig): CaptureSession {
        val backend =
            resolveNativeDesktopCaptureBackend(osName, sessionType, display, waylandDisplay)
                ?: error(nativeDesktopCaptureUnavailableMessage(osName, sessionType, waylandDisplay))
        if (backend == NativeDesktopCaptureBackend.LinuxWaylandPortalPipeWire) {
            return waylandCaptureFactory(config)
        }
        val sources = sourceEnumerator()
        check(sources.isNotEmpty()) {
            "${AegisFailureCodes.CAPTURE_SOURCE_UNAVAILABLE}: ${backend.displayName} reported no capturable display"
        }
        return NativeDesktopCaptureSession(sourceFactory(), sources, config)
    }
}

internal class NativeDesktopCaptureSession(
    internal val nativeSource: VideoDesktopSource,
    private val sources: List<DesktopSource>,
    initialConfig: CaptureConfig,
) : DesktopWebRtcCaptureSession,
    VideoTrackSink {
    private val frameEvents = MutableSharedFlow<DesktopVideoFrame>(extraBufferCapacity = 8)
    private var track: VideoTrack? = null
    private var closed = false
    override val frames: Flow<DesktopVideoFrame> = frameEvents.asSharedFlow()

    init {
        applyConfig(initialConfig)
    }

    override fun createVideoTrack(factory: dev.onvoid.webrtc.PeerConnectionFactory): VideoTrack = factory.createVideoTrack("aegis-desktop-video", nativeSource)

    override fun bind(track: VideoTrack) {
        check(!closed) { "${AegisFailureCodes.CAPTURE_SESSION_CLOSED}: Capture session is closed" }
        this.track?.removeSink(this)
        this.track = track
        track.addSink(this)
        nativeSource.start()
    }

    override fun onVideoFrame(frame: VideoFrame) {
        frameEvents.tryEmit(
            DesktopVideoFrame(
                width = frame.buffer.width,
                height = frame.buffer.height,
                capturedAtNanos = frame.timestampNs,
            ),
        )
    }

    override suspend fun selectMonitor(id: MonitorId) {
        check(!closed) { "${AegisFailureCodes.CAPTURE_SESSION_CLOSED}: Capture session is closed" }
        nativeSource.setSourceId(selectSource(sources, id).id, false)
    }

    override suspend fun reconfigure(config: CaptureConfig) {
        check(!closed) { "${AegisFailureCodes.CAPTURE_SESSION_CLOSED}: Capture session is closed" }
        applyConfig(config)
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        track?.removeSink(this)
        track?.dispose()
        track = null
        nativeSource.stop()
        nativeSource.dispose()
    }

    private fun applyConfig(config: CaptureConfig) {
        nativeSource.setFrameRate(config.maxFps)
        nativeSource.setMaxFrameSize(config.maxWidth, config.maxHeight)
        nativeSource.setSourceId(selectSource(sources, config.monitorId).id, false)
    }
}

internal fun selectSource(
    sources: List<DesktopSource>,
    preferred: MonitorId?,
): DesktopSource {
    if (preferred == null) return sources.first()
    return checkNotNull(sources.firstOrNull { it.id.toString() == preferred.value }) {
        "${AegisFailureCodes.CAPTURE_UNKNOWN_SOURCE}: Unknown native desktop source id ${preferred.value}"
    }
}

internal fun enumerateDesktopSources(backend: NativeDesktopCaptureBackend): List<DesktopSource> =
    when (backend) {
        NativeDesktopCaptureBackend.WindowsDesktopDuplication -> {
            // ScreenCapturer does not load webrtc-java's JNI library itself.
            // Monitor discovery runs before a PeerConnectionFactory exists, so
            // bootstrap the library explicitly.
            DesktopWebRtcRuntime.ensureLoaded()
            val capturer = ScreenCapturer()
            try {
                capturer.getDesktopSources()
            } finally {
                capturer.dispose()
            }
        }

        NativeDesktopCaptureBackend.LinuxX11 -> {
            enumerateLinuxX11Monitors().map(NativeDesktopMonitor::source)
        }

        NativeDesktopCaptureBackend.LinuxWaylandPortalPipeWire -> {
            emptyList()
        }
    }

internal enum class NativeDesktopCaptureBackend(
    val displayName: String,
) {
    WindowsDesktopDuplication("Windows Desktop Duplication"),
    LinuxX11("Linux X11 native capture"),
    LinuxWaylandPortalPipeWire("Linux Wayland Portal/PipeWire capture"),
}

internal fun resolveNativeDesktopCaptureBackend(
    osName: String,
    sessionType: String?,
    display: String?,
    waylandDisplay: String?,
): NativeDesktopCaptureBackend? =
    when (
        DesktopSessionEnvironment(
            osName = osName,
            sessionType = sessionType,
            waylandDisplay = waylandDisplay,
            x11Display = display,
        ).selectBackend().backend
    ) {
        DesktopSessionBackend.Windows -> NativeDesktopCaptureBackend.WindowsDesktopDuplication

        DesktopSessionBackend.LinuxX11 -> NativeDesktopCaptureBackend.LinuxX11

        DesktopSessionBackend.LinuxWayland -> NativeDesktopCaptureBackend.LinuxWaylandPortalPipeWire

        DesktopSessionBackend.Headless,
        DesktopSessionBackend.Unsupported,
        -> null
    }

private fun nativeDesktopCaptureUnavailableMessage(
    osName: String,
    sessionType: String?,
    waylandDisplay: String?,
): String {
    val wayland =
        osName.contains("linux", ignoreCase = true) &&
            DesktopSessionEnvironment(
                osName = osName,
                sessionType = sessionType,
                waylandDisplay = waylandDisplay,
                x11Display = null,
            ).selectBackend().backend == DesktopSessionBackend.LinuxWayland
    return if (wayland) {
        "${AegisFailureCodes.CAPTURE_BACKEND_UNAVAILABLE}: " +
            "Linux Wayland capture requires xdg-desktop-portal, PipeWire, and the packaged native bridge"
    } else {
        "${AegisFailureCodes.CAPTURE_BACKEND_UNAVAILABLE}: Native desktop capture is unavailable on $osName"
    }
}

@Deprecated("Use NativeDesktopFrameSource; the implementation supports Windows, Linux X11, and Portal/PipeWire Wayland")
typealias WindowsDesktopDuplicationFrameSource = NativeDesktopFrameSource

@Deprecated("Use NativeDesktopCaptureSession; the implementation supports Windows, Linux X11, and Portal/PipeWire Wayland")
internal typealias WindowsDesktopDuplicationCaptureSession = NativeDesktopCaptureSession
