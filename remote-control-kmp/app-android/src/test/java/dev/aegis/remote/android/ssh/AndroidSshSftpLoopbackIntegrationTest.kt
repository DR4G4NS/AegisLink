package dev.aegis.remote.android.ssh

import dev.aegis.remote.android.security.AndroidRemoteOperationException
import dev.aegis.remote.android.security.encodePrivateKeyCredentials
import dev.aegis.remote.android.security.openSshSha256Fingerprint
import dev.aegis.remote.android.sftp.AndroidSftpClient
import dev.aegis.remote.android.terminal.AndroidSshTerminalClient
import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.AuthMethod
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.HostAddress
import dev.aegis.remote.core.model.HostKeyFingerprint
import dev.aegis.remote.core.model.SshCredentialsRef
import dev.aegis.remote.core.security.SecureCredentialStore
import dev.aegis.remote.core.sftp.SftpTransferCancellation
import dev.aegis.remote.core.terminal.TerminalInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.server.shell.ShellFactory
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Base64
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AndroidSshSftpLoopbackIntegrationTest {
    private lateinit var tempDirectory: Path
    private lateinit var homeDirectory: Path
    private lateinit var server: SshServer
    private lateinit var profile: DeviceProfile
    private lateinit var clientKeyPair: KeyPair

    @BeforeTest
    fun startLoopbackServer() {
        tempDirectory = Files.createTempDirectory("aegis-ssh-loopback-")
        homeDirectory = Files.createDirectories(tempDirectory.resolve("home"))
        Files.write(homeDirectory.resolve("seed.txt"), "seed-data".encodeToByteArray())
        clientKeyPair =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2_048) }
                .generateKeyPair()

        val hostKeyProvider =
            SimpleGeneratorHostKeyProvider(tempDirectory.resolve("host-key.ser")).apply {
                algorithm = "RSA"
                keySize = 2_048
                setStrictFilePermissions(false)
            }
        val sftpFactory = SftpSubsystemFactory()
        sftpFactory.addSftpEventListener(
            object : SftpEventListener {
                override fun writing(
                    session: ServerSession,
                    remoteHandle: String,
                    localHandle: FileHandle,
                    offset: Long,
                    data: ByteArray,
                    dataOffset: Int,
                    dataLen: Int,
                ) {
                    Thread.sleep(SFTP_WRITE_DELAY_MILLIS)
                }
            },
        )

        server =
            SshServer.setUpDefaultServer().apply {
                host = LOOPBACK_HOST
                port = 0
                keyPairProvider = hostKeyProvider
                passwordAuthenticator =
                    PasswordAuthenticator { username, password, _ ->
                        username == TEST_USERNAME && password == TEST_PASSWORD
                    }
                publickeyAuthenticator =
                    PublickeyAuthenticator { username, key, _ ->
                        username == TEST_USERNAME && MessageDigest.isEqual(key.encoded, clientKeyPair.public.encoded)
                    }
                shellFactory = ShellFactory { EchoShellCommand() }
                subsystemFactories = listOf(sftpFactory)
                fileSystemFactory = VirtualFileSystemFactory(homeDirectory)
                start()
            }
        val boundPort = (server.boundAddresses.single() as InetSocketAddress).port
        val fingerprint = openSshSha256Fingerprint(hostKeyProvider.loadKeys(null).single().public)
        profile = profile(boundPort, fingerprint)
    }

    @AfterTest
    fun stopLoopbackServer() {
        runCatching { server.stop(true) }
        if (::tempDirectory.isInitialized && Files.exists(tempDirectory)) {
            deleteTempDirectoryWithRetry()
        }
    }

    private fun deleteTempDirectoryWithRetry() {
        repeat(TEMP_DELETE_ATTEMPTS - 1) {
            runCatching { deleteTempDirectory() }
            if (!Files.exists(tempDirectory)) return
            Thread.sleep(TEMP_DELETE_RETRY_MILLIS)
        }
        deleteTempDirectory()
    }

    private fun deleteTempDirectory() {
        Files.walk(tempDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `01 terminal authenticates with exact pin and carries interactive bytes`() =
        runBlocking<Unit> {
            val terminalClient = AndroidSshTerminalClient(FixedCredentialStore(TEST_PASSWORD.encodeToByteArray()))
            val session = terminalClient.connect(profile, LOOPBACK_HOST)
            val observed = CompletableDeferred<String>()
            val collector =
                launch(Dispatchers.Default) {
                    var text = ""
                    session.output.collect { chunk ->
                        text += chunk.bytes.toString(Charsets.UTF_8)
                        if (!observed.isCompleted && text.contains("echo:hello-aegis")) observed.complete(text)
                    }
                }
            try {
                session.send(TerminalInput("hello-aegis\r\n".encodeToByteArray()))

                assertContains(withTimeout(PROTOCOL_TIMEOUT_MILLIS) { observed.await() }, "echo:hello-aegis")
                session.resize(columns = 120, rows = 40)
            } finally {
                session.close()
                collector.cancelAndJoin()
            }
        }

    @Test
    fun `02 terminal rejects a different valid host pin and wrong password`() =
        runBlocking<Unit> {
            val correctCredentials = AndroidSshTerminalClient(FixedCredentialStore(TEST_PASSWORD.encodeToByteArray()))
            val hostKeyFailure =
                assertFailsWith<AndroidRemoteOperationException> {
                    correctCredentials.connect(
                        profile.copy(hostKeyFingerprint = HostKeyFingerprint("SHA256", "A".repeat(43))),
                        LOOPBACK_HOST,
                    )
                }
            assertEquals(AegisFailureCodes.SSH_HOST_KEY_CHANGED, hostKeyFailure.failure.code)

            val wrongCredentials = AndroidSshTerminalClient(FixedCredentialStore("wrong-password".encodeToByteArray()))
            val authenticationFailure =
                assertFailsWith<AndroidRemoteOperationException> {
                    wrongCredentials.connect(profile, LOOPBACK_HOST)
                }
            assertEquals(AegisFailureCodes.SSH_AUTHENTICATION_FAILED, authenticationFailure.failure.code)
        }

    @Test
    fun `03 sftp performs real CRUD and transfer progress`() =
        runBlocking<Unit> {
            val sftpClient = AndroidSftpClient(FixedCredentialStore(TEST_PASSWORD.encodeToByteArray()), null)
            val session = sftpClient.connect(profile, LOOPBACK_HOST)
            val localUpload = tempDirectory.resolve("upload.txt")
            val localDownload = tempDirectory.resolve("download.txt")
            Files.write(localUpload, "uploaded-through-sshj".encodeToByteArray())
            try {
                assertTrue(session.list(".").any { it.name == "seed.txt" })

                val uploadProgress = session.upload(localUpload.toString(), "upload.txt").first { it.done }
                assertTrue(uploadProgress.bytesTransferred > 0)
                assertEquals("uploaded-through-sshj", Files.readAllBytes(homeDirectory.resolve("upload.txt")).toString(Charsets.UTF_8))

                val completedDownload = session.download("upload.txt", localDownload.toString()).first { it.done }
                assertTrue(completedDownload.done)
                assertEquals("uploaded-through-sshj", Files.readAllBytes(localDownload).toString(Charsets.UTF_8))

                session.mkdir("folder")
                session.rename("folder", "renamed-folder")
                assertTrue(session.list(".").any { it.name == "renamed-folder" })
                session.delete("renamed-folder")

                val traversal = assertFailsWith<AndroidRemoteOperationException> { session.list("../outside") }
                assertEquals(AegisFailureCodes.SFTP_ROOT_ESCAPE_REJECTED, traversal.failure.code)
            } finally {
                session.close()
            }
        }

    @Test
    fun `04 sftp cancellation physically closes an active transfer`() =
        runBlocking<Unit> {
            val sftpClient = AndroidSftpClient(FixedCredentialStore(TEST_PASSWORD.encodeToByteArray()), null)
            val session = sftpClient.connect(profile, LOOPBACK_HOST)
            val slowUpload = tempDirectory.resolve("slow-upload.bin")
            writeTestPayload(slowUpload)
            try {
                val transferResult =
                    async(Dispatchers.IO) {
                        runCatching {
                            session.upload(slowUpload.toString(), "slow-upload.bin").collect()
                        }
                    }
                withTimeout(PROTOCOL_TIMEOUT_MILLIS) {
                    while (!homeDirectory.hasActiveUploadStagingFile()) delay(10)
                }

                assertEquals(SftpTransferCancellation.Cancelled, session.cancelTransfer())

                val result =
                    withContext(Dispatchers.Default.limitedParallelism(1)) {
                        withTimeout(PROTOCOL_TIMEOUT_MILLIS) { transferResult.await() }
                    }
                assertTrue(result.isFailure)
                assertTrue(!Files.exists(homeDirectory.resolve("slow-upload.bin")))
                val closedSession = assertFailsWith<AndroidRemoteOperationException> { session.list(".") }
                assertEquals(AegisFailureCodes.SFTP_SESSION_CLOSED, closedSession.failure.code)
            } finally {
                session.close()
            }
        }

    @Test
    fun `05 sftp authenticates with the enrolled private key and no password`() =
        runBlocking<Unit> {
            val privatePem =
                "-----BEGIN " +
                    "PRIVATE KEY-----\n" +
                    Base64.getMimeEncoder(64, "\n".encodeToByteArray()).encodeToString(clientKeyPair.private.encoded) +
                    "\n-----END " +
                    "PRIVATE KEY-----\n"
            val credential = encodePrivateKeyCredentials(privatePem, passphrase = null)
            val privateKeyProfile = profile.copy(authMethod = AuthMethod.PrivateKey)
            val session = AndroidSftpClient(FixedCredentialStore(credential), null).connect(privateKeyProfile, LOOPBACK_HOST)
            try {
                assertTrue(session.list(".").any { it.name == "seed.txt" })
            } finally {
                session.close()
                credential.fill(0)
            }
        }

    @Test
    fun `06 terminal reports a clean remote shell exit without reconnect semantics`() =
        runBlocking<Unit> {
            val session = AndroidSshTerminalClient(FixedCredentialStore(TEST_PASSWORD.encodeToByteArray())).connect(profile, LOOPBACK_HOST)
            val collector = launch(Dispatchers.IO) { session.output.collect { } }
            try {
                session.send(TerminalInput("exit\r\n".encodeToByteArray()))
                val exit = withTimeout(PROTOCOL_TIMEOUT_MILLIS) { session.awaitExit() }
                assertEquals(0, exit.status)
                assertTrue(!exit.closedByClient)
                assertTrue(!exit.transportFailure)
            } finally {
                session.close()
                collector.cancelAndJoin()
            }
        }

    private fun profile(
        port: Int,
        fingerprint: String,
    ) = DeviceProfile(
        id = DeviceProfileId("loopback-windows-profile"),
        displayName = "Loopback SSH fixture",
        localHost = HostAddress(LOOPBACK_HOST),
        sshPort = port,
        username = TEST_USERNAME,
        authMethod = AuthMethod.Password,
        credentialsRef = TEST_CREDENTIAL_REF,
        hostKeyFingerprint = HostKeyFingerprint("SHA256", fingerprint),
        permissions = DevicePermissions(terminal = true, sftp = true),
    )

    private fun writeTestPayload(path: Path) {
        val chunk = ByteArray(8 * 1_024) { (it % 251).toByte() }
        Files.newOutputStream(path).use { output ->
            repeat(512) { output.write(chunk) }
        }
    }

    private fun Path.hasActiveUploadStagingFile(): Boolean =
        Files.list(this).use { children ->
            children.anyMatch { child ->
                child.fileName.toString().startsWith(".aegis-upload-") &&
                    Files.isRegularFile(child) &&
                    Files.size(child) > 0L
            }
        }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val TEST_USERNAME = "aegis-test"
        const val TEST_PASSWORD = "test-only-password"
        const val SFTP_WRITE_DELAY_MILLIS = 15L
        const val PROTOCOL_TIMEOUT_MILLIS = 10_000L
        const val TEMP_DELETE_ATTEMPTS = 20
        const val TEMP_DELETE_RETRY_MILLIS = 50L
        val TEST_CREDENTIAL_REF = SshCredentialsRef("test:loopback")
    }
}

private class FixedCredentialStore(
    secret: ByteArray,
) : SecureCredentialStore {
    private val storedSecret = secret.copyOf()

    override suspend fun putSecret(
        label: String,
        secret: ByteArray,
    ): SshCredentialsRef = error("Test store is read-only")

    override suspend fun getSecret(ref: SshCredentialsRef): ByteArray? = if (ref.value == "test:loopback") storedSecret.copyOf() else null

    override suspend fun deleteSecret(ref: SshCredentialsRef) = Unit
}

private class EchoShellCommand : Command {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private lateinit var error: OutputStream
    private lateinit var exitCallback: ExitCallback
    private var thread: Thread? = null

    override fun setInputStream(inputStream: InputStream) {
        input = inputStream
    }

    override fun setOutputStream(outputStream: OutputStream) {
        output = outputStream
    }

    override fun setErrorStream(errorStream: OutputStream) {
        error = errorStream
    }

    override fun setExitCallback(callback: ExitCallback) {
        exitCallback = callback
    }

    override fun start(
        channel: ChannelSession,
        environment: Environment,
    ) {
        thread =
            Thread(
                {
                    try {
                        output.write("ready\r\n".encodeToByteArray())
                        output.flush()
                        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                            generateSequence(reader::readLine)
                                .takeWhile { line -> line != "exit" }
                                .forEach { line ->
                                    output.write("echo:$line\r\n".encodeToByteArray())
                                    output.flush()
                                }
                        }
                        exitCallback.onExit(0)
                    } catch (exception: Exception) {
                        runCatching {
                            error.write((exception.message ?: "echo shell stopped").encodeToByteArray())
                            error.flush()
                        }
                        exitCallback.onExit(1, exception.message ?: "echo shell stopped")
                    }
                },
                "aegis-loopback-echo-shell",
            ).apply {
                isDaemon = true
                start()
            }
    }

    override fun destroy(channel: ChannelSession) {
        runCatching { input.close() }
        thread?.interrupt()
        thread = null
    }
}
