package dev.aegis.remote.relay.routes

import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.relay.RegisterRelayDeviceV2Request
import dev.aegis.remote.core.relay.RelayDeviceChallengeRequest
import dev.aegis.remote.core.relay.RelayDeviceEvent
import dev.aegis.remote.core.relay.RelayPayloadKind
import dev.aegis.remote.core.relay.RevokeRelayDeviceV2Request
import dev.aegis.remote.core.relay.RotateRelayDeviceKeyV2Request
import dev.aegis.remote.relay.CreateRelaySessionRequest
import dev.aegis.remote.relay.EVENT_BACKPRESSURE_CODE
import dev.aegis.remote.relay.RelayErrorResponse
import dev.aegis.remote.relay.RelayServerDependencies
import dev.aegis.remote.relay.RelaySessionApprovalRequest
import dev.aegis.remote.relay.acquireRegistryMutation
import dev.aegis.remote.relay.rejectRateLimited
import dev.aegis.remote.relay.safeRelayId
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

internal fun Route.installSessionRoutes(dependencies: RelayServerDependencies) {
    post("/sessions") {
        handleCreateSession(call, dependencies)
    }
    post("/sessions/{sessionId}/approval") {
        handleSessionApproval(call, dependencies)
    }
    get("/turn/credentials") {
        handleTurnCredentials(call, dependencies)
    }
}

private suspend fun handleCreateSession(
    call: ApplicationCall,
    dependencies: RelayServerDependencies,
) {
    with(dependencies) {
        if (call.rejectRateLimited(rateLimiter, rateLimitConfig, "sessions-create", trustForwardedHeaders, metrics)) return
        val request = call.receive<CreateRelaySessionRequest>()
        val token =
            call.request.headers["Authorization"]
                ?.removePrefix("Bearer ")
                ?.trim()
        if (token == null || !registry.authenticate(request.sourceRelayDeviceId, token)) {
            call.respond(HttpStatusCode.Unauthorized, RelayErrorResponse("Invalid source relay token"))
            return
        }
        val lease = call.acquireRegistryMutation(ephemeralRuntime) ?: return
        val response =
            try {
                registry.refreshFromStore()
                registry.createSession(request.sourceRelayDeviceId, request.targetRelayDeviceId)
            } finally {
                lease.close()
            }
        if (response == null) {
            call.respond(HttpStatusCode.NotFound, RelayErrorResponse("Unable to create relay session"))
            return
        }
        val requestQueued =
            eventHub.publish(
                request.targetRelayDeviceId,
                RelayDeviceEvent.SessionRequested(
                    sessionId = response.session.sessionId,
                    sourceRelayDeviceId = response.sourceRelayDeviceId,
                    targetRelayDeviceId = response.targetRelayDeviceId,
                    expiresAtEpochMillis = response.session.expiresAtEpochMillis,
                    sourceIdentity = response.sourceIdentity,
                ),
            )
        if (!requestQueued) {
            registry.cancelSession(response.session.sessionId)
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                RelayErrorResponse("Target event mailbox is at capacity", EVENT_BACKPRESSURE_CODE),
            )
            return
        }
        metrics.sessionCreated()
        call.application.environment.log.info(
            "{\"event\":\"relay_session_created\",\"session\":\"{}\",\"source\":\"{}\",\"target\":\"{}\"}",
            safeRelayId(response.session.sessionId.value),
            safeRelayId(response.sourceRelayDeviceId.value),
            safeRelayId(response.targetRelayDeviceId.value),
        )
        call.respond(response)
    }
}

private suspend fun handleSessionApproval(
    call: ApplicationCall,
    dependencies: RelayServerDependencies,
) {
    with(dependencies) {
        if (call.rejectRateLimited(rateLimiter, rateLimitConfig, "sessions-approval", trustForwardedHeaders, metrics)) return
        val sessionId = SessionId(call.parameters["sessionId"] ?: "")
        val request = call.receive<RelaySessionApprovalRequest>()
        val targetRelayDeviceId =
            RelayDeviceId(
                call.request.headers["X-Relay-Device-Id"]
                    ?.trim()
                    .orEmpty(),
            )
        val token =
            call.request.headers["Authorization"]
                ?.removePrefix("Bearer ")
                ?.trim()
        if (token == null || !registry.authenticate(targetRelayDeviceId, token)) {
            call.respond(HttpStatusCode.Unauthorized, RelayErrorResponse("Invalid target relay token"))
            return
        }
        val lease = call.acquireRegistryMutation(ephemeralRuntime) ?: return
        val approval =
            try {
                registry.refreshFromStore()
                registry.decideSession(
                    sessionId = sessionId,
                    target = targetRelayDeviceId,
                    approved = request.approved,
                )
            } finally {
                lease.close()
            }
        if (approval == null) {
            call.respond(HttpStatusCode.NotFound, RelayErrorResponse("Relay session not found for target"))
            return
        }
        val decisionQueued =
            eventHub.publish(
                approval.sourceRelayDeviceId,
                if (approval.approved) {
                    RelayDeviceEvent.SessionApproved(
                        sessionId = approval.sessionId,
                        sourceRelayDeviceId = approval.sourceRelayDeviceId,
                        targetRelayDeviceId = approval.targetRelayDeviceId,
                        decidedAtEpochMillis = approval.decidedAtEpochMillis,
                        targetIdentity = approval.targetIdentity,
                    )
                } else {
                    RelayDeviceEvent.SessionRejected(
                        sessionId = approval.sessionId,
                        sourceRelayDeviceId = approval.sourceRelayDeviceId,
                        targetRelayDeviceId = approval.targetRelayDeviceId,
                        decidedAtEpochMillis = approval.decidedAtEpochMillis,
                        targetIdentity = approval.targetIdentity,
                    )
                },
            )
        if (!decisionQueued) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                RelayErrorResponse("Source event mailbox is at capacity; retry this decision", EVENT_BACKPRESSURE_CODE),
            )
            return
        }
        metrics.approvalDecided()
        call.application.environment.log.info(
            "{\"event\":\"relay_session_decided\",\"session\":\"{}\",\"approved\":{}}",
            safeRelayId(approval.sessionId.value),
            approval.approved,
        )
        call.respond(approval)
    }
}

private suspend fun handleTurnCredentials(
    call: ApplicationCall,
    dependencies: RelayServerDependencies,
) {
    with(dependencies) {
        if (call.rejectRateLimited(rateLimiter, rateLimitConfig, "turn-credentials", trustForwardedHeaders, metrics)) return
        val issuer = turnCredentialIssuer
        if (issuer == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, RelayErrorResponse("TURN credential issuing is not configured"))
            return
        }
        val relayDeviceId =
            RelayDeviceId(
                call.request.headers["X-Relay-Device-Id"]
                    ?.trim()
                    .orEmpty(),
            )
        val token =
            call.request.headers["Authorization"]
                ?.removePrefix("Bearer ")
                ?.trim()
        if (token == null || !registry.authenticate(relayDeviceId, token)) {
            call.respond(HttpStatusCode.Unauthorized, RelayErrorResponse("Invalid relay token"))
            return
        }
        call.respond(issuer.issue(relayDeviceId))
    }
}
