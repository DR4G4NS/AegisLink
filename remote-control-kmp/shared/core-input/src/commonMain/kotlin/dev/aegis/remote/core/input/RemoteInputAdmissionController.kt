package dev.aegis.remote.core.input

import dev.aegis.remote.core.model.MonitorInfo
import kotlin.math.abs

data class RemoteInputLimits(
    val rateWindowMillis: Long = 1_000L,
    val maxPointerEventsPerWindow: Int = 180,
    val maxDiscreteEventsPerWindow: Int = 120,
    val maxTextBytes: Int = 4 * 1_024,
    val maxShortcutKeys: Int = 8,
    val maxRelativeDelta: Int = 32_767,
    val maxScrollDelta: Float = 100f,
)

sealed interface RemoteInputAdmissionDecision {
    data object Allowed : RemoteInputAdmissionDecision

    /** High-rate pointer samples may be dropped without closing the session. */
    data class DropCoalescable(
        val code: String,
        val reason: String,
    ) : RemoteInputAdmissionDecision

    data class Rejected(
        val code: String,
        val reason: String,
    ) : RemoteInputAdmissionDecision
}

/**
 * Per-session structural validation and fixed-window admission control. The
 * DataChannel provides a bounded queue; this controller additionally prevents
 * a valid peer from monopolising the Windows input thread.
 */
class RemoteInputAdmissionController(
    private val limits: RemoteInputLimits = RemoteInputLimits(),
    private val clock: () -> Long,
) {
    private var windowStartedAt = clock()
    private var pointerEvents = 0
    private var discreteEvents = 0

    init {
        require(limits.rateWindowMillis > 0)
        require(limits.maxPointerEventsPerWindow > 0)
        require(limits.maxDiscreteEventsPerWindow > 0)
        require(limits.maxTextBytes > 0)
        require(limits.maxShortcutKeys > 0)
        require(limits.maxRelativeDelta > 0)
        require(limits.maxScrollDelta > 0f)
    }

    fun evaluate(event: RemoteInputEvent): RemoteInputAdmissionDecision {
        validate(event)?.let { return it }
        rotateWindowIfNeeded()
        return if (event.isCoalescablePointerEvent()) {
            pointerEvents += 1
            if (pointerEvents > limits.maxPointerEventsPerWindow) {
                RemoteInputAdmissionDecision.DropCoalescable(
                    code = INPUT_RATE_LIMITED_CODE,
                    reason = "Pointer event rate exceeded the per-session limit",
                )
            } else {
                RemoteInputAdmissionDecision.Allowed
            }
        } else {
            discreteEvents += 1
            if (discreteEvents > limits.maxDiscreteEventsPerWindow) {
                RemoteInputAdmissionDecision.Rejected(
                    code = INPUT_RATE_LIMITED_CODE,
                    reason = "Discrete input event rate exceeded the per-session limit",
                )
            } else {
                RemoteInputAdmissionDecision.Allowed
            }
        }
    }

    private fun validate(event: RemoteInputEvent): RemoteInputAdmissionDecision.Rejected? {
        val reason =
            when (event) {
                is RemoteInputEvent.MouseMove -> {
                    null
                }

                is RemoteInputEvent.MouseMoveRelative -> {
                    if (abs(event.deltaX.toLong()) > limits.maxRelativeDelta ||
                        abs(event.deltaY.toLong()) > limits.maxRelativeDelta
                    ) {
                        "Relative pointer delta exceeds the accepted range"
                    } else {
                        null
                    }
                }

                is RemoteInputEvent.MouseButton -> {
                    null
                }

                is RemoteInputEvent.Scroll -> {
                    if (!event.deltaX.isFinite() || !event.deltaY.isFinite()) {
                        "Scroll delta must be finite"
                    } else if (abs(event.deltaX) > limits.maxScrollDelta || abs(event.deltaY) > limits.maxScrollDelta) {
                        "Scroll delta exceeds the accepted range"
                    } else {
                        null
                    }
                }

                is RemoteInputEvent.Key -> {
                    when {
                        event.code == KeyCode.Character -> {
                            "Character keys must use Unicode TextInput"
                        }

                        event.scanCode != null && event.scanCode !in MIN_SCAN_CODE..MAX_SCAN_CODE -> {
                            "Physical scan code is outside the Windows input range"
                        }

                        event.sourceLayoutId != null && event.sourceLayoutId.length > MAX_LAYOUT_ID_LENGTH -> {
                            "Keyboard layout identifier is too long"
                        }

                        else -> {
                            null
                        }
                    }
                }

                is RemoteInputEvent.TextInput -> {
                    when {
                        event.text.isEmpty() -> "Text input must not be empty"
                        event.text.encodeToByteArray().size > limits.maxTextBytes -> "Text input exceeds the per-event byte limit"
                        else -> null
                    }
                }

                is RemoteInputEvent.Shortcut -> {
                    when {
                        event.keys.isEmpty() -> "Shortcut must contain at least one key"
                        event.keys.size > limits.maxShortcutKeys -> "Shortcut contains too many keys"
                        event.keys.any { it == KeyCode.Character } -> "Shortcut character keys must use explicit logical keys"
                        else -> null
                    }
                }

                is RemoteInputEvent.ClipboardSync,
                is RemoteInputEvent.SelectMonitor,
                is RemoteInputEvent.SetQuality,
                -> {
                    null
                }
            }
        return reason?.let { RemoteInputAdmissionDecision.Rejected(INPUT_INVALID_EVENT_CODE, it) }
    }

    private fun rotateWindowIfNeeded() {
        val now = clock()
        if (now < windowStartedAt || now - windowStartedAt >= limits.rateWindowMillis) {
            windowStartedAt = now
            pointerEvents = 0
            discreteEvents = 0
        }
    }
}

fun RemoteInputEvent.MouseMove.isInside(monitor: MonitorInfo): Boolean {
    if (monitor.width <= 0 || monitor.height <= 0 || monitor.id != monitorId) return false
    val maxX = monitor.originX.toLong() + monitor.width - 1L
    val maxY = monitor.originY.toLong() + monitor.height - 1L
    return x.toLong() in monitor.originX.toLong()..maxX && y.toLong() in monitor.originY.toLong()..maxY
}

private fun RemoteInputEvent.isCoalescablePointerEvent(): Boolean = this is RemoteInputEvent.MouseMove || this is RemoteInputEvent.MouseMoveRelative || this is RemoteInputEvent.Scroll

const val INPUT_INVALID_EVENT_CODE = "INP-1003"
const val INPUT_RATE_LIMITED_CODE = "INP-1004"

private const val MIN_SCAN_CODE = 1
private const val MAX_SCAN_CODE = 0xffff
private const val MAX_LAYOUT_ID_LENGTH = 64
