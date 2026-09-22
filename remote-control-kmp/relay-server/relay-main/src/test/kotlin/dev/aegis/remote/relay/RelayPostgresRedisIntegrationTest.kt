package dev.aegis.remote.relay

import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.relay.RelayDeviceChallengeRequest
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayPostgresRedisIntegrationTest {
    @Test
    @Suppress("LongMethod")
    fun productionStoresSurviveRestartAndCoordinateAcrossInstances() =
        runTest {
            val postgresUrl = System.getenv("AEGIS_TEST_POSTGRES_URL") ?: return@runTest
            val redisUrl = System.getenv("AEGIS_TEST_REDIS_URL") ?: return@runTest
            val namespace = "aegis:test:${UUID.randomUUID()}"
            val redisOne = RedisRelayRuntimeStore(redisUrl, RelayIdentityTestFixtures.tokenHashSecret, namespace = namespace)
            val redisTwo = RedisRelayRuntimeStore(redisUrl, RelayIdentityTestFixtures.tokenHashSecret, namespace = namespace)
            val (postgresOne, postgresCloseOne) =
                createPostgresRelayStore(
                    postgresUrl,
                    System.getenv("AEGIS_TEST_POSTGRES_USER"),
                    System.getenv("AEGIS_TEST_POSTGRES_PASSWORD"),
                )
            try {
                assertTrue(postgresOne.ready())
                assertTrue(redisOne.ready())
                val now = System.currentTimeMillis()
                val identity = RelayIdentityTestFixtures.identity()
                val registryOne = productionRegistry(now, postgresOne, redisOne)
                val challenge =
                    registryOne.issueRegistrationChallenge(
                        RelayDeviceChallengeRequest(identity = identity.identity),
                        integrationPolicy,
                    )

                // A second process consumes the challenge atomically from Redis.
                val registryTwo = productionRegistry(now, postgresOne, redisTwo)
                val registered =
                    registryTwo.registerV2(
                        RelayIdentityTestFixtures.registrationRequest(
                            identity = identity,
                            nonce = challenge.nonce,
                            timestampEpochMillis = now,
                            relayOrigin = integrationPolicy.relayOrigin,
                        ),
                        integrationPolicy,
                    )
                assertNull(redisOne.take(challenge.nonce))
                assertTrue(registryTwo.authenticate(registered.relayDeviceId, registered.authToken))

                redisOne.setPresence(registered.relayDeviceId, true)
                assertTrue(redisTwo.isPresent(registered.relayDeviceId))
                redisOne.recordEvent("integration", mapOf("kind" to "metadata_only"))
                assertTrue(redisTwo.eventCount("integration") > 0)

                val crossReplicaSessionId = SessionId("session-${UUID.randomUUID()}")
                val crossReplicaRecipient = RelayDeviceId("relay-${UUID.randomUUID()}")
                assertTrue(
                    redisOne.enqueueSessionFrame(
                        sessionId = crossReplicaSessionId,
                        recipient = crossReplicaRecipient,
                        payload = "opaque-e2ee-frame",
                        ttlMillis = 30_000,
                        maximumQueuedFrames = 8,
                    ),
                )
                assertEquals(
                    listOf("opaque-e2ee-frame"),
                    redisTwo.takeSessionFrames(crossReplicaSessionId, crossReplicaRecipient, maximumFrames = 8),
                )
                val invalidation = redisOne.invalidateDevice(crossReplicaRecipient, "REL-7405 token rotated", 30_000)
                assertEquals(invalidation, redisTwo.deviceInvalidation(crossReplicaRecipient))

                val participantLeaseId = "lease-${UUID.randomUUID()}"
                assertTrue(
                    redisOne.claimSessionParticipant(
                        crossReplicaSessionId,
                        crossReplicaRecipient,
                        participantLeaseId,
                        30_000L,
                    ),
                )
                assertFalse(
                    redisTwo.claimSessionParticipant(
                        crossReplicaSessionId,
                        crossReplicaRecipient,
                        "lease-${UUID.randomUUID()}",
                        30_000L,
                    ),
                )
                assertTrue(redisTwo.ownsSessionParticipant(crossReplicaSessionId, crossReplicaRecipient, participantLeaseId))
                assertTrue(
                    redisTwo.renewSessionParticipant(
                        crossReplicaSessionId,
                        crossReplicaRecipient,
                        participantLeaseId,
                        30_000L,
                    ),
                )
                redisTwo.releaseSessionParticipant(crossReplicaSessionId, crossReplicaRecipient, participantLeaseId)
                assertFalse(redisOne.ownsSessionParticipant(crossReplicaSessionId, crossReplicaRecipient, participantLeaseId))

                val subscriberLeaseId = "subscriber-${UUID.randomUUID()}"
                assertTrue(redisOne.claimDeviceEventSubscriber(crossReplicaRecipient, subscriberLeaseId, 30_000L))
                assertFalse(
                    redisTwo.claimDeviceEventSubscriber(
                        crossReplicaRecipient,
                        "subscriber-${UUID.randomUUID()}",
                        30_000L,
                    ),
                )
                assertTrue(redisTwo.ownsDeviceEventSubscriber(crossReplicaRecipient, subscriberLeaseId))
                redisTwo.releaseDeviceEventSubscriber(crossReplicaRecipient, subscriberLeaseId)
                assertFalse(redisOne.ownsDeviceEventSubscriber(crossReplicaRecipient, subscriberLeaseId))

                val lease = assertNotNull(redisOne.acquireLock("same-operation"))
                assertNull(redisTwo.acquireLock("same-operation"))
                lease.close()
                assertNotNull(redisTwo.acquireLock("same-operation"))?.close()

                val rateKey = "integration-${UUID.randomUUID()}"
                assertTrue(redisOne.tryAcquire(rateKey, 1, 60_000).allowed)
                assertFalse(redisTwo.tryAcquire(rateKey, 1, 60_000).allowed)

                postgresCloseOne.close()
                val (postgresTwo, postgresCloseTwo) =
                    createPostgresRelayStore(
                        postgresUrl,
                        System.getenv("AEGIS_TEST_POSTGRES_USER"),
                        System.getenv("AEGIS_TEST_POSTGRES_PASSWORD"),
                    )
                try {
                    val restarted = productionRegistry(now, postgresTwo, redisTwo)
                    assertTrue(restarted.authenticate(registered.relayDeviceId, registered.authToken))
                } finally {
                    postgresCloseTwo.close()
                }
            } finally {
                runCatching { postgresCloseOne.close() }
                redisOne.close()
                redisTwo.close()
            }
        }

    private fun productionRegistry(
        now: Long,
        store: RelayRegistryStore,
        challenges: RelayRegistrationChallengeStore,
    ) = InMemoryRelayRegistry(
        clock = { now },
        store = store,
        challengeStore = challenges,
        tokenHashSecret = RelayIdentityTestFixtures.tokenHashSecret,
    )

    private companion object {
        val integrationPolicy =
            RelayIdentityPolicy(
                relayOrigin = "https://integration.relay.test",
                challengeTtlMillis = 60_000,
                maxClockSkewMillis = 5_000,
            )
    }
}
