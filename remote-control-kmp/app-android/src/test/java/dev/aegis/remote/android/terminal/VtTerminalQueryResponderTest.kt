package dev.aegis.remote.android.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class VtTerminalQueryResponderTest {
    @Test
    fun answersPrimaryDeviceAttributesImmediately() {
        val responses = VtTerminalQueryResponder().responsesFor("before\u001B[cafter".toByteArray())

        assertEquals(1, responses.size)
        assertContentEquals("\u001B[?1;2c".toByteArray(), responses.single())
    }

    @Test
    fun recognizesQueriesSplitAcrossSshReads() {
        val responder = VtTerminalQueryResponder()

        assertEquals(0, responder.responsesFor("\u001B[".toByteArray()).size)
        val responses = responder.responsesFor("0c".toByteArray())

        assertEquals(1, responses.size)
        assertContentEquals("\u001B[?1;2c".toByteArray(), responses.single())
    }

    @Test
    fun reportsCursorPositionAfterHostOutput() {
        val responder = VtTerminalQueryResponder()

        val responses = responder.responsesFor("C:\\Users> \u001B[6n".toByteArray())

        assertEquals(1, responses.size)
        assertContentEquals("\u001B[1;11R".toByteArray(), responses.single())
    }
}
