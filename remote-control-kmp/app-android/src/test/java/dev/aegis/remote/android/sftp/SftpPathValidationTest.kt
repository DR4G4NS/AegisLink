package dev.aegis.remote.android.sftp

import dev.aegis.remote.core.sftp.SftpPathStyle
import java.nio.file.Files
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SftpPathValidationTest {
    @Test
    fun `browser validation preserves legal posix filename data`() {
        assertEquals("home/user/a file.txt", requireSftpPath("home/user/./a file.txt"))
        assertEquals(" leading and trailing ", requireSftpPath(" leading and trailing "))
        assertEquals("folder/name\\with\\backslashes", requireSftpPath("folder/name\\with\\backslashes"))
        assertEquals("C:/ordinary-posix-name", requireSftpPath("C:/ordinary-posix-name"))
    }

    @Test
    fun `connected windows validation rejects drive and alternate separator escapes`() {
        assertEquals("home/user/file.txt", requireSftpPath("home/user/file.txt", SftpPathStyle.Windows))
        assertFailsWith<IllegalArgumentException> {
            requireSftpPath("C:/Windows/System32", SftpPathStyle.Windows)
        }
        assertFailsWith<IllegalArgumentException> {
            requireSftpPath("C:drive-relative", SftpPathStyle.Windows)
        }
        assertFailsWith<IllegalArgumentException> {
            requireSftpPath("inside\\alternate", SftpPathStyle.Windows)
        }
    }

    @Test
    fun `rejects empty oversized nul absolute and traversal paths`() {
        assertFailsWith<IllegalArgumentException> { requireSftpPath("") }
        assertFailsWith<IllegalArgumentException> { requireSftpPath("a".repeat(4_097)) }
        assertFailsWith<IllegalArgumentException> { requireSftpPath("bad\u0000path") }
        assertFailsWith<IllegalArgumentException> { requireSftpPath("/etc/passwd") }
        assertFailsWith<IllegalArgumentException> { requireSftpPath("../outside") }
    }

    @Test
    fun `upload staging preserves the selected path style`() {
        val posixPlan =
            createSftpUploadPlan(
                finalPath = "folder/C:\\ report ",
                nonce = "12345678",
            )
        assertEquals("folder/C:\\ report ", posixPlan.finalPath)
        assertEquals("folder/.aegis-upload-12345678.part", posixPlan.stagingPath)

        assertFailsWith<IllegalArgumentException> {
            createSftpUploadPlan(
                finalPath = "folder\\report.txt",
                nonce = "12345678",
                pathStyle = SftpPathStyle.Windows,
            )
        }
    }

    @Test
    fun `staged download size must exactly match remote size`() {
        verifyStagedDownloadSize(actualSize = 42L, expectedSize = 42L)
        assertFailsWith<IllegalStateException> {
            verifyStagedDownloadSize(actualSize = 41L, expectedSize = 42L)
        }
    }

    @Test
    fun `local staged publish is no-overwrite and removes staging only on success`() {
        val directory = Files.createTempDirectory("aegis-download-publish-test-")
        try {
            val staging = directory.resolve(".aegis-download-first.part")
            val target = directory.resolve("download.bin")
            val payload = "complete-download".encodeToByteArray()
            Files.write(staging, payload)

            publishStagedDownloadToFile(staging.toFile(), target.toFile())

            assertFalse(Files.exists(staging))
            assertContentEquals(payload, Files.readAllBytes(target))

            val secondStaging = directory.resolve(".aegis-download-second.part")
            Files.write(secondStaging, "replacement".encodeToByteArray())
            assertFailsWith<IllegalArgumentException> {
                publishStagedDownloadToFile(secondStaging.toFile(), target.toFile())
            }
            assertTrue(Files.exists(secondStaging))
            assertContentEquals(payload, Files.readAllBytes(target))
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
