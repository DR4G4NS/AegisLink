package dev.aegis.remote.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdvertisedEndpointTest {
    @Test
    fun usesStableKeysInsteadOfRawIps() {
        val profile =
            DeviceProfile(
                id = DeviceProfileId("profile-1"),
                displayName = "PC",
                localHost = HostAddress("192.168.1.10"),
                vpnHost = HostAddress("100.64.0.2"),
                sshPort = 22,
                username = "ian",
                authMethod = AuthMethod.Password,
            )
        val endpoints = profile.stableAdvertisedEndpoints()
        assertEquals(listOf("lan", "tailscale"), endpoints.map { it.key })
        assertEquals(AdvertisedEndpointKind.Lan, endpoints[0].kind)
        assertEquals(AdvertisedEndpointKind.Tailscale, endpoints[1].kind)
        assertEquals(AegisLocalPorts.VISUAL_PROTOCOL, endpoints[0].host.port)
    }

    @Test
    fun pairingUrlsBecomeStableLanAndTailscaleHints() {
        val endpoints =
            advertisedEndpointsFromPairingUrls(
                listOf(
                    "https://192.168.1.20:48291",
                    "https://100.64.0.8:48291",
                    "https://192.168.1.20:48291",
                ),
            )
        assertEquals(listOf("lan", "tailscale"), endpoints.map { it.key })
        assertEquals(AdvertisedEndpointKind.Lan, endpoints[0].kind)
        assertEquals(AdvertisedEndpointKind.Tailscale, endpoints[1].kind)
        assertEquals("100.64.0.8", endpoints[1].host.host)
    }

    @Test
    fun withAdvertisedPairingUrlsStoresVpnWithoutChangingTrustRoot() {
        val profile =
            DeviceProfile(
                id = DeviceProfileId("profile-1"),
                displayName = "PC",
                localHost = HostAddress("10.0.0.2"),
                sshPort = 22,
                username = "ian",
                authMethod = AuthMethod.Password,
            ).withAdvertisedPairingUrls(
                listOf("https://192.168.1.20:48291", "https://100.64.0.8:48291"),
            )
        assertEquals("192.168.1.20", profile.localHost.host)
        assertEquals("100.64.0.8", profile.vpnHost?.host)
        assertEquals(listOf("lan", "tailscale"), profile.advertisedEndpoints.map { it.key })
        assertNull(profile.relayDeviceId)
    }
}
