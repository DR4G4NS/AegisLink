package dev.aegis.remote.android.webrtc

import dev.aegis.remote.core.model.WebRtcIceTransportPolicy
import org.webrtc.PeerConnection
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidIcePolicyTest {
    @Test
    fun `relay only maps to native relay transport policy`() {
        assertEquals(PeerConnection.IceTransportsType.RELAY, androidIceTransportsType(WebRtcIceTransportPolicy.RelayOnly))
        assertEquals(PeerConnection.IceTransportsType.ALL, androidIceTransportsType(WebRtcIceTransportPolicy.All))
    }
}
