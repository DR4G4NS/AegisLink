package dev.aegis.remote.core.nat

import dev.aegis.remote.core.model.NatTraversalState
import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.TurnConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BasicNatTraversalCheckerTest {
    @Test
    fun returnsDirectWhenStunProbeFindsDirectConnectivity() =
        kotlinx.coroutines.test.runTest {
            val turnProbe = RecordingTurnProbe(ConnectivityCheckResult(NatTraversalState.TurnRequired))
            val checker =
                BasicNatTraversalChecker(
                    stunProbe = RecordingStunProbe(ConnectivityCheckResult(NatTraversalState.DirectPossible, "srflx ok")),
                    turnProbe = turnProbe,
                    clock = { 1_000 },
                )

            val result =
                checker.check(
                    StunTurnConfig(
                        stunUrls = listOf("stun:stun.example.test:3478"),
                        turnConfig = usableTurnConfig(),
                    ),
                )

            assertEquals(NatTraversalState.DirectPossible, result.state)
            assertEquals("srflx ok", result.reason)
            assertFalse(turnProbe.called)
        }

    @Test
    fun fallsBackToTurnWhenStunCannotReachDirectPath() =
        kotlinx.coroutines.test.runTest {
            val turnProbe = RecordingTurnProbe(ConnectivityCheckResult(NatTraversalState.TurnRequired, "relay ok"))
            val checker =
                BasicNatTraversalChecker(
                    stunProbe = RecordingStunProbe(ConnectivityCheckResult(NatTraversalState.TurnRequired, "symmetric nat")),
                    turnProbe = turnProbe,
                    clock = { 1_000 },
                )

            val result =
                checker.check(
                    StunTurnConfig(
                        stunUrls = listOf("stun:stun.example.test:3478"),
                        turnConfig = usableTurnConfig(),
                    ),
                )

            assertEquals(NatTraversalState.TurnRequired, result.state)
            assertEquals("relay ok", result.reason)
            assertEquals(StunTurnConfig(turnConfig = usableTurnConfig()), turnProbe.lastConfig)
        }

    @Test
    fun reportsTurnRequiredWhenDirectFailsAndNoTurnIsConfigured() =
        kotlinx.coroutines.test.runTest {
            val checker =
                BasicNatTraversalChecker(
                    stunProbe = RecordingStunProbe(ConnectivityCheckResult(NatTraversalState.Blocked, "udp blocked")),
                    turnProbe = RecordingTurnProbe(ConnectivityCheckResult(NatTraversalState.TurnRequired)),
                    clock = { 1_000 },
                )

            val result = checker.check(StunTurnConfig(stunUrls = listOf("stun:stun.example.test:3478")))

            assertEquals(NatTraversalState.TurnRequired, result.state)
            assertEquals("udp blocked", result.reason)
        }

    @Test
    fun rejectsExpiredTurnCredentialsBeforeProbingTurn() =
        kotlinx.coroutines.test.runTest {
            val turnProbe = RecordingTurnProbe(ConnectivityCheckResult(NatTraversalState.TurnRequired))
            val checker =
                BasicNatTraversalChecker(
                    stunProbe = RecordingStunProbe(ConnectivityCheckResult(NatTraversalState.TurnRequired)),
                    turnProbe = turnProbe,
                    clock = { 5_000 },
                )

            val result =
                checker.check(
                    StunTurnConfig(
                        stunUrls = listOf("stun:stun.example.test:3478"),
                        turnConfig = usableTurnConfig(expiresAt = 4_999),
                    ),
                )

            assertEquals(NatTraversalState.Blocked, result.state)
            assertEquals("TURN credentials are expired", result.reason)
            assertFalse(turnProbe.called)
        }

    @Test
    fun iceServerFactoryIncludesStunAndUsableTurnOnly() {
        val factory = IceServerConfigFactory(clock = { 2_000 })

        val configs =
            factory.create(
                StunTurnConfig(
                    stunUrls = listOf("stun:stun1.example.test:3478", "stun:stun2.example.test:3478"),
                    turnConfig = usableTurnConfig(expiresAt = 3_000),
                ),
            )

        assertEquals(2, configs.size)
        assertEquals(listOf("stun:stun1.example.test:3478", "stun:stun2.example.test:3478"), configs[0].urls)
        assertEquals(listOf("turn:turn.example.test:3478"), configs[1].urls)
        assertEquals("turn-user", configs[1].username)
        assertEquals("turn-secret-ref", configs[1].credentialRef)

        val expiredTurnConfigs =
            factory.create(
                StunTurnConfig(
                    stunUrls = listOf("stun:stun.example.test:3478"),
                    turnConfig = usableTurnConfig(expiresAt = 1_999),
                ),
            )

        assertEquals(1, expiredTurnConfigs.size)
        assertEquals(listOf("stun:stun.example.test:3478"), expiredTurnConfigs.single().urls)
    }

    private fun usableTurnConfig(expiresAt: Long? = 10_000) =
        TurnConfig(
            urls = listOf("turn:turn.example.test:3478"),
            username = "turn-user",
            credentialRef = "turn-secret-ref",
            expiresAtEpochMillis = expiresAt,
        )
}

private class RecordingStunProbe(
    private val result: ConnectivityCheckResult,
) : StunProbe {
    override suspend fun check(stunUrls: List<String>): ConnectivityCheckResult = result
}

private class RecordingTurnProbe(
    private val result: ConnectivityCheckResult,
) : TurnProbe {
    var called: Boolean = false
        private set
    var lastConfig: StunTurnConfig? = null
        private set

    override suspend fun check(config: StunTurnConfig): ConnectivityCheckResult {
        called = true
        lastConfig = config
        return result
    }
}
