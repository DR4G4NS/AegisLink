package dev.aegis.remote.core.pairing

import dev.aegis.remote.core.model.DeviceAuthorization
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.RemoteDeviceId
import kotlin.test.Test
import kotlin.test.assertEquals

class PairingStateMachineTest {
    private val machine = PairingStateMachine()

    @Test
    fun localPairingRequiresVisibleApprovalBeforeApproved() {
        val request =
            PairingRequest(
                requestId = "req-1",
                deviceName = "Android",
                fingerprint = "SHA256:abc",
                requestedAtEpochMillis = 10,
                remote = false,
            )
        val authorization =
            DeviceAuthorization(
                remoteDeviceId = RemoteDeviceId("phone-1"),
                displayName = "Android",
                approvedAtEpochMillis = 20,
                permissions = DevicePermissions(terminal = true),
            )

        val showingCode = machine.reduce(PairingState.Idle, PairingEvent.StartLocal)
        val awaiting = machine.reduce(showingCode, PairingEvent.RequestReceived(request))
        val approved = machine.reduce(awaiting, PairingEvent.Approve(authorization))

        assertEquals(PairingState.ShowingCode, showingCode)
        assertEquals(PairingState.AwaitingApproval(request), awaiting)
        assertEquals(PairingState.Approved(authorization), approved)
    }
}
