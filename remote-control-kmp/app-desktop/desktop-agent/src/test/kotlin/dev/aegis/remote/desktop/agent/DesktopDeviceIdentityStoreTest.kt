package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.DeviceIdentityCanonicalEncoding
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import dev.aegis.remote.core.security.KeyRotationReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopDeviceIdentityStoreTest {
    @Test
    fun createsP256IdentityWithDeviceIdDerivedFromExactPublicKey() =
        runTest {
            val file = identityFile()
            val protector = InMemoryProtectedPrivateKeyStore()
            val identity = DesktopDeviceIdentityStore(file, protector).getOrCreate()

            assertEquals(IdentitySignatureAlgorithm.ECDSA_P256_SHA256, identity.publicIdentity.algorithm)
            assertEquals(1L, identity.publicIdentity.keyGeneration)
            assertTrue(identity.publicIdentity.publicKeySpki.size >= 80)
            assertEquals(
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(
                        DeviceIdentityCanonicalEncoding.deviceIdPreimage(
                            identity.publicIdentity.algorithm,
                            identity.publicIdentity.publicKeySpki,
                        ),
                    ),
                ),
                identity.publicIdentity.deviceId.value,
            )

            val payload = "relay registration transcript".encodeToByteArray()
            val publicKey =
                KeyFactory
                    .getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(identity.publicIdentity.publicKeySpki))
            val verified =
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey)
                    update(payload)
                    verify(identity.sign(payload))
                }
            assertTrue(verified)

            val persisted = Files.readString(file)
            assertFalse(persisted.contains(protector.lastPlaintextBase64Url.orEmpty()))
            assertTrue(persisted.contains("test-protected:v1"))

            val returnedPublicKey = identity.publicIdentity.publicKeySpki
            returnedPublicKey[0] = (returnedPublicKey[0].toInt() xor 0x01).toByte()
            assertEquals(
                identity.publicIdentity.deviceId.value,
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(
                        DeviceIdentityCanonicalEncoding.deviceIdPreimage(
                            identity.publicIdentity.algorithm,
                            identity.publicIdentity.publicKeySpki,
                        ),
                    ),
                ),
            )
        }

    @Test
    fun reopensTheSamePersistentIdentityWithoutWritingPlaintextPkcs8() =
        runTest {
            val file = identityFile()
            val protector = InMemoryProtectedPrivateKeyStore()
            val first = DesktopDeviceIdentityStore(file, protector).getOrCreate()
            val reopened = DesktopDeviceIdentityStore(file, protector).getOrCreate()

            assertEquals(first.publicIdentity.deviceId, reopened.publicIdentity.deviceId)
            assertEquals(first.publicIdentity.keyGeneration, reopened.publicIdentity.keyGeneration)
            assertContentEquals(first.publicIdentity.publicKeySpki, reopened.publicIdentity.publicKeySpki)
            assertEquals(1, protector.protectCalls)
            assertEquals(1, protector.unprotectCalls)
            assertFalse(Files.readString(file).contains(protector.lastPlaintextBase64Url.orEmpty()))
        }

    @Test
    fun confirmedTransactionalRotationRetainsOldProtectedIdentityUntilExplicitRelayCleanup() =
        runTest {
            val file = identityFile()
            val protector = InMemoryProtectedPrivateKeyStore()
            val store = DesktopDeviceIdentityStore(file, protector)
            val original = store.getOrCreate()

            val prepared = store.prepareRotation(KeyRotationReason.UserRequested)

            assertEquals(original.publicIdentity.deviceId, store.getOrCreate().publicIdentity.deviceId)
            assertEquals(2L, prepared.replacementIdentity.publicIdentity.keyGeneration)
            assertNotEquals(original.publicIdentity.deviceId, prepared.replacementIdentity.publicIdentity.deviceId)
            store.markRotationRemoteConfirmed(prepared.operationId)
            val rotated = store.commitRotation(prepared.operationId)

            assertEquals(2L, rotated.publicIdentity.keyGeneration)
            assertNotEquals(original.publicIdentity.deviceId, rotated.publicIdentity.deviceId)
            assertEquals(2, protector.protectCalls)
            assertEquals(0, protector.discardCalls)
            assertEquals(2, protector.entryCount)

            val retired = store.identityForKeyGeneration(1L)
            assertEquals(original.publicIdentity.deviceId, retired?.publicIdentity?.deviceId)
            val payload = "rotation proof".encodeToByteArray()
            val retiredPublicKey =
                KeyFactory
                    .getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(original.publicIdentity.publicKeySpki))
            assertTrue(
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(retiredPublicKey)
                    update(payload)
                    verify(retired!!.sign(payload))
                },
            )
        }

    @Test
    fun unconfirmedTransactionalRotationRollsBackWithoutChangingActiveIdentity() =
        runTest {
            val file = identityFile()
            val protector = InMemoryProtectedPrivateKeyStore()
            val store = DesktopDeviceIdentityStore(file, protector)
            val original = store.getOrCreate()
            val prepared = store.prepareRotation(KeyRotationReason.Recovery)

            store.rollbackRotation(prepared.operationId)

            val reopened = DesktopDeviceIdentityStore(file, protector).getOrCreate()
            assertEquals(original.publicIdentity.deviceId, reopened.publicIdentity.deviceId)
            assertEquals(1L, reopened.publicIdentity.keyGeneration)
            assertEquals(1, protector.discardCalls)
            assertEquals(1, protector.entryCount)
            assertEquals(null, store.pendingRotation())
        }

    @Test
    fun restartCommitsOnlyDurablyRemoteConfirmedRotation() =
        runTest {
            val file = identityFile()
            val protector = InMemoryProtectedPrivateKeyStore()
            val firstStore = DesktopDeviceIdentityStore(file, protector)
            val original = firstStore.getOrCreate()
            val prepared = firstStore.prepareRotation(KeyRotationReason.Scheduled)
            firstStore.markRotationRemoteConfirmed(prepared.operationId)

            val recovered = DesktopDeviceIdentityStore(file, protector).getOrCreate()

            assertEquals(2L, recovered.publicIdentity.keyGeneration)
            assertNotEquals(original.publicIdentity.deviceId, recovered.publicIdentity.deviceId)
            assertEquals(original.publicIdentity.deviceId, firstStore.identityForKeyGeneration(1L)?.publicIdentity?.deviceId)
            assertEquals(null, firstStore.pendingRotation())
        }

    @Test
    fun rotateFailsClosedWhenNoPriorIdentityExists() =
        runTest {
            val file = identityFile()
            val protector = InMemoryProtectedPrivateKeyStore()

            val failure =
                runCatching {
                    DesktopDeviceIdentityStore(file, protector).rotate(KeyRotationReason.UserRequested)
                }.exceptionOrNull()

            assertTrue(failure is DeviceIdentityStorageException)
            assertFalse(Files.exists(file))
            assertEquals(0, protector.protectCalls)
        }

    @Test
    fun concurrentStoreInstancesConvergeOnOnePersistentIdentity() =
        runTest {
            val file = identityFile()
            val protector = InMemoryProtectedPrivateKeyStore()
            val firstStore = DesktopDeviceIdentityStore(file, protector)
            val secondStore = DesktopDeviceIdentityStore(file, protector)

            val identities =
                listOf(
                    async(Dispatchers.Default) { firstStore.getOrCreate() },
                    async(Dispatchers.Default) { secondStore.getOrCreate() },
                ).awaitAll()

            assertEquals(identities[0].publicIdentity.deviceId, identities[1].publicIdentity.deviceId)
            assertEquals(1, protector.protectCalls)
        }

    @Test
    fun interruptedFirstWriteFailsClosedInsteadOfCreatingReplacementIdentity() =
        runTest {
            val file = identityFile()
            val protector = InMemoryProtectedPrivateKeyStore()
            Files.writeString(file.resolveSibling("${file.fileName}.pending"), "interrupted")

            val failure =
                runCatching {
                    DesktopDeviceIdentityStore(file, protector).getOrCreate()
                }.exceptionOrNull()

            assertTrue(failure is DeviceIdentityStorageException)
            assertEquals(0, protector.protectCalls)
            assertFalse(Files.exists(file))
        }

    @Test
    fun validMetadataRecoversSafelyFromStaleWriteMarker() =
        runTest {
            val file = identityFile()
            val protector = InMemoryProtectedPrivateKeyStore()
            val original = DesktopDeviceIdentityStore(file, protector).getOrCreate()
            val pending = file.resolveSibling("${file.fileName}.pending")
            Files.writeString(pending, "interrupted")

            val reopened = DesktopDeviceIdentityStore(file, protector).getOrCreate()

            assertEquals(original.publicIdentity.deviceId, reopened.publicIdentity.deviceId)
            assertEquals(1, protector.protectCalls)
            assertFalse(Files.exists(pending))
        }

    @Test
    fun missingMetadataAfterInitializationFailsClosedInsteadOfReplacingIdentity() =
        runTest {
            val file = identityFile()
            val protector = InMemoryProtectedPrivateKeyStore()
            DesktopDeviceIdentityStore(file, protector).getOrCreate()
            Files.delete(file)

            val failure =
                runCatching {
                    DesktopDeviceIdentityStore(file, protector).getOrCreate()
                }.exceptionOrNull()

            assertTrue(failure is DeviceIdentityStorageException)
            assertEquals(1, protector.protectCalls)
        }

    @Test
    fun invalidPersistedMetadataFailsClosedInsteadOfGeneratingReplacementIdentity() =
        runTest {
            val file = identityFile()
            val protector = InMemoryProtectedPrivateKeyStore()
            DesktopDeviceIdentityStore(file, protector).getOrCreate()
            Files.writeString(file, "{}")

            val failure =
                runCatching {
                    DesktopDeviceIdentityStore(file, protector).getOrCreate()
                }.exceptionOrNull()

            assertTrue(failure is DeviceIdentityStorageException)
            assertEquals(1, protector.protectCalls)
        }

    @Test
    fun linuxSecretServiceStoresOnlyReferenceOutsideIdentityMetadata() {
        val runner = InMemorySecretServiceRunner()
        val reference = UUID.randomUUID().toString()
        val protector =
            LinuxSecretServicePrivateKeyProtector(
                runner = runner,
                isAvailable = { true },
                referenceFactory = { reference },
            )
        val privateKey = ByteArray(24) { index -> (index + 1).toByte() }

        val protectedReference = protector.protect(privateKey)

        assertEquals("aegis-secret-service:v1:$reference", protectedReference.toString(StandardCharsets.US_ASCII))
        assertFalse(protectedReference.toString(StandardCharsets.US_ASCII).contains("pkcs8-private-material"))
        assertContentEquals(privateKey, protector.unprotect(protectedReference))
        assertTrue(runner.commands.any { it.take(2) == listOf("secret-tool", "store") })
        assertTrue(runner.commands.any { it.take(2) == listOf("secret-tool", "lookup") })
    }

    @Test
    fun linuxWithoutSecretServiceFailsBeforeAnyIdentityFileIsWritten() =
        runTest {
            val file = identityFile()
            val unavailableProtector = LinuxSecretServicePrivateKeyProtector(isAvailable = { false })

            val failure =
                runCatching {
                    DesktopDeviceIdentityStore(file, unavailableProtector).getOrCreate()
                }.exceptionOrNull()

            assertTrue(failure is DeviceIdentityStorageException)
            assertFalse(Files.exists(file))
        }

    @Test
    fun defaultLinuxSelectionNeverFallsBackToPlaintextWhenSecretServiceIsUnavailable() {
        val protector =
            defaultDesktopPrivateKeyProtector(
                osName = "Linux",
                linuxSecretServiceAvailable = { false },
            )

        val failure = runCatching { protector.protect("private".encodeToByteArray()) }.exceptionOrNull()

        assertTrue(failure is DeviceIdentityStorageException)
    }

    @Test
    fun defaultPrivateKeyProtectorSelectsDpapiOnWindowsAndSecretServiceOnLinux() {
        assertIs<WindowsDpapiPrivateKeyProtector>(
            defaultDesktopPrivateKeyProtector(
                osName = "Windows 11",
                linuxSecretServiceAvailable = { error("Windows must not probe Secret Service") },
            ),
        )
        assertIs<LinuxSecretServicePrivateKeyProtector>(
            defaultDesktopPrivateKeyProtector(
                osName = "Linux",
                linuxSecretServiceAvailable = { true },
            ),
        )
    }

    @Test
    fun windowsDpapiRoundTripsPrivateMaterialWhenRunningOnWindows() {
        if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) return

        val protector = WindowsDpapiPrivateKeyProtector()
        val plaintext = "desktop-private-key".encodeToByteArray()
        val protected = protector.protect(plaintext)

        assertFalse(protected.contentEquals(plaintext))
        assertContentEquals(plaintext, protector.unprotect(protected))
    }

    private fun identityFile(): Path =
        Files
            .createTempDirectory("aegis-desktop-identity-test")
            .resolve("desktop-device-identity.json")
}

private class InMemoryProtectedPrivateKeyStore : DesktopPrivateKeyProtector {
    override val scheme: String = "test-protected:v1"

    private val entries = mutableMapOf<String, ByteArray>()
    var protectCalls: Int = 0
        private set
    var unprotectCalls: Int = 0
        private set
    var discardCalls: Int = 0
        private set
    var lastPlaintextBase64Url: String? = null
        private set

    val entryCount: Int
        get() = entries.size

    override fun protect(pkcs8: ByteArray): ByteArray {
        protectCalls += 1
        lastPlaintextBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(pkcs8)
        val reference = "test-protected:v1:$protectCalls"
        entries[reference] = pkcs8.map { value -> (value.toInt() xor 0x5a).toByte() }.toByteArray()
        return reference.encodeToByteArray()
    }

    override fun unprotect(protectedValue: ByteArray): ByteArray {
        unprotectCalls += 1
        val reference = protectedValue.decodeToString()
        return entries.getValue(reference).map { value -> (value.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    override fun discard(protectedValue: ByteArray) {
        discardCalls += 1
        entries.remove(protectedValue.decodeToString())
    }
}

private class InMemorySecretServiceRunner : SecretServiceCommandRunner {
    val commands = mutableListOf<List<String>>()
    private val entries = mutableMapOf<String, ByteArray>()

    override fun run(
        command: List<String>,
        stdin: ByteArray?,
    ): SecretServiceCommandResult {
        commands += command
        val referenceIndex = command.indexOf("reference")
        val reference = command.getOrNull(referenceIndex + 1).orEmpty()
        return when (command.getOrNull(1)) {
            "store" -> {
                entries[reference] = stdin?.copyOf() ?: byteArrayOf()
                SecretServiceCommandResult(exitCode = 0)
            }

            "lookup" -> {
                SecretServiceCommandResult(exitCode = 0, stdout = entries[reference]?.copyOf() ?: byteArrayOf())
            }

            "clear" -> {
                entries.remove(reference)
                SecretServiceCommandResult(exitCode = 0)
            }

            else -> {
                SecretServiceCommandResult(exitCode = 1, stderr = "Unsupported test command")
            }
        }
    }
}
