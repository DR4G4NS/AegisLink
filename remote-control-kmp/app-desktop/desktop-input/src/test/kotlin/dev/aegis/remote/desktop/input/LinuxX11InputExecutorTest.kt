package dev.aegis.remote.desktop.input

import dev.aegis.remote.core.input.KeyCode
import dev.aegis.remote.core.input.MouseButtonType
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.MonitorId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LinuxX11InputExecutorTest {
    @Test
    fun mapsInputToXdotoolXtestCommands() =
        runTest {
            val runner = RecordingX11Runner()
            val executor = LinuxX11InputExecutor(runner, headless = { false })

            executor.execute(RemoteInputEvent.MouseMove(10, 20, MonitorId("primary")))
            executor.execute(RemoteInputEvent.MouseButton(MouseButtonType.Forward, true))
            executor.execute(RemoteInputEvent.Scroll(0f, -2f))
            executor.execute(RemoteInputEvent.Key(KeyCode.Enter, false))
            executor.execute(RemoteInputEvent.TextInput("--safe text"))
            executor.execute(RemoteInputEvent.Shortcut(listOf(KeyCode.Control, KeyCode.Alt)))

            assertEquals(
                listOf(
                    listOf("xdotool", "mousemove", "--sync", "10", "20"),
                    listOf("xdotool", "mousedown", "9"),
                    listOf("xdotool", "click", "--repeat", "2", "4"),
                    listOf("xdotool", "keyup", "Return"),
                    listOf("xdotool", "type", "--clearmodifiers", "--delay", "0", "--", "--safe text"),
                    listOf("xdotool", "keydown", "ctrl"),
                    listOf("xdotool", "keydown", "alt"),
                    listOf("xdotool", "keyup", "alt"),
                    listOf("xdotool", "keyup", "ctrl"),
                ),
                runner.commands,
            )
        }

    @Test
    fun reportsTimedOutXdotoolCommandAsCapabilityFailure() =
        runTest {
            val executor =
                LinuxX11InputExecutor(
                    runner = TimeoutX11Runner,
                    headless = { false },
                )

            val error =
                assertFailsWith<DesktopInputException> {
                    executor.execute(RemoteInputEvent.MouseMove(10, 20, MonitorId("primary")))
                }

            assertEquals(
                "X11 input injection failed: X11 input command timed out after 5000ms",
                error.appError.message,
            )
        }

    @Test
    fun releaseAllTracksHeldX11KeysAndButtonsAndIsIdempotent() =
        runTest {
            val runner = RecordingX11Runner()
            val executor = LinuxX11InputExecutor(runner, headless = { false })

            executor.execute(RemoteInputEvent.Key(KeyCode.Control, pressed = true))
            executor.execute(RemoteInputEvent.Key(KeyCode.Alt, pressed = true))
            executor.execute(RemoteInputEvent.MouseButton(MouseButtonType.Left, pressed = true))
            executor.releaseAll()
            executor.releaseAll()

            assertEquals(
                listOf(
                    listOf("xdotool", "keydown", "ctrl"),
                    listOf("xdotool", "keydown", "alt"),
                    listOf("xdotool", "mousedown", "1"),
                    listOf("xdotool", "keyup", "alt"),
                    listOf("xdotool", "keyup", "ctrl"),
                    listOf("xdotool", "mouseup", "1"),
                ),
                runner.commands,
            )
        }

    @Test
    fun positiveWheelDeltaUsesX11ScrollDownAndRightButtons() =
        runTest {
            val runner = RecordingX11Runner()
            val executor = LinuxX11InputExecutor(runner, headless = { false })

            executor.execute(RemoteInputEvent.Scroll(2f, 3f))

            assertEquals(
                listOf(
                    listOf("xdotool", "click", "--repeat", "3", "5"),
                    listOf("xdotool", "click", "--repeat", "2", "7"),
                ),
                runner.commands,
            )
        }

    @Test
    fun failedShortcutReleasesPossiblePartialAndPreviouslyTrackedInput() =
        runTest {
            val runner = RecordingX11Runner(failAtCall = 3)
            val executor = LinuxX11InputExecutor(runner, headless = { false })
            executor.execute(RemoteInputEvent.Key(KeyCode.Control, pressed = true))

            assertFailsWith<DesktopInputException> {
                executor.execute(RemoteInputEvent.Shortcut(listOf(KeyCode.Alt, KeyCode.Enter)))
            }
            executor.releaseAll()

            assertEquals(
                listOf(
                    listOf("xdotool", "keydown", "ctrl"),
                    listOf("xdotool", "keydown", "alt"),
                    listOf("xdotool", "keydown", "Return"),
                    listOf("xdotool", "keyup", "Return"),
                    listOf("xdotool", "keyup", "alt"),
                    listOf("xdotool", "keyup", "ctrl"),
                ),
                runner.commands,
            )
        }
}

private class RecordingX11Runner(
    private val failAtCall: Int? = null,
) : X11CommandRunner {
    val commands = mutableListOf<List<String>>()

    override fun run(command: List<String>): X11CommandResult {
        commands += command
        return if (commands.size == failAtCall) {
            X11CommandResult(124, "X11 input command timed out after 5000ms")
        } else {
            X11CommandResult(0)
        }
    }
}

private object TimeoutX11Runner : X11CommandRunner {
    override fun run(command: List<String>): X11CommandResult = X11CommandResult(124, "X11 input command timed out after 5000ms")
}
