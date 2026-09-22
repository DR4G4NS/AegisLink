package dev.aegis.remote.core.model

import kotlinx.serialization.Serializable

@Serializable
data class HostAddress(
    val host: String,
    val port: Int? = null,
)

@Serializable
data class MacAddress(
    val value: String,
)

@Serializable
data class WakeOnLanConfig(
    val macAddress: MacAddress,
    val ipAddress: String? = null,
    val prefixLength: Int? = null,
    val broadcastAddress: String = "255.255.255.255",
    val port: Int = 9,
    val adapterId: String? = null,
    val adapterName: String? = null,
    val adapterStatus: String? = null,
    val capability: WakeOnLanCapability = WakeOnLanCapability.Unknown,
    val capabilityReason: String? = null,
)

@Serializable
enum class WakeOnLanCapability {
    Supported,
    Unknown,
    Unsupported,
}

@Serializable
data class RelayConfig(
    val relayUrl: String,
    val deviceId: RelayDeviceId? = null,
    val authTokenRef: String? = null,
    val enabled: Boolean = false,
)

@Serializable
data class TurnConfig(
    val urls: List<String>,
    val username: String? = null,
    val credentialRef: String? = null,
    val expiresAtEpochMillis: Long? = null,
)

@Serializable
data class StunTurnConfig(
    val stunUrls: List<String> = emptyList(),
    val turnConfig: TurnConfig? = null,
    val iceTransportPolicy: WebRtcIceTransportPolicy = WebRtcIceTransportPolicy.All,
)

@Serializable
enum class WebRtcIceTransportPolicy {
    All,
    RelayOnly,
}
