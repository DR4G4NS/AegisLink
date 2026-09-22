package dev.aegis.remote.desktop.input

import dev.aegis.remote.core.input.KeyCode
import dev.aegis.remote.core.input.KeyLocation
import dev.aegis.remote.core.input.MouseButtonType
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.MonitorId
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinuxWaylandYdotoolInputExecutorTest {
    @Test
    fun `maps pointer buttons wheel keys text and shortcuts to bounded ydotool argv`() =
        runTest {
            val runner = RecordingYdotoolRunner()
            val executor =
                LinuxWaylandYdotoolInputExecutor(
                    runner = runner,
                    tool = "/usr/bin/ydotool",
                )

            executor.execute(RemoteInputEvent.MouseMove(10, 20, MonitorId("primary")))
            executor.execute(RemoteInputEvent.MouseMoveRelative(-3, 4))
            executor.execute(RemoteInputEvent.MouseButton(MouseButtonType.Back, pressed = true))
            executor.execute(RemoteInputEvent.MouseButton(MouseButtonType.Back, pressed = false))
            executor.execute(RemoteInputEvent.Scroll(deltaX = 2f, deltaY = 3f))
            executor.execute(RemoteInputEvent.Key(KeyCode.Control, pressed = true, location = KeyLocation.Right))
            executor.execute(RemoteInputEvent.Key(KeyCode.Control, pressed = false, location = KeyLocation.Right))
            executor.execute(RemoteInputEvent.TextInput("""--safe\n\x41\"""))
            executor.execute(RemoteInputEvent.Shortcut(listOf(KeyCode.Control, KeyCode.Alt, KeyCode.Enter)))

            assertEquals(
                listOf(
                    listOf("/usr/bin/ydotool", "mousemove", "--absolute", "-x", "10", "-y", "20"),
                    listOf("/usr/bin/ydotool", "mousemove", "-x", "-3", "-y", "4"),
                    listOf("/usr/bin/ydotool", "click", "0x46"),
                    listOf("/usr/bin/ydotool", "click", "0x86"),
                    listOf("/usr/bin/ydotool", "mousemove", "--wheel", "-x", "2", "-y", "-3"),
                    listOf("/usr/bin/ydotool", "key", "97:1"),
                    listOf("/usr/bin/ydotool", "key", "97:0"),
                    listOf(
                        "/usr/bin/ydotool",
                        "type",
                        "--key-delay=0",
                        "--key-hold=0",
                        "--escape=0",
                        "--",
                        """--safe\n\x41\""",
                    ),
                    listOf("/usr/bin/ydotool", "key", "29:1", "56:1", "28:1", "28:0", "56:0", "29:0"),
                ),
                runner.commands,
            )
        }

    @Test
    fun `releaseAll releases tracked keys and buttons once in reverse order`() =
        runTest {
            val runner = RecordingYdotoolRunner()
            val executor = LinuxWaylandYdotoolInputExecutor(runner)

            executor.execute(RemoteInputEvent.Key(KeyCode.Control, pressed = true))
            executor.execute(RemoteInputEvent.Key(KeyCode.Alt, pressed = true))
            executor.execute(RemoteInputEvent.MouseButton(MouseButtonType.Left, pressed = true))
            executor.execute(RemoteInputEvent.MouseButton(MouseButtonType.Forward, pressed = true))
            executor.releaseAll()
            executor.releaseAll()

            assertEquals(
                listOf(
                    listOf("ydotool", "key", "29:1"),
                    listOf("ydotool", "key", "56:1"),
                    listOf("ydotool", "click", "0x40"),
                    listOf("ydotool", "click", "0x45"),
                    listOf("ydotool", "key", "56:0", "29:0"),
                    listOf("ydotool", "click", "0x85", "0x80"),
                ),
                runner.commands,
            )
        }

    @Test
    fun `timed out command is reported and attempts release of a possible partial press`() =
        runTest {
            val runner = RecordingYdotoolRunner(failAtCall = 1)
            val executor = LinuxWaylandYdotoolInputExecutor(runner)

            val error =
                assertFailsWith<DesktopInputException> {
                    executor.execute(RemoteInputEvent.Key(KeyCode.Enter, pressed = true))
                }

            assertTrue(error.appError.message.contains("timed out"))
            assertEquals(
                listOf(
                    listOf("ydotool", "key", "28:1"),
                    listOf("ydotool", "key", "28:0"),
                ),
                runner.commands,
            )
        }

    @Test
    fun `unsupported text fails explicitly instead of being silently dropped`() =
        runTest {
            val executor = LinuxWaylandYdotoolInputExecutor(RecordingYdotoolRunner(), unicodeTextTool = null)

            val missingTool =
                assertFailsWith<DesktopInputException> {
                    executor.execute(RemoteInputEvent.TextInput("México"))
                }
            assertTrue(missingTool.appError.message.contains("wtype"))

            listOf("\u0000", "\r", "\u007f").forEach { text ->
                val error =
                    assertFailsWith<DesktopInputException> {
                        executor.execute(RemoteInputEvent.TextInput(text))
                    }
                assertTrue(error.appError.message.contains("control characters"))
            }
        }

    @Test
    fun `non ascii text goes through wtype with newlines and tabs as named keys`() =
        runTest {
            val runner = RecordingYdotoolRunner()
            val executor =
                LinuxWaylandYdotoolInputExecutor(
                    runner = runner,
                    tool = "ydotool",
                    unicodeTextTool = "/usr/bin/wtype",
                )

            executor.execute(RemoteInputEvent.TextInput("¿Qué tal?\n\t-ñ"))
            executor.execute(RemoteInputEvent.TextInput("plain ascii"))

            assertEquals(
                listOf(
                    listOf("/usr/bin/wtype", "--", "¿Qué tal?"),
                    listOf("/usr/bin/wtype", "-k", "Return"),
                    listOf("/usr/bin/wtype", "-k", "Tab"),
                    listOf("/usr/bin/wtype", "--", "-ñ"),
                    listOf("ydotool", "type", "--key-delay=0", "--key-hold=0", "--escape=0", "--", "plain ascii"),
                ),
                runner.commands,
            )
        }

    @Test
    fun `wtype failure surfaces as capability error`() =
        runTest {
            val runner = RecordingYdotoolRunner(failAtCall = 1)
            val executor = LinuxWaylandYdotoolInputExecutor(runner, unicodeTextTool = "wtype")

            val error =
                assertFailsWith<DesktopInputException> {
                    executor.execute(RemoteInputEvent.TextInput("ñ"))
                }
            assertTrue(error.appError.message.contains("wtype"))
        }

    @Test
    fun `availability probe verifies both executable and existing daemon socket`() {
        val directory = Files.createTempDirectory("aegis-ydotool-path")
        val executable = directory.resolve("ydotool")
        Files.writeString(executable, "")
        executable.toFile().setExecutable(true, true)
        val runner = RecordingYdotoolRunner()

        val available =
            YdotoolAvailabilityProbe(
                runner = runner,
                pathValue = directory.toString(),
            ).inspect()

        assertTrue(available.available)
        assertEquals(executable.toString(), available.executable)
        assertEquals(listOf(executable.toString(), "debug"), runner.commands.single())

        val unavailable =
            YdotoolAvailabilityProbe(
                runner = RecordingYdotoolRunner(failAtCall = 1),
                pathValue = directory.toString(),
            ).inspect()
        assertEquals(false, unavailable.available)
        assertTrue(unavailable.detail.contains("timed out"))
    }

    @Test
    fun `factory selects ydotool only after successful availability probe`() {
        val availableSelection =
            DesktopRemoteInputExecutorFactory(
                osName = "Linux",
                sessionType = "wayland",
                waylandDisplay = "wayland-0",
                ydotoolAvailability = {
                    YdotoolAvailability(true, "/opt/ydotool", "ready")
                },
                headless = { true },
            ).create()
        assertEquals(DesktopInputBackend.LinuxWaylandYdotool, availableSelection.backend)
        assertIs<LinuxWaylandYdotoolInputExecutor>(availableSelection.executor)

        val unavailableSelection =
            DesktopRemoteInputExecutorFactory(
                osName = "Linux",
                sessionType = "wayland",
                waylandDisplay = "wayland-0",
                ydotoolAvailability = {
                    YdotoolAvailability(false, "/opt/ydotool", "daemon socket denied")
                },
                headless = { false },
            ).create()
        assertEquals(DesktopInputBackend.LinuxWaylandPortalRequired, unavailableSelection.backend)
        val error =
            assertFailsWith<DesktopInputException> {
                runTest {
                    unavailableSelection.executor.execute(
                        RemoteInputEvent.MouseMove(1, 2, MonitorId("primary")),
                    )
                }
            }
        assertTrue(error.appError.message.contains("daemon socket denied"))
    }
}

private class RecordingYdotoolRunner(
    private val failAtCall: Int? = null,
) : YdotoolCommandRunner {
    val commands = mutableListOf<List<String>>()

    override fun run(command: List<String>): YdotoolCommandResult {
        commands += command
        return if (commands.size == failAtCall) {
            YdotoolCommandResult(124, "ydotool command timed out after 5000ms")
        } else {
            YdotoolCommandResult(0)
        }
    }
}
