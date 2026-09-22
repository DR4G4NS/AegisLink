package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.DeviceAuthorization
import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.DeviceTrustTransport
import dev.aegis.remote.core.model.RemoteDeviceId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopTrustCoordinatorTest {
    @Test
    fun `permission edits persist before old sessions close and disabling screen disables input`() =
        runTest {
            val authorization = testAuthorization().copy(permissions = DevicePermissions(visual = true, input = true, clipboard = true))
            val trustStore = InMemoryDeviceTrustStore().also { it.saveAuthorization(authorization) }
            val state = MutableStateFlow(DesktopAgentState(authorizedDevices = listOf(authorization)))
            var closed = false
            val coordinator =
                DesktopTrustCoordinator(
                    DesktopTrustDependencies(
                        state,
                        this,
                        trustStore,
                        null,
                        closeDeviceSessionResources = {
                            assertEquals(
                                false,
                                trustStore
                                    .listAuthorizedDevices()
                                    .single()
                                    .permissions.input,
                            )
                            closed = true
                        },
                        restart = {},
                        clock = { 1_000L },
                        updateState = { state.value = it(state.value) },
                    ),
                )
            coordinator.updatePermissions(authorization.remoteDeviceId.value, authorization.permissions.copy(visual = false))
            advanceUntilIdle()
            assertTrue(closed)
            assertEquals(
                DevicePermissions(clipboard = true),
                state.value.authorizedDevices
                    .single()
                    .permissions,
            )
            assertTrue(state.value.permissionChangesInProgress.isEmpty())
        }

    @Test
    fun `unsupported file access changes report failure and do not claim permissions were saved`() =
        runTest {
            val authorization = testAuthorization().copy(permissions = DevicePermissions(terminal = true, sftp = true))
            val trustStore = InMemoryDeviceTrustStore().also { it.saveAuthorization(authorization) }
            val state = MutableStateFlow(DesktopAgentState(authorizedDevices = listOf(authorization)))
            val coordinator =
                DesktopTrustCoordinator(
                    DesktopTrustDependencies(
                        state,
                        this,
                        trustStore,
                        null,
                        closeDeviceSessionResources = {},
                        restart = {},
                        clock = { 1_000L },
                        updateState = { state.value = it(state.value) },
                    ),
                )
            coordinator.updatePermissions(authorization.remoteDeviceId.value, DevicePermissions())
            advanceUntilIdle()
            assertEquals(authorization.permissions, trustStore.listAuthorizedDevices().single().permissions)
            assertTrue(state.value.permissionChangeErrors.containsKey(authorization.remoteDeviceId.value))
        }

    @Test
    fun `revocation blocks local control before incomplete ssh cleanup`() =
        runTest {
            val authorization = testAuthorization()
            val trustStore = InMemoryDeviceTrustStore()
            trustStore.saveAuthorization(authorization)
            val state = MutableStateFlow(DesktopAgentState(authorizedDevices = listOf(authorization)))
            var closedSessionResources = 0
            val coordinator =
                DesktopTrustCoordinator(
                    DesktopTrustDependencies(
                        state = state,
                        scope = this,
                        trustStore = trustStore,
                        openSshProvisioner = null,
                        closeDeviceSessionResources = { closedSessionResources += 1 },
                        restart = {},
                        clock = { 1_000L },
                        updateState = { update -> state.value = update(state.value) },
                    ),
                )

            coordinator.revoke(authorization.remoteDeviceId.value)
            advanceUntilIdle()

            val revoked = trustStore.listAuthorizedDevices().single()
            assertNotNull(revoked.revokedAtEpochMillis)
            assertTrue(revoked.sshKeyRemovalPending)
            assertEquals("SSH-7326", revoked.sshKeyRemovalFailureCode)
            assertEquals(1, closedSessionResources)
            assertTrue(state.value.logs.any { it.eventCode == AgentLogEventCode.DeviceRevoked })
        }
}

private fun testAuthorization() =
    DeviceAuthorization(
        remoteDeviceId = RemoteDeviceId("local-phone"),
        displayName = "Android",
        approvedAtEpochMillis = 100L,
        permissions = DevicePermissions(visual = true),
        publicIdentity =
            DevicePublicIdentity(
                deviceId = DeviceId("phone-identity"),
                signingPublicKey = ByteArray(32) { it.toByte() },
                keyGeneration = 1L,
            ),
        approvedTransport = DeviceTrustTransport.LocalPairing,
    )
