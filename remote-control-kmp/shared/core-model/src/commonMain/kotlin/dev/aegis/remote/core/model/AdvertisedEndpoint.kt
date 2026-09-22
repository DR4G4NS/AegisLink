package dev.aegis.remote.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class AdvertisedEndpointKind {
    Lan,
    Vpn,
    Tailscale,
}

/**
 * A stable-keyed advertised access hint. The connection attempt decides whether
 * the endpoint works; the key must not be a raw DHCP IP.
 */
@Serializable
data class AdvertisedEndpoint(
    val key: String,
    val kind: AdvertisedEndpointKind,
    val pairingUrl: String,
    val host: HostAddress,
) {
    init {
        require(key.isNotBlank()) { "Advertised endpoint key must not be blank" }
        require(pairingUrl.isNotBlank()) { "Advertised pairing URL must not be blank" }
    }
}

/**
 * Stable keys (`lan`, `vpn`, `tailscale`) rather than DHCP IPs. These are
 * connection hints; ICE still decides whether the path works.
 */
fun DeviceProfile.stableAdvertisedEndpoints(): List<AdvertisedEndpoint> {
    val lanPort = localHost.port ?: AegisLocalPorts.VISUAL_PROTOCOL
    val lanKind = classifyAdvertisedHost(localHost.host)
    val lanHost = localHost.copy(port = lanPort)
    val lan =
        AdvertisedEndpoint(
            key = "lan",
            kind = lanKind,
            pairingUrl = pairingUrlFor(lanHost),
            host = lanHost,
        )
    val overlay =
        vpnHost?.let { advertised ->
            val port = advertised.port ?: lanPort
            val host = advertised.copy(port = port)
            val kind = classifyAdvertisedHost(host.host)
            AdvertisedEndpoint(
                key = kind.name.lowercase(),
                kind = kind,
                pairingUrl = pairingUrlFor(host),
                host = host,
            )
        }
    return listOfNotNull(lan, overlay).distinctBy { it.key }
}

/** Classify a host as LAN, Tailscale CGNAT, or another overlay/VPN. */
fun classifyAdvertisedHost(host: String): AdvertisedEndpointKind {
    val value =
        host
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
            .lowercase()
    if (value.startsWith("100.") || value.startsWith("fd7a:115c:a1e0:")) {
        return AdvertisedEndpointKind.Tailscale
    }
    return if (isSiteLocalOrLoopback(value)) AdvertisedEndpointKind.Lan else AdvertisedEndpointKind.Vpn
}

fun advertisedEndpointsFromPairingUrls(urls: List<String>): List<AdvertisedEndpoint> {
    val seenKeys = mutableSetOf<String>()
    return urls.mapNotNull { url ->
        val host = hostFromPairingUrl(url) ?: return@mapNotNull null
        val kind = classifyAdvertisedHost(host.host)
        val key = kind.name.lowercase()
        if (!seenKeys.add(key)) return@mapNotNull null
        AdvertisedEndpoint(key = key, kind = kind, pairingUrl = url.trim(), host = host)
    }
}

fun DeviceProfile.withAdvertisedPairingUrls(urls: List<String>): DeviceProfile {
    val endpoints = advertisedEndpointsFromPairingUrls(urls)
    val lan = endpoints.firstOrNull { it.kind == AdvertisedEndpointKind.Lan }
    val overlay =
        endpoints.firstOrNull { it.kind == AdvertisedEndpointKind.Tailscale }
            ?: endpoints.firstOrNull { it.kind == AdvertisedEndpointKind.Vpn }
    return copy(
        localHost = lan?.host ?: localHost,
        vpnHost = overlay?.host ?: vpnHost,
        advertisedEndpoints = endpoints.ifEmpty { stableAdvertisedEndpoints() },
    )
}

private fun pairingUrlFor(host: HostAddress): String {
    val port = host.port ?: AegisLocalPorts.VISUAL_PROTOCOL
    val encoded = if (host.host.contains(":")) "[${host.host}]" else host.host
    return "https://$encoded:$port"
}

private fun hostFromPairingUrl(pairingUrl: String): HostAddress? {
    val match = PAIRING_URL_HOST.find(pairingUrl.trim()) ?: return null
    val host = match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
    if (host.isBlank()) return null
    val port = match.groupValues[3].toIntOrNull() ?: AegisLocalPorts.VISUAL_PROTOCOL
    return HostAddress(host = host.removePrefix("[").removeSuffix("]"), port = port)
}

private fun isSiteLocalOrLoopback(host: String): Boolean {
    if (host == "localhost" || host == "::1" || host.startsWith("127.") || host.startsWith("fe80:")) {
        return true
    }
    if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("169.254.")) {
        return true
    }
    if (host.startsWith("172.")) {
        val second = host.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        return second in 16..31
    }
    return false
}

private val PAIRING_URL_HOST =
    Regex("""^[a-z][a-z0-9+.-]*://(?:\[([^\]]+)\]|([^/:]+))(?::(\d+))?(?:/.*)?$""", RegexOption.IGNORE_CASE)
