package dev.aegis.remote.android.routing

import dev.aegis.remote.core.model.NatTraversalState
import dev.aegis.remote.core.nat.ConnectivityCheckResult
import dev.aegis.remote.core.nat.StunProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom

class UdpStunProbe(
    private val timeoutMillis: Int = 1_200,
    private val random: SecureRandom = SecureRandom(),
) : StunProbe {
    override suspend fun check(stunUrls: List<String>): ConnectivityCheckResult =
        withContext(Dispatchers.IO) {
            val endpoints = stunUrls.mapNotNull(::parseStunEndpoint)
            if (endpoints.isEmpty()) {
                return@withContext ConnectivityCheckResult(
                    state = NatTraversalState.Blocked,
                    reason = "No valid STUN URLs are configured",
                )
            }

            val failures = mutableListOf<String>()
            for (endpoint in endpoints) {
                val result = runCatching { probe(endpoint) }
                if (result.getOrDefault(false)) {
                    return@withContext ConnectivityCheckResult(
                        state = NatTraversalState.DirectPossible,
                        reason = "STUN binding response received from ${endpoint.hostString}:${endpoint.port}",
                    )
                }
                failures +=
                    "${endpoint.hostString}:${endpoint.port} ${result.exceptionOrNull()?.javaClass?.simpleName ?: "invalid response"}"
            }

            ConnectivityCheckResult(
                state = NatTraversalState.TurnRequired,
                reason = "No STUN binding response received (${failures.joinToString()})",
            )
        }

    private fun probe(endpoint: InetSocketAddress): Boolean {
        val transactionId = ByteArray(TransactionIdSize).also(random::nextBytes)
        val request = ByteArray(HeaderSize)
        request[0] = BindingRequestType0
        request[1] = BindingRequestType1
        request[4] = MagicCookie0
        request[5] = MagicCookie1
        request[6] = MagicCookie2
        request[7] = MagicCookie3
        transactionId.copyInto(request, destinationOffset = 8)

        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMillis
            socket.send(DatagramPacket(request, request.size, endpoint))
            val response = ByteArray(512)
            val packet = DatagramPacket(response, response.size)
            socket.receive(packet)
            return isBindingSuccessResponse(response, packet.length, transactionId)
        }
    }

    private fun isBindingSuccessResponse(
        payload: ByteArray,
        length: Int,
        transactionId: ByteArray,
    ): Boolean {
        if (length < HeaderSize) return false
        if (payload[0] != BindingSuccessResponseType0 || payload[1] != BindingSuccessResponseType1) return false
        if (payload[4] != MagicCookie0 || payload[5] != MagicCookie1 || payload[6] != MagicCookie2 ||
            payload[7] != MagicCookie3
        ) {
            return false
        }
        return transactionId.indices.all { index -> payload[8 + index] == transactionId[index] }
    }

    private fun parseStunEndpoint(rawUrl: String): InetSocketAddress? {
        val normalized = if (rawUrl.contains("://")) rawUrl else rawUrl.replaceFirst(":", "://")
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        if (uri.scheme != "stun" && uri.scheme != "stuns") return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else DefaultStunPort
        return InetSocketAddress(host, port)
    }

    private companion object {
        private const val HeaderSize = 20
        private const val TransactionIdSize = 12
        private const val DefaultStunPort = 3478
        private const val BindingRequestType0: Byte = 0x00
        private const val BindingRequestType1: Byte = 0x01
        private const val BindingSuccessResponseType0: Byte = 0x01
        private const val BindingSuccessResponseType1: Byte = 0x01
        private const val MagicCookie0: Byte = 0x21
        private const val MagicCookie1: Byte = 0x12
        private const val MagicCookie2: Byte = 0xA4.toByte()
        private const val MagicCookie3: Byte = 0x42
    }
}
