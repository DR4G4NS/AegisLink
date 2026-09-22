package dev.aegis.remote.core.model

import kotlinx.serialization.Serializable

/** Session-level protocol revision exchanged after E2EE, not the QR pairing version. */
const val AEGIS_SESSION_PROTOCOL_REV: Int = 1

object SessionProtocolFeatures {
    const val ICE_RESTART = "iceRestart"
    const val PING = "ping"
    const val TURN_REFRESH = "turnRefresh"
    const val MONITOR_SWITCH = "monitorSwitch"

    val REQUIRED: Set<String> = setOf(ICE_RESTART, PING, TURN_REFRESH)

    val ALL: Set<String> = REQUIRED + MONITOR_SWITCH
}

@Serializable
data class SessionProtocolCapabilities(
    val protocolRev: Int = AEGIS_SESSION_PROTOCOL_REV,
    val features: List<String> = SessionProtocolFeatures.ALL.toList(),
) {
    init {
        require(protocolRev > 0) { "protocolRev must be positive" }
        require(features.all { it.isNotBlank() }) { "feature names must not be blank" }
    }

    fun featureSet(): Set<String> = features.toSet()
}

fun localSessionProtocolCapabilities(): SessionProtocolCapabilities =
    SessionProtocolCapabilities(
        protocolRev = AEGIS_SESSION_PROTOCOL_REV,
        features = SessionProtocolFeatures.ALL.toList(),
    )
