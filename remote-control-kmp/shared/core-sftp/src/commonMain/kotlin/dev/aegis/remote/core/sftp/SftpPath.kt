package dev.aegis.remote.core.sftp

/**
 * The path grammar exposed by an SFTP server.
 *
 * POSIX is the permissive browser default because backslash and drive-shaped names are ordinary
 * POSIX filenames. A connected adapter must derive the style from the canonical account home and
 * validate every operation again with that style before sending a path to the server.
 */
enum class SftpPathStyle {
    Posix,
    Windows,
}

object SftpPath {
    const val MAX_REMOTE_PATH_LENGTH: Int = 4_096

    /**
     * Lexically normalizes a path without trimming it. In particular, leading and trailing spaces
     * are filename data, and a backslash is filename data under [SftpPathStyle.Posix].
     */
    fun normalize(
        path: String,
        style: SftpPathStyle = SftpPathStyle.Posix,
    ): String {
        requireValidLengthAndCharacters(path)

        val slashPath =
            when (style) {
                SftpPathStyle.Posix -> path
                SftpPathStyle.Windows -> path.replace('\\', '/')
            }
        val absolute = slashPath.startsWith("/")
        val segments = ArrayDeque<String>()

        slashPath.split('/').forEach { segment ->
            when (segment) {
                "", "." -> {
                    Unit
                }

                ".." -> {
                    if (segments.isNotEmpty() && segments.last() != "..") {
                        segments.removeLast()
                    } else if (!absolute) {
                        segments.addLast(segment)
                    }
                }

                else -> {
                    segments.addLast(segment)
                }
            }
        }

        return when {
            absolute && segments.isEmpty() -> "/"
            absolute -> "/" + segments.joinToString("/")
            segments.isEmpty() -> "."
            else -> segments.joinToString("/")
        }
    }

    fun parent(
        path: String,
        style: SftpPathStyle = SftpPathStyle.Posix,
    ): String {
        val normalized = normalize(path, style)
        if (normalized == "." || normalized == "/") return "."

        val withoutTrailingSlash = normalized.trimEnd('/')
        val parent = withoutTrailingSlash.substringBeforeLast("/", missingDelimiterValue = ".")
        return when {
            normalized.startsWith("/") && parent.isEmpty() -> "/"
            parent.isEmpty() -> "."
            else -> parent
        }
    }

    fun child(
        directory: String,
        childName: String,
        style: SftpPathStyle = SftpPathStyle.Posix,
    ): String {
        require(childName.isNotEmpty()) { "Remote child name cannot be empty" }
        require('\u0000' !in childName) { "Remote child name cannot contain NUL characters" }
        require('/' !in childName) { "Remote child name cannot contain a path separator" }
        if (style == SftpPathStyle.Windows) {
            require('\\' !in childName) { "Remote child name cannot contain a Windows path separator" }
            require(!WINDOWS_DRIVE_PATH.matches(childName)) { "Remote child name cannot be drive-qualified" }
        }
        require(childName != "." && childName != "..") { "Remote child name cannot be a navigation segment" }

        return when (val base = normalize(directory, style)) {
            "." -> childName
            "/" -> "/$childName"
            else -> "$base/$childName"
        }
    }

    /**
     * Converts a browser path into the root-relative namespace accepted by an Aegis SFTP session.
     *
     * The browser-facing default is deliberately POSIX/permissive so server-returned POSIX names
     * round-trip exactly. A connected adapter must call this overload with the style derived from
     * its canonical account home. Windows sessions reject alternate separators and drive-qualified
     * inputs before a request reaches the server.
     */
    fun requireRootRelative(
        path: String,
        style: SftpPathStyle = SftpPathStyle.Posix,
    ): String {
        require(path.isNotEmpty()) { "SFT-7101: Remote path cannot be empty" }
        require(path.length <= MAX_REMOTE_PATH_LENGTH) { "SFT-7101: Remote path is too long" }
        require('\u0000' !in path) { "SFT-7101: Remote path cannot contain NUL characters" }
        require(!path.startsWith('/')) { "SFT-7102: Absolute paths are outside the authorized SFTP root" }
        if (style == SftpPathStyle.Windows) {
            require('\\' !in path) { "SFT-7102: Backslash is not accepted for a Windows SFTP session" }
            require(!WINDOWS_DRIVE_PATH.matches(path)) {
                "SFT-7102: Drive-qualified paths are outside the authorized SFTP root"
            }
        }

        val normalized = normalize(path, style)
        require(normalized != ".." && !normalized.startsWith("../")) {
            "SFT-7102: Parent traversal would escape the authorized SFTP root"
        }
        return normalized
    }

    /**
     * Derives the least-permissive path grammar justified by a canonical absolute home.
     *
     * Drive-rooted paths win over the POSIX leading-slash form because some Windows SFTP servers
     * return `/C:/...`. Ambiguous drive-shaped homes therefore fail closed as Windows.
     */
    fun canonicalPathStyle(canonicalPath: String): SftpPathStyle? {
        if (canonicalPath.isEmpty() ||
            canonicalPath.length > MAX_REMOTE_PATH_LENGTH ||
            '\u0000' in canonicalPath
        ) {
            return null
        }
        return when {
            WINDOWS_CANONICAL_PATH.matches(canonicalPath) -> SftpPathStyle.Windows
            canonicalPath.startsWith('/') -> SftpPathStyle.Posix
            else -> null
        }
    }

    /** Segment-aware containment for canonical paths returned by the SFTP server. */
    fun isWithinCanonicalRoot(
        canonicalRoot: String,
        canonicalCandidate: String,
        style: SftpPathStyle = canonicalPathStyle(canonicalRoot) ?: SftpPathStyle.Posix,
    ): Boolean {
        val root = canonicalForComparison(canonicalRoot, style)
        val candidate = canonicalForComparison(canonicalCandidate, style)
        val ignoreCase = style == SftpPathStyle.Windows
        if (candidate.equals(root, ignoreCase = ignoreCase)) return true
        val prefix = if (root == "/") "/" else "$root/"
        return candidate.startsWith(prefix, ignoreCase = ignoreCase)
    }

    /**
     * Maps a canonical server path back into the virtual root-relative namespace. The caller must
     * only pass paths returned by REALPATH after [isWithinCanonicalRoot] succeeds.
     */
    fun relativeToCanonicalRoot(
        canonicalRoot: String,
        canonicalCandidate: String,
        style: SftpPathStyle = canonicalPathStyle(canonicalRoot) ?: SftpPathStyle.Posix,
    ): String {
        val root = canonicalForComparison(canonicalRoot, style)
        val candidate = canonicalForComparison(canonicalCandidate, style)
        require(isWithinCanonicalRoot(root, candidate, style)) {
            "SFT-7102: Canonical path escaped the authorized SFTP root"
        }
        if (candidate.equals(root, ignoreCase = style == SftpPathStyle.Windows)) return "."
        return candidate.substring(root.length).trimStart('/').ifEmpty { "." }
    }

    private fun canonicalForComparison(
        path: String,
        style: SftpPathStyle,
    ): String {
        val normalized = normalize(path, style)
        return if (normalized == "/") normalized else normalized.trimEnd('/')
    }

    private fun requireValidLengthAndCharacters(path: String) {
        require(path.isNotEmpty()) { "Remote path cannot be empty" }
        require('\u0000' !in path) { "Remote path cannot contain NUL characters" }
        require(path.length <= MAX_REMOTE_PATH_LENGTH) { "Remote path is too long" }
    }

    private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:.*")
    private val WINDOWS_CANONICAL_PATH = Regex("^/?[A-Za-z]:[\\\\/].*")
}
