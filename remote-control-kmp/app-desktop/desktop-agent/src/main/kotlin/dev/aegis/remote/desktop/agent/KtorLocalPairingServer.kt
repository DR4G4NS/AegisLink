package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.pairing.LanAuthorizationStatus
import dev.aegis.remote.core.pairing.LanRegisterRequest
import dev.aegis.remote.core.pairing.LanRegisterResponse
import dev.aegis.remote.core.pairing.LocalPairedProfile
import dev.aegis.remote.core.pairing.LocalPairingPayload
import dev.aegis.remote.core.pairing.LocalPairingQrPayload
import dev.aegis.remote.core.pairing.LocalPairingRequestBody
import dev.aegis.remote.core.pairing.LocalPairingRequestStatus
import dev.aegis.remote.core.pairing.LocalPairingResponse
import dev.aegis.remote.core.pairing.LocalPairingStatusResponse
import dev.aegis.remote.core.pairing.localPairingProofPayload
import dev.aegis.remote.core.pairing.localPairingQrSignaturePayload
import dev.aegis.remote.core.pairing.localProtocolTokenPayload
import dev.aegis.remote.core.security.DeviceIdentityStore
import dev.aegis.remote.core.security.LocalDeviceIdentity
import dev.aegis.remote.protocol.JsonProtocolMessageChannel
import dev.aegis.remote.protocol.ProtocolTextTransport
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class KtorLocalPairingServer(
    private val host: String = "0.0.0.0",
    private val advertisedHosts: () -> List<String> = { localHostAddresses() },
    private val port: Int = 48291,
    private val codeGenerator: PairingCodeGenerator = PairingCodeGenerator(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val nonceGenerator: () -> String = { UUID.randomUUID().toString() },
    private val authenticator: LocalProtocolAuthenticator = LocalProtocolAuthenticator(),
    private val tlsIdentityStore: LocalTlsIdentityStore = PersistentLocalTlsIdentityStore(),
    private val deviceIdentityStore: DeviceIdentityStore = DesktopDeviceIdentityStore(),
) : LocalPairingServer {
    private var engine: EmbeddedServer<*, *>? = null
    private val statuses = ConcurrentHashMap<String, LocalPairingStatusResponse>()
    private val pairingChallenges = ConcurrentHashMap<String, Long>()
    private val requestExpirations = ConcurrentHashMap<String, Long>()
    private val dispatchingRequestIds = ConcurrentHashMap.newKeySet<String>()
    private val attempts = ConcurrentHashMap<String, AttemptWindow>()
    private val usedQrTokenIds = ConcurrentHashMap<String, Long>()
    private val pairingSessionMutex = Mutex()
    private val pairingStateLock = Any()

    @Volatile
    private var serverRunning = false

    @Volatile
    private var currentPairingSession: LocalPairingSession? = null

    @Volatile
    private var pairingRuntime: PairingRuntime? = null

    @Volatile
    private var deviceAuthorizationLookup: suspend (String) -> LanAuthorizationStatus = { LanAuthorizationStatus.UNKNOWN }

    private inner class PairingRoutes {
        fun Routing.configurePairingRoutes(
            onPairingRequest: suspend (DesktopPairingRequest) -> PairingRequestDispatchResult,
            onProtocolChannel: suspend (LocalProtocolChannelRequest) -> Unit,
            onPairingEvent: (PairingServerEvent) -> Unit,
        ) {
            get("/pairing/payload") {
                respondPairingPayload(call)
            }
            post("/pairing/requests") {
                handlePairingRequest(call, onPairingRequest, onPairingEvent)
            }
            get("/pairing/requests/{requestId}") {
                respondPairingStatus(call, onPairingEvent)
            }
            post("/lan/register") {
                respondLanRegister(call)
            }
            webSocket("/protocol/{sessionId}") {
                val sessionId = call.parameters["sessionId"]?.takeIf { it.isNotBlank() }
                val authorizedDeviceId = call.request.queryParameters["deviceId"]?.takeIf { it.isNotBlank() }
                val sessionProof =
                    call.request.headers[HttpHeaders.Authorization]
                        ?.takeIf { it.startsWith("Aegis ", ignoreCase = true) }
                        ?.substringAfter(' ')
                        ?.takeIf { it.isNotBlank() }
                val proofTimestamp = call.request.headers[PROOF_TIMESTAMP_HEADER]?.toLongOrNull()
                if (sessionId == null || authorizedDeviceId == null) {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing session id or device id"))
                    return@webSocket
                }
                val transport = LocalWebSocketTextTransport(this)
                onProtocolChannel(
                    LocalProtocolChannelRequest(
                        sessionId = SessionId(sessionId),
                        authorizedDeviceId = authorizedDeviceId,
                        sessionProof = sessionProof,
                        proofTimestampEpochMillis = proofTimestamp,
                        channel = JsonProtocolMessageChannel(transport),
                        transport = transport,
                        hostIdentity = pairingRuntime?.hostIdentity,
                    ),
                )
            }
        }

        private suspend fun respondPairingPayload(call: ApplicationCall) {
            val session = currentPairingSession
            if (session == null) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    LocalPairingResponse(LocalPairingRequestStatus.Unknown, "QRP-7105: Pairing is unavailable"),
                )
                return
            }
            val now = clock()
            if (now !in session.issuedAtEpochMillis..session.expiresAtEpochMillis ||
                usedQrTokenIds.containsKey(session.tokenId)
            ) {
                call.respond(
                    HttpStatusCode.Gone,
                    LocalPairingResponse(
                        LocalPairingRequestStatus.Expired,
                        "QRP-7101: The QR token expired or was already used; refresh the QR in Aegis",
                    ),
                )
                return
            }
            val nonce = nonceGenerator()
            pairingChallenges[nonce] = now + PAIRING_CHALLENGE_TTL_MILLIS
            pairingChallenges.entries.removeIf { it.value < now }
            call.respond(
                LocalPairingPayload(
                    host = requestedPairingHost(call.request.local.localHost, session),
                    port = session.port,
                    pairingCode = session.pairingCode,
                    agentFingerprint = session.agentFingerprint,
                    timestampEpochMillis = now,
                    expiresAtEpochMillis = now + PAIRING_CHALLENGE_TTL_MILLIS,
                    nonce = nonce,
                ),
            )
        }

        private suspend fun respondLanRegister(call: ApplicationCall) {
            val runtime = pairingRuntime
            if (runtime == null || !serverRunning) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    LocalPairingResponse(LocalPairingRequestStatus.Unknown, "QRP-7105: Pairing is unavailable"),
                )
                return
            }
            val request = runCatching { call.receive<LanRegisterRequest>() }.getOrNull()
            val session = currentPairingSession
            val host =
                if (session != null) {
                    requestedPairingHost(call.request.local.localHost, session)
                } else {
                    runtime.hosts.first()
                }
            val port = session?.port ?: this@KtorLocalPairingServer.port
            val secure = session?.secure ?: true
            call.respond(
                LanRegisterResponse(
                    fingerprint = runtime.hostIdentity.publicIdentity.fingerprint,
                    tlsPin = runtime.agentFingerprint,
                    port = port,
                    pairingUrl = localPairingBaseUrl(host, port, secure),
                    host = host,
                    authorizationStatus = lanAuthorizationStatus(request?.authorizedDeviceId),
                    permissions = request?.authorizedDeviceId?.let { devicePermissionsLookup(it) },
                ),
            )
        }

        private suspend fun handlePairingRequest(
            call: ApplicationCall,
            onPairingRequest: suspend (DesktopPairingRequest) -> PairingRequestDispatchResult,
            onPairingEvent: (PairingServerEvent) -> Unit,
        ) {
            val requestStartedAt = clock()
            val remoteHost = call.request.origin.remoteHost
            emitPairingEvent(
                onPairingEvent,
                PairingServerEvent(
                    requestId = null,
                    stage = PairingServerEventStage.PAIRING_HTTP_REQUEST_RECEIVED,
                    result = "received",
                    remoteHost = remoteHost,
                    lifecycleGeneration = null,
                    pendingCount = pendingRequestCount(),
                    latencyMillis = 0L,
                ),
            )
            if (!allowAttempt(remoteHost, requestStartedAt)) {
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    LocalPairingResponse(LocalPairingRequestStatus.Rejected, "Too many pairing attempts; try again later"),
                )
                return
            }
            val session = currentPairingSession
            if (session == null) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    LocalPairingResponse(LocalPairingRequestStatus.Unknown, "QRP-7105: Pairing is unavailable"),
                )
                return
            }
            val validated = validatePairingRequest(call, session, requestStartedAt) ?: return
            val request = validated.request
            emitPairingEvent(
                onPairingEvent,
                PairingServerEvent(
                    requestId = request.requestId,
                    stage = PairingServerEventStage.PAIRING_REQUEST_VALIDATED,
                    result = "validated",
                    remoteHost = remoteHost,
                    lifecycleGeneration = null,
                    pendingCount = pendingRequestCount(),
                    latencyMillis = elapsedMillis(requestStartedAt),
                ),
            )
            requestExpirations[request.requestId] = request.requestedAtEpochMillis + PAIRING_APPROVAL_TTL_MILLIS
            dispatchingRequestIds += request.requestId
            if (!dispatchPairingRequest(call, onPairingRequest, onPairingEvent, request, validated.qrTokenId, remoteHost, requestStartedAt)) {
                return
            }
            if (!makeRequestVisible(request.requestId, validated.qrTokenId)) {
                emitDispatchRejected(onPairingEvent, request.requestId, remoteHost, requestStartedAt, "server-stopped")
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    LocalPairingResponse(
                        LocalPairingRequestStatus.Unknown,
                        "PAIRING_AGENT_UNAVAILABLE: Pairing request could not be delivered",
                    ),
                )
                return
            }
            emitPairingEvent(
                onPairingEvent,
                PairingServerEvent(
                    requestId = request.requestId,
                    stage = PairingServerEventStage.PAIRING_REQUEST_DISPATCHED,
                    result = "accepted",
                    remoteHost = remoteHost,
                    lifecycleGeneration = null,
                    pendingCount = pendingRequestCount(),
                    latencyMillis = elapsedMillis(requestStartedAt),
                ),
            )
            emitPairingEvent(
                onPairingEvent,
                PairingServerEvent(
                    requestId = request.requestId,
                    stage = PairingServerEventStage.PAIRING_REQUEST_VISIBLE,
                    result = "visible",
                    remoteHost = remoteHost,
                    lifecycleGeneration = null,
                    pendingCount = pendingRequestCount(),
                    latencyMillis = elapsedMillis(requestStartedAt),
                ),
            )
            val status = statuses[request.requestId]
            call.respond(
                LocalPairingResponse(
                    status = status?.status ?: LocalPairingRequestStatus.Unknown,
                    message = status?.message ?: "Pairing request not found",
                    requestId = request.requestId,
                ),
            )
        }

        private suspend fun validatePairingRequest(
            call: ApplicationCall,
            session: LocalPairingSession,
            now: Long,
        ): ValidatedPairingRequest? {
            val body = call.receive<LocalPairingRequestBody>()
            val challengeExpiry = pairingChallenges.remove(body.challengeNonce)
            if (challengeExpiry == null || challengeExpiry < now) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    LocalPairingResponse(LocalPairingRequestStatus.Expired, "Pairing challenge is expired or was already used"),
                )
                return null
            }
            if (body.pairingCode != session.pairingCode) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    LocalPairingResponse(LocalPairingRequestStatus.Rejected, "Invalid pairing code"),
                )
                return null
            }
            if (body.qrTokenId != session.tokenId || now !in session.issuedAtEpochMillis..session.expiresAtEpochMillis) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    LocalPairingResponse(LocalPairingRequestStatus.Expired, "QRP-7101: QR token expired"),
                )
                return null
            }
            val proofPayload =
                localPairingProofPayload(
                    nonce = body.challengeNonce,
                    deviceName = body.deviceName,
                    deviceFingerprint = body.deviceFingerprint,
                    qrTokenId = body.qrTokenId,
                    sshPublicKey = body.sshPublicKey,
                    deviceIdentity = body.deviceIdentity,
                )
            if (!authenticator.verifiesProof(session.pairingSecret, proofPayload, body.challengeProof)) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    LocalPairingResponse(LocalPairingRequestStatus.Rejected, "Invalid pairing challenge proof"),
                )
                return null
            }
            if (!verifyLocalPairingDeviceProof(body)) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    LocalPairingResponse(LocalPairingRequestStatus.Rejected, "QRP-7102: Android identity proof is invalid"),
                )
                return null
            }
            if (!isValidOpenSshAuthorizedKey(body.sshPublicKey)) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    LocalPairingResponse(LocalPairingRequestStatus.Rejected, "SSH-7002: Android SSH public key is invalid"),
                )
                return null
            }
            if (usedQrTokenIds.putIfAbsent(session.tokenId, now) != null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    LocalPairingResponse(LocalPairingRequestStatus.Expired, "QRP-7103: QR token was already consumed"),
                )
                return null
            }
            val requestId = UUID.randomUUID().toString()
            val request =
                DesktopPairingRequest(
                    requestId = requestId,
                    deviceName = body.deviceName,
                    fingerprint = body.deviceFingerprint,
                    requestedAtEpochMillis = now,
                    remote = false,
                    localProtocolToken = authenticator.deriveToken(session.pairingSecret, localProtocolTokenPayload(requestId)),
                    localHost = requestedPairingHost(call.request.local.localHost, session),
                    publicIdentity = body.deviceIdentity,
                    sshPublicKey = body.sshPublicKey,
                )
            return ValidatedPairingRequest(request, session.tokenId)
        }

        private suspend fun dispatchPairingRequest(
            call: ApplicationCall,
            onPairingRequest: suspend (DesktopPairingRequest) -> PairingRequestDispatchResult,
            onPairingEvent: (PairingServerEvent) -> Unit,
            request: DesktopPairingRequest,
            tokenId: String,
            remoteHost: String,
            requestStartedAt: Long,
        ): Boolean {
            val dispatchResult = runCatching { onPairingRequest(request) }
            val failure = dispatchResult.exceptionOrNull()
            if (failure != null) {
                cleanupFailedDispatch(request.requestId, tokenId)
                val failureType = failure::class.simpleName ?: "Exception"
                emitDispatchRejected(
                    onPairingEvent,
                    request.requestId,
                    remoteHost,
                    requestStartedAt,
                    "exception:$failureType",
                )
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    LocalPairingResponse(
                        LocalPairingRequestStatus.Unknown,
                        "PAIRING_DISPATCH_FAILED: Pairing request could not be delivered",
                    ),
                )
                return false
            }
            return when (dispatchResult.getOrThrow()) {
                PairingRequestDispatchResult.Accepted -> {
                    true
                }

                PairingRequestDispatchResult.AgentUnavailable -> {
                    cleanupFailedDispatch(request.requestId, tokenId)
                    emitDispatchRejected(onPairingEvent, request.requestId, remoteHost, requestStartedAt, "agent-unavailable")
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        LocalPairingResponse(
                            LocalPairingRequestStatus.Unknown,
                            "PAIRING_AGENT_UNAVAILABLE: Pairing request could not be delivered",
                        ),
                    )
                    false
                }
            }
        }

        private fun makeRequestVisible(
            requestId: String,
            tokenId: String,
        ): Boolean =
            synchronized(pairingStateLock) {
                if (!serverRunning) {
                    cleanupFailedDispatch(requestId, tokenId)
                    false
                } else {
                    dispatchingRequestIds.remove(requestId)
                    statuses.putIfAbsent(
                        requestId,
                        LocalPairingStatusResponse(
                            status = LocalPairingRequestStatus.Pending,
                            message = "Waiting for approval on this PC",
                        ),
                    )
                    true
                }
            }

        private suspend fun respondPairingStatus(
            call: ApplicationCall,
            onPairingEvent: (PairingServerEvent) -> Unit,
        ) {
            val requestId = call.parameters["requestId"]
            val pollStartedAt = clock()
            val status =
                synchronized(pairingStateLock) {
                    requestId?.let { id ->
                        val current = statuses[id]
                        val expired = requestExpirations[id]?.let { it < clock() } == true
                        if (current?.status == LocalPairingRequestStatus.Pending && expired) {
                            LocalPairingStatusResponse(
                                status = LocalPairingRequestStatus.Expired,
                                message = "Pairing approval request expired",
                            ).also { statuses[id] = it }
                        } else {
                            current
                        }
                    }
                }
            emitPairingEvent(
                onPairingEvent,
                PairingServerEvent(
                    requestId = requestId,
                    stage = PairingServerEventStage.PAIRING_STATUS_POLLED,
                    result = status?.status?.name?.lowercase() ?: "unknown",
                    remoteHost = call.request.origin.remoteHost,
                    lifecycleGeneration = null,
                    pendingCount = pendingRequestCount(),
                    latencyMillis = elapsedMillis(pollStartedAt),
                ),
            )
            call.respond(
                status ?: LocalPairingStatusResponse(
                    status = LocalPairingRequestStatus.Unknown,
                    message = "Pairing request not found",
                ),
            )
        }
    }

    override suspend fun start(
        onPairingRequest: suspend (DesktopPairingRequest) -> PairingRequestDispatchResult,
        onProtocolChannel: suspend (LocalProtocolChannelRequest) -> Unit,
        onPairingEvent: (PairingServerEvent) -> Unit,
    ): LocalPairingSession {
        check(engine == null) { "QRP-7105: The local pairing server is already running" }
        val tlsIdentity = tlsIdentityStore.getOrCreate()
        val hostIdentity = deviceIdentityStore.getOrCreate()
        val keyPassword = UUID.randomUUID().toString().toCharArray()
        val keyStore =
            KeyStore.getInstance("JKS").apply {
                load(null)
                setKeyEntry("aegis-local", tlsIdentity.privateKey, keyPassword, arrayOf(tlsIdentity.certificate))
            }
        val agentFingerprint = tlsIdentity.fingerprint
        val sessionHosts =
            advertisedHosts()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .ifEmpty { listOf("127.0.0.1") }
        val runtime = PairingRuntime(agentFingerprint, sessionHosts, hostIdentity)
        pairingRuntime = runtime
        val session = issuePairingSession(runtime).also { currentPairingSession = it }

        engine =
            embeddedServer(Netty, configure = {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = "aegis-local",
                    keyStorePassword = { keyPassword },
                    privateKeyPassword = { keyPassword },
                ) {
                    this.host = this@KtorLocalPairingServer.host
                    this.port = this@KtorLocalPairingServer.port
                }
            }) {
                install(ContentNegotiation) {
                    json()
                }
                install(WebSockets)
                routing {
                    with(PairingRoutes()) {
                        configurePairingRoutes(onPairingRequest, onProtocolChannel, onPairingEvent)
                    }
                }
            }.start(wait = false)
        synchronized(pairingStateLock) {
            serverRunning = true
        }
        keyPassword.fill('\u0000')

        return session
    }

    private var devicePermissionsLookup: suspend (String) -> dev.aegis.remote.core.model.DevicePermissions? = { null }

    override fun bindDevicePermissionsLookup(lookup: suspend (String) -> dev.aegis.remote.core.model.DevicePermissions?) {
        devicePermissionsLookup = lookup
    }

    override fun bindDeviceAuthorizationLookup(lookup: suspend (String) -> LanAuthorizationStatus) {
        deviceAuthorizationLookup = lookup
    }

    private suspend fun lanAuthorizationStatus(authorizedDeviceId: String?): LanAuthorizationStatus {
        val deviceId = authorizedDeviceId?.trim().orEmpty()
        if (deviceId.isBlank() || deviceId.equals("unknown", ignoreCase = true)) {
            return LanAuthorizationStatus.UNKNOWN
        }
        return runCatching { deviceAuthorizationLookup(deviceId) }
            .getOrDefault(LanAuthorizationStatus.UNKNOWN)
    }

    override suspend fun refreshPairingSession(): LocalPairingSession =
        pairingSessionMutex.withLock {
            val runtime = pairingRuntime ?: error("QRP-7105: The local pairing server is not running")
            val refreshed = issuePairingSession(runtime)
            currentPairingSession = refreshed
            pairingChallenges.clear()
            val now = clock()
            usedQrTokenIds.entries.removeIf { now - it.value > USED_QR_TOKEN_RETENTION_MILLIS }
            refreshed
        }

    override fun holdPendingApproval(
        requestId: String,
        extraMillis: Long,
    ) {
        synchronized(pairingStateLock) {
            val current = statuses[requestId] ?: return
            if (current.status != LocalPairingRequestStatus.Pending) return
            val holdFor = extraMillis.coerceAtLeast(PAIRING_APPROVAL_TTL_MILLIS)
            requestExpirations[requestId] = clock() + holdFor
        }
    }

    override fun approve(
        requestId: String,
        profile: LocalPairedProfile,
    ) {
        synchronized(pairingStateLock) {
            if (!statuses.containsKey(requestId) && !requestExpirations.containsKey(requestId)) return
            val current = statuses[requestId]
            if (current?.status in setOf(LocalPairingRequestStatus.Approved, LocalPairingRequestStatus.Rejected)) return
            // The operator already confirmed on this PC. Publish the profile even if
            // OpenSSH enrollment overran the original wait window or a poll marked it Expired.
            statuses[requestId] =
                LocalPairingStatusResponse(
                    status = LocalPairingRequestStatus.Approved,
                    message = "Approved on this PC",
                    profile = profile,
                )
            requestExpirations[requestId] = clock() + PAIRING_APPROVAL_TTL_MILLIS
        }
    }

    override fun reject(requestId: String) {
        synchronized(pairingStateLock) {
            val expiration = requestExpirations[requestId] ?: return
            val current = statuses[requestId]
            if (current?.status in setOf(LocalPairingRequestStatus.Approved, LocalPairingRequestStatus.Rejected, LocalPairingRequestStatus.Expired)) return
            statuses[requestId] =
                if (expiration < clock()) {
                    LocalPairingStatusResponse(
                        status = LocalPairingRequestStatus.Expired,
                        message = "Pairing approval request expired",
                    )
                } else {
                    LocalPairingStatusResponse(
                        status = LocalPairingRequestStatus.Rejected,
                        message = "Rejected on this PC",
                    )
                }
        }
    }

    override fun stop() {
        synchronized(pairingStateLock) {
            serverRunning = false
            dispatchingRequestIds.clear()
            statuses.clear()
            pairingChallenges.clear()
            requestExpirations.clear()
            attempts.clear()
            usedQrTokenIds.clear()
            currentPairingSession = null
            pairingRuntime = null
        }
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1_500)
        engine = null
    }

    private fun cleanupFailedDispatch(
        requestId: String,
        tokenId: String,
    ) {
        synchronized(pairingStateLock) {
            dispatchingRequestIds.remove(requestId)
            statuses.remove(requestId)
            requestExpirations.remove(requestId)
            usedQrTokenIds.remove(tokenId)
        }
    }

    private fun pendingRequestCount(): Int = statuses.values.count { it.status == LocalPairingRequestStatus.Pending }

    private fun elapsedMillis(startedAt: Long): Long = (clock() - startedAt).coerceAtLeast(0L)

    private fun emitDispatchRejected(
        callback: (PairingServerEvent) -> Unit,
        requestId: String,
        remoteHost: String,
        startedAt: Long,
        result: String,
    ) {
        emitPairingEvent(
            callback,
            PairingServerEvent(
                requestId = requestId,
                stage = PairingServerEventStage.PAIRING_REQUEST_DISPATCH_REJECTED,
                result = result,
                remoteHost = remoteHost,
                lifecycleGeneration = null,
                pendingCount = pendingRequestCount(),
                latencyMillis = elapsedMillis(startedAt),
            ),
        )
    }

    private fun emitPairingEvent(
        callback: (PairingServerEvent) -> Unit,
        event: PairingServerEvent,
    ) {
        callback(event)
    }

    private suspend fun issuePairingSession(runtime: PairingRuntime): LocalPairingSession {
        val pairingCode = codeGenerator.generate()
        val pairingSecret = authenticator.issueToken()
        val issuedAt = clock()
        val expiresAt = issuedAt + QR_TOKEN_TTL_MILLIS
        val tokenId = authenticator.issueToken()
        val pairingUrls = runtime.hosts.map { localPairingBaseUrl(it, port, secure = true) }
        val unsignedQr =
            LocalPairingQrPayload(
                version = 3,
                pairingUrl = pairingUrls.first(),
                pairingUrls = pairingUrls,
                pairingCode = pairingCode,
                agentFingerprint = runtime.agentFingerprint,
                pairingSecret = pairingSecret,
                tokenId = tokenId,
                issuedAtEpochMillis = issuedAt,
                expiresAtEpochMillis = expiresAt,
                hostIdentity = runtime.hostIdentity.publicIdentity,
            )
        val hostSignature =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                runtime.hostIdentity.sign(localPairingQrSignaturePayload(unsignedQr)),
            )
        return LocalPairingSession(
            host = runtime.hosts.first(),
            port = port,
            pairingCode = pairingCode,
            agentFingerprint = runtime.agentFingerprint,
            pairingSecret = pairingSecret,
            tokenId = tokenId,
            issuedAtEpochMillis = issuedAt,
            expiresAtEpochMillis = expiresAt,
            hostIdentity = runtime.hostIdentity.publicIdentity,
            hostSignature = hostSignature,
            alternativeHosts = runtime.hosts.drop(1),
        )
    }

    private fun allowAttempt(
        remoteHost: String,
        now: Long,
    ): Boolean {
        var allowed = false
        attempts.compute(remoteHost) { _, current ->
            val window =
                if (current == null || now - current.startedAtEpochMillis >= PAIRING_ATTEMPT_WINDOW_MILLIS) {
                    AttemptWindow(now, 0)
                } else {
                    current
                }
            allowed = window.count < MAX_PAIRING_ATTEMPTS_PER_WINDOW
            if (allowed) window.copy(count = window.count + 1) else window
        }
        return allowed
    }

    private data class ValidatedPairingRequest(
        val request: DesktopPairingRequest,
        val qrTokenId: String,
    )

    private data class AttemptWindow(
        val startedAtEpochMillis: Long,
        val count: Int,
    )

    private data class PairingRuntime(
        val agentFingerprint: String,
        val hosts: List<String>,
        val hostIdentity: LocalDeviceIdentity,
    )

    private companion object {
        const val PAIRING_CHALLENGE_TTL_MILLIS = 60_000L
        const val QR_TOKEN_TTL_MILLIS = 120_000L
        const val PAIRING_APPROVAL_TTL_MILLIS = 120_000L
        const val PAIRING_ATTEMPT_WINDOW_MILLIS = 60_000L
        const val MAX_PAIRING_ATTEMPTS_PER_WINDOW = 10
        const val USED_QR_TOKEN_RETENTION_MILLIS = 600_000L
        const val PROOF_TIMESTAMP_HEADER = "X-Aegis-Proof-Timestamp"
    }
}

private class LocalWebSocketTextTransport(
    private val socket: DefaultWebSocketSession,
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
        socket.close(CloseReason(CloseReason.Codes.NORMAL, "Local protocol channel closed"))
    }
}

private fun requestedPairingHost(
    localHost: String?,
    session: LocalPairingSession,
): String {
    val requestedHost =
        localHost
            ?.trim()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.substringBefore('%')
    return session.hosts.firstOrNull {
        it.substringBefore('%').equals(requestedHost, ignoreCase = true)
    } ?: session.host
}

private fun localHostAddresses(): List<String> {
    val defaultRouteAddress = defaultRouteLocalAddress()
    val candidates = runCatching { activeLocalAddressCandidates(defaultRouteAddress) }.getOrDefault(emptyList())
    val selected = selectAdvertisedHosts(candidates)
    if (selected.isNotEmpty()) return selected
    return listOfNotNull(
        runCatching { InetAddress.getLocalHost() }
            .getOrNull()
            ?.takeIf { it.isSiteLocalAddress || it.isIpv6UniqueLocal() }
            ?.hostAddress,
    ).ifEmpty { listOf("127.0.0.1") }
}

internal data class LocalAddressCandidate(
    val address: String,
    val interfaceName: String,
    val ipv4: Boolean,
    val siteLocal: Boolean,
    val linkLocal: Boolean,
    val hasBroadcast: Boolean,
    val supportsMulticast: Boolean,
    val virtualInterface: Boolean,
    val defaultRoute: Boolean = false,
    val pointToPoint: Boolean = false,
)

internal fun selectAdvertisedHost(candidates: List<LocalAddressCandidate>): String? = selectAdvertisedHosts(candidates).firstOrNull()

internal fun selectAdvertisedHosts(candidates: List<LocalAddressCandidate>): List<String> {
    val ranked =
        candidates
            .asSequence()
            // Link-local IPv6 addresses carry an interface scope that is meaningful only on this PC,
            // so an Android peer cannot safely reuse them from a QR code.
            .filter { it.siteLocal }
            .distinctBy { it.address.substringBefore('%').lowercase() }
            .sortedWith(
                compareByDescending<LocalAddressCandidate>(::localAddressScore)
                    .thenBy { it.interfaceName.lowercase() }
                    .thenBy { it.address },
            ).toList()
    val ipv4 = ranked.filter { it.ipv4 }
    return (if (ipv4.isNotEmpty()) ipv4 else ranked)
        .take(MAX_ADVERTISED_HOSTS)
        .map(LocalAddressCandidate::address)
}

private fun localAddressScore(candidate: LocalAddressCandidate): Int {
    val normalizedName = candidate.interfaceName.lowercase()
    val tunnelOrVirtual =
        candidate.virtualInterface || candidate.pointToPoint ||
            VIRTUAL_INTERFACE_MARKERS.any(normalizedName::contains)
    val wireless = WIRELESS_INTERFACE_MARKERS.any(normalizedName::contains)
    val ethernet = ETHERNET_INTERFACE_MARKERS.any(normalizedName::contains)
    var score = 0
    if (candidate.ipv4) score += 1_000
    if (candidate.siteLocal) score += 500
    if (candidate.hasBroadcast) score += 1_000
    if (candidate.supportsMulticast) score += 100
    if (wireless) score += 4_000
    if (ethernet) score += 2_000
    if (candidate.defaultRoute && !tunnelOrVirtual) score += 3_000
    if (candidate.linkLocal) score -= 3_000
    if (tunnelOrVirtual) score -= 20_000
    return score
}

private fun activeLocalAddressCandidates(defaultRouteAddress: String?): List<LocalAddressCandidate> {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
    val candidates = mutableListOf<LocalAddressCandidate>()
    while (interfaces.hasMoreElements()) {
        val networkInterface = interfaces.nextElement()
        if (!runCatching { networkInterface.isUp }.getOrDefault(false) || networkInterface.isLoopback) continue
        val supportsMulticast = runCatching { networkInterface.supportsMulticast() }.getOrDefault(false)
        val virtualInterface = runCatching { networkInterface.isVirtual }.getOrDefault(false)
        val pointToPoint = runCatching { networkInterface.isPointToPoint }.getOrDefault(false)
        val interfaceName =
            listOfNotNull(networkInterface.name, networkInterface.displayName)
                .distinct()
                .joinToString(" ")
        networkInterface.interfaceAddresses.forEach { interfaceAddress ->
            val address = interfaceAddress.address ?: return@forEach
            if (!isUsableLocalAddress(address)) return@forEach
            candidates +=
                LocalAddressCandidate(
                    address = address.hostAddress,
                    interfaceName = interfaceName,
                    ipv4 = address is Inet4Address,
                    siteLocal = address.isSiteLocalAddress || address.isIpv6UniqueLocal(),
                    linkLocal = address.isLinkLocalAddress,
                    hasBroadcast = interfaceAddress.broadcast != null,
                    supportsMulticast = supportsMulticast,
                    virtualInterface = virtualInterface,
                    defaultRoute = address.hostAddress.substringBefore('%') == defaultRouteAddress?.substringBefore('%'),
                    pointToPoint = pointToPoint,
                )
        }
    }
    return candidates
}

private fun defaultRouteLocalAddress(): String? =
    runCatching {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(DEFAULT_ROUTE_PROBE_ADDRESS, DEFAULT_ROUTE_PROBE_PORT))
            socket.localAddress
                ?.takeUnless { it.isAnyLocalAddress || it.isLoopbackAddress }
                ?.hostAddress
        }
    }.getOrNull()

private fun isUsableLocalAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return false
    return address is Inet4Address || address is Inet6Address
}

private fun InetAddress.isIpv6UniqueLocal(): Boolean {
    if (this !is Inet6Address) return false
    val first = address.first().toInt() and 0xff
    return first and 0xfe == 0xfc
}

private const val DEFAULT_ROUTE_PROBE_ADDRESS = "192.0.2.1"
private const val DEFAULT_ROUTE_PROBE_PORT = 9
private const val MAX_ADVERTISED_HOSTS = 8

private val VIRTUAL_INTERFACE_MARKERS =
    listOf(
        "virtual",
        "vmware",
        "vbox",
        "docker",
        "wsl",
        "hyper-v",
        "vethernet",
        "tailscale",
        "zerotier",
        "wireguard",
        "wintun",
        "utun",
        "tunnel",
        "tap adapter",
        "tun adapter",
        "hamachi",
        "vpn",
        "wan miniport",
    )

private val WIRELESS_INTERFACE_MARKERS = listOf("wi-fi", "wifi", "wireless", "wlan")
private val ETHERNET_INTERFACE_MARKERS = listOf("ethernet", "realtek pcie", "gigabit")
