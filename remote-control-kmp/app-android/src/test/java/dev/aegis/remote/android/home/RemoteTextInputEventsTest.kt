package dev.aegis.remote.android.home

import dev.aegis.remote.core.input.KeyCode
import dev.aegis.remote.core.input.RemoteInputEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RemoteTextInputEventsTest {
    @Test
    fun splitsNewlinesIntoEnterKeyTaps() {
        val events = "hi\nthere".toRemoteTextInputEvents()

        assertEquals(4, events.size)
        assertEquals("hi", assertIs<RemoteInputEvent.TextInput>(events[0]).text)
        assertEquals(KeyCode.Enter, assertIs<RemoteInputEvent.Key>(events[1]).code)
        assertEquals(true, assertIs<RemoteInputEvent.Key>(events[1]).pressed)
        assertEquals(false, assertIs<RemoteInputEvent.Key>(events[2]).pressed)
        assertEquals("there", assertIs<RemoteInputEvent.TextInput>(events[3]).text)
    }
}
