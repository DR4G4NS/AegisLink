package dev.aegis.remote.desktop.agent

import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AppScopedSshGatewayTest {
    @Test
    fun `exiting closes active streams and refuses new access while backend stays local`() {
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
                    assertNewConnectionClosed(backend.localPort)
                    assertNoPendingBackendConnection(backend)
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
                    assertPermissionChangeDisconnects(backend, gateway, client)
                }
            }
        }
    }

    private fun assertPermissionChangeDisconnects(
        backend: ServerSocket,
        gateway: AppScopedSshGateway,
        client: Socket,
    ) {
        backend.soTimeout = 2_000
        backend.accept().use { local ->
            client.soTimeout = 2_000
            local.getOutputStream().write(7)
            assertEquals(7, client.getInputStream().read())
            gateway.disconnectSessions()
            assertStreamClosed(client)
        }
    }

    @Test
    fun `a failed startup closes all partially opened listeners`() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { backend ->
            ServerSocket(backend.localPort, 8, InetAddress.getByName("127.0.0.3")).use {
                AppScopedSshGateway { listOf(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.3")) }.use { gateway ->
                    assertFailsWith<IllegalStateException> { gateway.start(backend.localPort) }
                    assertNewConnectionClosed(backend.localPort)
                    assertNoPendingBackendConnection(backend)
                }
            }
        }
    }

    private fun assertNewConnectionClosed(port: Int) {
        Socket().use { client ->
            try {
                client.connect(InetSocketAddress("127.0.0.2", port), 1_000)
            } catch (_: ConnectException) {
                return
            }
            // A queued TCP handshake may finish while another thread exits accept().
            // It must yield EOF/reset within the bound; data or a read timeout is a failure.
            client.soTimeout = 2_000
            assertStreamClosed(client)
        }
    }

    private fun assertNoPendingBackendConnection(backend: ServerSocket) {
        backend.soTimeout = 200
        assertFailsWith<SocketTimeoutException> { backend.accept().use { } }
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
