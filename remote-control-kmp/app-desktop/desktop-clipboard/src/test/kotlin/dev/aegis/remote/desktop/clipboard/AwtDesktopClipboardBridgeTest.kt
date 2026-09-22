package dev.aegis.remote.desktop.clipboard

import dev.aegis.remote.core.model.AppError
import dev.aegis.remote.core.model.ClipboardPayload
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AwtDesktopClipboardBridgeTest {
    @Test
    fun readsTextClipboardPayload() =
        runTest {
            val bridge =
                AwtDesktopClipboardBridge(
                    access = FakeDesktopClipboardAccess(text = "hello"),
                    headless = { false },
                )

            val payload = bridge.read()

            assertEquals("hello", assertIs<ClipboardPayload.Text>(payload).value)
        }

    @Test
    fun readsNullWhenClipboardHasNoText() =
        runTest {
            val bridge =
                AwtDesktopClipboardBridge(
                    access = FakeDesktopClipboardAccess(text = null),
                    headless = { false },
                )

            assertEquals(null, bridge.read())
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun writesTextAndEmitsChange() =
        runTest {
            val access = FakeDesktopClipboardAccess()
            val bridge = AwtDesktopClipboardBridge(access = access, headless = { false })
            val change = async(start = CoroutineStart.UNDISPATCHED) { bridge.changes.first() }
            runCurrent()

            bridge.write(ClipboardPayload.Text("copied"))

            assertEquals("copied", access.text)
            assertEquals("copied", assertIs<ClipboardPayload.Text>(change.await()).value)
        }

    @Test
    fun suppressesRemoteWriteEchoWithoutRetainingClipboardTextInTelemetry() =
        runTest {
            val bridge = AwtDesktopClipboardBridge(access = FakeDesktopClipboardAccess(), headless = { false }, clock = { 1_000L })
            val payload = ClipboardPayload.Text("remote")

            bridge.writeFromRemote(payload)

            assertFalse(bridge.shouldForwardChange(payload))
        }

    @Test
    fun suppressesRemoteEchoAcrossConcurrentAuthorizedSessionBridges() =
        runTest {
            val sharedGuard = DesktopClipboardLoopGuard { 1_000L }
            val source =
                AwtDesktopClipboardBridge(
                    access = FakeDesktopClipboardAccess(),
                    headless = { false },
                    loopGuard = sharedGuard,
                )
            val otherSession =
                AwtDesktopClipboardBridge(
                    access = FakeDesktopClipboardAccess(),
                    headless = { false },
                    loopGuard = sharedGuard,
                )
            val payload = ClipboardPayload.Text("remote from device A")

            source.writeFromRemote(payload)

            assertFalse(otherSession.shouldForwardChange(payload))
        }

    @Test
    fun sharedEchoGuardStillForwardsOneLocalChangeToEachAuthorizedSession() {
        val sharedGuard = DesktopClipboardLoopGuard { 1_000L }
        val first = AwtDesktopClipboardBridge(FakeDesktopClipboardAccess(), { false }, loopGuard = sharedGuard)
        val second = AwtDesktopClipboardBridge(FakeDesktopClipboardAccess(), { false }, loopGuard = sharedGuard)
        val payload = ClipboardPayload.Text("local")

        assertTrue(first.shouldForwardChange(payload))
        assertTrue(second.shouldForwardChange(payload))
    }

    @Test
    fun headlessReadFailsWithCapabilityError() =
        runTest {
            val bridge =
                AwtDesktopClipboardBridge(
                    access = FakeDesktopClipboardAccess(text = "hello"),
                    headless = { true },
                )

            val error =
                assertFailsWith<DesktopClipboardException> {
                    bridge.read()
                }

            assertIs<AppError.CapabilityUnavailable>(error.appError)
        }

    @Test
    fun headlessWriteFailsWithCapabilityError() =
        runTest {
            val bridge =
                AwtDesktopClipboardBridge(
                    access = FakeDesktopClipboardAccess(),
                    headless = { true },
                )

            val error =
                assertFailsWith<DesktopClipboardException> {
                    bridge.write(ClipboardPayload.Text("blocked"))
                }

            assertIs<AppError.CapabilityUnavailable>(error.appError)
        }

    @Test
    fun nativeReadFailureIsReportedAsCapabilityError() =
        runTest {
            val bridge =
                AwtDesktopClipboardBridge(
                    access = FakeDesktopClipboardAccess(readFailure = IllegalStateException("clipboard is busy")),
                    headless = { false },
                )

            val error =
                assertFailsWith<DesktopClipboardException> {
                    bridge.read()
                }

            assertIs<AppError.CapabilityUnavailable>(error.appError)
            assertTrue(error.appError.message.contains("read failed"))
        }

    @Test
    fun nativeWriteFailureIsReportedAsCapabilityError() =
        runTest {
            val bridge =
                AwtDesktopClipboardBridge(
                    access = FakeDesktopClipboardAccess(writeFailure = IllegalStateException("clipboard is busy")),
                    headless = { false },
                )

            val error =
                assertFailsWith<DesktopClipboardException> {
                    bridge.write(ClipboardPayload.Text("blocked"))
                }

            assertIs<AppError.CapabilityUnavailable>(error.appError)
            assertTrue(error.appError.message.contains("write failed"))
        }

    @Test
    fun factorySelectsAwtToolkitForWindows() {
        val selection =
            DesktopClipboardBridgeFactory(
                osName = "Windows 11",
                headless = { false },
            ).create()

        assertEquals(DesktopClipboardBackend.AwtToolkit, selection.backend)
        assertIs<AwtDesktopClipboardBridge>(selection.bridge)
    }

    @Test
    fun factoryRequiresWlClipboardForWaylandClipboard() =
        runTest {
            val selection =
                DesktopClipboardBridgeFactory(
                    osName = "Linux",
                    sessionType = "wayland",
                    waylandDisplay = "wayland-0",
                    wlClipboardAvailable = { false },
                    headless = { false },
                ).create()

            assertEquals(DesktopClipboardBackend.LinuxWaylandWlClipboard, selection.backend)
            val error =
                assertFailsWith<DesktopClipboardException> {
                    selection.bridge.read()
                }
            assertTrue(error.appError.message.contains("wl-copy"))
        }

    @Test
    fun factorySelectsWlClipboardForLinuxWaylandWhenAvailable() {
        val selection =
            DesktopClipboardBridgeFactory(
                osName = "Linux",
                sessionType = "wayland",
                waylandDisplay = "wayland-0",
                wlClipboardAvailable = { true },
                headless = { false },
            ).create()

        assertEquals(DesktopClipboardBackend.LinuxWaylandWlClipboard, selection.backend)
        assertIs<LinuxWaylandClipboardBridge>(selection.bridge)
    }

    @Test
    fun factorySelectsXclipForLinuxX11WhenAvailable() {
        val selection =
            DesktopClipboardBridgeFactory(
                osName = "Linux",
                sessionType = "x11",
                display = ":0",
                xclipAvailable = { true },
                headless = { false },
            ).create()

        assertEquals(DesktopClipboardBackend.LinuxX11Xclip, selection.backend)
        assertIs<LinuxX11ClipboardBridge>(selection.bridge)
    }
}

private class FakeDesktopClipboardAccess(
    var text: String? = null,
    private val readFailure: Throwable? = null,
    private val writeFailure: Throwable? = null,
) : DesktopClipboardAccess {
    override fun readText(): String? {
        readFailure?.let { throw it }
        return text
    }

    override fun writeText(text: String) {
        writeFailure?.let { throw it }
        this.text = text
    }
}
