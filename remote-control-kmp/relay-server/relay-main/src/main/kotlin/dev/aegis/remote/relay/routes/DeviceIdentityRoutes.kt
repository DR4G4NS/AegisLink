package dev.aegis.remote.relay.routes

import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.relay.RegisterRelayDeviceV2Request
import dev.aegis.remote.core.relay.RelayDeviceChallengeRequest
import dev.aegis.remote.core.relay.RelayDeviceEvent
import dev.aegis.remote.core.relay.RelayPayloadKind
import dev.aegis.remote.core.relay.RevokeRelayDeviceV2Request
import dev.aegis.remote.core.relay.RotateRelayDeviceKeyV2Request
import dev.aegis.remote.relay.IDENTITY_REVOKED_CLOSE
import dev.aegis.remote.relay.IDENTITY_ROTATED_CLOSE
import dev.aegis.remote.relay.RelayErrorResponse
import dev.aegis.remote.relay.RelayIdentityRejectedException
import dev.aegis.remote.relay.RelayServerDependencies
import dev.aegis.remote.relay.TOKEN_ROTATED_CLOSE
import dev.aegis.remote.relay.acquireRegistryMutation
import dev.aegis.remote.relay.rejectRateLimited
import dev.aegis.remote.relay.respondIdentityFailure
import dev.aegis.remote.relay.respondIdentityOperation
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

internal fun Route.installDeviceIdentityRoutes(dependencies: RelayServerDependencies) {
    post("/devices/register") {
        handleLegacyDeviceRegistration(call, dependencies)
    }
    post("/v3/devices/challenge") {
        handleDeviceChallenge(call, dependencies)
    }
    post("/v3/devices/register") {
        handleDeviceRegistration(call, dependencies)
    }
    post("/v3/devices/rotate-key") {
        handleDeviceKeyRotation(call, dependencies)
    }
    post("/v3/devices/revoke") {
        handleDeviceRevocation(call, dependencies)
    }
}

private suspend fun handleLegacyDeviceRegistration(
    call: ApplicationCall,
    dependencies: RelayServerDependencies,
) {
    if (call.rejectRateLimited(
            dependencies.rateLimiter,
            dependencies.rateLimitConfig,
            "devices-register",
            dependencies.trustForwardedHeaders,
            dependencies.metrics,
        )
    ) {
        return
    }
    call.respond(
        HttpStatusCode.Gone,
        RelayErrorResponse(
            error = "Legacy relay registration is disabled; use /v3/devices/challenge and /v3/devices/register",
            code = "LEGACY_REGISTRATION_DISABLED",
        ),
    )
}

private suspend fun handleDeviceChallenge(
    call: ApplicationCall,
    dependencies: RelayServerDependencies,
) {
    if (call.rejectRateLimited(
            dependencies.rateLimiter,
            dependencies.rateLimitConfig,
            "v2-devices-challenge",
            dependencies.trustForwardedHeaders,
            dependencies.metrics,
        )
    ) {
        return
    }
    val request = call.receive<RelayDeviceChallengeRequest>()
    dependencies.registry.refreshFromStore()
    call.respondIdentityOperation {
        dependencies.registry.issueRegistrationChallenge(request, dependencies.identityPolicy)
    }
}

private suspend fun handleDeviceRegistration(
    call: ApplicationCall,
    dependencies: RelayServerDependencies,
) {
    if (call.rejectRateLimited(
            dependencies.rateLimiter,
            dependencies.rateLimitConfig,
            "v2-devices-register",
            dependencies.trustForwardedHeaders,
            dependencies.metrics,
        )
    ) {
        return
    }
    val request = call.receive<RegisterRelayDeviceV2Request>()
    val lease = call.acquireRegistryMutation(dependencies.ephemeralRuntime) ?: return
    try {
        dependencies.registry.refreshFromStore()
        val response = dependencies.registry.registerV2(request, dependencies.identityPolicy)
        dependencies.signalingHub.invalidateDevice(response.relayDeviceId, TOKEN_ROTATED_CLOSE)
        dependencies.eventHub.invalidateDevice(response.relayDeviceId, TOKEN_ROTATED_CLOSE)
        call.respond(response)
    } catch (rejected: RelayIdentityRejectedException) {
        call.respondIdentityFailure(rejected)
    } finally {
        lease.close()
    }
}

private suspend fun handleDeviceKeyRotation(
    call: ApplicationCall,
    dependencies: RelayServerDependencies,
) {
    if (call.rejectRateLimited(
            dependencies.rateLimiter,
            dependencies.rateLimitConfig,
            "v2-devices-rotate-key",
            dependencies.trustForwardedHeaders,
            dependencies.metrics,
        )
    ) {
        return
    }
    val request = call.receive<RotateRelayDeviceKeyV2Request>()
    val lease = call.acquireRegistryMutation(dependencies.ephemeralRuntime) ?: return
    try {
        dependencies.registry.refreshFromStore()
        val response = dependencies.registry.rotateKeyV2(request, dependencies.identityPolicy)
        dependencies.signalingHub.invalidateDevice(response.relayDeviceId, IDENTITY_ROTATED_CLOSE)
        dependencies.eventHub.invalidateDevice(response.relayDeviceId, IDENTITY_ROTATED_CLOSE)
        response.invalidatedSessionIds.forEach { sessionId ->
            dependencies.signalingHub.closeSession(sessionId, "$IDENTITY_ROTATED_CLOSE session invalidated")
        }
        call.respond(response)
    } catch (rejected: RelayIdentityRejectedException) {
        call.respondIdentityFailure(rejected)
    } finally {
        lease.close()
    }
}

private suspend fun handleDeviceRevocation(
    call: ApplicationCall,
    dependencies: RelayServerDependencies,
) {
    if (call.rejectRateLimited(
            dependencies.rateLimiter,
            dependencies.rateLimitConfig,
            "v2-devices-revoke",
            dependencies.trustForwardedHeaders,
            dependencies.metrics,
        )
    ) {
        return
    }
    val request = call.receive<RevokeRelayDeviceV2Request>()
    val lease = call.acquireRegistryMutation(dependencies.ephemeralRuntime) ?: return
    try {
        dependencies.registry.refreshFromStore()
        val response = dependencies.registry.revokeV2(request, dependencies.identityPolicy)
        dependencies.signalingHub.invalidateDevice(response.relayDeviceId, IDENTITY_REVOKED_CLOSE)
        dependencies.eventHub.invalidateDevice(response.relayDeviceId, IDENTITY_REVOKED_CLOSE)
        response.invalidatedSessionIds.forEach { sessionId ->
            dependencies.signalingHub.closeSession(sessionId, "$IDENTITY_REVOKED_CLOSE session invalidated")
        }
        call.respond(response)
    } catch (rejected: RelayIdentityRejectedException) {
        call.respondIdentityFailure(rejected)
    } finally {
        lease.close()
    }
}
