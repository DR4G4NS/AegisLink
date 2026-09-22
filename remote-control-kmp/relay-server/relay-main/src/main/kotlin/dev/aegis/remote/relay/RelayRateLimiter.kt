package dev.aegis.remote.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

data class RelayRateLimitConfig(
    val requestsPerWindow: Int = 60,
    val websocketAttemptsPerWindow: Int = 30,
    val windowMillis: Long = 60_000,
)

data class RelayRateLimitDecision(
    val allowed: Boolean,
    val retryAfterMillis: Long = 0,
)

interface RelayRateLimiter {
    fun tryAcquire(
        key: String,
        limit: Int,
        windowMillis: Long,
    ): RelayRateLimitDecision
}

class SharedStoreRelayRateLimiter(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val store: AtomicRelayRateLimitStore,
) : RelayRateLimiter {
    override fun tryAcquire(
        key: String,
        limit: Int,
        windowMillis: Long,
    ): RelayRateLimitDecision = store.tryAcquire(key = key, limit = limit, windowMillis = windowMillis, now = clock())
}

class InMemoryRelayRateLimiter(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val store: RelayRateLimitStore? = null,
) : RelayRateLimiter {
    private val buckets = mutableMapOf<String, Bucket>()

    init {
        store?.load()?.buckets?.forEach { bucket ->
            if (bucket.key.isNotBlank() && bucket.count > 0) {
                buckets[bucket.key] =
                    Bucket(
                        key = bucket.key,
                        windowStartMillis = bucket.windowStartMillis,
                        count = bucket.count,
                    )
            }
        }
    }

    override fun tryAcquire(
        key: String,
        limit: Int,
        windowMillis: Long,
    ): RelayRateLimitDecision =
        synchronized(buckets) {
            val now = clock()
            val bucket = buckets.getOrPut(key) { Bucket(key = key, windowStartMillis = now, count = 0) }
            if (now - bucket.windowStartMillis >= windowMillis) {
                bucket.windowStartMillis = now
                bucket.count = 0
            }

            if (bucket.count >= limit) {
                persist(windowMillis, now)
                return@synchronized RelayRateLimitDecision(
                    allowed = false,
                    retryAfterMillis = (bucket.windowStartMillis + windowMillis - now).coerceAtLeast(0),
                )
            }

            bucket.count += 1
            persist(windowMillis, now)
            RelayRateLimitDecision(allowed = true)
        }

    private fun persist(
        windowMillis: Long,
        now: Long,
    ) {
        buckets.entries.removeIf { (_, bucket) -> now - bucket.windowStartMillis >= windowMillis }
        store?.save(
            RelayRateLimitSnapshot(
                buckets =
                    buckets.values
                        .sortedBy { it.key }
                        .map { RelayRateLimitBucket(it.key, it.windowStartMillis, it.count) },
            ),
        )
    }

    private data class Bucket(
        val key: String,
        var windowStartMillis: Long,
        var count: Int,
    )
}

interface RelayRateLimitStore {
    fun load(): RelayRateLimitSnapshot?

    fun save(snapshot: RelayRateLimitSnapshot)
}

interface AtomicRelayRateLimitStore {
    fun tryAcquire(
        key: String,
        limit: Int,
        windowMillis: Long,
        now: Long,
    ): RelayRateLimitDecision
}

class JsonRelayRateLimitStore(
    private val path: Path,
    private val json: Json = relayJson,
) : RelayRateLimitStore,
    AtomicRelayRateLimitStore {
    override fun load(): RelayRateLimitSnapshot? {
        if (!Files.exists(path)) return null
        return runCatching { json.decodeFromString<RelayRateLimitSnapshot>(Files.readString(path)) }
            .getOrNull()
    }

    override fun save(snapshot: RelayRateLimitSnapshot) {
        val absolutePath = path.toAbsolutePath()
        val parent = absolutePath.parent
        parent?.let { Files.createDirectories(it) }
        val temp =
            if (parent != null) {
                Files.createTempFile(parent, "${absolutePath.fileName}.", ".tmp")
            } else {
                Files.createTempFile("${absolutePath.fileName}.", ".tmp")
            }
        Files.writeString(temp, json.encodeToString(snapshot))
        try {
            Files.move(temp, absolutePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (atomicMoveUnsupported: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, absolutePath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun tryAcquire(
        key: String,
        limit: Int,
        windowMillis: Long,
        now: Long,
    ): RelayRateLimitDecision {
        val absolutePath = path.toAbsolutePath()
        absolutePath.parent?.let { Files.createDirectories(it) }
        synchronized(fileLock) {
            FileChannel
                .open(
                    absolutePath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                ).use { channel ->
                    channel.lock().use {
                        val buckets =
                            readSnapshot(channel)
                                .validBuckets(windowMillis = windowMillis, now = now)
                                .associateBy { bucket -> bucket.key }
                                .mapValues { (_, bucket) ->
                                    MutableBucket(
                                        key = bucket.key,
                                        windowStartMillis = bucket.windowStartMillis,
                                        count = bucket.count,
                                    )
                                }.toMutableMap()
                        val bucket =
                            buckets.getOrPut(key) {
                                MutableBucket(key = key, windowStartMillis = now, count = 0)
                            }

                        if (now - bucket.windowStartMillis >= windowMillis) {
                            bucket.windowStartMillis = now
                            bucket.count = 0
                        }

                        if (bucket.count >= limit) {
                            writeSnapshot(channel, buckets.values.toSnapshot())
                            return RelayRateLimitDecision(
                                allowed = false,
                                retryAfterMillis = (bucket.windowStartMillis + windowMillis - now).coerceAtLeast(0),
                            )
                        }

                        bucket.count += 1
                        writeSnapshot(channel, buckets.values.toSnapshot())
                        return RelayRateLimitDecision(allowed = true)
                    }
                }
        }
    }

    private fun readSnapshot(channel: FileChannel): RelayRateLimitSnapshot {
        if (channel.size() <= 0) return RelayRateLimitSnapshot()
        channel.position(0)
        val buffer = ByteBuffer.allocate(channel.size().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        while (buffer.hasRemaining() && channel.read(buffer) != -1) {
            // Read the locked snapshot fully before decoding.
        }
        buffer.flip()
        val text = StandardCharsets.UTF_8.decode(buffer).toString()
        return runCatching { json.decodeFromString<RelayRateLimitSnapshot>(text) }
            .getOrDefault(RelayRateLimitSnapshot())
    }

    private fun writeSnapshot(
        channel: FileChannel,
        snapshot: RelayRateLimitSnapshot,
    ) {
        val bytes = json.encodeToString(snapshot).toByteArray(StandardCharsets.UTF_8)
        channel.truncate(0)
        channel.position(0)
        channel.write(ByteBuffer.wrap(bytes))
        channel.force(true)
    }

    private fun RelayRateLimitSnapshot.validBuckets(
        windowMillis: Long,
        now: Long,
    ): List<RelayRateLimitBucket> =
        buckets.filter { bucket ->
            bucket.key.isNotBlank() &&
                bucket.count > 0 &&
                now - bucket.windowStartMillis < windowMillis
        }

    private fun Collection<MutableBucket>.toSnapshot(): RelayRateLimitSnapshot =
        RelayRateLimitSnapshot(
            buckets =
                asSequence()
                    .filter { bucket -> bucket.key.isNotBlank() && bucket.count > 0 }
                    .sortedBy { bucket -> bucket.key }
                    .map { bucket -> RelayRateLimitBucket(bucket.key, bucket.windowStartMillis, bucket.count) }
                    .toList(),
        )

    private data class MutableBucket(
        val key: String,
        var windowStartMillis: Long,
        var count: Int,
    )

    private companion object {
        private val fileLock = Any()
    }
}

@Serializable
data class RelayRateLimitSnapshot(
    val buckets: List<RelayRateLimitBucket> = emptyList(),
)

@Serializable
data class RelayRateLimitBucket(
    val key: String,
    val windowStartMillis: Long,
    val count: Int,
)
