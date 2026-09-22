package dev.aegis.remote.android.clipboard

import dev.aegis.remote.core.clipboard.ClipboardSyncDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AndroidClipboardSyncGateTest {
    private val gate = AndroidClipboardSyncGate()

    @Test
    fun allowsManualAndroidToDesktopClipboardTextAfterUserAction() {
        assertEquals(
            ClipboardSyncDecision.Allowed,
            gate.evaluateManualAndroidToDesktop("copy this to desktop"),
        )
    }

    @Test
    fun blocksManualAndroidToDesktopClipboardTextThatLooksSensitive() {
        val decision = gate.evaluateManualAndroidToDesktop("access_token=secret")

        assertIs<ClipboardSyncDecision.Blocked>(decision)
    }

    @Test
    fun allowsAutomaticDesktopToAndroidClipboardTextWhenSafe() {
        assertEquals(
            ClipboardSyncDecision.Allowed,
            gate.evaluateAutomaticDesktopToAndroid("desktop text"),
        )
    }

    @Test
    fun allowsAutomaticAndroidToDesktopClipboardTextWhenSafe() {
        assertEquals(
            ClipboardSyncDecision.Allowed,
            gate.evaluateAutomaticAndroidToDesktop("phone text"),
        )
    }

    @Test
    fun blocksAutomaticDesktopToAndroidClipboardTextThatLooksSensitive() {
        val decision = gate.evaluateAutomaticDesktopToAndroid("-----BEGIN OPENSSH PRIVATE KEY-----")

        assertIs<ClipboardSyncDecision.Blocked>(decision)
    }

    @Test
    fun blocksAutomaticAndroidToDesktopClipboardTextThatLooksSensitive() {
        val decision = gate.evaluateAutomaticAndroidToDesktop("access_token=secret")

        assertIs<ClipboardSyncDecision.Blocked>(decision)
    }

    @Test
    fun blocksBlankAutomaticDesktopToAndroidClipboardText() {
        val decision = gate.evaluateAutomaticDesktopToAndroid("")

        assertIs<ClipboardSyncDecision.Blocked>(decision)
    }
}
