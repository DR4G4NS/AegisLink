package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.nat.IceCandidatePairSnapshot
import dev.aegis.remote.core.nat.IceCandidatePairState
import dev.aegis.remote.core.nat.IceCandidateType
import dev.onvoid.webrtc.RTCStatsType

internal data class DesktopRtcStatsEntry(
    val id: String,
    val type: RTCStatsType,
    val attributes: Map<String, Any?>,
)

internal fun mapDesktopRtcStatsToIceCandidatePairs(entries: Collection<DesktopRtcStatsEntry>): List<IceCandidatePairSnapshot> {
    val selectedCandidatePairIds =
        entries
            .filter { it.type == RTCStatsType.TRANSPORT }
            .mapNotNull { it.attributes["selectedCandidatePairId"] as? String }
            .toSet()
    val candidates =
        entries
            .filter { it.type == RTCStatsType.LOCAL_CANDIDATE || it.type == RTCStatsType.REMOTE_CANDIDATE }
            .associate { entry ->
                entry.id to
                    CandidateMetadata(
                        type = entry.attributes["candidateType"].asIceCandidateType(),
                        protocol = entry.attributes["protocol"]?.toString()?.lowercase(),
                        relayProtocol = entry.attributes["relayProtocol"]?.toString()?.lowercase(),
                    )
            }

    return entries
        .filter { it.type == RTCStatsType.CANDIDATE_PAIR }
        .map { entry ->
            val localCandidateId = entry.attributes["localCandidateId"] as? String
            val remoteCandidateId = entry.attributes["remoteCandidateId"] as? String
            IceCandidatePairSnapshot(
                state = entry.attributes["state"].asIceCandidatePairState(),
                nominated = entry.attributes["nominated"] == true,
                selected = entry.attributes["selected"] == true || entry.id in selectedCandidatePairIds,
                localCandidateType = candidates[localCandidateId]?.type ?: IceCandidateType.Unknown,
                remoteCandidateType = candidates[remoteCandidateId]?.type ?: IceCandidateType.Unknown,
                currentRoundTripTimeMs = entry.attributes["currentRoundTripTime"].asSecondsToMillis(),
                availableOutgoingBitrateKbps = entry.attributes["availableOutgoingBitrate"].asBitsToKilobits(),
                transportProtocol = candidates[localCandidateId]?.protocol ?: candidates[remoteCandidateId]?.protocol,
                turnTransport = candidates[localCandidateId]?.relayProtocol ?: candidates[remoteCandidateId]?.relayProtocol,
            )
        }
}

private data class CandidateMetadata(
    val type: IceCandidateType,
    val protocol: String?,
    val relayProtocol: String?,
)

private fun Any?.asIceCandidateType(): IceCandidateType =
    when (this?.toString()?.lowercase()) {
        "host" -> IceCandidateType.Host
        "srflx" -> IceCandidateType.ServerReflexive
        "prflx" -> IceCandidateType.PeerReflexive
        "relay" -> IceCandidateType.Relay
        else -> IceCandidateType.Unknown
    }

private fun Any?.asIceCandidatePairState(): IceCandidatePairState =
    when (this?.toString()?.lowercase()) {
        "frozen" -> IceCandidatePairState.Frozen
        "waiting" -> IceCandidatePairState.Waiting
        "in-progress", "inprogress" -> IceCandidatePairState.InProgress
        "succeeded" -> IceCandidatePairState.Succeeded
        "failed" -> IceCandidatePairState.Failed
        else -> IceCandidatePairState.Waiting
    }

private fun Any?.asSecondsToMillis(): Long? =
    (this as? Number)
        ?.toDouble()
        ?.times(1_000)
        ?.toLong()

private fun Any?.asBitsToKilobits(): Int? =
    (this as? Number)
        ?.toDouble()
        ?.div(1_000)
        ?.toInt()
