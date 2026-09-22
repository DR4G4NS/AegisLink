package dev.aegis.remote.android.webrtc

import dev.aegis.remote.android.pairing.AndroidLocalPairingCrypto
import dev.aegis.remote.android.pairing.pinnedCertificateTrustManager
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.HostAddress
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.pairing.localProtocolSessionProofPayload
import dev.aegis.remote.core.security.LocalDeviceIdentity
import dev.aegis.remote.protocol.ProtocolMessageChannel
import dev.aegis.remote.protocol.ProtocolTextTransport
import dev.aegis.remote.protocol.openLanE2eeProtocolMessageChannel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.path
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class AndroidLocalProtocolClient(
    private val httpClient: HttpClient,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val pairingCrypto: AndroidLocalPairingCrypto = AndroidLocalPairingCrypto(),
    private val closeHttpClientOnChannelClose: Boolean = false,
) {
    suspend fun openProtocolMessageChannel(
        host: HostAddress,
        pairingPort: Int,
        sessionId: SessionId,
        authorizedDeviceId: String,
        protocolToken: String,
        localIdentity: LocalDeviceIdentity,
        expectedHostIdentity: DevicePublicIdentity,
    ): ProtocolMessageChannel {
        val timestamp = clock()
        val proof =
            pairingCrypto.derive(
                protocolToken,
                localProtocolSessionProofPayload(sessionId.value, authorizedDeviceId, timestamp),
            )
        val socket =
            httpClient.webSocketSession {
                url(localProtocolWebSocketUrl(host, pairingPort, sessionId, authorizedDeviceId))
                header(HttpHeaders.Authorization, "Aegis $proof")
                header("X-Aegis-Proof-Timestamp", timestamp.toString())
            }
        return openLanE2eeProtocolMessageChannel(
            transport = LocalProtocolWebSocketTextTransport(socket, httpClient.takeIf { closeHttpClientOnChannelClose }),
            sessionId = sessionId.value,
            localIdentity = localIdentity,
            expectedPeerIdentity = expectedHostIdentity,
            localIsSource = true,
            nowEpochMillis = timestamp,
        )
    }

    companion object {
        fun createPinned(agentCertificateFingerprint: String): AndroidLocalProtocolClient {
            val client =
                HttpClient(CIO) {
                    install(WebSockets)
                    // Bound only the connection phase; a request timeout would also
                    // terminate the long-lived protocol WebSocket session.
                    install(HttpTimeout) {
                        connectTimeoutMillis = LOCAL_PROTOCOL_CONNECT_TIMEOUT_MILLIS
                    }
                    engine {
                        https {
                            trustManager = pinnedCertificateTrustManager(agentCertificateFingerprint)
                        }
                    }
                }
            return AndroidLocalProtocolClient(client, closeHttpClientOnChannelClose = true)
        }
    }
}

internal fun localProtocolWebSocketUrl(
    host: HostAddress,
    pairingPort: Int,
    sessionId: SessionId,
    authorizedDeviceId: String,
): String {
    require(sessionId.value.matches(LOCAL_PROTOCOL_IDENTIFIER_PATTERN)) { "Invalid local protocol session id" }
    require(authorizedDeviceId.matches(LOCAL_PROTOCOL_IDENTIFIER_PATTERN)) { "Invalid local protocol device id" }
    return URLBuilder()
        .apply {
            protocol = URLProtocol.WSS
            this.host = host.host
            port = pairingPort
            path("protocol", sessionId.value)
            parameters.append("deviceId", authorizedDeviceId)
        }.buildString()
}

private val LOCAL_PROTOCOL_IDENTIFIER_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")

private const val LOCAL_PROTOCOL_CONNECT_TIMEOUT_MILLIS = 10_000L

private class LocalProtocolWebSocketTextTransport(
    private val socket: DefaultWebSocketSession,
    private val ownedHttpClient: HttpClient?,
) : ProtocolTextTransport {
    override val incomingText: Flow<String> =
        flow {
            for (frame in socket.incoming) {
                if (frame is Frame.Text) {
                    emit(frame.readText())
                }
            }
        }

    override suspend fun sendText(payload: String) {
        socket.send(payload)
    }

    override suspend fun close() {
        try {
            socket.close(CloseReason(CloseReason.Codes.NORMAL, "Local protocol channel closed"))
        } finally {
            ownedHttpClient?.close()
        }
    }
}
