package dev.aegis.remote.core.terminal

/**
 * Produces a stable, selectable text view from the subset of VT/xterm output commonly emitted
 * by interactive shells. The SSH contract intentionally remains byte-oriented; this renderer is
 * the fallback scrollback surface used when a full alternate-screen terminal is not required.
 *
 * It handles cursor movement, erasing, carriage-return progress updates, OSC titles and the
 * primary/alternate screen switch. SGR colours are consumed rather than leaked as escape text.
 */
class TerminalTextBuffer(
    private val maxLines: Int = DEFAULT_MAX_LINES,
) {
    init {
        require(maxLines > 0) { "maxLines must be positive" }
    }

    fun render(raw: String): String {
        val terminal = TerminalState(maxLines)
        var index = 0
        while (index < raw.length) {
            when (val char = raw[index]) {
                ESC -> {
                    index = consumeEscape(raw, index + 1, terminal)
                }

                '\r' -> {
                    terminal.screen.cursorColumn = 0
                    index += 1
                }

                '\n' -> {
                    terminal.screen.lineFeed()
                    index += 1
                }

                '\b' -> {
                    terminal.screen.cursorColumn = (terminal.screen.cursorColumn - 1).coerceAtLeast(0)
                    index += 1
                }

                '\t' -> {
                    terminal.screen.cursorColumn =
                        ((terminal.screen.cursorColumn / TAB_WIDTH) + 1) * TAB_WIDTH
                    index += 1
                }

                in '\u0000'..'\u001F', '\u007F' -> {
                    index += 1
                }

                else -> {
                    terminal.screen.put(char)
                    index += 1
                }
            }
        }
        return terminal.screen.visibleText(maxLines)
    }

    private fun consumeEscape(
        raw: String,
        start: Int,
        terminal: TerminalState,
    ): Int {
        if (start >= raw.length) return raw.length
        return when (raw[start]) {
            '[' -> {
                consumeCsi(raw, start + 1, terminal)
            }

            ']' -> {
                consumeOsc(raw, start + 1)
            }

            '7' -> {
                terminal.screen.saveCursor()
                start + 1
            }

            '8' -> {
                terminal.screen.restoreCursor()
                start + 1
            }

            'c' -> {
                terminal.reset()
                start + 1
            }

            '(', ')', '*', '+', '-', '.', '/' -> {
                (start + 2).coerceAtMost(raw.length)
            }

            else -> {
                start + 1
            }
        }
    }

    private fun consumeOsc(
        raw: String,
        start: Int,
    ): Int {
        var index = start
        while (index < raw.length) {
            when {
                raw[index] == BEL -> {
                    return index + 1
                }

                raw[index] == ESC && index + 1 < raw.length && raw[index + 1] == '\\' -> {
                    return index + 2
                }

                else -> {
                    index += 1
                }
            }
        }
        return raw.length
    }

    private fun consumeCsi(
        raw: String,
        start: Int,
        terminal: TerminalState,
    ): Int {
        var index = start
        while (index < raw.length && raw[index].code !in CSI_FINAL_MIN..CSI_FINAL_MAX) {
            index += 1
        }
        if (index >= raw.length) return raw.length

        val body = raw.substring(start, index)
        val privateMode = body.startsWith("?")
        val parameters =
            body
                .removePrefix("?")
                .substringBeforeLast(' ', body.removePrefix("?"))
                .split(';')
                .map { it.toIntOrNull() }
        applyCsi(
            final = raw[index],
            parameters = parameters,
            privateMode = privateMode,
            terminal = terminal,
        )
        return index + 1
    }

    @Suppress("CyclomaticComplexMethod")
    private fun applyCsi(
        final: Char,
        parameters: List<Int?>,
        privateMode: Boolean,
        terminal: TerminalState,
    ) {
        val screen = terminal.screen

        fun value(
            position: Int = 0,
            default: Int = 1,
        ): Int = (parameters.getOrNull(position) ?: default).coerceIn(0, MAX_CSI_PARAMETER)

        when (final) {
            'A' -> {
                screen.cursorRow = (screen.cursorRow - value()).coerceAtLeast(0)
            }

            'B' -> {
                screen.cursorRow = (screen.cursorRow + value()).coerceAtLeast(0)
            }

            'C' -> {
                screen.cursorColumn = (screen.cursorColumn + value()).coerceAtLeast(0)
            }

            'D' -> {
                screen.cursorColumn = (screen.cursorColumn - value()).coerceAtLeast(0)
            }

            'E' -> {
                screen.cursorRow = (screen.cursorRow + value()).coerceAtLeast(0)
                screen.cursorColumn = 0
            }

            'F' -> {
                screen.cursorRow = (screen.cursorRow - value()).coerceAtLeast(0)
                screen.cursorColumn = 0
            }

            'G', '`' -> {
                screen.cursorColumn = (value() - 1).coerceAtLeast(0)
            }

            'H', 'f' -> {
                screen.cursorRow = (value(0) - 1).coerceAtLeast(0)
                screen.cursorColumn = (value(1) - 1).coerceAtLeast(0)
            }

            'J' -> {
                screen.eraseDisplay(value(default = 0))
            }

            'K' -> {
                screen.eraseLine(value(default = 0))
            }

            'P' -> {
                screen.deleteCharacters(value())
            }

            '@' -> {
                screen.insertCharacters(value())
            }

            'X' -> {
                screen.eraseCharacters(value())
            }

            's' -> {
                screen.saveCursor()
            }

            'u' -> {
                screen.restoreCursor()
            }

            'h', 'l' -> {
                if (privateMode && parameters.any { it == ALTERNATE_SCREEN_MODE || it == LEGACY_ALTERNATE_SCREEN_MODE }) {
                    terminal.useAlternate(final == 'h')
                }
            }

            // SGR, device-status reports and mode changes do not alter the selectable fallback
            // text. They are still consumed so their bytes never leak into the UI.
            'm', 'n', 'r', 't', 'q' -> {
                Unit
            }
        }
    }

    private class TerminalState(
        private val maxRows: Int,
    ) {
        var primary = Screen(maxRows, MAX_MATERIALIZED_COLUMNS)
            private set
        private var alternate = Screen(maxRows, MAX_MATERIALIZED_COLUMNS)
        var screen: Screen = primary
            private set

        fun useAlternate(enabled: Boolean) {
            screen =
                if (enabled) {
                    alternate = Screen(maxRows, MAX_MATERIALIZED_COLUMNS)
                    alternate
                } else {
                    primary
                }
        }

        fun reset() {
            primary = Screen(maxRows, MAX_MATERIALIZED_COLUMNS)
            alternate = Screen(maxRows, MAX_MATERIALIZED_COLUMNS)
            screen = primary
        }
    }

    private class Screen(
        private val maxRows: Int,
        private val maxColumns: Int,
    ) {
        private val lines = mutableListOf(mutableListOf<Char>())
        var cursorRow: Int = 0
            set(value) {
                field = value.coerceIn(0, maxRows - 1)
                ensureRow(field)
            }
        var cursorColumn: Int = 0
            set(value) {
                field = value.coerceIn(0, maxColumns - 1)
            }
        private var savedCursorRow: Int = 0
        private var savedCursorColumn: Int = 0

        fun put(char: Char) {
            ensureRow(cursorRow)
            val line = lines[cursorRow]
            while (line.size < cursorColumn) line += ' '
            if (cursorColumn == line.size) {
                line += char
            } else {
                line[cursorColumn] = char
            }
            cursorColumn += 1
        }

        fun lineFeed() {
            if (cursorRow >= maxRows - 1) {
                if (lines.size >= maxRows) lines.removeAt(0)
                lines.add(mutableListOf())
                cursorRow = lines.lastIndex
            } else {
                cursorRow += 1
            }
            cursorColumn = 0
        }

        fun saveCursor() {
            savedCursorRow = cursorRow
            savedCursorColumn = cursorColumn
        }

        fun restoreCursor() {
            cursorRow = savedCursorRow
            cursorColumn = savedCursorColumn
        }

        fun eraseDisplay(mode: Int) {
            when (mode) {
                0 -> {
                    eraseLine(0)
                    while (lines.size > cursorRow + 1) lines.removeLast()
                }

                1 -> {
                    for (row in 0 until cursorRow) lines[row].clear()
                    eraseLine(1)
                }

                2, 3 -> {
                    lines.clear()
                    lines.add(mutableListOf())
                    cursorRow = 0
                    cursorColumn = 0
                }
            }
        }

        fun eraseLine(mode: Int) {
            ensureRow(cursorRow)
            val line = lines[cursorRow]
            when (mode) {
                0 -> {
                    while (line.size > cursorColumn) line.removeLast()
                }

                1 -> {
                    ensureColumn(line, cursorColumn)
                    for (column in 0..cursorColumn.coerceAtMost(line.lastIndex)) {
                        line[column] = ' '
                    }
                }

                2 -> {
                    line.clear()
                }
            }
        }

        fun deleteCharacters(count: Int) {
            val line = lines[cursorRow]
            repeat(count.coerceIn(0, line.size)) {
                if (cursorColumn < line.size) line.removeAt(cursorColumn)
            }
        }

        fun insertCharacters(count: Int) {
            val line = lines[cursorRow]
            ensureColumn(line, cursorColumn)
            repeat(count.coerceIn(0, maxColumns - line.size)) { line.add(cursorColumn, ' ') }
        }

        fun eraseCharacters(count: Int) {
            val line = lines[cursorRow]
            val end = (cursorColumn.toLong() + count.coerceAtLeast(0)).coerceAtMost(line.size.toLong()).toInt()
            for (column in cursorColumn until end) line[column] = ' '
        }

        fun visibleText(maxLines: Int): String =
            lines
                .dropLastWhile { it.isEmpty() }
                .takeLast(maxLines)
                .joinToString("\n") { line -> line.joinToString("").trimEnd() }

        private fun ensureRow(row: Int) {
            while (lines.size <= row && lines.size < maxRows) lines.add(mutableListOf())
        }

        private fun ensureColumn(
            line: MutableList<Char>,
            column: Int,
        ) {
            val boundedColumn = column.coerceAtMost(maxColumns - 1)
            while (line.size <= boundedColumn) line += ' '
        }
    }

    private companion object {
        const val DEFAULT_MAX_LINES = 2_000
        const val MAX_MATERIALIZED_COLUMNS = 4_096
        const val MAX_CSI_PARAMETER = 4_096
        const val TAB_WIDTH = 8
        const val CSI_FINAL_MIN = 0x40
        const val CSI_FINAL_MAX = 0x7E
        const val ALTERNATE_SCREEN_MODE = 1_049
        const val LEGACY_ALTERNATE_SCREEN_MODE = 47
        const val ESC = '\u001B'
        const val BEL = '\u0007'
    }
}

/** Raw key sequences understood by the xterm-compatible PTY requested by Aegis. */
enum class TerminalKeyStroke(
    private val sequence: String,
) {
    Enter("\r"),
    Tab("\t"),
    Escape("\u001B"),
    ArrowUp("\u001B[A"),
    ArrowDown("\u001B[B"),
    ArrowRight("\u001B[C"),
    ArrowLeft("\u001B[D"),
    Home("\u001B[H"),
    End("\u001B[F"),
    Backspace("\u007F"),
    Interrupt("\u0003"),
    EndOfFile("\u0004"),
    ClearScreen("\u000C"),
    ;

    fun bytes(): ByteArray = sequence.encodeToByteArray()
}
