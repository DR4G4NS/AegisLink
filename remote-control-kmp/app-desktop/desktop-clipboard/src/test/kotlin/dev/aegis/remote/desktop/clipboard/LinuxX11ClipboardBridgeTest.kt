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

class LinuxX11ClipboardBridgeTest {
    @Test
    fun readsWritesAndEmitsClipboardTextThroughXclip() =
        runTest {
            val runner = RecordingXclipRunner(readOutput = "from-x11")
            val bridge = LinuxX11ClipboardBridge(runner)
            val change = async(start = CoroutineStart.UNDISPATCHED) { bridge.changes.first() }

            val read = bridge.read()
            bridge.write(ClipboardPayload.Text("to-x11"))

            assertEquals("from-x11", assertIs<ClipboardPayload.Text>(read).value)
            assertEquals("to-x11", assertIs<ClipboardPayload.Text>(change.await()).value)
            assertEquals(
                listOf(
                    listOf("xclip", "-selection", "clipboard", "-o"),
                    listOf("xclip", "-selection", "clipboard", "-in"),
                ),
                runner.commands,
            )
            assertEquals(listOf(null, "to-x11"), runner.inputs)
        }

    @Test
    fun commandFailureIsReportedAsCapabilityError() =
        runTest {
            val runner = RecordingXclipRunner(readOutput = "", exitCode = 124, failureOutput = "xclip command timed out")
            val bridge = LinuxX11ClipboardBridge(runner)

            val error =
                assertFailsWith<DesktopClipboardException> {
                    bridge.read()
                }

            assertTrue(error.appError.message.contains("timed out"))
        }

    @Test
    fun unexpectedRunnerFailureIsReportedAsCapabilityError() =
        runTest {
            val bridge = LinuxX11ClipboardBridge(ThrowingXclipRunner(IllegalStateException("xclip pipe closed")))

            val error =
                assertFailsWith<DesktopClipboardException> {
                    bridge.write(ClipboardPayload.Text("to-x11"))
                }

            assertEquals("X11 clipboard via xclip failed: xclip pipe closed", error.appError.message)
        }
}

private class RecordingXclipRunner(
    private val readOutput: String,
    private val exitCode: Int = 0,
    private val failureOutput: String = "",
) : XclipCommandRunner {
    val commands = mutableListOf<List<String>>()
    val inputs = mutableListOf<String?>()

    override fun run(
        command: List<String>,
        standardInput: String?,
    ): XclipCommandResult {
        commands += command
        inputs += standardInput
        if (exitCode != 0) return XclipCommandResult(exitCode, failureOutput)
        return XclipCommandResult(0, if (command.last() == "-o") readOutput else "")
    }
}

private class ThrowingXclipRunner(
    private val error: RuntimeException,
) : XclipCommandRunner {
    override fun run(
        command: List<String>,
        standardInput: String?,
    ): XclipCommandResult = throw error
}
