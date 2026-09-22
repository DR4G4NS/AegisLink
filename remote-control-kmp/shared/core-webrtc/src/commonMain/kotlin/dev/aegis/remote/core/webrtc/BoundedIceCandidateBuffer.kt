package dev.aegis.remote.core.webrtc

/**
 * Rank used when a stuck SDP gate would otherwise grow without bound.
 * Host/srflx are dropped before relay so a TURN path is not starved.
 */
enum class IceCandidateEvictionRank {
    Host,
    ServerReflexive,
    PeerReflexive,
    Relay,
    Unknown,
}

fun iceCandidateEvictionRankFromSdp(sdp: String): IceCandidateEvictionRank {
    val match = TYPE_PATTERN.find(sdp) ?: return IceCandidateEvictionRank.Unknown
    return when (match.groupValues[1].lowercase()) {
        "host" -> IceCandidateEvictionRank.Host
        "srflx" -> IceCandidateEvictionRank.ServerReflexive
        "prflx" -> IceCandidateEvictionRank.PeerReflexive
        "relay" -> IceCandidateEvictionRank.Relay
        else -> IceCandidateEvictionRank.Unknown
    }
}

private val TYPE_PATTERN = Regex("""(?:^|[\s])typ\s+(host|srflx|prflx|relay)(?:[\s]|$)""", RegexOption.IGNORE_CASE)

class IceCandidateBufferOverflowException(
    message: String,
) : IllegalStateException(message)

/**
 * Thread-safe ICE candidate gate with a hard pending cap. Callers must perform
 * native WebRTC work outside the lock using the returned lists.
 */
class BoundedIceCandidateBuffer<T>(
    private val maxPending: Int = DEFAULT_MAX_PENDING,
    private val rankOf: (T) -> IceCandidateEvictionRank,
) {
    init {
        require(maxPending > 0)
    }

    private val pending = ArrayDeque<T>()
    private var gateOpen = false

    @Synchronized
    fun begin() {
        gateOpen = false
    }

    @Synchronized
    fun candidate(candidate: T): List<T> {
        if (gateOpen) return listOf(candidate)
        if (pending.size < maxPending) {
            pending.addLast(candidate)
            return emptyList()
        }
        val dropIndex = pending.indexOfFirst { rankOf(it) != IceCandidateEvictionRank.Relay }
        if (dropIndex >= 0) {
            pending.removeAt(dropIndex)
            pending.addLast(candidate)
            return emptyList()
        }
        throw IceCandidateBufferOverflowException(
            "WEBRTC_ICE_BUFFER_OVERFLOW: pending ICE candidates exceeded $maxPending relay candidates",
        )
    }

    @Synchronized
    fun release(): List<T> {
        gateOpen = true
        return buildList {
            while (pending.isNotEmpty()) add(pending.removeFirst())
        }
    }

    @Synchronized
    fun pendingCount(): Int = pending.size

    @Synchronized
    fun clear() {
        gateOpen = false
        pending.clear()
    }

    companion object {
        const val DEFAULT_MAX_PENDING = 64
    }
}
