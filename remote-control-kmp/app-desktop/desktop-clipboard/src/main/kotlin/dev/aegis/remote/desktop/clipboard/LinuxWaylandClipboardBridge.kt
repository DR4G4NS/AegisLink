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

class LinuxWaylandClipboardBridge(
    private val runner: WlClipboardCommandRunner = ProcessWlClipboardCommandRunner(),
    private val copyTool: String = "wl-copy",
    private val pasteTool: String = "wl-paste",
    clock: () -> Long = { System.currentTimeMillis() },
    private val loopGuard: DesktopClipboardLoopGuard = DesktopClipboardLoopGuard(clock),
) : ClipboardBridge {
    private val events = MutableSharedFlow<ClipboardPayload>(extraBufferCapacity = 1)
    private val localDuplicateGuard = ClipboardLoopGuard(clock)
    override val changes: Flow<ClipboardPayload> = events.asSharedFlow()

    override suspend fun read(): ClipboardPayload? {
        val result = runWlClipboard(listOf(pasteTool, "--no-newline"))
        if (result.exitCode != 0) throw failure(result.output)
        return result.output.takeIf { it.isNotEmpty() }?.let(ClipboardPayload::Text)
    }

    override suspend fun write(payload: ClipboardPayload) {
        when (payload) {
            is ClipboardPayload.Text -> {
                val result = runWlClipboard(listOf(copyTool), payload.value)
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

    private fun runWlClipboard(
        command: List<String>,
        standardInput: String? = null,
    ): WlClipboardCommandResult =
        runCatching { runner.run(command, standardInput) }
            .getOrElse { error ->
                throw failure(error.message ?: error::class.simpleName.orEmpty())
            }

    private fun failure(output: String): DesktopClipboardException =
        DesktopClipboardException(
            AppError.CapabilityUnavailable("Wayland clipboard via wl-clipboard failed: ${output.take(300)}"),
        )
}

interface WlClipboardCommandRunner {
    fun run(
        command: List<String>,
        standardInput: String? = null,
    ): WlClipboardCommandResult
}

data class WlClipboardCommandResult(
    val exitCode: Int,
    val output: String = "",
)

class ProcessWlClipboardCommandRunner(
    private val timeoutMillis: Long = 5_000L,
) : WlClipboardCommandRunner {
    override fun run(
        command: List<String>,
        standardInput: String?,
    ): WlClipboardCommandResult {
        val process =
            try {
                ProcessBuilder(command).redirectErrorStream(true).start()
            } catch (error: Exception) {
                return WlClipboardCommandResult(127, error.message.orEmpty())
            }
        return runCatching {
            standardInput?.let { text -> process.outputStream.bufferedWriter().use { it.write(text) } }
                ?: process.outputStream.close()
            val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                WlClipboardCommandResult(124, "wl-clipboard command timed out")
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                WlClipboardCommandResult(process.exitValue(), output)
            }
        }.getOrElse { error ->
            process.destroyForcibly()
            WlClipboardCommandResult(1, error.message.orEmpty())
        }
    }
}

object WlClipboardLocator {
    fun isAvailable(pathValue: String? = System.getenv("PATH")): Boolean {
        val paths =
            pathValue
                .orEmpty()
                .split(File.pathSeparatorChar)
                .asSequence()
                .filter { it.isNotBlank() }
                .map { Path.of(it) }
                .toList()

        fun exists(tool: String): Boolean =
            paths
                .map { it.resolve(tool) }
                .any { Files.isRegularFile(it) && Files.isExecutable(it) }

        return exists("wl-copy") && exists("wl-paste")
    }
}
