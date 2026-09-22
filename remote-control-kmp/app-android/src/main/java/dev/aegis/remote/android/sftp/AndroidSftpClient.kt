package dev.aegis.remote.android.sftp

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.aegis.remote.android.security.authenticateProfile
import dev.aegis.remote.android.security.createPinnedSshConnection
import dev.aegis.remote.android.security.enableSshKeepAlive
import dev.aegis.remote.android.security.remoteOperationFailure
import dev.aegis.remote.android.security.sftpOperationFailure
import dev.aegis.remote.android.security.sshOperationFailure
import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.FailureCategory
import dev.aegis.remote.core.model.FileEntry
import dev.aegis.remote.core.model.FileEntryType
import dev.aegis.remote.core.security.SecureCredentialStore
import dev.aegis.remote.core.sftp.SftpCapabilities
import dev.aegis.remote.core.sftp.SftpClient
import dev.aegis.remote.core.sftp.SftpPath
import dev.aegis.remote.core.sftp.SftpPathStyle
import dev.aegis.remote.core.sftp.SftpSession
import dev.aegis.remote.core.sftp.SftpTransferCancellation
import dev.aegis.remote.core.sftp.TransferProgress
import dev.aegis.remote.core.sftp.TransferResumeCapability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.StreamCopier
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.xfer.FileSystemFile
import net.schmizz.sshj.xfer.LocalDestFile
import net.schmizz.sshj.xfer.LocalFileFilter
import net.schmizz.sshj.xfer.LocalSourceFile
import net.schmizz.sshj.xfer.TransferListener
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class AndroidSftpClient(
    private val credentialStore: SecureCredentialStore,
    private val contentResolver: ContentResolver?,
) : SftpClient {
    override suspend fun connect(
        profile: DeviceProfile,
        routeHost: String,
    ): SftpSession =
        withContext(Dispatchers.IO) {
            val expectedFingerprint =
                profile.hostKeyFingerprint
                    ?: throw remoteOperationFailure(
                        code = AegisFailureCodes.SSH_HOST_KEY_MISSING,
                        component = "android-sshj-sftp",
                        operation = "connect-sftp",
                        stage = "precondition",
                        category = FailureCategory.CONFIGURATION,
                        summary = "The SSH host key is not pinned for this profile",
                        retryable = false,
                        nextAction = "Pair the desktop host again; unpinned SFTP connections are never allowed.",
                    )

            val pinned =
                runCatching { createPinnedSshConnection(expectedFingerprint) }
                    .getOrElse { error -> throw sshOperationFailure("connect-sftp", "pin-validation", error) }
            val ssh = pinned.client
            try {
                try {
                    ssh.connect(routeHost, profile.sshPort)
                } catch (error: Throwable) {
                    throw sshOperationFailure("connect-sftp", "transport-connect", error, pinned.observation)
                }
                try {
                    ssh.authenticateProfile(profile, credentialStore)
                } catch (error: Throwable) {
                    throw sshOperationFailure("connect-sftp", "authentication", error, pinned.observation)
                }
                enableSshKeepAlive(ssh)
                val sftp =
                    try {
                        ssh.newSFTPClient()
                    } catch (error: Throwable) {
                        throw sftpOperationFailure("connect-sftp", "subsystem-open", error, retryable = true)
                    }
                val canonicalHome =
                    try {
                        sftp.canonicalize(".")
                    } catch (error: Throwable) {
                        runCatching { sftp.close() }
                        throw sftpOperationFailure(
                            operation = "connect-sftp",
                            stage = "canonical-home",
                            error = error,
                            code = AegisFailureCodes.SFTP_ROOT_DISCOVERY_FAILED,
                        )
                    }
                if (SftpPath.canonicalPathStyle(canonicalHome) == null) {
                    runCatching { sftp.close() }
                    throw remoteOperationFailure(
                        code = AegisFailureCodes.SFTP_ROOT_DISCOVERY_FAILED,
                        component = "android-sshj-sftp",
                        operation = "connect-sftp",
                        stage = "canonical-home",
                        category = FailureCategory.PROTOCOL,
                        summary = "The SFTP server returned a non-absolute canonical account home",
                        expected = "an absolute REALPATH result",
                        actual = canonicalHome.take(256),
                        retryable = false,
                        nextAction = "Repair the Aegis OpenSSH SFTP subsystem; root confinement cannot be bypassed.",
                    )
                }
                AndroidSftpSession(ssh, sftp, contentResolver, canonicalHome)
            } catch (error: Throwable) {
                runCatching { ssh.disconnect() }
                runCatching { ssh.close() }
                throw error
            }
        }
}

private class AndroidSftpSession(
    private val ssh: SSHClient,
    private val sftp: SFTPClient,
    private val contentResolver: ContentResolver?,
    canonicalHome: String,
) : SftpSession {
    private val rootGuard = CanonicalSftpRootGuard(canonicalHome)
    private val operationMutex = Mutex()
    private val transferCancelRequested = AtomicBoolean(false)
    private val activeTransferResource = AtomicReference<Closeable?>(null)
    private val activeTransfer = AtomicReference<ActiveSftpTransfer?>(null)
    private val closed = AtomicBoolean(false)

    override val capabilities: SftpCapabilities =
        SftpCapabilities(
            canonicalHomeConfinement = true,
            symlinkTraversal = false,
            uploadResume =
                TransferResumeCapability.Unsupported(
                    reasonCode = AegisFailureCodes.SFTP_RESUME_UNSUPPORTED,
                    reason = "SSHJ offsets do not prove that the local and remote partial-file prefixes match.",
                ),
            downloadResume =
                TransferResumeCapability.Unsupported(
                    reasonCode = AegisFailureCodes.SFTP_RESUME_UNSUPPORTED,
                    reason = "Resume is disabled until Aegis persists and verifies a trusted partial-file digest.",
                ),
        )

    override suspend fun list(path: String): List<FileEntry> =
        withContext(Dispatchers.IO) {
            try {
                operationMutex.withLock {
                    requireOpen("list-directory")
                    val resolved = resolveExisting(path, "list-directory", expectedType = FileMode.Type.DIRECTORY)
                    sftp
                        .ls(resolved.canonicalPath)
                        .filterNot { it.name == "." || it.name == ".." }
                        .map { it.toFileEntry(resolved.userPath, rootGuard.pathStyle) }
                        .sortedWith(compareBy<FileEntry> { it.type != FileEntryType.Directory }.thenBy { it.name.lowercase() })
                }
            } catch (error: Throwable) {
                throw sftpOperationFailure("list-directory", "list", error)
            }
        }

    override fun upload(
        localPath: String,
        remotePath: String,
    ): Flow<TransferProgress> =
        callbackFlow {
            require(localPath.isNotBlank()) { "SFT-7101: Local upload path is required" }
            val userRemotePath = validatedUserPath(remotePath, "upload-file")
            val sourceUri = localPath.toContentUriOrNull()
            val resolver = sourceUri?.let { requireNotNull(contentResolver) { "Android content resolver is unavailable" } }
            val localFile = localPath.takeIf { sourceUri == null }?.let(::File)
            require(sourceUri != null || localFile?.isFile == true) { "SFT-7101: Local upload source is not a readable file" }
            val total = sourceUri?.length(checkNotNull(resolver)) ?: localFile?.length()
            if (!operationMutex.tryLock()) {
                close(
                    remoteOperationFailure(
                        code = AegisFailureCodes.SFTP_CONCURRENT_OPERATION,
                        component = "android-sshj-sftp",
                        operation = "upload-file",
                        stage = "operation-lock",
                        category = FailureCategory.INVALID_STATE,
                        summary = "Another SFTP operation is already active",
                        retryable = true,
                    ),
                )
                return@callbackFlow
            }
            transferCancelRequested.set(false)
            val transfer = ActiveSftpTransfer()
            activeTransfer.set(transfer)
            val transferredBytes = AtomicLong(0)
            trySend(TransferProgress(bytesTransferred = 0, totalBytes = total, done = false))
            val listener =
                ProgressTransferListener { transferred, listenerTotal ->
                    transferredBytes.set(transferred)
                    trySend(
                        TransferProgress(
                            bytesTransferred = transferred,
                            totalBytes = listenerTotal.takeIf { it >= 0 } ?: total,
                            done = false,
                        ),
                    )
                }
            val job =
                launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                    var failure: Throwable? = null
                    var completion: TransferProgress? = null
                    try {
                        requireOpen("upload-file")
                        val uploadPlan =
                            createSftpUploadPlan(
                                finalPath = userRemotePath,
                                nonce = UUID.randomUUID().toString(),
                                pathStyle = rootGuard.pathStyle,
                            )
                        sftp.fileTransfer.transferListener = listener
                        val source =
                            if (sourceUri != null) {
                                ContentUriSourceFile(checkNotNull(resolver), sourceUri, total)
                            } else {
                                FileSystemFile(checkNotNull(localFile))
                            }
                        executeStagedUpload(
                            plan = uploadPlan,
                            resolveMissing = { path ->
                                resolveDestination(path, "upload-file", requireMissing = true).canonicalPath
                            },
                            transferToStaging = { stagingCanonicalPath ->
                                sftp.put(
                                    TrackedLocalSourceFile(source, ::trackUploadStream),
                                    stagingCanonicalPath,
                                )
                            },
                            verifyRegularFile = { path ->
                                verifyCreatedPath(path, "upload-file", FileMode.Type.REGULAR)
                            },
                            publish = { stagingCanonicalPath, finalCanonicalPath ->
                                check(transfer.beginCommit()) { "SFT-7105: Upload was cancelled before publication" }
                                sftp.rename(stagingCanonicalPath, finalCanonicalPath)
                            },
                            cleanupStaging = { stagingCanonicalPath ->
                                closeActiveTransferResource()
                                if (sftp.statExistence(stagingCanonicalPath) != null) {
                                    sftp.rm(stagingCanonicalPath)
                                }
                            },
                        )
                        transfer.markSucceeded()
                        completion =
                            TransferProgress(
                                bytesTransferred = total ?: transferredBytes.get(),
                                totalBytes = total,
                                done = true,
                            )
                    } catch (error: Throwable) {
                        failure =
                            sftpOperationFailure(
                                operation = "upload-file",
                                stage = "stream-upload",
                                error = error,
                                code =
                                    if (transferCancelRequested.get()) {
                                        AegisFailureCodes.SFTP_TRANSFER_CANCELLED
                                    } else {
                                        AegisFailureCodes.SFTP_TRANSFER_FAILED
                                    },
                                category =
                                    if (transferCancelRequested.get()) FailureCategory.INVALID_STATE else FailureCategory.PROTOCOL,
                                retryable = !transferCancelRequested.get(),
                            )
                        transfer.markFailed()
                        closeTransport(forceSocketClose = true)
                    } finally {
                        finishTransferTeardown(transfer)
                    }
                    completion?.let(::trySend)
                    failure?.let { close(it) } ?: close()
                }
            awaitClose {
                if (requestTransferCancellation(transfer)) job.cancel()
            }
        }

    override fun download(
        remotePath: String,
        localPath: String,
    ): Flow<TransferProgress> =
        callbackFlow {
            val userRemotePath = validatedUserPath(remotePath, "download-file")
            require(localPath.isNotBlank()) { "SFT-7101: Local download path is required" }
            val targetUri = localPath.toContentUriOrNull()
            val resolver = targetUri?.let { requireNotNull(contentResolver) { "Android content resolver is unavailable" } }
            if (!operationMutex.tryLock()) {
                close(
                    remoteOperationFailure(
                        code = AegisFailureCodes.SFTP_CONCURRENT_OPERATION,
                        component = "android-sshj-sftp",
                        operation = "download-file",
                        stage = "operation-lock",
                        category = FailureCategory.INVALID_STATE,
                        summary = "Another SFTP operation is already active",
                        retryable = true,
                    ),
                )
                return@callbackFlow
            }
            transferCancelRequested.set(false)
            val transfer = ActiveSftpTransfer()
            activeTransfer.set(transfer)
            var total: Long? = null
            val transferredBytes = AtomicLong(0)
            trySend(TransferProgress(bytesTransferred = 0, totalBytes = total, done = false))
            val listener =
                ProgressTransferListener { transferred, listenerTotal ->
                    transferredBytes.set(transferred)
                    trySend(
                        TransferProgress(
                            bytesTransferred = transferred,
                            totalBytes = listenerTotal.takeIf { it >= 0 } ?: total,
                            done = false,
                        ),
                    )
                }
            val job =
                launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                    var failure: Throwable? = null
                    var completion: TransferProgress? = null
                    var stagingFile: File? = null
                    try {
                        requireOpen("download-file")
                        val source = resolveExisting(userRemotePath, "download-file", expectedType = FileMode.Type.REGULAR)
                        total = sftp.size(source.canonicalPath)
                        trySend(TransferProgress(bytesTransferred = 0, totalBytes = total, done = false))
                        sftp.fileTransfer.transferListener = listener
                        val localStagingFile =
                            createDownloadStagingFile(
                                localTarget = localPath.takeIf { targetUri == null }?.let(::File),
                            )
                        stagingFile = localStagingFile
                        sftp.get(
                            source.canonicalPath,
                            TrackedLocalDestFile(FileSystemFile(localStagingFile), ::trackDownloadStream),
                        )
                        verifyStagedDownloadSize(
                            actualSize = localStagingFile.length(),
                            expectedSize = checkNotNull(total),
                        )
                        check(transfer.beginCommit()) { "SFT-7105: Download was cancelled before publication" }
                        if (targetUri != null) {
                            publishStagedDownloadToContentUri(
                                stagingFile = localStagingFile,
                                contentResolver = checkNotNull(resolver),
                                targetUri = targetUri,
                                expectedSize = checkNotNull(total),
                            )
                        } else {
                            publishStagedDownloadToFile(
                                stagingFile = localStagingFile,
                                targetFile = File(localPath),
                            )
                        }
                        transfer.markSucceeded()
                        completion =
                            TransferProgress(
                                bytesTransferred = total ?: transferredBytes.get(),
                                totalBytes = total,
                                done = true,
                            )
                    } catch (error: Throwable) {
                        failure =
                            sftpOperationFailure(
                                operation = "download-file",
                                stage = "stream-download",
                                error = error,
                                code =
                                    if (transferCancelRequested.get()) {
                                        AegisFailureCodes.SFTP_TRANSFER_CANCELLED
                                    } else {
                                        AegisFailureCodes.SFTP_TRANSFER_FAILED
                                    },
                                category =
                                    if (transferCancelRequested.get()) FailureCategory.INVALID_STATE else FailureCategory.PROTOCOL,
                                retryable = !transferCancelRequested.get(),
                            )
                        transfer.markFailed()
                        closeTransport(forceSocketClose = true)
                    } finally {
                        stagingFile?.let { runCatching { it.delete() } }
                        finishTransferTeardown(transfer)
                    }
                    completion?.let(::trySend)
                    failure?.let { close(it) } ?: close()
                }
            awaitClose {
                if (requestTransferCancellation(transfer)) job.cancel()
            }
        }

    override suspend fun mkdir(path: String) =
        withContext(Dispatchers.IO) {
            try {
                operationMutex.withLock {
                    requireOpen("create-directory")
                    val target = resolveDestination(path, "create-directory", requireMissing = true)
                    sftp.mkdir(target.canonicalPath)
                    verifyCreatedPath(target.userPath, "create-directory", FileMode.Type.DIRECTORY)
                }
            } catch (error: Throwable) {
                throw sftpOperationFailure("create-directory", "mkdir", error)
            }
        }

    override suspend fun rename(
        from: String,
        to: String,
    ) = withContext(Dispatchers.IO) {
        try {
            operationMutex.withLock {
                requireOpen("rename-path")
                val source =
                    resolveExisting(
                        path = from,
                        operation = "rename-path",
                        rejectRoot = true,
                        allowFinalSymlinkObject = true,
                    )
                val target = resolveDestination(to, "rename-path", requireMissing = true)
                sftp.rename(source.canonicalPath, target.canonicalPath)
                verifyCreatedPath(
                    userPath = target.userPath,
                    operation = "rename-path",
                    expectedType = source.type,
                    allowFinalSymlinkObject = source.type == FileMode.Type.SYMLINK,
                )
            }
        } catch (error: Throwable) {
            throw sftpOperationFailure("rename-path", "rename", error)
        }
    }

    override suspend fun delete(path: String) =
        withContext(Dispatchers.IO) {
            try {
                operationMutex.withLock {
                    requireOpen("delete-path")
                    val target =
                        resolveExisting(
                            path = path,
                            operation = "delete-path",
                            rejectRoot = true,
                            allowFinalSymlinkObject = true,
                        )
                    if (target.type == FileMode.Type.DIRECTORY) {
                        sftp.rmdir(target.canonicalPath)
                    } else {
                        sftp.rm(target.canonicalPath)
                    }
                }
            } catch (error: Throwable) {
                throw sftpOperationFailure("delete-path", "delete", error)
            }
        }

    override suspend fun cancelTransfer(): SftpTransferCancellation =
        withContext(NonCancellable + Dispatchers.IO) {
            val transfer =
                activeTransfer.get()
                    ?: return@withContext SftpTransferCancellation.NoActiveTransfer
            val cancellationWon = requestTransferCancellation(transfer)
            // Transfer activity can be cleared or the collecting Flow can begin cancellation
            // before SSHJ has released its local handle. The independently registered teardown
            // signal is completed only by the worker's final block.
            transfer.teardown.await()
            if (!cancellationWon) {
                // The publication boundary won the race. The transfer may have completed, but this
                // UI action still closes the dedicated session so it cannot be orphaned.
                closeTransport(forceSocketClose = true)
            }
            if (cancellationWon) {
                SftpTransferCancellation.Cancelled
            } else {
                SftpTransferCancellation.AlreadyFinishing
            }
        }

    override suspend fun close() =
        withContext(NonCancellable + Dispatchers.IO) {
            val transfer = activeTransfer.get()
            if (transfer != null) {
                requestTransferCancellation(transfer)
                transfer.teardown.await()
            }
            closeTransport()
            Unit
        }

    private fun requireOpen(operation: String) {
        if (closed.get()) {
            throw remoteOperationFailure(
                code = AegisFailureCodes.SFTP_SESSION_CLOSED,
                component = "android-sshj-sftp",
                operation = operation,
                stage = "precondition",
                category = FailureCategory.INVALID_STATE,
                summary = "The SFTP session is closed",
                retryable = false,
                nextAction = "Reconnect only after confirming that the previous transfer or session has terminated.",
            )
        }
    }

    private fun validatedUserPath(
        path: String,
        operation: String,
    ): String =
        try {
            requireSftpPath(path, rootGuard.pathStyle)
        } catch (error: IllegalArgumentException) {
            val rootEscape = error.message?.startsWith("SFT-7102") == true
            throw remoteOperationFailure(
                code =
                    if (rootEscape) {
                        AegisFailureCodes.SFTP_ROOT_ESCAPE_REJECTED
                    } else {
                        AegisFailureCodes.SFTP_PATH_REJECTED
                    },
                component = "android-sshj-sftp",
                operation = operation,
                stage = "path-validation",
                category = FailureCategory.AUTHORIZATION,
                summary =
                    if (rootEscape) {
                        "The remote path would escape the authorized SFTP root"
                    } else {
                        "The remote SFTP path is invalid"
                    },
                error = error,
                expected = "a root-relative path below the canonical SSH account home",
                actual = path.take(MAX_DIAGNOSTIC_PATH_LENGTH),
                retryable = false,
                nextAction = "Choose a path from the Aegis file browser; absolute and parent-traversal paths are blocked.",
            )
        }

    @Suppress("ThrowsCount")
    private fun resolveExisting(
        path: String,
        operation: String,
        expectedType: FileMode.Type? = null,
        rejectRoot: Boolean = false,
        allowFinalSymlinkObject: Boolean = false,
    ): ResolvedRemotePath {
        val userPath = validatedUserPath(path, operation)
        if (rejectRoot && userPath == ".") {
            throw remoteOperationFailure(
                code = AegisFailureCodes.SFTP_ROOT_ESCAPE_REJECTED,
                component = "android-sshj-sftp",
                operation = operation,
                stage = "root-protection",
                category = FailureCategory.AUTHORIZATION,
                summary = "The authorized SFTP root cannot be modified",
                retryable = false,
            )
        }

        var current = rootGuard.canonicalHome
        var type = sftp.lstat(current).type
        val segments = userPath.takeUnless { it == "." }?.split('/').orEmpty()
        segments.forEachIndexed { index, segment ->
            val isFinalSegment = index == segments.lastIndex
            current = rootGuard.childServerPath(current, segment)
            val attributes = sftp.lstat(current)
            if (attributes.type == FileMode.Type.SYMLINK) {
                if (!allowFinalSymlinkObject || !isFinalSegment) {
                    throw symlinkRejected(operation, userPath)
                }
                rootGuard.requireContained(current, operation)
                type = FileMode.Type.SYMLINK
                return@forEachIndexed
            }
            type = attributes.type
            val resolvedComponent = sftp.canonicalize(current)
            rootGuard.requireContained(resolvedComponent, operation)
            if (!rootGuard.sameCanonicalPath(current, resolvedComponent)) {
                // This also catches Windows junction/reparse traversal when a server reports
                // it as DIRECTORY rather than SYMLINK in v3 attributes.
                throw symlinkRejected(operation, userPath)
            }
            current = resolvedComponent
        }

        val canonical =
            if (type == FileMode.Type.SYMLINK && allowFinalSymlinkObject) {
                current
            } else {
                sftp.canonicalize(current)
            }
        rootGuard.requireContained(canonical, operation)
        if (expectedType != null && type != expectedType) {
            throw remoteOperationFailure(
                code = AegisFailureCodes.SFTP_PATH_REJECTED,
                component = "android-sshj-sftp",
                operation = operation,
                stage = "type-validation",
                category = FailureCategory.DATA,
                summary = "The SFTP path has an unexpected resource type",
                expected = expectedType.name,
                actual = type.name,
                retryable = false,
            )
        }
        return ResolvedRemotePath(userPath, canonical, type)
    }

    private fun symlinkRejected(
        operation: String,
        userPath: String,
    ) = remoteOperationFailure(
        code = AegisFailureCodes.SFTP_SYMLINK_REJECTED,
        component = "android-sshj-sftp",
        operation = operation,
        stage = "symlink-confinement",
        category = FailureCategory.AUTHORIZATION,
        summary = "A symlink or reparse point cannot be traversed or used as an SFTP target",
        actual = userPath.take(MAX_DIAGNOSTIC_PATH_LENGTH),
        retryable = false,
        nextAction = "Select a regular file or directory inside the paired account home.",
    )

    @Suppress("ThrowsCount")
    private fun resolveDestination(
        path: String,
        operation: String,
        requireMissing: Boolean,
    ): ResolvedRemotePath {
        val userPath = validatedUserPath(path, operation)
        if (userPath == ".") {
            throw remoteOperationFailure(
                code = AegisFailureCodes.SFTP_ROOT_ESCAPE_REJECTED,
                component = "android-sshj-sftp",
                operation = operation,
                stage = "root-protection",
                category = FailureCategory.AUTHORIZATION,
                summary = "The authorized SFTP root cannot be overwritten",
                retryable = false,
            )
        }
        val parentUserPath = SftpPath.parent(userPath, rootGuard.pathStyle)
        val parent = resolveExisting(parentUserPath, operation, expectedType = FileMode.Type.DIRECTORY)
        val name = userPath.substringAfterLast('/')
        val requested = rootGuard.childServerPath(parent.canonicalPath, name)
        val existing = sftp.lstatExistence(requested)
        if (existing != null) {
            if (requireMissing) {
                throw remoteOperationFailure(
                    code = AegisFailureCodes.SFTP_PATH_REJECTED,
                    component = "android-sshj-sftp",
                    operation = operation,
                    stage = "destination-validation",
                    category = FailureCategory.INVALID_STATE,
                    summary = "The SFTP destination already exists",
                    actual = userPath.take(MAX_DIAGNOSTIC_PATH_LENGTH),
                    retryable = false,
                )
            }
            val resolved = resolveExisting(userPath, operation)
            if (resolved.type != FileMode.Type.REGULAR) {
                throw remoteOperationFailure(
                    code = AegisFailureCodes.SFTP_PATH_REJECTED,
                    component = "android-sshj-sftp",
                    operation = operation,
                    stage = "destination-validation",
                    category = FailureCategory.DATA,
                    summary = "Only a regular file can be replaced by an upload",
                    expected = FileMode.Type.REGULAR.name,
                    actual = resolved.type.name,
                    retryable = false,
                )
            }
            return resolved
        }
        rootGuard.requireContained(parent.canonicalPath, operation)
        return ResolvedRemotePath(userPath, requested, FileMode.Type.UNKNOWN)
    }

    private fun verifyCreatedPath(
        userPath: String,
        operation: String,
        expectedType: FileMode.Type,
        allowFinalSymlinkObject: Boolean = false,
    ) {
        resolveExisting(
            path = userPath,
            operation = operation,
            expectedType = expectedType,
            allowFinalSymlinkObject = allowFinalSymlinkObject,
        )
    }

    private fun closeTransport(forceSocketClose: Boolean = false) {
        closeActiveTransferResource()
        if (!closed.compareAndSet(false, true)) return
        if (forceSocketClose) runCatching { ssh.socket.close() }
        runCatching { sftp.close() }
        runCatching { ssh.disconnect() }
        runCatching { ssh.close() }
    }

    private fun requestTransferCancellation(transfer: ActiveSftpTransfer): Boolean {
        if (!transfer.requestCancellation()) return false
        transferCancelRequested.set(true)
        closeActiveTransferResource()
        closeTransport(forceSocketClose = true)
        return true
    }

    private fun finishTransferTeardown(transfer: ActiveSftpTransfer) {
        try {
            closeActiveTransferResource()
            runCatching { sftp.fileTransfer.transferListener = null }
        } finally {
            operationMutex.unlock()
            activeTransfer.compareAndSet(transfer, null)
            transfer.teardown.complete(Unit)
        }
    }

    private fun trackUploadStream(stream: InputStream): InputStream = trackTransferResource(stream)

    private fun trackDownloadStream(stream: OutputStream): OutputStream = trackTransferResource(stream)

    private fun <T : Closeable> trackTransferResource(resource: T): T {
        activeTransferResource.getAndSet(resource)?.let { previous ->
            runCatching { previous.close() }
        }
        if (transferCancelRequested.get() || closed.get()) {
            if (activeTransferResource.compareAndSet(resource, null)) {
                runCatching { resource.close() }
            }
        }
        return resource
    }

    private fun closeActiveTransferResource() {
        activeTransferResource.getAndSet(null)?.let { resource ->
            runCatching { resource.close() }
        }
    }

    private companion object {
        const val MAX_DIAGNOSTIC_PATH_LENGTH = 256
    }
}

/**
 * Coordinates the cancellation/commit race without publishing a partially transferred file.
 *
 * Cancellation may own an active transfer, or publication may own it after [beginCommit].
 * Whichever transition wins is final; callers then wait on [teardown] for physical handle/socket
 * cleanup before reporting completion.
 */
private class ActiveSftpTransfer {
    private val state = AtomicReference(State.Active)
    val teardown = CompletableDeferred<Unit>()

    fun beginCommit(): Boolean = state.compareAndSet(State.Active, State.Committing)

    fun requestCancellation(): Boolean = state.compareAndSet(State.Active, State.Cancelled)

    fun markSucceeded() {
        state.compareAndSet(State.Committing, State.Succeeded)
    }

    fun markFailed() {
        state.updateAndGet { current ->
            when (current) {
                State.Succeeded -> current
                else -> State.Failed
            }
        }
    }

    private enum class State {
        Active,
        Committing,
        Cancelled,
        Succeeded,
        Failed,
    }
}

internal fun requireSftpPath(
    path: String,
    pathStyle: SftpPathStyle = SftpPathStyle.Posix,
): String = SftpPath.requireRootRelative(path, pathStyle)

internal data class SftpUploadPlan(
    val finalPath: String,
    val stagingPath: String,
)

/** Creates a collision-resistant staging sibling without exposing a partial final filename. */
internal fun createSftpUploadPlan(
    finalPath: String,
    nonce: String,
    pathStyle: SftpPathStyle = SftpPathStyle.Posix,
): SftpUploadPlan {
    val normalizedFinalPath = SftpPath.requireRootRelative(finalPath, pathStyle)
    require(normalizedFinalPath != ".") { "SFT-7101: Upload destination must name a file" }
    require(SFTP_STAGING_NONCE.matches(nonce)) { "SFT-7101: Invalid upload staging nonce" }

    val parent = SftpPath.parent(normalizedFinalPath, pathStyle)
    val stagingName = ".aegis-upload-$nonce.part"
    val stagingPath =
        SftpPath.requireRootRelative(
            SftpPath.child(parent, stagingName, pathStyle),
            pathStyle,
        )
    return SftpUploadPlan(finalPath = normalizedFinalPath, stagingPath = stagingPath)
}

/**
 * Publishes a fully transferred staging file only after checking the final destination twice.
 * SFTP v3 has no portable atomic no-replace primitive, so the second check narrows the commit
 * race while preserving fail-closed behavior for ordinary user-driven uploads.
 */
internal fun executeStagedUpload(
    plan: SftpUploadPlan,
    resolveMissing: (String) -> String,
    transferToStaging: (canonicalStagingPath: String) -> Unit,
    verifyRegularFile: (userPath: String) -> Unit,
    publish: (canonicalStagingPath: String, canonicalFinalPath: String) -> Unit,
    cleanupStaging: (canonicalStagingPath: String) -> Unit,
) {
    var stagingCanonicalPath: String? = null
    var published = false
    try {
        resolveMissing(plan.finalPath)
        val resolvedStagingCanonicalPath = resolveMissing(plan.stagingPath)
        stagingCanonicalPath = resolvedStagingCanonicalPath
        transferToStaging(resolvedStagingCanonicalPath)
        verifyRegularFile(plan.stagingPath)

        val finalCanonicalPath = resolveMissing(plan.finalPath)
        publish(resolvedStagingCanonicalPath, finalCanonicalPath)
        published = true
        verifyRegularFile(plan.finalPath)
    } finally {
        if (!published) {
            stagingCanonicalPath?.let { canonicalPath ->
                runCatching { cleanupStaging(canonicalPath) }
            }
        }
    }
}

internal fun verifyStagedDownloadSize(
    actualSize: Long,
    expectedSize: Long,
) {
    require(expectedSize >= 0L) { "SFT-7101: Remote download size cannot be negative" }
    check(actualSize == expectedSize) {
        "SFT-7105: Staged download size mismatch; expected $expectedSize bytes but received $actualSize"
    }
}

private fun createDownloadStagingFile(localTarget: File?): File {
    if (localTarget == null) {
        // ActivityThread points java.io.tmpdir at the app's internal cache directory. NIO also
        // creates the file with restrictive default permissions where the filesystem supports it.
        return Files.createTempFile(DOWNLOAD_STAGING_PREFIX, DOWNLOAD_STAGING_SUFFIX).toFile()
    }

    val absoluteTarget = localTarget.absoluteFile
    require(!absoluteTarget.exists()) { "SFT-7101: Local download destination already exists" }
    val parent = checkNotNull(absoluteTarget.parentFile) { "SFT-7101: Local download destination has no parent" }
    require(parent.isDirectory) { "SFT-7101: Local download destination parent is not a directory" }
    return Files.createTempFile(parent.toPath(), DOWNLOAD_STAGING_PREFIX, DOWNLOAD_STAGING_SUFFIX).toFile()
}

internal fun publishStagedDownloadToFile(
    stagingFile: File,
    targetFile: File,
) {
    check(stagingFile.isFile) { "SFT-7105: Download staging file is missing" }
    require(!targetFile.exists()) { "SFT-7101: Local download destination already exists" }
    // No REPLACE_EXISTING option: a destination created during the transfer still fails closed.
    Files.move(stagingFile.toPath(), targetFile.toPath())
}

private fun publishStagedDownloadToContentUri(
    stagingFile: File,
    contentResolver: ContentResolver,
    targetUri: Uri,
    expectedSize: Long,
) {
    check(stagingFile.isFile) { "SFT-7105: Download staging file is missing" }
    val publishedBytes =
        stagingFile.inputStream().use { input ->
            val output =
                contentResolver.openOutputStream(targetUri, "rwt")
                    ?: error("Unable to open selected Android document for download")
            output.use {
                val copied = input.copyTo(it)
                it.flush()
                copied
            }
        }
    check(publishedBytes == expectedSize) {
        "SFT-7105: Published download size mismatch; expected $expectedSize bytes but wrote $publishedBytes"
    }
}

private val SFTP_STAGING_NONCE = Regex("^[A-Za-z0-9-]{8,64}$")
private const val DOWNLOAD_STAGING_PREFIX = ".aegis-download-"
private const val DOWNLOAD_STAGING_SUFFIX = ".part"

private data class ResolvedRemotePath(
    val userPath: String,
    val canonicalPath: String,
    val type: FileMode.Type,
)

private class CanonicalSftpRootGuard(
    canonicalHome: String,
) {
    val pathStyle: SftpPathStyle =
        requireNotNull(SftpPath.canonicalPathStyle(canonicalHome)) {
            "SFT-7102: The canonical SFTP home does not have absolute path semantics"
        }
    val canonicalHome: String = SftpPath.normalize(canonicalHome, pathStyle)

    fun childServerPath(
        parent: String,
        childName: String,
    ): String {
        require(childName.isNotEmpty() && '/' !in childName)
        if (pathStyle == SftpPathStyle.Windows) require('\\' !in childName)
        return if (parent == "/") "/$childName" else "${parent.trimEnd('/')}/$childName"
    }

    fun requireContained(
        canonicalCandidate: String,
        operation: String,
    ) {
        if (!SftpPath.isWithinCanonicalRoot(canonicalHome, canonicalCandidate, pathStyle)) {
            throw remoteOperationFailure(
                code = AegisFailureCodes.SFTP_ROOT_ESCAPE_REJECTED,
                component = "android-sshj-sftp",
                operation = operation,
                stage = "canonical-confinement",
                category = FailureCategory.AUTHORIZATION,
                summary = "The server-resolved path escaped the authorized SFTP root",
                expected = "a canonical descendant of the paired account home",
                actual = canonicalCandidate.take(256),
                retryable = false,
                nextAction = "Do not follow this path. Inspect symlinks and repair the account-home permissions.",
            )
        }
    }

    fun sameCanonicalPath(
        first: String,
        second: String,
    ): Boolean {
        val normalizedFirst = SftpPath.normalize(first, pathStyle).trimEnd('/').ifEmpty { "/" }
        val normalizedSecond = SftpPath.normalize(second, pathStyle).trimEnd('/').ifEmpty { "/" }
        return normalizedFirst.equals(normalizedSecond, ignoreCase = pathStyle == SftpPathStyle.Windows)
    }
}

private class ProgressTransferListener(
    private val onProgress: (transferred: Long, total: Long) -> Unit,
) : TransferListener {
    override fun directory(name: String): TransferListener = this

    override fun file(
        name: String,
        size: Long,
    ): StreamCopier.Listener =
        StreamCopier.Listener { transferred ->
            onProgress(transferred, size)
        }
}

private class ContentUriSourceFile(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val length: Long?,
) : LocalSourceFile {
    override fun getName(): String = uri.displayName(contentResolver) ?: uri.lastPathSegment ?: "android-upload"

    override fun getLength(): Long = length ?: -1L

    override fun getInputStream(): InputStream = contentResolver.openInputStream(uri) ?: error("Unable to open selected Android document for upload")

    override fun getPermissions(): Int = 0b110_100_100

    override fun isFile(): Boolean = true

    override fun isDirectory(): Boolean = false

    override fun getChildren(filter: LocalFileFilter): Iterable<LocalSourceFile> = emptyList()

    override fun providesAtimeMtime(): Boolean = false

    override fun getLastAccessTime(): Long = 0L

    override fun getLastModifiedTime(): Long = 0L
}

private class TrackedLocalSourceFile(
    private val delegate: LocalSourceFile,
    private val track: (InputStream) -> InputStream,
) : LocalSourceFile by delegate {
    override fun getInputStream(): InputStream = track(delegate.inputStream)
}

private class TrackedLocalDestFile(
    private val delegate: LocalDestFile,
    private val track: (OutputStream) -> OutputStream,
) : LocalDestFile by delegate {
    override fun getOutputStream(): OutputStream = track(delegate.outputStream)

    override fun getOutputStream(append: Boolean): OutputStream = track(delegate.getOutputStream(append))

    override fun getChild(name: String): LocalDestFile = TrackedLocalDestFile(delegate.getChild(name), track)

    override fun getTargetFile(filename: String): LocalDestFile = TrackedLocalDestFile(delegate.getTargetFile(filename), track)

    override fun getTargetDirectory(dirname: String): LocalDestFile = TrackedLocalDestFile(delegate.getTargetDirectory(dirname), track)
}

private fun RemoteResourceInfo.toFileEntry(
    parentUserPath: String,
    pathStyle: SftpPathStyle,
): FileEntry {
    val attrs = attributes
    val type =
        when (attrs.type) {
            FileMode.Type.DIRECTORY -> FileEntryType.Directory
            FileMode.Type.REGULAR -> FileEntryType.File
            FileMode.Type.SYMLINK -> FileEntryType.Symlink
            else -> FileEntryType.Other
        }
    return FileEntry(
        name = name,
        path = SftpPath.child(parentUserPath, name, pathStyle),
        type = type,
        size = runCatching { attrs.size }.getOrDefault(0L),
        modifiedAtEpochMillis = runCatching { attrs.mtime * 1000L }.getOrNull(),
        permissions = runCatching { attrs.permissions.joinToString(",") }.getOrNull(),
    )
}

private fun String.toContentUriOrNull(): Uri? = takeIf { it.startsWith("content://") }?.let(Uri::parse)

private fun Uri.length(contentResolver: ContentResolver): Long? = queryMetadata(contentResolver, OpenableColumns.SIZE)?.toLongOrNull()

private fun Uri.displayName(contentResolver: ContentResolver): String? = queryMetadata(contentResolver, OpenableColumns.DISPLAY_NAME)

private fun Uri.queryMetadata(
    contentResolver: ContentResolver,
    column: String,
): String? {
    return contentResolver.query(this, arrayOf(column), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(column).takeIf { it >= 0 } ?: return@use null
        if (cursor.moveToFirst() && !cursor.isNull(index)) cursor.getString(index) else null
    }
}

private fun SFTPClient.lstatExistence(path: String): FileAttributes? =
    try {
        lstat(path)
    } catch (error: SFTPException) {
        if (error.statusCode == Response.StatusCode.NO_SUCH_FILE) {
            null
        } else {
            throw error
        }
    }
