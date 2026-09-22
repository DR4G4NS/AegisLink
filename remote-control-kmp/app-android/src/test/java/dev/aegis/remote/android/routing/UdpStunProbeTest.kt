package dev.aegis.remote.android.routing

import dev.aegis.remote.core.model.NatTraversalState
import kotlinx.coroutines.test.runTest
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UdpStunProbeTest {
    @Test
    fun reportsDirectPossibleWhenBindingResponseMatchesTransaction() =
        runTest {
            val server = FakeStunServer()
            server.start()

            val result = UdpStunProbe(timeoutMillis = 1_000).check(listOf("stun:127.0.0.1:${server.port}"))

            assertEquals(NatTraversalState.DirectPossible, result.state)
            assertTrue(result.reason.orEmpty().contains("127.0.0.1:${server.port}"))
            server.close()
        }

    @Test
    fun reportsTurnRequiredWhenServerDoesNotRespond() =
        runTest {
            DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { reserved ->
                val result = UdpStunProbe(timeoutMillis = 50).check(listOf("stun:127.0.0.1:${reserved.localPort}"))

                assertEquals(NatTraversalState.TurnRequired, result.state)
                assertTrue(result.reason.orEmpty().contains("No STUN binding response"))
            }
        }

    @Test
    fun rejectsInvalidStunUrls() =
        runTest {
            val result = UdpStunProbe(timeoutMillis = 50).check(listOf("https://example.test"))

            assertEquals(NatTraversalState.Blocked, result.state)
            assertEquals("No valid STUN URLs are configured", result.reason)
        }
}

private class FakeStunServer {
    private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    val port: Int = socket.localPort
    private lateinit var thread: Thread

    fun start() {
        thread =
            Thread {
                val request = ByteArray(512)
                val packet = DatagramPacket(request, request.size)
                socket.receive(packet)
                val response = ByteArray(20)
                response[0] = 0x01
                response[1] = 0x01
                response[4] = 0x21
                response[5] = 0x12
                response[6] = 0xA4.toByte()
                response[7] = 0x42
                request.copyInto(response, destinationOffset = 8, startIndex = 8, endIndex = 20)
                socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
            }
        thread.isDaemon = true
        thread.start()
    }

    fun close() {
        socket.close()
        thread.join(1_000)
    }
}
