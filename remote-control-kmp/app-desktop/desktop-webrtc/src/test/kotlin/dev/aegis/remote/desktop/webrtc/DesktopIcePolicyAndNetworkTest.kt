package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.model.WebRtcIceTransportPolicy
import dev.onvoid.webrtc.RTCIceTransportPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopIcePolicyAndNetworkTest {
    @Test
    fun `relay only maps to native relay transport policy`() {
        assertEquals(RTCIceTransportPolicy.RELAY, desktopIceTransportPolicy(WebRtcIceTransportPolicy.RelayOnly))
        assertEquals(RTCIceTransportPolicy.ALL, desktopIceTransportPolicy(WebRtcIceTransportPolicy.All))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `network monitor emits only after interface fingerprint changes`() =
        runTest {
            var now = 0L
            var fingerprint = "wifi:10.0.0.2"
            val changed =
                async {
                    desktopNetworkChanges(
                        pollIntervalMillis = 100,
                        fingerprint = { fingerprint },
                        clock = { now },
                        debounceMillis = 0,
                    ).first()
                }
            runCurrent()
            now = 100
            advanceTimeBy(100)
            assertEquals(false, changed.isCompleted)
            fingerprint = "ethernet:192.168.1.2"
            now = 200
            advanceTimeBy(100)
            changed.await()
        }
}
