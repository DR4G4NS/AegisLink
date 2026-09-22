package dev.aegis.remote.relay.routes

import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.relay.RegisterRelayDeviceV2Request
import dev.aegis.remote.core.relay.RelayDeviceChallengeRequest
import dev.aegis.remote.core.relay.RelayDeviceEvent
import dev.aegis.remote.core.relay.RelayPayloadKind
import dev.aegis.remote.core.relay.RevokeRelayDeviceV2Request
import dev.aegis.remote.core.relay.RotateRelayDeviceKeyV2Request
import dev.aegis.remote.relay.RelayServerDependencies
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

internal fun Route.installHealthRoutes(dependencies: RelayServerDependencies) {
    with(dependencies) {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        get("/live") {
            call.respond(mapOf("status" to "live"))
        }
        get("/ready") {
            if (readinessCheck()) {
                call.respond(mapOf("status" to "ready"))
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "not_ready"))
            }
        }
        get("/metrics") {
            call.respondText(metrics.prometheus(connectionLimiter.active()), ContentType.Text.Plain)
        }
    }
}
