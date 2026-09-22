package dev.aegis.remote.desktop.clipboard

import dev.aegis.remote.core.clipboard.ClipboardBridge
import dev.aegis.remote.core.clipboard.ClipboardLoopGuard
import dev.aegis.remote.core.model.AppError
import dev.aegis.remote.core.model.ClipboardPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** X11 clipboard adapter backed by xclip. It is intentionally not selected on
 * Wayland, where the compositor/portal owns clipboard permissions. */
class LinuxX11ClipboardBridge(
    private val runner: XclipCommandRunner = ProcessXclipCommandRunner(),
    private val tool: String = "xclip",
    clock: () -> Long = { System.currentTimeMillis() },
    private val loopGuard: DesktopClipboardLoopGuard = DesktopClipboardLoopGuard(clock),
) : ClipboardBridge {
    private val events = MutableSharedFlow<ClipboardPayload>(extraBufferCapacity = 1)
    private val localDuplicateGuard = ClipboardLoopGuard(clock)
    override val changes: Flow<ClipboardPayload> = events.asSharedFlow()

    override suspend fun read(): ClipboardPayload? {
        val result = runXclip(listOf(tool, "-selection", "clipboard", "-o"))
        if (result.exitCode != 0) throw failure(result.output)
        return result.output.takeIf { it.isNotEmpty() }?.let(ClipboardPayload::Text)
    }

    override suspend fun write(payload: ClipboardPayload) {
        when (payload) {
            is ClipboardPayload.Text -> {
                val result = runXclip(listOf(tool, "-selection", "clipboard", "-in"), payload.value)
                if (result.exitCode != 0) throw failure(result.output)
                events.emit(payload)
            }
        }
    }

    override suspend fun writeFromRemote(payload: ClipboardPayload) {
        loopGuard.markRemoteWrite(payload)
        runCatching { write(payload) }
            .onFailure { loopGuard.cancelRemoteWrite(payload) }
            .getOrThrow()
    }

    override fun shouldForwardChange(payload: ClipboardPayload): Boolean {
        val remoteEcho = loopGuard.isRemoteEcho(payload)
        return !remoteEcho && localDuplicateGuard.shouldForwardLocalChange(payload)
    }

    private fun runXclip(
        command: List<String>,
        standardInput: String? = null,
    ): XclipCommandResult =
        runCatching { runner.run(command, standardInput) }
            .getOrElse { error ->
                throw failure(error.message ?: error::class.simpleName.orEmpty())
            }

    private fun failure(output: String): DesktopClipboardException =
        DesktopClipboardException(
            AppError.CapabilityUnavailable("X11 clipboard via xclip failed: ${output.take(300)}"),
        )
}

interface XclipCommandRunner {
    fun run(
        command: List<String>,
        standardInput: String? = null,
    ): XclipCommandResult
}

data class XclipCommandResult(
    val exitCode: Int,
    val output: String = "",
)

class ProcessXclipCommandRunner(
    private val timeoutMillis: Long = 5_000L,
) : XclipCommandRunner {
    override fun run(
        command: List<String>,
        standardInput: String?,
    ): XclipCommandResult {
        val process =
            try {
                ProcessBuilder(command).redirectErrorStream(true).start()
            } catch (error: Exception) {
                return XclipCommandResult(127, error.message.orEmpty())
            }
        return runCatching {
            standardInput?.let { text -> process.outputStream.bufferedWriter().use { it.write(text) } }
                ?: process.outputStream.close()
            val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                XclipCommandResult(124, "xclip command timed out")
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                XclipCommandResult(process.exitValue(), output)
            }
        }.getOrElse { error ->
            process.destroyForcibly()
            XclipCommandResult(1, error.message.orEmpty())
        }
    }
}

object XclipLocator {
    fun isAvailable(pathValue: String? = System.getenv("PATH")): Boolean =
        pathValue
            .orEmpty()
            .split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { Path.of(it).resolve("xclip") }
            .any { Files.isRegularFile(it) && Files.isExecutable(it) }
}
