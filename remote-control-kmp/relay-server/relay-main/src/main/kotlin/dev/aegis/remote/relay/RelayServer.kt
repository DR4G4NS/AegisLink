package dev.aegis.remote.relay

import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.relay.RegisterRelayDeviceV2Request
import dev.aegis.remote.core.relay.RelayDeviceChallengeRequest
import dev.aegis.remote.core.relay.RelayDeviceEvent
import dev.aegis.remote.core.relay.RelayPayloadKind
import dev.aegis.remote.core.relay.RevokeRelayDeviceV2Request
import dev.aegis.remote.core.relay.RotateRelayDeviceKeyV2Request
import dev.aegis.remote.relay.routes.installDeviceIdentityRoutes
import dev.aegis.remote.relay.routes.installEventWebSocketRoutes
import dev.aegis.remote.relay.routes.installHealthRoutes
import dev.aegis.remote.relay.routes.installSessionRoutes
import dev.aegis.remote.relay.routes.installSignalingWebSocketRoutes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64

fun Application.relayServerModule(
    registry: InMemoryRelayRegistry = InMemoryRelayRegistry(),
    turnCredentialIssuer: TurnCredentialIssuer? = environmentTurnCredentialIssuer(),
    rateLimiter: RelayRateLimiter = InMemoryRelayRateLimiter(),
    rateLimitConfig: RelayRateLimitConfig = RelayRateLimitConfig(),
    identityPolicy: RelayIdentityPolicy = RelayIdentityPolicy(),
    allowLegacyPlaintextSignaling: Boolean = false,
    runtimeConfig: RelayServerRuntimeConfig = RelayServerRuntimeConfig(),
    json: Json = relayJson,
) {
    val ephemeralRuntime = runtimeConfig.ephemeralRuntime
    val runtimeMessageBus =
        runtimeConfig.messageBus
            ?: (ephemeralRuntime as? RelayRuntimeMessageBus)
            ?: InMemoryRelayRuntimeMessageBus(runtimeConfig.clock)
    val dependencies =
        RelayServerDependencies(
            registry = registry,
            turnCredentialIssuer = turnCredentialIssuer,
            rateLimiter = rateLimiter,
            rateLimitConfig = rateLimitConfig,
            identityPolicy = identityPolicy,
            allowLegacyPlaintextSignaling = allowLegacyPlaintextSignaling,
            runtimeConfig = runtimeConfig,
            json = json,
            ephemeralRuntime = ephemeralRuntime,
            readinessCheck = runtimeConfig.readinessCheck,
            trustForwardedHeaders = runtimeConfig.trustForwardedHeaders,
            metrics = runtimeConfig.metrics,
            runtimeMessageBus = runtimeMessageBus,
            signalingHub = RelaySignalingHub(json, ephemeralRuntime, runtimeMessageBus),
            eventHub = RelayDeviceEventHub(json, ephemeralRuntime, runtimeMessageBus),
            connectionLimiter = RelayConnectionLimiter(runtimeConfig.maximumWebSocketConnections),
        )

    install(ContentNegotiation) {
        json(json)
    }
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 45_000
        maxFrameSize = MAX_WEBSOCKET_FRAME_BYTES
    }

    routing {
        installHealthRoutes(dependencies)
        installDeviceIdentityRoutes(dependencies)
        installSessionRoutes(dependencies)
        installEventWebSocketRoutes(dependencies)
        installSignalingWebSocketRoutes(dependencies)
    }
}

internal suspend fun ApplicationCall.acquireRegistryMutation(runtime: RelayEphemeralRuntime?): AutoCloseable? {
    if (runtime == null) return AutoCloseable {}
    val lease = runtime.acquireLock("registry-mutation", 10_000)
    if (lease == null) {
        respond(HttpStatusCode.Conflict, RelayErrorResponse("Another relay mutation is in progress"))
    }
    return lease
}

internal suspend fun <T : Any> ApplicationCall.respondIdentityOperation(operation: () -> T) {
    try {
        respond(operation() as Any)
    } catch (rejected: RelayIdentityRejectedException) {
        respondIdentityFailure(rejected)
    }
}

internal fun RelayRendezvousSession?.acceptsSignalingEnvelope(
    relayDeviceId: RelayDeviceId,
    sessionId: SessionId,
    envelope: RelayWebSocketEnvelope,
    allowLegacyPlaintextSignaling: Boolean,
): Boolean {
    val activeSession = this ?: return false
    if (relayDeviceId != activeSession.sourceRelayDeviceId && relayDeviceId != activeSession.targetRelayDeviceId) return false
    if (envelope.protocolVersion != 1) return false
    if (envelope.sessionId != sessionId) return false
    if (envelope.senderRelayDeviceId != relayDeviceId) return false
    if (!allowLegacyPlaintextSignaling && envelope.payloadKind == RelayPayloadKind.LEGACY_PLAINTEXT) return false
    return true
}

internal suspend fun ApplicationCall.respondIdentityFailure(rejected: RelayIdentityRejectedException) {
    val status =
        when (rejected.code) {
            RelayIdentityRejectionCode.UnsupportedProtocolVersion,
            RelayIdentityRejectionCode.InvalidIdentity,
            RelayIdentityRejectionCode.P256CurveSubstitution,
            RelayIdentityRejectionCode.DeviceIdMismatch,
            RelayIdentityRejectionCode.ChallengeIdentityMismatch,
            RelayIdentityRejectionCode.InvalidRotation,
            -> HttpStatusCode.BadRequest

            RelayIdentityRejectionCode.ChallengeMissingOrReused,
            RelayIdentityRejectionCode.ChallengeExpired,
            RelayIdentityRejectionCode.RelayOriginMismatch,
            RelayIdentityRejectionCode.ClockSkew,
            RelayIdentityRejectionCode.InvalidProof,
            RelayIdentityRejectionCode.RotationUnauthorized,
            RelayIdentityRejectionCode.RevocationUnauthorized,
            -> HttpStatusCode.Forbidden

            RelayIdentityRejectionCode.IdentityKeyMismatch,
            RelayIdentityRejectionCode.IdentityRevoked,
            -> HttpStatusCode.Conflict

            RelayIdentityRejectionCode.UnknownRelayDevice -> HttpStatusCode.NotFound
        }
    respond(status, RelayErrorResponse(error = rejected.message, code = rejected.code.name))
}

internal suspend fun ApplicationCall.rejectRateLimited(
    rateLimiter: RelayRateLimiter,
    config: RelayRateLimitConfig,
    action: String,
    trustForwardedHeaders: Boolean,
    metrics: RelayMetrics,
): Boolean {
    val decision = rateLimiter.tryAcquire(rateLimitKey(action, trustForwardedHeaders), config.requestsPerWindow, config.windowMillis)
    if (decision.allowed) return false

    metrics.rateLimited()
    response.headers.append("Retry-After", ((decision.retryAfterMillis + 999) / 1_000).coerceAtLeast(1).toString())
    respond(HttpStatusCode.TooManyRequests, RelayErrorResponse("Rate limit exceeded"))
    return true
}

internal fun ApplicationCall.allowWebSocketAttempt(
    rateLimiter: RelayRateLimiter,
    config: RelayRateLimitConfig,
    action: String,
    trustForwardedHeaders: Boolean,
): Boolean = rateLimiter.tryAcquire(rateLimitKey(action, trustForwardedHeaders), config.websocketAttemptsPerWindow, config.windowMillis).allowed

private fun ApplicationCall.rateLimitKey(
    action: String,
    trustForwardedHeaders: Boolean,
): String {
    val forwardedFor =
        request.headers["X-Forwarded-For"]
            ?.takeIf { trustForwardedHeaders }
            ?.substringBefore(",")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    val realIp =
        request.headers["X-Real-IP"]
            ?.takeIf { trustForwardedHeaders }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    val relayDeviceId = request.headers["X-Relay-Device-Id"]?.trim()?.takeIf { it.isNotBlank() }
    val authFingerprint =
        request.headers["Authorization"]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::rateLimitFingerprint)
    val actor = forwardedFor ?: realIp ?: relayDeviceId ?: authFingerprint ?: "anonymous"
    return "$action:$actor"
}

private fun rateLimitFingerprint(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return Base64
        .getUrlEncoder()
        .withoutPadding()
        .encodeToString(digest)
        .take(24)
}

val relayJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

internal const val MAX_WEBSOCKET_FRAME_BYTES = 128L * 1024L
internal const val AUTHORIZATION_CLOSE = "REL-7404 relay authorization failed"
internal const val TOKEN_ROTATED_CLOSE = "REL-7405 relay token rotated"
internal const val TOKEN_EXPIRED_CLOSE = "REL-7406 relay token expired"
internal const val EVENT_RECEIVE_ONLY_CLOSE = "REL-7407 device event stream is receive-only"
internal const val RATE_LIMIT_CLOSE = "REL-7408 rate limit exceeded"
internal const val CONNECTION_LIMIT_CLOSE = "REL-7409 connection capacity exceeded"
internal const val PARTICIPANT_LIMIT_CLOSE = "SES-7402 session participant limit exceeded"
internal const val SESSION_EXPIRED_CLOSE = "SES-7403 relay session expired"
internal const val FRAME_TOO_LARGE_CLOSE = "REL-7410 relay envelope too large"
internal const val MALFORMED_ENVELOPE_CLOSE = "REL-7411 malformed relay envelope"
internal const val INVALID_ENVELOPE_CLOSE = "REL-7412 relay envelope rejected"
internal const val IDENTITY_ROTATED_CLOSE = "IDN-7410 relay identity rotated"
internal const val IDENTITY_REVOKED_CLOSE = "IDN-7411 relay identity revoked"
internal const val EVENT_BACKPRESSURE_CODE = "REL-7413"
