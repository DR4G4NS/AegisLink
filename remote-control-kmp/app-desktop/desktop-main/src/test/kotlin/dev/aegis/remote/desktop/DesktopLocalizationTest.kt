package dev.aegis.remote.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopLocalizationTest {
    @Test
    fun spanishAndEnglishCatalogsShareTheSameKeys() {
        assertEquals(desktopSpanishCopyKeys(), desktopEnglishCopyKeys())
        assertTrue(desktopSpanishCopyKeys().isNotEmpty())
    }

    @Test
    fun everyKeyResolvesToATranslatedValue() {
        desktopSpanishCopyKeys().forEach { key ->
            assertNotEquals(key, DesktopLanguage.Spanish.text(key), "missing Spanish copy for $key")
            assertNotEquals(key, DesktopLanguage.English.text(key), "missing English copy for $key")
        }
    }

    @Test
    fun newUserFacingCopyIsTranslatedInBothLanguages() {
        assertEquals("Acceso remoto", DesktopLanguage.Spanish.text("settings.nav.access"))
        assertEquals("Remote access", DesktopLanguage.English.text("settings.nav.access"))
        assertEquals("Cortafuegos de la red local", DesktopLanguage.Spanish.text("ufw.title"))
        assertEquals("Local network firewall", DesktopLanguage.English.text("ufw.title"))
        assertEquals("Hay que recuperar el almacén de confianza", DesktopLanguage.Spanish.text("trust.recovery.title"))
        assertEquals("Trust store recovery is required", DesktopLanguage.English.text("trust.recovery.title"))
        assertEquals("El QR caduca en %d s y se renueva solo.", DesktopLanguage.Spanish.text("pair.qr.expires"))
        assertEquals("The QR expires in %d s and renews automatically.", DesktopLanguage.English.text("pair.qr.expires"))
        assertNotEquals(
            DesktopLanguage.Spanish.text("approval.permissions"),
            DesktopLanguage.English.text("approval.permissions"),
        )
        assertEquals("Degradado", DesktopLanguage.Spanish.text("status.degraded"))
        assertEquals("Degraded", DesktopLanguage.English.text("status.degraded"))
        assertEquals("Esta red está marcada como Pública", DesktopLanguage.Spanish.text("home.network.public.title"))
        assertEquals("This network is marked Public", DesktopLanguage.English.text("home.network.public.title"))
        assertEquals("Comprobación de Windows", DesktopLanguage.Spanish.text("preflight.windows"))
        assertEquals("Windows check", DesktopLanguage.English.text("preflight.windows"))
    }
}
