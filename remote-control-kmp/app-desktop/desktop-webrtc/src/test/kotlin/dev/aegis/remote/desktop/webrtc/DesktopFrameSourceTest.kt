package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.MonitorInfo
import dev.aegis.remote.desktop.capture.CaptureConfig
import dev.onvoid.webrtc.media.video.desktop.DesktopSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopFrameSourceTest {
    @Test
    fun `windows frame source declares desktop duplication capabilities`() {
        val capabilities = NativeDesktopFrameSource(osName = "Windows 11").capabilities
        assertEquals("windows-desktop-duplication", capabilities.backend)
        assertTrue(capabilities.hardwareAccelerated)
        assertTrue(capabilities.supportsMonitorSelection)
        assertTrue(capabilities.supportsLiveReconfigure)
    }

    @Test
    fun `monitor selection uses exact stable native id and defaults only without a preference`() {
        val sources = listOf(DesktopSource("Primary", 10), DesktopSource("Projector", 20))
        assertEquals(20, selectSource(sources, MonitorId("20")).id)
        assertEquals(10, selectSource(sources, null).id)

        val error = assertFailsWith<IllegalStateException> { selectSource(sources, MonitorId("missing")) }
        assertEquals("CAP-5003: Unknown native desktop source id missing", error.message)
    }

    @Test
    fun `monitor provider publishes native ids with matching awt geometry`() =
        runTest {
            val provider =
                DesktopWebRtcMonitorProvider(
                    osName = "Windows 11",
                    headless = { false },
                    sourceEnumerator = { _ ->
                        listOf(DesktopSource("Internal display", 10), DesktopSource("Projector", 20))
                    },
                    geometryEnumerator = { _ ->
                        listOf(
                            DesktopDisplayGeometry(1920, 1080, 1.25f, 0, 0, primary = false),
                            DesktopDisplayGeometry(1280, 1024, 1f, -1280, 40, primary = true),
                        )
                    },
                )

            assertEquals(
                listOf(
                    MonitorInfo(MonitorId("10"), "Internal display", 1920, 1080, 1.25f, 0, 0, primary = false),
                    MonitorInfo(MonitorId("20"), "Projector", 1280, 1024, 1f, -1280, 40, primary = true),
                ),
                provider.listMonitors(),
            )
        }

    @Test
    fun `monitor provider fails closed when native and awt topology counts differ`() =
        runTest {
            val provider =
                DesktopWebRtcMonitorProvider(
                    osName = "Windows 11",
                    headless = { false },
                    sourceEnumerator = { _ ->
                        listOf(DesktopSource("Primary display", 10), DesktopSource("Projector", 20))
                    },
                    geometryEnumerator = { _ ->
                        listOf(DesktopDisplayGeometry(1920, 1080, 1f, 0, 0, primary = true))
                    },
                )

            val error = assertFailsWith<IllegalStateException> { provider.listMonitors() }
            assertEquals(
                "CAP-5005: Windows monitor topology mismatch (sources=2, displays=1)",
                error.message,
            )
        }

    @Test
    fun `monitor provider rejects duplicate native source ids`() =
        runTest {
            val provider =
                DesktopWebRtcMonitorProvider(
                    osName = "Windows 11",
                    headless = { false },
                    sourceEnumerator = { _ ->
                        listOf(DesktopSource("Primary display", 10), DesktopSource("Mirrored display", 10))
                    },
                    geometryEnumerator = { _ ->
                        listOf(
                            DesktopDisplayGeometry(1920, 1080, 1f, 0, 0, primary = true),
                            DesktopDisplayGeometry(1920, 1080, 1f, 1920, 0, primary = false),
                        )
                    },
                )

            val error = assertFailsWith<IllegalStateException> { provider.listMonitors() }
            assertEquals("CAP-5006: Windows desktop source IDs are not unique", error.message)
        }

    @Test
    fun `windows backend refuses accidental use on another platform before native capture`() =
        runTest {
            val source = NativeDesktopFrameSource(osName = "Mac OS X")
            val error =
                assertFailsWith<IllegalStateException> {
                    source.open(CaptureConfig(null, 1920, 1080, 30))
                }
            assertEquals(
                "CAP-5001: Native desktop capture is unavailable on Mac OS X",
                error.message,
            )
        }

    @Test
    fun `windows backend reports an empty native source list causally`() =
        runTest {
            val source =
                NativeDesktopFrameSource(
                    osName = "Windows 11",
                    sourceEnumerator = { emptyList() },
                )
            val error =
                assertFailsWith<IllegalStateException> {
                    source.open(CaptureConfig(null, 1920, 1080, 30))
                }
            assertEquals(
                "CAP-5002: Windows Desktop Duplication reported no capturable display",
                error.message,
            )
        }

    @Test
    fun `linux x11 frame source exposes native capture without claiming acceleration`() {
        val capabilities =
            NativeDesktopFrameSource(
                osName = "Linux",
                sessionType = "x11",
                display = ":0",
                waylandDisplay = null,
            ).capabilities

        assertEquals("linux-x11-native", capabilities.backend)
        assertEquals(false, capabilities.hardwareAccelerated)
        assertTrue(capabilities.supportsMonitorSelection)
    }

    @Test
    fun `linux x11 monitor provider preserves native ids and fails closed on topology mismatch`() =
        runTest {
            val provider =
                DesktopWebRtcMonitorProvider(
                    osName = "Linux",
                    sessionType = "x11",
                    display = ":0",
                    waylandDisplay = null,
                    headless = { false },
                    sourceEnumerator = { _ ->
                        listOf(DesktopSource("X11 display", 41))
                    },
                    geometryEnumerator = { _ ->
                        listOf(DesktopDisplayGeometry(2560, 1440, 1f, -2560, 0, primary = true))
                    },
                )

            assertEquals(
                listOf(
                    MonitorInfo(MonitorId("41"), "X11 display", 2560, 1440, 1f, -2560, 0, primary = true),
                ),
                provider.listMonitors(),
            )
        }

    @Test
    fun `linux wayland selects the portal adapter without touching native xwayland enumeration`() =
        runTest {
            var enumerated = false
            val source =
                NativeDesktopFrameSource(
                    osName = "Linux",
                    sessionType = "wayland",
                    display = ":1",
                    waylandDisplay = "wayland-0",
                    sourceEnumerator = {
                        enumerated = true
                        emptyList()
                    },
                    waylandCaptureFactory = { config ->
                        PortalPipeWireCaptureSession(
                            initialConfig = config,
                            streamFactory = PipeWireFrameStreamFactory { error("test stream must not start during open") },
                        )
                    },
                )

            val session = source.open(CaptureConfig(null, 1920, 1080, 30))

            assertEquals("linux-wayland-portal-pipewire", source.capabilities.backend)
            assertTrue(session is PortalPipeWireCaptureSession)
            assertEquals(false, enumerated)
            session.close()
        }

    @Test
    fun `linux wayland monitor discovery does not claim an xwayland topology`() =
        runTest {
            var enumerated = false
            val provider =
                DesktopWebRtcMonitorProvider(
                    osName = "Linux",
                    sessionType = "wayland",
                    display = null,
                    waylandDisplay = "wayland-0",
                    headless = { true },
                    sourceEnumerator = { _ ->
                        enumerated = true
                        emptyList()
                    },
                    geometryEnumerator = { _ ->
                        enumerated = true
                        emptyList()
                    },
                )

            assertTrue(provider.listMonitors().isEmpty())
            assertEquals(false, enumerated)
        }

    @Test
    fun `explicit x11 session wins over a stale wayland display marker`() {
        assertEquals(
            NativeDesktopCaptureBackend.LinuxX11,
            resolveNativeDesktopCaptureBackend(
                osName = "Linux",
                sessionType = "x11",
                display = ":1",
                waylandDisplay = "wayland-0",
            ),
        )
    }

    @Test
    fun `windows backend ignores unix display markers`() {
        assertEquals(
            NativeDesktopCaptureBackend.WindowsDesktopDuplication,
            resolveNativeDesktopCaptureBackend(
                osName = "Windows 11",
                sessionType = "wayland",
                display = ":1",
                waylandDisplay = "wayland-0",
            ),
        )
    }
}
