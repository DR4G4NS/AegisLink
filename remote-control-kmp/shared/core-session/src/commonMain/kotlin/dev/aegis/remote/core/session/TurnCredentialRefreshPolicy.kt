package dev.aegis.remote.core.session

/**
 * HMAC TURN credentials are short-lived. Refresh at TTL/2 and restart ICE with
 * the new servers without tearing the E2EE/relay session.
 */
data class TurnRefreshAction(
    val restartIce: Boolean,
    val rebuildPeerConnection: Boolean,
    val rotateE2ee: Boolean,
    val replaceRelaySession: Boolean,
)

object TurnCredentialRefreshPolicy {
    /** ICE-only refresh: same peer, same E2EE generation, same relay session. */
    fun iceOnlyRefresh(): TurnRefreshAction =
        TurnRefreshAction(
            restartIce = true,
            rebuildPeerConnection = false,
            rotateE2ee = false,
            replaceRelaySession = false,
        )

    fun refreshAtEpochMillis(
        issuedAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): Long {
        require(expiresAtEpochMillis > issuedAtEpochMillis) { "TURN TTL must be positive" }
        val ttl = expiresAtEpochMillis - issuedAtEpochMillis
        return issuedAtEpochMillis + ttl / 2
    }

    fun shouldRefresh(
        nowEpochMillis: Long,
        issuedAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): Boolean = nowEpochMillis >= refreshAtEpochMillis(issuedAtEpochMillis, expiresAtEpochMillis)

    fun delayUntilRefreshMillis(
        nowEpochMillis: Long,
        issuedAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): Long = (refreshAtEpochMillis(issuedAtEpochMillis, expiresAtEpochMillis) - nowEpochMillis).coerceAtLeast(0L)
}
