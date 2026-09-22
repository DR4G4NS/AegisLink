package dev.aegis.remote.protocol

import dev.aegis.remote.core.relay.RelayOpaqueChannel
import dev.aegis.remote.core.relay.RelayOpaqueFrame
import dev.aegis.remote.core.relay.RelayPayloadKind
import dev.aegis.remote.core.security.E2eeSession
import dev.aegis.remote.core.security.EncryptedEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

internal const val AEGIS_REKEY_REQUEST_MESSAGE_TYPE = "aegis.e2ee.rekey.request.v1"
internal const val AEGIS_REKEY_ACK_MESSAGE_TYPE = "aegis.e2ee.rekey.ack.v1"
private const val REKEY_CONTROL_PROTOCOL_VERSION = 1
private const val INCOMING_PROTOCOL_BUFFER_CAPACITY = 64

enum class E2eeChannelRole {
    SOURCE,
    TARGET,
}

data class E2eeRekeyPolicy(
    val maxAgeMillis: Long = 15 * 60 * 1_000L,
    val maxMessagesPerDirection: ULong = 1_000_000uL,
    val timeoutMillis: Long = 15_000L,
) {
    init {
        require(maxAgeMillis > 0) { "INVALID_REKEY_MAX_AGE" }
        require(maxMessagesPerDirection > 0uL) { "INVALID_REKEY_MESSAGE_LIMIT" }
        require(timeoutMillis > 0) { "INVALID_REKEY_TIMEOUT" }
    }
}

@Serializable
internal data class EncryptedRekeyRequest(
    val protocolVersion: Int = REKEY_CONTROL_PROTOCOL_VERSION,
    val requestId: String,
    val currentGeneration: Long,
    val nextGeneration: Long,
    val requestedAtEpochMillis: Long,
)

@Serializable
internal data class EncryptedRekeyAcknowledgement(
    val protocolVersion: Int = REKEY_CONTROL_PROTOCOL_VERSION,
    val requestId: String,
    val previousGeneration: Long,
    val appliedGeneration: Long,
    val acknowledgedAtEpochMillis: Long,
)

/**
 * The only protocol-message adapter allowed on a remote relay route. The relay
 * transport receives envelope metadata and ciphertext, never ProtocolMessage JSON.
 *
 * Rekey control records are themselves encrypted envelopes. The source role is
 * the only initiator, application sends pause while the request/ack exchange is
 * in flight, and every protocol or timeout failure closes both crypto and relay
 * state. This avoids simultaneous updates and prevents either side from
 * continuing with ambiguous traffic-key generations.
 */
class E2eeProtocolMessageChannel(
    private val relay: RelayOpaqueChannel,
    private val session: E2eeSession,
    private val codec: ProtocolMessageJsonCodec = ProtocolMessageJsonCodec(),
    private val json: Json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        },
    private val role: E2eeChannelRole =
        if (session.isSource) E2eeChannelRole.SOURCE else E2eeChannelRole.TARGET,
    private val rekeyPolicy: E2eeRekeyPolicy = E2eeRekeyPolicy(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : ProtocolMessageChannel {
    private val closed = AtomicBoolean(false)
    private val automaticRekeyScheduled = AtomicBoolean(false)
    private val stateMutex = Mutex()
    private val sendMutex = Mutex()
    private val decodedMessages = Channel<ProtocolMessage>(INCOMING_PROTOCOL_BUFFER_CAPACITY)
    private val scope =
        CoroutineScope(
            coroutineContext.minusKey(Job) + SupervisorJob(coroutineContext[Job]),
        )
    private var rekeyState: RekeyState = RekeyState.Idle

    override val incoming: Flow<ProtocolMessage> = decodedMessages.receiveAsFlow()

    init {
        require((role == E2eeChannelRole.SOURCE) == session.isSource) { "E2EE_CHANNEL_ROLE_MISMATCH" }
        scope.launch { collectRelayFrames() }
    }

    override suspend fun send(message: ProtocolMessage) {
        while (true) {
            awaitRekeyAlreadyInProgress()
            var enteredCriticalSection = false
            try {
                val retry =
                    sendMutex.withLock {
                        enteredCriticalSection = true
                        val currentState = stateMutex.withLock { rekeyState }
                        check(currentState !is RekeyState.Closed) { "SESSION_CLOSED" }
                        if (currentState !is RekeyState.Idle) {
                            return@withLock true
                        }
                        if (role == E2eeChannelRole.SOURCE && shouldRekey()) {
                            performSourceRekeyWhileSendPaused()
                        }
                        sendEncryptedProtocolMessage(message)
                        false
                    }
                if (!retry) return
            } catch (error: Throwable) {
                if (!enteredCriticalSection && error is CancellationException) throw error
                terminate(error)
                throw error
            }
        }
    }

    override suspend fun close() {
        terminate(cause = null)
    }

    private suspend fun collectRelayFrames() {
        var terminalCause: Throwable? = null
        try {
            relay.incoming.collect(::handleRelayFrame)
            terminalCause = IllegalStateException("RELAY_E2EE_CHANNEL_ENDED")
        } catch (error: Throwable) {
            terminalCause = error
        } finally {
            if (!closed.get()) terminate(terminalCause ?: IllegalStateException("RELAY_E2EE_CHANNEL_ENDED"))
        }
    }

    private suspend fun handleRelayFrame(frame: RelayOpaqueFrame) {
        require(frame.kind == RelayPayloadKind.E2EE_ENVELOPE) { "UNEXPECTED_RELAY_FRAME_KIND" }
        val envelope = json.decodeFromString<EncryptedEnvelope>(frame.payloadJson)
        val plaintext = session.decrypt(envelope)
        try {
            when (envelope.messageType) {
                AEGIS_REKEY_REQUEST_MESSAGE_TYPE -> {
                    handleRekeyRequest(json.decodeFromString<EncryptedRekeyRequest>(plaintext.decodeToString()))
                }

                AEGIS_REKEY_ACK_MESSAGE_TYPE -> {
                    handleRekeyAcknowledgement(
                        json.decodeFromString<EncryptedRekeyAcknowledgement>(plaintext.decodeToString()),
                    )
                }

                else -> {
                    val decoded = codec.decode(plaintext.decodeToString())
                    check(decodedMessages.trySend(decoded).isSuccess) { "PROTOCOL_INCOMING_BUFFER_OVERFLOW" }
                    scheduleAutomaticRekeyIfNeeded()
                }
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private suspend fun handleRekeyRequest(request: EncryptedRekeyRequest) {
        require(role == E2eeChannelRole.TARGET) { "SOURCE_MUST_NOT_RECEIVE_REKEY_REQUEST" }
        require(request.protocolVersion == REKEY_CONTROL_PROTOCOL_VERSION) { "UNSUPPORTED_REKEY_PROTOCOL_VERSION" }
        require(request.requestId.isNotBlank()) { "MISSING_REKEY_REQUEST_ID" }
        require(request.requestedAtEpochMillis > 0) { "INVALID_REKEY_REQUEST_TIMESTAMP" }
        val currentGeneration = session.generation()
        check(currentGeneration < Long.MAX_VALUE) { "REKEY_GENERATION_EXHAUSTED" }
        require(request.currentGeneration == currentGeneration) { "REKEY_CURRENT_GENERATION_MISMATCH" }
        require(request.nextGeneration == currentGeneration + 1) { "INVALID_REKEY_NEXT_GENERATION" }
        val completion = CompletableDeferred<Unit>()
        stateMutex.withLock {
            require(rekeyState is RekeyState.Idle) { "REKEY_ALREADY_IN_PROGRESS" }
            rekeyState = RekeyState.TargetApplying(request.requestId, request.nextGeneration, completion)
        }
        try {
            withTimeout(rekeyPolicy.timeoutMillis) {
                sendMutex.withLock {
                    val pending =
                        stateMutex.withLock {
                            rekeyState as? RekeyState.TargetApplying
                                ?: error("TARGET_REKEY_STATE_LOST")
                        }
                    require(pending.requestId == request.requestId) { "REKEY_REQUEST_ID_MISMATCH" }
                    require(pending.nextGeneration == request.nextGeneration) { "REKEY_GENERATION_STATE_MISMATCH" }
                    val acknowledgement =
                        EncryptedRekeyAcknowledgement(
                            requestId = request.requestId,
                            previousGeneration = currentGeneration,
                            appliedGeneration = request.nextGeneration,
                            acknowledgedAtEpochMillis = nowEpochMillis(),
                        )
                    sendEncryptedControl(
                        messageType = AEGIS_REKEY_ACK_MESSAGE_TYPE,
                        plaintext = json.encodeToString(acknowledgement).encodeToByteArray(),
                    )
                    session.rekey(request.nextGeneration, nowEpochMillis())
                    stateMutex.withLock {
                        require(rekeyState === pending) { "TARGET_REKEY_STATE_CHANGED" }
                        rekeyState = RekeyState.Idle
                    }
                    completion.complete(Unit)
                }
            }
        } catch (error: Throwable) {
            completion.completeExceptionally(error)
            throw error
        }
    }

    private suspend fun handleRekeyAcknowledgement(acknowledgement: EncryptedRekeyAcknowledgement) {
        require(role == E2eeChannelRole.SOURCE) { "TARGET_MUST_NOT_RECEIVE_REKEY_ACK" }
        require(acknowledgement.protocolVersion == REKEY_CONTROL_PROTOCOL_VERSION) {
            "UNSUPPORTED_REKEY_PROTOCOL_VERSION"
        }
        require(acknowledgement.requestId.isNotBlank()) { "MISSING_REKEY_REQUEST_ID" }
        require(acknowledgement.acknowledgedAtEpochMillis > 0) { "INVALID_REKEY_ACK_TIMESTAMP" }
        val pending =
            stateMutex.withLock {
                val current = rekeyState as? RekeyState.SourceAwaitingAck ?: error("UNSOLICITED_REKEY_ACK")
                require(acknowledgement.requestId == current.requestId) { "REKEY_REQUEST_ID_MISMATCH" }
                require(acknowledgement.previousGeneration == session.generation()) {
                    "REKEY_PREVIOUS_GENERATION_MISMATCH"
                }
                require(acknowledgement.appliedGeneration == current.nextGeneration) {
                    "REKEY_ACK_GENERATION_MISMATCH"
                }
                require(acknowledgement.appliedGeneration == session.generation() + 1) {
                    "INVALID_REKEY_NEXT_GENERATION"
                }
                session.rekey(acknowledgement.appliedGeneration, nowEpochMillis())
                rekeyState = RekeyState.Idle
                current
            }
        pending.completion.complete(Unit)
    }

    private suspend fun performSourceRekeyWhileSendPaused() {
        check(role == E2eeChannelRole.SOURCE) { "ONLY_SOURCE_CAN_INITIATE_REKEY" }
        withContext(NonCancellable) {
            withTimeout(rekeyPolicy.timeoutMillis) {
                val currentGeneration = session.generation()
                check(currentGeneration < Long.MAX_VALUE) { "REKEY_GENERATION_EXHAUSTED" }
                val request =
                    EncryptedRekeyRequest(
                        requestId = UUID.randomUUID().toString(),
                        currentGeneration = currentGeneration,
                        nextGeneration = currentGeneration + 1,
                        requestedAtEpochMillis = nowEpochMillis(),
                    )
                val completion = CompletableDeferred<Unit>()
                val pending = RekeyState.SourceAwaitingAck(request.requestId, request.nextGeneration, completion)
                stateMutex.withLock {
                    require(rekeyState is RekeyState.Idle) { "REKEY_ALREADY_IN_PROGRESS" }
                    rekeyState = pending
                }
                try {
                    sendEncryptedControl(
                        messageType = AEGIS_REKEY_REQUEST_MESSAGE_TYPE,
                        plaintext = json.encodeToString(request).encodeToByteArray(),
                    )
                    completion.await()
                    check(session.generation() == request.nextGeneration) { "REKEY_DID_NOT_ADVANCE" }
                } catch (error: Throwable) {
                    completion.completeExceptionally(error)
                    throw error
                }
            }
        }
    }

    private suspend fun sendEncryptedProtocolMessage(message: ProtocolMessage) {
        val plaintext = codec.encode(message).encodeToByteArray()
        val envelope =
            try {
                session.encrypt(message::class.simpleName ?: "ProtocolMessage", plaintext)
            } finally {
                plaintext.fill(0)
            }
        sendEnvelope(envelope)
    }

    private suspend fun sendEncryptedControl(
        messageType: String,
        plaintext: ByteArray,
    ) {
        val envelope =
            try {
                session.encrypt(messageType, plaintext)
            } finally {
                plaintext.fill(0)
            }
        sendEnvelope(envelope)
    }

    private suspend fun sendEnvelope(envelope: EncryptedEnvelope) {
        relay.send(
            RelayOpaqueFrame(
                kind = RelayPayloadKind.E2EE_ENVELOPE,
                payloadJson = json.encodeToString(envelope),
            ),
        )
    }

    private fun shouldRekey(): Boolean =
        session.shouldRekey(
            nowEpochMillis = nowEpochMillis(),
            maxAgeMillis = rekeyPolicy.maxAgeMillis,
            maxMessagesPerDirection = rekeyPolicy.maxMessagesPerDirection,
        )

    private suspend fun awaitRekeyAlreadyInProgress() {
        val pending =
            stateMutex.withLock {
                check(rekeyState !is RekeyState.Closed) { "SESSION_CLOSED" }
                rekeyState.completionOrNull()
            } ?: return
        try {
            withTimeout(rekeyPolicy.timeoutMillis) { pending.await() }
        } catch (error: TimeoutCancellationException) {
            terminate(error)
            throw error
        }
    }

    private fun scheduleAutomaticRekeyIfNeeded() {
        if (role != E2eeChannelRole.SOURCE || !shouldRekey()) return
        if (!automaticRekeyScheduled.compareAndSet(false, true)) return
        scope.launch {
            var enteredCriticalSection = false
            try {
                sendMutex.withLock {
                    enteredCriticalSection = true
                    if (stateMutex.withLock { rekeyState is RekeyState.Idle } && shouldRekey()) {
                        performSourceRekeyWhileSendPaused()
                    }
                }
            } catch (error: Throwable) {
                if (enteredCriticalSection || error !is CancellationException) terminate(error)
            } finally {
                automaticRekeyScheduled.set(false)
            }
        }
    }

    private suspend fun terminate(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        val terminal = e2eeChannelTerminalFailure(cause)
        val pending =
            stateMutex.withLock {
                val completion = rekeyState.completionOrNull()
                rekeyState = RekeyState.Closed
                completion
            }
        pending?.completeExceptionally(terminal)
        session.close()
        decodedMessages.close(terminal)
        withContext(NonCancellable) {
            runCatching {
                withTimeout(rekeyPolicy.timeoutMillis) { relay.close() }
            }
        }
        scope.cancel("E2EE protocol channel terminated", terminal)
    }

    private sealed interface RekeyState {
        data object Idle : RekeyState

        data class SourceAwaitingAck(
            val requestId: String,
            val nextGeneration: Long,
            val completion: CompletableDeferred<Unit>,
        ) : RekeyState

        data class TargetApplying(
            val requestId: String,
            val nextGeneration: Long,
            val completion: CompletableDeferred<Unit>,
        ) : RekeyState

        data object Closed : RekeyState
    }

    private fun RekeyState.completionOrNull(): CompletableDeferred<Unit>? =
        when (this) {
            is RekeyState.SourceAwaitingAck -> completion

            is RekeyState.TargetApplying -> completion

            RekeyState.Idle,
            RekeyState.Closed,
            -> null
        }
}

/**
 * Terminal errors cross the channel boundary as an allowlisted causal code only.
 * In particular, decoder and transport exceptions can embed an untrusted envelope,
 * so retaining their message would violate the E2EE plaintext/payload logging boundary.
 */
private fun e2eeChannelTerminalFailure(cause: Throwable?): Throwable {
    if (cause == null || cause is CancellationException) return CancellationException("E2EE_CHANNEL_CLOSED")
    val code = cause.message?.takeIf { it in E2EE_CHANNEL_FAILURE_CODES } ?: "E2EE_PROTOCOL_FAILURE"
    return when (cause) {
        is SecurityException -> SecurityException(code)
        is IllegalArgumentException -> IllegalArgumentException(code)
        else -> IllegalStateException(code)
    }
}

private val E2EE_CHANNEL_FAILURE_CODES =
    setOf(
        "AEAD_AUTHENTICATION_FAILED",
        "CRYPTO_SUITE_DOWNGRADE",
        "INVALID_MESSAGE_TIMESTAMP",
        "INVALID_REKEY_NEXT_GENERATION",
        "INVALID_REKEY_REQUEST_TIMESTAMP",
        "MISSING_MESSAGE_ID",
        "MISSING_REKEY_REQUEST_ID",
        "PROTOCOL_INCOMING_BUFFER_OVERFLOW",
        "PROTOCOL_VERSION_DOWNGRADE",
        "REKEY_ACK_GENERATION_MISMATCH",
        "REKEY_ALREADY_IN_PROGRESS",
        "REKEY_CURRENT_GENERATION_MISMATCH",
        "REKEY_DID_NOT_ADVANCE",
        "REKEY_GENERATION_EXHAUSTED",
        "REKEY_GENERATION_STATE_MISMATCH",
        "REKEY_PREVIOUS_GENERATION_MISMATCH",
        "REKEY_REQUEST_ID_MISMATCH",
        "RELAY_E2EE_CHANNEL_ENDED",
        "REPLAY_REORDER_OR_GAP",
        "SOURCE_MUST_NOT_RECEIVE_REKEY_REQUEST",
        "TARGET_MUST_NOT_RECEIVE_REKEY_ACK",
        "UNEXPECTED_RELAY_FRAME_KIND",
        "UNKNOWN_CAPABILITY_VERSION",
        "UNSOLICITED_REKEY_ACK",
        "UNSUPPORTED_REKEY_PROTOCOL_VERSION",
        "WRONG_GENERATION",
        "WRONG_SENDER",
        "WRONG_SESSION",
    )
