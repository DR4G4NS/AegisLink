package dev.aegis.remote.relay

import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RelaySignalingSubscription internal constructor(
    val relayDeviceId: RelayDeviceId,
    internal val invalidationIdAtJoin: String?,
    internal val participantLeaseId: String,
)

class RelaySignalingHub(
    private val json: Json,
    private val runtime: RelayEphemeralRuntime? = null,
    private val messageBus: RelayRuntimeMessageBus =
        (runtime as? RelayRuntimeMessageBus) ?: InMemoryRelayRuntimeMessageBus(),
) {
    private val mutex = Mutex()
    private val participants = ConcurrentHashMap<String, MutableMap<String, DefaultWebSocketSession>>()

    @Suppress("TooGenericExceptionCaught")
    suspend fun join(
        sessionId: SessionId,
        relayDeviceId: RelayDeviceId,
        socket: DefaultWebSocketSession,
    ): RelaySignalingSubscription? {
        val invalidationAtJoin = messageBus.deviceInvalidation(relayDeviceId)?.id
        val participantLeaseId = UUID.randomUUID().toString()
        if (
            !messageBus.claimSessionParticipant(
                sessionId = sessionId,
                relayDeviceId = relayDeviceId,
                leaseId = participantLeaseId,
                ttlMillis = PARTICIPANT_LEASE_TTL_MILLIS,
            )
        ) {
            return null
        }
        return try {
            val accepted =
                mutex.withLock {
                    val peers = participants.getOrPut(sessionId.value) { mutableMapOf() }
                    if (relayDeviceId.value in peers || peers.size >= MAX_PARTICIPANTS_PER_SESSION) {
                        false
                    } else {
                        peers[relayDeviceId.value] = socket
                        true
                    }
                }
            if (accepted) {
                RelaySignalingSubscription(relayDeviceId, invalidationAtJoin, participantLeaseId)
            } else {
                messageBus.releaseSessionParticipant(sessionId, relayDeviceId, participantLeaseId)
                null
            }
        } catch (error: Throwable) {
            runCatching { messageBus.releaseSessionParticipant(sessionId, relayDeviceId, participantLeaseId) }
            throw error
        }
    }

    suspend fun leave(
        sessionId: SessionId,
        subscription: RelaySignalingSubscription,
        socket: DefaultWebSocketSession,
    ) {
        mutex.withLock {
            val peers = participants[sessionId.value]
            if (peers?.get(subscription.relayDeviceId.value) === socket) {
                peers.remove(subscription.relayDeviceId.value)
            }
            if (peers?.isEmpty() == true) participants.remove(sessionId.value)
        }
        messageBus.releaseSessionParticipant(
            sessionId = sessionId,
            relayDeviceId = subscription.relayDeviceId,
            leaseId = subscription.participantLeaseId,
        )
    }

    suspend fun ownsParticipantLease(
        sessionId: SessionId,
        subscription: RelaySignalingSubscription,
    ): Boolean =
        messageBus.ownsSessionParticipant(
            sessionId = sessionId,
            relayDeviceId = subscription.relayDeviceId,
            leaseId = subscription.participantLeaseId,
        )

    /**
     * Delivers opaque records from the recipient mailbox. The mailbox exists
     * before this collector, so an E2EE Init sent by the first participant is
     * retained until the other participant joins.
     */
    @Suppress("NestedBlockDepth")
    suspend fun pump(
        sessionId: SessionId,
        subscription: RelaySignalingSubscription,
        socket: DefaultWebSocketSession,
    ) {
        try {
            var nextLeaseRenewalAtNanos = 0L
            while (currentCoroutineContext().isActive) {
                val nowNanos = System.nanoTime()
                if (nowNanos >= nextLeaseRenewalAtNanos) {
                    val renewed =
                        messageBus.renewSessionParticipant(
                            sessionId = sessionId,
                            relayDeviceId = subscription.relayDeviceId,
                            leaseId = subscription.participantLeaseId,
                            ttlMillis = PARTICIPANT_LEASE_TTL_MILLIS,
                        )
                    if (!renewed) {
                        socket.close(policyClose(PARTICIPANT_LEASE_LOST_CLOSE, PARTICIPANT_LEASE_LOST_CLOSE))
                        return
                    }
                    nextLeaseRenewalAtNanos = nowNanos + PARTICIPANT_LEASE_RENEW_INTERVAL_NANOS
                }
                val sessionInvalidation = messageBus.sessionInvalidation(sessionId)
                if (sessionInvalidation != null) {
                    socket.close(policyClose(sessionInvalidation.reason, SESSION_INVALIDATED_CLOSE))
                    return
                }
                val deviceInvalidation = messageBus.deviceInvalidation(subscription.relayDeviceId)
                if (deviceInvalidation?.id != subscription.invalidationIdAtJoin) {
                    socket.close(policyClose(deviceInvalidation?.reason, DEVICE_INVALIDATED_CLOSE))
                    return
                }

                val frames =
                    messageBus.takeSessionFrames(
                        sessionId = sessionId,
                        recipient = subscription.relayDeviceId,
                        maximumFrames = MAX_DELIVERY_BATCH,
                    )
                if (frames.isEmpty()) {
                    delay(MAILBOX_POLL_MILLIS)
                    continue
                }
                frames.forEach { encoded ->
                    val sent = withTimeoutOrNull(SEND_TIMEOUT_MILLIS) { socket.send(Frame.Text(encoded)) }
                    if (sent == null) {
                        socket.close(
                            CloseReason(
                                CloseReason.Codes.TRY_AGAIN_LATER,
                                "$BACKPRESSURE_CLOSE recipient send timed out",
                            ),
                        )
                        return
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            runCatching {
                socket.close(
                    CloseReason(
                        CloseReason.Codes.INTERNAL_ERROR,
                        "$RUNTIME_UNAVAILABLE_CLOSE relay runtime unavailable",
                    ),
                )
            }
        }
    }

    suspend fun broadcast(
        sessionId: SessionId,
        senderRelayDeviceId: RelayDeviceId,
        recipientRelayDeviceId: RelayDeviceId,
        envelope: RelayWebSocketEnvelope,
    ): Boolean {
        val encoded = json.encodeToString(envelope)
        runtime?.recordEvent(
            scope = "session:${sessionId.value}",
            fields =
                mapOf(
                    "kind" to envelope.payloadKind.name,
                    "session" to safeRelayId(sessionId.value),
                    "sender" to safeRelayId(senderRelayDeviceId.value),
                    "recipient" to safeRelayId(recipientRelayDeviceId.value),
                ),
        )
        return messageBus.enqueueSessionFrame(
            sessionId = sessionId,
            recipient = recipientRelayDeviceId,
            payload = encoded,
            ttlMillis = SESSION_FRAME_TTL_MILLIS,
            maximumQueuedFrames = MAX_QUEUED_SESSION_FRAMES,
        )
    }

    /** Closes local sockets and marks the session invalid across relay replicas. */
    suspend fun closeSession(
        sessionId: SessionId,
        message: String,
    ) {
        messageBus.invalidateSession(sessionId, message, SESSION_INVALIDATION_TTL_MILLIS)
        val targets =
            mutex.withLock {
                participants
                    .remove(sessionId.value)
                    .orEmpty()
                    .values
                    .toList()
            }
        targets.forEach { target ->
            runCatching { target.close(policyClose(message, SESSION_INVALIDATED_CLOSE)) }
        }
    }

    /** Rotates the authorization epoch and closes this replica's old sockets. */
    suspend fun invalidateDevice(
        relayDeviceId: RelayDeviceId,
        message: String,
    ) {
        messageBus.invalidateDevice(relayDeviceId, message, DEVICE_INVALIDATION_TTL_MILLIS)
        val targets =
            mutex.withLock {
                buildList {
                    participants.entries.toList().forEach { (sessionId, peers) ->
                        peers.remove(relayDeviceId.value)?.let(::add)
                        if (peers.isEmpty()) participants.remove(sessionId)
                    }
                }
            }
        targets.forEach { target ->
            runCatching { target.close(policyClose(message, DEVICE_INVALIDATED_CLOSE)) }
        }
    }

    private companion object {
        const val MAX_PARTICIPANTS_PER_SESSION = 2
        const val MAX_QUEUED_SESSION_FRAMES = 128
        const val MAX_DELIVERY_BATCH = 32
        const val SEND_TIMEOUT_MILLIS = 2_000L
        const val MAILBOX_POLL_MILLIS = 100L
        const val SESSION_FRAME_TTL_MILLIS = 30_000L
        const val SESSION_INVALIDATION_TTL_MILLIS = 20 * 60_000L
        const val DEVICE_INVALIDATION_TTL_MILLIS = 2 * 60_000L
        const val PARTICIPANT_LEASE_TTL_MILLIS = 15_000L
        const val PARTICIPANT_LEASE_RENEW_INTERVAL_NANOS = 5_000_000_000L
    }
}

private fun policyClose(
    message: String?,
    fallback: String,
): CloseReason =
    CloseReason(
        CloseReason.Codes.VIOLATED_POLICY,
        message?.takeIf(String::isNotBlank)?.take(MAX_INVALIDATION_REASON_CHARS) ?: fallback,
    )

internal const val SESSION_INVALIDATED_CLOSE: String = "SES-7401 session expired or invalidated"
internal const val DEVICE_INVALIDATED_CLOSE: String = "REL-7401 relay authorization invalidated"
internal const val BACKPRESSURE_CLOSE: String = "REL-7402 mailbox backpressure"
internal const val RUNTIME_UNAVAILABLE_CLOSE: String = "REL-7403 relay runtime unavailable"
internal const val PARTICIPANT_LEASE_LOST_CLOSE: String = "REL-7414 distributed participant lease lost"
