package dev.aegis.remote.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Density
import java.util.prefs.Preferences

internal const val DESKTOP_ZOOM_MIN_PERCENT = 80
internal const val DESKTOP_ZOOM_MAX_PERCENT = 200
internal const val DESKTOP_ZOOM_STEP_PERCENT = 10
internal const val DESKTOP_ZOOM_DEFAULT_PERCENT = 100

internal class DesktopZoomPreferences(
    private val preferences: Preferences =
        Preferences.userRoot().node("dev/aegis/remote/desktop/appearance"),
) {
    fun load(): Int =
        preferences
            .get(ZOOM_PERCENT_KEY, null)
            ?.toIntOrNull()
            ?.takeIf(::isStoredZoomPercent)
            ?: DESKTOP_ZOOM_DEFAULT_PERCENT

    fun save(percent: Int): Int {
        val normalized = clampZoomPercent(percent)
        preferences.put(ZOOM_PERCENT_KEY, normalized.toString())
        runCatching { preferences.flush() }
        return normalized
    }

    fun applyAction(
        currentPercent: Int,
        action: DesktopZoomAction,
    ): Int =
        save(
            when (action) {
                DesktopZoomAction.Increase -> increase(currentPercent)
                DesktopZoomAction.Decrease -> decrease(currentPercent)
                DesktopZoomAction.Reset -> reset()
            },
        )

    companion object {
        private const val ZOOM_PERCENT_KEY = "zoomPercent"

        fun increase(percent: Int): Int = clampZoomPercent(percent + DESKTOP_ZOOM_STEP_PERCENT)

        fun decrease(percent: Int): Int = clampZoomPercent(percent - DESKTOP_ZOOM_STEP_PERCENT)

        fun reset(): Int = DESKTOP_ZOOM_DEFAULT_PERCENT

        fun effectiveDensity(
            baseDensity: Density,
            percent: Int,
        ): Density {
            val scale = clampZoomPercent(percent) / 100f
            return Density(
                density = baseDensity.density * scale,
                fontScale = baseDensity.fontScale * scale,
            )
        }
    }
}

internal enum class DesktopZoomShortcutKey {
    Plus,
    Equals,
    Minus,
    Zero,
    Other,
}

internal enum class DesktopZoomAction {
    Increase,
    Decrease,
    Reset,
}

internal fun desktopZoomActionForShortcut(
    key: DesktopZoomShortcutKey,
    ctrlPressed: Boolean,
    metaPressed: Boolean,
    isMac: Boolean,
): DesktopZoomAction? {
    if (!ctrlPressed && !(isMac && metaPressed)) return null
    return when (key) {
        DesktopZoomShortcutKey.Plus, DesktopZoomShortcutKey.Equals -> DesktopZoomAction.Increase
        DesktopZoomShortcutKey.Minus -> DesktopZoomAction.Decrease
        DesktopZoomShortcutKey.Zero -> DesktopZoomAction.Reset
        DesktopZoomShortcutKey.Other -> null
    }
}

internal fun desktopZoomShortcutKey(key: Key): DesktopZoomShortcutKey =
    when (key) {
        Key.Plus, Key.NumPadAdd -> DesktopZoomShortcutKey.Plus
        Key.Equals, Key.NumPadEquals -> DesktopZoomShortcutKey.Equals
        Key.Minus, Key.NumPadSubtract -> DesktopZoomShortcutKey.Minus
        Key.Zero, Key.NumPad0 -> DesktopZoomShortcutKey.Zero
        else -> DesktopZoomShortcutKey.Other
    }

internal fun clampZoomPercent(percent: Int): Int = percent.coerceIn(DESKTOP_ZOOM_MIN_PERCENT, DESKTOP_ZOOM_MAX_PERCENT)

private fun isStoredZoomPercent(percent: Int): Boolean =
    percent in DESKTOP_ZOOM_MIN_PERCENT..DESKTOP_ZOOM_MAX_PERCENT &&
        (percent - DESKTOP_ZOOM_MIN_PERCENT) % DESKTOP_ZOOM_STEP_PERCENT == 0
