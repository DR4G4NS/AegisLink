@file:Suppress("FunctionNaming")

package dev.aegis.remote.android.ui.navigation

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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import dev.aegis.remote.android.MainActivity
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
import dev.aegis.remote.android.selectLanguage
import dev.aegis.remote.android.ui.components.EmptyActionCard
import dev.aegis.remote.android.ui.pairing.AddProfileForm
import dev.aegis.remote.android.ui.pairing.LiveQrScanner
import dev.aegis.remote.android.ui.pairing.LocalPairingForm
import dev.aegis.remote.android.ui.profiles.ProfileDetail
import dev.aegis.remote.android.ui.profiles.ProfileList
import dev.aegis.remote.android.ui.relay.RelayScreen
import dev.aegis.remote.android.ui.remote.ClipboardScreen
import dev.aegis.remote.android.ui.remote.RemoteInputScreen
import dev.aegis.remote.android.ui.remote.VisualScreen
import dev.aegis.remote.android.ui.sftp.SftpScreen
import dev.aegis.remote.android.ui.terminal.TerminalScreen
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
internal fun AndroidHomeScreen(
    state: AndroidHomeUiState,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    val selected = state.profiles.firstOrNull { it.id == state.selectedProfileId }
    val navigateBack = {
        when (state.screen) {
            AndroidHomeScreenMode.Terminal -> {
                dispatch(AndroidHomeAction.DisconnectTerminal)
            }

            AndroidHomeScreenMode.Visual -> {
                if (state.visual.fullscreen) {
                    dispatch(AndroidHomeAction.SetVisualFullscreen(false))
                } else {
                    dispatch(AndroidHomeAction.CloseVisual)
                }
            }

            AndroidHomeScreenMode.Sftp -> {
                dispatch(AndroidHomeAction.CloseSftp)
            }

            AndroidHomeScreenMode.RemoteInput -> {
                dispatch(AndroidHomeAction.CloseRemoteInput)
            }

            AndroidHomeScreenMode.Clipboard -> {
                dispatch(AndroidHomeAction.CloseClipboard)
            }

            AndroidHomeScreenMode.Relay -> {
                dispatch(AndroidHomeAction.CloseRelay)
            }

            AndroidHomeScreenMode.AddProfile -> {
                val editingId = state.draft.editingProfileId
                if (editingId == null) {
                    dispatch(AndroidHomeAction.BackToList)
                } else {
                    dispatch(AndroidHomeAction.SelectProfile(editingId))
                }
            }

            else -> {
                dispatch(AndroidHomeAction.BackToList)
            }
        }
    }

    BackHandler(enabled = state.screen != AndroidHomeScreenMode.List, onBack = navigateBack)
    Surface(modifier = Modifier.fillMaxSize(), color = OnyxColors.Background) {
        HomeScreenBody(state, selected, dispatch, navigateBack)
    }
}

@Composable
private fun HomeScreenBody(
    state: AndroidHomeUiState,
    selected: DeviceProfile?,
    dispatch: (AndroidHomeAction) -> Unit,
    navigateBack: () -> Unit,
) {
    val immersiveVisual = state.screen == AndroidHomeScreenMode.Visual && state.visual.fullscreen
    if (immersiveVisual) {
        HomeScreenContent(state, selected, dispatch)
        return
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp)
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppHeader(
                title = state.screen.title(selected, state.draft.editingProfileId != null),
                subtitle = state.screen.subtitle(),
                onBack = navigateBack.takeIf { state.screen != AndroidHomeScreenMode.List },
                showLanguageSelector = state.screen == AndroidHomeScreenMode.List,
            )
            AnimatedContent(
                targetState = state.screen,
                modifier = Modifier.fillMaxWidth().weight(1f),
                transitionSpec = {
                    val forward = targetState.ordinal >= initialState.ordinal
                    val offset = if (forward) 24 else -24
                    (fadeIn(tween(220)) + slideInVertically(tween(260)) { offset })
                        .togetherWith(fadeOut(tween(140)))
                },
                label = "homeScreen",
            ) { screen ->
                HomeScreenContent(state.copy(screen = screen), selected, dispatch)
            }
        }
    }
}

@Composable
private fun HomeScreenContent(
    state: AndroidHomeUiState,
    selected: DeviceProfile?,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    when (state.screen) {
        AndroidHomeScreenMode.List -> {
            ProfileList(state.profiles, dispatch, state.errorMessage, presenceOf = state::presenceOf)
        }

        AndroidHomeScreenMode.Detail -> {
            selected?.let {
                ProfileDetail(
                    profile = it,
                    diagnosticsVisible = state.routeDiagnosticsVisible,
                    routeTesting = state.routeTesting,
                    diagnostics = state.routeDiagnostics,
                    wakeOnLan = state.wakeOnLan,
                    sshHealth = state.sshHealth,
                    dispatch = dispatch,
                    presence = state.presenceOf(it.id),
                )
            } ?: unavailableDeviceCard(dispatch)
        }

        AndroidHomeScreenMode.AddProfile -> {
            AddProfileForm(state.draft, state.errorMessage, dispatch)
        }

        AndroidHomeScreenMode.LocalPairing -> {
            LocalPairingForm(
                draft = state.localPairingDraft,
                busy = state.localPairingBusy,
                stage = state.localPairingStage,
                message = state.localPairingMessage,
                errorMessage = state.errorMessage,
                dispatch = dispatch,
            )
        }

        else -> {
            SessionScreenContent(state, selected, dispatch)
        }
    }
}

@Composable
private fun SessionScreenContent(
    state: AndroidHomeUiState,
    selected: DeviceProfile?,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    when (state.screen) {
        AndroidHomeScreenMode.Terminal -> TerminalScreen(state.terminal, dispatch)
        AndroidHomeScreenMode.Visual -> VisualScreen(state.visual, state.remoteInput, state.clipboard, dispatch)
        AndroidHomeScreenMode.Sftp -> SftpScreen(state.sftp, dispatch)
        AndroidHomeScreenMode.RemoteInput -> RemoteInputScreen(state.remoteInput, dispatch)
        AndroidHomeScreenMode.Clipboard -> ClipboardScreen(state.clipboard, dispatch)
        AndroidHomeScreenMode.Relay -> selected?.let { RelayScreen(it, state.relay, dispatch) } ?: unavailableDeviceCard(dispatch)
        else -> unavailableDeviceCard(dispatch)
    }
}

@Composable
private fun unavailableDeviceCard(dispatch: (AndroidHomeAction) -> Unit) =
    EmptyActionCard(
        stringResource(R.string.device_unavailable_title),
        stringResource(R.string.device_unavailable_message),
        actionLabel = stringResource(R.string.back),
        onAction = { dispatch(AndroidHomeAction.BackToList) },
    )

@Composable
internal fun AndroidHomeScreenMode.title(
    profile: DeviceProfile?,
    editing: Boolean,
): String =
    when (this) {
        AndroidHomeScreenMode.List -> stringResource(R.string.screen_list_title)
        AndroidHomeScreenMode.Detail -> profile?.displayName ?: stringResource(R.string.screen_detail_fallback_title)
        AndroidHomeScreenMode.AddProfile -> stringResource(if (editing) R.string.screen_edit_title else R.string.screen_add_title)
        AndroidHomeScreenMode.LocalPairing -> stringResource(R.string.screen_pair_title)
        AndroidHomeScreenMode.Terminal -> stringResource(R.string.screen_terminal_title)
        AndroidHomeScreenMode.Visual -> stringResource(R.string.screen_visual_title)
        AndroidHomeScreenMode.Sftp -> stringResource(R.string.screen_files_title)
        AndroidHomeScreenMode.RemoteInput -> stringResource(R.string.screen_input_title)
        AndroidHomeScreenMode.Clipboard -> stringResource(R.string.screen_clipboard_title)
        AndroidHomeScreenMode.Relay -> stringResource(R.string.screen_relay_title)
    }

@Composable
internal fun AndroidHomeScreenMode.subtitle(): String =
    when (this) {
        AndroidHomeScreenMode.List -> stringResource(R.string.screen_list_subtitle)
        AndroidHomeScreenMode.Detail -> stringResource(R.string.screen_detail_subtitle)
        AndroidHomeScreenMode.AddProfile -> stringResource(R.string.screen_add_subtitle)
        AndroidHomeScreenMode.LocalPairing -> stringResource(R.string.screen_pair_subtitle)
        AndroidHomeScreenMode.Terminal -> stringResource(R.string.screen_terminal_subtitle)
        AndroidHomeScreenMode.Visual -> stringResource(R.string.screen_visual_subtitle)
        AndroidHomeScreenMode.Sftp -> stringResource(R.string.screen_files_subtitle)
        AndroidHomeScreenMode.RemoteInput -> stringResource(R.string.screen_input_subtitle)
        AndroidHomeScreenMode.Clipboard -> stringResource(R.string.screen_clipboard_subtitle)
        AndroidHomeScreenMode.Relay -> stringResource(R.string.screen_relay_subtitle)
    }

@Composable
internal fun AppHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    showLanguageSelector: Boolean,
) {
    var showLanguageDialog by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? MainActivity
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
            onBack?.let { goBack ->
                IconButton(onClick = goBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.back), tint = OnyxColors.OnSurface)
                }
            }
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                color = OnyxColors.OnSurfaceStrong,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
            Text(
                subtitle,
                color = OnyxColors.OnSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
            if (showLanguageSelector) {
                IconButton(onClick = { showLanguageDialog = true }) {
                    Icon(Icons.Outlined.Language, contentDescription = stringResource(R.string.language), tint = OnyxColors.OnSurface)
                }
            }
        }
    }
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            containerColor = OnyxColors.ContainerHigh,
            title = { Text(stringResource(R.string.language_dialog_title), color = OnyxColors.OnSurfaceStrong, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LanguageChoice(stringResource(R.string.language_system)) {
                        showLanguageDialog = false
                        activity?.selectLanguage("")
                    }
                    LanguageChoice(stringResource(R.string.language_spanish)) {
                        showLanguageDialog = false
                        activity?.selectLanguage("es")
                    }
                    LanguageChoice(stringResource(R.string.language_english)) {
                        showLanguageDialog = false
                        activity?.selectLanguage("en")
                    }
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
internal fun LanguageChoice(
    label: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ContainerHighest),
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, color = OnyxColors.OnSurface)
    }
}
