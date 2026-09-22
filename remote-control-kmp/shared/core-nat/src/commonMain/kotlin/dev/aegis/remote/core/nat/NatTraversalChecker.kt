package dev.aegis.remote.core.nat

import dev.aegis.remote.core.model.NatTraversalState
import dev.aegis.remote.core.model.StunTurnConfig

data class ConnectivityCheckResult(
    val state: NatTraversalState,
    val reason: String? = null,
)

data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credentialRef: String? = null,
)

enum class IceCandidateType {
    Host,
    ServerReflexive,
    PeerReflexive,
    Relay,
    Unknown,
}

enum class IceCandidatePairState {
    Frozen,
    Waiting,
    InProgress,
    Succeeded,
    Failed,
}

data class IceCandidatePairSnapshot(
    val state: IceCandidatePairState,
    val nominated: Boolean,
    val selected: Boolean,
    val localCandidateType: IceCandidateType,
    val remoteCandidateType: IceCandidateType,
    val currentRoundTripTimeMs: Long? = null,
    val availableOutgoingBitrateKbps: Int? = null,
    val transportProtocol: String? = null,
    val turnTransport: String? = null,
)

data class IcePathValidationResult(
    val state: NatTraversalState,
    val route: ValidatedIceRoute?,
    val reason: String,
    val rttMs: Long? = null,
    val availableOutgoingBitrateKbps: Int? = null,
    val selectedPair: IceCandidatePairSnapshot? = null,
)

enum class ValidatedIceRoute {
    LanDirect,
    InternetDirect,
    TurnRelay,
}

interface StunProbe {
    suspend fun check(stunUrls: List<String>): ConnectivityCheckResult
}

interface TurnProbe {
    suspend fun check(config: StunTurnConfig): ConnectivityCheckResult
}

interface NatTraversalChecker {
    suspend fun check(config: StunTurnConfig): ConnectivityCheckResult
}

interface TurnConfigProvider {
    suspend fun getTurnConfig(): StunTurnConfig?
}

class IcePathValidator(
    private val maxDirectRttMs: Long = 1_000,
    private val maxRelayRttMs: Long = 2_500,
) {
    fun validate(candidatePairs: List<IceCandidatePairSnapshot>): IcePathValidationResult {
        val selected =
            candidatePairs.firstOrNull { it.selected && it.nominated }
                ?: candidatePairs.firstOrNull { it.selected }
                ?: candidatePairs.firstOrNull { it.nominated && it.state == IceCandidatePairState.Succeeded }

        if (selected == null) {
            val hasProgress =
                candidatePairs.any {
                    it.state == IceCandidatePairState.Waiting || it.state == IceCandidatePairState.InProgress
                }
            return if (hasProgress) {
                IcePathValidationResult(
                    state = NatTraversalState.Checking,
                    route = null,
                    reason = "ICE checks are still in progress and no selected candidate pair exists yet",
                )
            } else {
                IcePathValidationResult(
                    state = NatTraversalState.Blocked,
                    route = null,
                    reason = "ICE did not produce a selected candidate pair",
                )
            }
        }

        if (selected.state != IceCandidatePairState.Succeeded) {
            return IcePathValidationResult(
                state = NatTraversalState.Checking,
                route = null,
                reason = "Selected ICE candidate pair is ${selected.state.name.lowercase()}",
                rttMs = selected.currentRoundTripTimeMs,
                availableOutgoingBitrateKbps = selected.availableOutgoingBitrateKbps,
                selectedPair = selected,
            )
        }

        val route = selected.toValidatedRoute()
        val maxRtt = if (route == ValidatedIceRoute.TurnRelay) maxRelayRttMs else maxDirectRttMs
        val rtt = selected.currentRoundTripTimeMs
        if (rtt != null && rtt > maxRtt) {
            return IcePathValidationResult(
                state = NatTraversalState.Failed("ICE selected ${route.name} but RTT ${rtt}ms exceeds ${maxRtt}ms"),
                route = route,
                reason = "Selected ICE path is too slow for interactive control",
                rttMs = rtt,
                availableOutgoingBitrateKbps = selected.availableOutgoingBitrateKbps,
                selectedPair = selected,
            )
        }

        return IcePathValidationResult(
            state = if (route == ValidatedIceRoute.TurnRelay) NatTraversalState.TurnRequired else NatTraversalState.DirectPossible,
            route = route,
            reason =
                when (route) {
                    ValidatedIceRoute.LanDirect -> "ICE selected a host-to-host direct path"
                    ValidatedIceRoute.InternetDirect -> "ICE selected a server-reflexive or peer-reflexive direct path"
                    ValidatedIceRoute.TurnRelay -> "ICE selected a TURN relay path"
                },
            rttMs = rtt,
            availableOutgoingBitrateKbps = selected.availableOutgoingBitrateKbps,
            selectedPair = selected,
        )
    }

    private fun IceCandidatePairSnapshot.toValidatedRoute(): ValidatedIceRoute =
        if (localCandidateType == IceCandidateType.Relay || remoteCandidateType == IceCandidateType.Relay) {
            ValidatedIceRoute.TurnRelay
        } else if (localCandidateType == IceCandidateType.Host && remoteCandidateType == IceCandidateType.Host) {
            ValidatedIceRoute.LanDirect
        } else {
            ValidatedIceRoute.InternetDirect
        }
}

class BasicNatTraversalChecker(
    private val stunProbe: StunProbe,
    private val turnProbe: TurnProbe,
    private val clock: () -> Long,
) : NatTraversalChecker {
    override suspend fun check(config: StunTurnConfig): ConnectivityCheckResult {
        if (config.stunUrls.isEmpty() && config.turnConfig == null) {
            return ConnectivityCheckResult(
                state = NatTraversalState.Blocked,
                reason = "No STUN or TURN servers are configured",
            )
        }

        val stunResult =
            if (config.stunUrls.isNotEmpty()) {
                stunProbe.check(config.stunUrls)
            } else {
                ConnectivityCheckResult(
                    state = NatTraversalState.TurnRequired,
                    reason = "No STUN servers are configured",
                )
            }

        if (stunResult.state == NatTraversalState.DirectPossible) {
            return stunResult
        }

        val turnConfig =
            config.turnConfig ?: return ConnectivityCheckResult(
                state = NatTraversalState.TurnRequired,
                reason = stunResult.reason ?: "Direct WebRTC connectivity requires TURN fallback",
            )

        if (turnConfig.isExpired(clock())) {
            return ConnectivityCheckResult(
                state = NatTraversalState.Blocked,
                reason = "TURN credentials are expired",
            )
        }

        val turnResult = turnProbe.check(config.copy(stunUrls = emptyList()))
        return when (turnResult.state) {
            NatTraversalState.DirectPossible,
            NatTraversalState.TurnRequired,
            -> {
                ConnectivityCheckResult(
                    state = NatTraversalState.TurnRequired,
                    reason = turnResult.reason ?: "Direct path is unavailable; TURN relay is available",
                )
            }

            NatTraversalState.Unknown,
            NatTraversalState.Checking,
            NatTraversalState.Blocked,
            is NatTraversalState.Failed,
            -> {
                turnResult
            }
        }
    }
}

class IceServerConfigFactory(
    private val clock: () -> Long,
) {
    fun create(config: StunTurnConfig): List<IceServerConfig> =
        buildList {
            if (config.stunUrls.isNotEmpty()) {
                add(IceServerConfig(urls = config.stunUrls))
            }

            val turnConfig = config.turnConfig
            if (turnConfig != null && !turnConfig.isExpired(clock())) {
                add(
                    IceServerConfig(
                        urls = turnConfig.urls,
                        username = turnConfig.username,
                        credentialRef = turnConfig.credentialRef,
                    ),
                )
            }
        }
}

private fun dev.aegis.remote.core.model.TurnConfig.isExpired(now: Long): Boolean {
    val expiresAt = expiresAtEpochMillis ?: return false
    return expiresAt <= now
}
