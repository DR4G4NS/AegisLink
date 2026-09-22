package dev.aegis.remote.desktop.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalPairingAddressTest {
    @Test
    fun formatsIpv4AndIpv6PairingUrls() {
        assertEquals("https://192.168.1.10:48291", localPairingBaseUrl("192.168.1.10", 48291))
        assertEquals("https://[fd00::10]:48291", localPairingBaseUrl("fd00::10", 48291))
        assertEquals("https://[fd00::10]:48291", localPairingBaseUrl("[fd00::10]", 48291))
    }

    @Test
    fun activePhysicalIpv4LanWinsOverVpnVirtualAndIpv6Addresses() {
        val selected =
            selectAdvertisedHost(
                listOf(
                    candidate("10.147.19.2", "WireGuard VPN", ipv4 = true, hasBroadcast = false, virtual = true),
                    candidate("fd00::20", "Wi-Fi", ipv4 = false, hasBroadcast = false),
                    candidate("172.31.192.1", "vEthernet (WSL)", ipv4 = true, hasBroadcast = true, virtual = true),
                    candidate("192.168.1.92", "Wi-Fi", ipv4 = true, hasBroadcast = true, defaultRoute = true),
                ),
            )

        assertEquals("192.168.1.92", selected)
    }

    @Test
    fun rustWintunAddressFromAffectedMachineIsPenalizedEvenWhenJdkDoesNotMarkItVirtual() {
        val ranked =
            selectAdvertisedHosts(
                listOf(
                    candidate(
                        address = "172.31.196.207",
                        name = "iftype53_32768 Rust Wintun Tunnel Tunnel",
                        ipv4 = true,
                        hasBroadcast = false,
                        virtual = false,
                    ),
                    candidate(
                        address = "10.147.19.204",
                        name = "ethernet_32779 ZeroTier Virtual Port",
                        ipv4 = true,
                        hasBroadcast = false,
                        virtual = false,
                    ),
                    candidate(
                        address = "192.168.1.92",
                        name = "wireless_32774 RZ608 Wi-Fi 6E 80MHz #2",
                        ipv4 = true,
                        hasBroadcast = true,
                        defaultRoute = true,
                    ),
                ),
            )

        assertEquals(listOf("192.168.1.92", "10.147.19.204", "172.31.196.207"), ranked)
    }

    @Test
    fun ipv4LanAddressesArePreferredOverIpv6InThePairingQr() {
        val ranked =
            selectAdvertisedHosts(
                listOf(
                    candidate("fd00::20", "Wi-Fi", ipv4 = false, hasBroadcast = false),
                    candidate("192.168.1.92", "Wi-Fi", ipv4 = true, hasBroadcast = true, defaultRoute = true),
                    candidate("fd00::30", "Ethernet", ipv4 = false, hasBroadcast = false),
                ),
            )

        assertEquals(listOf("192.168.1.92"), ranked)
    }

    @Test
    fun returnsNullWhenNoLanAddressIsAvailable() {
        assertEquals(
            null,
            selectAdvertisedHost(
                listOf(
                    candidate(
                        address = "203.0.113.10",
                        name = "Ethernet",
                        ipv4 = true,
                        hasBroadcast = true,
                        siteLocal = false,
                    ),
                ),
            ),
        )
    }

    private fun candidate(
        address: String,
        name: String,
        ipv4: Boolean,
        hasBroadcast: Boolean,
        virtual: Boolean = false,
        siteLocal: Boolean = true,
        defaultRoute: Boolean = false,
    ) = LocalAddressCandidate(
        address = address,
        interfaceName = name,
        ipv4 = ipv4,
        siteLocal = siteLocal,
        linkLocal = false,
        hasBroadcast = hasBroadcast,
        supportsMulticast = true,
        virtualInterface = virtual,
        defaultRoute = defaultRoute,
    )
}
