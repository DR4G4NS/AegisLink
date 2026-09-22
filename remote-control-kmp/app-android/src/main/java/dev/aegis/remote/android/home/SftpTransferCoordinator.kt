package dev.aegis.remote.android.home

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.aegis.remote.core.model.FileEntry
import dev.aegis.remote.core.sftp.SftpSession
import dev.aegis.remote.core.sftp.SftpTransferCancellation
import dev.aegis.remote.core.sftp.TransferProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Owns SFTP transfer jobs and the cancellation boundary for one session. */
internal class SftpTransferCoordinator(
    private val state: kotlinx.coroutines.flow.StateFlow<AndroidHomeUiState>,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val contentResolver: ContentResolver,
    private val updateState: ((AndroidHomeUiState) -> AndroidHomeUiState) -> Unit,
    private val sessionProvider: () -> SftpSession?,
    private val isCurrentListing: (SftpSession, Long, Long, String) -> Boolean,
    private val connectionGeneration: () -> Long,
    private val navigationGeneration: () -> Long,
    private val normalizePath: (String, String) -> String?,
    private val childPath: (String, String, String) -> String?,
) {
    private var transferJob: Job? = null

    fun uploadFile() {
        val session = sessionProvider() ?: return
        val sftp = state.value.sftp
        val localPath = sftp.transferLocalPath.trim()
        if (localPath.isBlank()) {
            updateState { it.copy(sftp = it.sftp.copy(message = "Enter both local and remote paths before uploading.")) }
            return
        }
        val remotePath =
            normalizePath(
                sftp.transferRemotePath,
                "Enter both local and remote paths before uploading.",
            ) ?: return
        startTransfer("Uploading $localPath to $remotePath") { session.upload(localPath, remotePath) }
    }

    fun uploadPickedDocument(uri: String) = uploadPickedDocuments(listOf(uri))

    // Batch upload keeps validation, queueing, progress, and completion of one
    // user gesture in a single flow; splitting it would hide the ordering.
    @Suppress("CyclomaticComplexMethod")
    fun uploadPickedDocuments(uris: List<String>) {
        val unique = uris.map(String::trim).filter { it.isNotEmpty() }.distinct()
        if (unique.isEmpty()) return
        val session = sessionProvider() ?: return
        val currentSftp = state.value.sftp
        if (currentSftp.loading || currentSftp.operationBusy || currentSftp.transferBusy) return
        val uploads = unique.mapNotNull(::resolvePickedUpload)
        if (uploads.isEmpty()) return
        val path = currentSftp.path
        val currentConnectionGeneration = connectionGeneration()
        val currentNavigationGeneration = navigationGeneration()
        updateState {
            it.copy(
                sftp =
                    it.sftp.copy(
                        transferBusy = true,
                        transferProgressText = "0 bytes",
                        selectedEntry = null,
                        transferLocalPath = uploads.first().localUri,
                        transferRemotePath = uploads.first().remotePath,
                        message =
                            if (uploads.size == 1) {
                                "Uploading ${uploads.first().remotePath}"
                            } else {
                                "Uploading 1 of ${uploads.size} files..."
                            },
                    ),
            )
        }
        transferJob =
            scope.launch {
                try {
                    runCatching {
                        uploads.forEachIndexed { index, upload ->
                            val startedMessage =
                                if (uploads.size == 1) {
                                    "Uploading ${upload.remotePath}"
                                } else {
                                    "Uploading ${index + 1} of ${uploads.size}: ${upload.displayName}"
                                }
                            updateState {
                                it.copy(
                                    sftp =
                                        it.sftp.copy(
                                            transferLocalPath = upload.localUri,
                                            transferRemotePath = upload.remotePath,
                                            message = startedMessage,
                                        ),
                                )
                            }
                            collectTransferProgress(
                                session = session,
                                path = path,
                                connectionGeneration = currentConnectionGeneration,
                                navigationGeneration = currentNavigationGeneration,
                                startedMessage = startedMessage,
                                transfer = { session.upload(upload.localUri, upload.remotePath) },
                            )
                        }
                        if (isCurrentListing(session, currentConnectionGeneration, currentNavigationGeneration, path)) {
                            val refresh = runCatching { session.list(path) }
                            if (isCurrentListing(session, currentConnectionGeneration, currentNavigationGeneration, path)) {
                                updateState {
                                    it.copy(
                                        sftp =
                                            it.sftp.copy(
                                                transferBusy = false,
                                                entries = refresh.getOrNull() ?: it.sftp.entries,
                                                selectedEntry = null,
                                                message =
                                                    refresh.exceptionOrNull()?.let { error ->
                                                        "Transfer complete, but the folder could not refresh: ${error.message}"
                                                    } ?: if (uploads.size == 1) {
                                                        "Transfer complete."
                                                    } else {
                                                        "Uploaded ${uploads.size} files."
                                                    },
                                            ),
                                    )
                                }
                            }
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        if (isCurrentListing(session, currentConnectionGeneration, currentNavigationGeneration, path)) {
                            updateState { it.copy(sftp = it.sftp.copy(transferBusy = false, message = error.message ?: "Transfer failed")) }
                        }
                    }
                } finally {
                    if (currentConnectionGeneration == connectionGeneration()) transferJob = null
                }
            }
    }

    private fun resolvePickedUpload(uri: String): PickedSftpUpload? {
        val parsedUri =
            runCatching { Uri.parse(uri) }
                .onFailure { error ->
                    updateState { it.copy(sftp = it.sftp.copy(message = error.message ?: "The selected document is invalid.")) }
                }.getOrNull() ?: return null
        val displayName =
            runCatching {
                contentResolver
                    .query(parsedUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                    }
            }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: parsedUri.lastPathSegment?.substringAfterLast('/')
                ?: "upload"
        val safeName =
            displayName
                .replace('/', '_')
                .replace('\\', '_')
                .take(MAX_REMOTE_PICKED_FILE_NAME_LENGTH)
                .ifEmpty { "upload" }
        val remotePath =
            childPath(
                state.value.sftp.path,
                safeName,
                "The selected document has no usable name.",
            ) ?: return null
        return PickedSftpUpload(parsedUri.toString(), remotePath, safeName)
    }

    private data class PickedSftpUpload(
        val localUri: String,
        val remotePath: String,
        val displayName: String,
    )

    fun downloadFile() {
        val session = sessionProvider() ?: return
        val sftp = state.value.sftp
        val localPath = sftp.transferLocalPath.trim()
        if (localPath.isBlank()) {
            updateState { it.copy(sftp = it.sftp.copy(message = "Enter both remote and local paths before downloading.")) }
            return
        }
        val remotePath =
            normalizePath(
                sftp.transferRemotePath,
                "Enter both remote and local paths before downloading.",
            ) ?: return
        startTransfer("Downloading $remotePath to $localPath") { session.download(remotePath, localPath) }
    }

    fun downloadPickedDocument(
        uri: String,
        requestedRemotePath: String,
    ) {
        val remotePath =
            normalizePath(
                requestedRemotePath,
                "Select a regular file before choosing a download destination.",
            ) ?: return
        if (remotePath.isEmpty()) {
            updateState {
                it.copy(sftp = it.sftp.copy(message = "Select a regular file before choosing a download destination."))
            }
            return
        }
        updateState {
            it.copy(
                sftp = it.sftp.copy(transferLocalPath = uri, transferRemotePath = remotePath),
            )
        }
        downloadFile()
    }

    fun cancel(): Job? {
        val current = transferJob
        transferJob = null
        current?.cancel()
        return current
    }

    private fun startTransfer(
        startedMessage: String,
        transfer: () -> Flow<TransferProgress>,
    ) {
        val session = sessionProvider() ?: return
        val currentSftp = state.value.sftp
        if (currentSftp.loading || currentSftp.operationBusy || currentSftp.transferBusy) return
        val path = currentSftp.path
        val currentConnectionGeneration = connectionGeneration()
        val currentNavigationGeneration = navigationGeneration()
        updateState {
            it.copy(
                sftp =
                    it.sftp.copy(
                        transferBusy = true,
                        transferProgressText = "0 bytes",
                        message = startedMessage,
                    ),
            )
        }
        transferJob =
            scope.launch {
                runTransfer(
                    session = session,
                    path = path,
                    connectionGeneration = currentConnectionGeneration,
                    navigationGeneration = currentNavigationGeneration,
                    startedMessage = startedMessage,
                    transfer = transfer,
                )
            }
    }

    private suspend fun runTransfer(
        session: SftpSession,
        path: String,
        connectionGeneration: Long,
        navigationGeneration: Long,
        startedMessage: String,
        transfer: () -> Flow<TransferProgress>,
    ) {
        try {
            runCatching {
                collectTransferProgress(session, path, connectionGeneration, navigationGeneration, startedMessage, transfer)
                if (isCurrentListing(session, connectionGeneration, navigationGeneration, path)) {
                    val refresh = runCatching { session.list(path) }
                    if (isCurrentListing(session, connectionGeneration, navigationGeneration, path)) {
                        updateState {
                            it.copy(
                                sftp =
                                    it.sftp.copy(
                                        transferBusy = false,
                                        entries = refresh.getOrNull() ?: it.sftp.entries,
                                        selectedEntry = null,
                                        message =
                                            refresh.exceptionOrNull()?.let { error ->
                                                "Transfer complete, but the folder could not refresh: ${error.message}"
                                            } ?: "Transfer complete.",
                                    ),
                            )
                        }
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (isCurrentListing(session, connectionGeneration, navigationGeneration, path)) {
                    updateState { it.copy(sftp = it.sftp.copy(transferBusy = false, message = error.message ?: "Transfer failed")) }
                }
            }
        } finally {
            if (connectionGeneration == connectionGeneration()) transferJob = null
        }
    }

    private suspend fun collectTransferProgress(
        session: SftpSession,
        path: String,
        connectionGeneration: Long,
        navigationGeneration: Long,
        startedMessage: String,
        transfer: () -> Flow<TransferProgress>,
    ) {
        transfer().collect { progress ->
            if (!isCurrentListing(session, connectionGeneration, navigationGeneration, path)) return@collect
            updateState {
                it.copy(
                    sftp =
                        it.sftp.copy(
                            transferBusy = true,
                            transferProgressText = progress.label(),
                            message = if (progress.done) "Refreshing folder..." else startedMessage,
                        ),
                )
            }
        }
    }

    private fun TransferProgress.label(): String {
        val total = totalBytes
        val progress =
            if (total != null && total > 0) {
                val percent = (bytesTransferred * 100 / total).coerceIn(0, 100)
                "$bytesTransferred / $total bytes ($percent%)"
            } else {
                "$bytesTransferred bytes"
            }
        return if (done) "$progress done" else progress
    }

    private companion object {
        const val MAX_REMOTE_PICKED_FILE_NAME_LENGTH = 255
    }
}
