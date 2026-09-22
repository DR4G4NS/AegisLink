package dev.aegis.remote.android.home

import android.content.ContentResolver
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.FileEntry
import dev.aegis.remote.core.model.FileEntryType
import dev.aegis.remote.core.model.WakeOnLanCapability
import dev.aegis.remote.core.session.SshRoutePlan
import dev.aegis.remote.core.sftp.SftpClient
import dev.aegis.remote.core.sftp.SftpPath
import dev.aegis.remote.core.sftp.SftpSession
import dev.aegis.remote.core.sftp.SftpTransferCancellation
import dev.aegis.remote.core.sftp.TransferResumeCapability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Owns one SFTP session, navigation/connection generations and transfer
 * cancellation. Late listings are discarded when any generation changes.
 */
internal class SftpCoordinator(
    private val state: StateFlow<AndroidHomeUiState>,
    private val scope: CoroutineScope,
    private val cleanupScope: CoroutineScope,
    private val sftpClient: SftpClient,
    private val contentResolver: ContentResolver,
    private val resolveRoute: suspend (DeviceProfile) -> SshRoutePlan,
    private val onProfileConnected: suspend (DeviceProfileId, dev.aegis.remote.core.model.ConnectionRouteType) -> Unit,
    private val onSessionStarted: () -> Unit,
    private val onSessionIdle: () -> Unit,
    private val updateState: ((AndroidHomeUiState) -> AndroidHomeUiState) -> Unit,
) {
    private var activeSftpSession: SftpSession? = null
    private var sftpConnectJob: Job? = null
    private var sftpOperationJob: Job? = null
    private var sftpNavigationJob: Job? = null
    private var sftpConnectionGenerationState: Long = 0
    private var sftpNavigationGenerationState: Long = 0

    private val transferCoordinator: SftpTransferCoordinator by lazy {
        SftpTransferCoordinator(
            state = state,
            scope = scope,
            contentResolver = contentResolver,
            updateState = updateState,
            sessionProvider = { activeSftpSession },
            isCurrentListing = ::isCurrentSftpListing,
            connectionGeneration = { sftpConnectionGenerationState },
            navigationGeneration = { sftpNavigationGenerationState },
            normalizePath = ::normalizeSftpPathOrReport,
            childPath = ::childSftpPathOrReport,
        )
    }

    val hasActiveSession: Boolean
        get() = activeSftpSession != null

    fun start() {
        val current = state.value
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId } ?: return
        if (!profile.permissions.sftp) {
            updateState { it.copy(errorMessage = "SFTP access is not permitted for this profile.") }
            return
        }
        val connectionGeneration = ++sftpConnectionGenerationState
        sftpNavigationGenerationState += 1
        sftpConnectJob?.cancel()
        sftpNavigationJob?.cancel()
        sftpOperationJob?.cancel()
        transferCoordinator.cancel()
        val previousSession = activeSftpSession.also { activeSftpSession = null }
        previousSession?.let { stale ->
            cleanupScope.launch { runCatching { stale.close() } }
        }
        updateState {
            it.copy(
                screen = AndroidHomeScreenMode.Sftp,
                sftp = SftpUiState(path = ".", loading = true, message = "Resolving SSH route..."),
                errorMessage = null,
            )
        }
        sftpConnectJob = scope.launch { connectSftpSession(profile, connectionGeneration) }
    }

    private suspend fun connectSftpSession(
        profile: DeviceProfile,
        connectionGeneration: Long,
    ) {
        var pendingSession: SftpSession? = null
        try {
            runCatching {
                val route = resolveRoute(profile)
                val routeProfile = profile.withSshRoutePort(route)
                if (isCurrentSftpConnection(connectionGeneration, profile.id)) {
                    updateState {
                        it.copy(
                            sftp =
                                it.sftp.copy(
                                    message = "Connecting via ${route.host.host}:${routeProfile.sshPort} (${route.route.description})...",
                                ),
                        )
                    }
                    val session = sftpClient.connect(routeProfile, route.host.host)
                    pendingSession = session
                    if (isCurrentSftpConnection(connectionGeneration, profile.id)) {
                        activeSftpSession = session
                        onProfileConnected(profile.id, route.route.type)
                        onSessionStarted()
                        val entries = session.list(".")
                        if (isCurrentSftpSession(session, connectionGeneration, profile.id)) {
                            pendingSession = null
                            val capabilityNotice = session.capabilities.downloadResume.capabilityNotice()
                            updateState {
                                it.copy(
                                    sftp =
                                        it.sftp.copy(
                                            loading = false,
                                            connected = true,
                                            entries = entries,
                                            capabilityNotice = capabilityNotice,
                                            message = "Connected to the account-home SFTP root.",
                                        ),
                                )
                            }
                        } else {
                            if (activeSftpSession === session) activeSftpSession = null
                            session.close()
                        }
                    } else {
                        session.close()
                    }
                }
            }.onFailure { error ->
                pendingSession?.let { runCatching { it.close() } }
                if (error is CancellationException) throw error
                if (connectionGeneration == sftpConnectionGenerationState && state.value.screen == AndroidHomeScreenMode.Sftp) {
                    if (activeSftpSession === pendingSession) activeSftpSession = null
                    updateState {
                        it.copy(
                            sftp = it.sftp.copy(loading = false, connected = false, message = error.message ?: "SFTP failed"),
                            errorMessage = error.message,
                        )
                    }
                }
            }
        } finally {
            if (connectionGeneration == sftpConnectionGenerationState) sftpConnectJob = null
        }
    }

    private fun isCurrentSftpConnection(
        generation: Long,
        profileId: DeviceProfileId,
    ): Boolean =
        generation == sftpConnectionGenerationState &&
            state.value.screen == AndroidHomeScreenMode.Sftp &&
            state.value.selectedProfileId == profileId

    fun refresh() {
        navigate(state.value.sftp.path, refresh = true)
    }

    fun openEntry(entry: dev.aegis.remote.core.model.FileEntry) {
        val currentSftp = state.value.sftp
        if (currentSftp.loading || currentSftp.operationBusy || currentSftp.transferBusy) return
        if (entry.type != dev.aegis.remote.core.model.FileEntryType.Directory) {
            updateState {
                it.copy(
                    sftp =
                        it.sftp.copy(
                            transferRemotePath = entry.path,
                            operationPath = entry.path,
                            selectedEntry = entry,
                            message = null,
                        ),
                )
            }
            return
        }
        navigate(entry.path)
    }

    fun goUp() {
        val currentPath = state.value.sftp.path
        val parent = SftpPath.parent(currentPath)
        navigate(parent)
    }

    /**
     * Lists first and commits the breadcrumb only after success. A generation guard prevents a
     * slow SSH response from replacing a newer directory chosen by the user.
     */
    fun navigate(
        requestedPath: String,
        refresh: Boolean = false,
    ) {
        val session = activeSftpSession ?: return
        val currentSftp = state.value.sftp
        if (currentSftp.operationBusy || currentSftp.transferBusy) {
            updateState {
                it.copy(sftp = it.sftp.copy(message = "Wait for the active SFTP operation before changing folders."))
            }
            return
        }
        val target =
            runCatching { SftpPath.requireRootRelative(requestedPath) }
                .onFailure { error ->
                    updateState {
                        it.copy(sftp = it.sftp.copy(message = error.message ?: "Invalid SFTP path"))
                    }
                }.getOrNull() ?: return
        val generation = ++sftpNavigationGenerationState
        sftpNavigationJob?.cancel()
        updateState {
            it.copy(
                sftp =
                    it.sftp.copy(
                        loading = true,
                        message = if (refresh) "Refreshing $target..." else "Opening $target...",
                    ),
            )
        }
        sftpNavigationJob =
            scope.launch {
                runCatching { session.list(target) }
                    .onSuccess { entries ->
                        if (generation != sftpNavigationGenerationState || session !== activeSftpSession) return@onSuccess
                        updateState {
                            it.copy(
                                sftp =
                                    it.sftp.copy(
                                        path = target,
                                        loading = false,
                                        connected = true,
                                        entries = entries,
                                        selectedEntry = null,
                                        transferRemotePath = "",
                                        operationPath = "",
                                        renameTargetPath = "",
                                        message = null,
                                    ),
                            )
                        }
                    }.onFailure { error ->
                        if (error is CancellationException ||
                            generation != sftpNavigationGenerationState ||
                            session !== activeSftpSession
                        ) {
                            return@onFailure
                        }
                        updateState {
                            it.copy(
                                sftp =
                                    it.sftp.copy(
                                        loading = false,
                                        message = error.message ?: "Could not open directory",
                                    ),
                            )
                        }
                    }
            }
    }

    fun createFolderNamed(name: String) {
        val child =
            childSftpPathOrReport(
                parent = state.value.sftp.path,
                name = name,
                blankMessage = "Enter a folder name.",
            ) ?: return
        updateState { it.copy(sftp = it.sftp.copy(operationPath = child)) }
        createDirectory()
    }

    fun renameSelectedEntry(
        expectedPath: String,
        name: String,
    ) {
        val selected = state.value.sftp.selectedEntry
        if (selected?.path != expectedPath) {
            updateState { it.copy(sftp = it.sftp.copy(message = "The selected file changed; reopen its details and retry.")) }
            return
        }
        val target =
            childSftpPathOrReport(
                parent = SftpPath.parent(selected.path),
                name = name,
                blankMessage = "Enter a new name.",
            ) ?: return
        updateState {
            it.copy(
                sftp =
                    it.sftp.copy(
                        operationPath = selected.path,
                        renameTargetPath = target,
                    ),
            )
        }
        renamePath()
    }

    fun deleteSelectedEntry(expectedPath: String) {
        val selected = state.value.sftp.selectedEntry
        if (selected?.path != expectedPath) {
            updateState { it.copy(sftp = it.sftp.copy(message = "The selected file changed; reopen its details and retry.")) }
            return
        }
        updateState { it.copy(sftp = it.sftp.copy(operationPath = selected.path)) }
        deletePath()
    }

    private fun childSftpPathOrReport(
        parent: String,
        name: String,
        blankMessage: String,
    ): String? {
        if (name.isEmpty()) {
            updateState { it.copy(sftp = it.sftp.copy(message = blankMessage)) }
            return null
        }
        return runCatching { SftpPath.child(parent, name) }
            .onFailure { error ->
                updateState {
                    it.copy(sftp = it.sftp.copy(message = error.message ?: "Invalid remote name"))
                }
            }.getOrNull()
    }

    fun createDirectory() {
        val session = activeSftpSession ?: return
        val path =
            normalizeSftpPathOrReport(
                state.value.sftp.operationPath,
                "Enter a remote directory path before creating it.",
            ) ?: return
        runSftpOperation(
            startedMessage = "Creating directory $path...",
            successMessage = "Directory created.",
        ) {
            session.mkdir(path)
        }
    }

    fun renamePath() {
        val session = activeSftpSession ?: return
        val from =
            normalizeSftpPathOrReport(
                state.value.sftp.operationPath,
                "Enter both source and target remote paths before renaming.",
            ) ?: return
        val to =
            normalizeSftpPathOrReport(
                state.value.sftp.renameTargetPath,
                "Enter both source and target remote paths before renaming.",
            ) ?: return
        runSftpOperation(
            startedMessage = "Renaming $from to $to...",
            successMessage = "Path renamed.",
        ) {
            session.rename(from, to)
        }
    }

    fun deletePath() {
        val session = activeSftpSession ?: return
        val path =
            normalizeSftpPathOrReport(
                state.value.sftp.operationPath,
                "Enter a remote file or directory path before deleting.",
            ) ?: return
        runSftpOperation(
            startedMessage = "Deleting $path...",
            successMessage = "Path deleted.",
        ) {
            session.delete(path)
        }
    }

    private fun runSftpOperation(
        startedMessage: String,
        successMessage: String,
        operation: suspend () -> Unit,
    ) {
        val session = activeSftpSession ?: return
        val currentSftp = state.value.sftp
        if (currentSftp.loading || currentSftp.operationBusy || currentSftp.transferBusy) return
        val path = state.value.sftp.path
        val connectionGeneration = sftpConnectionGenerationState
        val navigationGeneration = sftpNavigationGenerationState
        sftpOperationJob?.cancel()
        updateState {
            it.copy(sftp = it.sftp.copy(operationBusy = true, loading = true, message = startedMessage))
        }
        sftpOperationJob =
            scope.launch {
                try {
                    runCatching {
                        operation()
                        val entries = session.list(path)
                        if (isCurrentSftpListing(session, connectionGeneration, navigationGeneration, path)) {
                            updateState {
                                it.copy(
                                    sftp =
                                        it.sftp.copy(
                                            operationBusy = false,
                                            loading = false,
                                            connected = true,
                                            entries = entries,
                                            selectedEntry = null,
                                            message = successMessage,
                                        ),
                                )
                            }
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        if (isCurrentSftpListing(session, connectionGeneration, navigationGeneration, path)) {
                            updateState {
                                it.copy(
                                    sftp =
                                        it.sftp.copy(
                                            operationBusy = false,
                                            loading = false,
                                            message = error.message ?: "SFTP operation failed",
                                        ),
                                )
                            }
                        }
                    }
                } finally {
                    if (connectionGeneration == sftpConnectionGenerationState) {
                        sftpOperationJob = null
                    }
                }
            }
    }

    private fun isCurrentSftpListing(
        session: SftpSession,
        connectionGeneration: Long,
        navigationGeneration: Long,
        path: String,
    ): Boolean =
        session === activeSftpSession &&
            connectionGeneration == sftpConnectionGenerationState &&
            navigationGeneration == sftpNavigationGenerationState &&
            state.value.screen == AndroidHomeScreenMode.Sftp &&
            state.value.sftp.path == path

    private fun isCurrentSftpSession(
        session: SftpSession,
        generation: Long,
        profileId: DeviceProfileId,
    ): Boolean = isCurrentSftpConnection(generation, profileId) && session === activeSftpSession

    fun close() {
        val session = activeSftpSession
        activeSftpSession = null
        sftpConnectionGenerationState += 1
        sftpNavigationGenerationState += 1
        val connectJob = sftpConnectJob
        sftpConnectJob = null
        connectJob?.cancel()
        sftpNavigationJob?.cancel()
        sftpNavigationJob = null
        val operationJob = sftpOperationJob
        sftpOperationJob = null
        operationJob?.cancel()
        onSessionIdle()
        val transferJob = transferCoordinator.cancel()
        updateState {
            it.copy(
                screen = AndroidHomeScreenMode.Detail,
                sftp = SftpUiState(),
            )
        }
        scope.launch {
            runCatching { session?.close() }
            transferJob?.cancelAndJoin()
            operationJob?.cancelAndJoin()
            connectJob?.cancelAndJoin()
        }
    }

    fun uploadFile() = transferCoordinator.uploadFile()

    fun uploadPickedDocument(uri: String) = transferCoordinator.uploadPickedDocument(uri)

    fun uploadPickedDocuments(uris: List<String>) = transferCoordinator.uploadPickedDocuments(uris)

    fun downloadFile() = transferCoordinator.downloadFile()

    fun downloadPickedDocument(
        uri: String,
        requestedRemotePath: String,
    ) = transferCoordinator.downloadPickedDocument(uri, requestedRemotePath)

    private fun normalizeSftpPathOrReport(
        path: String,
        blankMessage: String,
    ): String? {
        if (path.isBlank()) {
            updateState { it.copy(sftp = it.sftp.copy(message = blankMessage)) }
            return null
        }
        return runCatching { SftpPath.requireRootRelative(path) }
            .onFailure { error ->
                updateState {
                    it.copy(sftp = it.sftp.copy(message = error.message ?: "Invalid remote path"))
                }
            }.getOrNull()
    }

    fun cancelTransfer() {
        val session = activeSftpSession
        val transferJob = transferCoordinator.cancel()
        activeSftpSession = null
        sftpConnectionGenerationState += 1
        sftpNavigationGenerationState += 1
        val connectJob = sftpConnectJob
        sftpConnectJob = null
        connectJob?.cancel()
        sftpNavigationJob?.cancel()
        sftpNavigationJob = null
        val operationJob = sftpOperationJob
        sftpOperationJob = null
        operationJob?.cancel()
        onSessionIdle()
        updateState {
            it.copy(
                sftp =
                    it.sftp.copy(
                        transferBusy = true,
                        message = "Cancelling transfer and releasing the SFTP connection...",
                    ),
            )
        }
        scope.launch {
            val cancellation =
                runCatching {
                    session?.cancelTransfer() ?: SftpTransferCancellation.NoActiveTransfer
                }
            runCatching { session?.close() }
            transferJob?.cancelAndJoin()
            operationJob?.cancelAndJoin()
            connectJob?.cancelAndJoin()
            updateState {
                it.copy(
                    sftp =
                        it.sftp.copy(
                            connected = false,
                            transferBusy = false,
                            message =
                                cancellation.fold(
                                    onSuccess = { result ->
                                        when (result) {
                                            SftpTransferCancellation.Cancelled -> {
                                                "SFT-7106: Transfer physically cancelled. " +
                                                    "The SFTP socket was closed; reconnect to continue browsing."
                                            }

                                            SftpTransferCancellation.AlreadyFinishing -> {
                                                "The transfer had already reached its safe publication boundary. " +
                                                    "The SFTP session is closed; reconnect and refresh to confirm the completed file."
                                            }

                                            SftpTransferCancellation.NoActiveTransfer -> {
                                                "No active transfer remained. The SFTP session was closed."
                                            }
                                        }
                                    },
                                    onFailure = { error ->
                                        "The SFTP session was closed while cancelling: ${error.message ?: "unknown error"}"
                                    },
                                ),
                        ),
                )
            }
        }
    }

    private fun DeviceProfile.withSshRoutePort(route: SshRoutePlan): DeviceProfile = copy(sshPort = route.host.port ?: sshPort)

    private fun TransferResumeCapability.capabilityNotice(): String =
        when (this) {
            TransferResumeCapability.Supported -> "Transfers support verified resume."
            is TransferResumeCapability.Unsupported -> "$reasonCode: Resume disabled (fail-closed): $reason"
        }
}
