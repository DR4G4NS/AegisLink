package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.AEGIS_P256_AESGCM_V1
import dev.aegis.remote.core.model.DeviceAuthorization
import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.MonitorInfo
import dev.aegis.remote.core.model.RelayConfig
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.RemoteDeviceId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.monitor.MonitorProvider
import dev.aegis.remote.core.pairing.LocalPairedProfile
import dev.aegis.remote.core.pairing.LocalPairingQrPayload
import dev.aegis.remote.core.pairing.localProtocolSessionProofPayload
import dev.aegis.remote.core.relay.RelayAuthToken
import dev.aegis.remote.core.relay.RelayDeviceEvent
import dev.aegis.remote.core.relay.RelayDeviceIdentity
import dev.aegis.remote.core.relay.RelayRegistration
import dev.aegis.remote.core.relay.RevokeRelayDeviceV2Response
import dev.aegis.remote.core.security.KeyRotationReason
import dev.aegis.remote.core.security.LocalDeviceIdentity
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageChannel
import dev.aegis.remote.relayclient.RelayIdentityRotationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopAgentTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun approvingPairingMovesRequestToAuthorizedDevices() =
        runTest {
            val server = FakeLocalPairingServer()
            val phoneIdentity = relayIdentity(21)
            val agent =
                DesktopAgent(
                    server,
                    trustStore = InMemoryDeviceTrustStore(),
                    openSshProvisioner = FakeAegisOpenSshManager(),
                    clock = { 100L },
                    scope = serviceScope(),
                )
            agent.start()
            advanceUntilIdle()

            val request =
                DesktopPairingRequest(
                    requestId = "req-1",
                    deviceName = "Android",
                    fingerprint = "SHA256:phone",
                    requestedAtEpochMillis = 100L,
                    remote = false,
                    localProtocolToken = "test-local-token",
                    localHost = "192.168.1.92",
                    publicIdentity = phoneIdentity,
                    sshPublicKey = TEST_SSH_PUBLIC_KEY,
                )
            server.emit(request)
            agent.approvePairing("req-1")

            advanceUntilIdle()

            assertTrue(
                agent.state.value.pendingPairingRequests
                    .isEmpty(),
            )
            val authorization =
                agent.state.value.authorizedDevices
                    .single()
            val profile = assertNotNull(server.approvedProfile)
            assertEquals("Android", authorization.displayName)
            assertEquals("test-local-token", authorization.localProtocolToken)
            assertEquals(authorization.permissions, profile.permissions)
            assertEquals("192.168.1.92", profile.localHost)
            assertTrue(profile.permissions.clipboard)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun startPublishesQrReadyLocalPairingPayload() =
        runTest {
            val agent = DesktopAgent(FakeLocalPairingServer(), trustStore = InMemoryDeviceTrustStore(), scope = serviceScope())

            agent.start()
            advanceUntilIdle()

            val encodedPayload = assertNotNull(agent.state.value.pairingQrPayload)
            assertTrue(encodedPayload.startsWith("AEGIS3:"))
            val payload =
                Json.decodeFromString(
                    LocalPairingQrPayload.serializer(),
                    inflatePairingQrPayload(encodedPayload),
                )
            assertEquals(3, payload.version)
            assertEquals("https://127.0.0.1:48291", payload.pairingUrl)
            assertEquals(listOf("https://127.0.0.1:48291"), payload.pairingUrls)
            assertEquals("123456", payload.pairingCode)
            assertEquals("SHA256:test", payload.agentFingerprint)
            assertEquals("token-initial", payload.tokenId)
            assertEquals(Long.MAX_VALUE, payload.expiresAtEpochMillis)
            assertNotNull(payload.hostIdentity)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun startupWithUnreadableTrustStoreBlocksPairingAndPublishesRecoveryDiagnostic() =
        runTest {
            val path = Files.createTempDirectory("aegis-agent-corrupt-trust-test").resolve("authorized-devices.json")
            FileDeviceTrustStore(path, payloadProtector = PlainTrustStorePayloadProtector).saveAuthorization(testTrustAuthorization("seed"))
            Files.writeString(path, "{corrupt")
            val server = FakeLocalPairingServer()
            val agent =
                DesktopAgent(
                    pairingServer = server,
                    trustStore = FileDeviceTrustStore(path, payloadProtector = PlainTrustStorePayloadProtector),
                    relayConnector = null,
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()

            assertFalse(agent.state.value.pairingServerRunning)
            assertTrue(agent.state.value.trustStoreRecoveryRequired)
            assertEquals(TrustStoreFailureReason.Json, agent.state.value.trustStoreFailureReason)
            assertEquals(0, server.startCalls)
            assertTrue(
                agent.state.value.logs.any {
                    it.eventCode == AgentLogEventCode.TrustStoreUnavailable &&
                        it.message.contains("Restore a verified backup")
                },
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun auxiliaryCapabilityFailureDoesNotDisablePairingServer() =
        runTest {
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = InMemoryDeviceTrustStore(),
                    monitorProvider =
                        object : MonitorProvider {
                            override suspend fun listMonitors(): List<MonitorInfo> = error("monitor backend failed")
                        },
                    capabilityDetector =
                        object : DesktopCapabilityDetector {
                            override fun detect(): DesktopCapabilityReport = error("capability backend failed")
                        },
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()

            assertTrue(agent.state.value.pairingServerRunning)
            assertEquals("https://127.0.0.1:48291", agent.state.value.pairingUrl)
            assertTrue(
                agent.state.value.logs
                    .any { it.message.contains("Monitor discovery unavailable") },
            )
            assertTrue(
                agent.state.value.logs
                    .any { it.message.contains("Desktop capability detection unavailable") },
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun revokedDeviceCannotOpenLocalProtocolChannel() =
        runTest {
            val authenticator = LocalProtocolAuthenticator()
            val token = authenticator.issueToken()
            val store = InMemoryDeviceTrustStore()
            store.saveAuthorization(
                DeviceAuthorization(
                    remoteDeviceId = RemoteDeviceId("phone-1"),
                    displayName = "Android",
                    approvedAtEpochMillis = 100L,
                    permissions = DevicePermissions(visual = true, input = true),
                    localProtocolToken = token,
                ),
            )
            val server = FakeLocalPairingServer()
            val agent = DesktopAgent(server, trustStore = store, relayConnector = null, scope = serviceScope())
            agent.start()
            advanceUntilIdle()
            agent.revoke("phone-1")
            advanceUntilIdle()
            val channel = RecordingProtocolChannel()

            val timestamp = System.currentTimeMillis()
            val proof = authenticator.deriveToken(token, localProtocolSessionProofPayload("session-1", "phone-1", timestamp))
            server.emitProtocol(
                LocalProtocolChannelRequest(
                    sessionId = SessionId("session-1"),
                    authorizedDeviceId = "phone-1",
                    sessionProof = proof,
                    proofTimestampEpochMillis = timestamp,
                    channel = channel,
                ),
            )
            advanceUntilIdle()

            val error = channel.sent.single() as ProtocolMessage.Control
            assertEquals("unauthorized", (error.command as dev.aegis.remote.protocol.ControlCommand.Error).code)
            assertTrue(channel.closed)
            val diagnostic =
                agent.state.value.logs
                    .last { it.eventCode == AgentLogEventCode.LocalProtocolAuthorizationFailed }
            assertEquals("DEVICE_REVOKED", diagnostic.context["reason"])
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sshRemovalFalseKeepsRevocationPendingAndRetriesIdempotently() =
        runTest {
            val store = InMemoryDeviceTrustStore()
            val ssh =
                FakeAegisOpenSshManager().apply {
                    removalResults += sshRemovalResult(relayIdentity(71).deviceId.value, removed = false)
                }
            store.saveAuthorization(localSshAuthorization("phone-ssh", 71))
            val agent = DesktopAgent(FakeLocalPairingServer(), trustStore = store, relayConnector = null, openSshProvisioner = ssh, scope = serviceScope())
            agent.start()
            advanceUntilIdle()

            agent.revoke("phone-ssh")
            advanceUntilIdle()

            val pending =
                agent.state.value.authorizedDevices
                    .single()
            assertNotNull(pending.revokedAtEpochMillis)
            assertTrue(pending.sshKeyRemovalPending)
            assertEquals("SSH-7326", pending.sshKeyRemovalFailureCode)
            assertEquals(listOf(pending.publicIdentity!!.deviceId.value), ssh.removedDeviceIds)
            assertTrue(
                agent.state.value.logs.any {
                    it.eventCode == AgentLogEventCode.SshKeyRemovalPending &&
                        it.context["removalResult"] == "removed=false" &&
                        it.context["failureCode"] == "SSH-7326"
                },
            )

            agent.deleteRevokedDevice("phone-ssh")
            advanceUntilIdle()
            assertEquals(
                DeviceRecordActionOutcome.SshKeyRemovalPending,
                agent.state.value.lastDeviceRecordAction
                    ?.outcome,
            )
            assertTrue(store.listAuthorizedDevices().single().sshKeyRemovalPending)

            ssh.removalResults += sshRemovalResult(relayIdentity(71).deviceId.value, removed = true)
            agent.retrySshKeyRemoval("phone-ssh")
            advanceUntilIdle()

            val completed =
                agent.state.value.authorizedDevices
                    .single()
            assertFalse(completed.sshKeyRemovalPending)
            assertEquals(null, completed.sshKeyRemovalFailureCode)
            assertEquals(2, ssh.removedDeviceIds.size)
            assertTrue(
                agent.state.value.logs
                    .any { it.eventCode == AgentLogEventCode.SshKeyRemovalCompleted },
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sshRemovalExceptionKeepsLocalRevocationAndPersistsRetryableFailure() =
        runTest {
            val store = InMemoryDeviceTrustStore()
            val ssh = FakeAegisOpenSshManager().apply { removalError = IllegalStateException("script unavailable") }
            store.saveAuthorization(localSshAuthorization("phone-ssh-error", 72))
            val agent = DesktopAgent(FakeLocalPairingServer(), trustStore = store, relayConnector = null, openSshProvisioner = ssh, scope = serviceScope())
            agent.start()
            advanceUntilIdle()

            agent.revoke("phone-ssh-error")
            advanceUntilIdle()

            val pending =
                agent.state.value.authorizedDevices
                    .single()
            assertNotNull(pending.revokedAtEpochMillis)
            assertTrue(pending.sshKeyRemovalPending)
            assertEquals("SSH-7326", pending.sshKeyRemovalFailureCode)
            val diagnostic =
                agent.state.value.logs
                    .last { it.eventCode == AgentLogEventCode.SshKeyRemovalPending }
            assertEquals("exception", diagnostic.context["removalResult"])
            assertEquals("SSH-7326", diagnostic.context["failureCode"])
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sshRemovalDeviceIdMismatchKeepsRevocationPending() =
        runTest {
            val store = InMemoryDeviceTrustStore()
            val ssh =
                FakeAegisOpenSshManager().apply {
                    removalResults += sshRemovalResult("another-device", removed = true)
                }
            store.saveAuthorization(localSshAuthorization("phone-ssh-mismatch", 73))
            val agent = DesktopAgent(FakeLocalPairingServer(), trustStore = store, relayConnector = null, openSshProvisioner = ssh, scope = serviceScope())
            agent.start()
            advanceUntilIdle()

            agent.revoke("phone-ssh-mismatch")
            advanceUntilIdle()

            val pending =
                agent.state.value.authorizedDevices
                    .single()
            assertTrue(pending.sshKeyRemovalPending)
            assertEquals("SSH-7326", pending.sshKeyRemovalFailureCode)
            assertTrue(
                agent.state.value.logs.any {
                    it.eventCode == AgentLogEventCode.SshKeyRemovalPending &&
                        it.context["removalResult"] == "device-id-mismatch" &&
                        it.context["failureCode"] == "SSH-7326"
                },
            )
            assertFalse(
                agent.state.value.logs
                    .any { it.eventCode == AgentLogEventCode.SshKeyRemovalCompleted },
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun confirmedSshRemovalPublishesCompleteSshRevocation() =
        runTest {
            val store = InMemoryDeviceTrustStore()
            val ssh = FakeAegisOpenSshManager()
            store.saveAuthorization(localSshAuthorization("phone-ssh-complete", 73))
            val agent = DesktopAgent(FakeLocalPairingServer(), trustStore = store, relayConnector = null, openSshProvisioner = ssh, scope = serviceScope())
            agent.start()
            advanceUntilIdle()

            agent.revoke("phone-ssh-complete")
            advanceUntilIdle()

            val authorization =
                agent.state.value.authorizedDevices
                    .single()
            assertNotNull(authorization.revokedAtEpochMillis)
            assertFalse(authorization.sshKeyRemovalPending)
            assertEquals(null, authorization.sshKeyRemovalFailureCode)
            assertTrue(
                agent.state.value.logs
                    .any { it.eventCode == AgentLogEventCode.SshKeyRemovalCompleted },
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun revokedDeviceRecordCanBeDeletedButActiveRecordCannot() =
        runTest {
            val store = InMemoryDeviceTrustStore()
            store.saveAuthorization(
                DeviceAuthorization(
                    remoteDeviceId = RemoteDeviceId("phone-delete"),
                    displayName = "Android",
                    approvedAtEpochMillis = 100L,
                    permissions = DevicePermissions(visual = true),
                ),
            )
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = store,
                    relayConnector = null,
                    clock = { 500L },
                    scope = serviceScope(),
                )
            agent.start()
            advanceUntilIdle()

            agent.deleteRevokedDevice("phone-delete")
            advanceUntilIdle()

            assertEquals(
                DeviceRecordActionOutcome.MustRevokeFirst,
                agent.state.value.lastDeviceRecordAction
                    ?.outcome,
            )
            assertEquals(1, agent.state.value.authorizedDevices.size)

            agent.revoke("phone-delete")
            advanceUntilIdle()
            agent.deleteRevokedDevice("phone-delete")
            advanceUntilIdle()

            assertEquals(
                DeviceRecordActionOutcome.Deleted,
                agent.state.value.lastDeviceRecordAction
                    ?.outcome,
            )
            assertTrue(
                agent.state.value.authorizedDevices
                    .isEmpty(),
            )
            assertTrue(
                agent.state.value.deviceRecordActionsInProgress
                    .isEmpty(),
            )
            assertEquals(
                AgentLogEventCode.DeviceRecordDeleted,
                agent.state.value.logs
                    .last()
                    .eventCode,
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedPairingApprovalRollsBackGhostTrustRecord() =
        runTest {
            val store = InMemoryDeviceTrustStore()
            val server = FakeLocalPairingServer(approvalError = IllegalStateException("request already closed"))
            val openSsh = FakeAegisOpenSshManager()
            val phoneIdentity = relayIdentity(22)
            val agent =
                DesktopAgent(
                    server,
                    trustStore = store,
                    relayConnector = null,
                    openSshProvisioner = openSsh,
                    clock = { 100L },
                    scope = serviceScope(),
                )
            agent.start()
            advanceUntilIdle()
            server.emit(
                DesktopPairingRequest(
                    requestId = "req-failed",
                    deviceName = "Android",
                    fingerprint = "SHA256:phone",
                    requestedAtEpochMillis = 100L,
                    remote = false,
                    localProtocolToken = "token",
                    publicIdentity = phoneIdentity,
                    sshPublicKey = TEST_SSH_PUBLIC_KEY,
                ),
            )

            agent.approvePairing("req-failed")
            advanceUntilIdle()

            assertTrue(store.listAuthorizedDevices().isEmpty())
            assertEquals(
                AgentLogEventCode.PairingApprovalFailed,
                agent.state.value.logs
                    .last()
                    .eventCode,
            )
            assertEquals(
                "IllegalStateException",
                agent.state.value.logs
                    .last()
                    .errorType,
            )
            assertEquals(listOf(phoneIdentity.deviceId.value), openSsh.removedDeviceIds)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedPairingApprovalRetainsRevokedTrustRecordWhenSshRemovalIsIncomplete() =
        runTest {
            val store = InMemoryDeviceTrustStore()
            val server = FakeLocalPairingServer(approvalError = IllegalStateException("request already closed"))
            val openSsh =
                FakeAegisOpenSshManager().apply {
                    removalResults += sshRemovalResult(relayIdentity(23).deviceId.value, removed = false)
                }
            val phoneIdentity = relayIdentity(23)
            val agent =
                DesktopAgent(
                    server,
                    trustStore = store,
                    relayConnector = null,
                    openSshProvisioner = openSsh,
                    clock = { 100L },
                    scope = serviceScope(),
                )
            agent.start()
            advanceUntilIdle()
            server.emit(
                DesktopPairingRequest(
                    requestId = "req-failed-removal",
                    deviceName = "Android",
                    fingerprint = "SHA256:phone",
                    requestedAtEpochMillis = 100L,
                    remote = false,
                    localProtocolToken = "token",
                    publicIdentity = phoneIdentity,
                    sshPublicKey = TEST_SSH_PUBLIC_KEY,
                ),
            )

            agent.approvePairing("req-failed-removal")
            advanceUntilIdle()

            val pending = store.listAuthorizedDevices().single()
            assertNotNull(pending.revokedAtEpochMillis)
            assertTrue(pending.sshKeyRemovalPending)
            assertEquals("SSH-7326", pending.sshKeyRemovalFailureCode)
            assertEquals(listOf(phoneIdentity.deviceId.value), openSsh.removedDeviceIds)
            assertTrue(
                agent.state.value.logs.any {
                    it.eventCode == AgentLogEventCode.SshKeyRemovalPending &&
                        it.context["removalResult"] == "removed=false"
                },
            )

            openSsh.removalResults += sshRemovalResult(phoneIdentity.deviceId.value, removed = true)
            agent.retrySshKeyRemoval(pending.remoteDeviceId.value)
            advanceUntilIdle()

            val completed = store.listAuthorizedDevices().single()
            assertFalse(completed.sshKeyRemovalPending)
            assertEquals(null, completed.sshKeyRemovalFailureCode)
            assertEquals(2, openSsh.removedDeviceIds.size)
        }

    @Test
    fun fileTrustStorePersistsAuthorizationsAndRevocation() =
        runTest {
            val path = Files.createTempDirectory("aegis-trust-test").resolve("authorized-devices.json")
            val store = FileDeviceTrustStore(path, clock = { 200L }, payloadProtector = PlainTrustStorePayloadProtector)
            assertEquals(TrustStoreReadResult.Missing, store.readResult())
            store.saveAuthorization(
                DeviceAuthorization(
                    remoteDeviceId = RemoteDeviceId("phone-1"),
                    displayName = "Android",
                    approvedAtEpochMillis = 100L,
                    permissions = DevicePermissions(terminal = true, sftp = true),
                ),
            )

            val reloaded = FileDeviceTrustStore(path, clock = { 200L }, payloadProtector = PlainTrustStorePayloadProtector)
            assertEquals("Android", reloaded.listAuthorizedDevices().single().displayName)

            reloaded.revoke("phone-1")

            val revoked =
                FileDeviceTrustStore(path, clock = {
                    300L
                }, payloadProtector = PlainTrustStorePayloadProtector).listAuthorizedDevices().single()
            assertEquals(200L, revoked.revokedAtEpochMillis)

            if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(path),
                )
            }
            Files.getFileAttributeView(path, AclFileAttributeView::class.java)?.let { aclView ->
                val owner = Files.getOwner(path)
                assertEquals(1, aclView.acl.size)
                assertEquals(owner, aclView.acl.single().principal())
            }
        }

    @Test
    fun fileTrustStorePersistsPendingSshRemovalState() =
        runTest {
            val path = Files.createTempDirectory("aegis-trust-ssh-pending-test").resolve("authorized-devices.json")
            val store = FileDeviceTrustStore(path, clock = { 200L }, payloadProtector = PlainTrustStorePayloadProtector)
            store.saveAuthorization(
                localSshAuthorization("phone-ssh-persisted", 74).copy(
                    revokedAtEpochMillis = 200L,
                    sshKeyRemovalPending = true,
                    sshKeyRemovalFailureCode = "SSH-7326",
                ),
            )

            val restored = FileDeviceTrustStore(path, payloadProtector = PlainTrustStorePayloadProtector).listAuthorizedDevices().single()
            assertNotNull(restored.revokedAtEpochMillis)
            assertTrue(restored.sshKeyRemovalPending)
            assertEquals("SSH-7326", restored.sshKeyRemovalFailureCode)
        }

    @Test
    fun fileTrustStorePermanentlyDeletesOnlyRevokedRecords() =
        runTest {
            val path = Files.createTempDirectory("aegis-trust-delete-test").resolve("authorized-devices.json")
            val store = FileDeviceTrustStore(path, clock = { 200L }, payloadProtector = PlainTrustStorePayloadProtector)
            store.saveAuthorization(
                DeviceAuthorization(
                    remoteDeviceId = RemoteDeviceId("phone-1"),
                    displayName = "Android",
                    approvedAtEpochMillis = 100L,
                    permissions = DevicePermissions(visual = true),
                ),
            )

            assertEquals(DeleteRevokedDeviceResult.MustRevokeFirst, store.deleteRevoked("phone-1"))
            assertEquals(1, store.listAuthorizedDevices().size)
            store.revoke("phone-1")
            assertEquals(DeleteRevokedDeviceResult.Deleted, store.deleteRevoked("phone-1"))
            assertTrue(FileDeviceTrustStore(path, payloadProtector = PlainTrustStorePayloadProtector).listAuthorizedDevices().isEmpty())
            assertEquals(DeleteRevokedDeviceResult.NotFound, store.deleteRevoked("phone-1"))
        }

    @Test
    fun fileTrustStoreCanProtectStoredPayload() =
        runTest {
            val path = Files.createTempDirectory("aegis-trust-protected-test").resolve("authorized-devices.json")
            val protector = PrefixTrustStorePayloadProtector()
            val store = FileDeviceTrustStore(path, clock = { 200L }, payloadProtector = protector)

            store.saveAuthorization(
                DeviceAuthorization(
                    remoteDeviceId = RemoteDeviceId("phone-1"),
                    displayName = "Android",
                    approvedAtEpochMillis = 100L,
                    permissions = DevicePermissions(terminal = true),
                ),
            )

            val raw = Files.readString(path)
            assertTrue(raw.startsWith("test-protected:"))
            assertFalse(raw.contains("Android"))
            val reloaded = FileDeviceTrustStore(path, clock = { 300L }, payloadProtector = protector)

            assertEquals("Android", reloaded.listAuthorizedDevices().single().displayName)
        }

    @Test
    fun fileTrustStoreReadsLegacyPlainJsonWhenProtectorIsConfigured() =
        runTest {
            val path = Files.createTempDirectory("aegis-trust-legacy-test").resolve("authorized-devices.json")
            FileDeviceTrustStore(path, clock = { 200L }, payloadProtector = PlainTrustStorePayloadProtector)
                .saveAuthorization(
                    DeviceAuthorization(
                        remoteDeviceId = RemoteDeviceId("phone-1"),
                        displayName = "Android",
                        approvedAtEpochMillis = 100L,
                        permissions = DevicePermissions(terminal = true),
                    ),
                )

            val reloaded = FileDeviceTrustStore(path, clock = { 300L }, payloadProtector = PrefixTrustStorePayloadProtector())

            assertEquals("Android", reloaded.listAuthorizedDevices().single().displayName)
        }

    @Test
    fun fileTrustStoreCorruptJsonFailsClosedWithoutReplacingOriginalBlob() =
        runTest {
            val path = Files.createTempDirectory("aegis-trust-corrupt-json-test").resolve("authorized-devices.json")
            FileDeviceTrustStore(path, payloadProtector = PlainTrustStorePayloadProtector).saveAuthorization(testTrustAuthorization("seed"))
            Files.writeString(path, "{not-valid-json")
            val original = Files.readAllBytes(path)
            val store = FileDeviceTrustStore(path, payloadProtector = PlainTrustStorePayloadProtector)

            val listFailure = runCatching { store.listAuthorizedDevices() }.exceptionOrNull()
            val saveFailure = runCatching { store.saveAuthorization(testTrustAuthorization("phone-corrupt")) }.exceptionOrNull()
            val revokeFailure = runCatching { store.revoke("phone-corrupt") }.exceptionOrNull()
            val deleteFailure = runCatching { store.deleteRevoked("phone-corrupt") }.exceptionOrNull()

            assertEquals(TrustStoreFailureReason.Json, (listFailure as? TrustStoreAccessException)?.reason)
            assertEquals(TrustStoreFailureReason.Json, (saveFailure as? TrustStoreAccessException)?.reason)
            assertEquals(TrustStoreFailureReason.Json, (revokeFailure as? TrustStoreAccessException)?.reason)
            assertEquals(TrustStoreFailureReason.Json, (deleteFailure as? TrustStoreAccessException)?.reason)
            assertTrue(Files.readAllBytes(path).contentEquals(original))
        }

    @Test
    fun fileTrustStoreInvalidDpapiPayloadFailsClosedWithoutReplacingOriginalBlob() =
        runTest {
            val path = Files.createTempDirectory("aegis-trust-invalid-dpapi-test").resolve("authorized-devices.json")
            FileDeviceTrustStore(path, payloadProtector = PlainTrustStorePayloadProtector).saveAuthorization(testTrustAuthorization("seed"))
            Files.writeString(path, "aegis-dpapi:v1:not-base64!")
            val original = Files.readAllBytes(path)
            val store = FileDeviceTrustStore(path, payloadProtector = WindowsDpapiTrustStorePayloadProtector())

            val failure = runCatching { store.saveAuthorization(testTrustAuthorization("phone-dpapi")) }.exceptionOrNull()

            assertEquals(TrustStoreFailureReason.PayloadBase64, (failure as? TrustStoreAccessException)?.reason)
            assertTrue(Files.readAllBytes(path).contentEquals(original))
        }

    @Test
    fun fileTrustStoreProtectorFailureAndReadFailureAreTypedAndFailClosed() =
        runTest {
            val protectedPath = Files.createTempDirectory("aegis-trust-protector-error-test").resolve("authorized-devices.json")
            FileDeviceTrustStore(protectedPath, payloadProtector = PlainTrustStorePayloadProtector).saveAuthorization(testTrustAuthorization("seed"))
            Files.writeString(protectedPath, "test-failing-protector")
            val protectedOriginal = Files.readAllBytes(protectedPath)
            val protectedStore = FileDeviceTrustStore(protectedPath, payloadProtector = FailingTrustStorePayloadProtector())
            val protectorFailure = runCatching { protectedStore.revoke("phone-protector") }.exceptionOrNull()

            val directoryPath = Files.createTempDirectory("aegis-trust-read-error-test")
            val readStore = FileDeviceTrustStore(directoryPath, payloadProtector = PlainTrustStorePayloadProtector)
            val readFailure = runCatching { readStore.listAuthorizedDevices() }.exceptionOrNull()

            assertEquals(TrustStoreFailureReason.PayloadUnprotect, (protectorFailure as? TrustStoreAccessException)?.reason)
            assertTrue(Files.readAllBytes(protectedPath).contentEquals(protectedOriginal))
            assertTrue(
                (readFailure as? TrustStoreAccessException)?.reason in
                    setOf(TrustStoreFailureReason.FileRead, TrustStoreFailureReason.FileAccess),
            )
        }

    @Test
    fun fileTrustStoreRejectsInsecureOrUnreadableAclBeforeDecoding() =
        runTest {
            val path = Files.createTempDirectory("aegis-trust-acl-test").resolve("authorized-devices.json")
            FileDeviceTrustStore(path, payloadProtector = PlainTrustStorePayloadProtector).saveAuthorization(testTrustAuthorization("seed"))
            val original = Files.readAllBytes(path)
            val insecureStore =
                FileDeviceTrustStore(
                    path,
                    payloadProtector = PlainTrustStorePayloadProtector,
                    securityInspector = TrustStoreSecurityInspector { _, _ -> throw SecurityException("extra ACL principal") },
                )
            val unreadableAclStore =
                FileDeviceTrustStore(
                    path,
                    payloadProtector = PlainTrustStorePayloadProtector,
                    securityInspector = TrustStoreSecurityInspector { _, _ -> error("ACL query unavailable") },
                )

            val insecureFailure = runCatching { insecureStore.listAuthorizedDevices() }.exceptionOrNull()
            val queryFailure = runCatching { unreadableAclStore.saveAuthorization(testTrustAuthorization("replacement")) }.exceptionOrNull()

            assertEquals(TrustStoreFailureReason.Acl, (insecureFailure as? TrustStoreAccessException)?.reason)
            assertEquals(TrustStoreFailureReason.Acl, (queryFailure as? TrustStoreAccessException)?.reason)
            assertTrue(Files.readAllBytes(path).contentEquals(original))
        }

    @Test
    fun fileTrustStoreAbortsWhenAtomicMoveIsUnavailableWithoutReplacingBlob() =
        runTest {
            val path = Files.createTempDirectory("aegis-trust-atomic-move-test").resolve("authorized-devices.json")
            FileDeviceTrustStore(path, payloadProtector = PlainTrustStorePayloadProtector).saveAuthorization(testTrustAuthorization("seed"))
            val original = Files.readAllBytes(path)
            val store =
                FileDeviceTrustStore(
                    path,
                    payloadProtector = PlainTrustStorePayloadProtector,
                    atomicMove = { _, _ -> throw java.nio.file.AtomicMoveNotSupportedException("source", "target", "test") },
                )

            val failure = runCatching { store.saveAuthorization(testTrustAuthorization("replacement")) }.exceptionOrNull()

            assertTrue(failure is java.nio.file.AtomicMoveNotSupportedException)
            assertTrue(Files.readAllBytes(path).contentEquals(original))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun explicitBackupRecoveryRestartsPairingOnlyAfterValidatedRestore() =
        runTest {
            val directory = Files.createTempDirectory("aegis-trust-recovery-test")
            val path = directory.resolve("authorized-devices.json")
            FileDeviceTrustStore(path, payloadProtector = PlainTrustStorePayloadProtector).saveAuthorization(testTrustAuthorization("seed"))
            Files.writeString(path, "{corrupt")
            val backup = directory.resolve("authorized-devices.backup.json")
            FileDeviceTrustStore(backup, payloadProtector = PlainTrustStorePayloadProtector).saveAuthorization(testTrustAuthorization("restored"))
            val server = FakeLocalPairingServer()
            val agent =
                DesktopAgent(
                    pairingServer = server,
                    trustStore = FileDeviceTrustStore(path, payloadProtector = PlainTrustStorePayloadProtector),
                    relayConnector = null,
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()
            assertTrue(agent.state.value.trustStoreRecoveryRequired)
            assertEquals(0, server.startCalls)

            agent.restoreTrustStoreFromBackup(backup.toString())
            advanceUntilIdle()

            assertFalse(agent.state.value.trustStoreRecoveryRequired)
            assertTrue(agent.state.value.pairingServerRunning)
            assertEquals(1, server.startCalls)
            assertEquals(
                "restored",
                agent.state.value.authorizedDevices
                    .single()
                    .remoteDeviceId.value,
            )
            assertTrue(
                agent.state.value.logs
                    .any { it.eventCode == AgentLogEventCode.TrustStoreRecovered },
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun explicitResetRecoveryRequiresTheAgentRecoveryStateBeforeReplacingStore() =
        runTest {
            val path = Files.createTempDirectory("aegis-trust-reset-test").resolve("authorized-devices.json")
            FileDeviceTrustStore(path, payloadProtector = PlainTrustStorePayloadProtector).saveAuthorization(testTrustAuthorization("seed"))
            Files.writeString(path, "{corrupt")
            val server = FakeLocalPairingServer()
            val agent =
                DesktopAgent(
                    pairingServer = server,
                    trustStore = FileDeviceTrustStore(path, payloadProtector = PlainTrustStorePayloadProtector),
                    relayConnector = null,
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()
            assertTrue(agent.state.value.trustStoreRecoveryRequired)
            assertEquals("{corrupt", Files.readString(path))

            agent.resetTrustStoreAfterConsent()
            advanceUntilIdle()

            assertFalse(agent.state.value.trustStoreRecoveryRequired)
            assertTrue(agent.state.value.pairingServerRunning)
            assertTrue(
                agent.state.value.authorizedDevices
                    .isEmpty(),
            )
            assertEquals(1, server.startCalls)
        }

    @Test
    fun fileDesktopSettingsRepositoryPersistsBooleanSettingsWithOwnerOnlyPermissions() =
        runTest {
            val path = Files.createTempDirectory("aegis-settings-test").resolve("desktop-settings.json")
            val repository = FileDesktopAppSettingsRepository(path)

            assertFalse(repository.getBoolean(DESKTOP_CLIPBOARD_SYNC_ENABLED_KEY, default = false))
            repository.putBoolean(DESKTOP_CLIPBOARD_SYNC_ENABLED_KEY, true)

            val reloaded = FileDesktopAppSettingsRepository(path)
            assertTrue(reloaded.getBoolean(DESKTOP_CLIPBOARD_SYNC_ENABLED_KEY, default = false))

            if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(path),
                )
            }
            Files.getFileAttributeView(path, AclFileAttributeView::class.java)?.let { aclView ->
                val owner = Files.getOwner(path)
                assertEquals(1, aclView.acl.size)
                assertEquals(owner, aclView.acl.single().principal())
            }
        }

    @Test
    fun fileDesktopSettingsRepositoryPersistsRelayConfigurationAlongsideBooleanSettings() =
        runTest {
            val path = Files.createTempDirectory("aegis-relay-settings-test").resolve("desktop-settings.json")
            val repository = FileDesktopAppSettingsRepository(path)
            repository.putBoolean(DESKTOP_CLIPBOARD_SYNC_ENABLED_KEY, true)
            repository.saveRelayConfig(
                RelayConfig(
                    relayUrl = "https://relay.example.test",
                    deviceId = RelayDeviceId("desktop-stable"),
                    enabled = true,
                ),
            )

            val reloaded = FileDesktopAppSettingsRepository(path)
            val relay = assertNotNull(reloaded.getRelayConfig())
            assertEquals("https://relay.example.test", relay.relayUrl)
            assertEquals("desktop-stable", relay.deviceId?.value)
            assertTrue(relay.enabled)
            assertTrue(reloaded.getBoolean(DESKTOP_CLIPBOARD_SYNC_ENABLED_KEY, default = false))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun startRestoresPersistedClipboardSyncSetting() =
        runTest {
            val path = Files.createTempDirectory("aegis-agent-settings-test").resolve("desktop-settings.json")
            val settings = FileDesktopAppSettingsRepository(path)
            settings.putBoolean(DESKTOP_CLIPBOARD_SYNC_ENABLED_KEY, true)
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = null,
                    settingsRepository = settings,
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()

            assertTrue(agent.state.value.clipboardSyncEnabled)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun setClipboardSyncEnabledPersistsSetting() =
        runTest {
            val path = Files.createTempDirectory("aegis-agent-settings-write-test").resolve("desktop-settings.json")
            val settings = FileDesktopAppSettingsRepository(path)
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = null,
                    settingsRepository = settings,
                    scope = serviceScope(),
                )

            agent.setClipboardSyncEnabled(true)
            advanceUntilIdle()

            assertTrue(FileDesktopAppSettingsRepository(path).getBoolean(DESKTOP_CLIPBOARD_SYNC_ENABLED_KEY, default = false))
        }

    @Test
    fun linuxAutostartManagerWritesAndRemovesUserDesktopEntry() {
        val home = Files.createTempDirectory("aegis-linux-autostart-test")
        val manager =
            UserDesktopAutostartManager(
                osName = "Linux",
                userHome = home,
                launchCommandProvider = { listOf("/opt/Aegis Remote Desktop/bin/Aegis Remote Desktop") },
            )

        val enabled = manager.setEnabled(true)
        val entry = home.resolve(".config").resolve("autostart").resolve("aegis-remote-desktop.desktop")

        assertTrue(enabled.enabled)
        assertTrue(Files.readString(entry).contains("Exec=\"/opt/Aegis Remote Desktop/bin/Aegis Remote Desktop\""))

        val disabled = manager.setEnabled(false)

        assertFalse(disabled.enabled)
        assertFalse(Files.exists(entry))
    }

    @Test
    fun windowsAutostartManagerWritesAndRemovesUserStartupCommand() {
        val appData = Files.createTempDirectory("aegis-windows-autostart-test")
        val manager =
            UserDesktopAutostartManager(
                osName = "Windows 11",
                appData = appData.toString(),
                launchCommandProvider = { listOf("C:\\Program Files\\Aegis Remote Desktop\\Aegis Remote Desktop.exe") },
            )

        val enabled = manager.setEnabled(true)
        val entry =
            appData
                .resolve("Microsoft")
                .resolve("Windows")
                .resolve("Start Menu")
                .resolve("Programs")
                .resolve("Startup")
                .resolve("Aegis Remote Desktop.cmd")

        assertTrue(enabled.enabled)
        assertTrue(Files.readString(entry).contains("\"C:\\Program Files\\Aegis Remote Desktop\\Aegis Remote Desktop.exe\""))

        val disabled = manager.setEnabled(false)

        assertFalse(disabled.enabled)
        assertFalse(Files.exists(entry))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun startPublishesAutostartStatusAndToggleUpdatesIt() =
        runTest {
            val autostart = FakeAutostartManager(DesktopAutostartStatus(available = true, enabled = false, message = "startup target"))
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = null,
                    autostartManager = autostart,
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()
            agent.setAutostartEnabled(true)
            advanceUntilIdle()

            assertTrue(agent.state.value.autostartAvailable)
            assertTrue(agent.state.value.autostartEnabled)
            assertEquals("startup target", agent.state.value.autostartMessage)
            assertEquals(listOf(true), autostart.requests)
        }

    @Test
    fun linuxSecretServiceProtectorStoresPayloadOutOfBand() =
        runTest {
            val runner = RecordingSecretToolRunner()
            val protector = LinuxSecretServiceTrustStorePayloadProtector("store-1", runner)
            val path = Files.createTempDirectory("aegis-trust-secret-service-test").resolve("authorized-devices.json")
            val store = FileDeviceTrustStore(path, clock = { 200L }, payloadProtector = protector)

            store.saveAuthorization(
                DeviceAuthorization(
                    remoteDeviceId = RemoteDeviceId("phone-1"),
                    displayName = "Android",
                    approvedAtEpochMillis = 100L,
                    permissions = DevicePermissions(terminal = true),
                ),
            )

            val marker = Files.readString(path)
            assertEquals("aegis-secret-service:v1:store-1", marker)
            assertEquals(
                "Android",
                FileDeviceTrustStore(path, clock = {
                    300L
                }, payloadProtector = protector).listAuthorizedDevices().single().displayName,
            )
            assertTrue(runner.commands.any { it.take(2) == listOf("secret-tool", "store") })
            assertTrue(runner.commands.any { it.take(2) == listOf("secret-tool", "lookup") })
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun startRegistersConfiguredRelayConnector() =
        runTest {
            val server = FakeLocalPairingServer()
            val relay = FakeDesktopRelayConnector()
            val agent =
                DesktopAgent(
                    pairingServer = server,
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = relay,
                    clock = { 100L },
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()

            assertTrue(agent.state.value.relayConnected)
            assertEquals("https://relay.example.test", agent.state.value.relayUrl)
            assertEquals("pc-1", agent.state.value.relayDeviceId)
            assertTrue(agent.state.value.remoteAccessEnabled)
            assertNotNull(relay.registeredDisplayName)
            assertEquals(listOf(true), relay.registrations)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun userRotationClosesOldRelayContextAndPublishesConfirmedGeneration() =
        runTest {
            val relay = FakeDesktopRelayConnector()
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = relay,
                    scope = serviceScope(),
                )
            agent.start()
            advanceUntilIdle()

            agent.rotateRelayIdentity()
            advanceUntilIdle()

            assertEquals(listOf(KeyRotationReason.UserRequested), relay.rotationReasons)
            assertEquals(RelayConfigurationMessageCode.IdentityRotated, agent.state.value.relayConfigurationMessageCode)
            assertTrue(agent.state.value.relayConnected)
            assertTrue(
                agent.state.value.logs.any {
                    it.eventCode == AgentLogEventCode.RelayIdentityChanged && it.context["action"] == "rotation-completed"
                },
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun removingConnectedRelayRevokesIdentityBeforeClosingConnector() =
        runTest {
            val relay = FakeDesktopRelayConnector()
            val settings =
                FileDesktopAppSettingsRepository(
                    Files.createTempDirectory("aegis-relay-revoke-test").resolve("desktop-settings.json"),
                )
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = relay,
                    settingsRepository = settings,
                    relayConfigRepository = settings,
                    scope = serviceScope(),
                )
            agent.start()
            advanceUntilIdle()

            agent.clearRelayConfiguration()
            advanceUntilIdle()

            assertEquals(1, relay.revocations)
            assertTrue(relay.closed)
            assertEquals(RelayConfigurationMessageCode.Removed, agent.state.value.relayConfigurationMessageCode)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun startRestoresPersistedRelayAndRegistersWithoutEnvironmentVariables() =
        runTest {
            val path = Files.createTempDirectory("aegis-relay-restore-test").resolve("desktop-settings.json")
            val settings = FileDesktopAppSettingsRepository(path)
            settings.saveRelayConfig(
                RelayConfig(
                    relayUrl = "https://saved-relay.example.test",
                    deviceId = RelayDeviceId("saved-pc"),
                    enabled = true,
                ),
            )
            val created = mutableListOf<FakeDesktopRelayConnector>()
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = null,
                    settingsRepository = settings,
                    relayConfigRepository = settings,
                    relayConnectorFactory = { config ->
                        FakeDesktopRelayConnector(
                            relayUrl = config.relayUrl,
                            remoteAccessEnabled = config.enabled,
                            registeredRelayDeviceId = config.deviceId ?: RelayDeviceId("assigned-pc"),
                        ).also(created::add)
                    },
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()

            assertEquals(1, created.size)
            assertEquals(listOf(true), created.single().registrations)
            assertEquals("https://saved-relay.example.test", agent.state.value.relayUrl)
            assertEquals("saved-pc", agent.state.value.relayDeviceId)
            assertTrue(agent.state.value.relayConnected)
            assertTrue(agent.state.value.remoteAccessEnabled)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun configureRelayPersistsRebuildsAndConnectsImmediately() =
        runTest {
            val path = Files.createTempDirectory("aegis-relay-configure-test").resolve("desktop-settings.json")
            val settings = FileDesktopAppSettingsRepository(path)
            val createdConfigs = mutableListOf<RelayConfig>()
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = null,
                    settingsRepository = settings,
                    relayConfigRepository = settings,
                    relayConnectorFactory = { config ->
                        createdConfigs += config
                        FakeDesktopRelayConnector(
                            relayUrl = config.relayUrl,
                            remoteAccessEnabled = config.enabled,
                            registeredRelayDeviceId = config.deviceId ?: RelayDeviceId("assigned-pc"),
                        )
                    },
                    scope = serviceScope(),
                )
            agent.start()
            advanceUntilIdle()

            agent.configureRelay(" https://relay.ui.test/ ", "ui-pc")
            advanceUntilIdle()

            assertEquals("https://relay.ui.test", createdConfigs.single().relayUrl)
            assertEquals("ui-pc", createdConfigs.single().deviceId?.value)
            assertEquals("https://relay.ui.test", agent.state.value.relayUrl)
            assertEquals("ui-pc", agent.state.value.relayDeviceId)
            assertTrue(agent.state.value.relayConnected)
            assertEquals("Relay conectado", agent.state.value.relayConfigurationMessage)
            assertEquals("ui-pc", settings.getRelayConfig()?.deviceId?.value)
        }

    @Test
    fun configureRelayRejectsUnsafeUrlBeforeCreatingConnector() =
        runTest {
            var factoryCalls = 0
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    relayConnector = null,
                    relayConnectorFactory = {
                        factoryCalls += 1
                        FakeDesktopRelayConnector()
                    },
                    scope = serviceScope(),
                )

            agent.configureRelay("ftp://user:secret@relay.example.test/#fragment", "bad id")

            assertEquals(0, factoryCalls)
            assertFalse(agent.state.value.relayConfigurationBusy)
            assertTrue(
                agent.state.value.relayConfigurationMessage
                    .orEmpty()
                    .isNotBlank(),
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun stopClosesRelayConnectorBeforeReturning() =
        runTest {
            val relay = FakeDesktopRelayConnector()
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = relay,
                    scope = serviceScope(),
                )
            agent.start()
            advanceUntilIdle()

            agent.stop()

            assertTrue(relay.closed)
            assertFalse(agent.state.value.pairingServerRunning)
            assertFalse(agent.state.value.relayConnected)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun stopAlsoStopsAnOwnedUserScopedOpenSshChild() =
        runTest {
            val openSsh = FakeAegisOpenSshManager()
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = null,
                    openSshProvisioner = openSsh,
                    scope = serviceScope(),
                )
            agent.start()
            advanceUntilIdle()

            agent.stop()

            assertEquals(1, openSsh.shutdownCalls)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun relayEventFailureReRegistersWithBackoff() =
        runTest {
            val relay = FakeDesktopRelayConnector().apply { eventOpenFailures = 1 }
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = relay,
                    relayReconnectionPolicy =
                        BoundedDesktopRelayReconnectPolicy(
                            baseDelayMillis = 1,
                            maxDelayMillis = 1,
                            maxAttempts = 2,
                        ),
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()

            assertEquals(listOf(true, true), relay.registrations)
            assertTrue(agent.state.value.relayConnected)
            assertTrue(
                agent.state.value.logs
                    .any { it.message == "Relay event stream re-registered" },
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun startAndStopAreIdempotentAndCallbacksFromTheStoppedGenerationAreIgnored() =
        runTest {
            val server = FakeLocalPairingServer()
            val agent =
                DesktopAgent(
                    pairingServer = server,
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = null,
                    scope = serviceScope(),
                )

            agent.start()
            agent.start()
            advanceUntilIdle()
            assertEquals(1, server.startCalls)

            agent.stop()
            agent.stop()
            assertEquals(1, server.stopCalls)

            val lateDispatch =
                server.emit(
                    DesktopPairingRequest(
                        requestId = "late-pairing",
                        deviceName = "late-device",
                        fingerprint = "SHA256:late",
                        requestedAtEpochMillis = 1L,
                        remote = false,
                    ),
                )
            assertEquals(PairingRequestDispatchResult.AgentUnavailable, lateDispatch)
            val channel = RecordingProtocolChannel()
            server.emitProtocol(
                LocalProtocolChannelRequest(
                    sessionId = SessionId("late-session"),
                    authorizedDeviceId = "late-device",
                    sessionProof = null,
                    proofTimestampEpochMillis = null,
                    channel = channel,
                ),
            )
            advanceUntilIdle()

            assertTrue(
                agent.state.value.pendingPairingRequests
                    .isEmpty(),
            )
            assertTrue(channel.closed)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun expiredStatusPollingRemovesThePendingPairingFromTheDesktopState() =
        runTest {
            val server = FakeLocalPairingServer()
            val agent =
                DesktopAgent(
                    pairingServer = server,
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = null,
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()
            server.emit(
                DesktopPairingRequest(
                    requestId = "expired-pairing",
                    deviceName = "expired-device",
                    fingerprint = "SHA256:expired",
                    requestedAtEpochMillis = 1L,
                    remote = false,
                ),
            )
            assertTrue(
                agent
                    .state
                    .value
                    .pendingPairingRequests
                    .any { it.requestId == "expired-pairing" },
            )

            server.emitPairingEvent(
                PairingServerEvent(
                    requestId = "expired-pairing",
                    stage = PairingServerEventStage.PAIRING_STATUS_POLLED,
                    result = "expired",
                    remoteHost = "127.0.0.1",
                    lifecycleGeneration = null,
                    pendingCount = 1,
                    latencyMillis = 1,
                ),
            )

            assertTrue(
                agent
                    .state
                    .value
                    .pendingPairingRequests
                    .none { it.requestId == "expired-pairing" },
            )
            assertTrue(
                agent
                    .state
                    .value
                    .logs
                    .any { it.eventCode == AgentLogEventCode.PairingRequestExpired },
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun acceptedPairingSurvivesAConcurrentStateUpdate() =
        runTest {
            val server = FakeLocalPairingServer()
            val agent =
                DesktopAgent(
                    pairingServer = server,
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = null,
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()
            val request =
                DesktopPairingRequest(
                    requestId = "concurrent-pairing",
                    deviceName = "concurrent-device",
                    fingerprint = "SHA256:concurrent",
                    requestedAtEpochMillis = 1L,
                    remote = false,
                )
            val dispatch = async(Dispatchers.Default) { server.emit(request) }
            val stateUpdates =
                async(Dispatchers.Default) {
                    repeat(100) { index -> agent.setRemoteInputEnabled(index % 2 == 0) }
                }

            assertEquals(PairingRequestDispatchResult.Accepted, dispatch.await())
            stateUpdates.await()
            assertTrue(
                agent
                    .state
                    .value
                    .pendingPairingRequests
                    .any { it.requestId == request.requestId },
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun stopDuringPairingInitializationCancelsTheStartupWithoutLeavingTheServerRunning() =
        runTest {
            val startGate = CompletableDeferred<Unit>()
            val server = FakeLocalPairingServer(startGate = startGate)
            val agent =
                DesktopAgent(
                    pairingServer = server,
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = null,
                    scope = serviceScope(),
                )

            agent.start()
            runCurrent()
            assertEquals(1, server.startCalls)

            agent.stop()
            startGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, server.stopCalls)
            assertFalse(agent.state.value.pairingServerRunning)
            assertTrue(
                agent.state.value.pendingPairingRequests
                    .isEmpty(),
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun startPublishesDesktopCapabilitiesAndMonitors() =
        runTest {
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = null,
                    monitorProvider = FakeMonitorProvider(),
                    capabilityDetector =
                        FakeCapabilityDetector(
                            DesktopCapabilityReport(
                                capture = CapabilityStatus.Available,
                                input = CapabilityStatus.PermissionRequired,
                            ),
                        ),
                    clock = { 100L },
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()

            assertEquals(CapabilityStatus.Available, agent.state.value.captureCapability)
            assertEquals(CapabilityStatus.PermissionRequired, agent.state.value.inputCapability)
            assertEquals(
                "primary",
                agent.state.value.monitors
                    .single()
                    .id.value,
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun relaySessionRequestedEventBecomesPendingRelaySession() =
        runTest {
            val identity = relayIdentity(11)
            val relay =
                FakeDesktopRelayConnector(
                    events =
                        flowOf(
                            RelayDeviceEvent.SessionRequested(
                                sessionId = SessionId("session-1"),
                                sourceRelayDeviceId = RelayDeviceId("android-1"),
                                targetRelayDeviceId = RelayDeviceId("pc-1"),
                                expiresAtEpochMillis = 5_000L,
                                sourceIdentity =
                                    RelayDeviceIdentity(
                                        relayDeviceId = RelayDeviceId("android-1"),
                                        displayName = "Ian Phone",
                                        publicKeyFingerprint = "SHA256:phone-relay",
                                        publicIdentity = identity,
                                    ),
                            ),
                        ),
                )
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = relay,
                    clock = { 100L },
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()

            val pending =
                agent.state.value.pendingRelaySessions
                    .single()
            assertEquals("session-1", pending.sessionId.value)
            assertEquals("android-1", pending.sourceRelayDeviceId.value)
            assertEquals("Ian Phone", pending.sourceDisplayName)
            assertEquals("SHA256:phone-relay", pending.sourcePublicKeyFingerprint)
            assertEquals(identity.deviceId, pending.sourcePublicIdentity?.deviceId)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun trustedRelayDeviceIsAutoApprovedFromPersistedPolicy() =
        runTest {
            val identity = relayIdentity(12)
            val trustStore = InMemoryDeviceTrustStore()
            trustStore.saveAuthorization(
                DeviceAuthorization(
                    remoteDeviceId = RemoteDeviceId(identity.deviceId.value),
                    displayName = "Relay android-1",
                    approvedAtEpochMillis = 50L,
                    permissions =
                        DevicePermissions(
                            visual = true,
                            input = true,
                            clipboard = true,
                            remoteAccess = true,
                        ),
                    publicIdentity = identity,
                    relayDeviceId = RelayDeviceId("android-1"),
                    approvedTransport = dev.aegis.remote.core.model.DeviceTrustTransport.RelayRemote,
                    lastApprovedCryptoSuiteId = AEGIS_P256_AESGCM_V1.id,
                    cryptoSuiteDowngradeFloor = AEGIS_P256_AESGCM_V1.id,
                ),
            )
            val relay =
                FakeDesktopRelayConnector(
                    events =
                        flowOf(
                            RelayDeviceEvent.SessionRequested(
                                sessionId = SessionId("session-1"),
                                sourceRelayDeviceId = RelayDeviceId("android-1"),
                                targetRelayDeviceId = RelayDeviceId("pc-1"),
                                expiresAtEpochMillis = 5_000L,
                                sourceIdentity =
                                    RelayDeviceIdentity(
                                        relayDeviceId = RelayDeviceId("android-1"),
                                        displayName = "Ian Phone",
                                        publicKeyFingerprint = "SHA256:phone-relay",
                                        publicIdentity = identity,
                                    ),
                            ),
                        ),
                )
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = trustStore,
                    relayConnector = relay,
                    clock = { 100L },
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()

            assertTrue(
                agent.state.value.pendingRelaySessions
                    .isEmpty(),
            )
            assertEquals("session-1:true", relay.decisions.single())
            assertEquals(listOf("session-1"), relay.openedProtocolSessions)

            agent.stop()
            advanceUntilIdle()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun relayDeviceIdMatchWithDifferentPublicKeyIsNeverAutoApproved() =
        runTest {
            val trustedIdentity = relayIdentity(13)
            val attackerIdentity = relayIdentity(14)
            val trustStore = InMemoryDeviceTrustStore()
            trustStore.saveAuthorization(
                DeviceAuthorization(
                    remoteDeviceId = RemoteDeviceId(trustedIdentity.deviceId.value),
                    displayName = "Relay android-1 (SHA256:trusted-key)",
                    approvedAtEpochMillis = 50L,
                    permissions =
                        DevicePermissions(
                            visual = true,
                            input = true,
                            remoteAccess = true,
                        ),
                    publicIdentity = trustedIdentity,
                    relayDeviceId = RelayDeviceId("android-1"),
                    approvedTransport = dev.aegis.remote.core.model.DeviceTrustTransport.RelayRemote,
                    lastApprovedCryptoSuiteId = AEGIS_P256_AESGCM_V1.id,
                    cryptoSuiteDowngradeFloor = AEGIS_P256_AESGCM_V1.id,
                ),
            )
            val relay =
                FakeDesktopRelayConnector(
                    events =
                        flowOf(
                            RelayDeviceEvent.SessionRequested(
                                sessionId = SessionId("session-attacker-key"),
                                sourceRelayDeviceId = RelayDeviceId("android-1"),
                                targetRelayDeviceId = RelayDeviceId("pc-1"),
                                expiresAtEpochMillis = 5_000L,
                                sourceIdentity =
                                    RelayDeviceIdentity(
                                        relayDeviceId = RelayDeviceId("android-1"),
                                        displayName = "Relay android-1 (SHA256:trusted-key)",
                                        publicKeyFingerprint = "SHA256:attacker-key",
                                        publicIdentity = attackerIdentity,
                                    ),
                            ),
                        ),
                )
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = trustStore,
                    relayConnector = relay,
                    clock = { 100L },
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()

            assertEquals(emptyList(), relay.decisions)
            assertEquals(
                "session-attacker-key",
                agent.state.value.pendingRelaySessions
                    .single()
                    .sessionId.value,
            )

            agent.stop()
            advanceUntilIdle()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun exactLocatorAndKeyBytesWithDifferentAlgorithmIsNeverAutoApproved() =
        runTest {
            val trustedIdentity = relayIdentity(31)
            val substitutedAlgorithm =
                trustedIdentity.copy(
                    algorithm = IdentitySignatureAlgorithm.ECDSA_P256_SHA256,
                )
            val trustStore = InMemoryDeviceTrustStore()
            trustStore.saveAuthorization(
                DeviceAuthorization(
                    remoteDeviceId = RemoteDeviceId(trustedIdentity.deviceId.value),
                    displayName = "Relay android-1",
                    approvedAtEpochMillis = 50L,
                    permissions = DevicePermissions(visual = true, remoteAccess = true),
                    publicIdentity = trustedIdentity,
                    relayDeviceId = RelayDeviceId("android-1"),
                    approvedTransport = dev.aegis.remote.core.model.DeviceTrustTransport.RelayRemote,
                    lastApprovedCryptoSuiteId = AEGIS_P256_AESGCM_V1.id,
                    cryptoSuiteDowngradeFloor = AEGIS_P256_AESGCM_V1.id,
                ),
            )
            val relay =
                FakeDesktopRelayConnector(
                    events =
                        flowOf(
                            RelayDeviceEvent.SessionRequested(
                                sessionId = SessionId("session-substituted-algorithm"),
                                sourceRelayDeviceId = RelayDeviceId("android-1"),
                                targetRelayDeviceId = RelayDeviceId("pc-1"),
                                expiresAtEpochMillis = 5_000L,
                                sourceIdentity =
                                    RelayDeviceIdentity(
                                        RelayDeviceId("android-1"),
                                        "Relay android-1",
                                        "SHA256:same-text",
                                        substitutedAlgorithm,
                                    ),
                            ),
                        ),
                )
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = trustStore,
                    relayConnector = relay,
                    clock = { 100L },
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()

            assertEquals(emptyList(), relay.decisions)
            assertEquals(
                "session-substituted-algorithm",
                agent.state.value.pendingRelaySessions
                    .single()
                    .sessionId.value,
            )
            agent.stop()
            advanceUntilIdle()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun approvingRelaySessionRemovesPendingSession() =
        runTest {
            val identity = relayIdentity(15)
            val relay =
                FakeDesktopRelayConnector(
                    events =
                        flowOf(
                            RelayDeviceEvent.SessionRequested(
                                sessionId = SessionId("session-1"),
                                sourceRelayDeviceId = RelayDeviceId("android-1"),
                                targetRelayDeviceId = RelayDeviceId("pc-1"),
                                expiresAtEpochMillis = 5_000L,
                                sourceIdentity =
                                    RelayDeviceIdentity(
                                        relayDeviceId = RelayDeviceId("android-1"),
                                        displayName = "Ian Phone",
                                        publicKeyFingerprint = "SHA256:phone-relay",
                                        publicIdentity = identity,
                                    ),
                            ),
                        ),
                )
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = relay,
                    monitorProvider = FakeMonitorProvider(),
                    clock = { 100L },
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()
            agent.approveRelaySession("session-1")
            advanceUntilIdle()

            assertTrue(
                agent.state.value.pendingRelaySessions
                    .isEmpty(),
            )
            assertEquals("session-1:true", relay.decisions.single())
            val authorization =
                assertNotNull(
                    agent.state.value.authorizedDevices
                        .singleOrNull(),
                    agent.state.value.logs
                        .joinToString(" | ") { "${it.eventCode}:${it.message}" },
                )
            assertEquals(identity.deviceId.value, authorization.remoteDeviceId.value)
            assertEquals("Relay Ian Phone", authorization.displayName)
            assertEquals(identity.deviceId, authorization.publicIdentity?.deviceId)
            assertEquals(RelayDeviceId("android-1"), authorization.relayDeviceId)
            assertTrue(authorization.permissions.remoteAccess)
            assertTrue(authorization.permissions.visual)
            assertTrue(authorization.permissions.input)
            assertTrue(authorization.permissions.clipboard)
            assertEquals(false, authorization.permissions.terminal)
            assertEquals(false, authorization.permissions.sftp)
            assertEquals(AEGIS_P256_AESGCM_V1.id, authorization.lastApprovedCryptoSuiteId)
            assertEquals(AEGIS_P256_AESGCM_V1.id, authorization.cryptoSuiteDowngradeFloor)
            assertEquals(listOf("session-1"), relay.openedProtocolSessions)

            agent.stop()
            advanceUntilIdle()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun approvingRelaySessionPublishesMonitorTelemetry() =
        runTest {
            val identity = relayIdentity(16)
            val relay =
                FakeDesktopRelayConnector(
                    events =
                        flowOf(
                            RelayDeviceEvent.SessionRequested(
                                sessionId = SessionId("session-1"),
                                sourceRelayDeviceId = RelayDeviceId("android-1"),
                                targetRelayDeviceId = RelayDeviceId("pc-1"),
                                expiresAtEpochMillis = 5_000L,
                                sourceIdentity =
                                    RelayDeviceIdentity(
                                        relayDeviceId = RelayDeviceId("android-1"),
                                        displayName = "Ian Phone",
                                        publicKeyFingerprint = "SHA256:phone-relay",
                                        publicIdentity = identity,
                                    ),
                            ),
                        ),
                )
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = relay,
                    monitorProvider = FakeMonitorProvider(),
                    clock = { 100L },
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()
            agent.approveRelaySession("session-1")
            advanceUntilIdle()

            val monitorMessage = relay.sentProtocolMessages.single() as ProtocolMessage.Monitors
            assertEquals("session-1", monitorMessage.sessionId.value)
            assertEquals(
                "primary",
                monitorMessage.monitors
                    .single()
                    .id.value,
            )

            agent.stop()
            advanceUntilIdle()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun disablingRemoteAccessReregistersAndRejectsIncomingRelaySessions() =
        runTest {
            val events = MutableSharedFlow<RelayDeviceEvent>()
            val relay = FakeDesktopRelayConnector(events = events)
            val agent =
                DesktopAgent(
                    pairingServer = FakeLocalPairingServer(),
                    trustStore = InMemoryDeviceTrustStore(),
                    relayConnector = relay,
                    clock = { 100L },
                    scope = serviceScope(),
                )

            agent.start()
            advanceUntilIdle()
            agent.setRemoteAccessEnabled(false)
            advanceUntilIdle()
            events.emit(
                RelayDeviceEvent.SessionRequested(
                    sessionId = SessionId("session-2"),
                    sourceRelayDeviceId = RelayDeviceId("android-1"),
                    targetRelayDeviceId = RelayDeviceId("pc-1"),
                    expiresAtEpochMillis = 5_000L,
                ),
            )
            advanceUntilIdle()

            assertEquals(listOf(true, false), relay.registrations)
            assertEquals(false, agent.state.value.remoteAccessEnabled)
            assertTrue(
                agent.state.value.pendingRelaySessions
                    .isEmpty(),
            )
            assertEquals("session-2:false", relay.decisions.single())

            agent.stop()
            advanceUntilIdle()
        }
}

private fun TestScope.serviceScope(): CoroutineScope = CoroutineScope(coroutineContext + SupervisorJob())

class DesktopPlatformCapabilityDetectorTest {
    @Test
    fun windowsCapabilitiesAreAvailableWhenNotHeadless() {
        val report =
            DesktopPlatformCapabilityDetector(
                osName = "Windows 11",
                headless = false,
            ).detect()

        assertEquals(CapabilityStatus.Available, report.capture)
        assertEquals(CapabilityStatus.Available, report.input)
    }

    @Test
    fun waylandReportsYdotoolInputAvailableEvenWhenAwtIsHeadless() {
        val report =
            DesktopPlatformCapabilityDetector(
                osName = "Linux",
                sessionType = "wayland",
                waylandDisplay = "wayland-0",
                headless = true,
                ydotoolAvailable = { true },
            ).detect()

        assertEquals(CapabilityStatus.Degraded, report.capture)
        assertEquals(CapabilityStatus.Available, report.input)
    }

    @Test
    fun waylandInputIsUnavailableWithoutUsableYdotoolSocket() {
        val report =
            DesktopPlatformCapabilityDetector(
                osName = "Linux",
                sessionType = "wayland",
                waylandDisplay = "wayland-0",
                headless = true,
                ydotoolAvailable = { false },
            ).detect()

        assertEquals(CapabilityStatus.Degraded, report.capture)
        assertEquals(CapabilityStatus.Unavailable, report.input)
    }

    @Test
    fun x11InputFallsBackToAwtRobotWithoutXdotoolXtest() {
        val report =
            DesktopPlatformCapabilityDetector(
                osName = "Linux",
                sessionType = "x11",
                display = ":0",
                waylandDisplay = "wayland-stale",
                headless = false,
                xdotoolAvailable = { false },
                ydotoolAvailable = { error("Explicit X11 must not probe the Wayland backend") },
                awtRobotAvailable = { true },
            ).detect()

        assertEquals(CapabilityStatus.Available, report.capture)
        assertEquals(CapabilityStatus.Available, report.input)
    }

    @Test
    fun x11InputIsUnavailableWhenNeitherXdotoolNorAwtRobotCanStart() {
        val report =
            DesktopPlatformCapabilityDetector(
                osName = "Linux",
                sessionType = "x11",
                display = ":0",
                headless = false,
                xdotoolAvailable = { false },
                awtRobotAvailable = { false },
            ).detect()

        assertEquals(CapabilityStatus.Available, report.capture)
        assertEquals(CapabilityStatus.Unavailable, report.input)
    }

    @Test
    fun headlessDesktopCapabilitiesAreUnavailable() {
        val report =
            DesktopPlatformCapabilityDetector(
                osName = "Linux",
                sessionType = "x11",
                display = ":0",
                headless = true,
            ).detect()

        assertEquals(CapabilityStatus.Unavailable, report.capture)
        assertEquals(CapabilityStatus.Unavailable, report.input)
    }
}

private class FakeLocalPairingServer(
    private val approvalError: Throwable? = null,
    private val startGate: CompletableDeferred<Unit>? = null,
) : LocalPairingServer {
    private var callback: (suspend (DesktopPairingRequest) -> PairingRequestDispatchResult)? = null
    var startCalls = 0
    var stopCalls = 0
    private var protocolCallback: (suspend (LocalProtocolChannelRequest) -> Unit)? = null
    private var pairingEventCallback: ((PairingServerEvent) -> Unit)? = null
    var approvedProfile: LocalPairedProfile? = null

    override suspend fun start(
        onPairingRequest: suspend (DesktopPairingRequest) -> PairingRequestDispatchResult,
        onProtocolChannel: suspend (LocalProtocolChannelRequest) -> Unit,
        onPairingEvent: (PairingServerEvent) -> Unit,
    ): LocalPairingSession {
        startCalls += 1
        startGate?.await()
        callback = onPairingRequest
        protocolCallback = onProtocolChannel
        pairingEventCallback = onPairingEvent
        return pairingSession("token-initial")
    }

    override suspend fun refreshPairingSession(): LocalPairingSession = pairingSession("token-refreshed")

    override fun stop() {
        stopCalls += 1
    }

    override fun approve(
        requestId: String,
        profile: LocalPairedProfile,
    ) {
        approvalError?.let { throw it }
        approvedProfile = profile
    }

    override fun reject(requestId: String) = Unit

    suspend fun emit(request: DesktopPairingRequest): PairingRequestDispatchResult =
        callback?.invoke(request)
            ?: PairingRequestDispatchResult.AgentUnavailable

    fun emitPairingEvent(event: PairingServerEvent) {
        pairingEventCallback?.invoke(event)
    }

    suspend fun emitProtocol(request: LocalProtocolChannelRequest) {
        protocolCallback?.invoke(request)
    }

    private fun pairingSession(tokenId: String): LocalPairingSession =
        LocalPairingSession(
            host = "127.0.0.1",
            port = 48291,
            pairingCode = "123456",
            agentFingerprint = "SHA256:test",
            pairingSecret = "0".repeat(32),
            tokenId = tokenId,
            issuedAtEpochMillis = 0L,
            expiresAtEpochMillis = Long.MAX_VALUE,
            hostIdentity = relayIdentity(77),
            hostSignature = "test-signature",
        )
}

private class FakeAegisOpenSshManager : AegisOpenSshManager {
    val removedDeviceIds = mutableListOf<String>()
    val removalResults = ArrayDeque<AegisSshKeyRemovalResult>()
    var removalError: Throwable? = null
    var shutdownCalls: Int = 0

    override suspend fun inspect(): AegisOpenSshState =
        AegisOpenSshState(
            serviceName = "AegisOpenSSH",
            serviceStatus = "Running",
            port = 48_222,
            rootDirectory = "C:\\ProgramData\\Aegis\\OpenSSH",
            configPath = "C:\\ProgramData\\Aegis\\OpenSSH\\sshd_config",
            authorizedKeysPath = "C:\\ProgramData\\Aegis\\OpenSSH\\authorized_keys",
            hostKeyPath = "C:\\ProgramData\\Aegis\\OpenSSH\\ssh_host_ed25519_key",
            hostKeyFingerprint = "SHA256:test-host-key",
            hostKeyAlgorithm = "ssh-ed25519",
            firewallRuleName = "Aegis OpenSSH (Private and Domain)",
            authorizedUser = "aegis-test",
        )

    override suspend fun enrollAuthorizedKey(
        publicKey: String,
        deviceId: String,
    ): AegisSshBootstrapResult =
        AegisSshBootstrapResult(
            username = "aegis-test",
            port = 48_222,
            hostKeyFingerprint =
                dev.aegis.remote.core.model
                    .HostKeyFingerprint("SHA256", "test-host-key"),
            wakeOnLanConfigs = emptyList(),
            enrollment =
                AegisSshKeyEnrollmentResult(
                    deviceId = deviceId,
                    fingerprint = "SHA256:test-client-key",
                    algorithm = "ssh-ed25519",
                    added = true,
                    authorizedKeysPath = "C:\\ProgramData\\Aegis\\OpenSSH\\authorized_keys",
                ),
        )

    override suspend fun removeAuthorizedKey(deviceId: String): AegisSshKeyRemovalResult {
        removedDeviceIds += deviceId
        removalError?.let { throw it }
        return removalResults.removeFirstOrNull()
            ?: AegisSshKeyRemovalResult(
                deviceId = deviceId,
                removed = true,
                authorizedKeysPath = "C:\\ProgramData\\Aegis\\OpenSSH\\authorized_keys",
            )
    }

    override suspend fun shutdown() {
        shutdownCalls += 1
    }
}

private const val TEST_SSH_PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAegisUnitTestOnly"

private fun inflatePairingQrPayload(payload: String): String {
    val inflater = Inflater(true)
    val output = ByteArrayOutputStream()
    return try {
        inflater.setInput(Base64.getUrlDecoder().decode(payload.removePrefix("AEGIS3:")))
        val buffer = ByteArray(256)
        while (!inflater.finished()) {
            output.write(buffer, 0, inflater.inflate(buffer))
        }
        output.toString(Charsets.UTF_8.name())
    } finally {
        inflater.end()
        output.close()
    }
}

private fun testTrustAuthorization(remoteDeviceId: String): DeviceAuthorization =
    DeviceAuthorization(
        remoteDeviceId = RemoteDeviceId(remoteDeviceId),
        displayName = "Android",
        approvedAtEpochMillis = 100L,
        permissions = DevicePermissions(visual = true),
    )

private fun localSshAuthorization(
    remoteDeviceId: String,
    identitySeed: Int,
): DeviceAuthorization =
    DeviceAuthorization(
        remoteDeviceId = RemoteDeviceId(remoteDeviceId),
        displayName = "Android",
        approvedAtEpochMillis = 100L,
        permissions = DevicePermissions(terminal = true, visual = true),
        localProtocolToken = "test-token",
        publicIdentity = relayIdentity(identitySeed),
        approvedTransport = dev.aegis.remote.core.model.DeviceTrustTransport.LocalPairing,
    )

private fun sshRemovalResult(
    deviceId: String,
    removed: Boolean,
): AegisSshKeyRemovalResult =
    AegisSshKeyRemovalResult(
        deviceId = deviceId,
        removed = removed,
        authorizedKeysPath = "C:\\ProgramData\\Aegis\\OpenSSH\\authorized_keys",
    )

private class RecordingProtocolChannel : ProtocolMessageChannel {
    override val incoming: Flow<ProtocolMessage> = emptyFlow()
    val sent = mutableListOf<ProtocolMessage>()
    var closed = false

    override suspend fun send(message: ProtocolMessage) {
        sent += message
    }

    override suspend fun close() {
        closed = true
    }
}

private class FakeDesktopRelayConnector(
    private val events: Flow<RelayDeviceEvent> = emptyFlow(),
    override val relayUrl: String = "https://relay.example.test",
    override val remoteAccessEnabled: Boolean = true,
    private val registeredRelayDeviceId: RelayDeviceId = RelayDeviceId("pc-1"),
) : DesktopRelayConnector {
    private val localIdentity = relayIdentity(99)
    var registeredDisplayName: String? = null
    val decisions = mutableListOf<String>()
    val registrations = mutableListOf<Boolean>()
    val openedProtocolSessions = mutableListOf<String>()
    val sentProtocolMessages = mutableListOf<dev.aegis.remote.protocol.ProtocolMessage>()
    val rotationReasons = mutableListOf<KeyRotationReason>()
    var revocations = 0
    var closed = false
    var eventOpenFailures = 0

    override suspend fun register(
        displayName: String,
        remoteAccessEnabled: Boolean,
    ): RelayRegistration {
        registeredDisplayName = displayName
        registrations += remoteAccessEnabled
        return RelayRegistration(
            relayDeviceId = registeredRelayDeviceId,
            authToken = RelayAuthToken("token-1", 2_000L),
        )
    }

    override suspend fun localPublicIdentity(): DevicePublicIdentity = localIdentity

    override suspend fun openDeviceEvents(): Flow<RelayDeviceEvent> {
        if (eventOpenFailures > 0) {
            eventOpenFailures -= 1
            error("event stream unavailable")
        }
        return events
    }

    override suspend fun approveSession(
        sessionId: SessionId,
        approved: Boolean,
    ) {
        decisions += "${sessionId.value}:$approved"
    }

    override suspend fun openProtocolMessageChannel(
        sessionId: SessionId,
        peerIdentity: DevicePublicIdentity,
    ): ProtocolMessageChannel {
        openedProtocolSessions += sessionId.value
        return object : ProtocolMessageChannel {
            override val incoming: Flow<dev.aegis.remote.protocol.ProtocolMessage> = emptyFlow()

            override suspend fun send(message: dev.aegis.remote.protocol.ProtocolMessage) {
                sentProtocolMessages += message
            }

            override suspend fun close() = Unit
        }
    }

    override suspend fun requestTurnCredentials(): dev.aegis.remote.core.relay.RelayTurnCredentials =
        dev.aegis.remote.core.relay.RelayTurnCredentials(
            urls = listOf("turn:relay.example.test:3478"),
            username = "desktop",
            credential = "test-only",
            expiresAtEpochMillis = Long.MAX_VALUE,
        )

    override suspend fun rotateIdentity(
        reason: KeyRotationReason,
        displayName: String,
        remoteAccessEnabled: Boolean,
    ): RelayIdentityRotationResult {
        rotationReasons += reason
        val identity =
            object : LocalDeviceIdentity {
                override val publicIdentity = relayIdentity(100).copy(keyGeneration = 2L)

                override suspend fun sign(payload: ByteArray): ByteArray = payload.copyOf()
            }
        return RelayIdentityRotationResult(
            operationId = "00000000-0000-0000-0000-000000000301",
            reason = reason,
            identity = identity,
            registration =
                RelayRegistration(
                    relayDeviceId = registeredRelayDeviceId,
                    authToken = RelayAuthToken("token-rotated", 3_000L),
                ),
        )
    }

    override suspend fun revokeIdentity(): RevokeRelayDeviceV2Response {
        revocations += 1
        return RevokeRelayDeviceV2Response(
            relayDeviceId = registeredRelayDeviceId,
            deviceId = localIdentity.deviceId,
            revokedAtEpochMillis = 1_500L,
        )
    }

    override suspend fun close() {
        closed = true
    }
}

private fun relayIdentity(seed: Int): DevicePublicIdentity {
    val rawKey = ByteArray(32) { index -> (seed + index).toByte() }
    val deviceId =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(rawKey),
        )
    return DevicePublicIdentity(
        deviceId = DeviceId(deviceId),
        signingPublicKey = rawKey,
        keyGeneration = 1L,
    )
}

private class FakeMonitorProvider : MonitorProvider {
    override suspend fun listMonitors(): List<MonitorInfo> =
        listOf(
            MonitorInfo(
                id = MonitorId("primary"),
                name = "Primary",
                width = 1920,
                height = 1080,
                primary = true,
            ),
        )
}

private class FakeCapabilityDetector(
    private val report: DesktopCapabilityReport,
) : DesktopCapabilityDetector {
    override fun detect(): DesktopCapabilityReport = report
}

private class FakeAutostartManager(
    initial: DesktopAutostartStatus,
) : DesktopAutostartManager {
    private var current = initial
    val requests = mutableListOf<Boolean>()

    override fun status(): DesktopAutostartStatus = current

    override fun setEnabled(enabled: Boolean): DesktopAutostartStatus {
        requests += enabled
        current = current.copy(enabled = enabled)
        return current
    }
}

private class PrefixTrustStorePayloadProtector : TrustStorePayloadProtector {
    override fun protect(plainText: String): String = "test-protected:" + plainText.reversed()

    override fun canUnprotect(payload: String): Boolean = payload.startsWith("test-protected:")

    override fun unprotect(payload: String): String = payload.removePrefix("test-protected:").reversed()
}

private class FailingTrustStorePayloadProtector : TrustStorePayloadProtector {
    override fun protect(plainText: String): String = plainText

    override fun canUnprotect(payload: String): Boolean = true

    override fun unprotect(payload: String): String = error("protector unavailable")
}

private class RecordingSecretToolRunner : SecretToolCommandRunner {
    val commands = mutableListOf<List<String>>()
    private val secrets = mutableMapOf<String, String>()

    override fun run(
        command: List<String>,
        stdin: String?,
    ): SecretToolCommandResult {
        commands += command
        val storeId = command.storeId()
        return when (command.getOrNull(1)) {
            "store" -> {
                secrets[storeId] = stdin.orEmpty()
                SecretToolCommandResult(0)
            }

            "lookup" -> {
                SecretToolCommandResult(0, stdout = secrets[storeId].orEmpty())
            }

            else -> {
                SecretToolCommandResult(1, stderr = "unsupported command")
            }
        }
    }

    private fun List<String>.storeId(): String {
        val index = indexOf("store-id")
        return if (index >= 0) get(index + 1) else ""
    }
}
