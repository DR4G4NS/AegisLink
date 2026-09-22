@file:Suppress("FunctionNaming")

package dev.aegis.remote.android.ui.profiles

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
import dev.aegis.remote.android.home.HostPresence
import dev.aegis.remote.android.home.HostPresenceStatus
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
import dev.aegis.remote.android.ui.components.DiagnosticsCard
import dev.aegis.remote.android.ui.components.EmptyActionCard
import dev.aegis.remote.android.ui.components.OnyxButton
import dev.aegis.remote.android.ui.components.PresenceDot
import dev.aegis.remote.android.ui.components.label
import dev.aegis.remote.android.ui.components.labelRes
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
internal fun ProfileList(
    profiles: List<DeviceProfile>,
    dispatch: (AndroidHomeAction) -> Unit,
    errorMessage: String? = null,
    presenceOf: (DeviceProfileId) -> HostPresence = { HostPresence() },
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            item {
                Text(
                    message.localizedStatusMessage(),
                    color = OnyxColors.Error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
        item {
            PairingHeroCard(
                onScan = { dispatch(AndroidHomeAction.ShowLocalPairing) },
                onManual = { dispatch(AndroidHomeAction.ShowAddProfile) },
            )
        }
        item {
            Text(
                text = stringResource(if (profiles.isEmpty()) R.string.computers_section_empty else R.string.computers_section),
                color = OnyxColors.OnSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { heading() },
            )
        }
        if (profiles.isEmpty()) {
            item {
                EmptyActionCard(
                    title = stringResource(R.string.no_computers_title),
                    description = stringResource(R.string.no_computers_message),
                    actionLabel = stringResource(R.string.scan_qr),
                    onAction = { dispatch(AndroidHomeAction.ShowLocalPairing) },
                )
            }
        } else {
            items(profiles, key = { it.id.value }) { profile ->
                ProfileCard(
                    profile = profile,
                    presence = presenceOf(profile.id),
                    onClick = { dispatch(AndroidHomeAction.SelectProfile(profile.id)) },
                )
            }
        }
    }
}

@Composable
internal fun PairingHeroCard(
    onScan: () -> Unit,
    onManual: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(48.dp).background(OnyxColors.PrimaryContainer, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = OnyxColors.OnSurfaceStrong)
            }
            Text(
                stringResource(R.string.pairing_hero_title),
                color = OnyxColors.OnSurfaceStrong,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.pairing_hero_message),
                color = OnyxColors.OnSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            OnyxButton(stringResource(R.string.scan_qr), Icons.Outlined.QrCodeScanner, onClick = onScan, primary = true)
            Button(
                onClick = onManual,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = OnyxColors.OnSurfaceVariant,
                    ),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.advanced_ssh_action), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(32.dp))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ProfileCard(
    profile: DeviceProfile,
    onClick: () -> Unit,
    presence: HostPresence = HostPresence(),
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(0.dp),
        modifier =
            Modifier
                .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(44.dp).background(OnyxColors.Container, RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Computer, contentDescription = null, tint = OnyxColors.OnSurface)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.displayName, color = OnyxColors.OnSurfaceStrong, style = MaterialTheme.typography.titleMedium)
                Text(
                    profile.localHost.host,
                    color = OnyxColors.OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PresenceDot(presence.status)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${presence.label()} · ${profile.readinessLabel()}",
                        color = presence.labelColor(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Icon(Icons.Outlined.Link, contentDescription = null, tint = OnyxColors.OnSurfaceVariant)
        }
    }
}

@Composable
internal fun HostPresence.labelColor(): Color =
    when (status) {
        HostPresenceStatus.Online -> OnyxColors.Primary
        HostPresenceStatus.Offline -> OnyxColors.Error
        HostPresenceStatus.Unknown -> OnyxColors.OnSurfaceVariant
    }

@Composable
internal fun DeviceProfile.readinessLabel(): String =
    when {
        localProtocolTokenRef != null && credentialsRef != null -> stringResource(R.string.device_linked_ssh)
        localProtocolTokenRef != null -> stringResource(R.string.device_linked)
        credentialsRef != null -> stringResource(R.string.device_ssh_ready)
        else -> stringResource(R.string.device_setup_pending)
    }

@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
internal fun ProfileDetail(
    profile: DeviceProfile,
    diagnosticsVisible: Boolean,
    routeTesting: Boolean,
    diagnostics: RouteDiagnostics?,
    wakeOnLan: WolUiState,
    sshHealth: SshHealthUiState,
    dispatch: (AndroidHomeAction) -> Unit,
    presence: HostPresence = HostPresence(),
) {
    val sshReady = profile.credentialsRef != null && profile.hostKeyFingerprint != null && profile.username.isNotBlank()
    var confirmDelete by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DeviceSummaryCard(profile, presence) }
        item {
            Text(
                stringResource(R.string.actions_section),
                color = OnyxColors.OnSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() },
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DeviceActionCard(
                    stringResource(R.string.action_screen),
                    stringResource(R.string.action_screen_detail),
                    Icons.Outlined.DesktopWindows,
                    enabled = profile.permissions.visual,
                    primary = true,
                    modifier = Modifier.weight(1f),
                ) { dispatch(AndroidHomeAction.PrepareVisualSession) }
                DeviceActionCard(
                    stringResource(R.string.action_terminal),
                    stringResource(if (sshReady) R.string.action_open_ssh else R.string.action_configure_ssh),
                    Icons.Outlined.Terminal,
                    enabled = profile.permissions.terminal,
                    modifier = Modifier.weight(1f),
                ) {
                    dispatch(if (sshReady) AndroidHomeAction.StartTerminal else AndroidHomeAction.EditSelectedProfile)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DeviceActionCard(
                    stringResource(R.string.action_files),
                    stringResource(if (sshReady) R.string.action_explore_sftp else R.string.action_configure_ssh),
                    Icons.Outlined.FolderOpen,
                    enabled = profile.permissions.sftp,
                    modifier = Modifier.weight(1f),
                ) {
                    dispatch(if (sshReady) AndroidHomeAction.StartSftp else AndroidHomeAction.EditSelectedProfile)
                }
                DeviceActionCard(
                    stringResource(R.string.action_control),
                    stringResource(R.string.action_mouse_keyboard),
                    Icons.Outlined.Mouse,
                    enabled = profile.permissions.input,
                    modifier = Modifier.weight(1f),
                ) { dispatch(AndroidHomeAction.PrepareVisualSession) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DeviceActionCard(
                    stringResource(R.string.action_clipboard),
                    stringResource(R.string.action_share_text),
                    Icons.Outlined.ContentPaste,
                    enabled = profile.permissions.clipboard,
                    modifier = Modifier.weight(1f),
                ) { dispatch(AndroidHomeAction.ShowClipboard) }
                DeviceActionCard(
                    stringResource(R.string.action_power_on),
                    if (wakeOnLan.sending) stringResource(R.string.action_sending) else stringResource(R.string.wol_action_detail),
                    Icons.Outlined.PowerSettingsNew,
                    enabled =
                        profile.permissions.wakeOnLan &&
                            profile.wakeOnLanConfig?.capability == WakeOnLanCapability.Supported &&
                            !wakeOnLan.sending,
                    modifier = Modifier.weight(1f),
                ) { dispatch(AndroidHomeAction.SendWakeOnLan) }
            }
        }
        if (profile.permissions.wakeOnLan && profile.availableWakeOnLanConfigs.isNotEmpty()) {
            item {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(OnyxColors.ContainerLow, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.wol_adapter), color = OnyxColors.OnSurface, fontWeight = FontWeight.SemiBold)
                    profile.availableWakeOnLanConfigs.forEach { config ->
                        val selected = config == profile.wakeOnLanConfig
                        val adapterLabel = config.adapterName ?: config.adapterId ?: config.macAddress.value
                        Button(
                            onClick = { dispatch(AndroidHomeAction.SelectWakeOnLanAdapter(config)) },
                            enabled = config.capability == WakeOnLanCapability.Supported && !wakeOnLan.sending,
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor =
                                        if (selected) OnyxColors.PrimaryContainer else OnyxColors.ContainerHigh,
                                ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    "${if (selected) "✓ " else ""}$adapterLabel — ${stringResource(config.capability.labelRes())}",
                                    color = OnyxColors.OnSurface,
                                )
                                Text(
                                    "${config.macAddress.value} · ${config.broadcastAddress}:${config.port}" +
                                        config.capabilityReason?.let { " · $it" }.orEmpty(),
                                    color = OnyxColors.OnSurfaceVariant,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (!sshReady && (profile.permissions.terminal || profile.permissions.sftp)) {
            item {
                InfoBanner(
                    title = stringResource(R.string.ssh_feature_title),
                    message = stringResource(R.string.ssh_feature_message),
                    icon = Icons.Outlined.Lock,
                )
            }
        }
        if (sshReady && (profile.permissions.terminal || profile.permissions.sftp)) {
            item {
                SettingsActionRow(
                    stringResource(R.string.ssh_health_title),
                    stringResource(if (sshHealth.checking) R.string.ssh_health_checking else R.string.ssh_health_idle),
                    Icons.Outlined.AssignmentTurnedIn,
                ) {
                    if (!sshHealth.checking) dispatch(AndroidHomeAction.CheckSshHealth)
                }
            }
        }
        sshHealth.message?.let { healthMessage ->
            item {
                InfoBanner(
                    title = stringResource(R.string.ssh_health_title),
                    message = healthMessage.localizedStatusMessage(),
                    icon = Icons.Outlined.AssignmentTurnedIn,
                    error = sshHealth.healthy == false,
                )
            }
        }
        wakeOnLan.message?.let { message ->
            item {
                InfoBanner(
                    stringResource(R.string.action_power_on),
                    message.localizedStatusMessage(),
                    Icons.Outlined.PowerSettingsNew,
                    error = !wakeOnLan.packetSent,
                )
            }
        }
        item {
            SettingsActionRow(
                stringResource(R.string.remote_access),
                stringResource(R.string.relay_stun_turn),
                Icons.Outlined.SettingsEthernet,
            ) {
                dispatch(AndroidHomeAction.ShowRelay)
            }
        }
        item {
            SettingsActionRow(
                stringResource(R.string.edit_computer),
                stringResource(R.string.network_ssh_permissions),
                Icons.Outlined.Edit,
            ) {
                dispatch(AndroidHomeAction.EditSelectedProfile)
            }
        }
        item {
            OnyxButton(
                label = stringResource(if (routeTesting) R.string.checking_routes else R.string.check_connection),
                icon = Icons.Outlined.Lan,
                enabled = !routeTesting,
                primary = false,
                onClick = { dispatch(AndroidHomeAction.TestRoutes) },
            )
        }
        if (diagnosticsVisible) item { DiagnosticsCard(profile, diagnostics, routeTesting) }
        item {
            Button(
                onClick = { confirmDelete = true },
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = OnyxColors.Error,
                    ),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.delete_computer))
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = OnyxColors.ContainerHigh,
            title = { Text(stringResource(R.string.delete_computer_title, profile.displayName), color = OnyxColors.OnSurfaceStrong) },
            text = { Text(stringResource(R.string.delete_computer_message), color = OnyxColors.OnSurfaceVariant) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        dispatch(AndroidHomeAction.DeleteProfile(profile.id))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ErrorContainer),
                ) { Text(stringResource(R.string.delete), color = OnyxColors.OnSurfaceStrong) }
            },
            dismissButton = {
                Button(
                    onClick = { confirmDelete = false },
                    colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ContainerHighest),
                ) { Text(stringResource(R.string.cancel), color = OnyxColors.OnSurface) }
            },
        )
    }
}

@Composable
internal fun DeviceSummaryCard(
    profile: DeviceProfile,
    presence: HostPresence = HostPresence(),
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(OnyxColors.ContainerHigh, RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Computer, contentDescription = null, tint = OnyxColors.Primary) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.localHost.host, color = OnyxColors.OnSurface, fontFamily = FontFamily.Monospace)
                Text(profile.readinessLabel(), color = OnyxColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(presence.label(), color = presence.labelColor(), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                PresenceDot(presence.status)
            }
        }
    }
}

@Composable
internal fun DeviceActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val container = if (primary && enabled) OnyxColors.PrimaryContainer else Color.Transparent
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(14.dp))
                .background(container)
                .border(1.dp, if (primary && enabled) OnyxColors.PrimaryContainer else OnyxColors.Hairline, RoundedCornerShape(14.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) OnyxColors.OnSurfaceStrong else OnyxColors.Outline,
        )
        Text(
            title,
            color = if (enabled) OnyxColors.OnSurfaceStrong else OnyxColors.Outline,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(subtitle, color = if (enabled) OnyxColors.OnSurfaceVariant else OnyxColors.Outline, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun SettingsActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, OnyxColors.Hairline, RoundedCornerShape(14.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = OnyxColors.OnSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = OnyxColors.OnSurface, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = OnyxColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Outlined.Link, contentDescription = null, tint = OnyxColors.Outline)
    }
}

@Composable
internal fun InfoBanner(
    title: String,
    message: String,
    icon: ImageVector,
    error: Boolean = false,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, OnyxColors.Hairline, RoundedCornerShape(14.dp))
                .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = if (error) OnyxColors.Error else OnyxColors.Primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = OnyxColors.OnSurface, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(message, color = if (error) OnyxColors.Error else OnyxColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
