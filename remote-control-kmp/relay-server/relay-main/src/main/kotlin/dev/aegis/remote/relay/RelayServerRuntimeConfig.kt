package dev.aegis.remote.relay

data class RelayServerRuntimeConfig(
    val ephemeralRuntime: RelayEphemeralRuntime? = null,
    val messageBus: RelayRuntimeMessageBus? = null,
    val readinessCheck: () -> Boolean = { true },
    val trustForwardedHeaders: Boolean = false,
    val maximumWebSocketConnections: Int = 1_000,
    val metrics: RelayMetrics = RelayMetrics(),
    val clock: () -> Long = { System.currentTimeMillis() },
)
