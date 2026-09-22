package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.model.AegisFailure
import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.AppError
import dev.aegis.remote.core.model.FailureCategory
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.webrtc.SignalingMessage
import dev.aegis.remote.protocol.ControlCommand
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageChannel
import dev.aegis.remote.protocol.isBaseProtocolMessageAllowed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DesktopProtocolChannelBridge(
    private val channel: ProtocolMessageChannel,
    private val scope: CoroutineScope,
    private val processor: suspend (ProtocolMessage) -> ProtocolHandlingResult,
    private val signalingProcessor: ((SignalingMessage) -> Unit)? = null,
    private val onClosed: suspend () -> Unit = {},
) {
    private var collectionJob: Job? = null
    private val closeMutex = Mutex()
    private var closed = false

    @Suppress("TooGenericExceptionCaught")
    fun start(): Job {
        collectionJob?.takeIf { it.isActive }?.let { return it }
        return scope
            .launch {
                try {
                    channel.incoming.collect { message ->
                        try {
                            if (!message.isBaseProtocolMessageAllowed()) return@collect
                            if (message is ProtocolMessage.Signaling) {
                                signalingProcessor?.invoke(message.message)
                                return@collect
                            }
                            when (val result = processor(message)) {
                                is ProtocolHandlingResult.Respond -> channel.send(result.message)

                                is ProtocolHandlingResult.Handled,
                                is ProtocolHandlingResult.Ignored,
                                -> Unit
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: DesktopProtocolMessageException) {
                            val failure = error.appError.failure ?: error.appError.toProtocolFailure(message.sessionId)
                            channel.send(
                                ProtocolMessage.Control(
                                    sessionId = message.sessionId,
                                    command =
                                        ControlCommand.Error(
                                            code = failure.code,
                                            message = error.appError.message,
                                            failure = failure,
                                        ),
                                ),
                            )
                        } catch (error: Throwable) {
                            val failure = unexpectedProtocolFailure(message.sessionId, error)
                            channel.send(
                                ProtocolMessage.Control(
                                    sessionId = message.sessionId,
                                    command =
                                        ControlCommand.Error(
                                            code = failure.code,
                                            message = failure.summary,
                                            failure = failure,
                                        ),
                                ),
                            )
                        }
                    }
                } finally {
                    closeResources()
                }
            }.also { job ->
                collectionJob = job
            }
    }

    suspend fun stop() {
        collectionJob?.cancelAndJoin()
        collectionJob = null
        closeResources()
    }

    private suspend fun closeResources() {
        closeMutex.withLock {
            if (closed) return
            closed = true
            runCatching { onClosed() }
            runCatching { channel.close() }
        }
    }
}

private data class DesktopProtocolFailureTemplate(
    val code: String,
    val stage: String,
    val category: FailureCategory,
    val summary: String,
    val expected: String,
    val retryable: Boolean,
    val nextAction: String,
)

private fun AppError.toProtocolFailure(sessionId: SessionId): AegisFailure {
    val template =
        when (this) {
            is AppError.Network -> {
                DesktopProtocolFailureTemplate(
                    AegisFailureCodes.WEBRTC_NETWORK_OPERATION_FAILED,
                    "dependency-io",
                    FailureCategory.NETWORK,
                    "A network operation required by the remote request failed.",
                    "The protocol request completes through its selected transport",
                    true,
                    "Check transport health and retry only while the session remains authorized.",
                )
            }

            is AppError.Authentication -> {
                DesktopProtocolFailureTemplate(
                    AegisFailureCodes.IDENTITY_AUTHENTICATION_FAILED,
                    "authentication",
                    FailureCategory.AUTHENTICATION,
                    "The remote request could not be authenticated.",
                    "An authenticated peer bound to the active session",
                    false,
                    "Reject the request and establish a freshly authenticated session.",
                )
            }

            is AppError.Authorization -> {
                DesktopProtocolFailureTemplate(
                    AegisFailureCodes.SESSION_REMOTE_ACTION_NOT_AUTHORIZED,
                    "authorization",
                    FailureCategory.AUTHORIZATION,
                    "The remote protocol action is not authorized.",
                    "An action allowed by the active device and session policy",
                    false,
                    "Keep the action blocked and review the device permission or kill switch.",
                )
            }

            is AppError.HostKeyMismatch -> {
                DesktopProtocolFailureTemplate(
                    AegisFailureCodes.SSH_HOST_KEY_CHANGED,
                    "host-key-verification",
                    FailureCategory.AUTHENTICATION,
                    "The remote SSH host key does not match the pinned identity.",
                    "The previously pinned SSH host key",
                    false,
                    "Keep the connection blocked until the host-key change is explicitly verified.",
                )
            }

            is AppError.CapabilityUnavailable -> {
                DesktopProtocolFailureTemplate(
                    AegisFailureCodes.DEPENDENCY_CAPABILITY_UNAVAILABLE,
                    "capability-check",
                    FailureCategory.UNSUPPORTED_CAPABILITY,
                    "A capability required by the remote request is unavailable.",
                    "The requested desktop capability is available and enabled",
                    false,
                    "Use a supported action or restore the reported capability before retrying.",
                )
            }

            is AppError.Validation -> {
                DesktopProtocolFailureTemplate(
                    AegisFailureCodes.SESSION_INVALID_PROTOCOL_MESSAGE,
                    "validation",
                    FailureCategory.PROTOCOL,
                    "The remote protocol request is invalid.",
                    "A well-formed request valid for the active session state",
                    false,
                    "Discard the request and correct the sender's protocol state.",
                )
            }

            is AppError.Unknown -> {
                DesktopProtocolFailureTemplate(
                    AegisFailureCodes.SESSION_PROTOCOL_PROCESSING_FAILED,
                    "dispatch",
                    FailureCategory.CAUSE_UNCONFIRMED,
                    "The desktop agent could not process the remote request.",
                    "The processor returns a handled, ignored, or response result",
                    false,
                    "Inspect the correlation trace to confirm the cause before retrying.",
                )
            }
        }

    return AegisFailure(
        code = template.code,
        component = "desktop-protocol-bridge",
        operation = "process-protocol-message",
        stage = template.stage,
        category = template.category,
        summary = template.summary,
        technicalCause = message,
        expected = template.expected,
        actual = message,
        retryable = template.retryable,
        correlationId = sessionId.value,
        evidenceRef = null,
        nextAction = template.nextAction,
        underlyingType = this::class.qualifiedName,
    )
}

private fun unexpectedProtocolFailure(
    sessionId: SessionId,
    error: Throwable,
): AegisFailure =
    AegisFailure(
        code = AegisFailureCodes.SESSION_PROTOCOL_PROCESSING_FAILED,
        component = "desktop-protocol-bridge",
        operation = "process-protocol-message",
        stage = "dispatch",
        category = FailureCategory.CAUSE_UNCONFIRMED,
        summary = "The desktop agent could not process this request.",
        technicalCause = "An unexpected exception escaped the protocol processor; the cause is not confirmed.",
        expected = "The processor returns a handled, ignored, or response result",
        actual = "The processor raised an unexpected exception",
        retryable = false,
        correlationId = sessionId.value,
        evidenceRef = null,
        nextAction = "Inspect the correlation trace, diagnose the exception type, and retry only after the cause is understood.",
        underlyingType = error::class.qualifiedName ?: error::class.simpleName ?: "Throwable",
    )
