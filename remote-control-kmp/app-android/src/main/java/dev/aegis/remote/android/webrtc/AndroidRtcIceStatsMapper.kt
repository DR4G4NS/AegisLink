package dev.aegis.remote.android.webrtc

import dev.aegis.remote.core.nat.IceCandidatePairSnapshot
import dev.aegis.remote.core.nat.IceCandidatePairState
import dev.aegis.remote.core.nat.IceCandidateType

internal data class AndroidRtcStatsEntry(
    val id: String,
    val type: String,
    val members: Map<String, Any?>,
)

internal fun mapAndroidRtcStatsToIceCandidatePairs(entries: Collection<AndroidRtcStatsEntry>): List<IceCandidatePairSnapshot> {
    val selectedCandidatePairIds =
        entries
            .filter { it.type == "transport" }
            .mapNotNull { it.members["selectedCandidatePairId"] as? String }
            .toSet()
    val candidates =
        entries
            .filter { it.type == "local-candidate" || it.type == "remote-candidate" }
            .associate { entry ->
                entry.id to
                    AndroidCandidateMetadata(
                        type = entry.members["candidateType"].asIceCandidateType(),
                        protocol = entry.members["protocol"]?.toString()?.lowercase(),
                        relayProtocol = entry.members["relayProtocol"]?.toString()?.lowercase(),
                    )
            }

    return entries
        .filter { it.type == "candidate-pair" }
        .map { entry ->
            val localCandidateId = entry.members["localCandidateId"] as? String
            val remoteCandidateId = entry.members["remoteCandidateId"] as? String
            IceCandidatePairSnapshot(
                state = entry.members["state"].asIceCandidatePairState(),
                nominated = entry.members["nominated"] == true,
                selected = entry.members["selected"] == true || entry.id in selectedCandidatePairIds,
                localCandidateType = candidates[localCandidateId]?.type ?: IceCandidateType.Unknown,
                remoteCandidateType = candidates[remoteCandidateId]?.type ?: IceCandidateType.Unknown,
                currentRoundTripTimeMs = entry.members["currentRoundTripTime"].asSecondsToMillis(),
                availableOutgoingBitrateKbps = entry.members["availableOutgoingBitrate"].asBitsToKilobits(),
                transportProtocol = candidates[localCandidateId]?.protocol ?: candidates[remoteCandidateId]?.protocol,
                turnTransport = candidates[localCandidateId]?.relayProtocol ?: candidates[remoteCandidateId]?.relayProtocol,
            )
        }
}

private data class AndroidCandidateMetadata(
    val type: IceCandidateType,
    val protocol: String?,
    val relayProtocol: String?,
)

private fun Any?.asIceCandidateType(): IceCandidateType =
    when ((this as? String)?.lowercase()) {
        "host" -> IceCandidateType.Host
        "srflx" -> IceCandidateType.ServerReflexive
        "prflx" -> IceCandidateType.PeerReflexive
        "relay" -> IceCandidateType.Relay
        else -> IceCandidateType.Unknown
    }

private fun Any?.asIceCandidatePairState(): IceCandidatePairState =
    when ((this as? String)?.lowercase()) {
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
