package dev.aegis.remote.desktop.agent

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AppScopedSshGatewayTest {
    @Test
    fun `exiting closes active streams and refuses new connections while backend stays local`() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { backend ->
            thread(isDaemon = true) {
                backend.accept().use { socket ->
                    runCatching {
                        while (true) {
                            val byte = socket.getInputStream().read()
                            if (byte == -1) break
                            socket.getOutputStream().write(byte)
                        }
                    }
                }
            }
            AppScopedSshGateway { listOf(InetAddress.getByName("127.0.0.2")) }.use { gateway ->
                gateway.start(backend.localPort)
                Socket("127.0.0.2", backend.localPort).use { client ->
                    client.soTimeout = 2_000
                    client.getOutputStream().write(42)
                    assertEquals(42, client.getInputStream().read())
                    gateway.close()
                    assertStreamClosed(client)
                    assertFailsWith<java.net.ConnectException> {
                        Socket().use { it.connect(InetSocketAddress("127.0.0.2", backend.localPort), 1_000) }
                    }
                    assertFalse(backend.isClosed)
                }
            }
        }
    }

    @Test
    fun `permission changes interrupt already authenticated connections`() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { backend ->
            AppScopedSshGateway { listOf(InetAddress.getByName("127.0.0.2")) }.use { gateway ->
                gateway.start(backend.localPort)
                Socket("127.0.0.2", backend.localPort).use { client ->
                    backend.soTimeout = 2_000
                    backend.accept().use { local ->
                        client.soTimeout = 2_000
                        local.getOutputStream().write(7)
                        assertEquals(7, client.getInputStream().read())
                        gateway.disconnectSessions()
                        assertStreamClosed(client)
                    }
                }
            }
        }
    }

    @Test
    fun `a failed startup closes all partially opened listeners`() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { backend ->
            ServerSocket(backend.localPort, 8, InetAddress.getByName("127.0.0.3")).use {
                AppScopedSshGateway { listOf(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.3")) }.use { gateway ->
                    assertFailsWith<IllegalStateException> { gateway.start(backend.localPort) }
                    assertFailsWith<java.net.ConnectException> {
                        Socket().use { it.connect(InetSocketAddress("127.0.0.2", backend.localPort), 1_000) }
                    }
                }
            }
        }
    }

    private fun assertStreamClosed(client: Socket) {
        val result =
            try {
                client.getInputStream().read()
            } catch (_: SocketException) {
                -1 // A reset is also a closed stream; a read timeout must still fail the test.
            }
        assertEquals(-1, result)
    }
}
