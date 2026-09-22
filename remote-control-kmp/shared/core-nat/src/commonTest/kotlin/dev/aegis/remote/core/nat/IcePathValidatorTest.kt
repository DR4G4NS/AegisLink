package dev.aegis.remote.core.nat

import dev.aegis.remote.core.model.NatTraversalState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class IcePathValidatorTest {
    private val validator = IcePathValidator(maxDirectRttMs = 100, maxRelayRttMs = 300)

    @Test
    fun reportsCheckingWhenCandidatePairsAreStillInProgress() {
        val result =
            validator.validate(
                listOf(
                    pair(
                        state = IceCandidatePairState.InProgress,
                        selected = false,
                        nominated = false,
                    ),
                ),
            )

        assertEquals(NatTraversalState.Checking, result.state)
        assertNull(result.route)
    }

    @Test
    fun reportsBlockedWhenNoSelectedCandidatePairExists() {
        val result =
            validator.validate(
                listOf(
                    pair(
                        state = IceCandidatePairState.Failed,
                        selected = false,
                        nominated = false,
                    ),
                ),
            )

        assertEquals(NatTraversalState.Blocked, result.state)
        assertNull(result.route)
    }

    @Test
    fun validatesLanDirectHostCandidatePair() {
        val result =
            validator.validate(
                listOf(
                    pair(
                        local = IceCandidateType.Host,
                        remote = IceCandidateType.Host,
                        rtt = 12,
                        bitrate = 8_000,
                    ),
                ),
            )

        assertEquals(NatTraversalState.DirectPossible, result.state)
        assertEquals(ValidatedIceRoute.LanDirect, result.route)
        assertEquals(12, result.rttMs)
        assertEquals(8_000, result.availableOutgoingBitrateKbps)
    }

    @Test
    fun validatesInternetDirectServerReflexiveCandidatePair() {
        val result =
            validator.validate(
                listOf(
                    pair(
                        local = IceCandidateType.ServerReflexive,
                        remote = IceCandidateType.PeerReflexive,
                        rtt = 72,
                    ),
                ),
            )

        assertEquals(NatTraversalState.DirectPossible, result.state)
        assertEquals(ValidatedIceRoute.InternetDirect, result.route)
    }

    @Test
    fun validatesTurnRelayCandidatePairAsTurnRequired() {
        val result =
            validator.validate(
                listOf(
                    pair(
                        local = IceCandidateType.Relay,
                        remote = IceCandidateType.ServerReflexive,
                        rtt = 210,
                    ),
                ),
            )

        assertEquals(NatTraversalState.TurnRequired, result.state)
        assertEquals(ValidatedIceRoute.TurnRelay, result.route)
    }

    @Test
    fun rejectsSelectedPathWhenRttExceedsRouteLimit() {
        val result =
            validator.validate(
                listOf(
                    pair(
                        local = IceCandidateType.ServerReflexive,
                        remote = IceCandidateType.ServerReflexive,
                        rtt = 101,
                    ),
                ),
            )

        assertIs<NatTraversalState.Failed>(result.state)
        assertEquals(ValidatedIceRoute.InternetDirect, result.route)
    }

    @Test
    fun prefersSelectedPairOverOnlyNominatedPair() {
        val result =
            validator.validate(
                listOf(
                    pair(
                        selected = false,
                        nominated = true,
                        local = IceCandidateType.Host,
                        remote = IceCandidateType.Host,
                    ),
                    pair(
                        selected = true,
                        nominated = false,
                        local = IceCandidateType.Relay,
                        remote = IceCandidateType.Relay,
                    ),
                ),
            )

        assertEquals(NatTraversalState.TurnRequired, result.state)
        assertEquals(ValidatedIceRoute.TurnRelay, result.route)
    }

    private fun pair(
        state: IceCandidatePairState = IceCandidatePairState.Succeeded,
        selected: Boolean = true,
        nominated: Boolean = true,
        local: IceCandidateType = IceCandidateType.Host,
        remote: IceCandidateType = IceCandidateType.Host,
        rtt: Long? = null,
        bitrate: Int? = null,
    ) = IceCandidatePairSnapshot(
        state = state,
        nominated = nominated,
        selected = selected,
        localCandidateType = local,
        remoteCandidateType = remote,
        currentRoundTripTimeMs = rtt,
        availableOutgoingBitrateKbps = bitrate,
    )
}
