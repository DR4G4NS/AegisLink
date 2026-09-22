package dev.aegis.remote.relay.routes

import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.relay.RegisterRelayDeviceV2Request
import dev.aegis.remote.core.relay.RelayDeviceChallengeRequest
import dev.aegis.remote.core.relay.RelayDeviceEvent
import dev.aegis.remote.core.relay.RelayPayloadKind
import dev.aegis.remote.core.relay.RevokeRelayDeviceV2Request
import dev.aegis.remote.core.relay.RotateRelayDeviceKeyV2Request
import dev.aegis.remote.relay.AUTHORIZATION_CLOSE
import dev.aegis.remote.relay.BACKPRESSURE_CLOSE
import dev.aegis.remote.relay.CONNECTION_LIMIT_CLOSE
import dev.aegis.remote.relay.FRAME_TOO_LARGE_CLOSE
import dev.aegis.remote.relay.INVALID_ENVELOPE_CLOSE
import dev.aegis.remote.relay.MALFORMED_ENVELOPE_CLOSE
import dev.aegis.remote.relay.MAX_WEBSOCKET_FRAME_BYTES
import dev.aegis.remote.relay.PARTICIPANT_LEASE_LOST_CLOSE
import dev.aegis.remote.relay.PARTICIPANT_LIMIT_CLOSE
import dev.aegis.remote.relay.RATE_LIMIT_CLOSE
import dev.aegis.remote.relay.RUNTIME_UNAVAILABLE_CLOSE
import dev.aegis.remote.relay.RelayRendezvousSession
import dev.aegis.remote.relay.RelayServerDependencies
import dev.aegis.remote.relay.RelaySignalingSubscription
import dev.aegis.remote.relay.RelayWebSocketEnvelope
import dev.aegis.remote.relay.SESSION_EXPIRED_CLOSE
import dev.aegis.remote.relay.TOKEN_EXPIRED_CLOSE
import dev.aegis.remote.relay.acceptsSignalingEnvelope
import dev.aegis.remote.relay.allowWebSocketAttempt
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
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
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

internal fun Route.installSignalingWebSocketRoutes(dependencies: RelayServerDependencies) {
    webSocket("/signaling/{sessionId}/{relayDeviceId}") {
        handleSignalingConnection(dependencies)
    }
}

private data class SignalingConnectionContext(
    val sessionId: SessionId,
    val relayDeviceId: RelayDeviceId,
    val token: String,
    val authExpiresAt: Long,
    val session: RelayRendezvousSession,
    val subscription: RelaySignalingSubscription,
    val recipientRelayDeviceId: RelayDeviceId,
)

private suspend fun DefaultWebSocketServerSession.handleSignalingConnection(
    dependencies: RelayServerDependencies,
) {
    val context = prepareSignalingConnection(dependencies) ?: return
    val deliveryJob =
        launch {
            dependencies.signalingHub.pump(
                context.sessionId,
                context.subscription,
                this@handleSignalingConnection,
            )
        }
    val connectionExpiresAt = minOf(context.authExpiresAt, context.session.session.expiresAtEpochMillis)
    val expirationReason =
        if (context.session.session.expiresAtEpochMillis <= context.authExpiresAt) {
            SESSION_EXPIRED_CLOSE
        } else {
            TOKEN_EXPIRED_CLOSE
        }
    val expiryJob =
        launch {
            delay((connectionExpiresAt - dependencies.runtimeConfig.clock()).coerceAtLeast(0L))
            close(
                io.ktor.websocket.CloseReason(
                    io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY,
                    expirationReason,
                ),
            )
        }
    try {
        for (frame in incoming) {
            if (!processSignalingFrame(frame, context, dependencies)) return
        }
    } finally {
        deliveryJob.cancel()
        expiryJob.cancel()
        dependencies.signalingHub.leave(context.sessionId, context.subscription, this@handleSignalingConnection)
        dependencies.connectionLimiter.close()
    }
}

private suspend fun DefaultWebSocketServerSession.prepareSignalingConnection(
    dependencies: RelayServerDependencies,
): SignalingConnectionContext? {
    if (!call.allowWebSocketAttempt(
            dependencies.rateLimiter,
            dependencies.rateLimitConfig,
            "signaling",
            dependencies.trustForwardedHeaders,
        )
    ) {
        close(
            io.ktor.websocket.CloseReason(
                io.ktor.websocket.CloseReason.Codes.TRY_AGAIN_LATER,
                "${RATE_LIMIT_CLOSE} retry later",
            ),
        )
        return null
    }
    val sessionId = SessionId(call.parameters["sessionId"] ?: "")
    val relayDeviceId = RelayDeviceId(call.parameters["relayDeviceId"] ?: "")
    val token =
        call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")
            ?.trim()
    dependencies.registry.refreshFromStore()
    val session = dependencies.registry.getApprovedSession(sessionId)
    val authExpiresAt = token?.let { dependencies.registry.authenticationExpiresAt(relayDeviceId, it) }
    if (session == null || authExpiresAt == null || relayDeviceId !in setOf(session.sourceRelayDeviceId, session.targetRelayDeviceId)) {
        close(
            io.ktor.websocket.CloseReason(
                io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY,
                AUTHORIZATION_CLOSE,
            ),
        )
        return null
    }
    if (!dependencies.connectionLimiter.tryOpen()) {
        dependencies.metrics.websocketRejected()
        close(
            io.ktor.websocket.CloseReason(
                io.ktor.websocket.CloseReason.Codes.TRY_AGAIN_LATER,
                "${CONNECTION_LIMIT_CLOSE} retry later",
            ),
        )
        return null
    }
    dependencies.metrics.websocketAccepted()
    val subscription = dependencies.signalingHub.join(sessionId, relayDeviceId, this)
    if (subscription == null) {
        dependencies.metrics.websocketRejected()
        dependencies.connectionLimiter.close()
        close(
            io.ktor.websocket.CloseReason(
                io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY,
                "${PARTICIPANT_LIMIT_CLOSE} duplicate participant",
            ),
        )
        return null
    }
    val recipientRelayDeviceId =
        if (relayDeviceId == session.sourceRelayDeviceId) {
            session.targetRelayDeviceId
        } else {
            session.sourceRelayDeviceId
        }
    return SignalingConnectionContext(
        sessionId = sessionId,
        relayDeviceId = relayDeviceId,
        token = requireNotNull(token),
        authExpiresAt = requireNotNull(authExpiresAt),
        session = session,
        subscription = subscription,
        recipientRelayDeviceId = recipientRelayDeviceId,
    )
}

private suspend fun DefaultWebSocketServerSession.processSignalingFrame(
    frame: Frame,
    context: SignalingConnectionContext,
    dependencies: RelayServerDependencies,
): Boolean =
    when (frame) {
        is Frame.Text -> {
            processSignalingTextFrame(frame, context, dependencies)
        }

        is Frame.Binary -> {
            close(
                io.ktor.websocket.CloseReason(
                    io.ktor.websocket.CloseReason.Codes.CANNOT_ACCEPT,
                    "${INVALID_ENVELOPE_CLOSE} text frames required",
                ),
            )
            false
        }

        else -> {
            true
        }
    }

private suspend fun DefaultWebSocketServerSession.processSignalingTextFrame(
    frame: Frame.Text,
    context: SignalingConnectionContext,
    dependencies: RelayServerDependencies,
): Boolean {
    if (!ownsParticipantLease(context, dependencies)) return false
    if (!authorizationIsValid(context, dependencies)) return false
    if (frame.data.size > MAX_WEBSOCKET_FRAME_BYTES) {
        close(
            io.ktor.websocket.CloseReason(
                io.ktor.websocket.CloseReason.Codes.TOO_BIG,
                "${FRAME_TOO_LARGE_CLOSE} max 128 KiB",
            ),
        )
        return false
    }
    val envelope = decodeSignalingEnvelope(frame, dependencies.json) ?: return false
    val activeSession = dependencies.registry.getApprovedSession(context.sessionId)
    if (!activeSession.acceptsSignalingEnvelope(
            context.relayDeviceId,
            context.sessionId,
            envelope,
            dependencies.allowLegacyPlaintextSignaling,
        )
    ) {
        close(
            io.ktor.websocket.CloseReason(
                io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY,
                "${INVALID_ENVELOPE_CLOSE} metadata or payload kind rejected",
            ),
        )
        return false
    }
    val queued =
        dependencies.signalingHub.broadcast(
            sessionId = context.sessionId,
            senderRelayDeviceId = context.relayDeviceId,
            recipientRelayDeviceId = context.recipientRelayDeviceId,
            envelope = envelope,
        )
    if (!queued) {
        close(
            io.ktor.websocket.CloseReason(
                io.ktor.websocket.CloseReason.Codes.TRY_AGAIN_LATER,
                "${BACKPRESSURE_CLOSE} recipient mailbox full",
            ),
        )
        return false
    }
    dependencies.metrics.envelopeForwarded()
    return true
}

private suspend fun DefaultWebSocketServerSession.ownsParticipantLease(
    context: SignalingConnectionContext,
    dependencies: RelayServerDependencies,
): Boolean {
    val owned =
        try {
            dependencies.signalingHub.ownsParticipantLease(context.sessionId, context.subscription)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            close(
                io.ktor.websocket.CloseReason(
                    io.ktor.websocket.CloseReason.Codes.INTERNAL_ERROR,
                    RUNTIME_UNAVAILABLE_CLOSE,
                ),
            )
            return false
        }
    if (!owned) {
        close(
            io.ktor.websocket.CloseReason(
                io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY,
                PARTICIPANT_LEASE_LOST_CLOSE,
            ),
        )
    }
    return owned
}

private suspend fun DefaultWebSocketServerSession.authorizationIsValid(
    context: SignalingConnectionContext,
    dependencies: RelayServerDependencies,
): Boolean {
    val authorizationInvalidated =
        dependencies.runtimeMessageBus.deviceInvalidation(context.relayDeviceId)?.id !=
            context.subscription.invalidationIdAtJoin
    val tokenExpired =
        dependencies.registry.authenticationExpiresAt(context.relayDeviceId, context.token) == null
    val sessionInvalidated =
        dependencies.runtimeMessageBus.sessionInvalidation(context.sessionId) != null
    if (authorizationInvalidated || tokenExpired || sessionInvalidated) {
        close(
            io.ktor.websocket.CloseReason(
                io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY,
                AUTHORIZATION_CLOSE,
            ),
        )
        return false
    }
    return true
}

private suspend fun DefaultWebSocketServerSession.decodeSignalingEnvelope(
    frame: Frame.Text,
    json: Json,
): RelayWebSocketEnvelope? =
    runCatching { json.decodeFromString<RelayWebSocketEnvelope>(frame.readText()) }
        .getOrElse {
            close(
                io.ktor.websocket.CloseReason(
                    io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY,
                    "${MALFORMED_ENVELOPE_CLOSE} invalid JSON",
                ),
            )
            null
        }
