@file:Suppress("FunctionNaming")

package dev.aegis.remote.android.ui.pairing

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
import dev.aegis.remote.android.ui.components.OnyxButton
import dev.aegis.remote.android.ui.components.OnyxTextField
import dev.aegis.remote.android.ui.components.localizedValidationMessage
import dev.aegis.remote.android.ui.components.onyxSwitchColors
import dev.aegis.remote.android.ui.pairing.LiveQrScanner
import dev.aegis.remote.android.ui.profiles.InfoBanner
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
internal fun AddProfileForm(
    draft: AddProfileDraft,
    errorMessage: String?,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    val editing = draft.editingProfileId != null
    val canReusePassword = editing && draft.originalAuthMethod == AuthMethod.Password && draft.existingCredentialsRef != null
    val canReusePrivateKey = editing && draft.originalAuthMethod == AuthMethod.PrivateKey && draft.existingCredentialsRef != null
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InfoBanner(
            title = stringResource(R.string.manual_intro_title),
            message = stringResource(R.string.manual_intro_message),
            icon = Icons.Outlined.Terminal,
        )
        Text(
            stringResource(R.string.required_fields_hint),
            color = OnyxColors.OnSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        errorMessage?.let {
            InfoBanner(
                title = stringResource(R.string.device_setup_pending),
                message = it.localizedValidationMessage(),
                icon = Icons.Outlined.Lock,
                error = true,
            )
        }
        FormSectionLabel(stringResource(R.string.manual_identity_section))
        OnyxTextField(
            label = stringResource(R.string.field_display_name),
            value = draft.displayName,
            required = true,
            supportingText = stringResource(R.string.field_display_name_help),
        ) {
            dispatch(AndroidHomeAction.UpdateDraft(draft.copy(displayName = it)))
        }
        OnyxTextField(
            label = stringResource(R.string.field_lan_host),
            value = draft.localHost,
            required = true,
            supportingText = stringResource(R.string.field_lan_host_help),
        ) {
            dispatch(AndroidHomeAction.UpdateDraft(draft.copy(localHost = it)))
        }
        FormSectionLabel(stringResource(R.string.manual_ssh_section))
        OnyxTextField(
            label = stringResource(R.string.field_ssh_port),
            value = draft.sshPort,
            required = true,
            supportingText = stringResource(R.string.field_ssh_port_help),
        ) {
            dispatch(AndroidHomeAction.UpdateDraft(draft.copy(sshPort = it)))
        }
        OnyxTextField(
            label = stringResource(R.string.field_ssh_username),
            value = draft.username,
            required = true,
            supportingText = stringResource(R.string.field_ssh_username_help),
        ) {
            dispatch(AndroidHomeAction.UpdateDraft(draft.copy(username = it)))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(AuthMethod.Password, AuthMethod.PrivateKey).forEach { method ->
                Button(
                    onClick = { dispatch(AndroidHomeAction.UpdateDraft(draft.copy(authMethod = method))) },
                    modifier = Modifier.weight(1f),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = if (draft.authMethod == method) OnyxColors.PrimaryContainer else OnyxColors.ContainerHigh,
                        ),
                ) {
                    Text(
                        stringResource(if (method == AuthMethod.Password) R.string.field_ssh_password else R.string.field_ssh_private_key),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        if (draft.authMethod == AuthMethod.PrivateKey) {
            OnyxTextField(
                label =
                    stringResource(
                        if (canReusePrivateKey) R.string.field_private_key_existing else R.string.field_private_key_pem,
                    ),
                value = draft.sshPrivateKey,
                required = !canReusePrivateKey,
                supportingText = stringResource(R.string.field_ssh_private_key_help),
                singleLine = false,
                modifier = Modifier.fillMaxWidth().height(190.dp),
                onValueChange = { dispatch(AndroidHomeAction.UpdateDraft(draft.copy(sshPrivateKey = it))) },
            )
            OnyxTextField(stringResource(R.string.field_private_key_passphrase), draft.sshPrivateKeyPassphrase, password = true) {
                dispatch(AndroidHomeAction.UpdateDraft(draft.copy(sshPrivateKeyPassphrase = it)))
            }
        } else {
            OnyxTextField(
                label =
                    stringResource(
                        if (canReusePassword) R.string.field_ssh_password_existing else R.string.field_ssh_password,
                    ),
                value = draft.sshPassword,
                password = true,
                required = !canReusePassword,
                supportingText = stringResource(R.string.field_ssh_password_help),
            ) {
                dispatch(AndroidHomeAction.UpdateDraft(draft.copy(sshPassword = it)))
            }
        }
        OnyxTextField(
            label = stringResource(R.string.field_ssh_fingerprint),
            value = draft.hostKeyFingerprint,
            required = true,
            supportingText = stringResource(R.string.field_ssh_fingerprint_help),
        ) {
            dispatch(AndroidHomeAction.UpdateDraft(draft.copy(hostKeyFingerprint = it)))
        }
        FormSectionLabel(stringResource(R.string.manual_optional_section))
        OnyxTextField(stringResource(R.string.field_vpn_ip), draft.vpnHost) {
            dispatch(AndroidHomeAction.UpdateDraft(draft.copy(vpnHost = it)))
        }
        OnyxTextField(stringResource(R.string.field_relay_id), draft.relayDeviceId) {
            dispatch(AndroidHomeAction.UpdateDraft(draft.copy(relayDeviceId = it)))
        }
        OnyxTextField(stringResource(R.string.field_mac_wol), draft.macAddress) {
            dispatch(AndroidHomeAction.UpdateDraft(draft.copy(macAddress = it)))
        }
        OnyxTextField(stringResource(R.string.field_wol_broadcast), draft.wolBroadcastAddress) {
            dispatch(AndroidHomeAction.UpdateDraft(draft.copy(wolBroadcastAddress = it)))
        }
        Button(
            onClick = { dispatch(AndroidHomeAction.SaveDraft) },
            colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.PrimaryContainer),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (editing) R.string.save_changes else R.string.save_profile), color = OnyxColors.OnSurface)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
internal fun FormSectionLabel(label: String) {
    Text(
        label,
        color = OnyxColors.OnSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() },
    )
}

@Composable
internal fun LocalPairingForm(
    draft: LocalPairingDraft,
    busy: Boolean,
    stage: LocalPairingStage,
    message: String?,
    errorMessage: String?,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    var scanLocked by remember { mutableStateOf(false) }
    val terminalFailure =
        stage in
            setOf(
                LocalPairingStage.Rejected,
                LocalPairingStage.Expired,
                LocalPairingStage.Failed,
            )
    val waitingForDesktop =
        busy || scanLocked || draft.requestId != null || stage !in
            setOf(
                LocalPairingStage.Scanning,
                LocalPairingStage.Rejected,
                LocalPairingStage.Expired,
                LocalPairingStage.Failed,
            )
    when {
        terminalFailure && errorMessage != null -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                PairingResultCard(
                    title = stringResource(R.string.pairing_failed_title),
                    message = errorMessage.toPairingErrorMessage(),
                    error = true,
                    actionLabel = stringResource(R.string.try_again),
                    detail = errorMessage.toPairingErrorDetail(),
                    onAction = {
                        scanLocked = false
                        dispatch(AndroidHomeAction.ShowLocalPairing)
                    },
                )
            }
        }

        waitingForDesktop -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                PairingProgressCard(
                    stage = stage,
                    requestSubmitted = draft.requestId != null,
                    message = message,
                    onRetry =
                        if (stage == LocalPairingStage.NetworkError) {
                            { dispatch(AndroidHomeAction.ShowLocalPairing) }
                        } else {
                            null
                        },
                )
            }
        }

        else -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LiveQrScanner(
                    onQrCode = { payload ->
                        if (!scanLocked) {
                            scanLocked = true
                            dispatch(AndroidHomeAction.ImportScannedLocalPairingQrPayload(payload))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                InfoBanner(
                    title = stringResource(R.string.pairing_secure_title),
                    message = stringResource(R.string.pairing_secure_message),
                    icon = Icons.Outlined.Lock,
                )
            }
        }
    }
}

@Composable
internal fun PairingProgressCard(
    stage: LocalPairingStage,
    requestSubmitted: Boolean,
    message: String?,
    onRetry: (() -> Unit)?,
) {
    val awaitingApproval =
        requestSubmitted &&
            stage in
            setOf(
                LocalPairingStage.AwaitingApproval,
                LocalPairingStage.RetryingConnection,
                LocalPairingStage.NetworkError,
            )
    val retrying = requestSubmitted && stage in setOf(LocalPairingStage.RetryingConnection, LocalPairingStage.NetworkError)
    val unreachable = stage == LocalPairingStage.NetworkError && !requestSubmitted
    val title =
        when {
            unreachable -> stringResource(R.string.pairing_unreachable_title)
            retrying -> stringResource(R.string.pairing_retrying_title)
            awaitingApproval -> stringResource(R.string.pairing_awaiting_title)
            else -> stringResource(R.string.pairing_connecting_title)
        }
    val description =
        when {
            unreachable -> stringResource(R.string.pairing_unreachable_message)
            retrying -> stringResource(R.string.pairing_retrying_message)
            awaitingApproval -> stringResource(R.string.pairing_awaiting_message)
            else -> stringResource(R.string.pairing_connecting_message)
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(OnyxColors.ContainerLow)
                .border(1.dp, OnyxColors.Hairline, RoundedCornerShape(20.dp))
                .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(color = OnyxColors.Primary, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
        Text(
            title,
            color = OnyxColors.OnSurfaceStrong,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            description,
            color = OnyxColors.OnSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        message?.takeIf { it.isNotBlank() }?.let {
            Text(
                it.toPairingProgressMessage(),
                color = OnyxColors.Primary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        if (awaitingApproval || retrying) {
            Text(
                stringResource(R.string.pairing_request_arrives_hint),
                color = OnyxColors.OnSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(OnyxColors.ContainerHigh, RoundedCornerShape(12.dp))
                        .padding(12.dp),
            )
        }
        PairingStep(stringResource(R.string.pairing_step_qr), complete = true)
        PairingStep(stringResource(R.string.pairing_step_request), complete = requestSubmitted)
        PairingStep(stringResource(R.string.pairing_step_approval), complete = false)
        onRetry?.let {
            OnyxButton(
                label = stringResource(R.string.retry_connection),
                icon = Icons.Outlined.Lan,
                onClick = it,
                primary = true,
            )
        }
    }
}

@Composable
internal fun PairingStep(
    label: String,
    complete: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .background(if (complete) OnyxColors.PrimaryContainer else OnyxColors.ContainerHigh, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.AssignmentTurnedIn,
                contentDescription = null,
                tint = if (complete) OnyxColors.OnSurfaceStrong else OnyxColors.Outline,
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = if (complete) OnyxColors.OnSurface else OnyxColors.OnSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun PairingResultCard(
    title: String,
    message: String,
    error: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    detail: String? = null,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(OnyxColors.ContainerLow)
                .border(1.dp, OnyxColors.Hairline, RoundedCornerShape(20.dp))
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (error) Icons.Outlined.Lock else Icons.Outlined.AssignmentTurnedIn,
            contentDescription = null,
            tint = if (error) OnyxColors.Error else OnyxColors.Primary,
            modifier = Modifier.size(40.dp),
        )
        Text(title, color = OnyxColors.OnSurfaceStrong, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(message, color = OnyxColors.OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        detail?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                color = OnyxColors.Outline,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )
        }
        OnyxButton(actionLabel, Icons.Outlined.QrCodeScanner, onClick = onAction, primary = true)
    }
}

// Flat failure-code-to-message mapping table; each branch is one match rule.
@Suppress("CyclomaticComplexMethod")
@Composable
internal fun String.toPairingErrorMessage(): String =
    when {
        contains("QRP-7101", ignoreCase = true) ||
            (contains("expired", ignoreCase = true) && !contains("QRP-7105", ignoreCase = true)) -> {
            stringResource(R.string.pairing_error_expired)
        }

        contains("reject", ignoreCase = true) -> {
            stringResource(R.string.pairing_error_rejected)
        }

        contains("QRP-7105", ignoreCase = true) && contains("not valid yet", ignoreCase = true) -> {
            stringResource(R.string.pairing_error_clock)
        }

        contains("fingerprint", ignoreCase = true) ||
            contains("reach the PC", ignoreCase = true) ||
            contains("connect", ignoreCase = true) ||
            contains("network", ignoreCase = true) -> {
            stringResource(R.string.pairing_error_network)
        }

        contains("QRP-7107", ignoreCase = true) -> {
            stringResource(R.string.pairing_error_host_key)
        }

        contains("QRP-7103", ignoreCase = true) ||
            contains("truncated", ignoreCase = true) ||
            contains("corrupt", ignoreCase = true) ||
            contains("malformed", ignoreCase = true) ||
            contains("QRP-7102", ignoreCase = true) ||
            contains("QRP-7104", ignoreCase = true) ||
            contains("QRP-7108", ignoreCase = true) ||
            contains("payload", ignoreCase = true) -> {
            stringResource(R.string.pairing_error_invalid_qr)
        }

        else -> {
            stringResource(R.string.pairing_error_generic)
        }
    }

internal fun String.toPairingErrorDetail(): String? {
    val code = Regex("""QRP-\d+""").find(this)?.value
    val compact = trim().replace('\n', ' ').take(160)
    return when {
        code != null -> code
        compact.isNotBlank() -> compact
        else -> null
    }
}

@Composable
internal fun String.toPairingProgressMessage(): String =
    when {
        contains("approval", ignoreCase = true) || contains("waiting", ignoreCase = true) -> {
            stringResource(R.string.pairing_progress_waiting)
        }

        contains("contact", ignoreCase = true) || contains("connect", ignoreCase = true) -> {
            stringResource(R.string.pairing_progress_verifying)
        }

        else -> {
            stringResource(R.string.pairing_progress_protecting)
        }
    }
