package dev.aegis.remote.android.home

import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.relay.RelaySession
import dev.aegis.remote.core.session.RemoteSessionRejectedException
import dev.aegis.remote.core.session.RemoteSessionState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AndroidRemoteSessionCoordinatorTest {
    private val coordinator =
        AndroidRemoteSessionCoordinator(
            localIdentity = identity("phone"),
            relayUrl = "https://RELAY.example.test:443/api",
            clock = { 1_000L },
        )

    @Test
    fun `source lifecycle binds created target identity through reconnect`() =
        runTest {
            val id = SessionId("session-1")
            coordinator.created(session(id, identity("desktop")), DeviceProfileId("desktop-profile"))
            assertEquals(RemoteSessionState.AwaitingApproval, coordinator.state(id))
            coordinator.approved(id)
            coordinator.establishingE2ee(id)
            coordinator.openingSignaling(id)
            coordinator.negotiatingIce(id)
            coordinator.connected(id)
            coordinator.beginIceRestart(id)
            coordinator.connected(id)
            assertEquals(RemoteSessionState.Connected, coordinator.state(id))
        }

    @Test
    fun `relay cannot create identity-less session`() =
        runTest {
            val error =
                assertFailsWith<RemoteSessionRejectedException> {
                    coordinator.created(session(SessionId("missing"), null), DeviceProfileId("profile"))
                }
            assertEquals("TARGET_IDENTITY_REQUIRED", error.code)
        }

    @Test
    fun `remote rejection terminally removes session`() =
        runTest {
            val id = SessionId("rejected")
            coordinator.created(session(id, identity("desktop")), DeviceProfileId("profile"))
            coordinator.rejected(id)
            assertNull(coordinator.state(id))
        }

    @Test
    fun `explicit close terminally removes a connected session`() =
        runTest {
            val id = SessionId("closed")
            coordinator.created(session(id, identity("desktop")), DeviceProfileId("profile"))
            coordinator.approved(id)
            coordinator.establishingE2ee(id)
            coordinator.openingSignaling(id)
            coordinator.negotiatingIce(id)
            coordinator.connected(id)

            coordinator.closed(id)

            assertNull(coordinator.state(id))
        }
}

private fun session(
    id: SessionId,
    target: DevicePublicIdentity?,
) = RelaySession(
    sessionId = id,
    relayDeviceId = RelayDeviceId("desktop-relay"),
    expiresAtEpochMillis = 10_000L,
    targetIdentity = target,
)

private fun identity(id: String) =
    DevicePublicIdentity(
        deviceId = DeviceId(id),
        signingPublicKey = ByteArray(32) { (it + id.length).toByte() },
        keyGeneration = 1,
    )
