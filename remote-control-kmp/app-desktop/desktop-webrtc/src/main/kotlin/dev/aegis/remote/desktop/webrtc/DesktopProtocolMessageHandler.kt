package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.clipboard.ClipboardBridge
import dev.aegis.remote.core.clipboard.ClipboardSyncDecision
import dev.aegis.remote.core.clipboard.ClipboardSyncDirection
import dev.aegis.remote.core.clipboard.ClipboardSyncPolicy
import dev.aegis.remote.core.clipboard.ClipboardSyncPolicyEngine
import dev.aegis.remote.core.clipboard.ClipboardSyncRequest
import dev.aegis.remote.core.input.RemoteInputAdmissionController
import dev.aegis.remote.core.input.RemoteInputAdmissionDecision
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.input.RemoteInputExecutor
import dev.aegis.remote.core.input.isInside
import dev.aegis.remote.core.model.AegisFailure
import dev.aegis.remote.core.model.AppError
import dev.aegis.remote.core.model.ClipboardPayload
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.FailureCategory
import dev.aegis.remote.core.model.MonitorInfo
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.model.localSessionProtocolCapabilities
import dev.aegis.remote.core.monitor.MonitorProvider
import dev.aegis.remote.core.webrtc.RemoteVideoSession
import dev.aegis.remote.protocol.ControlCommand
import dev.aegis.remote.protocol.ProtocolMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class DesktopProtocolMessageException(
    val appError: AppError,
) : RuntimeException(appError.message)

sealed interface ProtocolHandlingResult {
    data class Handled(
        val action: String,
    ) : ProtocolHandlingResult

    data class Ignored(
        val reason: String,
    ) : ProtocolHandlingResult

    data class Respond(
        val message: ProtocolMessage,
    ) : ProtocolHandlingResult
}

class DesktopProtocolMessageHandler(
    private val sessionId: SessionId,
    private val permissions: DevicePermissions,
    private val inputExecutor: RemoteInputExecutor,
    private val clipboardBridge: ClipboardBridge,
    private val videoSession: RemoteVideoSession,
    private val inputEnabled: () -> Boolean = { true },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val monitorProvider: MonitorProvider? = null,
    private val inputAdmissionController: RemoteInputAdmissionController = RemoteInputAdmissionController(clock = clock),
    private val clipboardPolicyEngine: ClipboardSyncPolicyEngine = ClipboardSyncPolicyEngine(),
) {
    private val inputMutex = Mutex()
    private val clipboardMutex = Mutex()
    private var monitorCache: List<MonitorInfo> = emptyList()
    private var monitorCacheAtEpochMillis: Long = Long.MIN_VALUE

    suspend fun handle(message: ProtocolMessage): ProtocolHandlingResult {
        if (message.sessionId != sessionId) {
            throw DesktopProtocolMessageException(
                AppError.Authorization(
                    "Protocol message session ${message.sessionId.value} does not match active session ${sessionId.value}",
                ),
            )
        }

        return when (message) {
            is ProtocolMessage.Input -> handleInput(message.event)
            is ProtocolMessage.Control -> handleControl(message.command)
            is ProtocolMessage.Signaling -> ProtocolHandlingResult.Ignored("Signaling is handled by the signaling client")
            is ProtocolMessage.Stats -> ProtocolHandlingResult.Ignored("Stats messages are not consumed by the desktop control handler")
            is ProtocolMessage.Monitors -> ProtocolHandlingResult.Ignored("Monitor list messages are produced by the desktop side")
        }
    }

    private suspend fun handleInput(event: RemoteInputEvent): ProtocolHandlingResult =
        when (event) {
            is RemoteInputEvent.ClipboardSync -> {
                requireClipboardPermission()
                clipboardMutex.withLock { writeRemoteClipboard(event.text) }
            }

            is RemoteInputEvent.SelectMonitor -> {
                requireVisualPermission()
                videoSession.selectMonitor(event.monitorId.value)
                ProtocolHandlingResult.Handled("select-monitor")
            }

            is RemoteInputEvent.SetQuality -> {
                ProtocolHandlingResult.Ignored("SetQuality input event requires a full VideoConfig control message")
            }

            is RemoteInputEvent.MouseMove,
            is RemoteInputEvent.MouseMoveRelative,
            is RemoteInputEvent.MouseButton,
            is RemoteInputEvent.Scroll,
            is RemoteInputEvent.Key,
            is RemoteInputEvent.TextInput,
            is RemoteInputEvent.Shortcut,
            -> {
                inputMutex.withLock { executeRemoteInput(event) }
            }
        }

    private suspend fun handleControl(command: ControlCommand): ProtocolHandlingResult =
        when (command) {
            is ControlCommand.StartVisualSession -> {
                requireVisualPermission()
                videoSession.start(command.videoConfig, command.iceConfig)
                ProtocolHandlingResult.Handled("start-visual")
            }

            ControlCommand.StopVisualSession -> {
                requireVisualPermission()
                videoSession.stop()
                ProtocolHandlingResult.Handled("stop-visual")
            }

            is ControlCommand.SelectMonitor -> {
                requireVisualPermission()
                videoSession.selectMonitor(command.monitorId.value)
                ProtocolHandlingResult.Handled("select-monitor")
            }

            is ControlCommand.SetQuality -> {
                requireVisualPermission()
                videoSession.setQuality(command.videoConfig)
                ProtocolHandlingResult.Handled("set-quality")
            }

            is ControlCommand.Ping -> {
                ProtocolHandlingResult.Respond(
                    ProtocolMessage.Control(
                        sessionId = sessionId,
                        command =
                            ControlCommand.Pong(
                                pingSentAtEpochMillis = command.sentAtEpochMillis,
                                receivedAtEpochMillis = clock(),
                            ),
                    ),
                )
            }

            is ControlCommand.Pong -> {
                ProtocolHandlingResult.Handled("pong")
            }

            is ControlCommand.Error -> {
                ProtocolHandlingResult.Ignored("Remote control error ${command.code}: ${command.message}")
            }

            is ControlCommand.SessionCapabilities -> {
                val local = localSessionProtocolCapabilities()
                ProtocolHandlingResult.Respond(
                    ProtocolMessage.Control(
                        sessionId = sessionId,
                        command =
                            ControlCommand.SessionCapabilities(
                                protocolRev = local.protocolRev,
                                features = local.features,
                            ),
                    ),
                )
            }
        }

    @Suppress("TooGenericExceptionCaught")
    suspend fun close() {
        inputMutex.withLock {
            try {
                inputExecutor.releaseAll()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                throw protocolFailure(
                    error = AppError.CapabilityUnavailable("Held remote input could not be released"),
                    code = INPUT_RELEASE_FAILED_CODE,
                    operation = "release-held-input",
                    stage = "session-cleanup",
                    category = FailureCategory.WINDOWS_API,
                    summary = "The input backend did not confirm release of every tracked remote key and button.",
                    technicalCause = error.message.nonBlankCause("The input backend raised ${error::class.simpleName}"),
                    expected = "All tracked keys and buttons are released idempotently",
                    actual = "The release batch failed during session cleanup",
                    nextAction = "Keep the session closed and ask the local user to release affected keys/buttons before reconnecting.",
                    underlyingType = error::class.qualifiedName,
                )
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun executeRemoteInput(event: RemoteInputEvent): ProtocolHandlingResult {
        requireInputPermission()
        when (val decision = inputAdmissionController.evaluate(event)) {
            RemoteInputAdmissionDecision.Allowed -> {
                Unit
            }

            is RemoteInputAdmissionDecision.DropCoalescable -> {
                return ProtocolHandlingResult.Ignored(decision.code)
            }

            is RemoteInputAdmissionDecision.Rejected -> {
                throw protocolFailure(
                    error = AppError.Validation(decision.reason),
                    code = decision.code,
                    operation = "validate-remote-input",
                    stage = "admission-control",
                    category = FailureCategory.PROTOCOL,
                    summary = "The remote input event was rejected before host injection.",
                    expected = "A bounded, structurally valid input event",
                    actual = decision.reason,
                    nextAction = "Correct the sender event or reduce its per-session event rate.",
                )
            }
        }
        if (event is RemoteInputEvent.MouseMove) requireValidMonitorCoordinates(event)
        try {
            inputExecutor.execute(event)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: DesktopProtocolMessageException) {
            throw error
        } catch (error: Throwable) {
            runCatching { inputExecutor.releaseAll() }
            throw protocolFailure(
                error = AppError.CapabilityUnavailable("Host input injection failed"),
                code = INPUT_INJECTION_FAILED_CODE,
                operation = "inject-remote-input",
                stage = "windows-send-input",
                category = FailureCategory.WINDOWS_API,
                summary = "The host input backend rejected or could not complete remote input injection.",
                technicalCause = error.message.nonBlankCause("The input backend raised ${error::class.simpleName}"),
                expected = "The active interactive desktop accepts the complete input batch",
                actual = "The input backend failed and all tracked keys/buttons were released",
                nextAction =
                    "Keep input blocked on secure desktop/UAC prompts; return to the user's interactive desktop and verify integrity level access.",
                underlyingType = error::class.qualifiedName,
            )
        }
        return ProtocolHandlingResult.Handled("input")
    }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun writeRemoteClipboard(text: String): ProtocolHandlingResult {
        val payload = ClipboardPayload.Text(text)
        when (
            val decision =
                clipboardPolicyEngine.evaluate(
                    ClipboardSyncRequest(
                        policy = ClipboardSyncPolicy.Manual,
                        direction = ClipboardSyncDirection.AndroidToDesktop,
                        payload = payload,
                        userConfirmed = true,
                    ),
                )
        ) {
            ClipboardSyncDecision.Allowed -> {
                Unit
            }

            is ClipboardSyncDecision.Blocked -> {
                throw protocolFailure(
                    error = AppError.Validation(decision.reason),
                    code = decision.code,
                    operation = "write-remote-clipboard",
                    stage = "privacy-policy",
                    category = FailureCategory.DATA,
                    summary = "The inbound clipboard value was blocked by the desktop privacy policy.",
                    expected = "Non-empty text within the configured byte limit and privacy policy",
                    actual = decision.reason,
                    nextAction = "Use a smaller non-sensitive text value or an explicit file transfer for files.",
                )
            }

            is ClipboardSyncDecision.RequiresConfirmation -> {
                throw protocolFailure(
                    error = AppError.Authorization(decision.reason),
                    code = decision.code,
                    operation = "write-remote-clipboard",
                    stage = "consent",
                    category = FailureCategory.AUTHORIZATION,
                    summary = "Clipboard synchronization requires explicit local consent.",
                    expected = "An approved clipboard action for this paired device",
                    actual = decision.reason,
                    nextAction = "Confirm the clipboard action in the visible session UI.",
                )
            }
        }
        try {
            withTimeout(CLIPBOARD_OPERATION_TIMEOUT_MILLIS) {
                clipboardBridge.writeFromRemote(payload)
            }
        } catch (error: TimeoutCancellationException) {
            throw protocolFailure(
                error = AppError.CapabilityUnavailable("Desktop clipboard write timed out"),
                code = CLIPBOARD_TIMEOUT_CODE,
                operation = "write-remote-clipboard",
                stage = "windows-clipboard",
                category = FailureCategory.TIMEOUT,
                summary = "The host clipboard did not accept the remote text before the timeout.",
                technicalCause = "The clipboard may be locked by another process.",
                expected = "A clipboard write completes within $CLIPBOARD_OPERATION_TIMEOUT_MILLIS ms",
                actual = "The operation timed out without logging clipboard contents",
                nextAction = "Wait for the application holding the clipboard to finish, then retry once.",
                underlyingType = error::class.qualifiedName,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw protocolFailure(
                error = AppError.CapabilityUnavailable("Desktop clipboard write failed"),
                code = CLIPBOARD_WRITE_FAILED_CODE,
                operation = "write-remote-clipboard",
                stage = "windows-clipboard",
                category = FailureCategory.WINDOWS_API,
                summary = "The host clipboard rejected the remote clipboard write.",
                technicalCause = error.message.nonBlankCause("The clipboard backend raised ${error::class.simpleName}"),
                expected = "The active user desktop accepts a bounded text clipboard value",
                actual = "The clipboard backend failed without logging clipboard contents",
                nextAction = "Verify an interactive desktop is active and retry after the clipboard lock is released.",
                underlyingType = error::class.qualifiedName,
            )
        }
        return ProtocolHandlingResult.Handled("clipboard")
    }

    private suspend fun requireValidMonitorCoordinates(event: RemoteInputEvent.MouseMove) {
        val provider = monitorProvider ?: return
        val now = clock()
        if (monitorCache.isEmpty() || now < monitorCacheAtEpochMillis || now - monitorCacheAtEpochMillis >= MONITOR_CACHE_MILLIS) {
            monitorCache =
                runCatching { provider.listMonitors() }
                    .getOrElse { error ->
                        throw protocolFailure(
                            error = AppError.CapabilityUnavailable("Desktop monitor topology is unavailable"),
                            code = INPUT_MONITOR_TOPOLOGY_FAILED_CODE,
                            operation = "validate-pointer-coordinates",
                            stage = "monitor-topology",
                            category = FailureCategory.WINDOWS_API,
                            summary = "The desktop monitor topology could not be read before pointer injection.",
                            technicalCause = error.message.nonBlankCause("The monitor provider raised ${error::class.simpleName}"),
                            expected = "A current Windows virtual-desktop monitor snapshot",
                            actual = "Monitor enumeration failed",
                            nextAction = "Refresh the interactive desktop session and republish monitor telemetry before retrying.",
                            underlyingType = error::class.qualifiedName,
                        )
                    }
            monitorCacheAtEpochMillis = now
        }
        val monitor = monitorCache.firstOrNull { it.id == event.monitorId }
        if (monitor == null || !event.isInside(monitor)) {
            throw protocolFailure(
                error = AppError.Validation("Pointer coordinates do not belong to the declared monitor"),
                code = INPUT_INVALID_COORDINATES_CODE,
                operation = "validate-pointer-coordinates",
                stage = "virtual-desktop-bounds",
                category = FailureCategory.PROTOCOL,
                summary = "Absolute pointer coordinates were outside the declared monitor.",
                expected = "Coordinates inside the current bounds of monitor ${event.monitorId.value}",
                actual = "Coordinates were outside the current monitor snapshot or the monitor no longer exists",
                nextAction = "Refresh monitor telemetry and map the touch point against width - 1 and height - 1.",
            )
        }
    }

    private suspend fun requireInputPermission() {
        if (!permissions.input) {
            throw protocolFailure(
                error = AppError.Authorization("Remote input is not authorized for this device"),
                code = INPUT_NOT_AUTHORIZED_CODE,
                operation = "authorize-remote-input",
                stage = "device-permission",
                category = FailureCategory.AUTHORIZATION,
                summary = "This paired device is not permitted to inject input.",
                expected = "An active per-device input permission",
                actual = "The paired device permission denies input",
                nextAction = "Keep the event blocked; grant input only from the visible desktop device settings if intended.",
            )
        }
        if (!inputEnabled()) {
            runCatching { inputExecutor.releaseAll() }
            throw protocolFailure(
                error = AppError.Authorization("Remote input is paused by the visible desktop kill switch"),
                code = INPUT_KILL_SWITCH_ACTIVE_CODE,
                operation = "authorize-remote-input",
                stage = "local-kill-switch",
                category = FailureCategory.AUTHORIZATION,
                summary = "The local user paused remote input.",
                expected = "The visible desktop input switch is enabled",
                actual = "The switch is disabled and held input was released",
                nextAction = "Keep input blocked until the local user explicitly resumes it.",
            )
        }
    }

    private fun requireClipboardPermission() {
        if (!permissions.clipboard) {
            throw protocolFailure(
                error = AppError.Authorization("Clipboard sync is not authorized for this device"),
                code = CLIPBOARD_NOT_AUTHORIZED_CODE,
                operation = "authorize-clipboard-sync",
                stage = "device-permission",
                category = FailureCategory.AUTHORIZATION,
                summary = "This paired device is not permitted to synchronize clipboard text.",
                expected = "An active per-device clipboard permission",
                actual = "The paired device permission denies clipboard access",
                nextAction = "Keep the value blocked; grant clipboard access only from visible desktop settings if intended.",
            )
        }
    }

    private fun requireVisualPermission() {
        if (!permissions.visual) {
            throw DesktopProtocolMessageException(AppError.Authorization("Visual remote control is not authorized for this device"))
        }
    }

    private fun protocolFailure(
        error: AppError,
        code: String,
        operation: String,
        stage: String,
        category: FailureCategory,
        summary: String,
        technicalCause: String? = null,
        expected: String,
        actual: String,
        nextAction: String,
        underlyingType: String? = null,
    ): DesktopProtocolMessageException {
        val failure =
            AegisFailure(
                code = code,
                component = "desktop-protocol-input-clipboard",
                operation = operation,
                stage = stage,
                category = category,
                summary = summary,
                technicalCause = technicalCause,
                expected = expected,
                actual = actual,
                retryable = category == FailureCategory.TIMEOUT,
                correlationId = sessionId.value,
                evidenceRef = null,
                nextAction = nextAction,
                underlyingType = underlyingType,
            )
        val causalError =
            when (error) {
                is AppError.Network -> error.copy(failure = failure)
                is AppError.Authentication -> error.copy(failure = failure)
                is AppError.Authorization -> error.copy(failure = failure)
                is AppError.HostKeyMismatch -> error.copy(failure = failure)
                is AppError.CapabilityUnavailable -> error.copy(failure = failure)
                is AppError.Validation -> error.copy(failure = failure)
                is AppError.Unknown -> error.copy(failure = failure)
            }
        return DesktopProtocolMessageException(causalError)
    }
}

private const val INPUT_NOT_AUTHORIZED_CODE = "INP-1001"
private const val INPUT_KILL_SWITCH_ACTIVE_CODE = "INP-1002"
private const val INPUT_INVALID_COORDINATES_CODE = "INP-1003"
private const val INPUT_INJECTION_FAILED_CODE = "INP-1005"
private const val INPUT_MONITOR_TOPOLOGY_FAILED_CODE = "INP-1006"
private const val INPUT_RELEASE_FAILED_CODE = "INP-1007"
private const val CLIPBOARD_NOT_AUTHORIZED_CODE = "CLP-1001"
private const val CLIPBOARD_TIMEOUT_CODE = "CLP-1004"
private const val CLIPBOARD_WRITE_FAILED_CODE = "CLP-1005"
private const val CLIPBOARD_OPERATION_TIMEOUT_MILLIS = 2_000L
private const val MONITOR_CACHE_MILLIS = 2_000L

private fun String?.nonBlankCause(fallback: String): String = this?.take(300)?.takeIf(String::isNotBlank) ?: fallback
