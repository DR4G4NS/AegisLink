package dev.aegis.remote.desktop.agent

import kotlinx.coroutines.test.runTest
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxAegisOpenSshManagerTest {
    @Test
    fun `inspect creates an isolated public key only config and starts one owned foreground child`() =
        runTest {
            val root = Files.createTempDirectory("aegis-linux-sshd-test").resolve("openssh")
            val runner = RecordingLinuxOpenSshRunner()
            val launcher = RecordingLinuxSshdLauncher()
            val manager = testManager(root, runner, launcher)

            val first = manager.inspect()
            val second = manager.inspect()

            assertEquals("Running", first.serviceStatus)
            assertEquals(48_222, first.port)
            assertEquals("test_user", first.authorizedUser)
            assertEquals(false, first.firewallEnabled)
            assertEquals(first, second)
            assertEquals(1, launcher.commands.size)
            assertEquals(
                listOf(
                    "/usr/bin/sshd",
                    "-D",
                    "-e",
                    "-f",
                    root.resolve("sshd_config").toString(),
                ),
                launcher.commands.single(),
            )
            assertTrue(
                runner.commands.any { command ->
                    command.take(3) == listOf("/usr/bin/ssh-keygen", "-q", "-t")
                },
            )
            assertTrue(
                runner.commands.any { command ->
                    command == listOf("/usr/bin/sshd", "-t", "-f", root.resolve("sshd_config").toString())
                },
            )

            val config = Files.readString(root.resolve("sshd_config"))
            assertTrue(config.contains("Port 48222"))
            assertTrue(config.contains("AuthorizedKeysFile \"${root.resolve("authorized_keys")}\""))
            assertTrue(config.contains("AllowUsers test_user"))
            assertTrue(config.contains("AuthenticationMethods publickey"))
            assertTrue(config.contains("PasswordAuthentication no"))
            assertTrue(config.contains("KbdInteractiveAuthentication no"))
            assertTrue(config.contains("Subsystem sftp internal-sftp"))
            assertTrue(config.contains("AllowTcpForwarding no"))
            assertFalse(config.contains("/etc/ssh"))
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                Files.getPosixFilePermissions(root),
            )
            listOf("sshd_config", "authorized_keys", "ssh_host_ed25519_key", "ssh_host_ed25519_key.pub").forEach { name ->
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(root.resolve(name)),
                )
            }
        }

    @Test
    fun `inspect replaces a verified stale managed sshd before launching`() =
        runTest {
            val root = Files.createTempDirectory("aegis-linux-stale-sshd-test").resolve("openssh")
            val runner = RecordingLinuxOpenSshRunner()
            val launcher = RecordingLinuxSshdLauncher()
            val stale = RecordingLinuxManagedSshdProcess().apply { alive = true }
            val manager = testManager(root, runner, launcher) { _, _, _, _, _, _ -> stale }

            val state = manager.inspect()

            assertEquals("Running", state.serviceStatus)
            assertEquals(1, stale.terminateCalls)
            assertFalse(stale.alive)
            assertEquals(1, launcher.commands.size)
        }

    @Test
    fun `enrollment replaces only the exact tagged device record and returns canonical fingerprints`() =
        runTest {
            val root = Files.createTempDirectory("aegis-linux-keys-test").resolve("openssh")
            val runner = RecordingLinuxOpenSshRunner()
            val launcher = RecordingLinuxSshdLauncher()
            val manager = testManager(root, runner, launcher)
            manager.inspect()
            val authorizedKeys = root.resolve("authorized_keys")
            val unmanaged = "${publicKey("unmanaged")} local-key"
            val similarComment = "${publicKey("similar")} local-aegis-device:device-1"
            Files.writeString(authorizedKeys, "$unmanaged\n$similarComment\n", StandardCharsets.UTF_8)

            val first = manager.enrollAuthorizedKey(publicKey("client-a"), "device-1")
            val duplicate = manager.enrollAuthorizedKey(publicKey("client-a"), "device-1")
            val replacement = manager.enrollAuthorizedKey(publicKey("client-b"), "device-1")
            manager.enrollAuthorizedKey(publicKey("client-c"), "device-2")

            assertTrue(first.enrollment.added)
            assertFalse(duplicate.enrollment.added)
            assertTrue(replacement.enrollment.added)
            assertTrue(first.enrollment.fingerprint.matches(Regex("^SHA256:[A-Za-z0-9+/]{43}$")))
            assertTrue(first.hostKeyFingerprint.value.matches(Regex("^[A-Za-z0-9+/]{43}$")))

            val enrolled = Files.readAllLines(authorizedKeys)
            assertTrue(unmanaged in enrolled)
            assertTrue(similarComment in enrolled)
            assertFalse(enrolled.any { it.startsWith(publicKey("client-a")) })
            assertEquals(1, enrolled.count { it.substringAfterLast(' ') == "aegis-device:device-1" })
            assertEquals(1, enrolled.count { it.substringAfterLast(' ') == "aegis-device:device-2" })
            assertTrue(
                enrolled
                    .single { it.substringAfterLast(' ') == "aegis-device:device-1" }
                    .startsWith(publicKey("client-b")),
            )

            val removed = manager.removeAuthorizedKey("device-1")
            val missing = manager.removeAuthorizedKey("device-1")
            assertTrue(removed.removed)
            assertFalse(missing.removed)
            val afterRemoval = Files.readAllLines(authorizedKeys)
            assertTrue(unmanaged in afterRemoval)
            assertTrue(similarComment in afterRemoval)
            assertFalse(afterRemoval.any { it.substringAfterLast(' ') == "aegis-device:device-1" })
            assertTrue(afterRemoval.any { it.substringAfterLast(' ') == "aegis-device:device-2" })
        }

    @Test
    fun `real user scoped child passes loopback ssh command sftp hash and revocation smoke`() =
        runTest {
            val isolatedHome =
                Files.createTempDirectory(
                    Path.of(System.getProperty("user.home").orEmpty()),
                    ".aegis-linux-loopback-test",
                )
            val root = isolatedHome.resolve("openssh")
            val clientKey = root.resolve("qa-client")
            val knownHosts = root.resolve("known_hosts")
            val sshdPath = testExecutable("AEGIS_TEST_SSHD_PATH", "/usr/bin/sshd")
            val sshKeygenPath = testExecutable("AEGIS_TEST_SSH_KEYGEN_PATH", "/usr/bin/ssh-keygen")
            val sshKeyscanPath = testExecutable("AEGIS_TEST_SSH_KEYSCAN_PATH", "/usr/bin/ssh-keyscan")
            val sshPath = testExecutable("AEGIS_TEST_SSH_PATH", "/usr/bin/ssh")
            val sftpPath = testExecutable("AEGIS_TEST_SFTP_PATH", "/usr/bin/sftp")
            val upload = Files.createTempFile("aegis-loopback-upload", ".txt")
            Files.writeString(upload, "Aegis loopback UTF-8: áé漢字\n", StandardCharsets.UTF_8)
            val downloaded = root.resolve("qa-download.txt")
            val remoteFile = root.resolve("qa-upload.txt")
            val manager =
                LinuxAegisOpenSshManager(
                    rootDirectory = root,
                    authorizedUser = System.getProperty("user.name").orEmpty(),
                    port = 48_222,
                    sshdPath = sshdPath,
                    sshKeygenPath = sshKeygenPath,
                    startupGraceMillis = 3_000,
                )
            val fixture =
                LinuxLoopbackFixture(
                    root = root,
                    clientKey = clientKey,
                    knownHosts = knownHosts,
                    upload = upload,
                    downloaded = downloaded,
                    remoteFile = remoteFile,
                    manager = manager,
                    sshKeygenPath = sshKeygenPath,
                    sshKeyscanPath = sshKeyscanPath,
                    sshPath = sshPath,
                    sftpPath = sftpPath,
                )
            try {
                val state = manager.inspect()
                assertEquals("Running", state.serviceStatus)
                assertEquals(48_222, state.port)
                assertTrue(Files.isRegularFile(root.resolve("ssh_host_ed25519_key")))

                exerciseLoopback(fixture, state)
            } finally {
                manager.shutdown()
                manager.shutdown()
            }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var listening = true
            while (listening && System.nanoTime() < deadline) {
                listening = runCatching { Socket("127.0.0.1", 48_222).use { true } }.getOrDefault(false)
                if (listening) Thread.sleep(25)
            }
            assertFalse(listening)
            assertTrue(Files.notExists(root.resolve("sshd.pid")))
            listOf("sshd_config", "authorized_keys", "ssh_host_ed25519_key", "ssh_host_ed25519_key.pub").forEach { name ->
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(root.resolve(name)),
                )
            }
        }

    private suspend fun exerciseLoopback(
        fixture: LinuxLoopbackFixture,
        state: AegisOpenSshState,
    ) {
        val keygen =
            runExternal(
                listOf(
                    fixture.sshKeygenPath.toString(),
                    "-q",
                    "-t",
                    "ed25519",
                    "-N",
                    "",
                    "-f",
                    fixture.clientKey.toString(),
                ),
            )
        assertEquals(0, keygen.exitCode, keygen.output)

        val enrollment =
            fixture.manager.enrollAuthorizedKey(
                Files.readString(Path.of("${fixture.clientKey}.pub")),
                "qa-loopback",
            )
        assertEquals(state.hostKeyFingerprint.orEmpty().removePrefix("SHA256:"), enrollment.hostKeyFingerprint.value)

        waitForTcpPort("127.0.0.1", 48_222)
        val scan = scanHostKey(fixture.sshKeyscanPath, "127.0.0.1", 48_222)
        assertEquals(0, scan.exitCode, scan.output)
        Files.writeString(fixture.knownHosts, scan.output, StandardCharsets.UTF_8)
        val scannedFingerprint =
            runExternal(
                listOf(fixture.sshKeygenPath.toString(), "-lf", fixture.knownHosts.toString(), "-E", "sha256"),
            )
        assertEquals(0, scannedFingerprint.exitCode, scannedFingerprint.output)
        assertTrue(scannedFingerprint.output.contains(state.hostKeyFingerprint.orEmpty()), scannedFingerprint.output)

        val sshOptions =
            listOf(
                "-o",
                "BatchMode=yes",
                "-o",
                "StrictHostKeyChecking=yes",
                "-o",
                "GlobalKnownHostsFile=/dev/null",
                "-o",
                "UserKnownHostsFile=${fixture.knownHosts}",
                "-o",
                "IdentitiesOnly=yes",
                "-o",
                "PasswordAuthentication=no",
                "-o",
                "KbdInteractiveAuthentication=no",
                "-o",
                "ConnectTimeout=5",
                "-i",
                fixture.clientKey.toString(),
                "-p",
                "48222",
                "${state.authorizedUser}@127.0.0.1",
            )
        val command = runExternal(listOf(fixture.sshPath.toString()) + sshOptions + listOf("printf AEGIS_LOOPBACK"))
        assertEquals(0, command.exitCode, command.output)
        assertEquals("AEGIS_LOOPBACK", command.output.trim())

        val batch =
            "put ${fixture.upload} ${fixture.remoteFile}\n" +
                "get ${fixture.remoteFile} ${fixture.downloaded}\n"
        val sftpOptions =
            listOf(
                "-o",
                "BatchMode=yes",
                "-o",
                "StrictHostKeyChecking=yes",
                "-o",
                "GlobalKnownHostsFile=/dev/null",
                "-o",
                "UserKnownHostsFile=${fixture.knownHosts}",
                "-o",
                "IdentitiesOnly=yes",
                "-o",
                "ConnectTimeout=5",
                "-i",
                fixture.clientKey.toString(),
                "-P",
                "48222",
                "${state.authorizedUser}@127.0.0.1",
            )
        val sftp =
            runExternal(
                listOf(fixture.sftpPath.toString(), "-b", "-") + sftpOptions,
                batch.toByteArray(StandardCharsets.UTF_8),
            )
        assertEquals(0, sftp.exitCode, sftp.output)
        assertEquals(Files.readAllBytes(fixture.upload).toList(), Files.readAllBytes(fixture.downloaded).toList())
        assertEquals(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(fixture.upload)).toList(),
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(fixture.downloaded)).toList(),
        )

        val removal = fixture.manager.removeAuthorizedKey("qa-loopback")
        assertTrue(removal.removed)
        val revoked = runExternal(listOf(fixture.sshPath.toString()) + sshOptions + listOf("true"))
        assertTrue(revoked.exitCode != 0, revoked.output)
    }

    private data class LinuxLoopbackFixture(
        val root: Path,
        val clientKey: Path,
        val knownHosts: Path,
        val upload: Path,
        val downloaded: Path,
        val remoteFile: Path,
        val manager: LinuxAegisOpenSshManager,
        val sshKeygenPath: Path,
        val sshKeyscanPath: Path,
        val sshPath: Path,
        val sftpPath: Path,
    )

    private fun testExecutable(
        environmentName: String,
        fallback: String,
    ): Path =
        System
            .getenv(environmentName)
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: Path.of(fallback)

    @Test
    fun `shutdown terminates only the manager owned child and is idempotent`() =
        runTest {
            val root = Files.createTempDirectory("aegis-linux-stop-test").resolve("openssh")
            val launcher = RecordingLinuxSshdLauncher()
            val manager = testManager(root, RecordingLinuxOpenSshRunner(), launcher)
            manager.inspect()

            manager.shutdown()
            manager.shutdown()

            assertEquals(1, launcher.process.terminateCalls)
            assertFalse(launcher.process.alive)
        }

    @Test
    fun `invalid generated config fails before any long lived process is launched`() =
        runTest {
            val root = Files.createTempDirectory("aegis-linux-config-test").resolve("openssh")
            val runner = RecordingLinuxOpenSshRunner(failConfigValidation = true)
            val launcher = RecordingLinuxSshdLauncher()
            val manager = testManager(root, runner, launcher)

            val error = assertFailsWith<AegisOpenSshException> { manager.inspect() }

            assertEquals("linux-openssh", error.failure.component)
            assertEquals("validate-user-sshd-config", error.failure.stage)
            assertTrue(launcher.commands.isEmpty())
        }

    @Test
    fun `managed key files reject symbolic link redirection`() =
        runTest {
            val directory = Files.createTempDirectory("aegis-linux-symlink-test")
            val root = directory.resolve("openssh")
            Files.createDirectories(root)
            val outside = directory.resolve("outside-authorized-keys")
            Files.writeString(outside, "do-not-touch")
            Files.createSymbolicLink(root.resolve("authorized_keys"), outside)
            val manager = testManager(root, RecordingLinuxOpenSshRunner(), RecordingLinuxSshdLauncher())

            val error = assertFailsWith<IllegalStateException> { manager.inspect() }

            assertTrue(error.message.orEmpty().contains("symbolic link"))
            assertEquals("do-not-touch", Files.readString(outside))
        }
}

private data class ExternalCommandResult(
    val exitCode: Int,
    val output: String,
)

private fun runExternal(
    command: List<String>,
    stdin: ByteArray? = null,
): ExternalCommandResult {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    stdin?.let {
        process.outputStream.use { output -> output.write(it) }
    } ?: process.outputStream.close()
    if (!process.waitFor(15, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        error("Command timed out: ${command.firstOrNull()}")
    }
    return ExternalCommandResult(process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
}

private fun waitForTcpPort(
    host: String,
    port: Int,
    timeoutMillis: Long = 15_000,
) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (System.nanoTime() < deadline) {
        if (runCatching { Socket(host, port).use { true } }.getOrDefault(false)) {
            return
        }
        Thread.sleep(50)
    }
    error("TCP port $host:$port did not become reachable within ${timeoutMillis}ms")
}

private fun scanHostKey(
    sshKeyscanPath: Path,
    host: String,
    port: Int,
    maxAttempts: Int = 6,
): ExternalCommandResult {
    var lastResult = ExternalCommandResult(1, "")
    repeat(maxAttempts) { attempt ->
        lastResult =
            runExternal(
                listOf(
                    sshKeyscanPath.toString(),
                    "-T",
                    "5",
                    "-p",
                    port.toString(),
                    "-t",
                    "ed25519",
                    host,
                ),
            )
        if (lastResult.exitCode == 0 && lastResult.output.isNotBlank()) {
            return lastResult
        }
        if (attempt < maxAttempts - 1) {
            Thread.sleep(250L * (attempt + 1))
        }
    }
    return lastResult
}

private fun testManager(
    root: Path,
    runner: OpenSshCommandRunner,
    launcher: LinuxSshdProcessLauncher,
    managedProcessFinder: (Path, Path, Path, Path, Int, String) -> LinuxManagedSshdProcess? = { _, _, _, _, _, _ -> null },
): LinuxAegisOpenSshManager =
    LinuxAegisOpenSshManager(
        rootDirectory = root,
        authorizedUser = "test_user",
        sshdPath = Path.of("/usr/bin/sshd"),
        sshKeygenPath = Path.of("/usr/bin/ssh-keygen"),
        commandRunner = runner,
        processLauncher = launcher,
        managedProcessFinder = managedProcessFinder,
        executableCheck = { true },
        startupGraceMillis = 1,
        shutdownTimeoutMillis = 10,
    )

private class RecordingLinuxOpenSshRunner(
    private val failConfigValidation: Boolean = false,
) : OpenSshCommandRunner {
    val commands = mutableListOf<List<String>>()

    override suspend fun run(
        command: List<String>,
        timeoutMillis: Long,
    ): OpenSshCommandResult {
        commands += command
        if (command.firstOrNull() == "/usr/bin/ssh-keygen" && "-f" in command && "-y" !in command) {
            val keyPath = Path.of(command[command.indexOf("-f") + 1])
            Files.writeString(keyPath, "private-test-host-key")
            Files.writeString(Path.of("$keyPath.pub"), "${publicKey("host-key")} aegis-user-scoped-host\n")
        }
        if (command.firstOrNull() == "/usr/bin/sshd" && "-t" in command && failConfigValidation) {
            return OpenSshCommandResult(1, "", "bad generated config")
        }
        return OpenSshCommandResult(0, "", "")
    }
}

private class RecordingLinuxSshdLauncher : LinuxSshdProcessLauncher {
    val commands = mutableListOf<List<String>>()
    val process = RecordingLinuxManagedSshdProcess()

    override fun start(
        command: List<String>,
        workingDirectory: Path,
    ): LinuxManagedSshdProcess {
        commands += command
        process.alive = true
        return process
    }
}

private class RecordingLinuxManagedSshdProcess : LinuxManagedSshdProcess {
    var alive: Boolean = false
    var terminateCalls: Int = 0

    override fun isAlive(): Boolean = alive

    override fun waitForExit(timeoutMillis: Long): Boolean = !alive

    override fun terminate(timeoutMillis: Long) {
        terminateCalls += 1
        alive = false
    }

    override fun diagnostics(): String = ""
}

private fun publicKey(seed: String): String {
    val blob = Base64.getEncoder().encodeToString("ssh-ed25519:$seed".toByteArray(StandardCharsets.UTF_8))
    return "ssh-ed25519 $blob"
}
