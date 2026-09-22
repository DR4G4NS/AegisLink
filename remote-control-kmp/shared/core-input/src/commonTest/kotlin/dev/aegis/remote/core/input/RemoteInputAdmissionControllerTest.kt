package dev.aegis.remote.core.input

import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.MonitorInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RemoteInputAdmissionControllerTest {
    @Test
    fun dropsExcessPointerSamplesWithoutRejectingLaterWindow() {
        var now = 1_000L
        val controller =
            RemoteInputAdmissionController(
                limits = RemoteInputLimits(maxPointerEventsPerWindow = 2),
                clock = { now },
            )

        assertIs<RemoteInputAdmissionDecision.Allowed>(controller.evaluate(RemoteInputEvent.MouseMoveRelative(1, 1)))
        assertIs<RemoteInputAdmissionDecision.Allowed>(controller.evaluate(RemoteInputEvent.Scroll(0f, 1f)))
        assertIs<RemoteInputAdmissionDecision.DropCoalescable>(controller.evaluate(RemoteInputEvent.MouseMoveRelative(1, 1)))

        now += 1_000L
        assertIs<RemoteInputAdmissionDecision.Allowed>(controller.evaluate(RemoteInputEvent.MouseMoveRelative(1, 1)))
    }

    @Test
    fun rejectsOversizedTextAndInvalidPhysicalScanCode() {
        val controller =
            RemoteInputAdmissionController(
                limits = RemoteInputLimits(maxTextBytes = 4),
                clock = { 0L },
            )

        assertIs<RemoteInputAdmissionDecision.Rejected>(controller.evaluate(RemoteInputEvent.TextInput("ééé")))
        assertIs<RemoteInputAdmissionDecision.Rejected>(
            controller.evaluate(RemoteInputEvent.Key(KeyCode.Enter, pressed = true, scanCode = 0)),
        )
    }

    @Test
    fun validatesAbsoluteCoordinatesAgainstNegativeOriginMonitor() {
        val monitor = MonitorInfo(MonitorId("left"), "Left", width = 1_920, height = 1_080, originX = -1_920)

        assertTrue(RemoteInputEvent.MouseMove(-1_920, 0, monitor.id).isInside(monitor))
        assertTrue(RemoteInputEvent.MouseMove(-1, 1_079, monitor.id).isInside(monitor))
        assertFalse(RemoteInputEvent.MouseMove(0, 1_079, monitor.id).isInside(monitor))
    }
}
