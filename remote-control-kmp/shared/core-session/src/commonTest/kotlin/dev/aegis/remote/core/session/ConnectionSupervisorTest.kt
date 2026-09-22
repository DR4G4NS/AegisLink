package dev.aegis.remote.core.session

import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.SessionProtocolCapabilities
import dev.aegis.remote.core.model.SessionProtocolFeatures
import dev.aegis.remote.core.model.localSessionProtocolCapabilities
import dev.aegis.remote.core.webrtc.VideoSessionState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectionSupervisorTest {
    private val supervisor = ConnectionSupervisor()

    @Test
    fun iceRestartRecoveredByPingDoesNotRebuild() =
        runTest {
            val decision =
                supervisor.decide(
                    input(
                        videoState = VideoSessionState.Reconnecting,
                        iceRestartInFlight = true,
                        pingAlive = true,
                        iceRestartGraceElapsed = true,
                    ),
                )

            assertEquals(ConnectionSupervisorDecision.Hold, decision)
        }

    @Test
    fun failedDuringIceRestartWithPingAliveDoesNotRebuild() =
        runTest {
            val decision =
                supervisor.decide(
                    input(
                        videoState = VideoSessionState.Failed("native fail"),
                        iceRestartInFlight = true,
                        pingAlive = true,
                    ),
                )

            assertEquals(ConnectionSupervisorDecision.Hold, decision)
            assertFalse(decision is ConnectionSupervisorDecision.RebuildSession)
        }

    @Test
    fun iceRestartGraceWithoutPingProbesBeforeRebuild() =
        runTest {
            val decision =
                supervisor.decide(
                    input(
                        videoState = VideoSessionState.Reconnecting,
                        iceRestartInFlight = true,
                        pingAlive = false,
                        iceRestartGraceElapsed = true,
                    ),
                )

            assertIs<ConnectionSupervisorDecision.ProbeSession>(decision)
        }

    @Test
    fun backgroundThreeSecondsProbesOnly() =
        runTest {
            val decision =
                supervisor.decide(
                    input(
                        videoState = VideoSessionState.Streaming,
                        pingAlive = false,
                        backgroundDurationMillis = 3_000,
                    ),
                )

            assertIs<ConnectionSupervisorDecision.ProbeSession>(decision)
        }

    @Test
    fun backgroundFifteenSecondsReconnectsWhenPingIsStale() =
        runTest {
            val decision =
                supervisor.decide(
                    input(
                        videoState = VideoSessionState.Streaming,
                        pingAlive = false,
                        backgroundDurationMillis = 15_000,
                    ),
                )

            val rebuild = assertIs<ConnectionSupervisorDecision.RebuildSession>(decision)
            assertTrue(rebuild.preferSameRoute)
        }

    @Test
    fun backgroundFifteenSecondsProbesWhenPingIsFresh() =
        runTest {
            val decision =
                supervisor.decide(
                    input(
                        videoState = VideoSessionState.Streaming,
                        pingAlive = true,
                        backgroundDurationMillis = 15_000,
                    ),
                )

            assertIs<ConnectionSupervisorDecision.ProbeSession>(decision)
        }

    @Test
    fun offlineDoesNotConsumeAttempts() =
        runTest {
            val decision =
                supervisor.decide(
                    input(
                        videoState = VideoSessionState.Failed("offline"),
                        networkOnline = false,
                        attempt = 40,
                    ),
                )

            assertIs<ConnectionSupervisorDecision.WaitOffline>(decision)
        }

    @Test
    fun protocolSkewStopsRetries() =
        runTest {
            val decision =
                supervisor.decide(
                    input(
                        videoState = VideoSessionState.Failed("skew"),
                        failureKind = ConnectionFailureKind.ProtocolSkew,
                    ),
                )

            val closed = assertIs<ConnectionSupervisorDecision.FailClosed>(decision)
            assertEquals(AegisFailureCodes.SESSION_PROTOCOL_SKEW, closed.code)
        }

    private fun input(
        videoState: VideoSessionState,
        iceRestartInFlight: Boolean = false,
        pingAlive: Boolean = false,
        networkOnline: Boolean = true,
        iceRestartGraceElapsed: Boolean = false,
        backgroundDurationMillis: Long? = null,
        failureKind: ConnectionFailureKind = ConnectionFailureKind.Transient,
        attempt: Int = 0,
    ) = ConnectionSupervisorInput(
        videoState = videoState,
        iceRestartInFlight = iceRestartInFlight,
        pingAlive = pingAlive,
        networkOnline = networkOnline,
        iceRestartGraceElapsed = iceRestartGraceElapsed,
        backgroundDurationMillis = backgroundDurationMillis,
        failureKind = failureKind,
        attempt = attempt,
        nowEpochMillis = 10_000,
        currentRouteType = ConnectionRouteType.Lan,
    )
}

class SessionLivenessTrackerTest {
    @Test
    fun timesOutWhenPongIsMissing() {
        var now = 1_000L
        val tracker = SessionLivenessTracker(clock = { now })
        tracker.onPingSent(now)
        now += SessionLivenessTracker.DEFAULT_PING_TIMEOUT_MILLIS
        assertTrue(tracker.pingTimedOut())
        assertFalse(tracker.isFresh())
    }

    @Test
    fun staysFreshAfterPong() {
        var now = 1_000L
        val tracker = SessionLivenessTracker(clock = { now })
        tracker.onPingSent(now)
        tracker.onPong(now, now + 40)
        now += 1_000
        assertTrue(tracker.isFresh())
        assertFalse(tracker.pingTimedOut())
    }
}

class ProtocolCapabilityHandshakeTest {
    @Test
    fun compatiblePeersNegotiateIntersection() {
        val result = ProtocolCapabilityHandshake().evaluate(localSessionProtocolCapabilities())
        val compatible = assertIs<ProtocolCapabilityHandshakeResult.Compatible>(result)
        assertTrue(compatible.negotiated.features.contains("ping"))
    }

    @Test
    fun revisionMismatchIsProtocolSkew() {
        val result =
            ProtocolCapabilityHandshake().evaluate(
                SessionProtocolCapabilities(protocolRev = 99, features = SessionProtocolFeatures.ALL.toList()),
            )
        val skew = assertIs<ProtocolCapabilityHandshakeResult.Skew>(result)
        assertEquals(AegisFailureCodes.SESSION_PROTOCOL_SKEW, skew.code)
    }
}

class TurnCredentialRefreshPolicyTest {
    @Test
    fun refreshesAtHalfTtlThenLeavesE2eeAlone() {
        val issued = 1_000L
        val expires = 1_000L + 600_000L
        assertEquals(issued + 300_000L, TurnCredentialRefreshPolicy.refreshAtEpochMillis(issued, expires))
        assertFalse(TurnCredentialRefreshPolicy.shouldRefresh(issued + 299_999, issued, expires))
        assertTrue(TurnCredentialRefreshPolicy.shouldRefresh(issued + 300_000, issued, expires))
        val action = TurnCredentialRefreshPolicy.iceOnlyRefresh()
        assertTrue(action.restartIce)
        assertFalse(action.rebuildPeerConnection)
        assertFalse(action.rotateE2ee)
        assertFalse(action.replaceRelaySession)
    }
}

class ApplicationWakeupTest {
    @Test
    fun shortBackgroundProbes() {
        assertEquals(ApplicationWakeup.Probe, classifyApplicationWakeup(3_000, pingFresh = false))
    }

    @Test
    fun longBackgroundReconnectsOnlyWhenPingIsStale() {
        assertEquals(ApplicationWakeup.Reconnect, classifyApplicationWakeup(15_000, pingFresh = false))
        assertEquals(ApplicationWakeup.Probe, classifyApplicationWakeup(15_000, pingFresh = true))
    }
}
