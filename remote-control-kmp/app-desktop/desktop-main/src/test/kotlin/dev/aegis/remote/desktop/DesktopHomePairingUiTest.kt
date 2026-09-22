package dev.aegis.remote.desktop

import dev.aegis.remote.desktop.agent.DesktopAgentState
import dev.aegis.remote.desktop.agent.DesktopPairingRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopHomePairingUiTest {
    @Test
    fun pendingPairingIsBadgedAndAppearsBeforeTheQr() {
        val ui =
            desktopHomePairingUi(
                DesktopAgentState(
                    pendingPairingRequests =
                        listOf(
                            DesktopPairingRequest(
                                requestId = "request-1",
                                deviceName = "Test phone",
                                fingerprint = "SHA256:test",
                                requestedAtEpochMillis = 1L,
                                remote = false,
                            ),
                        ),
                ),
            )

        assertEquals(1, ui.pendingBadgeCount)
        assertEquals(DesktopApprovalUiState.Pending, ui.approvalState)
        assertEquals(
            listOf(DesktopHomeContent.PendingApprovals, DesktopHomeContent.PairingQr),
            ui.contentOrder,
        )
    }
}
