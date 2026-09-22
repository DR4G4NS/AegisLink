package dev.aegis.remote.core.pairing

import dev.aegis.remote.core.model.AdvertisedEndpointKind
import dev.aegis.remote.core.model.AegisLanDiscovery
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.HostAddress
import kotlinx.serialization.Serializable

@Serializable
data class LanAnnouncePayload(
    val fingerprint: String,
    val tlsPin: String,
    val port: Int,
    val pairingUrl: String,
    val kind: AdvertisedEndpointKind = AdvertisedEndpointKind.Lan,
) {
    init {
        require(fingerprint.isNotBlank()) { "LAN announce fingerprint must not be blank" }
        require(tlsPin.isNotBlank()) { "LAN announce TLS pin must not be blank" }
        require(port > 0) { "LAN announce port must be positive" }
        require(pairingUrl.isNotBlank()) { "LAN announce pairing URL must not be blank" }
    }
}

@Serializable
data class LanRegisterRequest(
    val fingerprint: String,
    val authorizedDeviceId: String,
) {
    init {
        require(fingerprint.isNotBlank())
        require(authorizedDeviceId.isNotBlank())
    }
}

@Serializable
enum class LanAuthorizationStatus {
    UNKNOWN,
    ACTIVE,
    REVOKED,
    NOT_FOUND,
}

fun LanAuthorizationStatus.marksHostUnlinked(): Boolean = this == LanAuthorizationStatus.REVOKED || this == LanAuthorizationStatus.NOT_FOUND

@Serializable
data class LanRegisterResponse(
    val fingerprint: String,
    val tlsPin: String,
    val port: Int,
    val pairingUrl: String,
    val host: String,
    val authorizationStatus: LanAuthorizationStatus = LanAuthorizationStatus.UNKNOWN,
    val permissions: dev.aegis.remote.core.model.DevicePermissions? = null,
)

sealed interface LanRediscoveryDecision {
    data object DropSelf : LanRediscoveryDecision

    data class PinMismatch(
        val reason: String,
    ) : LanRediscoveryDecision

    data class UpdateLocalHost(
        val host: HostAddress,
        val pairingUrl: String,
    ) : LanRediscoveryDecision

    data object HostUnlinked : LanRediscoveryDecision

    data object Ignore : LanRediscoveryDecision
}

object LanRediscoveryPolicy {
    const val MULTICAST_GROUP = AegisLanDiscovery.MULTICAST_GROUP
    const val PORT = AegisLanDiscovery.PORT

    fun decide(
        profile: DeviceProfile,
        announce: LanAnnouncePayload,
        localFingerprint: String?,
        expectedTlsPin: String?,
        tlsPinVerified: Boolean,
    ): LanRediscoveryDecision {
        val announcedFingerprint = normalizeFingerprint(announce.fingerprint)
        if (localFingerprint != null && announcedFingerprint == normalizeFingerprint(localFingerprint)) {
            return LanRediscoveryDecision.DropSelf
        }
        val pinnedHost = profile.pairedHostIdentity?.fingerprint?.let(::normalizeFingerprint)
        if (pinnedHost != null && announcedFingerprint != pinnedHost) {
            return LanRediscoveryDecision.Ignore
        }
        val expectedPin = expectedTlsPin?.let(::normalizeFingerprint)
        val announcedPin = normalizeFingerprint(announce.tlsPin)
        if (expectedPin != null && announcedPin != expectedPin) {
            return LanRediscoveryDecision.PinMismatch("TLS pin mismatch; refusing to update DeviceProfile.localHost")
        }
        if (!tlsPinVerified) {
            return LanRediscoveryDecision.PinMismatch("TLS pin was not verified against the announcer")
        }
        val host = hostFromPairingUrl(announce.pairingUrl, announce.port) ?: return LanRediscoveryDecision.Ignore
        if (host.host == profile.localHost.host && (profile.localHost.port ?: announce.port) == announce.port) {
            return LanRediscoveryDecision.Ignore
        }
        return LanRediscoveryDecision.UpdateLocalHost(host = host, pairingUrl = announce.pairingUrl)
    }

    fun normalizeFingerprint(value: String): String = value.trim().removePrefix("SHA256:").lowercase()

    fun hostFromPairingUrl(
        pairingUrl: String,
        fallbackPort: Int,
    ): HostAddress? {
        val match = URL_HOST.find(pairingUrl.trim()) ?: return null
        val host = match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
        if (host.isBlank()) return null
        val port = match.groupValues[3].toIntOrNull() ?: fallbackPort
        return HostAddress(host = host.removePrefix("[").removeSuffix("]"), port = port)
    }

    private val URL_HOST =
        Regex("""^[a-z][a-z0-9+.-]*://(?:\[([^\]]+)\]|([^/:]+))(?::(\d+))?(?:/.*)?$""", RegexOption.IGNORE_CASE)
}
