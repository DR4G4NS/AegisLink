package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.webrtc.BoundedIceCandidateBuffer
import dev.aegis.remote.core.webrtc.IceCandidateEvictionRank
import dev.aegis.remote.core.webrtc.iceCandidateEvictionRankFromSdp

/** Thread-safe SDP/ICE ordering gates used by native WebRTC callbacks. */
internal class DesktopLocalIceCandidateBuffer<T>(
    rankOf: (T) -> IceCandidateEvictionRank = { IceCandidateEvictionRank.Unknown },
) {
    private val buffer = BoundedIceCandidateBuffer(rankOf = rankOf)

    fun beginDescription() = buffer.begin()

    fun candidate(candidate: T): List<T> = buffer.candidate(candidate)

    fun descriptionSignaled(): List<T> = buffer.release()

    fun clear() = buffer.clear()
}

internal class DesktopRemoteIceCandidateBuffer<T>(
    rankOf: (T) -> IceCandidateEvictionRank = { IceCandidateEvictionRank.Unknown },
) {
    private val buffer = BoundedIceCandidateBuffer(rankOf = rankOf)

    fun beginDescription() = buffer.begin()

    fun candidate(candidate: T): List<T> = buffer.candidate(candidate)

    fun descriptionApplied(): List<T> = buffer.release()

    fun clear() = buffer.clear()
}

internal fun desktopIceCandidateRank(sdp: String): IceCandidateEvictionRank = iceCandidateEvictionRankFromSdp(sdp)
