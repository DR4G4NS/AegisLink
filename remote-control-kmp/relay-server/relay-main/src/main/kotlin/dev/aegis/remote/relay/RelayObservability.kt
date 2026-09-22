package dev.aegis.remote.relay

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RelayMetrics {
    private val rateLimited = AtomicLong()
    private val websocketAccepted = AtomicLong()
    private val websocketRejected = AtomicLong()
    private val envelopesForwarded = AtomicLong()
    private val sessionsCreated = AtomicLong()
    private val approvalsDecided = AtomicLong()

    fun rateLimited() = rateLimited.incrementAndGet()

    fun websocketAccepted() = websocketAccepted.incrementAndGet()

    fun websocketRejected() = websocketRejected.incrementAndGet()

    fun envelopeForwarded() = envelopesForwarded.incrementAndGet()

    fun sessionCreated() = sessionsCreated.incrementAndGet()

    fun approvalDecided() = approvalsDecided.incrementAndGet()

    fun prometheus(activeConnections: Int): String =
        buildString {
            appendLine("# TYPE aegis_relay_rate_limited_total counter")
            appendLine("aegis_relay_rate_limited_total ${rateLimited.get()}")
            appendLine("# TYPE aegis_relay_websocket_accepted_total counter")
            appendLine("aegis_relay_websocket_accepted_total ${websocketAccepted.get()}")
            appendLine("# TYPE aegis_relay_websocket_rejected_total counter")
            appendLine("aegis_relay_websocket_rejected_total ${websocketRejected.get()}")
            appendLine("# TYPE aegis_relay_envelopes_forwarded_total counter")
            appendLine("aegis_relay_envelopes_forwarded_total ${envelopesForwarded.get()}")
            appendLine("# TYPE aegis_relay_sessions_created_total counter")
            appendLine("aegis_relay_sessions_created_total ${sessionsCreated.get()}")
            appendLine("# TYPE aegis_relay_approvals_decided_total counter")
            appendLine("aegis_relay_approvals_decided_total ${approvalsDecided.get()}")
            appendLine("# TYPE aegis_relay_websocket_connections gauge")
            appendLine("aegis_relay_websocket_connections $activeConnections")
        }
}

class RelayConnectionLimiter(
    private val maximum: Int,
) {
    private val active = AtomicInteger()

    init {
        require(maximum > 0) { "Maximum WebSocket connections must be positive" }
    }

    fun tryOpen(): Boolean {
        while (true) {
            val current = active.get()
            if (current >= maximum) return false
            if (active.compareAndSet(current, current + 1)) return true
        }
    }

    fun close() {
        active.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    fun active(): Int = active.get()
}

internal fun safeRelayId(value: String): String =
    when {
        value.length <= 12 -> value
        else -> value.take(8) + "..." + value.takeLast(4)
    }
