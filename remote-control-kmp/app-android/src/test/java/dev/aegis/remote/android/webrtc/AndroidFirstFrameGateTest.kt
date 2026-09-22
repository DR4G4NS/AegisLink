package dev.aegis.remote.android.webrtc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidFirstFrameGateTest {
    @Test
    fun opensOnlyOnceAfterTheFirstFrame() {
        var transitions = 0
        val gate = AndroidFirstFrameGate { transitions += 1 }

        assertFalse(gate.hasObservedFirstFrame)

        gate.observeFrame()
        gate.observeFrame()

        assertTrue(gate.hasObservedFirstFrame)
        assertEquals(1, transitions)
    }

    @Test
    fun aReplacementGateWaitsForItsOwnFirstFrame() {
        val first = AndroidFirstFrameGate {}
        val replacement = AndroidFirstFrameGate {}

        first.observeFrame()

        assertTrue(first.hasObservedFirstFrame)
        assertFalse(replacement.hasObservedFirstFrame)
    }
}
