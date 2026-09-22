package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.session.RemoteSessionRejectedException
import dev.aegis.remote.core.session.RemoteSessionState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DesktopRemoteSessionCoordinatorTest {
    private var now = 1_000L
    private val coordinator =
        DesktopRemoteSessionCoordinator(
            localIdentity = identity("desktop", 4),
            relayUrl = "HTTPS://Relay.Example.test:443/v3",
            clock = { now },
        )

    @Test
    fun `approval e2ee signaling ice and close use one authoritative lifecycle`() =
        runTest {
            val sessionId = SessionId("session-1")
            coordinator.admit(sessionId, identity("android", 7), 10_000L)
            assertEquals(RemoteSessionState.AwaitingApproval, coordinator.state(sessionId))

            coordinator.approved(sessionId)
            coordinator.establishingE2ee(sessionId)
            coordinator.openingSignaling(sessionId)
            coordinator.negotiatingIce(sessionId)
            coordinator.connected(sessionId)
            assertEquals(RemoteSessionState.Connected, coordinator.state(sessionId))

            coordinator.closed(sessionId)
            assertNull(coordinator.state(sessionId))
        }

    @Test
    fun `duplicate relay session is rejected and cannot replace active profile`() =
        runTest {
            val sessionId = SessionId("session-replay")
            coordinator.admit(sessionId, identity("android", 1), 10_000L)

            val error =
                assertFailsWith<RemoteSessionRejectedException> {
                    coordinator.admit(sessionId, identity("attacker", 1), 10_000L)
                }
            assertEquals("SESSION_REPLAY", error.code)
            assertEquals(RemoteSessionState.AwaitingApproval, coordinator.state(sessionId))
        }

    @Test
    fun `expired request fails admission before it can reach approval`() =
        runTest {
            now = 5_000L
            val error =
                assertFailsWith<RemoteSessionRejectedException> {
                    coordinator.admit(SessionId("expired"), identity("android", 1), 4_999L)
                }
            assertEquals("SESSION_EXPIRED", error.code)
        }

    @Test
    fun `rejection closes and tombstones the session`() =
        runTest {
            val sessionId = SessionId("rejected")
            coordinator.admit(sessionId, identity("android", 1), 10_000L)
            coordinator.rejected(sessionId)
            assertNull(coordinator.state(sessionId))

            val error =
                assertFailsWith<RemoteSessionRejectedException> {
                    coordinator.admit(sessionId, identity("android", 1), 10_000L)
                }
            assertEquals("SESSION_REPLAY", error.code)
        }
}

private fun identity(
    id: String,
    generation: Long,
) = DevicePublicIdentity(
    deviceId = DeviceId(id),
    signingPublicKey = ByteArray(32) { (it + generation.toInt()).toByte() },
    keyGeneration = generation,
)
