package dev.aegis.remote.relay

import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import kotlinx.serialization.Serializable
import java.util.ArrayDeque
import java.util.UUID

/**
 * Opaque, bounded relay mailboxes shared by device-event and signaling hubs.
 *
 * Implementations must treat [payload] as an indivisible string. In particular,
 * the production implementation must never decode E2EE envelopes or persist
 * their plaintext contents.
 */
@Suppress("TooManyFunctions")
interface RelayRuntimeMessageBus {
    suspend fun enqueueSessionFrame(
        sessionId: SessionId,
        recipient: RelayDeviceId,
        payload: String,
        ttlMillis: Long,
        maximumQueuedFrames: Int,
    ): Boolean

    suspend fun takeSessionFrames(
        sessionId: SessionId,
        recipient: RelayDeviceId,
        maximumFrames: Int,
    ): List<String>

    suspend fun enqueueDeviceEvent(
        recipient: RelayDeviceId,
        payload: String,
        ttlMillis: Long,
        maximumQueuedEvents: Int,
    ): Boolean

    suspend fun takeDeviceEvents(
        recipient: RelayDeviceId,
        maximumEvents: Int,
    ): List<String>

    suspend fun invalidateSession(
        sessionId: SessionId,
        reason: String,
        ttlMillis: Long,
    )

    suspend fun sessionInvalidation(sessionId: SessionId): RelayRuntimeInvalidation?

    suspend fun invalidateDevice(
        relayDeviceId: RelayDeviceId,
        reason: String,
        ttlMillis: Long,
    ): RelayRuntimeInvalidation

    suspend fun deviceInvalidation(relayDeviceId: RelayDeviceId): RelayRuntimeInvalidation?

    /**
     * Claims one participant slot across every relay replica. The opaque lease
     * ID is connection-scoped and must be compared atomically on renew/release.
     */
    suspend fun claimSessionParticipant(
        sessionId: SessionId,
        relayDeviceId: RelayDeviceId,
        leaseId: String,
        ttlMillis: Long,
    ): Boolean

    suspend fun renewSessionParticipant(
        sessionId: SessionId,
        relayDeviceId: RelayDeviceId,
        leaseId: String,
        ttlMillis: Long,
    ): Boolean

    suspend fun ownsSessionParticipant(
        sessionId: SessionId,
        relayDeviceId: RelayDeviceId,
        leaseId: String,
    ): Boolean

    suspend fun releaseSessionParticipant(
        sessionId: SessionId,
        relayDeviceId: RelayDeviceId,
        leaseId: String,
    )

    suspend fun claimDeviceEventSubscriber(
        relayDeviceId: RelayDeviceId,
        leaseId: String,
        ttlMillis: Long,
    ): Boolean

    suspend fun renewDeviceEventSubscriber(
        relayDeviceId: RelayDeviceId,
        leaseId: String,
        ttlMillis: Long,
    ): Boolean

    suspend fun ownsDeviceEventSubscriber(
        relayDeviceId: RelayDeviceId,
        leaseId: String,
    ): Boolean

    suspend fun releaseDeviceEventSubscriber(
        relayDeviceId: RelayDeviceId,
        leaseId: String,
    )
}

@Serializable
data class RelayRuntimeInvalidation(
    val id: String,
    val reason: String,
)

@Serializable
internal data class RelayQueuedPayload(
    val payload: String,
    val expiresAtEpochMillis: Long,
)

/** Explicit single-process fallback for development and Ktor host tests. */
class InMemoryRelayRuntimeMessageBus(
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RelayRuntimeMessageBus {
    private val lock = Any()
    private val sessionQueues = mutableMapOf<String, ArrayDeque<RelayQueuedPayload>>()
    private val deviceQueues = mutableMapOf<String, ArrayDeque<RelayQueuedPayload>>()
    private val sessionInvalidations = mutableMapOf<String, ExpiringInvalidation>()
    private val deviceInvalidations = mutableMapOf<String, ExpiringInvalidation>()
    private val participantLeases = mutableMapOf<String, ExpiringParticipantLease>()

    override suspend fun enqueueSessionFrame(
        sessionId: SessionId,
        recipient: RelayDeviceId,
        payload: String,
        ttlMillis: Long,
        maximumQueuedFrames: Int,
    ): Boolean =
        enqueue(
            queues = sessionQueues,
            key = sessionQueueKey(sessionId, recipient),
            payload = payload,
            ttlMillis = ttlMillis,
            maximum = maximumQueuedFrames,
        )

    override suspend fun takeSessionFrames(
        sessionId: SessionId,
        recipient: RelayDeviceId,
        maximumFrames: Int,
    ): List<String> = take(sessionQueues, sessionQueueKey(sessionId, recipient), maximumFrames)

    override suspend fun enqueueDeviceEvent(
        recipient: RelayDeviceId,
        payload: String,
        ttlMillis: Long,
        maximumQueuedEvents: Int,
    ): Boolean = enqueue(deviceQueues, recipient.value, payload, ttlMillis, maximumQueuedEvents)

    override suspend fun takeDeviceEvents(
        recipient: RelayDeviceId,
        maximumEvents: Int,
    ): List<String> = take(deviceQueues, recipient.value, maximumEvents)

    override suspend fun invalidateSession(
        sessionId: SessionId,
        reason: String,
        ttlMillis: Long,
    ) {
        synchronized(lock) {
            sessionInvalidations[sessionId.value] = newInvalidation(reason, ttlMillis)
        }
    }

    override suspend fun sessionInvalidation(sessionId: SessionId): RelayRuntimeInvalidation? {
        val sessionKey = sessionId.value
        return synchronized(lock) { currentInvalidation(sessionInvalidations, sessionKey) }
    }

    override suspend fun invalidateDevice(
        relayDeviceId: RelayDeviceId,
        reason: String,
        ttlMillis: Long,
    ): RelayRuntimeInvalidation =
        synchronized(lock) {
            newInvalidation(reason, ttlMillis).also { deviceInvalidations[relayDeviceId.value] = it }.value
        }

    override suspend fun deviceInvalidation(relayDeviceId: RelayDeviceId): RelayRuntimeInvalidation? {
        val deviceKey = relayDeviceId.value
        return synchronized(lock) { currentInvalidation(deviceInvalidations, deviceKey) }
    }

    override suspend fun claimSessionParticipant(
        sessionId: SessionId,
        relayDeviceId: RelayDeviceId,
        leaseId: String,
        ttlMillis: Long,
    ): Boolean = claimLease(participantLeaseKey(sessionId, relayDeviceId), leaseId, ttlMillis)

    override suspend fun renewSessionParticipant(
        sessionId: SessionId,
        relayDeviceId: RelayDeviceId,
        leaseId: String,
        ttlMillis: Long,
    ): Boolean = renewLease(participantLeaseKey(sessionId, relayDeviceId), leaseId, ttlMillis)

    override suspend fun ownsSessionParticipant(
        sessionId: SessionId,
        relayDeviceId: RelayDeviceId,
        leaseId: String,
    ): Boolean = ownsLease(participantLeaseKey(sessionId, relayDeviceId), leaseId)

    override suspend fun releaseSessionParticipant(
        sessionId: SessionId,
        relayDeviceId: RelayDeviceId,
        leaseId: String,
    ) {
        releaseLease(participantLeaseKey(sessionId, relayDeviceId), leaseId)
    }

    override suspend fun claimDeviceEventSubscriber(
        relayDeviceId: RelayDeviceId,
        leaseId: String,
        ttlMillis: Long,
    ): Boolean = claimLease(deviceSubscriberLeaseKey(relayDeviceId), leaseId, ttlMillis)

    override suspend fun renewDeviceEventSubscriber(
        relayDeviceId: RelayDeviceId,
        leaseId: String,
        ttlMillis: Long,
    ): Boolean = renewLease(deviceSubscriberLeaseKey(relayDeviceId), leaseId, ttlMillis)

    override suspend fun ownsDeviceEventSubscriber(
        relayDeviceId: RelayDeviceId,
        leaseId: String,
    ): Boolean = ownsLease(deviceSubscriberLeaseKey(relayDeviceId), leaseId)

    override suspend fun releaseDeviceEventSubscriber(
        relayDeviceId: RelayDeviceId,
        leaseId: String,
    ) {
        releaseLease(deviceSubscriberLeaseKey(relayDeviceId), leaseId)
    }

    private fun claimLease(
        key: String,
        leaseId: String,
        ttlMillis: Long,
    ): Boolean {
        requireLeaseArguments(leaseId, ttlMillis)
        return synchronized(lock) {
            val now = clock()
            participantLeases[key]?.takeIf { it.expiresAtEpochMillis > now }?.let { existing ->
                return@synchronized existing.leaseId == leaseId
            }
            participantLeases[key] = ExpiringParticipantLease(leaseId, safeExpiry(now, ttlMillis))
            true
        }
    }

    private fun renewLease(
        key: String,
        leaseId: String,
        ttlMillis: Long,
    ): Boolean {
        requireLeaseArguments(leaseId, ttlMillis)
        return synchronized(lock) {
            val now = clock()
            val current = participantLeases[key]
            when {
                current == null -> {
                    false
                }

                current.expiresAtEpochMillis <= now -> {
                    participantLeases.remove(key)
                    false
                }

                current.leaseId != leaseId -> {
                    false
                }

                else -> {
                    participantLeases[key] = current.copy(expiresAtEpochMillis = safeExpiry(now, ttlMillis))
                    true
                }
            }
        }
    }

    private fun ownsLease(
        key: String,
        leaseId: String,
    ): Boolean {
        require(leaseId.isNotBlank()) { "Relay participant lease ID is required" }
        return synchronized(lock) {
            val current = participantLeases[key] ?: return@synchronized false
            if (current.expiresAtEpochMillis <= clock()) {
                participantLeases.remove(key)
                false
            } else {
                current.leaseId == leaseId
            }
        }
    }

    private fun releaseLease(
        key: String,
        leaseId: String,
    ) {
        require(leaseId.isNotBlank()) { "Relay participant lease ID is required" }
        synchronized(lock) {
            if (participantLeases[key]?.leaseId == leaseId) participantLeases.remove(key)
        }
    }

    private fun enqueue(
        queues: MutableMap<String, ArrayDeque<RelayQueuedPayload>>,
        key: String,
        payload: String,
        ttlMillis: Long,
        maximum: Int,
    ): Boolean {
        require(payload.isNotBlank())
        require(ttlMillis > 0)
        require(maximum > 0)
        return synchronized(lock) {
            val now = clock()
            val queue = queues.getOrPut(key) { ArrayDeque() }
            queue.removeIf { it.expiresAtEpochMillis <= now }
            if (queue.size >= maximum) return@synchronized false
            queue.addLast(RelayQueuedPayload(payload, safeExpiry(now, ttlMillis)))
            true
        }
    }

    private fun take(
        queues: MutableMap<String, ArrayDeque<RelayQueuedPayload>>,
        key: String,
        maximum: Int,
    ): List<String> {
        require(maximum > 0)
        return synchronized(lock) {
            val queue = queues[key] ?: return@synchronized emptyList()
            val now = clock()
            queue.removeIf { it.expiresAtEpochMillis <= now }
            val values =
                buildList {
                    repeat(minOf(maximum, queue.size)) {
                        add(queue.removeFirst().payload)
                    }
                }
            if (queue.isEmpty()) queues.remove(key)
            values
        }
    }

    private fun newInvalidation(
        reason: String,
        ttlMillis: Long,
    ): ExpiringInvalidation {
        require(reason.isNotBlank())
        require(ttlMillis > 0)
        return ExpiringInvalidation(
            value = RelayRuntimeInvalidation(UUID.randomUUID().toString(), reason.take(MAX_INVALIDATION_REASON_CHARS)),
            expiresAtEpochMillis = safeExpiry(clock(), ttlMillis),
        )
    }

    private fun currentInvalidation(
        values: MutableMap<String, ExpiringInvalidation>,
        key: String,
    ): RelayRuntimeInvalidation? {
        val value = values[key] ?: return null
        if (value.expiresAtEpochMillis <= clock()) {
            values.remove(key)
            return null
        }
        return value.value
    }

    private data class ExpiringInvalidation(
        val value: RelayRuntimeInvalidation,
        val expiresAtEpochMillis: Long,
    )

    private data class ExpiringParticipantLease(
        val leaseId: String,
        val expiresAtEpochMillis: Long,
    )
}

private fun sessionQueueKey(
    sessionId: SessionId,
    recipient: RelayDeviceId,
): String = "${sessionId.value}:${recipient.value}"

private fun participantLeaseKey(
    sessionId: SessionId,
    relayDeviceId: RelayDeviceId,
): String = "session:${sessionId.value}:${relayDeviceId.value}"

private fun deviceSubscriberLeaseKey(relayDeviceId: RelayDeviceId): String = "device:${relayDeviceId.value}"

private fun requireLeaseArguments(
    leaseId: String,
    ttlMillis: Long,
) {
    require(leaseId.isNotBlank()) { "Relay participant lease ID is required" }
    require(ttlMillis > 0L) { "Relay participant lease TTL must be positive" }
}

internal fun safeExpiry(
    nowEpochMillis: Long,
    ttlMillis: Long,
): Long =
    try {
        Math.addExact(nowEpochMillis, ttlMillis)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

internal const val MAX_INVALIDATION_REASON_CHARS: Int = 96
