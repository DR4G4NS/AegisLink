package dev.aegis.remote.desktop

import com.sun.jna.Library
import com.sun.jna.Native

/**
 * Process-level tweaks that must land before AWT loads its X11 toolkit. They only matter
 * on Linux and are no-ops elsewhere.
 */
internal object LinuxDesktopEnvironment {
    private interface LibC : Library {
        fun setenv(
            name: String,
            value: String,
            overwrite: Int,
        ): Int
    }

    fun prepare(
        osName: String = System.getProperty("os.name", ""),
        environment: Map<String, String> = System.getenv(),
        setEnvironment: (String, String) -> Unit = ::nativeSetenv,
        setProperty: (String, String) -> Unit = { key, value -> System.setProperty(key, value) },
    ) {
        if (!osName.contains("Linux", ignoreCase = true)) return
        requiredEnvironment(environment).forEach { (name, value) -> runCatching { setEnvironment(name, value) } }
        // AWT ignores fontconfig hinting unless asked, which makes Inter-like UI text look jagged
        // under XWayland.
        setProperty("awt.useSystemAAFontSettings", "on")
        setProperty("swing.aatext", "true")
    }

    /**
     * Non-reparenting window managers (Hyprland and sway through XWayland, i3, bspwm, dwm…)
     * leave AWT windows grey unless the toolkit is told not to expect a reparenting WM.
     */
    internal fun requiredEnvironment(environment: Map<String, String>): Map<String, String> =
        buildMap {
            if (environment["_JAVA_AWT_WM_NONREPARENTING"].isNullOrBlank()) {
                put("_JAVA_AWT_WM_NONREPARENTING", "1")
            }
        }

    private fun nativeSetenv(
        name: String,
        value: String,
    ) {
        Native.load("c", LibC::class.java).setenv(name, value, 1)
    }
}
