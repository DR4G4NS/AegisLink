@file:Suppress("FunctionNaming")

package dev.aegis.remote.android.ui.relay

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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mouse
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import dev.aegis.remote.android.ui.components.OnyxTextField
import dev.aegis.remote.android.ui.components.localizedStatusMessage
import dev.aegis.remote.android.ui.components.onyxSwitchColors
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
private fun RelayDiagnosticSummary(
    profile: DeviceProfile,
    relay: RelayUiState,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OnyxColors.ContainerLow),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.relay_target_computer), color = OnyxColors.OnSurfaceVariant, fontSize = 12.sp)
            Text(
                profile.relayDeviceId?.value
                    ?: relay.pcRelayDeviceId.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.not_configured),
                color =
                    if (profile.relayDeviceId == null &&
                        relay.pcRelayDeviceId.isBlank()
                    ) {
                        OnyxColors.Error
                    } else {
                        OnyxColors.OnSurface
                    },
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
            relay.lastSessionId?.let {
                val status =
                    stringResource(
                        if (relay.lastSessionApproved) R.string.relay_session_approved else R.string.relay_session_waiting,
                    )
                Text(
                    stringResource(R.string.relay_last_session, it, status),
                    color = OnyxColors.Primary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (relay.turnUrls.isNotEmpty()) {
                Text(
                    stringResource(R.string.relay_backup_servers, relay.turnUrls.joinToString()),
                    color = OnyxColors.OnSurfaceVariant,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            relay.turnCredentialRef?.let {
                Text(
                    stringResource(R.string.relay_turn_protected, it),
                    color = OnyxColors.OnSurfaceVariant,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            relay.turnCredentialsExpiresAtEpochMillis?.let {
                Text(
                    stringResource(R.string.relay_turn_expires, it),
                    color = OnyxColors.Primary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            relay.message?.let {
                Text(
                    it.localizedStatusMessage(),
                    color = if (relay.registered) OnyxColors.OnSurfaceVariant else OnyxColors.Error,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun LegacyConnectionSettings(
    profile: DeviceProfile,
    relay: RelayUiState,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    var identityRotationConfirmationVisible by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RelayDiagnosticSummary(profile, relay)
        OnyxTextField(stringResource(R.string.relay_url_required), relay.relayUrl) {
            dispatch(AndroidHomeAction.UpdateRelayDraft(relay.copy(relayUrl = it, message = null)))
        }
        OnyxTextField(stringResource(R.string.relay_phone_id_optional), relay.phoneRelayDeviceId) {
            dispatch(AndroidHomeAction.UpdateRelayDraft(relay.copy(phoneRelayDeviceId = it, registered = false, message = null)))
        }
        OnyxTextField(stringResource(R.string.relay_phone_name_required), relay.phoneName) {
            dispatch(AndroidHomeAction.UpdateRelayDraft(relay.copy(phoneName = it, message = null)))
        }
        OnyxTextField(stringResource(R.string.relay_pc_id_required), relay.pcRelayDeviceId) {
            dispatch(AndroidHomeAction.UpdateRelayDraft(relay.copy(pcRelayDeviceId = it, message = null)))
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(OnyxColors.ContainerLow)
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.relay_advertise), color = OnyxColors.OnSurface, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.relay_advertise_hint), color = OnyxColors.OnSurfaceVariant, fontSize = 12.sp)
            }
            Switch(
                checked = relay.enabled,
                onCheckedChange = { dispatch(AndroidHomeAction.UpdateRelayDraft(relay.copy(enabled = it, message = null))) },
                colors = onyxSwitchColors(),
            )
        }
        Button(
            onClick = { dispatch(AndroidHomeAction.RegisterRelayDevice) },
            enabled = !relay.busy,
            colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.PrimaryContainer),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (relay.busy) R.string.relay_connecting else R.string.relay_register_phone), color = OnyxColors.OnSurface)
        }
        if (relay.registered || relay.identityRotationPending) {
            Button(
                onClick = { identityRotationConfirmationVisible = true },
                enabled = !relay.busy,
                colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ContainerHigh),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.relay_rotate_identity), color = OnyxColors.Warning)
            }
        }
        Button(
            onClick = { dispatch(AndroidHomeAction.CreateRelaySession) },
            enabled = !relay.busy && relay.registered && (profile.relayDeviceId != null || relay.pcRelayDeviceId.isNotBlank()),
            colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ContainerHigh),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.relay_request_pc), color = OnyxColors.OnSurface)
        }
        Button(
            onClick = { dispatch(AndroidHomeAction.RequestTurnCredentials) },
            enabled = !relay.busy && relay.registered,
            colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ContainerHigh),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.relay_refresh_turn), color = OnyxColors.OnSurface)
        }
        Button(
            onClick = { dispatch(AndroidHomeAction.CloseRelay) },
            colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ContainerHigh),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.back_to_computer), color = OnyxColors.Error)
        }
    }
    if (identityRotationConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { identityRotationConfirmationVisible = false },
            containerColor = OnyxColors.ContainerHigh,
            title = { Text(stringResource(R.string.relay_rotate_identity_title), color = OnyxColors.OnSurfaceStrong) },
            text = { Text(stringResource(R.string.relay_rotate_identity_body), color = OnyxColors.OnSurfaceVariant) },
            confirmButton = {
                TextButton(
                    onClick = {
                        identityRotationConfirmationVisible = false
                        dispatch(AndroidHomeAction.RotateRelayIdentity)
                    },
                ) {
                    Text(stringResource(R.string.relay_rotate_identity_confirm), color = OnyxColors.Warning)
                }
            },
            dismissButton = {
                TextButton(onClick = { identityRotationConfirmationVisible = false }) {
                    Text(stringResource(R.string.cancel), color = OnyxColors.OnSurface)
                }
            },
        )
    }
}

@Composable
internal fun RelayScreen(
    profile: DeviceProfile,
    relay: RelayUiState,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    var draft by remember(profile.id, profile.vpnHost, profile.remoteSshPort) {
        mutableStateOf(
            dev.aegis.remote.android.home.DirectAccessDraft(
                host = profile.vpnHost?.host.orEmpty(),
                connectionPort = (profile.vpnHost?.port ?: 48291).toString(),
                filePort = (profile.remoteSshPort ?: profile.sshPort).toString(),
            ),
        )
    }
    var publicAddress by remember { mutableStateOf(false) }
    var portsVisible by remember { mutableStateOf(false) }
    var diagnosticsVisible by remember { mutableStateOf(false) }
    if (diagnosticsVisible) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { diagnosticsVisible = false }) { Text(stringResource(R.string.back)) }
            LegacyConnectionSettings(profile, relay, dispatch)
        }
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            profile.displayName,
            style = MaterialTheme.typography.titleLarge,
            color = OnyxColors.OnSurfaceStrong,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false, true).forEach { mode ->
                Button(
                    onClick = { publicAddress = mode },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (mode == publicAddress) OnyxColors.PrimaryContainer else OnyxColors.ContainerHigh),
                ) {
                    Text(stringResource(if (mode) R.string.direct_public else R.string.direct_simple), textAlign = TextAlign.Center)
                }
            }
        }
        Text(
            stringResource(if (publicAddress) R.string.direct_public_guide else R.string.direct_private_guide),
            color = OnyxColors.OnSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        OnyxTextField(
            stringResource(R.string.direct_address),
            draft.host,
            supportingText = stringResource(R.string.direct_address_hint),
        ) { draft = draft.copy(host = it) }
        TextButton(onClick = { portsVisible = !portsVisible }) {
            Text(stringResource(if (portsVisible) R.string.direct_hide_ports else R.string.direct_ports))
        }
        if (portsVisible) {
            OnyxTextField(stringResource(R.string.direct_connection_port), draft.connectionPort) { draft = draft.copy(connectionPort = it) }
            OnyxTextField(stringResource(R.string.direct_file_port), draft.filePort) { draft = draft.copy(filePort = it) }
        }
        Button(
            onClick = { dispatch(AndroidHomeAction.SaveDirectAccess(draft)) },
            enabled = !relay.busy && draft.host.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.PrimaryContainer),
        ) {
            Text(stringResource(if (relay.busy) R.string.direct_checking else R.string.direct_save), textAlign = TextAlign.Center)
        }
        relay.message?.let { Text(it.localizedStatusMessage(), color = OnyxColors.OnSurfaceVariant, textAlign = TextAlign.Center) }
        Text(stringResource(R.string.direct_open_pc), color = OnyxColors.OnSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center)
        if (profile.vpnHost != null) {
            TextButton(onClick = { dispatch(AndroidHomeAction.RemoveDirectAccess) }, enabled = !relay.busy) {
                Text(stringResource(R.string.direct_remove), color = OnyxColors.Error)
            }
        }
        TextButton(onClick = { diagnosticsVisible = true }) { Text(stringResource(R.string.direct_diagnostics)) }
    }
}
