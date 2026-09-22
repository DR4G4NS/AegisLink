package dev.aegis.remote.android.webrtc

import dev.aegis.remote.protocol.ProtocolDataChannelKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDataChannelConfigurationTest {
    @Test
    fun `pointer channel is unordered and never retransmitted`() {
        val init = androidDataChannelInit(ProtocolDataChannelKind.Pointer)

        assertFalse(init.ordered)
        assertEquals(0, init.maxRetransmits)
    }

    @Test
    fun `control keyboard and clipboard retain reliable ordered defaults`() {
        listOf(
            ProtocolDataChannelKind.Control,
            ProtocolDataChannelKind.Keyboard,
            ProtocolDataChannelKind.Clipboard,
        ).forEach { kind ->
            val init = androidDataChannelInit(kind)
            assertTrue(init.ordered)
            assertEquals(-1, init.maxRetransmits)
        }
    }
}
