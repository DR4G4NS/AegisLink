package dev.aegis.remote.relay

import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.params.SetParams
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface RelayRegistrationChallengeStore {
    /** Returns false only when the nonce already exists. */
    fun put(
        challenge: PendingRelayRegistrationChallenge,
        ttlMillis: Long,
    ): Boolean

    /** Atomically consumes a challenge. */
    fun take(nonce: String): PendingRelayRegistrationChallenge?
}

class InMemoryRelayRegistrationChallengeStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RelayRegistrationChallengeStore {
    private val challenges = ConcurrentHashMap<String, PendingRelayRegistrationChallenge>()

    override fun put(
        challenge: PendingRelayRegistrationChallenge,
        ttlMillis: Long,
    ): Boolean {
        challenges.entries.removeIf { it.value.expiresAtEpochMillis <= clock() }
        return challenges.putIfAbsent(challenge.nonce, challenge) == null
    }

    override fun take(nonce: String): PendingRelayRegistrationChallenge? = challenges.remove(nonce)
}

interface RelayEphemeralRuntime {
    fun ready(): Boolean

    fun setPresence(
        relayDeviceId: RelayDeviceId,
        present: Boolean,
        ttlMillis: Long = 90_000,
    )

    /** Records metadata only. Payloads, credentials and public keys are forbidden here. */
    fun recordEvent(
        scope: String,
        fields: Map<String, String>,
        ttlMillis: Long = 15 * 60_000L,
    )

    fun acquireLock(
        key: String,
        ttlMillis: Long = 10_000,
    ): AutoCloseable?
}

class RedisRelayRuntimeStore(
    redisUrl: String,
    identifierHmacSecret: ByteArray,
    private val json: Json = relayJson,
    private val namespace: String = "aegis:relay:v3",
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RelayRegistrationChallengeStore,
    RelayRateLimiter,
    RelayEphemeralRuntime,
    RelayRuntimeMessageBus,
    AutoCloseable {
    private val jedis = JedisPooled(URI(redisUrl))
    private val identifierHmacSecret = identifierHmacSecret.copyOf()

    init {
        require(this.identifierHmacSecret.size >= 32) { "Redis identifier HMAC secret must be at least 32 bytes" }
    }

    override fun put(
        challenge: PendingRelayRegistrationChallenge,
        ttlMillis: Long,
    ): Boolean =
        jedis.set(
            key("challenge", challenge.nonce),
            json.encodeToString(challenge),
            SetParams.setParams().nx().px(ttlMillis),
        ) == "OK"

    override fun take(nonce: String): PendingRelayRegistrationChallenge? = jedis.getDel(key("challenge", nonce))?.let { json.decodeFromString(it) }

    override fun tryAcquire(
        key: String,
        limit: Int,
        windowMillis: Long,
    ): RelayRateLimitDecision {
        require(limit > 0) { "Rate limit must be positive" }
        require(windowMillis > 0) { "Rate limit window must be positive" }
        val result =
            jedis.eval(
                RATE_LIMIT_SCRIPT,
                listOf(key("rate", key)),
                listOf(limit.toString(), windowMillis.toString()),
            ) as List<*>
        val allowed = (result[0] as Number).toLong() == 1L
        val retryAfterMillis = (result[1] as Number).toLong().coerceAtLeast(0L)
        return RelayRateLimitDecision(allowed, if (allowed) 0 else retryAfterMillis)
    }

    override fun ready(): Boolean = runCatching { jedis.ping() == "PONG" }.getOrDefault(false)

    override fun setPresence(
        relayDeviceId: RelayDeviceId,
        present: Boolean,
        ttlMillis: Long,
    ) {
        val redisKey = key("presence", relayDeviceId.value)
        if (present) {
            jedis.set(redisKey, "online", SetParams.setParams().px(ttlMillis))
        } else {
            jedis.del(redisKey)
        }
    }

    override fun recordEvent(
        scope: String,
        fields: Map<String, String>,
        ttlMillis: Long,
    ) {
        val eventKey = key("events", scope)
        val safeFields = fields.mapValues { (_, value) -> value.take(MAX_EVENT_FIELD_CHARS) }
        jedis.rpush(eventKey, json.encodeToString(safeFields))
        jedis.ltrim(eventKey, -MAX_EPHEMERAL_EVENTS, -1)
        jedis.pexpire(eventKey, ttlMillis)
    }

    override fun acquireLock(
        key: String,
        ttlMillis: Long,
    ): AutoCloseable? {
        val redisKey = key("lock", key)
        val token = UUID.randomUUID().toString()
        if (jedis.set(redisKey, token, SetParams.setParams().nx().px(ttlMillis)) != "OK") return null
        return AutoCloseable {
            runCatching { jedis.eval(RELEASE_LOCK_SCRIPT, listOf(redisKey), listOf(token)) }
        }
    }

    override suspend fun enqueueSessionFrame(
        sessionId: SessionId,
        recipient: RelayDeviceId,
        payload: String,
        ttlMillis: Long,
        maximumQueuedFrames: Int,
    ): Boolean =
        enqueue(
            redisKey = key("session-inbox", sessionId.value, recipient.value),
            payload = payload,
            ttlMillis = ttlMillis,
            maximum = maximumQueuedFrames,
        )

    override suspend fun takeSessionFrames(
        sessionId: SessionId,
        recipient: RelayDeviceId,
        maximumFrames: Int,
    ): List<String> =
        take(
            redisKey = key("session-inbox", sessionId.value, recipient.value),
            maximum = maximumFrames,
        )

    override suspend fun enqueueDeviceEvent(
        recipient: RelayDeviceId,
        payload: String,
        ttlMillis: Long,
        maximumQueuedEvents: Int,
    ): Boolean =
        enqueue(
            redisKey = key("device-inbox", recipient.value),
            payload = payload,
            ttlMillis = ttlMillis,
            maximum = maximumQueuedEvents,
        )

    override suspend fun takeDeviceEvents(
        recipient: RelayDeviceId,
        maximumEvents: Int,
    ): List<String> =
        take(
            redisKey = key("device-inbox", recipient.value),
            maximum = maximumEvents,
        )

    override suspend fun invalidateSession(
        sessionId: SessionId,
        reason: String,
        ttlMillis: Long,
    ) {
        putInvalidation(key("session-invalidation", sessionId.value), reason, ttlMillis)
    }

    override suspend fun sessionInvalidation(sessionId: SessionId): RelayRuntimeInvalidation? {
        val invalidationKey = key("session-invalidation", sessionId.value)
        return getInvalidation(invalidationKey)
    }

    override suspend fun invalidateDevice(
        relayDeviceId: RelayDeviceId,
        reason: String,
        ttlMillis: Long,
    ): RelayRuntimeInvalidation {
        val invalidationKey = key("device-invalidation", relayDeviceId.value)
        return putInvalidation(invalidationKey, reason, ttlMillis)
    }

    override suspend fun deviceInvalidation(relayDeviceId: RelayDeviceId): RelayRuntimeInvalidation? {
        val invalidationKey = key("device-invalidation", relayDeviceId.value)
        return getInvalidation(invalidationKey)
    }

    override suspend fun claimSessionParticipant(
        sessionId: SessionId,
        relayDeviceId: RelayDeviceId,
        leaseId: String,
        ttlMillis: Long,
    ): Boolean = claimParticipantLease(participantLeaseKey(sessionId, relayDeviceId), leaseId, ttlMillis)

    override suspend fun renewSessionParticipant(
        sessionId: SessionId,
        relayDeviceId: RelayDeviceId,
        leaseId: String,
        ttlMillis: Long,
    ): Boolean = renewParticipantLease(participantLeaseKey(sessionId, relayDeviceId), leaseId, ttlMillis)

    override suspend fun ownsSessionParticipant(
        sessionId: SessionId,
        relayDeviceId: RelayDeviceId,
        leaseId: String,
    ): Boolean = ownsParticipantLease(participantLeaseKey(sessionId, relayDeviceId), leaseId)

    override suspend fun releaseSessionParticipant(
        sessionId: SessionId,
        relayDeviceId: RelayDeviceId,
        leaseId: String,
    ) {
        releaseParticipantLease(participantLeaseKey(sessionId, relayDeviceId), leaseId)
    }

    override suspend fun claimDeviceEventSubscriber(
        relayDeviceId: RelayDeviceId,
        leaseId: String,
        ttlMillis: Long,
    ): Boolean = claimParticipantLease(deviceEventSubscriberLeaseKey(relayDeviceId), leaseId, ttlMillis)

    override suspend fun renewDeviceEventSubscriber(
        relayDeviceId: RelayDeviceId,
        leaseId: String,
        ttlMillis: Long,
    ): Boolean = renewParticipantLease(deviceEventSubscriberLeaseKey(relayDeviceId), leaseId, ttlMillis)

    override suspend fun ownsDeviceEventSubscriber(
        relayDeviceId: RelayDeviceId,
        leaseId: String,
    ): Boolean = ownsParticipantLease(deviceEventSubscriberLeaseKey(relayDeviceId), leaseId)

    override suspend fun releaseDeviceEventSubscriber(
        relayDeviceId: RelayDeviceId,
        leaseId: String,
    ) {
        releaseParticipantLease(deviceEventSubscriberLeaseKey(relayDeviceId), leaseId)
    }

    internal fun isPresent(relayDeviceId: RelayDeviceId): Boolean = jedis.exists(key("presence", relayDeviceId.value))

    internal fun eventCount(scope: String): Long = jedis.llen(key("events", scope))

    override fun close() = jedis.close()

    private suspend fun enqueue(
        redisKey: String,
        payload: String,
        ttlMillis: Long,
        maximum: Int,
    ): Boolean {
        require(payload.isNotBlank())
        require(ttlMillis > 0)
        require(maximum > 0)
        val encoded =
            json.encodeToString(
                RelayQueuedPayload(
                    payload = payload,
                    expiresAtEpochMillis = safeExpiry(clock(), ttlMillis),
                ),
            )
        return withContext(Dispatchers.IO) {
            val result =
                jedis.eval(
                    ENQUEUE_SCRIPT,
                    listOf(redisKey),
                    listOf(encoded, maximum.toString(), ttlMillis.toString()),
                ) as Number
            result.toLong() == 1L
        }
    }

    private suspend fun claimParticipantLease(
        redisKey: String,
        leaseId: String,
        ttlMillis: Long,
    ): Boolean {
        requireParticipantLease(leaseId, ttlMillis)
        return withContext(Dispatchers.IO) {
            val result =
                jedis.eval(
                    CLAIM_PARTICIPANT_LEASE_SCRIPT,
                    listOf(redisKey),
                    listOf(leaseId, ttlMillis.toString()),
                ) as Number
            result.toLong() == 1L
        }
    }

    private suspend fun renewParticipantLease(
        redisKey: String,
        leaseId: String,
        ttlMillis: Long,
    ): Boolean {
        requireParticipantLease(leaseId, ttlMillis)
        return withContext(Dispatchers.IO) {
            val result =
                jedis.eval(
                    RENEW_PARTICIPANT_LEASE_SCRIPT,
                    listOf(redisKey),
                    listOf(leaseId, ttlMillis.toString()),
                ) as Number
            result.toLong() == 1L
        }
    }

    private suspend fun ownsParticipantLease(
        redisKey: String,
        leaseId: String,
    ): Boolean {
        require(leaseId.isNotBlank()) { "Relay participant lease ID is required" }
        return withContext(Dispatchers.IO) { jedis.get(redisKey) == leaseId }
    }

    private suspend fun releaseParticipantLease(
        redisKey: String,
        leaseId: String,
    ) {
        require(leaseId.isNotBlank()) { "Relay participant lease ID is required" }
        withContext(Dispatchers.IO) {
            jedis.eval(RELEASE_LOCK_SCRIPT, listOf(redisKey), listOf(leaseId))
        }
    }

    private suspend fun take(
        redisKey: String,
        maximum: Int,
    ): List<String> {
        require(maximum > 0)
        val encoded =
            withContext(Dispatchers.IO) {
                @Suppress("UNCHECKED_CAST")
                (jedis.eval(DEQUEUE_SCRIPT, listOf(redisKey), listOf(maximum.toString())) as List<Any?>)
                    .filterIsInstance<String>()
            }
        val now = clock()
        return encoded
            .map { value -> json.decodeFromString<RelayQueuedPayload>(value) }
            .filter { value -> value.expiresAtEpochMillis > now }
            .map(RelayQueuedPayload::payload)
    }

    private suspend fun putInvalidation(
        redisKey: String,
        reason: String,
        ttlMillis: Long,
    ): RelayRuntimeInvalidation {
        require(reason.isNotBlank())
        require(ttlMillis > 0)
        val invalidation =
            RelayRuntimeInvalidation(
                id = UUID.randomUUID().toString(),
                reason = reason.take(MAX_INVALIDATION_REASON_CHARS),
            )
        withContext(Dispatchers.IO) {
            check(
                jedis.set(
                    redisKey,
                    json.encodeToString(invalidation),
                    SetParams.setParams().px(ttlMillis),
                ) == "OK",
            ) { "Redis rejected a relay invalidation marker" }
        }
        return invalidation
    }

    private suspend fun getInvalidation(redisKey: String): RelayRuntimeInvalidation? =
        withContext(Dispatchers.IO) { jedis.get(redisKey) }
            ?.let { value -> json.decodeFromString<RelayRuntimeInvalidation>(value) }

    private fun key(
        category: String,
        vararg identifiers: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(identifierHmacSecret, "HmacSHA256"))
        val digest = mac.doFinal(identifiers.joinToString(":").toByteArray(Charsets.UTF_8))
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return "$namespace:$category:$encoded"
    }

    private fun participantLeaseKey(
        sessionId: SessionId,
        relayDeviceId: RelayDeviceId,
    ): String = key("session-participant", sessionId.value, relayDeviceId.value)

    private fun deviceEventSubscriberLeaseKey(relayDeviceId: RelayDeviceId): String = key("device-event-subscriber", relayDeviceId.value)

    private fun requireParticipantLease(
        leaseId: String,
        ttlMillis: Long,
    ) {
        require(leaseId.isNotBlank()) { "Relay participant lease ID is required" }
        require(ttlMillis > 0L) { "Relay participant lease TTL must be positive" }
    }

    private companion object {
        const val MAX_EVENT_FIELD_CHARS = 128
        const val MAX_EPHEMERAL_EVENTS = 1_000L

        val RATE_LIMIT_SCRIPT =
            """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            local ttl = redis.call('PTTL', KEYS[1])
            if current <= tonumber(ARGV[1]) then return {1, ttl} end
            return {0, ttl}
            """.trimIndent()

        val RELEASE_LOCK_SCRIPT =
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """.trimIndent()

        val RENEW_PARTICIPANT_LEASE_SCRIPT =
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            return 0
            """.trimIndent()

        val CLAIM_PARTICIPANT_LEASE_SCRIPT =
            """
            local current = redis.call('GET', KEYS[1])
            if not current then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 1
            end
            if current == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            return 0
            """.trimIndent()

        val ENQUEUE_SCRIPT =
            """
            if redis.call('LLEN', KEYS[1]) >= tonumber(ARGV[2]) then
              return 0
            end
            redis.call('RPUSH', KEYS[1], ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1
            """.trimIndent()

        val DEQUEUE_SCRIPT =
            """
            local count = math.min(redis.call('LLEN', KEYS[1]), tonumber(ARGV[1]))
            local values = {}
            for index = 1, count do
              values[index] = redis.call('LPOP', KEYS[1])
            end
            return values
            """.trimIndent()
    }
}
