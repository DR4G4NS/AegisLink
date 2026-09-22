package dev.aegis.remote.desktop.webrtc

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.unix.X11
import com.sun.jna.ptr.IntByReference
import dev.aegis.remote.core.model.AegisFailureCodes
import dev.onvoid.webrtc.media.video.desktop.DesktopSource

/**
 * XRandR monitor metadata used by both monitor discovery and WebRTC source
 * selection. WebRTC's X11 capturer uses the monitor-name Atom as SourceId.
 *
 * webrtc-java 0.14's standalone ScreenCapturer wrapper has an invalid native
 * handle on Linux. Calling getDesktopSources() through it can terminate the
 * JVM, while VideoDesktopSource itself captures X11 correctly. Reading the
 * same IDs directly from XRandR avoids that unsafe wrapper.
 */
internal data class NativeDesktopMonitor(
    val source: DesktopSource,
    val geometry: DesktopDisplayGeometry,
)

internal fun enumerateLinuxX11Monitors(
    displayName: String? = System.getenv("DISPLAY"),
): List<NativeDesktopMonitor> {
    val x11 = X11.INSTANCE
    val display =
        checkNotNull(x11.XOpenDisplay(displayName)) {
            "${AegisFailureCodes.CAPTURE_SOURCE_UNAVAILABLE}: Unable to open X11 display ${displayName.orEmpty()}"
        }

    return try {
        val root = x11.XDefaultRootWindow(display)
        val count = IntByReference()
        val monitorsPointer =
            try {
                XRandR.INSTANCE.XRRGetMonitors(display, root, 1, count)
            } catch (_: UnsatisfiedLinkError) {
                null
            }

        if (monitorsPointer == null || count.value <= 0) {
            listOf(fullDesktopMonitor(x11, display))
        } else {
            try {
                val monitors =
                    XRandRMonitorInfo(monitorsPointer)
                        .toArray(count.value)
                        .map { it as XRandRMonitorInfo }
                monitors.mapIndexed { index, monitor ->
                    val sourceId = monitor.name.toLong()
                    val title =
                        x11
                            .XGetAtomName(display, X11.Atom(sourceId))
                            ?.takeIf(String::isNotBlank)
                            ?: "Monitor ${index + 1}"
                    NativeDesktopMonitor(
                        source = DesktopSource(title, sourceId),
                        geometry =
                            DesktopDisplayGeometry(
                                width = monitor.width,
                                height = monitor.height,
                                scaleFactor = 1f,
                                originX = monitor.x,
                                originY = monitor.y,
                                primary = monitor.primary != 0,
                            ),
                    )
                }
            } finally {
                XRandR.INSTANCE.XRRFreeMonitors(monitorsPointer)
            }
        }
    } finally {
        x11.XCloseDisplay(display)
    }
}

private fun fullDesktopMonitor(
    x11: X11,
    display: X11.Display,
): NativeDesktopMonitor {
    val screen = x11.XDefaultScreen(display)
    return NativeDesktopMonitor(
        source = DesktopSource("X11 desktop", FULL_DESKTOP_SOURCE_ID),
        geometry =
            DesktopDisplayGeometry(
                width = x11.XDisplayWidth(display, screen),
                height = x11.XDisplayHeight(display, screen),
                scaleFactor = 1f,
                originX = 0,
                originY = 0,
                primary = true,
            ),
    )
}

private const val FULL_DESKTOP_SOURCE_ID = -1L

@Suppress("FunctionNaming")
private interface XRandR : Library {
    fun XRRGetMonitors(
        display: X11.Display,
        window: X11.Window,
        getActive: Int,
        count: IntByReference,
    ): Pointer?

    fun XRRFreeMonitors(monitors: Pointer?)

    companion object {
        val INSTANCE: XRandR = Native.load("Xrandr", XRandR::class.java)
    }
}

@Structure.FieldOrder(
    "name",
    "primary",
    "automatic",
    "outputCount",
    "x",
    "y",
    "width",
    "height",
    "physicalWidth",
    "physicalHeight",
    "outputs",
)
internal class XRandRMonitorInfo : Structure {
    @JvmField
    var name: NativeLong = NativeLong()

    @JvmField
    var primary: Int = 0

    @JvmField
    var automatic: Int = 0

    @JvmField
    var outputCount: Int = 0

    @JvmField
    var x: Int = 0

    @JvmField
    var y: Int = 0

    @JvmField
    var width: Int = 0

    @JvmField
    var height: Int = 0

    @JvmField
    var physicalWidth: Int = 0

    @JvmField
    var physicalHeight: Int = 0

    @JvmField
    var outputs: Pointer? = null

    constructor() : super()

    constructor(pointer: Pointer) : super(pointer) {
        read()
    }
}
