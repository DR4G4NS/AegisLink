package dev.aegis.remote.android.home

import dev.aegis.remote.android.security.AndroidRemoteOperationException
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.session.ExponentialBackoffReconnectionManager
import dev.aegis.remote.core.session.SshRoutePlan
import dev.aegis.remote.core.terminal.SshTerminalClient
import dev.aegis.remote.core.terminal.TerminalInput
import dev.aegis.remote.core.terminal.TerminalKeyStroke
import dev.aegis.remote.core.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class TerminalCoordinator(
    private val state: StateFlow<AndroidHomeUiState>,
    private val scope: CoroutineScope,
    private val cleanupScope: CoroutineScope,
    private val terminalClient: SshTerminalClient,
    private val reconnectionManager: ExponentialBackoffReconnectionManager,
    private val resolveRoute: suspend (DeviceProfile) -> SshRoutePlan,
    private val onProfileConnected: suspend (DeviceProfileId, ConnectionRouteType) -> Unit,
    private val onSessionStarted: () -> Unit,
    private val onSessionIdle: () -> Unit,
    private val updateState: ((AndroidHomeUiState) -> AndroidHomeUiState) -> Unit,
) {
    private val terminalInputMutex = Mutex()
    private var activeTerminalSession: TerminalSession? = null
    private var terminalConnectionJob: Job? = null
    private var terminalOutputJob: Job? = null
    private var terminalResizeJob: Job? = null
    private var terminalGeneration: Long = 0

    val hasActiveSession: Boolean
        get() = activeTerminalSession != null

    fun start() {
        val current = state.value
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId } ?: return
        if (!profile.permissions.terminal) {
            updateState { it.copy(errorMessage = "SSH terminal access is not permitted for this profile.") }
            return
        }
        val generation = ++terminalGeneration
        terminalConnectionJob?.cancel()
        terminalOutputJob?.cancel()
        terminalResizeJob?.cancel()
        val previousSession = activeTerminalSession.also { activeTerminalSession = null }
        previousSession?.let { stale ->
            cleanupScope.launch { runCatching { stale.close() } }
        }
        updateState {
            it.copy(
                screen = AndroidHomeScreenMode.Terminal,
                terminal = TerminalUiState(connecting = true, connected = false, output = "Resolving SSH route...\n"),
                errorMessage = null,
            )
        }
        terminalConnectionJob =
            scope.launch {
                var pendingSession: TerminalSession? = null
                try {
                    runCatching {
                        val route = resolveRoute(profile)
                        val routeProfile = profile.withSshRoutePort(route)
                        val session = connectTerminalWithRetry(routeProfile, route)
                        pendingSession = session
                        if (isCurrentTerminalSession(generation, profile.id)) {
                            activeTerminalSession = session
                            pendingSession = null
                            onProfileConnected(profile.id, route.route.type)
                            onSessionStarted()
                            updateState {
                                it.copy(terminal = it.terminal.copy(connecting = false, connected = true, output = it.terminal.output + "Connected.\n"))
                            }
                            terminalOutputJob?.cancel()
                            terminalOutputJob = launchTerminalOutputLoop(profile, route, session)
                        } else {
                            session.close()
                        }
                    }.onFailure { error ->
                        pendingSession?.let { runCatching { it.close() } }
                        if (error is CancellationException) throw error
                        if (generation == terminalGeneration &&
                            state.value.screen == AndroidHomeScreenMode.Terminal
                        ) {
                            updateState {
                                it.copy(
                                    terminal =
                                        it.terminal.copy(
                                            connecting = false,
                                            connected = false,
                                            output =
                                                it.terminal.output + "Failed: ${error.message}\n",
                                        ),
                                    errorMessage = error.message,
                                )
                            }
                        }
                    }
                } finally {
                    if (generation == terminalGeneration) {
                        terminalConnectionJob = null
                    }
                }
            }
    }

    fun resize() {
        val session = activeTerminalSession
        if (session == null) {
            updateState {
                it.copy(terminal = it.terminal.copy(output = it.terminal.output + "No active SSH terminal to resize.\n"))
            }
            return
        }
        val terminal = state.value.terminal
        val columns = terminal.columns.toIntOrNull()
        val rows = terminal.rows.toIntOrNull()
        if (!isValidPtySize(columns, rows)) {
            updateState {
                it.copy(terminal = it.terminal.copy(output = it.terminal.output + "PTY size must be 20-300 columns and 5-120 rows.\n"))
            }
            return
        }
        val ptyColumns = checkNotNull(columns)
        val ptyRows = checkNotNull(rows)
        updateState { it.copy(terminal = it.terminal.copy(resizing = true)) }
        scope.launch {
            runCatching { session.resize(ptyColumns, ptyRows) }
                .onSuccess {
                    updateState {
                        it.copy(
                            terminal =
                                it.terminal.copy(
                                    resizing = false,
                                    output = it.terminal.output + "PTY resized to ${ptyColumns}x$ptyRows.\n",
                                ),
                        )
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    updateState {
                        it.copy(
                            terminal =
                                it.terminal.copy(
                                    resizing = false,
                                    output = it.terminal.output + "PTY resize failed: ${error.message ?: error::class.simpleName}\n",
                                ),
                        )
                    }
                }
        }
    }

    fun autoResize(
        columns: Int,
        rows: Int,
    ) {
        val session = activeTerminalSession ?: return
        val generation = terminalGeneration
        val terminal = state.value.terminal
        if (!terminal.acceptsAutoResize(columns, rows)) return

        updateState {
            it.copy(
                terminal =
                    it.terminal.copy(
                        columns = columns.toString(),
                        rows = rows.toString(),
                        resizing = true,
                    ),
            )
        }
        terminalResizeJob?.cancel()
        terminalResizeJob =
            scope.launch {
                delay(AUTO_RESIZE_DEBOUNCE_MILLIS)
                if (!isCurrentResize(session, generation)) return@launch
                runCatching { session.resize(columns, rows) }
                    .onSuccess {
                        if (isCurrentResize(session, generation)) {
                            updateState { it.copy(terminal = it.terminal.copy(resizing = false)) }
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        if (isCurrentResize(session, generation)) {
                            updateState { it.copy(terminal = it.terminal.copy(resizing = false)) }
                        }
                    }
            }
    }

    private fun isCurrentResize(
        session: TerminalSession,
        generation: Long,
    ): Boolean = activeTerminalSession === session && terminalGeneration == generation

    private fun launchTerminalOutputLoop(
        profile: DeviceProfile,
        route: SshRoutePlan,
        initialSession: TerminalSession,
    ): Job =
        scope.launch {
            var activeSession = initialSession
            var activeRoute = route
            var reconnectAttempt = 0
            while (true) {
                val exitResult =
                    runCatching {
                        activeSession.output.collect { output ->
                            reconnectAttempt = 0
                            val text = output.bytes.toString(Charsets.UTF_8)
                            updateState {
                                it.copy(terminal = it.terminal.copy(output = (it.terminal.output + text).takeLast(TERMINAL_OUTPUT_LIMIT)))
                            }
                        }
                        activeSession.awaitExit()
                    }
                if (exitResult.isSuccess) {
                    val exit = checkNotNull(exitResult.getOrNull())
                    if (!exit.transportFailure) {
                        if (activeTerminalSession === activeSession) activeTerminalSession = null
                        onSessionIdle()
                        val exitLabel = terminalExitLabel(exit)
                        updateState {
                            it.copy(
                                terminal =
                                    it.terminal.copy(
                                        connecting = false,
                                        connected = false,
                                        output = (it.terminal.output + "\n$exitLabel\n").takeLast(TERMINAL_OUTPUT_LIMIT),
                                    ),
                            )
                        }
                        return@launch
                    }
                }
                val error =
                    exitResult.exceptionOrNull()
                        ?: IllegalStateException(checkNotNull(exitResult.getOrNull()).errorMessage ?: "SSH transport ended unexpectedly")
                if (error is CancellationException) throw error
                if (activeTerminalSession !== activeSession) return@launch
                when (val recovery = recoverTerminalSession(profile, activeRoute, error, reconnectAttempt)) {
                    TerminalRecovery.Stop -> {
                        return@launch
                    }

                    is TerminalRecovery.Retry -> {
                        reconnectAttempt = recovery.attempt
                    }

                    is TerminalRecovery.Reconnected -> {
                        terminalResizeJob?.cancel()
                        activeTerminalSession = recovery.session
                        activeSession = recovery.session
                        activeRoute = recovery.route
                        reconnectAttempt = 0
                        updateState {
                            it.copy(
                                terminal =
                                    it.terminal.copy(
                                        connecting = false,
                                        connected = true,
                                        output = it.terminal.output + "Reconnected.\n",
                                    ),
                                errorMessage = null,
                            )
                        }
                    }
                }
            }
        }

    private suspend fun recoverTerminalSession(
        profile: DeviceProfile,
        activeRoute: SshRoutePlan,
        error: Throwable,
        reconnectAttempt: Int,
    ): TerminalRecovery {
        runCatching { activeTerminalSession?.close() }
            .onFailure { closeError -> if (closeError is CancellationException) throw closeError }
        if (error.isNonRetryableRemoteFailure()) {
            stopAfterTerminalFailure(error, "\nSSH session stopped: ${error.message}\n")
            return TerminalRecovery.Stop
        }
        if (!reconnectionManager.shouldRetry(reconnectAttempt + 1, System.currentTimeMillis())) {
            stopAfterTerminalFailure(
                error,
                "\nSSH session lost: ${error.message ?: error::class.simpleName}. Reconnection attempts exhausted.\n",
            )
            return TerminalRecovery.Stop
        }
        val delayMillis = reconnectionManager.nextDelayMillis(reconnectAttempt)
        updateState {
            it.copy(
                terminal =
                    it.terminal.copy(
                        connecting = true,
                        connected = false,
                        output =
                            it.terminal.output +
                                "\nSSH session lost: ${error.message ?: error::class.simpleName}. Reconnecting in ${delayMillis}ms...\n",
                    ),
            )
        }
        delay(delayMillis)
        val nextRouteResult = runCatching { resolveRoute(profile) }
        if (nextRouteResult.isFailure) {
            return handleReconnectFailure(checkNotNull(nextRouteResult.exceptionOrNull()), reconnectAttempt)
        }
        val nextRoute = checkNotNull(nextRouteResult.getOrNull())
        val nextProfile = profile.withSshRoutePort(nextRoute)
        updateRouteMessage(activeRoute, nextRoute, nextProfile)
        val nextSessionResult = runCatching { connectTerminalWithRetry(nextProfile, nextRoute) }
        if (nextSessionResult.isFailure) {
            return handleReconnectFailure(checkNotNull(nextSessionResult.exceptionOrNull()), reconnectAttempt)
        }
        return TerminalRecovery.Reconnected(checkNotNull(nextSessionResult.getOrNull()), nextRoute)
    }

    private fun handleReconnectFailure(
        error: Throwable,
        reconnectAttempt: Int,
    ): TerminalRecovery {
        if (error is CancellationException) throw error
        if (error.isNonRetryableRemoteFailure()) {
            stopAfterTerminalFailure(error, "Reconnect blocked: ${error.message}\n")
            return TerminalRecovery.Stop
        }
        updateState {
            it.copy(
                terminal =
                    it.terminal.copy(
                        connecting = false,
                        connected = false,
                        output =
                            it.terminal.output +
                                "Reconnect failed: ${error.message ?: error::class.simpleName}\n",
                    ),
            )
        }
        return TerminalRecovery.Retry(reconnectAttempt + 1)
    }

    private fun terminalExitLabel(exit: dev.aegis.remote.core.terminal.TerminalExit): String =
        when {
            exit.closedByClient -> "SSH terminal closed locally."
            exit.status != null -> "Remote shell exited with status ${exit.status}."
            exit.signal != null -> "Remote shell exited after signal ${exit.signal}."
            else -> "Remote shell exited."
        }

    private fun updateRouteMessage(
        previousRoute: SshRoutePlan,
        nextRoute: SshRoutePlan,
        nextProfile: DeviceProfile,
    ) {
        val message =
            if (nextRoute.route.type != previousRoute.route.type || nextRoute.host != previousRoute.host) {
                "Route changed to ${nextRoute.host.host}:${nextProfile.sshPort} (${nextRoute.route.description}).\n"
            } else {
                "Route still ${nextRoute.host.host}:${nextProfile.sshPort} (${nextRoute.route.description}).\n"
            }
        updateState { it.copy(terminal = it.terminal.copy(output = it.terminal.output + message)) }
    }

    private fun stopAfterTerminalFailure(
        error: Throwable,
        message: String,
    ) {
        activeTerminalSession = null
        onSessionIdle()
        updateState {
            it.copy(
                terminal =
                    it.terminal.copy(
                        connecting = false,
                        connected = false,
                        output = it.terminal.output + message,
                    ),
                errorMessage = error.message,
            )
        }
    }

    private suspend fun connectTerminalWithRetry(
        profile: DeviceProfile,
        route: SshRoutePlan,
    ): TerminalSession {
        var attempt = 0
        while (true) {
            updateState {
                it.copy(
                    terminal =
                        it.terminal.copy(
                            output =
                                it.terminal.output +
                                    "Connecting via ${route.host.host}:${profile.sshPort} (${route.route.description}), attempt ${attempt + 1}...\n",
                        ),
                )
            }
            val connectionResult = runCatching { terminalClient.connect(profile, route.host.host) }
            if (connectionResult.isSuccess) {
                return checkNotNull(connectionResult.getOrNull())
            }
            val error = checkNotNull(connectionResult.exceptionOrNull())
            val nextAttempt = attempt + 1
            val now = System.currentTimeMillis()
            if (error is CancellationException ||
                error.isNonRetryableRemoteFailure() ||
                !reconnectionManager.shouldRetry(nextAttempt, now)
            ) {
                rethrowTerminalConnectionFailure(error)
            }
            val delayMillis = reconnectionManager.nextDelayMillis(attempt)
            updateState {
                it.copy(
                    terminal =
                        it.terminal.copy(
                            output =
                                it.terminal.output +
                                    "Connection failed: ${error.message ?: error::class.simpleName}. Retrying in ${delayMillis}ms...\n",
                        ),
                )
            }
            delay(delayMillis)
            attempt = nextAttempt
        }
    }

    private fun rethrowTerminalConnectionFailure(error: Throwable): Nothing {
        if (error is CancellationException) throw error
        throw error
    }

    private fun isCurrentTerminalSession(
        generation: Long,
        profileId: DeviceProfileId,
    ): Boolean =
        generation == terminalGeneration &&
            state.value.screen == AndroidHomeScreenMode.Terminal &&
            state.value.selectedProfileId == profileId

    private fun isValidPtySize(
        columns: Int?,
        rows: Int?,
    ): Boolean = columns != null && columns in 20..300 && rows != null && rows in 5..120

    fun sendInput() {
        val input = state.value.terminal.input
        if (input.isEmpty()) return
        val session = activeTerminalSession ?: return
        scope.launch {
            runCatching {
                terminalInputMutex.withLock {
                    session.send(TerminalInput((input + "\r").toByteArray(Charsets.UTF_8)))
                }
            }.onSuccess {
                updateState {
                    val nextInput = if (it.terminal.input == input) "" else it.terminal.input
                    it.copy(terminal = it.terminal.copy(input = nextInput))
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                updateState {
                    it.copy(
                        terminal =
                            it.terminal.copy(
                                output =
                                    (it.terminal.output + "\nSend failed: ${error.message}\n")
                                        .takeLast(TERMINAL_OUTPUT_LIMIT),
                            ),
                    )
                }
            }
        }
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        val session = activeTerminalSession ?: return
        val payload = text.replace("\r\n", "\n").replace('\n', '\r')
        if (payload.isEmpty()) return
        scope.launch {
            runCatching {
                terminalInputMutex.withLock {
                    session.send(TerminalInput(payload.toByteArray(Charsets.UTF_8)))
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                updateState {
                    it.copy(
                        terminal =
                            it.terminal.copy(
                                output =
                                    (it.terminal.output + "\nInput failed: ${error.message}\n")
                                        .takeLast(TERMINAL_OUTPUT_LIMIT),
                            ),
                    )
                }
            }
        }
    }

    fun sendKey(key: TerminalKeyStroke) {
        val session = activeTerminalSession ?: return
        if (key == TerminalKeyStroke.ClearScreen) {
            updateState { it.copy(terminal = it.terminal.copy(output = "")) }
        }
        scope.launch {
            runCatching {
                terminalInputMutex.withLock {
                    session.send(TerminalInput(key.bytes()))
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                updateState {
                    it.copy(
                        terminal =
                            it.terminal.copy(
                                output =
                                    (it.terminal.output + "\nKey send failed: ${error.message}\n")
                                        .takeLast(TERMINAL_OUTPUT_LIMIT),
                            ),
                    )
                }
            }
        }
    }

    fun close() {
        val session = activeTerminalSession
        activeTerminalSession = null
        terminalGeneration += 1
        val connectJob = terminalConnectionJob
        terminalConnectionJob = null
        connectJob?.cancel()
        onSessionIdle()
        terminalOutputJob?.cancel()
        terminalOutputJob = null
        terminalResizeJob?.cancel()
        terminalResizeJob = null
        updateState {
            it.copy(
                screen = AndroidHomeScreenMode.Detail,
                terminal = TerminalUiState(),
            )
        }
        scope.launch {
            runCatching { session?.close() }
            connectJob?.cancelAndJoin()
        }
    }

    private fun DeviceProfile.withSshRoutePort(route: SshRoutePlan): DeviceProfile = copy(sshPort = route.host.port ?: sshPort)

    private fun Throwable.isNonRetryableRemoteFailure(): Boolean = (this as? AndroidRemoteOperationException)?.failure?.retryable == false
}

private fun TerminalUiState.acceptsAutoResize(
    columns: Int,
    rows: Int,
): Boolean =
    columns in 20..300 &&
        rows in 5..120 &&
        connected &&
        !connecting &&
        !resizing &&
        (this.columns != columns.toString() || this.rows != rows.toString())

private const val AUTO_RESIZE_DEBOUNCE_MILLIS = 180L

private sealed interface TerminalRecovery {
    data object Stop : TerminalRecovery

    data class Retry(
        val attempt: Int,
    ) : TerminalRecovery

    data class Reconnected(
        val session: TerminalSession,
        val route: SshRoutePlan,
    ) : TerminalRecovery
}

private const val TERMINAL_OUTPUT_LIMIT = 32_000
