package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.nat.IceCandidatePairState
import dev.aegis.remote.core.nat.IceCandidateType
import dev.onvoid.webrtc.RTCStatsType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopRtcIceStatsMapperTest {
    @Test
    fun mapsSelectedServerReflexiveCandidatePairFromTransportStats() {
        val pairs =
            mapDesktopRtcStatsToIceCandidatePairs(
                listOf(
                    DesktopRtcStatsEntry(
                        id = "transport-1",
                        type = RTCStatsType.TRANSPORT,
                        attributes = mapOf("selectedCandidatePairId" to "pair-1"),
                    ),
                    DesktopRtcStatsEntry(
                        id = "local-1",
                        type = RTCStatsType.LOCAL_CANDIDATE,
                        attributes = mapOf("candidateType" to "srflx"),
                    ),
                    DesktopRtcStatsEntry(
                        id = "remote-1",
                        type = RTCStatsType.REMOTE_CANDIDATE,
                        attributes = mapOf("candidateType" to "prflx"),
                    ),
                    DesktopRtcStatsEntry(
                        id = "pair-1",
                        type = RTCStatsType.CANDIDATE_PAIR,
                        attributes =
                            mapOf(
                                "state" to "succeeded",
                                "nominated" to true,
                                "localCandidateId" to "local-1",
                                "remoteCandidateId" to "remote-1",
                                "currentRoundTripTime" to 0.12,
                                "availableOutgoingBitrate" to 5_000_000.0,
                            ),
                    ),
                ),
            )

        val pair = pairs.single()
        assertTrue(pair.selected)
        assertEquals(IceCandidatePairState.Succeeded, pair.state)
        assertEquals(IceCandidateType.ServerReflexive, pair.localCandidateType)
        assertEquals(IceCandidateType.PeerReflexive, pair.remoteCandidateType)
        assertEquals(120L, pair.currentRoundTripTimeMs)
        assertEquals(5_000, pair.availableOutgoingBitrateKbps)
    }

    @Test
    fun mapsDirectSelectedRelayCandidatePair() {
        val pairs =
            mapDesktopRtcStatsToIceCandidatePairs(
                listOf(
                    DesktopRtcStatsEntry(
                        id = "local-1",
                        type = RTCStatsType.LOCAL_CANDIDATE,
                        attributes = mapOf("candidateType" to "relay", "protocol" to "udp", "relayProtocol" to "tls"),
                    ),
                    DesktopRtcStatsEntry(
                        id = "remote-1",
                        type = RTCStatsType.REMOTE_CANDIDATE,
                        attributes = mapOf("candidateType" to "host"),
                    ),
                    DesktopRtcStatsEntry(
                        id = "pair-1",
                        type = RTCStatsType.CANDIDATE_PAIR,
                        attributes =
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
        assertEquals(IceCandidateType.Host, pair.remoteCandidateType)
        assertEquals("udp", pair.transportProtocol)
        assertEquals("tls", pair.turnTransport)
    }
}
