package dev.aegis.remote.android.ui.components

import dev.aegis.remote.android.R
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.QualityMode
import dev.aegis.remote.core.model.WakeOnLanCapability
import java.util.Locale

internal enum class UserFacingStatusKind {
    WaitingApproval,
    Recovering,
    Reconnecting,
    Connecting,
    Connected,
    Preparing,
    Registered,
    Loading,
    Refreshing,
    PermissionDenied,
    ConfirmationRequired,
    Blocked,
    Cancelled,
    Completed,
    Failed,
    ProtocolMismatch,
    Idle,
}

// Flat keyword-to-status mapping table; each branch is one independent match rule.
@Suppress("CyclomaticComplexMethod")
internal fun userFacingStatusKind(raw: String): UserFacingStatusKind? {
    val value = raw.lowercase(Locale.ROOT)
    return when {
        containsAny(value, "protocol skew", "protocol mismatch", "capability handshake") -> {
            UserFacingStatusKind.ProtocolMismatch
        }

        containsAny(value, "ping is alive", "recovering", "ice restart in flight", "peer is not rebuilt") -> {
            UserFacingStatusKind.Recovering
        }

        containsAll(value, "waiting", "approval") -> {
            UserFacingStatusKind.WaitingApproval
        }

        containsAny(value, "reconnect", "ping timed out") -> {
            UserFacingStatusKind.Reconnecting
        }

        containsAny(value, "connecting", "resolving") -> {
            UserFacingStatusKind.Connecting
        }

        value == "connected" || containsAny(value, "streaming") -> {
            UserFacingStatusKind.Connected
        }

        containsAny(value, "preparing", "negotiating") -> {
            UserFacingStatusKind.Preparing
        }

        containsAny(value, "idle", "closed", "en espera") -> {
            UserFacingStatusKind.Idle
        }

        containsAny(value, "registered") -> {
            UserFacingStatusKind.Registered
        }

        containsAny(value, "loading", "loaded", "opening", "verif") -> {
            UserFacingStatusKind.Loading
        }

        containsAny(value, "refresh", "aplicando") -> {
            UserFacingStatusKind.Refreshing
        }

        containsAny(value, "permission", "not permitted") -> {
            UserFacingStatusKind.PermissionDenied
        }

        containsAny(value, "confirmation", "confirm") -> {
            UserFacingStatusKind.ConfirmationRequired
        }

        containsAny(value, "blocked") -> {
            UserFacingStatusKind.Blocked
        }

        containsAny(value, "cancel") -> {
            UserFacingStatusKind.Cancelled
        }

        containsAny(value, "complete", "copied") -> {
            UserFacingStatusKind.Completed
        }

        containsAny(value, "failed", "could not", "error", "no se pudo") -> {
            UserFacingStatusKind.Failed
        }

        else -> {
            null
        }
    }
}

// Exhaustive enum-to-resource lookup; the branch count tracks the enum size.
@Suppress("CyclomaticComplexMethod")
internal fun UserFacingStatusKind.resourceId(): Int =
    when (this) {
        UserFacingStatusKind.WaitingApproval -> R.string.status_waiting_approval
        UserFacingStatusKind.Recovering -> R.string.status_recovering
        UserFacingStatusKind.Connected -> R.string.status_connected
        UserFacingStatusKind.Preparing -> R.string.status_preparing
        UserFacingStatusKind.Registered -> R.string.status_registered
        UserFacingStatusKind.Loading -> R.string.status_loading
        UserFacingStatusKind.Refreshing -> R.string.status_refreshing
        UserFacingStatusKind.PermissionDenied -> R.string.status_permission_denied
        UserFacingStatusKind.ConfirmationRequired -> R.string.status_confirmation_required
        UserFacingStatusKind.Blocked -> R.string.status_blocked
        UserFacingStatusKind.Cancelled -> R.string.status_cancelled
        UserFacingStatusKind.Completed -> R.string.status_completed
        UserFacingStatusKind.Failed -> R.string.status_failed
        UserFacingStatusKind.ProtocolMismatch -> R.string.status_protocol_mismatch
        UserFacingStatusKind.Reconnecting -> R.string.status_reconnecting
        UserFacingStatusKind.Connecting -> R.string.status_connecting
        UserFacingStatusKind.Idle -> R.string.visual_waiting
    }

internal fun QualityMode.labelRes(): Int =
    when (this) {
        QualityMode.LowLatency -> R.string.quality_low_latency
        QualityMode.Balanced -> R.string.quality_balanced
        QualityMode.QualityFirst -> R.string.quality_first
        QualityMode.BatterySaver -> R.string.quality_battery
        QualityMode.RelaySaver -> R.string.quality_relay
    }

internal fun ConnectionRouteType.labelRes(): Int =
    when (this) {
        ConnectionRouteType.Lan -> R.string.route_lan
        ConnectionRouteType.Vpn -> R.string.route_vpn
        ConnectionRouteType.StunDirect -> R.string.route_stun_direct
        ConnectionRouteType.TurnRelay -> R.string.route_turn_relay
        ConnectionRouteType.ReverseRelay -> R.string.route_reverse_relay
        ConnectionRouteType.ManualSsh -> R.string.route_manual_ssh
    }

internal fun WakeOnLanCapability.labelRes(): Int =
    when (this) {
        WakeOnLanCapability.Supported -> R.string.wol_supported
        WakeOnLanCapability.Unknown -> R.string.wol_unknown
        WakeOnLanCapability.Unsupported -> R.string.wol_unsupported
    }

private fun containsAny(
    value: String,
    vararg terms: String,
): Boolean = terms.any(value::contains)

private fun containsAll(
    value: String,
    vararg terms: String,
): Boolean = terms.all(value::contains)
