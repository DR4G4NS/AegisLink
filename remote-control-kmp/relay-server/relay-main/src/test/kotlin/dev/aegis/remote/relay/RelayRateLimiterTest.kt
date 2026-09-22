package dev.aegis.remote.relay

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayRateLimiterTest {
    @Test
    fun limitsRequestsWithinWindowAndResetsAfterWindow() {
        var now = 1_000L
        val limiter = InMemoryRelayRateLimiter(clock = { now })

        assertTrue(limiter.tryAcquire("register:client-1", limit = 2, windowMillis = 1_000).allowed)
        assertTrue(limiter.tryAcquire("register:client-1", limit = 2, windowMillis = 1_000).allowed)
        assertFalse(limiter.tryAcquire("register:client-1", limit = 2, windowMillis = 1_000).allowed)

        now = 2_000L

        assertTrue(limiter.tryAcquire("register:client-1", limit = 2, windowMillis = 1_000).allowed)
    }

    @Test
    fun isolatesDifferentKeys() {
        val limiter = InMemoryRelayRateLimiter(clock = { 1_000L })

        assertTrue(limiter.tryAcquire("sessions:client-1", limit = 1, windowMillis = 1_000).allowed)
        assertFalse(limiter.tryAcquire("sessions:client-1", limit = 1, windowMillis = 1_000).allowed)
        assertTrue(limiter.tryAcquire("sessions:client-2", limit = 1, windowMillis = 1_000).allowed)
    }

    @Test
    fun persistsBucketsAcrossRestartWhenStoreIsConfigured() {
        val storagePath = createTempDirectory("aegis-relay-rate-limit-test").resolve("rate-limit.json")
        val firstLimiter =
            InMemoryRelayRateLimiter(
                clock = { 1_000L },
                store = JsonRelayRateLimitStore(storagePath),
            )

        assertTrue(firstLimiter.tryAcquire("turn:client-1", limit = 2, windowMillis = 1_000).allowed)
        assertTrue(firstLimiter.tryAcquire("turn:client-1", limit = 2, windowMillis = 1_000).allowed)

        val secondLimiter =
            InMemoryRelayRateLimiter(
                clock = { 1_100L },
                store = JsonRelayRateLimitStore(storagePath),
            )

        assertFalse(secondLimiter.tryAcquire("turn:client-1", limit = 2, windowMillis = 1_000).allowed)
    }

    @Test
    fun sharedStoreLimiterAppliesLimitsAcrossInstances() {
        val storagePath = createTempDirectory("aegis-relay-rate-limit-shared-test").resolve("rate-limit.json")
        val firstLimiter =
            SharedStoreRelayRateLimiter(
                clock = { 1_000L },
                store = JsonRelayRateLimitStore(storagePath),
            )
        val secondLimiter =
            SharedStoreRelayRateLimiter(
                clock = { 1_100L },
                store = JsonRelayRateLimitStore(storagePath),
            )

        assertTrue(firstLimiter.tryAcquire("register:client-1", limit = 1, windowMillis = 1_000).allowed)
        val decision = secondLimiter.tryAcquire("register:client-1", limit = 1, windowMillis = 1_000)

        assertFalse(decision.allowed)
        assertEquals(900L, decision.retryAfterMillis)
    }

    @Test
    fun prunesExpiredBucketsBeforePersistingSnapshot() {
        var now = 1_000L
        val store = RecordingRateLimitStore()
        val limiter = InMemoryRelayRateLimiter(clock = { now }, store = store)

        assertTrue(limiter.tryAcquire("turn:old-client", limit = 2, windowMillis = 1_000).allowed)
        now = 2_500L
        assertTrue(limiter.tryAcquire("turn:new-client", limit = 2, windowMillis = 1_000).allowed)

        assertEquals(listOf("turn:new-client"), store.lastSaved?.buckets?.map { it.key })
    }

    @Test
    fun ignoresMalformedPersistedSnapshot() {
        val storagePath = createTempDirectory("aegis-relay-rate-limit-malformed-test").resolve("rate-limit.json")
        storagePath.writeText("{not json")

        val limiter =
            InMemoryRelayRateLimiter(
                clock = { 1_000L },
                store = JsonRelayRateLimitStore(storagePath),
            )

        assertTrue(limiter.tryAcquire("register:client-1", limit = 1, windowMillis = 1_000).allowed)
    }

    @Test
    fun sharedStoreLimiterIgnoresMalformedSnapshot() {
        val storagePath = createTempDirectory("aegis-relay-rate-limit-shared-malformed-test").resolve("rate-limit.json")
        storagePath.writeText("{not json")
        val limiter =
            SharedStoreRelayRateLimiter(
                clock = { 1_000L },
                store = JsonRelayRateLimitStore(storagePath),
            )

        assertTrue(limiter.tryAcquire("register:client-1", limit = 1, windowMillis = 1_000).allowed)
    }
}

private class RecordingRateLimitStore(
    private val initial: RelayRateLimitSnapshot? = null,
) : RelayRateLimitStore {
    var lastSaved: RelayRateLimitSnapshot? = null

    override fun load(): RelayRateLimitSnapshot? = initial

    override fun save(snapshot: RelayRateLimitSnapshot) {
        lastSaved = snapshot
    }
}
