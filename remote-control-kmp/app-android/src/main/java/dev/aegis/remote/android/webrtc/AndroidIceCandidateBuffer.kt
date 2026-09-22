package dev.aegis.remote.android.webrtc

import dev.aegis.remote.core.webrtc.BoundedIceCandidateBuffer
import dev.aegis.remote.core.webrtc.IceCandidateEvictionRank
import dev.aegis.remote.core.webrtc.iceCandidateEvictionRankFromSdp

/**
 * Keeps trickled ICE candidates behind the SDP that gives their ufrag meaning.
 * WebRTC callbacks may run on a native thread, so every state transition is
 * synchronized and returns work for the caller to perform outside the lock.
 */
internal class AndroidLocalIceCandidateBuffer<T>(
    rankOf: (T) -> IceCandidateEvictionRank = { IceCandidateEvictionRank.Unknown },
) {
    private val buffer = BoundedIceCandidateBuffer(rankOf = rankOf)

    fun beginDescription() = buffer.begin()

    fun candidate(candidate: T): List<T> = buffer.candidate(candidate)

    fun descriptionSignaled(): List<T> = buffer.release()

    fun clear() = buffer.clear()
}

internal class AndroidRemoteIceCandidateBuffer<T>(
    rankOf: (T) -> IceCandidateEvictionRank = { IceCandidateEvictionRank.Unknown },
) {
    private val buffer = BoundedIceCandidateBuffer(rankOf = rankOf)

    fun beginDescription() = buffer.begin()

    fun candidate(candidate: T): List<T> = buffer.candidate(candidate)

    fun descriptionApplied(): List<T> = buffer.release()

    fun clear() = buffer.clear()
}

internal fun androidIceCandidateRank(sdp: String): IceCandidateEvictionRank = iceCandidateEvictionRankFromSdp(sdp)
