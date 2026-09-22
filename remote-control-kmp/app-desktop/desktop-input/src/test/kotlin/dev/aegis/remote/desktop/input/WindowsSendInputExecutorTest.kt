package dev.aegis.remote.desktop.input

import dev.aegis.remote.core.input.KeyCode
import dev.aegis.remote.core.input.KeyLocation
import dev.aegis.remote.core.input.MouseButtonType
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.AppError
import dev.aegis.remote.core.model.MonitorId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowsSendInputExecutorTest {
    @Test
    fun mapsMouseKeyboardTextAndShortcutToNativePackets() =
        runTest {
            val api = RecordingWindowsApi()
            val executor = WindowsSendInputExecutor(api, headless = { false })

            executor.execute(RemoteInputEvent.MouseMove(100, 200, MonitorId("primary")))
            executor.execute(RemoteInputEvent.MouseButton(MouseButtonType.Left, true))
            executor.execute(RemoteInputEvent.Scroll(0f, -2f))
            executor.execute(RemoteInputEvent.Key(KeyCode.Enter, true))
            executor.execute(RemoteInputEvent.TextInput("A"))
            executor.execute(RemoteInputEvent.TextInput("hi\nthere"))
            executor.execute(RemoteInputEvent.Shortcut(listOf(KeyCode.Control, KeyCode.Alt)))

            assertEquals(
                listOf(
                    WindowsInputPacket.Mouse(0xC001, x = 100, y = 200),
                    WindowsInputPacket.Mouse(0x0002),
                    WindowsInputPacket.Mouse(0x0800, data = 240),
                    WindowsInputPacket.Key(0x0D, false),
                    WindowsInputPacket.Unicode('A', false),
                    WindowsInputPacket.Unicode('A', true),
                    WindowsInputPacket.Unicode('h', false),
                    WindowsInputPacket.Unicode('h', true),
                    WindowsInputPacket.Unicode('i', false),
                    WindowsInputPacket.Unicode('i', true),
                    WindowsInputPacket.Key(0x0D, false),
                    WindowsInputPacket.Key(0x0D, true),
                    WindowsInputPacket.Unicode('t', false),
                    WindowsInputPacket.Unicode('t', true),
                    WindowsInputPacket.Unicode('h', false),
                    WindowsInputPacket.Unicode('h', true),
                    WindowsInputPacket.Unicode('e', false),
                    WindowsInputPacket.Unicode('e', true),
                    WindowsInputPacket.Unicode('r', false),
                    WindowsInputPacket.Unicode('r', true),
                    WindowsInputPacket.Unicode('e', false),
                    WindowsInputPacket.Unicode('e', true),
                    WindowsInputPacket.Key(0x11, false),
                    WindowsInputPacket.Key(0x12, false),
                    WindowsInputPacket.Key(0x12, true),
                    WindowsInputPacket.Key(0x11, true),
                ),
                api.sent,
            )
        }

    @Test
    fun wrapsUnexpectedNativeSendInputFailureAsCapabilityError() =
        runTest {
            val executor = WindowsSendInputExecutor(FailingWindowsApi(RuntimeException("native access denied")), headless = { false })

            val error =
                assertFailsWith<DesktopInputException> {
                    executor.execute(RemoteInputEvent.MouseButton(MouseButtonType.Left, true))
                }

            assertEquals("Windows SendInput could not inject input: native access denied", error.appError.message)
        }

    @Test
    fun supportsRelativeMovementHorizontalWheelAndPhysicalScanCodes() =
        runTest {
            val api = RecordingWindowsApi()
            val executor = WindowsSendInputExecutor(api, headless = { false })

            executor.execute(RemoteInputEvent.MouseMoveRelative(-4, 7))
            executor.execute(RemoteInputEvent.Scroll(2f, 0f))
            executor.execute(
                RemoteInputEvent.Key(
                    code = KeyCode.Control,
                    pressed = true,
                    scanCode = 0x1d,
                    extended = true,
                    location = KeyLocation.Right,
                    sourceLayoutId = "es-MX",
                ),
            )

            assertEquals(
                listOf(
                    WindowsInputPacket.Mouse(0x0001, x = -4, y = 7),
                    WindowsInputPacket.Mouse(0x1000, data = 240),
                    WindowsInputPacket.Key(virtualKey = 0, keyUp = false, scanCode = 0x1d, extended = true),
                ),
                api.sent,
            )
        }

    @Test
    fun releasesEveryTrackedKeyAndButtonIdempotently() =
        runTest {
            val api = RecordingWindowsApi()
            val executor = WindowsSendInputExecutor(api, headless = { false })

            executor.execute(RemoteInputEvent.Key(KeyCode.Control, pressed = true, scanCode = 0x1d))
            executor.execute(RemoteInputEvent.MouseButton(MouseButtonType.Left, pressed = true))
            executor.releaseAll()
            executor.releaseAll()

            assertEquals(
                listOf(
                    WindowsInputPacket.Key(virtualKey = 0, keyUp = false, scanCode = 0x1d),
                    WindowsInputPacket.Mouse(0x0002),
                    WindowsInputPacket.Mouse(0x0004),
                    WindowsInputPacket.Key(virtualKey = 0, keyUp = true, scanCode = 0x1d),
                ),
                api.sent,
            )
        }

    @Test
    fun preservesTypedSendInputFailure() =
        runTest {
            val expected = DesktopInputException(AppError.Network("Windows SendInput accepted 0 of 1 events"))
            val executor = WindowsSendInputExecutor(FailingWindowsApi(expected), headless = { false })

            val error =
                assertFailsWith<DesktopInputException> {
                    executor.execute(RemoteInputEvent.MouseButton(MouseButtonType.Left, true))
                }

            assertEquals(expected.appError, error.appError)
        }
}

private class RecordingWindowsApi : WindowsSendInputApi {
    val sent = mutableListOf<WindowsInputPacket>()

    override fun absoluteMove(
        x: Int,
        y: Int,
    ) = WindowsInputPacket.Mouse(0xC001, x = x, y = y)

    override fun send(packets: List<WindowsInputPacket>) {
        sent += packets
    }
}

private class FailingWindowsApi(
    private val error: RuntimeException,
) : WindowsSendInputApi {
    override fun absoluteMove(
        x: Int,
        y: Int,
    ): WindowsInputPacket.Mouse = WindowsInputPacket.Mouse(0xC001, x = x, y = y)

    override fun send(packets: List<WindowsInputPacket>): Unit = throw error
}
