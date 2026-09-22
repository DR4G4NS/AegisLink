package dev.aegis.remote.relayclient

import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.relay.CreateRelaySessionRequest
import dev.aegis.remote.core.relay.RELAY_IDENTITY_PROTOCOL_VERSION
import dev.aegis.remote.core.relay.RegisterRelayDeviceV2Request
import dev.aegis.remote.core.relay.RegisterRelayDeviceV2Response
import dev.aegis.remote.core.relay.RelayAuthToken
import dev.aegis.remote.core.relay.RelayClient
import dev.aegis.remote.core.relay.RelayConnectionState
import dev.aegis.remote.core.relay.RelayDeviceChallengeRequest
import dev.aegis.remote.core.relay.RelayDeviceChallengeResponse
import dev.aegis.remote.core.relay.RelayDeviceEvent
import dev.aegis.remote.core.relay.RelayDeviceEventChannel
import dev.aegis.remote.core.relay.RelayDeviceRegistration
import dev.aegis.remote.core.relay.RelayIdentityTranscript
import dev.aegis.remote.core.relay.RelayOpaqueChannel
import dev.aegis.remote.core.relay.RelayOpaqueFrame
import dev.aegis.remote.core.relay.RelayPayloadKind
import dev.aegis.remote.core.relay.RelayRegistration
import dev.aegis.remote.core.relay.RelaySession
import dev.aegis.remote.core.relay.RelaySessionApproval
import dev.aegis.remote.core.relay.RelaySessionApprovalRequest
import dev.aegis.remote.core.relay.RelaySessionResponse
import dev.aegis.remote.core.relay.RelaySignalingChannel
import dev.aegis.remote.core.relay.RelayTurnCredentials
import dev.aegis.remote.core.relay.RelayWebSocketEnvelope
import dev.aegis.remote.core.relay.RevokeRelayDeviceV2Request
import dev.aegis.remote.core.relay.RevokeRelayDeviceV2Response
import dev.aegis.remote.core.relay.RotateRelayDeviceKeyV2Request
import dev.aegis.remote.core.relay.RotateRelayDeviceKeyV2Response
import dev.aegis.remote.core.security.LocalDeviceIdentity
import dev.aegis.remote.core.webrtc.SignalingMessage
import dev.aegis.remote.protocol.JsonProtocolMessageChannel
import dev.aegis.remote.protocol.ProtocolMessageChannel
import dev.aegis.remote.protocol.ProtocolMessageJsonCodec
import dev.aegis.remote.protocol.ProtocolTextTransport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class KtorRelayClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val json: Json = relayClientJson,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RelayClient,
    RelayIdentityLifecycleRemote {
    private val _states = MutableStateFlow<RelayConnectionState>(RelayConnectionState.Disconnected)
    override val states: Flow<RelayConnectionState> = _states

    private var registration: RelayRegistration? = null
    private var identityRegistration: RelayIdentityRegistration? = null

    override suspend fun connect() {
        _states.update { RelayConnectionState.Connecting }
        runCatching {
            httpClient.get(endpoint("/health"))
        }.onSuccess {
            _states.update { RelayConnectionState.Connected }
        }.onFailure { error ->
            _states.update { RelayConnectionState.Failed(error.message ?: "Relay health check failed") }
            throw error
        }
    }

    @Deprecated("Relay registration v1 is disabled; use registerDeviceV2")
    override suspend fun registerDevice(registration: RelayDeviceRegistration): RelayRegistration = throw RelayLegacyRegistrationDisabledException()

    /**
     * Registers through the identity-v2 challenge/proof-of-possession flow.
     * The signing key remains inside [identity]; only the public identity and
     * signature cross the network.
     */
    override suspend fun registerDeviceV2(
        identity: LocalDeviceIdentity,
        displayName: String,
        remoteAccessEnabled: Boolean,
    ): RelayRegistration {
        val publicIdentity = identity.publicIdentity
        val challenge = requestIdentityChallenge(publicIdentity)
        val proofTimestamp = clock()
        require(proofTimestamp <= challenge.expiresAtEpochMillis) {
            "Relay identity challenge expired before a registration proof could be created"
        }

        val signature =
            identity.sign(
                RelayIdentityTranscript.registration(
                    protocolVersion = challenge.protocolVersion,
                    relayOrigin = challenge.relayOrigin,
                    identity = publicIdentity,
                    challengeNonce = challenge.nonce,
                    timestampEpochMillis = proofTimestamp,
                ),
            )
        val response =
            httpClient
                .post(endpoint("/v3/devices/register")) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        RegisterRelayDeviceV2Request(
                            protocolVersion = challenge.protocolVersion,
                            identity = publicIdentity,
                            challengeNonce = challenge.nonce,
                            relayOrigin = challenge.relayOrigin,
                            timestampEpochMillis = proofTimestamp,
                            signature = signature,
                            displayName = displayName,
                            remoteAccessEnabled = remoteAccessEnabled,
                        ),
                    )
                }.body<RegisterRelayDeviceV2Response>()

        require(response.identity.matches(publicIdentity)) {
            "Relay registration response returned a different device identity"
        }
        return RelayRegistration(
            relayDeviceId = response.relayDeviceId,
            authToken =
                RelayAuthToken(
                    tokenRef = response.authToken,
                    expiresAtEpochMillis = response.expiresAtEpochMillis,
                ),
        ).also {
            registration = it
            identityRegistration = RelayIdentityRegistration(publicIdentity, challenge.relayOrigin)
            _states.update { RelayConnectionState.Connected }
        }
    }

    /**
     * Rebuilds only the public rotation context after process death. The
     * persisted relay locator is not trusted: the old-key proof binds it and
     * the relay rejects any mismatch. No bearer token is restored or sent.
     */
    suspend fun prepareIdentityRotationRecoveryV2(
        currentIdentity: LocalDeviceIdentity,
        relayDeviceId: RelayDeviceId,
    ) {
        val challenge = requestIdentityChallenge(currentIdentity.publicIdentity)
        registration =
            RelayRegistration(
                relayDeviceId = relayDeviceId,
                authToken = RelayAuthToken(ROTATION_RECOVERY_TOKEN_SENTINEL, challenge.expiresAtEpochMillis),
            )
        identityRegistration = RelayIdentityRegistration(currentIdentity.publicIdentity, challenge.relayOrigin)
        _states.update { RelayConnectionState.Connecting }
    }

    /** Rotates an already registered identity using a proof from the old key. */
    override suspend fun rotateDeviceKeyV2(
        currentIdentity: LocalDeviceIdentity,
        replacementIdentity: LocalDeviceIdentity,
        operationId: String,
        activateLocally: Boolean,
    ): RelayRegistration {
        val currentRegistration = requireRegistration()
        val currentContext = requireIdentityRegistration()
        require(operationId.isNotBlank()) { "Identity rotation operation ID is required" }
        require(currentContext.identity.matches(currentIdentity.publicIdentity)) {
            "The supplied current identity does not match the registered relay identity"
        }
        require(replacementIdentity.publicIdentity.keyGeneration > currentIdentity.publicIdentity.keyGeneration) {
            "Replacement identity key generation must increase"
        }
        val timestamp = clock()
        val signature =
            currentIdentity.sign(
                RelayIdentityTranscript.rotation(
                    protocolVersion = RELAY_IDENTITY_PROTOCOL_VERSION,
                    operationId = operationId,
                    relayOrigin = currentContext.relayOrigin,
                    relayDeviceId = currentRegistration.relayDeviceId,
                    currentIdentity = currentIdentity.publicIdentity,
                    replacementIdentity = replacementIdentity.publicIdentity,
                    timestampEpochMillis = timestamp,
                ),
            )
        val response =
            httpClient
                .post(endpoint("/v3/devices/rotate-key")) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        RotateRelayDeviceKeyV2Request(
                            operationId = operationId,
                            relayDeviceId = currentRegistration.relayDeviceId,
                            currentIdentity = currentIdentity.publicIdentity,
                            replacementIdentity = replacementIdentity.publicIdentity,
                            relayOrigin = currentContext.relayOrigin,
                            timestampEpochMillis = timestamp,
                            signature = signature,
                        ),
                    )
                }.body<RotateRelayDeviceKeyV2Response>()
        require(response.relayDeviceId == currentRegistration.relayDeviceId) {
            "Relay rotation response changed the relay locator"
        }
        require(response.identity.matches(replacementIdentity.publicIdentity)) {
            "Relay rotation response returned a different replacement identity"
        }
        val rotatedRegistration =
            RelayRegistration(
                relayDeviceId = response.relayDeviceId,
                authToken = RelayAuthToken(response.authToken, response.expiresAtEpochMillis),
            )
        if (activateLocally) adoptRotatedDeviceKeyV2(replacementIdentity, rotatedRegistration)
        return rotatedRegistration
    }

    override fun adoptRotatedDeviceKeyV2(
        replacementIdentity: LocalDeviceIdentity,
        registration: RelayRegistration,
    ) {
        val currentRegistration = requireRegistration()
        val currentContext = requireIdentityRegistration()
        require(registration.relayDeviceId == currentRegistration.relayDeviceId) {
            "Relay rotation adoption changed the relay locator"
        }
        this.registration = registration
        identityRegistration = RelayIdentityRegistration(replacementIdentity.publicIdentity, currentContext.relayOrigin)
        _states.update { RelayConnectionState.Connected }
    }

    /** Revokes the locally registered identity and drops its in-memory token. */
    override suspend fun revokeDeviceV2(identity: LocalDeviceIdentity): RevokeRelayDeviceV2Response {
        val currentRegistration = requireRegistration()
        val currentContext = requireIdentityRegistration()
        require(currentContext.identity.matches(identity.publicIdentity)) {
            "The supplied identity does not match the registered relay identity"
        }
        val timestamp = clock()
        val signature =
            identity.sign(
                RelayIdentityTranscript.revocation(
                    protocolVersion = RELAY_IDENTITY_PROTOCOL_VERSION,
                    relayOrigin = currentContext.relayOrigin,
                    relayDeviceId = currentRegistration.relayDeviceId,
                    identity = identity.publicIdentity,
                    timestampEpochMillis = timestamp,
                ),
            )
        val response =
            httpClient
                .post(endpoint("/v3/devices/revoke")) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        RevokeRelayDeviceV2Request(
                            relayDeviceId = currentRegistration.relayDeviceId,
                            identity = identity.publicIdentity,
                            relayOrigin = currentContext.relayOrigin,
                            timestampEpochMillis = timestamp,
                            signature = signature,
                        ),
                    )
                }.body<RevokeRelayDeviceV2Response>()
        require(response.relayDeviceId == currentRegistration.relayDeviceId) {
            "Relay revocation response changed the relay locator"
        }
        require(response.deviceId == identity.publicIdentity.deviceId) {
            "Relay revocation response referenced another identity"
        }
        registration = null
        identityRegistration = null
        _states.update { RelayConnectionState.Disconnected }
        return response
    }

    override suspend fun createSession(targetRelayDeviceId: RelayDeviceId): RelaySession {
        val current = requireRegistration()
        val httpResponse =
            httpClient.post(endpoint("/sessions")) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${current.authToken.tokenRef}")
                setBody(
                    CreateRelaySessionRequest(
                        sourceRelayDeviceId = current.relayDeviceId,
                        targetRelayDeviceId = targetRelayDeviceId,
                    ),
                )
            }
        check(httpResponse.status.value in 200..299) {
            "RELAY_SESSION_CREATE_HTTP_${httpResponse.status.value}"
        }
        val response = httpResponse.body<RelaySessionResponse>()
        return response.session.copy(targetIdentity = response.targetIdentity?.publicIdentity)
    }

    override suspend fun approveSession(
        sessionId: SessionId,
        approved: Boolean,
    ): RelaySessionApproval {
        val current = requireRegistration()
        return httpClient
            .post(endpoint("/sessions/${sessionId.value}/approval")) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${current.authToken.tokenRef}")
                header("X-Relay-Device-Id", current.relayDeviceId.value)
                setBody(RelaySessionApprovalRequest(approved))
            }.body<RelaySessionApproval>()
    }

    override suspend fun requestTurnCredentials(): RelayTurnCredentials {
        val current = requireRegistration()
        return httpClient
            .get(endpoint("/turn/credentials")) {
                header(HttpHeaders.Authorization, "Bearer ${current.authToken.tokenRef}")
                header("X-Relay-Device-Id", current.relayDeviceId.value)
            }.body<RelayTurnCredentials>()
    }

    override suspend fun openSignalingChannel(sessionId: SessionId): RelaySignalingChannel {
        error("LEGACY_PLAINTEXT visual signaling is not permitted; use the opaque E2EE adapter")
    }

    override suspend fun openOpaqueChannel(sessionId: SessionId): RelayOpaqueChannel {
        val current = requireRegistration()
        val socket =
            httpClient.webSocketSession {
                url("${webSocketBaseUrl()}/signaling/${sessionId.value}/${current.relayDeviceId.value}")
                header(HttpHeaders.Authorization, "Bearer ${current.authToken.tokenRef}")
            }
        return KtorRelayOpaqueChannel(sessionId, current.relayDeviceId, socket, json)
    }

    suspend fun openProtocolMessageChannel(sessionId: SessionId): ProtocolMessageChannel {
        val current = requireRegistration()
        val socket =
            httpClient.webSocketSession {
                url("${webSocketBaseUrl()}/signaling/${sessionId.value}/${current.relayDeviceId.value}")
                header(HttpHeaders.Authorization, "Bearer ${current.authToken.tokenRef}")
            }
        return JsonProtocolMessageChannel(
            transport =
                KtorRelayProtocolTextTransport(
                    sessionId = sessionId,
                    senderRelayDeviceId = current.relayDeviceId,
                    socket = socket,
                    json = json,
                    payloadKind = RelayPayloadKind.E2EE_ENVELOPE,
                ),
            codec = ProtocolMessageJsonCodec(json),
        )
    }

    override suspend fun openDeviceEvents(): RelayDeviceEventChannel {
        val current = requireRegistration()
        val socket =
            httpClient.webSocketSession {
                url("${webSocketBaseUrl()}/devices/${current.relayDeviceId.value}/events")
                header(HttpHeaders.Authorization, "Bearer ${current.authToken.tokenRef}")
            }
        return KtorRelayDeviceEventChannel(socket, json)
    }

    override suspend fun close() {
        registration = null
        identityRegistration = null
        _states.update { RelayConnectionState.Disconnected }
        httpClient.close()
    }

    private fun requireRegistration(): RelayRegistration = registration ?: error("Relay device must be registered before creating sessions or opening signaling")

    private fun requireIdentityRegistration(): RelayIdentityRegistration = identityRegistration ?: error("Relay identity-v2 registration is required for this operation")

    fun registeredRelayOrigin(): String = requireIdentityRegistration().relayOrigin

    private suspend fun requestIdentityChallenge(
        identity: dev.aegis.remote.core.model.DevicePublicIdentity,
    ): RelayDeviceChallengeResponse {
        val challenge =
            httpClient
                .post(endpoint("/v3/devices/challenge")) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        RelayDeviceChallengeRequest(
                            protocolVersion = RELAY_IDENTITY_PROTOCOL_VERSION,
                            identity = identity,
                        ),
                    )
                }.body<RelayDeviceChallengeResponse>()
        require(challenge.protocolVersion == RELAY_IDENTITY_PROTOCOL_VERSION) {
            "Relay selected unsupported identity protocol version ${challenge.protocolVersion}"
        }
        require(challenge.nonce.isNotBlank()) { "Relay returned an empty identity challenge nonce" }
        require(challenge.relayOrigin.isNotBlank()) { "Relay returned an empty identity origin" }
        require(challenge.expiresAtEpochMillis >= challenge.issuedAtEpochMillis) {
            "Relay returned an invalid identity challenge lifetime"
        }
        require(clock() <= challenge.expiresAtEpochMillis) {
            "Relay identity challenge expired before it could be used"
        }
        return challenge
    }

    private fun endpoint(path: String): String = "${baseUrl.trimEnd('/')}$path"

    private fun webSocketBaseUrl(): String {
        val trimmed = baseUrl.trimEnd('/')
        return when {
            trimmed.startsWith("https://") -> "wss://${trimmed.removePrefix("https://")}"
            trimmed.startsWith("http://") -> "ws://${trimmed.removePrefix("http://")}"
            else -> trimmed
        }
    }

    private companion object {
        const val ROTATION_RECOVERY_TOKEN_SENTINEL = "rotation-recovery-context-no-bearer-token"
    }
}

private data class RelayIdentityRegistration(
    val identity: dev.aegis.remote.core.model.DevicePublicIdentity,
    val relayOrigin: String,
)

class RelayLegacyRegistrationDisabledException :
    IllegalStateException(
        "Relay registration v1 is disabled. Register through the identity-v2 challenge flow.",
    )

private class KtorRelayDeviceEventChannel(
    private val socket: DefaultWebSocketSession,
    private val json: Json,
) : RelayDeviceEventChannel {
    override val incoming: Flow<RelayDeviceEvent> =
        flow {
            for (frame in socket.incoming) {
                if (frame is Frame.Text) {
                    emit(json.decodeFromString(RelayDeviceEvent.serializer(), frame.readText()))
                }
            }
            throw RelayEventStreamClosedException()
        }

    override suspend fun close() {
        socket.close(CloseReason(CloseReason.Codes.NORMAL, "Relay device events closed"))
    }
}

class RelayEventStreamClosedException : RuntimeException("Relay device event stream closed")

private class KtorRelaySignalingChannel(
    private val sessionId: SessionId,
    private val senderRelayDeviceId: RelayDeviceId,
    private val socket: DefaultWebSocketSession,
    private val json: Json,
) : RelaySignalingChannel {
    override val incoming: Flow<SignalingMessage> =
        flow {
            for (frame in socket.incoming) {
                if (frame is Frame.Text) {
                    val envelope = json.decodeFromString(RelayWebSocketEnvelope.serializer(), frame.readText())
                    emit(json.decodeFromString(SignalingMessage.serializer(), envelope.payloadJson))
                }
            }
        }

    override suspend fun send(message: SignalingMessage) {
        error("LEGACY_PLAINTEXT visual signaling is not permitted; use the opaque E2EE adapter")
    }

    override suspend fun close() {
        socket.close(CloseReason(CloseReason.Codes.NORMAL, "Relay signaling closed"))
    }
}

private class KtorRelayOpaqueChannel(
    private val sessionId: SessionId,
    private val senderRelayDeviceId: RelayDeviceId,
    private val socket: DefaultWebSocketSession,
    private val json: Json,
) : RelayOpaqueChannel {
    override val incoming: Flow<RelayOpaqueFrame> =
        flow {
            for (frame in socket.incoming) {
                if (frame is Frame.Text) {
                    val envelope = json.decodeFromString(RelayWebSocketEnvelope.serializer(), frame.readText())
                    if (envelope.sessionId == sessionId && envelope.payloadKind != RelayPayloadKind.LEGACY_PLAINTEXT) {
                        emit(RelayOpaqueFrame(envelope.payloadKind, envelope.payloadJson))
                    }
                }
            }
        }

    override suspend fun send(frame: RelayOpaqueFrame) {
        val envelope =
            RelayWebSocketEnvelope(
                sessionId = sessionId,
                senderRelayDeviceId = senderRelayDeviceId,
                payloadKind = frame.kind,
                payloadJson = frame.payloadJson,
            )
        socket.send(Frame.Text(json.encodeToString(RelayWebSocketEnvelope.serializer(), envelope)))
    }

    override suspend fun close() {
        socket.close(CloseReason(CloseReason.Codes.NORMAL, "Relay E2EE channel closed"))
    }
}

private class KtorRelayProtocolTextTransport(
    private val sessionId: SessionId,
    private val senderRelayDeviceId: RelayDeviceId,
    private val socket: DefaultWebSocketSession,
    private val json: Json,
    private val payloadKind: RelayPayloadKind = RelayPayloadKind.E2EE_ENVELOPE,
) : ProtocolTextTransport {
    init {
        require(payloadKind != RelayPayloadKind.LEGACY_PLAINTEXT) {
            "LEGACY_PLAINTEXT visual signaling is not permitted; use the opaque E2EE adapter"
        }
    }

    override val incomingText: Flow<String> =
        flow {
            for (frame in socket.incoming) {
                if (frame is Frame.Text) {
                    val envelope = json.decodeFromString(RelayWebSocketEnvelope.serializer(), frame.readText())
                    if (envelope.sessionId == sessionId) {
                        emit(envelope.payloadJson)
                    }
                }
            }
        }

    override suspend fun sendText(payload: String) {
        val envelope =
            RelayWebSocketEnvelope(
                sessionId = sessionId,
                senderRelayDeviceId = senderRelayDeviceId,
                payloadKind = payloadKind,
                payloadJson = payload,
            )
        socket.send(Frame.Text(json.encodeToString(RelayWebSocketEnvelope.serializer(), envelope)))
    }

    override suspend fun close() {
        socket.close(CloseReason(CloseReason.Codes.NORMAL, "Relay protocol channel closed"))
    }
}

fun defaultKtorRelayHttpClient(json: Json = relayClientJson): HttpClient =
    HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(WebSockets)
        // Only the connection phase is bounded: a request timeout would also kill
        // long-lived relay WebSocket sessions, which must stay open indefinitely.
        install(HttpTimeout) {
            connectTimeoutMillis = RELAY_CONNECT_TIMEOUT_MILLIS
        }
    }

private const val RELAY_CONNECT_TIMEOUT_MILLIS = 10_000L

val relayClientJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
