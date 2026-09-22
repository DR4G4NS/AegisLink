package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.AdvertisedEndpointKind
import dev.aegis.remote.core.pairing.LanAnnouncePayload
import dev.aegis.remote.core.pairing.LanRediscoveryPolicy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.StandardSocketOptions
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Announces an already-pinned host with one UDP multicast socket per network
 * interface. Identity is already trusted; this only rediscovers the host after
 * a DHCP or VPN address change.
 */
class LanMulticastAnnouncer(
    private val payload: () -> LanAnnouncePayload?,
    private val json: Json = Json { encodeDefaults = true },
    private val groupAddress: String = LanRediscoveryPolicy.MULTICAST_GROUP,
    private val port: Int = LanRediscoveryPolicy.PORT,
    private val intervalMillis: Long = 1_000L,
    private val rebindCheckIntervalMillis: Long = 10_000L,
) {
    private val sockets = CopyOnWriteArrayList<MulticastSocket>()
    private val socketLock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        var boundSignature = rebindPerInterface()
        worker =
            thread(name = "aegis-lan-announce", isDaemon = true) {
                val group = InetAddress.getByName(groupAddress)
                var lastRebindCheckAt = System.currentTimeMillis()
                while (running) {
                    // A momentarily missing payload (e.g. while the QR session rotates)
                    // must not kill the announcer; skip this cycle and try again.
                    val body = payload()
                    var allSendsFailed = false
                    if (body != null) {
                        val bytes = json.encodeToString(LanAnnouncePayload.serializer(), body).encodeToByteArray()
                        val targets = sockets.toList()
                        val failures =
                            targets.count { socket ->
                                runCatching {
                                    socket.send(DatagramPacket(bytes, bytes.size, group, port))
                                }.isFailure
                            }
                        allSendsFailed = targets.isNotEmpty() && failures == targets.size
                    }
                    // Sockets bound at start() go stale after a DHCP renewal, a
                    // VPN toggle, or an interface change; announcements would keep
                    // flowing into dead interfaces and never cover new ones.
                    // Re-enumerate periodically and rebind when the interface set
                    // changed or every send failed.
                    val now = System.currentTimeMillis()
                    if (allSendsFailed || sockets.isEmpty() || now - lastRebindCheckAt >= rebindCheckIntervalMillis) {
                        lastRebindCheckAt = now
                        if (allSendsFailed || interfaceSignature() != boundSignature) {
                            boundSignature = rebindPerInterface()
                        }
                    }
                    try {
                        Thread.sleep(intervalMillis)
                    } catch (_: InterruptedException) {
                        return@thread
                    }
                }
            }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        synchronized(socketLock) {
            sockets.forEach { socket -> runCatching { socket.close() } }
            sockets.clear()
        }
    }

    private fun rebindPerInterface(): Set<String> =
        synchronized(socketLock) {
            sockets.forEach { socket -> runCatching { socket.close() } }
            sockets.clear()
            if (!running) return emptySet()
            val group = InetAddress.getByName(groupAddress)
            eligibleInterfaces().forEach { network ->
                runCatching {
                    val socket = MulticastSocket(null)
                    socket.reuseAddress = true
                    runCatching { socket.setOption(StandardSocketOptions.SO_REUSEPORT, true) }
                    socket.bind(InetSocketAddress(port))
                    socket.networkInterface = network
                    socket.joinGroup(InetSocketAddress(group, port), network)
                    sockets += socket
                }
            }
            interfaceSignature()
        }

    private fun interfaceSignature(): Set<String> =
        eligibleInterfaces()
            .map { network ->
                val addresses =
                    network.interfaceAddresses
                        .mapNotNull { it.address?.hostAddress }
                        .sorted()
                        .joinToString(",")
                "${network.name}|$addresses"
            }.toSet()

    private fun eligibleInterfaces(): List<NetworkInterface> =
        runCatching {
            NetworkInterface
                .getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
        }.getOrElse { emptyList() }
}

fun LocalPairingSession.toLanAnnouncePayload(): LanAnnouncePayload =
    LanAnnouncePayload(
        fingerprint = hostIdentity.fingerprint,
        tlsPin = agentFingerprint,
        port = port,
        pairingUrl = url,
        kind = AdvertisedEndpointKind.Lan,
    )
