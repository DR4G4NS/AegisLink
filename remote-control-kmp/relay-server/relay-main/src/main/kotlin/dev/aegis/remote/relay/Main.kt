package dev.aegis.remote.relay

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Path
import java.util.Base64

fun main() {
    val runtime = environmentRelayRuntime()
    try {
        embeddedServer(Netty, host = "0.0.0.0", port = runtime.port) {
            relayServerModule(
                registry = runtime.registry,
                rateLimiter = runtime.rateLimiter,
                identityPolicy = runtime.identityPolicy,
                runtimeConfig = runtime.serverConfig,
            )
        }.start(wait = true)
    } finally {
        runtime.close()
    }
}

private fun environmentRelayRuntime(): RelayApplicationRuntime {
    val storagePath =
        environmentValue("AEGIS_RELAY_STORAGE_PATH")?.let(Path::of)
    val rateLimitStoragePath =
        environmentValue("AEGIS_RELAY_RATE_LIMIT_STORAGE_PATH")?.let(Path::of)
    val identityPolicy =
        RelayIdentityPolicy(
            relayOrigin = System.getenv("AEGIS_RELAY_ORIGIN")?.trim()?.takeIf { it.isNotEmpty() } ?: "aegis-relay",
        )
    val databaseUrl = environmentValue("AEGIS_DATABASE_URL")
    val redisUrl = environmentValue("AEGIS_REDIS_URL")
    val developmentMode = environmentValue("AEGIS_RELAY_DEVELOPMENT_MODE")?.toBooleanStrictOrNull() == true
    val tokenHashSecret = environmentRelayTokenHashSecret()
    require((databaseUrl == null) == (redisUrl == null)) {
        "AEGIS_DATABASE_URL and AEGIS_REDIS_URL must be configured together for durable production mode"
    }
    require(databaseUrl != null || developmentMode) {
        "Configure PostgreSQL and Redis, or explicitly set AEGIS_RELAY_DEVELOPMENT_MODE=true for the in-memory/JSON runtime"
    }
    val closeables = mutableListOf<AutoCloseable>()
    val postgresStore =
        databaseUrl?.let {
            createPostgresRelayStore(
                jdbcUrl = it,
                username = environmentValue("AEGIS_DATABASE_USER"),
                password = environmentValue("AEGIS_DATABASE_PASSWORD"),
            ).also { (_, closeable) -> closeables += closeable }.first
        }
    val redisRuntime =
        redisUrl?.let { RedisRelayRuntimeStore(it, tokenHashSecret).also(closeables::add) }
    val registry =
        InMemoryRelayRegistry(
            store = postgresStore ?: storagePath?.let { JsonRelayRegistryStore(it) },
            challengeStore = redisRuntime ?: InMemoryRelayRegistrationChallengeStore(),
            tokenHashSecret = tokenHashSecret,
        )
    val rateLimiter =
        redisRuntime
            ?: rateLimitStoragePath
                ?.let { SharedStoreRelayRateLimiter(store = JsonRelayRateLimitStore(it)) }
            ?: InMemoryRelayRateLimiter()
    return RelayApplicationRuntime(
        port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
        registry = registry,
        rateLimiter = rateLimiter,
        identityPolicy = identityPolicy,
        serverConfig =
            RelayServerRuntimeConfig(
                ephemeralRuntime = redisRuntime,
                readinessCheck = { (postgresStore?.ready() != false) && (redisRuntime?.ready() != false) },
                trustForwardedHeaders =
                    environmentValue("AEGIS_TRUST_FORWARDED_HEADERS")?.toBooleanStrictOrNull() == true,
            ),
        closeables = closeables,
    )
}

private data class RelayApplicationRuntime(
    val port: Int,
    val registry: InMemoryRelayRegistry,
    val rateLimiter: RelayRateLimiter,
    val identityPolicy: RelayIdentityPolicy,
    val serverConfig: RelayServerRuntimeConfig,
    val closeables: List<AutoCloseable>,
) : AutoCloseable {
    override fun close() {
        closeables.asReversed().forEach { runCatching { it.close() } }
    }
}

private fun environmentValue(name: String): String? = System.getenv(name)?.trim()?.takeIf(String::isNotEmpty)

/**
 * Public relay instances must provide a stable server-side HMAC key so stored
 * token hashes remain valid across restart. Use raw UTF-8 (at least 32 bytes)
 * or prefix a base64url value with `base64url:`.
 */
fun environmentRelayTokenHashSecret(getenv: (String) -> String? = System::getenv): ByteArray {
    val raw =
        getenv("AEGIS_RELAY_TOKEN_HMAC_SECRET")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error("AEGIS_RELAY_TOKEN_HMAC_SECRET is required to start the relay")
    val secret =
        if (raw.startsWith("base64url:")) {
            runCatching { Base64.getUrlDecoder().decode(raw.removePrefix("base64url:")) }
                .getOrElse { error("AEGIS_RELAY_TOKEN_HMAC_SECRET base64url value is invalid") }
        } else {
            raw.encodeToByteArray()
        }
    require(secret.size >= 32) { "AEGIS_RELAY_TOKEN_HMAC_SECRET must contain at least 32 bytes" }
    return secret
}
