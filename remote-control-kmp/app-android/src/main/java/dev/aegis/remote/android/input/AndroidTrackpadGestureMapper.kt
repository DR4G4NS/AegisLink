package dev.aegis.remote.android.input

import kotlin.math.hypot

/**
 * Converts Android touch deltas into bounded desktop pointer/wheel deltas.
 *
 * Fractional motion is retained between frames, low-speed input stays precise, and faster swipes
 * gain modest acceleration. Scroll uses natural touchpad direction: content follows two fingers.
 */
internal class AndroidTrackpadGestureMapper(
    private val sensitivity: Float = 1.15f,
    private val accelerationDistance: Float = 18f,
    private val maximumAcceleration: Float = 1.75f,
    private val scrollPixelsPerStep: Float = 28f,
    private val maximumMotionPerFrame: Int = 180,
    private val maximumScrollStepsPerFrame: Int = 8,
) {
    private var pendingMotionX = 0f
    private var pendingMotionY = 0f
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f

    init {
        require(sensitivity > 0f)
        require(accelerationDistance > 0f)
        require(maximumAcceleration >= 1f)
        require(scrollPixelsPerStep > 0f)
        require(maximumMotionPerFrame > 0)
        require(maximumScrollStepsPerFrame > 0)
    }

    @Synchronized
    fun mapMotion(
        deltaX: Float,
        deltaY: Float,
    ): TrackpadMotion? {
        if (!deltaX.isFinite() || !deltaY.isFinite()) return null
        val distance = hypot(deltaX, deltaY)
        val acceleration =
            (1f + (distance / accelerationDistance) * 0.35f)
                .coerceAtMost(maximumAcceleration)
        pendingMotionX += deltaX * sensitivity * acceleration
        pendingMotionY += deltaY * sensitivity * acceleration

        val rawX = pendingMotionX.toInt()
        val rawY = pendingMotionY.toInt()
        pendingMotionX -= rawX
        pendingMotionY -= rawY
        val mappedX = rawX.coerceIn(-maximumMotionPerFrame, maximumMotionPerFrame)
        val mappedY = rawY.coerceIn(-maximumMotionPerFrame, maximumMotionPerFrame)
        return TrackpadMotion(mappedX, mappedY).takeUnless { it.deltaX == 0 && it.deltaY == 0 }
    }

    @Synchronized
    fun mapNaturalScroll(
        deltaX: Float,
        deltaY: Float,
    ): TrackpadScroll? {
        if (!deltaX.isFinite() || !deltaY.isFinite()) return null
        pendingScrollX += -deltaX / scrollPixelsPerStep
        pendingScrollY += -deltaY / scrollPixelsPerStep

        val rawX = pendingScrollX.toInt()
        val rawY = pendingScrollY.toInt()
        pendingScrollX -= rawX
        pendingScrollY -= rawY
        val mappedX = rawX.coerceIn(-maximumScrollStepsPerFrame, maximumScrollStepsPerFrame)
        val mappedY = rawY.coerceIn(-maximumScrollStepsPerFrame, maximumScrollStepsPerFrame)
        return TrackpadScroll(mappedX.toFloat(), mappedY.toFloat())
            .takeUnless { it.deltaX == 0f && it.deltaY == 0f }
    }

    @Synchronized
    fun reset() {
        pendingMotionX = 0f
        pendingMotionY = 0f
        pendingScrollX = 0f
        pendingScrollY = 0f
    }
}

internal data class TrackpadMotion(
    val deltaX: Int,
    val deltaY: Int,
)

internal data class TrackpadScroll(
    val deltaX: Float,
    val deltaY: Float,
)
