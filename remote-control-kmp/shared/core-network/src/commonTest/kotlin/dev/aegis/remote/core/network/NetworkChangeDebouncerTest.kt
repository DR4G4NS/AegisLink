package dev.aegis.remote.core.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkChangeDebouncerTest {
    @Test
    fun initializesWithoutEmittingAndDebouncesBursts() {
        val debouncer = NetworkChangeDebouncer(debounceMillis = 400)
        assertFalse(debouncer.shouldEmit("wifi|validated=true|vpn=false|10.0.0.2", 1_000, online = true))
        assertFalse(debouncer.shouldEmit("cell|validated=true|vpn=false|10.1.0.2", 1_200, online = true))
        assertTrue(debouncer.shouldEmit("cell|validated=true|vpn=false|10.1.0.2", 1_500, online = true))
    }

    @Test
    fun offlineDoesNotEmit() {
        val debouncer = NetworkChangeDebouncer(debounceMillis = 0)
        debouncer.shouldEmit("wifi", 1_000, online = true)
        assertFalse(debouncer.shouldEmit("", 2_000, online = false))
    }
}
