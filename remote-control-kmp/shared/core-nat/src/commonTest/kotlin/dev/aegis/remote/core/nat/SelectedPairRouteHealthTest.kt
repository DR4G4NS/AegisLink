package dev.aegis.remote.core.nat

import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.RouteEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectedPairRouteHealthTest {
    @Test
    fun stunBindingWithoutSelectedPairIsNotAvailable() {
        val health =
            routeHealthFromSelectedPair(
                expectedType = ConnectionRouteType.StunDirect,
                selectedRoute = null,
                reasonWhenMissing = "STUN binding is a hint, not a selected ICE pair",
            )

        assertFalse(health.available)
        assertTrue(health.hint)
        assertEquals(RouteEvidence.UnprovenHint, health.evidence)
    }

    @Test
    fun turnCredentialsWithoutRelayPairAreNotAvailable() {
        val health =
            routeHealthFromSelectedPair(
                expectedType = ConnectionRouteType.TurnRelay,
                selectedRoute = ValidatedIceRoute.InternetDirect,
                reasonWhenMissing = "TURN credentials are a hint, not a selected ICE pair",
            )

        assertFalse(health.available)
        assertTrue(health.hint)
    }

    @Test
    fun selectedRelayPairProvesTurnRelay() {
        val health =
            routeHealthFromSelectedPair(
                expectedType = ConnectionRouteType.TurnRelay,
                selectedRoute = ValidatedIceRoute.TurnRelay,
                reasonWhenMissing = "unused",
            )

        assertTrue(health.available)
        assertTrue(health.isProvenAvailable())
        assertEquals(RouteEvidence.SelectedIcePair, health.evidence)
    }
}
