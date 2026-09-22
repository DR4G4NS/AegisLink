package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.DesktopSessionBackend
import dev.aegis.remote.core.model.DesktopSessionEnvironment
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.MonitorInfo
import dev.aegis.remote.core.model.selectBackend
import dev.aegis.remote.core.monitor.MonitorProvider
import dev.onvoid.webrtc.media.video.VideoDesktopSource
import dev.onvoid.webrtc.media.video.desktop.DesktopSource
import java.awt.GraphicsEnvironment

/**
 * Publishes the same stable native IDs consumed by [VideoDesktopSource].
 *
 * java-webrtc does not expose geometry on [DesktopSource], so native sources
 * and AWT screen devices are paired by deterministic enumeration index on
 * Windows and X11. A topology count mismatch fails closed on both platforms:
 * advertising capture IDs with geometry from another display would make
 * absolute pointer input unsafe.
 */
class DesktopWebRtcMonitorProvider internal constructor(
    private val osName: String,
    private val sessionType: String? = null,
    private val display: String? = null,
    private val waylandDisplay: String? = null,
    private val headless: () -> Boolean,
    private val sourceEnumerator: (NativeDesktopCaptureBackend) -> List<DesktopSource>,
    private val geometryEnumerator: (NativeDesktopCaptureBackend) -> List<DesktopDisplayGeometry>,
) : MonitorProvider {
    constructor() : this(
        osName = System.getProperty("os.name").orEmpty(),
        sessionType = System.getenv("XDG_SESSION_TYPE"),
        display = System.getenv("DISPLAY"),
        waylandDisplay = System.getenv("WAYLAND_DISPLAY"),
        headless = { GraphicsEnvironment.isHeadless() },
        sourceEnumerator = ::enumerateDesktopSources,
        geometryEnumerator = ::enumerateDesktopDisplayGeometry,
    )

    override suspend fun listMonitors(): List<MonitorInfo> {
        val backend =
            resolveNativeDesktopCaptureBackend(osName, sessionType, display, waylandDisplay)
                ?: run {
                    if (DesktopSessionEnvironment(
                            osName = osName,
                            sessionType = sessionType,
                            waylandDisplay = waylandDisplay,
                            x11Display = display,
                        ).selectBackend().backend == DesktopSessionBackend.LinuxWayland
                    ) {
                        error(
                            "${AegisFailureCodes.CAPTURE_BACKEND_UNAVAILABLE}: " +
                                "Linux Wayland monitor enumeration belongs to the Portal/PipeWire consent session",
                        )
                    }
                    return emptyList()
                }
        if (headless()) return emptyList()

        val sources = sourceEnumerator(backend)
        val geometries = geometryEnumerator(backend)
        check(sources.size == geometries.size) {
            "${AegisFailureCodes.CAPTURE_TOPOLOGY_MISMATCH}: " +
                "${backend.topologyLabel} monitor topology mismatch (sources=${sources.size}, displays=${geometries.size})"
        }
        check(sources.map { it.id }.distinct().size == sources.size) {
            "${AegisFailureCodes.CAPTURE_DUPLICATE_SOURCE_ID}: ${backend.topologyLabel} desktop source IDs are not unique"
        }

        return sources.zip(geometries).mapIndexed { index, (source, geometry) ->
            MonitorInfo(
                id = MonitorId(source.id.toString()),
                name = source.title.orEmpty().ifBlank { "Monitor ${index + 1}" },
                width = geometry.width,
                height = geometry.height,
                scaleFactor = geometry.scaleFactor,
                originX = geometry.originX,
                originY = geometry.originY,
                primary = geometry.primary,
            )
        }
    }
}

internal data class DesktopDisplayGeometry(
    val width: Int,
    val height: Int,
    val scaleFactor: Float,
    val originX: Int,
    val originY: Int,
    val primary: Boolean,
)

private val NativeDesktopCaptureBackend.topologyLabel: String
    get() =
        when (this) {
            NativeDesktopCaptureBackend.WindowsDesktopDuplication -> "Windows"
            NativeDesktopCaptureBackend.LinuxX11 -> "Linux X11"
            NativeDesktopCaptureBackend.LinuxWaylandPortalPipeWire -> "Linux Wayland Portal/PipeWire"
        }

private fun enumerateDesktopDisplayGeometry(
    backend: NativeDesktopCaptureBackend,
): List<DesktopDisplayGeometry> =
    when (backend) {
        NativeDesktopCaptureBackend.WindowsDesktopDuplication -> {
            val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val primaryDevice = environment.defaultScreenDevice
            environment.screenDevices
                .map { device ->
                    val configuration = device.defaultConfiguration
                    val bounds = configuration.bounds
                    DesktopDisplayGeometry(
                        width = bounds.width,
                        height = bounds.height,
                        scaleFactor = configuration.defaultTransform.scaleX.toFloat(),
                        originX = bounds.x,
                        originY = bounds.y,
                        primary = device == primaryDevice,
                    )
                }
        }

        NativeDesktopCaptureBackend.LinuxX11 -> {
            enumerateLinuxX11Monitors().map(NativeDesktopMonitor::geometry)
        }

        NativeDesktopCaptureBackend.LinuxWaylandPortalPipeWire -> {
            emptyList()
        }
    }

@Deprecated("Use DesktopWebRtcMonitorProvider; the implementation supports Windows, Linux X11, and Portal/PipeWire Wayland")
typealias WindowsWebRtcMonitorProvider = DesktopWebRtcMonitorProvider

@Deprecated("Use DesktopDisplayGeometry; the geometry is shared by Windows and Linux X11")
internal typealias WindowsDisplayGeometry = DesktopDisplayGeometry
