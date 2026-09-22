@file:Suppress("FunctionNaming")

package dev.aegis.remote.android.ui.components

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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import dev.aegis.remote.android.ui.AegisAndroidApp
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
internal fun LabelValue(
    label: String,
    value: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, OnyxColors.Hairline, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, color = OnyxColors.OnSurfaceVariant, fontSize = 12.sp)
        Text(value, color = OnyxColors.OnSurface, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
internal fun OnyxTextField(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    password: Boolean = false,
    singleLine: Boolean = true,
    required: Boolean = false,
    supportingText: String? = null,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(if (required) "$label *" else label) },
        supportingText =
            supportingText?.let { text ->
                { Text(text, color = OnyxColors.OnSurfaceVariant) }
            },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        maxLines = if (singleLine) 1 else 5,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        shape = RoundedCornerShape(if (singleLine) 999.dp else 14.dp),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedTextColor = OnyxColors.OnSurface,
                unfocusedTextColor = OnyxColors.OnSurface,
                focusedBorderColor = OnyxColors.Primary,
                unfocusedBorderColor = OnyxColors.OutlineVariant,
                focusedLabelColor = OnyxColors.Primary,
                unfocusedLabelColor = OnyxColors.OnSurfaceVariant,
                cursorColor = OnyxColors.Primary,
                focusedContainerColor = OnyxColors.ContainerLowest,
                unfocusedContainerColor = OnyxColors.ContainerLowest,
            ),
        modifier = modifier,
    )
}

@Composable
internal fun OnyxButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (primary) OnyxColors.PrimaryContainer else OnyxColors.Container,
                contentColor = if (primary) OnyxColors.OnSurfaceStrong else OnyxColors.OnSurface,
                disabledContainerColor = OnyxColors.ContainerLow,
                disabledContentColor = OnyxColors.Outline,
            ),
        shape = RoundedCornerShape(999.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp),
        modifier = modifier,
    ) {
        // Reserve the same space on both sides so the label stays on the
        // button's centerline even when it wraps or the translation is longer.
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(32.dp))
    }
}

@Composable
internal fun onyxSwitchColors() =
    SwitchDefaults.colors(
        checkedThumbColor = OnyxColors.OnSurfaceStrong,
        checkedTrackColor = OnyxColors.PrimaryContainer,
        checkedBorderColor = OnyxColors.PrimaryContainer,
        uncheckedThumbColor = OnyxColors.OnSurfaceVariant,
        uncheckedTrackColor = OnyxColors.ContainerHighest,
        uncheckedBorderColor = OnyxColors.OutlineVariant,
    )

@Composable
internal fun DiagnosticsCard(
    profile: DeviceProfile,
    diagnostics: RouteDiagnostics?,
    routeTesting: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, OnyxColors.Hairline, RoundedCornerShape(12.dp)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.diagnostics_title), color = OnyxColors.OnSurface, fontWeight = FontWeight.SemiBold)
            if (routeTesting) {
                DiagnosticRow(
                    stringResource(R.string.diagnostics_checking),
                    stringResource(R.string.diagnostics_checking_detail),
                    true,
                )
            } else if (diagnostics == null) {
                DiagnosticRow(stringResource(R.string.route_lan), stringResource(R.string.diagnostics_candidate, profile.localHost.host), true)
                DiagnosticRow(stringResource(R.string.route_vpn), profile.vpnHost?.host ?: stringResource(R.string.not_configured), profile.vpnHost != null)
                DiagnosticRow(stringResource(R.string.route_stun_direct), stringResource(R.string.diagnostics_relay_required), profile.relayDeviceId != null)
                DiagnosticRow(stringResource(R.string.route_turn_relay), stringResource(R.string.diagnostics_turn_fallback), profile.relayDeviceId != null)
                DiagnosticRow(
                    stringResource(R.string.route_reverse_relay),
                    stringResource(R.string.diagnostics_remote_disabled),
                    profile.remoteAccessEnabled,
                )
            } else {
                diagnostics.health.forEach { health ->
                    val reason =
                        health.reason?.localizedStatusMessage()
                            ?: stringResource(R.string.diagnostics_no_detail)
                    val detail = health.latencyMs?.let { "$reason | ${it}ms" } ?: reason
                    DiagnosticRow(
                        label = health.routeType.displayName(),
                        detail = detail,
                        available = health.available,
                    )
                }
                DiagnosticRow(
                    label = stringResource(R.string.diagnostics_selected),
                    detail =
                        diagnostics.selectedRoute?.displayName()
                            ?: diagnostics.failureReason?.localizedStatusMessage()
                            ?: stringResource(R.string.diagnostics_no_route),
                    available = diagnostics.selectedRoute != null,
                )
            }
        }
    }
}

@Composable
internal fun ConnectionRouteType.displayName(): String = stringResource(labelRes())

@Composable
internal fun String.localizedValidationMessage(): String {
    val normalized = lowercase(Locale.ROOT)
    return when {
        "display name" in normalized -> stringResource(R.string.validation_display_name)
        "lan host" in normalized || "lan address" in normalized -> stringResource(R.string.validation_lan_host)
        "ssh username" in normalized -> stringResource(R.string.validation_ssh_username)
        "ssh password" in normalized -> stringResource(R.string.validation_ssh_password)
        "ssh private key" in normalized -> stringResource(R.string.validation_ssh_private_key)
        "agent authentication" in normalized -> stringResource(R.string.validation_ssh_agent)
        "host key fingerprint" in normalized -> stringResource(R.string.validation_ssh_fingerprint)
        "ssh port" in normalized -> stringResource(R.string.validation_ssh_port)
        "relay url is required" in normalized -> stringResource(R.string.validation_relay_url)
        "relay url must" in normalized -> stringResource(R.string.validation_relay_scheme)
        "phone name" in normalized -> stringResource(R.string.validation_phone_name)
        else -> localizedStatusMessage()
    }
}

@Composable
internal fun String.localizedStatusMessage(): String {
    val kind = userFacingStatusKind(this) ?: return this
    return stringResource(kind.resourceId())
}

internal fun String.suggestedDownloadName(): String =
    trim()
        .trimEnd('/')
        .substringAfterLast('/', missingDelimiterValue = "aegis-download.bin")
        .takeIf { it.isNotBlank() && it != "." }
        ?: "aegis-download.bin"

@Composable
internal fun DiagnosticRow(
    label: String,
    detail: String,
    available: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        StatusDot(available)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, color = OnyxColors.OnSurface, fontSize = 14.sp)
            Text(detail, color = OnyxColors.OnSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun EmptyActionCard(
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, OnyxColors.OutlineVariant, RoundedCornerShape(16.dp))
                .padding(16.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = OnyxColors.OnSurface, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(description, color = OnyxColors.OnSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) {
                    Text(actionLabel, color = OnyxColors.Primary, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
internal fun StatusDot(connected: Boolean) {
    PresenceDot(if (connected) HostPresenceStatus.Online else HostPresenceStatus.Offline)
}

/**
 * Live host presence indicator: green pulse while the PC is reachable, red when it stopped
 * answering, neutral while nothing is known yet.
 */
@Composable
internal fun PresenceDot(
    status: HostPresenceStatus,
    modifier: Modifier = Modifier,
) {
    val color =
        when (status) {
            HostPresenceStatus.Online -> OnyxColors.Primary
            HostPresenceStatus.Offline -> OnyxColors.Error
            HostPresenceStatus.Unknown -> OnyxColors.Outline
        }
    Box(modifier.size(14.dp), contentAlignment = Alignment.Center) {
        if (status == HostPresenceStatus.Online) {
            val pulse = rememberInfiniteTransition(label = "presence")
            val haloScale by pulse.animateFloat(
                initialValue = 1f,
                targetValue = 2.4f,
                animationSpec = infiniteRepeatable(tween(1_600), RepeatMode.Restart),
                label = "halo-scale",
            )
            val haloAlpha by pulse.animateFloat(
                initialValue = 0.45f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(tween(1_600), RepeatMode.Restart),
                label = "halo-alpha",
            )
            Box(
                Modifier
                    .size(8.dp)
                    .scale(haloScale)
                    .alpha(haloAlpha)
                    .background(color, CircleShape),
            )
        }
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}

@Composable
internal fun HostPresence.label(): String =
    when (status) {
        HostPresenceStatus.Online -> stringResource(R.string.host_presence_online)
        HostPresenceStatus.Offline -> stringResource(R.string.host_presence_offline)
        HostPresenceStatus.Unknown -> stringResource(R.string.host_presence_unknown)
    }

@Preview
@Composable
internal fun AegisAndroidAppPreview() {
    AegisAndroidApp()
}
