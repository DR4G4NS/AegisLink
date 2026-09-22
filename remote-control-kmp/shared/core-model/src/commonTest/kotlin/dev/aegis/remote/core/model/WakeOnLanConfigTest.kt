package dev.aegis.remote.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class WakeOnLanConfigTest {
    @Test
    fun persistsObservedAdapterNetworkAndCapabilityFields() {
        val config =
            WakeOnLanConfig(
                macAddress = MacAddress("00:11:22:33:44:55"),
                ipAddress = "192.168.40.23",
                prefixLength = 24,
                broadcastAddress = "192.168.40.255",
                adapterId = "11111111-2222-3333-4444-555555555555",
                adapterName = "Ethernet Test",
                adapterStatus = "Down",
                capability = WakeOnLanCapability.Unknown,
                capabilityReason = "WOL-7204 MAGIC_PACKET_WAKE_DISABLED",
            )

        val persistedPayload = Json.encodeToString(config)
        val restored = Json.decodeFromString<WakeOnLanConfig>(persistedPayload)

        assertEquals(config, restored)
    }
}
