package dev.aegis.remote.relay

import kotlinx.serialization.json.Json

internal data class RelayServerDependencies(
    val registry: InMemoryRelayRegistry,
    val turnCredentialIssuer: TurnCredentialIssuer?,
    val rateLimiter: RelayRateLimiter,
    val rateLimitConfig: RelayRateLimitConfig,
    val identityPolicy: RelayIdentityPolicy,
    val allowLegacyPlaintextSignaling: Boolean,
    val runtimeConfig: RelayServerRuntimeConfig,
    val json: Json,
    val ephemeralRuntime: RelayEphemeralRuntime?,
    val readinessCheck: () -> Boolean,
    val trustForwardedHeaders: Boolean,
    val metrics: RelayMetrics,
    val runtimeMessageBus: RelayRuntimeMessageBus,
    val signalingHub: RelaySignalingHub,
    val eventHub: RelayDeviceEventHub,
    val connectionLimiter: RelayConnectionLimiter,
)
