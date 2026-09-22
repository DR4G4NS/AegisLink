package dev.aegis.remote.desktop.clipboard

import dev.aegis.remote.core.model.ClipboardPayload
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinuxWaylandClipboardBridgeTest {
    @Test
    fun readsWritesAndEmitsClipboardTextThroughWlClipboard() =
        runTest {
            val runner = RecordingWlClipboardRunner(readOutput = "from-wayland")
            val bridge = LinuxWaylandClipboardBridge(runner)
            val change = async(start = CoroutineStart.UNDISPATCHED) { bridge.changes.first() }

            val read = bridge.read()
            bridge.write(ClipboardPayload.Text("to-wayland"))

            assertEquals("from-wayland", assertIs<ClipboardPayload.Text>(read).value)
            assertEquals("to-wayland", assertIs<ClipboardPayload.Text>(change.await()).value)
            assertEquals(
                listOf(
                    listOf("wl-paste", "--no-newline"),
                    listOf("wl-copy"),
                ),
                runner.commands,
            )
            assertEquals(listOf(null, "to-wayland"), runner.inputs)
        }

    @Test
    fun commandFailureIsReportedAsCapabilityError() =
        runTest {
            val runner = RecordingWlClipboardRunner(readOutput = "", exitCode = 124, failureOutput = "wl-clipboard command timed out")
            val bridge = LinuxWaylandClipboardBridge(runner)

            val error =
                assertFailsWith<DesktopClipboardException> {
                    bridge.read()
                }

            assertTrue(error.appError.message.contains("timed out"))
        }

    @Test
    fun unexpectedRunnerFailureIsReportedAsCapabilityError() =
        runTest {
            val bridge = LinuxWaylandClipboardBridge(ThrowingWlClipboardRunner(IllegalStateException("wayland pipe closed")))

            val error =
                assertFailsWith<DesktopClipboardException> {
                    bridge.write(ClipboardPayload.Text("to-wayland"))
                }

            assertEquals("Wayland clipboard via wl-clipboard failed: wayland pipe closed", error.appError.message)
        }
}

private class RecordingWlClipboardRunner(
    private val readOutput: String,
    private val exitCode: Int = 0,
    private val failureOutput: String = "",
) : WlClipboardCommandRunner {
    val commands = mutableListOf<List<String>>()
    val inputs = mutableListOf<String?>()

    override fun run(
        command: List<String>,
        standardInput: String?,
    ): WlClipboardCommandResult {
        commands += command
        inputs += standardInput
        if (exitCode != 0) return WlClipboardCommandResult(exitCode, failureOutput)
        return WlClipboardCommandResult(0, if (command.first() == "wl-paste") readOutput else "")
    }
}

private class ThrowingWlClipboardRunner(
    private val error: RuntimeException,
) : WlClipboardCommandRunner {
    override fun run(
        command: List<String>,
        standardInput: String?,
    ): WlClipboardCommandResult = throw error
}
