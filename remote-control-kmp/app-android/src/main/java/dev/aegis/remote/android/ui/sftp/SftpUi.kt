@file:Suppress("FunctionNaming")

package dev.aegis.remote.android.ui.sftp

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.aegis.remote.android.R
import dev.aegis.remote.android.home.AddProfileDraft
import dev.aegis.remote.android.home.AegisAndroidAppGraph
import dev.aegis.remote.android.home.AndroidHomeAction
import dev.aegis.remote.android.home.AndroidHomeScreenMode
import dev.aegis.remote.android.home.AndroidHomeUiState
import dev.aegis.remote.android.home.AndroidHomeViewModel
import dev.aegis.remote.android.home.AndroidHomeViewModelFactory
import dev.aegis.remote.android.home.ClipboardUiState
import dev.aegis.remote.android.home.LocalPairingDraft
import dev.aegis.remote.android.home.LocalPairingStage
import dev.aegis.remote.android.home.RelayUiState
import dev.aegis.remote.android.home.RemoteInputUiState
import dev.aegis.remote.android.home.SftpUiState
import dev.aegis.remote.android.home.SshHealthUiState
import dev.aegis.remote.android.home.TerminalUiState
import dev.aegis.remote.android.home.VisualPointerPosition
import dev.aegis.remote.android.home.VisualUiState
import dev.aegis.remote.android.home.WolUiState
import dev.aegis.remote.android.ui.components.EmptyActionCard
import dev.aegis.remote.android.ui.components.OnyxTextField
import dev.aegis.remote.android.ui.components.localizedStatusMessage
import dev.aegis.remote.android.ui.pairing.LiveQrScanner
import dev.aegis.remote.android.ui.theme.AegisTheme
import dev.aegis.remote.android.ui.theme.OnyxColors
import dev.aegis.remote.core.input.KeyCode
import dev.aegis.remote.core.input.MouseButtonType
import dev.aegis.remote.core.model.AuthMethod
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.FileEntry
import dev.aegis.remote.core.model.FileEntryType
import dev.aegis.remote.core.model.RouteDiagnostics
import dev.aegis.remote.core.model.WakeOnLanCapability
import dev.aegis.remote.core.sftp.SftpPath
import dev.aegis.remote.core.terminal.TerminalKeyStroke
import dev.aegis.remote.core.terminal.TerminalTextBuffer
import dev.aegis.remote.core.terminal.TerminalViewportMetrics
import dev.aegis.remote.core.terminal.TerminalViewportSizer
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel

@Composable
internal fun SftpScreen(
    sftp: SftpUiState,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SftpSortMode.Name) }
    var createFolderVisible by remember { mutableStateOf(false) }
    var createFolderName by remember { mutableStateOf("") }
    var renameVisible by remember { mutableStateOf(false) }
    var renameName by remember { mutableStateOf("") }
    var deleteConfirmationVisible by remember { mutableStateOf(false) }
    var transfersExpanded by remember { mutableStateOf(false) }
    var lastSelectedPath by remember { mutableStateOf<String?>(null) }
    var pendingDownloadPath by remember { mutableStateOf<String?>(null) }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var overflowMenuVisible by remember { mutableStateOf(false) }
    val uploadPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                dispatch(AndroidHomeAction.UploadPickedSftpDocuments(uris.map { it.toString() }))
            }
        }
    val downloadTargetPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val remotePath = pendingDownloadPath
            pendingDownloadPath = null
            if (uri != null && remotePath != null) {
                dispatch(AndroidHomeAction.DownloadPickedSftpDocument(uri.toString(), remotePath))
            }
        }
    val visibleEntries =
        remember(sftp.entries, searchQuery, sortMode) {
            val matching =
                sftp.entries.filter { entry ->
                    searchQuery.isBlank() || entry.name.contains(searchQuery, ignoreCase = true)
                }
            val comparator =
                when (sortMode) {
                    SftpSortMode.Name -> compareBy<FileEntry> { it.name.lowercase() }
                    SftpSortMode.Modified -> compareByDescending<FileEntry> { it.modifiedAtEpochMillis ?: Long.MIN_VALUE }
                    SftpSortMode.Size -> compareByDescending<FileEntry> { it.size }
                }
            matching.sortedWith(
                compareBy<FileEntry> { it.type != FileEntryType.Directory }
                    .then(comparator),
            )
        }
    val browserBusy = sftp.loading || sftp.operationBusy || sftp.transferBusy
    val browserInteractionEnabled = sftp.connected && !browserBusy
    val selectionMode = selectedPaths.isNotEmpty()
    val selectedEntries = remember(visibleEntries, selectedPaths) { visibleEntries.filter { it.path in selectedPaths } }
    BackHandler(enabled = selectionMode) { selectedPaths = emptySet() }
    LaunchedEffect(sftp.path) { selectedPaths = emptySet() }

    LaunchedEffect(sftp.selectedEntry?.path) {
        val selectedPath = sftp.selectedEntry?.path
        if (selectedPath != lastSelectedPath) {
            renameVisible = false
            deleteConfirmationVisible = false
            pendingDownloadPath = null
        }
        lastSelectedPath = selectedPath
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        SftpBreadcrumbs(sftp.path, browserInteractionEnabled, dispatch)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SftpSearchField(searchQuery, { searchQuery = it }, Modifier.weight(1f))
            SftpOverflowMenu(
                sortMode = sortMode,
                expanded = overflowMenuVisible,
                enabled = browserInteractionEnabled,
                onExpandedChange = { overflowMenuVisible = it },
                onSortSelected = { sortMode = it },
                onRefresh = { dispatch(AndroidHomeAction.RefreshSftp) },
                onNewFolder = {
                    createFolderName = ""
                    createFolderVisible = true
                },
                onUpload = { uploadPicker.launch(arrayOf("*/*")) },
            )
        }
        sftp.message?.let { message ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (sftp.loading) {
                    CircularProgressIndicator(
                        color = OnyxColors.Primary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    message.localizedStatusMessage(),
                    color = if (sftp.loading) OnyxColors.Primary else OnyxColors.OnSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
        if (selectionMode) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(OnyxColors.PrimaryContainer.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.sftp_selected_count, selectedPaths.size),
                    color = OnyxColors.Primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { selectedPaths = emptySet() }) {
                    Text(stringResource(R.string.sftp_clear_selection), color = OnyxColors.OnSurface)
                }
                TextButton(
                    onClick = {
                        val files = selectedEntries.filter { it.type == FileEntryType.File }
                        val first = files.firstOrNull() ?: return@TextButton
                        pendingDownloadPath = first.path
                        downloadTargetPicker.launch(first.name.ifBlank { "aegis-download" })
                    },
                    enabled = browserInteractionEnabled && selectedEntries.any { it.type == FileEntryType.File },
                ) {
                    Text(stringResource(R.string.sftp_download_selected), color = OnyxColors.Primary)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.sftp_items_count, visibleEntries.size),
                color = OnyxColors.OnSurfaceVariant,
                fontSize = 12.sp,
            )
            Text(
                stringResource(sortMode.labelResource),
                color = OnyxColors.Outline,
                fontSize = 12.sp,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 4.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            if (visibleEntries.isEmpty() && !sftp.loading) {
                item {
                    if (searchQuery.isBlank()) {
                        EmptyActionCard(
                            stringResource(R.string.sftp_empty_title),
                            stringResource(R.string.sftp_empty_message),
                            actionLabel = stringResource(R.string.sftp_refresh),
                            onAction = { dispatch(AndroidHomeAction.RefreshSftp) },
                        )
                    } else {
                        EmptyActionCard(
                            stringResource(R.string.sftp_empty_title),
                            stringResource(R.string.sftp_search_empty),
                            actionLabel = stringResource(R.string.sftp_clear_search),
                            onAction = { searchQuery = "" },
                        )
                    }
                }
            }
            items(visibleEntries, key = { it.path }) { entry ->
                val selected = entry.path in selectedPaths
                SftpEntryRow(
                    entry = entry,
                    enabled = browserInteractionEnabled,
                    selected = selected,
                    onToggleSelect = {
                        selectedPaths =
                            if (selected) {
                                selectedPaths - entry.path
                            } else {
                                selectedPaths + entry.path
                            }
                    },
                    onClick = {
                        if (selectionMode) {
                            selectedPaths =
                                if (selected) {
                                    selectedPaths - entry.path
                                } else {
                                    selectedPaths + entry.path
                                }
                        } else {
                            dispatch(AndroidHomeAction.OpenSftpEntry(entry))
                        }
                    },
                    onLongClick = {
                        selectedPaths =
                            if (selected) {
                                selectedPaths - entry.path
                            } else {
                                selectedPaths + entry.path
                            }
                    },
                )
            }
        }
        SftpTransferDock(
            sftp = sftp,
            expanded = transfersExpanded,
            onToggle = { transfersExpanded = !transfersExpanded },
            onCancel = { dispatch(AndroidHomeAction.CancelSftpTransfer) },
        )
    }

    if (createFolderVisible) {
        AlertDialog(
            onDismissRequest = { createFolderVisible = false },
            containerColor = OnyxColors.ContainerHigh,
            title = { Text(stringResource(R.string.sftp_new_folder), color = OnyxColors.OnSurface) },
            text = {
                OnyxTextField(
                    label = stringResource(R.string.sftp_folder_name),
                    value = createFolderName,
                ) {
                    createFolderName = it
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        dispatch(AndroidHomeAction.CreateSftpFolderNamed(createFolderName))
                        createFolderVisible = false
                    },
                    enabled = createFolderName.isNotEmpty() && browserInteractionEnabled,
                ) {
                    Text(stringResource(R.string.sftp_create_folder), color = OnyxColors.Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { createFolderVisible = false }) {
                    Text(stringResource(R.string.cancel), color = OnyxColors.OnSurface)
                }
            },
        )
    }

    sftp.selectedEntry?.let { selected ->
        if (!renameVisible && !deleteConfirmationVisible) {
            AlertDialog(
                onDismissRequest = { dispatch(AndroidHomeAction.ClearSftpSelection) },
                containerColor = OnyxColors.ContainerHigh,
                title = {
                    Text(selected.name, color = OnyxColors.OnSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            selected.type.displayLabel(),
                            color = OnyxColors.Primary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                        Text(
                            selected.path,
                            color = OnyxColors.OnSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                        Text(
                            selected.metadataLabel(),
                            color = OnyxColors.OnSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        if (selected.type == FileEntryType.File) {
                            Button(
                                onClick = {
                                    pendingDownloadPath = selected.path
                                    downloadTargetPicker.launch(selected.name.ifBlank { "aegis-download" })
                                },
                                enabled = browserInteractionEnabled,
                                colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.PrimaryContainer),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                                Spacer(Modifier.width(7.dp))
                                Text(stringResource(R.string.sftp_download))
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    renameName = selected.name
                                    renameVisible = true
                                },
                                enabled = browserInteractionEnabled,
                                colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ContainerHighest),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.Edit, contentDescription = null)
                                Spacer(Modifier.width(5.dp))
                                Text(stringResource(R.string.sftp_rename), fontSize = 12.sp)
                            }
                            Button(
                                onClick = { deleteConfirmationVisible = true },
                                enabled = browserInteractionEnabled,
                                colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ContainerHighest),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = OnyxColors.Error)
                                Spacer(Modifier.width(5.dp))
                                Text(stringResource(R.string.sftp_delete), color = OnyxColors.Error, fontSize = 12.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { dispatch(AndroidHomeAction.ClearSftpSelection) }) {
                        Text(stringResource(R.string.sftp_details_close), color = OnyxColors.OnSurface)
                    }
                },
            )
        }
    }

    if (renameVisible && sftp.selectedEntry != null) {
        AlertDialog(
            onDismissRequest = { renameVisible = false },
            containerColor = OnyxColors.ContainerHigh,
            title = { Text(stringResource(R.string.sftp_rename), color = OnyxColors.OnSurface) },
            text = {
                OnyxTextField(
                    label = stringResource(R.string.sftp_rename_name),
                    value = renameName,
                ) {
                    renameName = it
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        dispatch(AndroidHomeAction.RenameSelectedSftpEntry(sftp.selectedEntry.path, renameName))
                        renameVisible = false
                    },
                    enabled =
                        browserInteractionEnabled &&
                            renameName.isNotEmpty() &&
                            renameName != sftp.selectedEntry.name,
                ) {
                    Text(stringResource(R.string.sftp_rename), color = OnyxColors.Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameVisible = false }) {
                    Text(stringResource(R.string.cancel), color = OnyxColors.OnSurface)
                }
            },
        )
    }

    if (deleteConfirmationVisible && sftp.selectedEntry != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirmationVisible = false },
            containerColor = OnyxColors.ContainerHigh,
            title = { Text(stringResource(R.string.sftp_delete_confirm_title), color = OnyxColors.OnSurface) },
            text = {
                Text(
                    stringResource(R.string.sftp_delete_confirm_message, sftp.selectedEntry.name),
                    color = OnyxColors.OnSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        dispatch(AndroidHomeAction.DeleteSelectedSftpEntry(sftp.selectedEntry.path))
                        deleteConfirmationVisible = false
                    },
                    enabled = browserInteractionEnabled,
                ) {
                    Text(stringResource(R.string.sftp_delete), color = OnyxColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmationVisible = false }) {
                    Text(stringResource(R.string.cancel), color = OnyxColors.OnSurface)
                }
            },
        )
    }
}

@Composable
private fun SftpSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.sftp_search)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.sftp_clear_search))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(999.dp),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = OnyxColors.ContainerLow,
                unfocusedContainerColor = OnyxColors.ContainerLow,
                focusedBorderColor = OnyxColors.Primary,
                unfocusedBorderColor = Color.Transparent,
            ),
        modifier = modifier.height(56.dp),
    )
}

@Composable
private fun SftpOverflowMenu(
    sortMode: SftpSortMode,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSortSelected: (SftpSortMode) -> Unit,
    onRefresh: () -> Unit,
    onNewFolder: () -> Unit,
    onUpload: () -> Unit,
) {
    Box {
        IconButton(onClick = { onExpandedChange(true) }, enabled = true) {
            Icon(
                Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.sftp_more_actions),
                tint = OnyxColors.OnSurface,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            SftpSortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(mode.optionLabelResource),
                            color = if (mode == sortMode) OnyxColors.Primary else OnyxColors.OnSurface,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Sort,
                            contentDescription = null,
                            tint = if (mode == sortMode) OnyxColors.Primary else OnyxColors.OnSurfaceVariant,
                        )
                    },
                    onClick = {
                        onSortSelected(mode)
                        onExpandedChange(false)
                    },
                )
            }
            Divider(color = OnyxColors.Hairline)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sftp_refresh), color = OnyxColors.OnSurface) },
                leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null, tint = OnyxColors.Primary) },
                enabled = enabled,
                onClick = {
                    onRefresh()
                    onExpandedChange(false)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sftp_new_folder), color = OnyxColors.OnSurface) },
                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, tint = OnyxColors.Primary) },
                enabled = enabled,
                onClick = {
                    onNewFolder()
                    onExpandedChange(false)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sftp_upload_here), color = OnyxColors.OnSurface) },
                leadingIcon = { Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = OnyxColors.Primary) },
                enabled = enabled,
                onClick = {
                    onUpload()
                    onExpandedChange(false)
                },
            )
        }
    }
}

@Composable
internal fun SftpBreadcrumbs(
    path: String,
    enabled: Boolean,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    val crumbs = remember(path) { path.toSftpBreadcrumbs() }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(OnyxColors.ContainerLow)
                .horizontalScroll(rememberScrollState())
                .border(1.dp, OnyxColors.Hairline, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text("/", color = OnyxColors.Outline, fontFamily = FontFamily.Monospace)
            }
            TextButton(
                onClick = { dispatch(AndroidHomeAction.NavigateSftp(crumb.path)) },
                enabled = enabled && crumb.path != path,
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
            ) {
                if (index == 0) {
                    Icon(Icons.Outlined.Home, contentDescription = stringResource(R.string.sftp_home), tint = OnyxColors.OnSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("/", color = OnyxColors.Outline, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sftp_root_name), color = OnyxColors.Primary, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                } else {
                    Text(
                        crumb.label,
                        color = if (crumb.path == path) OnyxColors.Primary else OnyxColors.OnSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SftpEntryRow(
    entry: FileEntry,
    enabled: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        enabled = enabled,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    ).background(if (selected) OnyxColors.PrimaryContainer.copy(alpha = 0.16f) else Color.Transparent)
                    .padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelect() },
                enabled = enabled,
                colors =
                    CheckboxDefaults.colors(
                        checkedColor = OnyxColors.Primary,
                        uncheckedColor = OnyxColors.Outline,
                    ),
            )
            Icon(
                imageVector =
                    when (entry.type) {
                        FileEntryType.Directory -> Icons.Outlined.FolderOpen
                        FileEntryType.Symlink -> Icons.Outlined.Link
                        else -> Icons.Outlined.InsertDriveFile
                    },
                contentDescription = entry.type.displayLabel(),
                tint = if (entry.type == FileEntryType.Directory) OnyxColors.Primary else OnyxColors.OnSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.name,
                    color = OnyxColors.OnSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.listMetadataLabel(),
                    color = OnyxColors.OnSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                entry.permissions?.takeIf(String::isNotBlank)?.let { permissions ->
                    Text(
                        permissions,
                        color = OnyxColors.OnSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 132.dp).border(1.dp, OnyxColors.Hairline, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
                Text(
                    if (entry.type == FileEntryType.Directory) "" else entry.size.humanReadableSize(),
                    color = if (entry.type == FileEntryType.Directory) OnyxColors.Primary else OnyxColors.Outline,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                if (entry.type == FileEntryType.Directory) {
                    Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = stringResource(R.string.sftp_open_folder), tint = OnyxColors.OnSurfaceVariant)
                }
            }
        }
        Divider(color = OnyxColors.Hairline)
    }
}

@Composable
private fun SftpTransferDock(
    sftp: SftpUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCancel: () -> Unit,
) {
    val active = sftp.transferBusy
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, OnyxColors.Hairline, RoundedCornerShape(16.dp))
                .background(OnyxColors.ContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = OnyxColors.Primary, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.sftp_transfers), color = OnyxColors.OnSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(7.dp).background(if (active) OnyxColors.Primary else OnyxColors.Outline, RoundedCornerShape(99.dp)))
            Text(if (active) stringResource(R.string.sftp_one_active) else stringResource(R.string.sftp_none_active), color = OnyxColors.OnSurfaceVariant, fontSize = 12.sp)
            Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, stringResource(R.string.sftp_toggle_transfers), tint = OnyxColors.OnSurfaceVariant)
        }
        if (expanded && active) {
            Divider(color = OnyxColors.Hairline)
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        sftp.transferProgressText.orEmpty().localizedStatusMessage(),
                        color = OnyxColors.OnSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.sftp_cancel_transfer), color = OnyxColors.Error) }
                }
                LinearProgressIndicator(color = OnyxColors.Primary, trackColor = OnyxColors.ContainerHighest, modifier = Modifier.fillMaxWidth())
            }
        } else if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 44.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.sftp_no_transfers_title), color = OnyxColors.OnSurface, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.sftp_no_transfers_message), color = OnyxColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SftpTransferMetric(stringResource(R.string.sftp_none_active), stringResource(R.string.sftp_active_label))
                    SftpTransferMetric(stringResource(R.string.sftp_none_queued), stringResource(R.string.sftp_queued_label))
                    SftpTransferMetric("0 B/s", stringResource(R.string.sftp_speed_label))
                }
            }
        }
    }
}

@Composable
private fun RowScope.SftpTransferMetric(
    value: String,
    label: String,
) {
    Column(Modifier.weight(1f)) {
        Text(value, color = OnyxColors.OnSurface, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text(label, color = OnyxColors.Outline, fontSize = 10.sp)
    }
}

internal enum class SftpSortMode(
    val labelResource: Int,
    val optionLabelResource: Int,
) {
    Name(R.string.sftp_sort_name, R.string.sftp_sort_option_name),
    Modified(R.string.sftp_sort_modified, R.string.sftp_sort_option_modified),
    Size(R.string.sftp_sort_size, R.string.sftp_sort_option_size),
}

internal data class SftpBreadcrumb(
    val label: String,
    val path: String,
)

internal fun String.toSftpBreadcrumbs(): List<SftpBreadcrumb> {
    if (this == ".") return listOf(SftpBreadcrumb("", "."))
    val breadcrumbs = mutableListOf(SftpBreadcrumb("", "."))
    var current = "."
    split('/').filter(String::isNotEmpty).forEach { segment ->
        current = SftpPath.child(current, segment)
        breadcrumbs += SftpBreadcrumb(segment, current)
    }
    return breadcrumbs
}

@Composable
internal fun FileEntryType.displayLabel(): String =
    stringResource(
        when (this) {
            FileEntryType.Directory -> R.string.sftp_directory_badge
            FileEntryType.File -> R.string.sftp_file_badge
            FileEntryType.Symlink -> R.string.sftp_symlink_badge
            FileEntryType.Other -> R.string.sftp_other_badge
        },
    )

internal fun FileEntry.metadataLabel(): String {
    val modified =
        modifiedAtEpochMillis?.let { epoch ->
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epoch))
        }
    val sizeLabel = if (type == FileEntryType.Directory) null else size.humanReadableSize()
    return listOfNotNull(sizeLabel, modified, permissions).joinToString(" · ").ifEmpty { "—" }
}

internal fun FileEntry.listMetadataLabel(): String {
    val modified = modifiedAtEpochMillis?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
    return listOfNotNull(if (type == FileEntryType.Directory) null else size.humanReadableSize(), modified).joinToString(" · ").ifEmpty { "—" }
}

internal fun Long.humanReadableSize(): String {
    if (this < 1_024L) return "$this B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = toDouble()
    var index = -1
    while (value >= 1_024.0 && index < units.lastIndex) {
        value /= 1_024.0
        index += 1
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[index])
}
