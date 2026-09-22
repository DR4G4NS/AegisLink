package dev.aegis.remote.android.webrtc

import dev.aegis.remote.core.nat.IceCandidatePairState
import dev.aegis.remote.core.nat.IceCandidateType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidRtcIceStatsMapperTest {
    @Test
    fun mapsSelectedHostCandidatePairFromTransportStats() {
        val pairs =
            mapAndroidRtcStatsToIceCandidatePairs(
                listOf(
                    AndroidRtcStatsEntry(
                        id = "transport-1",
                        type = "transport",
                        members = mapOf("selectedCandidatePairId" to "pair-1"),
                    ),
                    AndroidRtcStatsEntry(
                        id = "local-1",
                        type = "local-candidate",
                        members = mapOf("candidateType" to "host"),
                    ),
                    AndroidRtcStatsEntry(
                        id = "remote-1",
                        type = "remote-candidate",
                        members = mapOf("candidateType" to "host"),
                    ),
                    AndroidRtcStatsEntry(
                        id = "pair-1",
                        type = "candidate-pair",
                        members =
                            mapOf(
                                "state" to "succeeded",
                                "nominated" to true,
                                "localCandidateId" to "local-1",
                                "remoteCandidateId" to "remote-1",
                                "currentRoundTripTime" to 0.042,
                                "availableOutgoingBitrate" to 2_400_000.0,
                            ),
                    ),
                ),
            )

        assertEquals(1, pairs.size)
        val pair = pairs.single()
        assertTrue(pair.selected)
        assertEquals(IceCandidatePairState.Succeeded, pair.state)
        assertEquals(IceCandidateType.Host, pair.localCandidateType)
        assertEquals(IceCandidateType.Host, pair.remoteCandidateType)
        assertEquals(42L, pair.currentRoundTripTimeMs)
        assertEquals(2_400, pair.availableOutgoingBitrateKbps)
    }

    @Test
    fun mapsDirectSelectedRelayCandidatePair() {
        val pairs =
            mapAndroidRtcStatsToIceCandidatePairs(
                listOf(
                    AndroidRtcStatsEntry(
                        id = "local-1",
                        type = "local-candidate",
                        members = mapOf("candidateType" to "relay", "protocol" to "udp", "relayProtocol" to "tcp"),
                    ),
                    AndroidRtcStatsEntry(
                        id = "remote-1",
                        type = "remote-candidate",
                        members = mapOf("candidateType" to "srflx"),
                    ),
                    AndroidRtcStatsEntry(
                        id = "pair-1",
                        type = "candidate-pair",
                        members =
                            mapOf(
                                "state" to "in-progress",
                                "selected" to true,
                                "localCandidateId" to "local-1",
                                "remoteCandidateId" to "remote-1",
                            ),
                    ),
                ),
            )

        val pair = pairs.single()
        assertTrue(pair.selected)
        assertEquals(IceCandidatePairState.InProgress, pair.state)
        assertEquals(IceCandidateType.Relay, pair.localCandidateType)
        assertEquals(IceCandidateType.ServerReflexive, pair.remoteCandidateType)
        assertEquals("udp", pair.transportProtocol)
        assertEquals("tcp", pair.turnTransport)
    }
}
