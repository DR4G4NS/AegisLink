package dev.aegis.remote.android.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AndroidPairingIdentityStoreTest {
    @Test
    fun fingerprintIsStableForAnInstallationSeed() {
        val seed = ByteArray(32) { index -> index.toByte() }

        assertEquals(stablePairingFingerprint(seed), stablePairingFingerprint(seed.copyOf()))
        assertNotEquals(stablePairingFingerprint(seed), stablePairingFingerprint(ByteArray(32) { 7 }))
    }

    @Test
    fun deviceNameAvoidsDuplicatingManufacturer() {
        assertEquals("Google Pixel 10", pairingDeviceDisplayName("Google", "Pixel 10"))
        assertEquals("Samsung Galaxy S26", pairingDeviceDisplayName("Samsung", "Samsung Galaxy S26"))
        assertEquals("Android device", pairingDeviceDisplayName("", ""))
    }
}
