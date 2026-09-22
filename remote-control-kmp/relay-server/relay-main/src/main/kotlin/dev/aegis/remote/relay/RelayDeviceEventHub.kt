package dev.aegis.remote.relay

import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.relay.RelayDeviceEvent
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

class RelayDeviceEventSubscription internal constructor(
    internal val invalidationIdAtJoin: String?,
    internal val subscriberLeaseId: String,
)

class RelayDeviceEventHub(
    private val json: Json,
    private val runtime: RelayEphemeralRuntime? = null,
    private val messageBus: RelayRuntimeMessageBus =
        (runtime as? RelayRuntimeMessageBus) ?: InMemoryRelayRuntimeMessageBus(),
) {
    private val mutex = Mutex()
    private val participants = ConcurrentHashMap<String, MutableSet<DefaultWebSocketSession>>()

    @Suppress("TooGenericExceptionCaught")
    suspend fun join(
        relayDeviceId: RelayDeviceId,
        socket: DefaultWebSocketSession,
    ): RelayDeviceEventSubscription? {
        val invalidationAtJoin = messageBus.deviceInvalidation(relayDeviceId)?.id
        val subscriberLeaseId = UUID.randomUUID().toString()
        if (
            !messageBus.claimDeviceEventSubscriber(
                relayDeviceId = relayDeviceId,
                leaseId = subscriberLeaseId,
                ttlMillis = SUBSCRIBER_LEASE_TTL_MILLIS,
            )
        ) {
            return null
        }
        return try {
            val accepted =
                mutex.withLock {
                    val local = participants.getOrPut(relayDeviceId.value) { mutableSetOf() }
                    if (local.isNotEmpty()) false else local.add(socket)
                }
            if (accepted) {
                runtime?.setPresence(relayDeviceId, true)
                RelayDeviceEventSubscription(invalidationAtJoin, subscriberLeaseId)
            } else {
                messageBus.releaseDeviceEventSubscriber(relayDeviceId, subscriberLeaseId)
                null
            }
        } catch (error: Throwable) {
            runCatching { messageBus.releaseDeviceEventSubscriber(relayDeviceId, subscriberLeaseId) }
            throw error
        }
    }

    suspend fun leave(
        relayDeviceId: RelayDeviceId,
        subscription: RelayDeviceEventSubscription,
        socket: DefaultWebSocketSession,
    ) {
        val removedLastLocalSubscriber =
            mutex.withLock {
                var removedLast = false
                participants[relayDeviceId.value]?.remove(socket)
                if (participants[relayDeviceId.value]?.isEmpty() == true) {
                    participants.remove(relayDeviceId.value)
                    removedLast = true
                }
                removedLast
            }

        // Presence belongs to the distributed subscriber lease, not merely to
        // this replica's local socket. An expired connection must not erase the
        // presence written by a replacement connection on another replica.
        try {
            if (
                removedLastLocalSubscriber &&
                messageBus.ownsDeviceEventSubscriber(relayDeviceId, subscription.subscriberLeaseId)
            ) {
                runtime?.setPresence(relayDeviceId, false)
            }
        } finally {
            messageBus.releaseDeviceEventSubscriber(relayDeviceId, subscription.subscriberLeaseId)
        }
    }

    @Suppress("NestedBlockDepth")
    suspend fun pump(
        relayDeviceId: RelayDeviceId,
        subscription: RelayDeviceEventSubscription,
        socket: DefaultWebSocketSession,
    ) {
        try {
            var nextLeaseRenewalAtNanos = 0L
            while (currentCoroutineContext().isActive) {
                val nowNanos = System.nanoTime()
                if (nowNanos >= nextLeaseRenewalAtNanos) {
                    val renewed =
                        messageBus.renewDeviceEventSubscriber(
                            relayDeviceId = relayDeviceId,
                            leaseId = subscription.subscriberLeaseId,
                            ttlMillis = SUBSCRIBER_LEASE_TTL_MILLIS,
                        )
                    if (!renewed) {
                        socket.close(
                            CloseReason(
                                CloseReason.Codes.VIOLATED_POLICY,
                                DEVICE_SUBSCRIBER_LEASE_LOST_CLOSE,
                            ),
                        )
                        return
                    }
                    nextLeaseRenewalAtNanos = nowNanos + SUBSCRIBER_LEASE_RENEW_INTERVAL_NANOS
                }
                val invalidation = messageBus.deviceInvalidation(relayDeviceId)
                if (invalidation?.id != subscription.invalidationIdAtJoin) {
                    socket.close(
                        CloseReason(
                            CloseReason.Codes.VIOLATED_POLICY,
                            invalidation?.reason?.take(MAX_INVALIDATION_REASON_CHARS) ?: DEVICE_INVALIDATED_CLOSE,
                        ),
                    )
                    return
                }
                val events = messageBus.takeDeviceEvents(relayDeviceId, MAX_DELIVERY_BATCH)
                if (events.isEmpty()) {
                    delay(MAILBOX_POLL_MILLIS)
                    continue
                }
                events.forEach { encoded ->
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

    suspend fun publish(
        relayDeviceId: RelayDeviceId,
        event: RelayDeviceEvent,
    ): Boolean {
        val encoded = json.encodeToString(RelayDeviceEvent.serializer(), event)
        runtime?.recordEvent(
            scope = "device:${relayDeviceId.value}",
            fields = mapOf("kind" to (event::class.simpleName ?: "event"), "device" to safeRelayId(relayDeviceId.value)),
        )
        return messageBus.enqueueDeviceEvent(
            recipient = relayDeviceId,
            payload = encoded,
            ttlMillis = DEVICE_EVENT_TTL_MILLIS,
            maximumQueuedEvents = MAX_QUEUED_DEVICE_EVENTS,
        )
    }

    suspend fun invalidateDevice(
        relayDeviceId: RelayDeviceId,
        message: String,
    ) {
        messageBus.invalidateDevice(relayDeviceId, message, DEVICE_INVALIDATION_TTL_MILLIS)
        val targets = mutex.withLock { participants.remove(relayDeviceId.value).orEmpty().toList() }
        runtime?.setPresence(relayDeviceId, false)
        targets.forEach { target ->
            runCatching {
                target.close(
                    CloseReason(
                        CloseReason.Codes.VIOLATED_POLICY,
                        message.take(MAX_INVALIDATION_REASON_CHARS),
                    ),
                )
            }
        }
    }

    private companion object {
        const val MAX_QUEUED_DEVICE_EVENTS = 256
        const val MAX_DELIVERY_BATCH = 32
        const val MAILBOX_POLL_MILLIS = 100L
        const val SEND_TIMEOUT_MILLIS = 2_000L
        const val DEVICE_EVENT_TTL_MILLIS = 15 * 60_000L
        const val DEVICE_INVALIDATION_TTL_MILLIS = 2 * 60_000L
        const val SUBSCRIBER_LEASE_TTL_MILLIS = 15_000L
        const val SUBSCRIBER_LEASE_RENEW_INTERVAL_NANOS = 5_000_000_000L
    }
}

internal const val DEVICE_SUBSCRIBER_LEASE_LOST_CLOSE: String = "REL-7415 distributed device-event lease lost"
