package dev.aegis.remote.core.sftp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SftpPathTest {
    @Test
    fun posixNormalizationPreservesFilenameData() {
        assertEquals("/home/user/docs", SftpPath.normalize("/home//user/./docs/"))
        assertEquals(" leading ", SftpPath.normalize(" leading "))
        assertEquals("workspace\\project\\..\\file.txt", SftpPath.normalize("workspace\\project\\..\\file.txt"))
        assertEquals("C:/ordinary-posix-name", SftpPath.normalize("C:/ordinary-posix-name"))
    }

    @Test
    fun windowsNormalizationRecognizesAlternateSeparators() {
        assertEquals(
            "workspace/file.txt",
            SftpPath.normalize("workspace\\project\\..\\file.txt", SftpPathStyle.Windows),
        )
    }

    @Test
    fun normalizeHandlesParentSegmentsWithoutEscapingAbsoluteRoot() {
        assertEquals("/etc", SftpPath.normalize("/home/../etc"))
        assertEquals("/", SftpPath.normalize("/../../"))
        assertEquals("../shared", SftpPath.normalize("../shared"))
    }

    @Test
    fun parentReturnsStableBrowserParentsWithoutTrimmingNames() {
        assertEquals(".", SftpPath.parent("."))
        assertEquals(".", SftpPath.parent("/"))
        assertEquals("/", SftpPath.parent("/home"))
        assertEquals("/home/user", SftpPath.parent("/home/user/docs"))
        assertEquals("project", SftpPath.parent("project/src"))
        assertEquals("folder", SftpPath.parent("folder/trailing "))
    }

    @Test
    fun posixChildRoundTripsSpacesBackslashesAndDriveShapedNames() {
        assertEquals("/home/user/file.txt", SftpPath.child("/home/user", "file.txt"))
        assertEquals(" leading and trailing ", SftpPath.child(".", " leading and trailing "))
        assertEquals("folder/name\\with\\backslashes", SftpPath.child("folder", "name\\with\\backslashes"))
        assertEquals("C:", SftpPath.child(".", "C:"))
        assertFailsWith<IllegalArgumentException> { SftpPath.child("/home", "../secret") }
        assertFailsWith<IllegalArgumentException> { SftpPath.child("/home", "..") }
        assertFailsWith<IllegalArgumentException> { SftpPath.child("/home", "nested/file.txt") }
    }

    @Test
    fun windowsChildRejectsAlternateSeparatorsAndDriveNames() {
        assertFailsWith<IllegalArgumentException> {
            SftpPath.child(".", "folder\\file.txt", SftpPathStyle.Windows)
        }
        assertFailsWith<IllegalArgumentException> {
            SftpPath.child(".", "C:", SftpPathStyle.Windows)
        }
    }

    @Test
    fun onlyEmptyOrNulPathsAreRejectedAsMissing() {
        assertEquals(" ", SftpPath.normalize(" "))
        assertFailsWith<IllegalArgumentException> { SftpPath.normalize("") }
        assertFailsWith<IllegalArgumentException> { SftpPath.normalize("bad\u0000path") }
    }

    @Test
    fun browserValidationUsesPermissivePosixSemantics() {
        assertEquals(".", SftpPath.requireRootRelative("./"))
        assertEquals("folder/file.txt", SftpPath.requireRootRelative("folder/./file.txt"))
        assertEquals(" leading ", SftpPath.requireRootRelative(" leading "))
        assertEquals("folder/name\\with\\backslashes", SftpPath.requireRootRelative("folder/name\\with\\backslashes"))
        assertEquals("C:/ordinary-posix-name", SftpPath.requireRootRelative("C:/ordinary-posix-name"))
        assertFailsWith<IllegalArgumentException> { SftpPath.requireRootRelative("../secret") }
        assertFailsWith<IllegalArgumentException> { SftpPath.requireRootRelative("safe/../../secret") }
        assertFailsWith<IllegalArgumentException> { SftpPath.requireRootRelative("/etc/passwd") }
    }

    @Test
    fun windowsSessionValidationRejectsDriveAndAlternateSeparatorEscapes() {
        assertEquals("folder/file.txt", SftpPath.requireRootRelative("folder/file.txt", SftpPathStyle.Windows))
        assertFailsWith<IllegalArgumentException> {
            SftpPath.requireRootRelative("C:/Windows/System32", SftpPathStyle.Windows)
        }
        assertFailsWith<IllegalArgumentException> {
            SftpPath.requireRootRelative("folder\\file.txt", SftpPathStyle.Windows)
        }
    }

    @Test
    fun canonicalStyleIsDerivedConservatively() {
        assertEquals(SftpPathStyle.Posix, SftpPath.canonicalPathStyle("/home/user"))
        assertEquals(SftpPathStyle.Windows, SftpPath.canonicalPathStyle("C:/Users/Alice"))
        assertEquals(SftpPathStyle.Windows, SftpPath.canonicalPathStyle("/C:/Users/Alice"))
        assertEquals(SftpPathStyle.Windows, SftpPath.canonicalPathStyle("C:\\Users\\Alice"))
        assertNull(SftpPath.canonicalPathStyle("relative/home"))
        assertNull(SftpPath.canonicalPathStyle(""))
    }

    @Test
    fun canonicalContainmentIsSegmentAwareAndStyleAware() {
        assertTrue(SftpPath.isWithinCanonicalRoot("/home/user", "/home/user/docs/file.txt"))
        assertFalse(SftpPath.isWithinCanonicalRoot("/home/user", "/home/user-escape/file.txt"))
        assertTrue(SftpPath.isWithinCanonicalRoot("/home/user", "/home/user/name\\with\\slashes"))
        assertFalse(SftpPath.isWithinCanonicalRoot("/home/user", "/home/user\\escape"))

        assertTrue(SftpPath.isWithinCanonicalRoot("C:/Users/Alice", "c:\\users\\alice\\Documents"))
        assertFalse(SftpPath.isWithinCanonicalRoot("C:/Users/Alice", "C:/Users/Alicia"))
        assertEquals(
            "Documents",
            SftpPath.relativeToCanonicalRoot("C:/Users/Alice", "c:\\users\\alice\\Documents"),
        )
    }
}
