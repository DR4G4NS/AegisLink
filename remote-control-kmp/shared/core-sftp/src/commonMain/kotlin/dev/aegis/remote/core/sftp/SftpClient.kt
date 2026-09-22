package dev.aegis.remote.core.sftp

import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.FileEntry
import kotlinx.coroutines.flow.Flow

interface SftpClient {
    suspend fun connect(
        profile: DeviceProfile,
        routeHost: String,
    ): SftpSession
}

interface SftpSession {
    /** Capabilities are immutable for one authenticated SFTP subsystem. */
    val capabilities: SftpCapabilities

    suspend fun list(path: String): List<FileEntry>

    fun upload(
        localPath: String,
        remotePath: String,
    ): Flow<TransferProgress>

    fun download(
        remotePath: String,
        localPath: String,
    ): Flow<TransferProgress>

    suspend fun mkdir(path: String)

    suspend fun rename(
        from: String,
        to: String,
    )

    suspend fun delete(path: String)

    /**
     * Requests cancellation and waits until the physical transfer resources have been released.
     * Publication is a short non-cancellable boundary: if it already started, the completed file
     * wins and callers must not report that a partial transfer was cancelled.
     */
    suspend fun cancelTransfer(): SftpTransferCancellation

    suspend fun close()
}

data class TransferProgress(
    val bytesTransferred: Long,
    val totalBytes: Long?,
    val done: Boolean = false,
)

enum class SftpTransferCancellation {
    /** Cancellation won before publication and the transport was physically interrupted. */
    Cancelled,

    /** The transfer had already entered publication or teardown when cancellation arrived. */
    AlreadyFinishing,

    /** No transfer was registered by this session. */
    NoActiveTransfer,
}

data class SftpCapabilities(
    /** All public paths are relative to the canonical SSH account home. */
    val canonicalHomeConfinement: Boolean,
    /** Symlinks are visible in listings but cannot be traversed or used as transfer targets. */
    val symlinkTraversal: Boolean,
    /**
     * Resume stays disabled until Aegis can bind a partial file to a trusted size and digest.
     * SSHJ exposes offsets, but offsets alone cannot prove that both sides contain the same
     * prefix and would make silent corruption possible.
     */
    val uploadResume: TransferResumeCapability,
    val downloadResume: TransferResumeCapability,
)

sealed interface TransferResumeCapability {
    data object Supported : TransferResumeCapability

    data class Unsupported(
        val reasonCode: String,
        val reason: String,
    ) : TransferResumeCapability
}
