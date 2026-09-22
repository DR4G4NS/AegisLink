package dev.aegis.remote.core.session

/**
 * Application DataChannel heartbeat. A missing Pong after 2× interval means the
 * SCTP association is half-open even if ICE still looks connected.
 */
class SessionLivenessTracker(
    private val pingIntervalMillis: Long = DEFAULT_PING_INTERVAL_MILLIS,
    private val timeoutMillis: Long = DEFAULT_PING_TIMEOUT_MILLIS,
    private val clock: () -> Long,
) {
    init {
        require(pingIntervalMillis > 0L)
        require(timeoutMillis >= pingIntervalMillis)
    }

    @Volatile
    private var lastPingSentAtEpochMillis: Long? = null

    @Volatile
    private var lastPongAtEpochMillis: Long? = null

    fun isFresh(nowEpochMillis: Long = clock()): Boolean {
        val pongAt = lastPongAtEpochMillis ?: return false
        return nowEpochMillis - pongAt <= timeoutMillis
    }

    fun shouldSendPing(nowEpochMillis: Long = clock()): Boolean {
        val lastPing = lastPingSentAtEpochMillis
        return lastPing == null || nowEpochMillis - lastPing >= pingIntervalMillis
    }

    fun onPingSent(sentAtEpochMillis: Long = clock()) {
        lastPingSentAtEpochMillis = sentAtEpochMillis
    }

    fun onPong(
        pingSentAtEpochMillis: Long,
        receivedAtEpochMillis: Long = clock(),
    ) {
        lastPongAtEpochMillis = receivedAtEpochMillis
        if (lastPingSentAtEpochMillis == pingSentAtEpochMillis) {
            lastPingSentAtEpochMillis = pingSentAtEpochMillis
        }
    }

    fun pingTimedOut(nowEpochMillis: Long = clock()): Boolean {
        val lastPing = lastPingSentAtEpochMillis ?: return false
        if (lastPongAtEpochMillis != null && lastPongAtEpochMillis!! >= lastPing) return false
        return nowEpochMillis - lastPing >= timeoutMillis
    }

    fun reset() {
        lastPingSentAtEpochMillis = null
        lastPongAtEpochMillis = null
    }

    companion object {
        const val DEFAULT_PING_INTERVAL_MILLIS = 3_000L
        const val DEFAULT_PING_TIMEOUT_MILLIS = 6_000L
    }
}
