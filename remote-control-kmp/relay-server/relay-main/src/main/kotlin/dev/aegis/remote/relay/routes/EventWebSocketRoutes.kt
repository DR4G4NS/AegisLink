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
import dev.aegis.remote.relay.CONNECTION_LIMIT_CLOSE
import dev.aegis.remote.relay.DEVICE_SUBSCRIBER_LEASE_LOST_CLOSE
import dev.aegis.remote.relay.EVENT_RECEIVE_ONLY_CLOSE
import dev.aegis.remote.relay.RATE_LIMIT_CLOSE
import dev.aegis.remote.relay.RelayServerDependencies
import dev.aegis.remote.relay.TOKEN_EXPIRED_CLOSE
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

internal fun Route.installEventWebSocketRoutes(dependencies: RelayServerDependencies) {
    with(dependencies) {
        webSocket("/devices/{relayDeviceId}/events") {
            if (!call.allowWebSocketAttempt(rateLimiter, rateLimitConfig, "device-events", trustForwardedHeaders)) {
                close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.TRY_AGAIN_LATER, "$RATE_LIMIT_CLOSE retry later"))
                return@webSocket
            }
            val relayDeviceId = RelayDeviceId(call.parameters["relayDeviceId"] ?: "")
            val token =
                call.request.headers["Authorization"]
                    ?.removePrefix("Bearer ")
                    ?.trim()
            val authExpiresAt = token?.let { registry.authenticationExpiresAt(relayDeviceId, it) }
            if (authExpiresAt == null) {
                close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, AUTHORIZATION_CLOSE))
                return@webSocket
            }
            if (!connectionLimiter.tryOpen()) {
                metrics.websocketRejected()
                close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.TRY_AGAIN_LATER, "$CONNECTION_LIMIT_CLOSE retry later"))
                return@webSocket
            }
            metrics.websocketAccepted()

            val subscription = eventHub.join(relayDeviceId, this)
            if (subscription == null) {
                metrics.websocketRejected()
                connectionLimiter.close()
                close(
                    io.ktor.websocket.CloseReason(
                        io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY,
                        "$DEVICE_SUBSCRIBER_LEASE_LOST_CLOSE duplicate device event subscriber",
                    ),
                )
                return@webSocket
            }
            val deliveryJob = launch { eventHub.pump(relayDeviceId, subscription, this@webSocket) }
            val expiryJob =
                launch {
                    delay((authExpiresAt - runtimeConfig.clock()).coerceAtLeast(0L))
                    close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, TOKEN_EXPIRED_CLOSE))
                }
            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Close -> {
                            return@webSocket
                        }

                        is Frame.Text,
                        is Frame.Binary,
                        -> {
                            close(
                                io.ktor.websocket.CloseReason(
                                    io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY,
                                    "$EVENT_RECEIVE_ONLY_CLOSE client frames are forbidden",
                                ),
                            )
                            return@webSocket
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            } finally {
                deliveryJob.cancel()
                expiryJob.cancel()
                eventHub.leave(relayDeviceId, subscription, this)
                connectionLimiter.close()
            }
        }
    }
}
