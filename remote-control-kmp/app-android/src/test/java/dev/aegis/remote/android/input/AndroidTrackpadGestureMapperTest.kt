package dev.aegis.remote.android.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidTrackpadGestureMapperTest {
    @Test
    fun `retains fractional movement for precise slow gestures`() {
        val mapper =
            AndroidTrackpadGestureMapper(
                sensitivity = 1f,
                maximumAcceleration = 1f,
            )

        assertNull(mapper.mapMotion(0.4f, 0.4f))
        assertNull(mapper.mapMotion(0.4f, 0.4f))
        assertEquals(TrackpadMotion(1, 1), mapper.mapMotion(0.4f, 0.4f))
    }

    @Test
    fun `accelerates a fast swipe while preserving its direction`() {
        val mapper = AndroidTrackpadGestureMapper(sensitivity = 1f)

        val slow = assertNotNull(mapper.mapMotion(4f, 0f))
        mapper.reset()
        val fast = assertNotNull(mapper.mapMotion(40f, 0f))

        assertTrue(fast.deltaX > slow.deltaX * 5)
        assertEquals(0, fast.deltaY)
    }

    @Test
    fun `bounds malformed or extreme motion`() {
        val mapper = AndroidTrackpadGestureMapper(maximumMotionPerFrame = 30)

        assertNull(mapper.mapMotion(Float.NaN, 1f))
        assertEquals(TrackpadMotion(30, -30), mapper.mapMotion(10_000f, -10_000f))
    }

    @Test
    fun `natural two finger scroll accumulates pixels into platform steps`() {
        val mapper = AndroidTrackpadGestureMapper(scrollPixelsPerStep = 20f)

        assertNull(mapper.mapNaturalScroll(0f, -9f))
        assertEquals(TrackpadScroll(0f, 1f), mapper.mapNaturalScroll(0f, -11f))
        assertEquals(TrackpadScroll(-1f, 0f), mapper.mapNaturalScroll(20f, 0f))
    }

    @Test
    fun `reset discards residual movement and scroll`() {
        val mapper =
            AndroidTrackpadGestureMapper(
                sensitivity = 1f,
                maximumAcceleration = 1f,
                scrollPixelsPerStep = 20f,
            )
        mapper.mapMotion(0.75f, 0f)
        mapper.mapNaturalScroll(0f, 15f)

        mapper.reset()

        assertNull(mapper.mapMotion(0.3f, 0f))
        assertNull(mapper.mapNaturalScroll(0f, 6f))
    }
}
