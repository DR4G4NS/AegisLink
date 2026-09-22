package dev.aegis.remote.android.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidStringCatalogTest {
    @Test
    fun spanishAndEnglishStringCatalogsShareTheSameKeys() {
        val spanish = catalogKeys("values/strings.xml")
        val english = catalogKeys("values-en/strings.xml")
        assertEquals(emptySet(), spanish - english, "missing English translations")
        assertEquals(emptySet(), english - spanish, "missing Spanish translations")
        assertTrue(spanish.size >= 320, "expected a full Android catalog, found ${spanish.size} keys")
    }

    @Test
    fun userFacingConnectionCopyExistsInBothCatalogs() {
        val spanish = catalog("values/strings.xml")
        val english = catalog("values-en/strings.xml")
        assertEquals("En directo", spanish.getValue("visual_streaming"))
        assertEquals("Live", english.getValue("visual_streaming"))
        assertEquals("La conexión se está recuperando…", spanish.getValue("status_recovering"))
        assertEquals("The connection is recovering…", english.getValue("status_recovering"))
        assertEquals("Esta versión de Aegis no coincide con la del PC. Actualiza ambas aplicaciones.", spanish.getValue("status_protocol_mismatch"))
        assertEquals("This Aegis version does not match the PC. Update both apps.", english.getValue("status_protocol_mismatch"))
        assertEquals("Sesión de Aegis activa", spanish.getValue("session_notification_title"))
        assertEquals("Aegis session active", english.getValue("session_notification_title"))
        assertEquals("Más fluidez", spanish.getValue("quality_low_latency"))
        assertEquals("Smoother", english.getValue("quality_low_latency"))
    }

    private fun catalogKeys(relative: String): Set<String> = catalog(relative).keys

    private fun catalog(relative: String): Map<String, String> {
        val xml = catalogFile(relative).readText()
        val matches = STRING_NAME.findAll(xml)
        return matches.associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    private fun catalogFile(relative: String): File {
        val candidates =
            listOf(
                File("src/main/res", relative),
                File("app-android/src/main/res", relative),
                File("../app-android/src/main/res", relative),
            )
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not find Android string catalog $relative")
    }

    companion object {
        private val STRING_NAME = Regex("""<string name="([^"]+)">([\s\S]*?)</string>""")
    }
}
