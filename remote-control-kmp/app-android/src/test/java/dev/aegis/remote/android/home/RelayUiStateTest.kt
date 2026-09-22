package dev.aegis.remote.android.home

import dev.aegis.remote.core.model.RelayDeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelayUiStateTest {
    @Test
    fun approvedSessionsAreResolvedPerTargetInsteadOfFromLastSession() {
        val state =
            RelayUiState(
                lastSessionId = "session-for-b",
                lastSessionTargetRelayDeviceId = "desktop-b",
                lastSessionApproved = true,
                approvedSessionIdsByRelayDevice =
                    mapOf(
                        "desktop-a" to "session-for-a",
                        "desktop-b" to "session-for-b",
                    ),
            )

        assertEquals("session-for-a", state.approvedSessionIdFor(RelayDeviceId("desktop-a")))
        assertEquals("session-for-b", state.approvedSessionIdFor(RelayDeviceId("desktop-b")))
        assertNull(state.approvedSessionIdFor(RelayDeviceId("desktop-c")))
    }

    @Test
    fun legacyLastSessionFieldsNeverAuthorizeAConnection() {
        val state =
            RelayUiState(
                lastSessionId = "legacy-session",
                lastSessionTargetRelayDeviceId = "desktop-a",
                lastSessionApproved = true,
            )

        assertNull(state.approvedSessionIdFor(RelayDeviceId("desktop-a")))
    }
}
