package dev.aegis.remote.core.nat

import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.RouteEvidence
import dev.aegis.remote.core.model.RouteHealth

/**
 * A STUN binding or TURN credential is a hint. A route is healthy only after
 * ICE selects a candidate pair of the expected type.
 */
fun routeHealthFromSelectedPair(
    expectedType: ConnectionRouteType,
    selectedRoute: ValidatedIceRoute?,
    reasonWhenMissing: String,
): RouteHealth {
    val provenType = selectedRoute?.toConnectionRouteType()
    return if (provenType == expectedType) {
        RouteHealth(
            routeType = expectedType,
            available = true,
            reason = "ICE selected ${selectedRoute.name} candidate pair",
            evidence = RouteEvidence.SelectedIcePair,
            hint = false,
        )
    } else {
        RouteHealth(
            routeType = expectedType,
            available = false,
            reason = reasonWhenMissing,
            evidence = RouteEvidence.UnprovenHint,
            hint = true,
        )
    }
}

fun ValidatedIceRoute.toConnectionRouteType(): ConnectionRouteType =
    when (this) {
        ValidatedIceRoute.LanDirect -> ConnectionRouteType.Lan
        ValidatedIceRoute.InternetDirect -> ConnectionRouteType.StunDirect
        ValidatedIceRoute.TurnRelay -> ConnectionRouteType.TurnRelay
    }
