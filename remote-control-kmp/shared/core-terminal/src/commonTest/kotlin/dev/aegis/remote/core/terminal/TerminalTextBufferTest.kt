package dev.aegis.remote.core.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TerminalTextBufferTest {
    private val buffer = TerminalTextBuffer()

    @Test
    fun consumesSgrAndOscSequences() {
        val raw = "\u001B]0;secret title\u0007\u001B[32mgreen\u001B[0m text"

        assertEquals("green text", buffer.render(raw))
    }

    @Test
    fun appliesCarriageReturnBackspaceAndEraseLine() {
        val raw = "Progress 10%\rProgress 90%\nabc\bD\u001B[K"

        assertEquals("Progress 90%\nabD", buffer.render(raw))
    }

    @Test
    fun appliesCursorPositionAndDisplayClear() {
        val raw = "old\u001B[2J\u001B[2;3Hok"

        assertEquals("\n  ok", buffer.render(raw))
    }

    @Test
    fun restoresPrimaryTextAfterAlternateScreen() {
        val raw = "shell prompt\u001B[?1049hfull screen app\u001B[?1049l\nready"

        assertEquals("shell prompt\nready", buffer.render(raw))
    }

    @Test
    fun rendersTheActiveAlternateScreen() {
        val raw = "shell prompt\u001B[?1049hfull screen app"

        assertEquals("full screen app", buffer.render(raw))
    }

    @Test
    fun limitsSelectableScrollback() {
        val raw = (1..5).joinToString("\n") { "line$it" }

        assertEquals("line3\nline4\nline5", TerminalTextBuffer(maxLines = 3).render(raw))
    }

    @Test
    fun boundsHostileCursorCoordinates() {
        val rendered =
            TerminalTextBuffer(maxLines = 3)
                .render("\u001B[2147483647;2147483647Hsafe")

        assertEquals(3, rendered.lines().size)
        assertEquals(4_096, rendered.lines().last().length)
    }

    @Test
    fun exposesXtermCompatibleSpecialKeyBytes() {
        assertContentEquals(byteArrayOf(0x03), TerminalKeyStroke.Interrupt.bytes())
        assertContentEquals("\u001B[A".encodeToByteArray(), TerminalKeyStroke.ArrowUp.bytes())
        assertContentEquals("\r".encodeToByteArray(), TerminalKeyStroke.Enter.bytes())
        assertContentEquals(byteArrayOf(0x7F), TerminalKeyStroke.Backspace.bytes())
    }
}
