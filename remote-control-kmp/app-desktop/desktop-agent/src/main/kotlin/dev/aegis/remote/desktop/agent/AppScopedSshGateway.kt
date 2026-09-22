package dev.aegis.remote.desktop.agent

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** The installed SSH service listens on loopback only. All network sockets belong to Aegis. */
internal class AppScopedSshGateway(
    private val addresses: () -> List<InetAddress> = {
        NetworkInterface
            .getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterNot { it.isLoopbackAddress || it.isAnyLocalAddress || it.isLinkLocalAddress }
    },
) : AutoCloseable {
    private val lock = Any()
    private val listeners = mutableMapOf<InetAddress, ServerSocket>()
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(16)
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "aegis-ssh-gateway").apply { isDaemon = true } }
    private val monitor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "aegis-ssh-addresses").apply { isDaemon = true } }
    private var closed = false
    private var started = false

    fun start(port: Int) =
        synchronized(lock) {
            check(!closed) { "SSH-7334: Aegis access has already stopped" }
            require(port in 1..65535)
            if (!started) {
                refresh(port)
                started = true
                monitor.scheduleWithFixedDelay({ runCatching { refresh(port) } }, 2, 2, TimeUnit.SECONDS)
            }
        }

    private fun refresh(port: Int) =
        synchronized(lock) {
            if (closed) return@synchronized
            val current = addresses().toSet()
            (listeners.keys - current).forEach { address -> listeners.remove(address)?.close() }
            (current - listeners.keys).forEach { address ->
                val listener = ServerSocket()
                try {
                    listener.reuseAddress = false
                    listener.bind(InetSocketAddress(address, port), 16)
                    listeners[address] = listener
                    workers.execute { accept(listener, port) }
                } catch (error: Exception) {
                    listener.close()
                    // Do not expose a partially configured gateway during initial startup.
                    if (!started) {
                        listeners.values.forEach { runCatching { it.close() } }
                        listeners.clear()
                        throw IllegalStateException("SSH-7334: Cannot open Aegis access on ${address.hostAddress}:$port", error)
                    }
                }
            }
        }

    private fun accept(
        listener: ServerSocket,
        port: Int,
    ) {
        while (!listener.isClosed) {
            val client = runCatching { listener.accept() }.getOrNull() ?: break
            if (!slots.tryAcquire()) {
                client.close()
                continue
            }
            val backend = Socket()
            synchronized(lock) {
                if (closed) {
                    client.close()
                    backend.close()
                    slots.release()
                    return
                }
                connections.add(client)
                connections.add(backend)
            }
            workers.execute {
                try {
                    backend.connect(InetSocketAddress("127.0.0.1", port), 3_000)
                    client.tcpNoDelay = true
                    backend.tcpNoDelay = true
                    workers.execute {
                        try {
                            client.getInputStream().copyTo(backend.getOutputStream())
                        } catch (_: Exception) {
                            // Disconnects close both directions, including any active SFTP transfer.
                        } finally {
                            runCatching { client.close() }
                            runCatching { backend.close() }
                        }
                    }
                    backend.getInputStream().copyTo(client.getOutputStream())
                } catch (_: Exception) {
                    // No fallback to an unguarded endpoint.
                } finally {
                    runCatching { client.close() }
                    runCatching { backend.close() }
                    connections.remove(client)
                    connections.remove(backend)
                    slots.release()
                }
            }
        }
    }

    fun disconnectSessions() =
        synchronized(lock) {
            connections.toList().forEach { runCatching { it.close() } }
        }

    override fun close() =
        synchronized(lock) {
            if (closed) return@synchronized
            closed = true
            listeners.values.forEach { runCatching { it.close() } }
            listeners.clear()
            disconnectSessions()
            monitor.shutdownNow()
            workers.shutdownNow()
            Unit
        }
}
