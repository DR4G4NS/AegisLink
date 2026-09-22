package dev.aegis.remote.desktop.input

import dev.aegis.remote.core.input.KeyCode
import dev.aegis.remote.core.input.MouseButtonType
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.AppError
import dev.aegis.remote.core.model.MonitorId
import kotlinx.coroutines.test.runTest
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AwtRemoteInputExecutorTest {
    @Test
    fun executesMouseMoveButtonAndScroll() =
        runTest {
            val robot = RecordingDesktopRobotInput()
            val executor = AwtRemoteInputExecutor(robot, headless = { false })

            executor.execute(RemoteInputEvent.MouseMove(10, 20, MonitorId("primary")))
            executor.execute(RemoteInputEvent.MouseButton(MouseButtonType.Left, pressed = true))
            executor.execute(RemoteInputEvent.MouseButton(MouseButtonType.Left, pressed = false))
            executor.execute(RemoteInputEvent.Scroll(deltaX = 0f, deltaY = 3.4f))

            assertEquals(
                listOf(
                    "mouseMove:10:20",
                    "mousePress:${InputEvent.BUTTON1_DOWN_MASK}",
                    "mouseRelease:${InputEvent.BUTTON1_DOWN_MASK}",
                    "mouseWheel:3",
                ),
                robot.calls,
            )
        }

    @Test
    fun executesCommonKeyPressAndRelease() =
        runTest {
            val robot = RecordingDesktopRobotInput()
            val executor = AwtRemoteInputExecutor(robot, headless = { false })

            executor.execute(RemoteInputEvent.Key(KeyCode.Enter, pressed = true))
            executor.execute(RemoteInputEvent.Key(KeyCode.Enter, pressed = false))

            assertEquals(
                listOf("keyPress:${KeyEvent.VK_ENTER}", "keyRelease:${KeyEvent.VK_ENTER}"),
                robot.calls,
            )
        }

    @Test
    fun releasesTrackedAwtKeysAndButtonsIdempotently() =
        runTest {
            val robot = RecordingDesktopRobotInput()
            val executor = AwtRemoteInputExecutor(robot, headless = { false })
            executor.execute(RemoteInputEvent.Key(KeyCode.Control, pressed = true))
            executor.execute(RemoteInputEvent.MouseButton(MouseButtonType.Left, pressed = true))

            executor.releaseAll()
            executor.releaseAll()

            assertEquals(
                listOf(
                    "keyPress:${KeyEvent.VK_CONTROL}",
                    "mousePress:${InputEvent.BUTTON1_DOWN_MASK}",
                    "keyRelease:${KeyEvent.VK_CONTROL}",
                    "mouseRelease:${InputEvent.BUTTON1_DOWN_MASK}",
                ),
                robot.calls,
            )
        }

    @Test
    fun failedAwtShortcutReleasesPossiblePartialAndTrackedInput() =
        runTest {
            val robot = RecordingDesktopRobotInput(failAtCall = 3)
            val executor = AwtRemoteInputExecutor(robot, headless = { false })
            executor.execute(RemoteInputEvent.Key(KeyCode.Control, pressed = true))

            assertFailsWith<RuntimeException> {
                executor.execute(RemoteInputEvent.Shortcut(listOf(KeyCode.Alt, KeyCode.Enter)))
            }
            executor.releaseAll()

            assertEquals(
                listOf(
                    "keyPress:${KeyEvent.VK_CONTROL}",
                    "keyPress:${KeyEvent.VK_ALT}",
                    "keyPress:${KeyEvent.VK_ENTER}",
                    "keyRelease:${KeyEvent.VK_ENTER}",
                    "keyRelease:${KeyEvent.VK_ALT}",
                    "keyRelease:${KeyEvent.VK_CONTROL}",
                ),
                robot.calls,
            )
        }

    @Test
    fun executesShortcutByPressingForwardAndReleasingReverse() =
        runTest {
            val robot = RecordingDesktopRobotInput()
            val executor = AwtRemoteInputExecutor(robot, headless = { false })

            executor.execute(RemoteInputEvent.Shortcut(listOf(KeyCode.Control, KeyCode.Alt, KeyCode.Backspace)))

            assertEquals(
                listOf(
                    "keyPress:${KeyEvent.VK_CONTROL}",
                    "keyPress:${KeyEvent.VK_ALT}",
                    "keyPress:${KeyEvent.VK_BACK_SPACE}",
                    "keyRelease:${KeyEvent.VK_BACK_SPACE}",
                    "keyRelease:${KeyEvent.VK_ALT}",
                    "keyRelease:${KeyEvent.VK_CONTROL}",
                ),
                robot.calls,
            )
        }

    @Test
    fun executesTextInputWithShiftForUppercase() =
        runTest {
            val robot = RecordingDesktopRobotInput()
            val executor = AwtRemoteInputExecutor(robot, headless = { false })

            executor.execute(RemoteInputEvent.TextInput("Ab"))

            assertEquals(
                listOf(
                    "keyPress:${KeyEvent.VK_SHIFT}",
                    "keyPress:${KeyEvent.VK_A}",
                    "keyRelease:${KeyEvent.VK_A}",
                    "keyRelease:${KeyEvent.VK_SHIFT}",
                    "keyPress:${KeyEvent.VK_B}",
                    "keyRelease:${KeyEvent.VK_B}",
                ),
                robot.calls,
            )
        }

    @Test
    fun headlessInputFailsWithCapabilityError() =
        runTest {
            val executor = AwtRemoteInputExecutor(RecordingDesktopRobotInput(), headless = { true })

            val error =
                assertFailsWith<DesktopInputException> {
                    executor.execute(RemoteInputEvent.MouseMove(1, 2, MonitorId("primary")))
                }

            assertIs<AppError.CapabilityUnavailable>(error.appError)
        }

    @Test
    fun characterKeyEventFailsWithValidationError() =
        runTest {
            val executor = AwtRemoteInputExecutor(RecordingDesktopRobotInput(), headless = { false })

            val error =
                assertFailsWith<DesktopInputException> {
                    executor.execute(RemoteInputEvent.Key(KeyCode.Character, pressed = true))
                }

            assertIs<AppError.Validation>(error.appError)
        }

    @Test
    fun factorySelectsNativeSendInputForWindows() {
        val selection =
            DesktopRemoteInputExecutorFactory(
                osName = "Windows 11",
                headless = { false },
            ).create()

        assertEquals(DesktopInputBackend.WindowsSendInput, selection.backend)
        assertIs<WindowsSendInputExecutor>(selection.executor)
    }

    @Test
    fun factoryRequiresNativeBackendForWayland() =
        runTest {
            val selection =
                DesktopRemoteInputExecutorFactory(
                    osName = "Linux",
                    sessionType = "wayland",
                    waylandDisplay = "wayland-0",
                    ydotoolAvailability = {
                        YdotoolAvailability(false, detail = "ydotool is not installed")
                    },
                    headless = { false },
                ).create()

            assertEquals(DesktopInputBackend.LinuxWaylandPortalRequired, selection.backend)
            val error =
                assertFailsWith<DesktopInputException> {
                    selection.executor.execute(RemoteInputEvent.MouseMove(1, 2, MonitorId("primary")))
                }
            assertTrue(error.appError.message.contains("Wayland"))
        }

    @Test
    fun factorySelectsXtestBackendForLinuxX11WhenXdotoolIsAvailable() {
        val selection =
            DesktopRemoteInputExecutorFactory(
                osName = "Linux",
                sessionType = "x11",
                display = ":0",
                waylandDisplay = "wayland-stale",
                xdotoolAvailable = { true },
                ydotoolAvailability = { error("Explicit X11 must not probe the Wayland backend") },
                headless = { false },
            ).create()

        assertEquals(DesktopInputBackend.LinuxX11Xtest, selection.backend)
        assertIs<LinuxX11InputExecutor>(selection.executor)
    }

    @Test
    fun factoryFallsBackToAwtRobotForX11WithoutXdotool() {
        val selection =
            DesktopRemoteInputExecutorFactory(
                osName = "Linux",
                sessionType = "x11",
                display = ":0",
                waylandDisplay = "wayland-stale",
                xdotoolAvailable = { false },
                ydotoolAvailability = { error("Explicit X11 must not probe the Wayland backend") },
                awtRobotAvailable = { true },
                headless = { false },
            ).create()

        assertEquals(DesktopInputBackend.AwtRobot, selection.backend)
        assertIs<AwtRemoteInputExecutor>(selection.executor)
    }
}

private class RecordingWindowsSendInputApi : WindowsSendInputApi {
    val sent = mutableListOf<WindowsInputPacket>()

    override fun absoluteMove(
        x: Int,
        y: Int,
    ) = WindowsInputPacket.Mouse(flags = 0xC001, x = x, y = y)

    override fun send(packets: List<WindowsInputPacket>) {
        sent += packets
    }
}

private class RecordingDesktopRobotInput(
    private val failAtCall: Int? = null,
) : DesktopRobotInput {
    val calls = mutableListOf<String>()
    private var callCount = 0

    override fun mouseMove(
        x: Int,
        y: Int,
    ) {
        record("mouseMove:$x:$y")
    }

    override fun mousePress(buttonMask: Int) {
        record("mousePress:$buttonMask")
    }

    override fun mouseRelease(buttonMask: Int) {
        record("mouseRelease:$buttonMask")
    }

    override fun mouseWheel(amount: Int) {
        record("mouseWheel:$amount")
    }

    override fun keyPress(keyCode: Int) {
        record("keyPress:$keyCode")
    }

    override fun keyRelease(keyCode: Int) {
        record("keyRelease:$keyCode")
    }

    private fun record(call: String) {
        calls += call
        callCount += 1
        check(callCount != failAtCall) { "simulated AWT input failure" }
    }
}
