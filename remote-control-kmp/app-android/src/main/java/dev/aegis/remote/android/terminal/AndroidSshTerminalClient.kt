package dev.aegis.remote.android.terminal

import dev.aegis.remote.android.security.authenticateProfile
import dev.aegis.remote.android.security.createPinnedSshConnection
import dev.aegis.remote.android.security.enableSshKeepAlive
import dev.aegis.remote.android.security.remoteOperationFailure
import dev.aegis.remote.android.security.sshOperationFailure
import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.FailureCategory
import dev.aegis.remote.core.security.SecureCredentialStore
import dev.aegis.remote.core.terminal.SshTerminalClient
import dev.aegis.remote.core.terminal.TerminalExit
import dev.aegis.remote.core.terminal.TerminalInput
import dev.aegis.remote.core.terminal.TerminalOutput
import dev.aegis.remote.core.terminal.TerminalOutputStream
import dev.aegis.remote.core.terminal.TerminalSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AndroidSshTerminalClient(
    private val credentialStore: SecureCredentialStore,
) : SshTerminalClient {
    override suspend fun connect(
        profile: DeviceProfile,
        routeHost: String,
    ): TerminalSession =
        withContext(Dispatchers.IO) {
            val expectedFingerprint =
                profile.hostKeyFingerprint
                    ?: throw remoteOperationFailure(
                        code = AegisFailureCodes.SSH_HOST_KEY_MISSING,
                        component = "android-sshj",
                        operation = "connect-terminal",
                        stage = "precondition",
                        category = FailureCategory.CONFIGURATION,
                        summary = "The SSH host key is not pinned for this profile",
                        retryable = false,
                        nextAction = "Pair the desktop host again; unpinned SSH connections are never allowed.",
                    )

            val pinned =
                runCatching { createPinnedSshConnection(expectedFingerprint) }
                    .getOrElse { error ->
                        throw sshOperationFailure("connect-terminal", "pin-validation", error)
                    }
            val ssh = pinned.client
            try {
                try {
                    ssh.connect(routeHost, profile.sshPort)
                } catch (error: Throwable) {
                    throw sshOperationFailure("connect-terminal", "transport-connect", error, pinned.observation)
                }
                try {
                    ssh.authenticateProfile(profile, credentialStore)
                } catch (error: Throwable) {
                    throw sshOperationFailure("connect-terminal", "authentication", error, pinned.observation)
                }
                enableSshKeepAlive(ssh)

                val session =
                    try {
                        ssh.startSession()
                    } catch (error: Throwable) {
                        throw sshOperationFailure("connect-terminal", "shell", error, pinned.observation)
                    }
                val shell =
                    try {
                        session.allocatePTY(DEFAULT_TERM, DEFAULT_COLUMNS, DEFAULT_ROWS, 0, 0, emptyMap())
                        session.startShell()
                    } catch (error: Throwable) {
                        runCatching { session.close() }
                        throw sshOperationFailure("connect-terminal", "pty", error, pinned.observation)
                    }
                AndroidSshTerminalSession(ssh, session, shell)
            } catch (error: Throwable) {
                runCatching { ssh.disconnect() }
                runCatching { ssh.close() }
                throw error
            }
        }

    private companion object {
        const val DEFAULT_TERM = "xterm-256color"
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
    }
}

private class AndroidSshTerminalSession(
    private val ssh: SSHClient,
    private val session: Session,
    private val shell: Session.Shell,
) : TerminalSession {
    private val closed = AtomicBoolean(false)
    private val clientCloseRequested = AtomicBoolean(false)
    private val remoteOutputCompleted = AtomicBoolean(false)
    private val outputSubscribed = AtomicBoolean(false)
    private val terminalFailure = AtomicReference<Throwable?>(null)
    private val exit = CompletableDeferred<TerminalExit>()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outputWriteLock = Any()
    private val terminalQueryResponder = VtTerminalQueryResponder()

    init {
        sessionScope.launch {
            try {
                shell.join()
                val closedByClient = clientCloseRequested.get()
                completeExit(
                    closedByClient = closedByClient,
                    transportFailure =
                        !closedByClient &&
                            !remoteExitReported() &&
                            (!ssh.isConnected || terminalFailure.get() != null),
                )
            } catch (error: Throwable) {
                terminalFailure.compareAndSet(null, error)
                val closedByClient = clientCloseRequested.get()
                completeExit(
                    closedByClient = closedByClient,
                    transportFailure = !closedByClient && !remoteExitReported(),
                )
            } finally {
                closeTransport()
            }
        }
    }

    override val output: Flow<TerminalOutput> =
        callbackFlow {
            if (!outputSubscribed.compareAndSet(false, true)) {
                close(IllegalStateException("SSH terminal output supports one collector per session"))
                return@callbackFlow
            }
            if (closed.get()) {
                close()
                return@callbackFlow
            }
            val stdout = shell.inputStream
            val stderr = shell.errorStream
            val stdoutJob = launchTerminalReader(stdout, TerminalOutputStream.Stdout)
            val stderrJob = launchTerminalReader(stderr, TerminalOutputStream.Stderr)
            val completionJob =
                launch(Dispatchers.IO) {
                    joinAll(stdoutJob, stderrJob)
                    val failure = terminalFailure.get()
                    remoteOutputCompleted.set(true)
                    if (failure == null || clientCloseRequested.get()) close() else close(failure)
                }
            awaitClose {
                // Cancelling a coroutine does not interrupt SSHJ's blocking channel read.
                // Closing the socket is intentional: a cancelled collector must not leave a
                // native transfer, channel or credential-bearing transport alive.
                val remoteCompleted = remoteOutputCompleted.get()
                if (!exit.isCompleted && !remoteCompleted) clientCloseRequested.set(true)
                if (!remoteCompleted) {
                    closeTransport()
                    runCatching { stdout.close() }
                    runCatching { stderr.close() }
                }
                stdoutJob.cancel()
                stderrJob.cancel()
                completionJob.cancel()
            }
        }

    private fun kotlinx.coroutines.channels.ProducerScope<TerminalOutput>.launchTerminalReader(
        input: InputStream,
        stream: TerminalOutputStream,
    ) = launch(Dispatchers.IO) {
        try {
            val buffer = ByteArray(OUTPUT_CHUNK_BYTES)
            val framer = Utf8ChunkFramer()
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) {
                    framer.finish()?.let { trailingBytes ->
                        send(TerminalOutput(trailingBytes, stream))
                    }
                    return@launch
                }
                if (read > 0) {
                    terminalQueryResponder.responsesFor(buffer, read).forEach(::writeTerminalResponse)
                    framer.frame(buffer, read)?.let { completeBytes ->
                        send(TerminalOutput(completeBytes, stream))
                    }
                }
            }
        } catch (error: Throwable) {
            if (!closed.get()) {
                terminalFailure.compareAndSet(null, sshOperationFailure("stream-terminal-output", "read", error))
            }
        }
    }

    override suspend fun send(input: TerminalInput) =
        withContext(Dispatchers.IO) {
            if (closed.get()) {
                throw remoteOperationFailure(
                    code = AegisFailureCodes.SSH_SESSION_CLOSED,
                    component = "android-sshj",
                    operation = "send-terminal-input",
                    stage = "precondition",
                    category = FailureCategory.INVALID_STATE,
                    summary = "The SSH terminal session is closed",
                    retryable = false,
                )
            }
            if (input.bytes.size > MAX_INPUT_BYTES) {
                throw remoteOperationFailure(
                    code = AegisFailureCodes.SSH_INPUT_LIMIT_EXCEEDED,
                    component = "android-sshj",
                    operation = "send-terminal-input",
                    stage = "validation",
                    category = FailureCategory.RESOURCE_EXHAUSTION,
                    summary = "Terminal input exceeds the per-message limit",
                    expected = "at most $MAX_INPUT_BYTES bytes",
                    actual = "${input.bytes.size} bytes",
                    retryable = false,
                )
            }
            try {
                synchronized(outputWriteLock) {
                    shell.outputStream.write(input.bytes)
                    shell.outputStream.flush()
                }
            } catch (error: Throwable) {
                throw sshOperationFailure("send-terminal-input", "write", error)
            }
        }

    private fun writeTerminalResponse(response: ByteArray) {
        if (closed.get()) return
        synchronized(outputWriteLock) {
            shell.outputStream.write(response)
            shell.outputStream.flush()
        }
    }

    override suspend fun resize(
        columns: Int,
        rows: Int,
    ) = withContext(Dispatchers.IO) {
        if (closed.get()) {
            throw remoteOperationFailure(
                code = AegisFailureCodes.SSH_SESSION_CLOSED,
                component = "android-sshj",
                operation = "resize-terminal",
                stage = "precondition",
                category = FailureCategory.INVALID_STATE,
                summary = "The SSH terminal session is closed",
                retryable = false,
            )
        }
        if (columns !in MIN_COLUMNS..MAX_COLUMNS || rows !in MIN_ROWS..MAX_ROWS) {
            throw remoteOperationFailure(
                code = AegisFailureCodes.SSH_PTY_SIZE_INVALID,
                component = "android-sshj",
                operation = "resize-terminal",
                stage = "validation",
                category = FailureCategory.CONFIGURATION,
                summary = "The requested PTY size is outside the supported bounds",
                expected = "$MIN_COLUMNS-$MAX_COLUMNS columns and $MIN_ROWS-$MAX_ROWS rows",
                actual = "$columns columns and $rows rows",
                retryable = false,
            )
        }
        try {
            terminalQueryResponder.resize(columns, rows)
            shell.changeWindowDimensions(columns, rows, 0, 0)
        } catch (error: Throwable) {
            throw sshOperationFailure("resize-terminal", "pty", error)
        }
    }

    override suspend fun awaitExit(): TerminalExit = exit.await()

    private fun remoteExitReported(): Boolean {
        val command = shell as? Session.Command ?: return false
        return command.exitStatus != null || command.exitSignal != null || command.exitErrorMessage != null
    }

    override suspend fun close() =
        withContext(Dispatchers.IO) {
            clientCloseRequested.set(true)
            closeTransport()
            Unit
        }

    private fun closeTransport() {
        if (!closed.compareAndSet(false, true)) return
        // The dedicated socket is closed first so blocking SSHJ reads/writes are physically
        // interrupted on coroutine cancellation instead of waiting for cooperative shutdown.
        runCatching { ssh.socket.close() }
        runCatching { shell.outputStream.close() }
        runCatching { shell.close() }
        runCatching { session.close() }
        runCatching { ssh.disconnect() }
        runCatching { ssh.close() }
        completeExit(
            closedByClient = clientCloseRequested.get(),
            transportFailure = terminalFailure.get() != null && !clientCloseRequested.get(),
        )
        sessionScope.cancel()
    }

    private fun completeExit(
        closedByClient: Boolean,
        transportFailure: Boolean,
    ) {
        if (exit.isCompleted) return
        val command = shell as? Session.Command
        exit.complete(
            TerminalExit(
                status = command?.exitStatus,
                signal = command?.exitSignal?.name,
                errorMessage = command?.exitErrorMessage ?: terminalFailure.get()?.message,
                closedByClient = closedByClient,
                transportFailure = transportFailure,
            ),
        )
    }

    private companion object {
        const val OUTPUT_CHUNK_BYTES = 4 * 1_024
        const val MAX_INPUT_BYTES = 64 * 1_024
        const val MIN_COLUMNS = 20
        const val MAX_COLUMNS = 300
        const val MIN_ROWS = 5
        const val MAX_ROWS = 120
    }
}

/**
 * Answers the small set of VT/xterm capability queries used by interactive shells such as fish.
 * The PTY advertises xterm-256color, so leaving these unanswered makes the shell block for its
 * compatibility timeout before it accepts the first command.
 */
internal class VtTerminalQueryResponder(
    private var columns: Int = DEFAULT_COLUMNS,
    private var rows: Int = DEFAULT_ROWS,
) {
    private var tail = ""
    private var cursorRow = 0
    private var cursorColumn = 0

    fun resize(
        columns: Int,
        rows: Int,
    ) {
        this.columns = columns.coerceAtLeast(1)
        this.rows = rows.coerceAtLeast(1)
        cursorRow = cursorRow.coerceIn(0, this.rows - 1)
        cursorColumn = cursorColumn.coerceIn(0, this.columns - 1)
    }

    fun responsesFor(
        bytes: ByteArray,
        length: Int = bytes.size,
    ): List<ByteArray> {
        require(length in 0..bytes.size)
        val chunk = bytes.copyOfRange(0, length).toString(Charsets.ISO_8859_1)
        val text = tail + chunk
        val responses = mutableListOf<ByteArray>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                ESC -> {
                    val consumed = consumeEscape(text, index + 1, responses)
                    if (consumed <= index) {
                        tail = text.substring(index)
                        return responses
                    }
                    index = consumed
                }

                '\r' -> {
                    cursorColumn = 0
                    index += 1
                }

                '\n' -> {
                    lineFeed()
                    index += 1
                }

                '\b' -> {
                    cursorColumn = (cursorColumn - 1).coerceAtLeast(0)
                    index += 1
                }

                in '\u0000'..'\u001F', '\u007F' -> {
                    index += 1
                }

                else -> {
                    putGlyph()
                    index += 1
                }
            }
        }
        tail = ""
        return responses
    }

    private fun consumeEscape(
        text: String,
        start: Int,
        responses: MutableList<ByteArray>,
    ): Int {
        if (start >= text.length) return start - 1
        return when (text[start]) {
            '[' -> consumeCsi(text, start + 1, responses)
            ']' -> consumeOsc(text, start + 1)
            '7', '8', 'c' -> start + 1
            '(', ')', '*', '+', '-', '.', '/' -> (start + 2).coerceAtMost(text.length)
            else -> start + 1
        }
    }

    private fun consumeOsc(
        text: String,
        start: Int,
    ): Int {
        var index = start
        while (index < text.length) {
            when {
                text[index] == BEL -> return index + 1
                text[index] == ESC && index + 1 < text.length && text[index + 1] == '\\' -> return index + 2
                else -> index += 1
            }
        }
        return start - 2
    }

    // CSI dispatch is a flat decode table over terminal final bytes; splitting
    // it would scatter one escape-sequence grammar across many helpers.
    @Suppress("CyclomaticComplexMethod")
    private fun consumeCsi(
        text: String,
        start: Int,
        responses: MutableList<ByteArray>,
    ): Int {
        var index = start
        while (index < text.length && text[index].code !in CSI_FINAL_MIN..CSI_FINAL_MAX) {
            index += 1
        }
        if (index >= text.length) return start - 2
        val body = text.substring(start, index)
        val final = text[index]
        when {
            body in listOf("", "0") && final == 'c' -> {
                responses += PRIMARY_DEVICE_ATTRIBUTES_RESPONSE
            }

            body in listOf(">", ">0") && final == 'c' -> {
                responses += SECONDARY_DEVICE_ATTRIBUTES_RESPONSE
            }

            body == "5" && final == 'n' -> {
                responses += DEVICE_STATUS_RESPONSE
            }

            body == "6" && final == 'n' -> {
                responses += cursorPositionResponse()
            }

            final == 'H' || final == 'f' -> {
                val parameters = body.split(';').map { it.toIntOrNull() ?: 1 }
                cursorRow = ((parameters.getOrNull(0) ?: 1) - 1).coerceIn(0, rows - 1)
                cursorColumn = ((parameters.getOrNull(1) ?: 1) - 1).coerceIn(0, columns - 1)
            }

            final == 'G' || final == '`' -> {
                val column = (body.toIntOrNull() ?: 1) - 1
                cursorColumn = column.coerceIn(0, columns - 1)
            }

            final == 'A' -> {
                cursorRow = (cursorRow - (body.toIntOrNull() ?: 1)).coerceAtLeast(0)
            }

            final == 'B' -> {
                cursorRow = (cursorRow + (body.toIntOrNull() ?: 1)).coerceAtMost(rows - 1)
            }

            final == 'C' -> {
                cursorColumn = (cursorColumn + (body.toIntOrNull() ?: 1)).coerceAtMost(columns - 1)
            }

            final == 'D' -> {
                cursorColumn = (cursorColumn - (body.toIntOrNull() ?: 1)).coerceAtLeast(0)
            }
        }
        return index + 1
    }

    private fun putGlyph() {
        cursorColumn += 1
        if (cursorColumn >= columns) {
            cursorColumn = 0
            lineFeed()
        }
    }

    private fun lineFeed() {
        if (cursorRow < rows - 1) {
            cursorRow += 1
        }
        cursorColumn = 0
    }

    private fun cursorPositionResponse(): ByteArray = "\u001B[${cursorRow + 1};${cursorColumn + 1}R".toByteArray(Charsets.US_ASCII)

    private companion object {
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
        const val CSI_FINAL_MIN = 0x40
        const val CSI_FINAL_MAX = 0x7E
        const val ESC = '\u001B'
        const val BEL = '\u0007'
        val PRIMARY_DEVICE_ATTRIBUTES_RESPONSE = "\u001B[?1;2c".toByteArray(Charsets.US_ASCII)
        val SECONDARY_DEVICE_ATTRIBUTES_RESPONSE = "\u001B[>0;136;0c".toByteArray(Charsets.US_ASCII)
        val DEVICE_STATUS_RESPONSE = "\u001B[0n".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * Keeps a potentially incomplete UTF-8 code point between SSHJ reads without decoding terminal
 * output or changing its byte-oriented contract. Invalid UTF-8 is passed through unchanged; only
 * a well-formed prefix at the end of a chunk is delayed until the next chunk (or EOF).
 */
internal class Utf8ChunkFramer {
    private var pending: ByteArray = EMPTY_BYTES

    internal val pendingByteCount: Int
        get() = pending.size

    fun frame(
        bytes: ByteArray,
        length: Int = bytes.size,
    ): ByteArray? {
        require(length in 0..bytes.size) { "length must be within the source byte array" }
        if (length == 0) return null

        val combined = ByteArray(pending.size + length)
        pending.copyInto(combined)
        bytes.copyInto(combined, destinationOffset = pending.size, endIndex = length)

        val suffixLength = incompleteUtf8SuffixLength(combined)
        val emittedLength = combined.size - suffixLength
        pending =
            if (suffixLength == 0) {
                EMPTY_BYTES
            } else {
                combined.copyOfRange(emittedLength, combined.size)
            }
        return combined.copyOfRange(0, emittedLength).takeIf { it.isNotEmpty() }
    }

    fun finish(): ByteArray? {
        val trailing = pending.takeIf { it.isNotEmpty() }
        pending = EMPTY_BYTES
        return trailing
    }

    private companion object {
        val EMPTY_BYTES = ByteArray(0)
    }
}

private fun incompleteUtf8SuffixLength(bytes: ByteArray): Int {
    if (bytes.isEmpty()) return 0
    val earliestPossibleLead = (bytes.size - MAX_INCOMPLETE_UTF8_BYTES).coerceAtLeast(0)
    for (leadIndex in bytes.lastIndex downTo earliestPossibleLead) {
        val expectedLength = utf8SequenceLength(bytes[leadIndex].toUnsignedInt())
        if (expectedLength == 0) continue

        val availableLength = bytes.size - leadIndex
        if (availableLength >= expectedLength) continue
        if (isWellFormedUtf8Prefix(bytes, leadIndex, availableLength)) return availableLength
    }
    return 0
}

private fun isWellFormedUtf8Prefix(
    bytes: ByteArray,
    leadIndex: Int,
    availableLength: Int,
): Boolean {
    if (availableLength <= 1) return true
    val lead = bytes[leadIndex].toUnsignedInt()
    val second = bytes[leadIndex + 1].toUnsignedInt()
    val secondIsValid =
        when (lead) {
            0xE0 -> second in 0xA0..0xBF
            0xED -> second in 0x80..0x9F
            0xF0 -> second in 0x90..0xBF
            0xF4 -> second in 0x80..0x8F
            else -> second.isUtf8Continuation()
        }
    if (!secondIsValid) return false
    for (index in 2 until availableLength) {
        if (!bytes[leadIndex + index].toUnsignedInt().isUtf8Continuation()) return false
    }
    return true
}

private fun utf8SequenceLength(lead: Int): Int =
    when (lead) {
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> 0
    }

private fun Int.isUtf8Continuation(): Boolean = this in 0x80..0xBF

private fun Byte.toUnsignedInt(): Int = toInt() and 0xFF

private const val MAX_INCOMPLETE_UTF8_BYTES = 3
