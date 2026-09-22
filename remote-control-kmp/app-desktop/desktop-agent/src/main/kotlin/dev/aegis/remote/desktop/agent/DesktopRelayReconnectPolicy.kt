package dev.aegis.remote.desktop.agent

interface DesktopRelayReconnectPolicy {
    fun shouldRetry(attempt: Int): Boolean

    fun nextDelayMillis(attempt: Int): Long
}

class BoundedDesktopRelayReconnectPolicy(
    private val baseDelayMillis: Long = 1_000,
    private val maxDelayMillis: Long = 15_000,
    private val maxAttempts: Int = 8,
) : DesktopRelayReconnectPolicy {
    override fun shouldRetry(attempt: Int): Boolean = attempt < maxAttempts

    override fun nextDelayMillis(attempt: Int): Long {
        val multiplier = 1L shl attempt.coerceIn(0, 16)
        return (baseDelayMillis * multiplier).coerceAtMost(maxDelayMillis)
    }
}
