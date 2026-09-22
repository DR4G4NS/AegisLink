package dev.aegis.remote.core.network

/**
 * Collapses capability/address flaps into one ICE restart. Going offline does
 * not, by itself, consume reconnect attempts — callers must check [online].
 */
class NetworkChangeDebouncer(
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) {
    init {
        require(debounceMillis >= 0L)
    }

    private var lastEmittedFingerprint: String? = null
    private var lastEmittedAtEpochMillis: Long = Long.MIN_VALUE / 2

    fun shouldEmit(
        fingerprint: String,
        nowEpochMillis: Long,
        online: Boolean,
    ): Boolean {
        if (!online) {
            lastEmittedFingerprint = fingerprint
            return false
        }
        if (lastEmittedFingerprint == null) {
            lastEmittedFingerprint = fingerprint
            lastEmittedAtEpochMillis = nowEpochMillis
            return false
        }
        if (fingerprint == lastEmittedFingerprint) return false
        if (nowEpochMillis - lastEmittedAtEpochMillis < debounceMillis) return false
        lastEmittedFingerprint = fingerprint
        lastEmittedAtEpochMillis = nowEpochMillis
        return true
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 400L
    }
}

fun networkChangeFingerprint(
    kind: NetworkKind,
    validated: Boolean,
    vpnActive: Boolean,
    localAddresses: Collection<String>,
): String {
    val addresses = localAddresses.sorted().joinToString(",")
    return "$kind|validated=$validated|vpn=$vpnActive|$addresses"
}
