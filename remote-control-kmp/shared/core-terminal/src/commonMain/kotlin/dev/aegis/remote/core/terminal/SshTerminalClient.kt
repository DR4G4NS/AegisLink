package dev.aegis.remote.core.terminal

import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.HostKeyFingerprint
import kotlinx.coroutines.flow.Flow

interface SshTerminalClient {
    suspend fun connect(
        profile: DeviceProfile,
        routeHost: String,
    ): TerminalSession
}

interface TerminalSession {
    val output: Flow<TerminalOutput>

    suspend fun send(input: TerminalInput)

    suspend fun resize(
        columns: Int,
        rows: Int,
    )

    /** Completes when the remote shell exits, the transport fails, or the client closes it. */
    suspend fun awaitExit(): TerminalExit

    suspend fun close()
}

data class TerminalInput(
    val bytes: ByteArray,
)

data class TerminalOutput(
    val bytes: ByteArray,
    val stream: TerminalOutputStream = TerminalOutputStream.Stdout,
)

enum class TerminalOutputStream {
    Stdout,
    Stderr,
}

data class TerminalExit(
    val status: Int? = null,
    val signal: String? = null,
    val errorMessage: String? = null,
    val closedByClient: Boolean = false,
    val transportFailure: Boolean = false,
)

data class TerminalPtySize(
    val columns: Int,
    val rows: Int,
)

data class TerminalViewportMetrics(
    val viewportWidthPx: Float,
    val viewportHeightPx: Float,
    val cellWidthPx: Float,
    val cellHeightPx: Float,
)

class TerminalViewportSizer(
    private val minColumns: Int = 20,
    private val maxColumns: Int = 300,
    private val minRows: Int = 5,
    private val maxRows: Int = 120,
) {
    fun calculate(metrics: TerminalViewportMetrics): TerminalPtySize {
        require(metrics.viewportWidthPx > 0f) { "viewportWidthPx must be positive" }
        require(metrics.viewportHeightPx > 0f) { "viewportHeightPx must be positive" }
        require(metrics.cellWidthPx > 0f) { "cellWidthPx must be positive" }
        require(metrics.cellHeightPx > 0f) { "cellHeightPx must be positive" }
        require(minColumns > 0) { "minColumns must be positive" }
        require(minRows > 0) { "minRows must be positive" }
        require(maxColumns >= minColumns) { "maxColumns must be greater than or equal to minColumns" }
        require(maxRows >= minRows) { "maxRows must be greater than or equal to minRows" }

        val columns =
            (metrics.viewportWidthPx / metrics.cellWidthPx)
                .toInt()
                .coerceIn(minColumns, maxColumns)
        val rows =
            (metrics.viewportHeightPx / metrics.cellHeightPx)
                .toInt()
                .coerceIn(minRows, maxRows)

        return TerminalPtySize(columns = columns, rows = rows)
    }
}

interface HostKeyVerifier {
    suspend fun verify(
        host: String,
        port: Int,
        actual: HostKeyFingerprint,
    ): Boolean
}
