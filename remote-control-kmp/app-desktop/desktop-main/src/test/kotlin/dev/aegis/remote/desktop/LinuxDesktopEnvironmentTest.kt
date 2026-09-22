package dev.aegis.remote.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxDesktopEnvironmentTest {
    @Test
    fun nonReparentingHintIsAddedOnlyWhenMissing() {
        assertEquals(
            mapOf("_JAVA_AWT_WM_NONREPARENTING" to "1"),
            LinuxDesktopEnvironment.requiredEnvironment(emptyMap()),
        )
        assertTrue(LinuxDesktopEnvironment.requiredEnvironment(mapOf("_JAVA_AWT_WM_NONREPARENTING" to "0")).isEmpty())
    }

    @Test
    fun prepareIsNoOpOutsideLinux() {
        val applied = mutableListOf<String>()
        LinuxDesktopEnvironment.prepare(
            osName = "Windows 11",
            environment = emptyMap(),
            setEnvironment = { name, _ -> applied += name },
            setProperty = { key, _ -> applied += key },
        )
        assertTrue(applied.isEmpty())
    }

    @Test
    fun prepareAppliesEnvironmentAndFontHintsOnLinux() {
        val environment = mutableMapOf<String, String>()
        val properties = mutableMapOf<String, String>()
        LinuxDesktopEnvironment.prepare(
            osName = "Linux",
            environment = emptyMap(),
            setEnvironment = { name, value -> environment[name] = value },
            setProperty = { key, value -> properties[key] = value },
        )
        assertEquals("1", environment["_JAVA_AWT_WM_NONREPARENTING"])
        assertEquals("on", properties["awt.useSystemAAFontSettings"])
    }
}
