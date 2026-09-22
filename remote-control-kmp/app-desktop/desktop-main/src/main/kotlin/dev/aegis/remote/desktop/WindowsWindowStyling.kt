package dev.aegis.remote.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.Window

/** Keeps the native Windows frame consistent with the OLED Compose surface. */
internal fun applyDarkWindowsFrame(window: Window) {
    if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) return
    runCatching {
        val hwnd = Native.getWindowPointer(window) ?: return@runCatching
        val enabled = IntByReference(1)
        val result = DwmApi.INSTANCE.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, enabled, Int.SIZE_BYTES)
        if (result != 0) {
            DwmApi.INSTANCE.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY, enabled, Int.SIZE_BYTES)
        }
        setDwmColor(hwnd, DWMWA_CAPTION_COLOR, COLOR_BLACK)
        setDwmColor(hwnd, DWMWA_BORDER_COLOR, COLOR_DARK_BORDER)
        setDwmColor(hwnd, DWMWA_TEXT_COLOR, COLOR_WHITE)
    }
}

private fun setDwmColor(
    hwnd: Pointer,
    attribute: Int,
    colorRef: Int,
) {
    DwmApi.INSTANCE.DwmSetWindowAttribute(hwnd, attribute, IntByReference(colorRef), Int.SIZE_BYTES)
}

private interface DwmApi : Library {
    fun DwmSetWindowAttribute(
        hwnd: Pointer,
        attribute: Int,
        value: IntByReference,
        valueSize: Int,
    ): Int

    companion object {
        val INSTANCE: DwmApi = Native.load("dwmapi", DwmApi::class.java)
    }
}

private const val DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY = 19
private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_BORDER_COLOR = 34
private const val DWMWA_CAPTION_COLOR = 35
private const val DWMWA_TEXT_COLOR = 36
private const val COLOR_BLACK = 0x00000000
private const val COLOR_DARK_BORDER = 0x001B1B1B
private const val COLOR_WHITE = 0x00FFFFFF
