package dev.aegis.remote.android.clipboard

import dev.aegis.remote.core.clipboard.ClipboardSyncDecision
import dev.aegis.remote.core.clipboard.ClipboardSyncDirection
import dev.aegis.remote.core.clipboard.ClipboardSyncPolicy
import dev.aegis.remote.core.clipboard.ClipboardSyncPolicyEngine
import dev.aegis.remote.core.clipboard.ClipboardSyncRequest
import dev.aegis.remote.core.model.ClipboardPayload

class AndroidClipboardSyncGate(
    private val policyEngine: ClipboardSyncPolicyEngine = ClipboardSyncPolicyEngine(),
) {
    fun evaluateManualAndroidToDesktop(text: String): ClipboardSyncDecision =
        policyEngine.evaluate(
            ClipboardSyncRequest(
                policy = ClipboardSyncPolicy.Manual,
                direction = ClipboardSyncDirection.AndroidToDesktop,
                payload = ClipboardPayload.Text(text),
                userConfirmed = true,
            ),
        )

    fun evaluateAutomaticAndroidToDesktop(text: String): ClipboardSyncDecision =
        policyEngine.evaluate(
            ClipboardSyncRequest(
                policy = ClipboardSyncPolicy.Automatic,
                direction = ClipboardSyncDirection.AndroidToDesktop,
                payload = ClipboardPayload.Text(text),
                userConfirmed = false,
            ),
        )

    fun evaluateAutomaticDesktopToAndroid(text: String): ClipboardSyncDecision =
        policyEngine.evaluate(
            ClipboardSyncRequest(
                policy = ClipboardSyncPolicy.Automatic,
                direction = ClipboardSyncDirection.DesktopToAndroid,
                payload = ClipboardPayload.Text(text),
                userConfirmed = false,
            ),
        )
}
