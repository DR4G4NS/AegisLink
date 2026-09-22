@file:Suppress("FunctionNaming")

package dev.aegis.remote.android.ui.remote

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.outlined.Fullscreen
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
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalView
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import dev.aegis.remote.android.ui.components.LabelValue
import dev.aegis.remote.android.ui.components.labelRes
import dev.aegis.remote.android.ui.components.localizedStatusMessage
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
import dev.aegis.remote.core.model.QualityMode
import dev.aegis.remote.core.model.RouteDiagnostics
import dev.aegis.remote.core.model.WakeOnLanCapability
import dev.aegis.remote.core.sftp.SftpPath
import dev.aegis.remote.core.terminal.TerminalKeyStroke
import dev.aegis.remote.core.terminal.TerminalTextBuffer
import dev.aegis.remote.core.terminal.TerminalViewportMetrics
import dev.aegis.remote.core.terminal.TerminalViewportSizer
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel

@Composable
internal fun RemoteInputScreen(
    remoteInput: RemoteInputUiState,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = OnyxColors.ContainerLow),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.input_active),
                    color = OnyxColors.OnSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    stringResource(R.string.input_active_message),
                    color = OnyxColors.OnSurface,
                    fontSize = 13.sp,
                )
                remoteInput.message?.let {
                    Text(it.localizedStatusMessage(), color = OnyxColors.OnSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = remoteInput.x,
                onValueChange = { dispatch(AndroidHomeAction.UpdateRemoteInputX(it)) },
                label = { Text(stringResource(R.string.position_x)) },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = remoteInput.y,
                onValueChange = { dispatch(AndroidHomeAction.UpdateRemoteInputY(it)) },
                label = { Text(stringResource(R.string.position_y)) },
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = remoteInput.monitorId,
            onValueChange = { dispatch(AndroidHomeAction.UpdateRemoteInputMonitorId(it)) },
            label = { Text(stringResource(R.string.monitor)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { dispatch(AndroidHomeAction.PrepareMouseMovePayload) },
            enabled = !remoteInput.busy,
            colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.PrimaryContainer),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.move_pointer), color = OnyxColors.OnSurface)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { dispatch(AndroidHomeAction.PrepareLeftMouseDownPayload) },
                enabled = !remoteInput.busy,
                colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ContainerHigh),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.press), color = OnyxColors.OnSurface)
            }
            Button(
                onClick = { dispatch(AndroidHomeAction.PrepareLeftMouseUpPayload) },
                enabled = !remoteInput.busy,
                colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ContainerHigh),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.release), color = OnyxColors.OnSurface)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { dispatch(AndroidHomeAction.PrepareScrollUpPayload) },
                enabled = !remoteInput.busy,
                colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ContainerHigh),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.scroll_up), color = OnyxColors.OnSurface)
            }
            Button(
                onClick = { dispatch(AndroidHomeAction.PrepareScrollDownPayload) },
                enabled = !remoteInput.busy,
                colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ContainerHigh),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.scroll_down), color = OnyxColors.OnSurface)
            }
        }
        OutlinedTextField(
            value = remoteInput.text,
            onValueChange = { dispatch(AndroidHomeAction.UpdateRemoteInputText(it)) },
            label = { Text(stringResource(R.string.type_on_pc)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { dispatch(AndroidHomeAction.PrepareTextInputPayload) },
            enabled = !remoteInput.busy,
            colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.PrimaryContainer),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.send_text), color = OnyxColors.OnSurface)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            KeyPayloadButton(stringResource(R.string.enter_key), KeyCode.Enter, true, remoteInput.busy, dispatch, Modifier.weight(1f))
            KeyPayloadButton(stringResource(R.string.release_enter), KeyCode.Enter, false, remoteInput.busy, dispatch, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            KeyPayloadButton(
                stringResource(R.string.backspace_key),
                KeyCode.Backspace,
                null,
                remoteInput.busy,
                dispatch,
                Modifier.weight(1f),
            )
            KeyPayloadButton(
                stringResource(R.string.escape_key),
                KeyCode.Escape,
                null,
                remoteInput.busy,
                dispatch,
                Modifier.weight(1f),
            )
        }
        Button(
            onClick = { dispatch(AndroidHomeAction.CloseRemoteInput) },
            colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ContainerHigh),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.back_to_computer), color = OnyxColors.Error)
        }
    }
}

@Composable
internal fun KeyPayloadButton(
    label: String,
    key: KeyCode,
    pressed: Boolean?,
    busy: Boolean,
    dispatch: (AndroidHomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = {
            dispatch(
                pressed?.let { AndroidHomeAction.PrepareKeyPayload(key, it) }
                    ?: AndroidHomeAction.PrepareKeyTap(key),
            )
        },
        enabled = !busy,
        colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ContainerHigh),
        shape = RoundedCornerShape(999.dp),
        modifier = modifier,
    ) {
        Text(label, color = OnyxColors.OnSurface)
    }
}

@Composable
internal fun ClipboardScreen(
    clipboard: ClipboardUiState,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxSize()
                .imePadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = OnyxColors.Primary,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.privacy_section),
                    color = OnyxColors.OnSurface,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    stringResource(R.string.clipboard_privacy_message),
                    color = OnyxColors.OnSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
        Divider(color = OnyxColors.Hairline)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.clipboard_auto_sync), color = OnyxColors.OnSurface, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(if (clipboard.automaticSyncEnabled) R.string.clipboard_watching else R.string.disabled),
                    color = OnyxColors.OnSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = clipboard.automaticSyncEnabled,
                onCheckedChange = { dispatch(AndroidHomeAction.SetAutomaticClipboardSync(it)) },
                colors = onyxSwitchColors(),
            )
        }
        clipboard.lastAutomaticSyncText?.let {
            Text(stringResource(R.string.clipboard_last_sync, it.take(48)), color = OnyxColors.OnSurfaceVariant, fontSize = 12.sp)
        }
        clipboard.message?.let {
            Text(it.localizedStatusMessage(), color = OnyxColors.OnSurfaceVariant, fontSize = 13.sp)
        }
        OutlinedTextField(
            value = clipboard.text,
            onValueChange = { dispatch(AndroidHomeAction.UpdateClipboardText(it)) },
            label = { Text(stringResource(R.string.clipboard_text)) },
            minLines = 6,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { dispatch(AndroidHomeAction.ReadLocalClipboard) },
                enabled = !clipboard.busy,
                colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ContainerHigh),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.clipboard_read_phone), color = OnyxColors.OnSurface)
            }
            Button(
                onClick = { dispatch(AndroidHomeAction.WriteLocalClipboard) },
                enabled = !clipboard.busy,
                colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.PrimaryContainer),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.clipboard_copy_phone), color = OnyxColors.OnSurface)
            }
        }
        Button(
            onClick = { dispatch(AndroidHomeAction.PrepareClipboardDataChannelPayload) },
            enabled = !clipboard.busy,
            colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.PrimaryContainer),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.clipboard_send_pc), color = OnyxColors.OnSurface)
        }
    }
}

private enum class VisualPanel {
    Trackpad,
    Keyboard,
    Clipboard,
    Status,
}

@Composable
internal fun VisualScreen(
    visual: VisualUiState,
    remoteInput: RemoteInputUiState,
    clipboard: ClipboardUiState,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    if (visual.fullscreen) {
        ImmersiveVisualScreen(visual, remoteInput, dispatch)
        return
    }
    var panel by rememberSaveable { mutableStateOf(VisualPanel.Trackpad) }
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            VisualRenderer(inputEnabled = false, clipCorners = false, modifier = Modifier.fillMaxSize(), dispatch = dispatch)
            IconButton(
                onClick = { dispatch(AndroidHomeAction.SetVisualFullscreen(true)) },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(OnyxColors.ContainerLow, RoundedCornerShape(12.dp)),
            ) {
                Icon(Icons.Outlined.Fullscreen, contentDescription = stringResource(R.string.visual_fullscreen), tint = OnyxColors.OnSurfaceStrong)
            }
        }
        ConnectionTelemetryStrip(visual)
        RemoteControlTabs(selected = panel, onSelected = { panel = it })
        Divider(color = OnyxColors.Hairline)
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (panel) {
                VisualPanel.Trackpad -> {
                    if (visual.inputEnabled) {
                        TrackpadCard(enabled = visual.streaming, modifier = Modifier.fillMaxSize(), dispatch = dispatch)
                    } else {
                        InfoBanner(stringResource(R.string.visual_input_disabled_title), stringResource(R.string.visual_input_disabled_body), Icons.Outlined.Lock)
                    }
                }

                VisualPanel.Keyboard -> {
                    VisualKeyboardPanel(visual, remoteInput, dispatch)
                }

                VisualPanel.Clipboard -> {
                    VisualClipboardPanel(visual, clipboard, dispatch)
                }

                VisualPanel.Status -> {
                    VisualStatusPanel(visual, dispatch)
                }
            }
        }
    }
}

@Composable
@Suppress("LongMethod") // Fullscreen chrome, lifecycle, keyboard, and input share one immersive state owner.
private fun ImmersiveVisualScreen(
    visual: VisualUiState,
    remoteInput: RemoteInputUiState,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    val activity = LocalContext.current as? Activity
    val view = LocalView.current
    var keyboardVisible by rememberSaveable { mutableStateOf(false) }
    var dragLocked by rememberSaveable { mutableStateOf(false) }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    val latestDragLocked by rememberUpdatedState(dragLocked)
    LaunchedEffect(Unit) {
        delay(FULLSCREEN_CHROME_TIMEOUT_MILLIS)
        chromeVisible = false
    }
    DisposableEffect(activity, view) {
        val previousOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            if (latestDragLocked) dispatch(AndroidHomeAction.TrackpadSetButton(MouseButtonType.Left, false))
            activity?.requestedOrientation = previousOrientation
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                VisualRenderer(inputEnabled = false, clipCorners = false, modifier = Modifier.fillMaxSize(), dispatch = dispatch)
                TrackpadSurface(
                    enabled = visual.streaming && visual.inputEnabled,
                    dragLocked = dragLocked,
                    modifier = Modifier.fillMaxSize(),
                    transparent = true,
                    dispatch = dispatch,
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = chromeVisible,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it },
                    exit = fadeOut(tween(220)) + slideOutVertically(tween(260)) { -it },
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(OnyxColors.Background.copy(alpha = 0.94f))
                                .statusBarsPadding()
                                .padding(start = 64.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(R.string.screen_visual_title), color = OnyxColors.OnSurfaceStrong, style = MaterialTheme.typography.titleMedium)
                            Row(
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(99.dp))
                                        .background(OnyxColors.ContainerLow)
                                        .padding(horizontal = 10.dp, vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    visual.route?.localizedStatusMessage()
                                        ?: stringResource(if (visual.streaming) R.string.visual_streaming else R.string.visual_waiting),
                                    color = OnyxColors.Primary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                visual.stats?.let {
                                    Text("|", color = OnyxColors.OutlineVariant)
                                    Text(
                                        it.localizedStatusMessage(),
                                        color = OnyxColors.OnSurfaceVariant,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }
                IconButton(
                    onClick = { dispatch(AndroidHomeAction.SetVisualFullscreen(false)) },
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(12.dp)
                            .background(OnyxColors.Background.copy(alpha = 0.76f), RoundedCornerShape(14.dp)),
                ) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.back), tint = OnyxColors.OnSurfaceStrong)
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedVisibility(
                    visible = keyboardVisible,
                    enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(180)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(OnyxColors.ContainerLow.copy(alpha = 0.96f), RoundedCornerShape(18.dp)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = remoteInput.text,
                            onValueChange = { dispatch(AndroidHomeAction.UpdateRemoteInputText(it)) },
                            placeholder = { Text(stringResource(R.string.type_on_pc)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions =
                                KeyboardActions(
                                    onSend = { dispatch(AndroidHomeAction.SendVisualText) },
                                ),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { dispatch(AndroidHomeAction.SendVisualText) },
                            enabled = visual.streaming && remoteInput.text.isNotBlank(),
                        ) {
                            Icon(Icons.Outlined.AssignmentTurnedIn, contentDescription = stringResource(R.string.send_text), tint = OnyxColors.Primary)
                        }
                    }
                }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(OnyxColors.ContainerLow.copy(alpha = 0.96f), RoundedCornerShape(24.dp))
                            .border(1.dp, OnyxColors.Hairline, RoundedCornerShape(24.dp))
                            .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FullscreenDockButton(stringResource(R.string.visual_tab_trackpad), Icons.Outlined.TouchApp, active = !keyboardVisible, modifier = Modifier.weight(1f)) { keyboardVisible = false }
                    FullscreenDockDivider()
                    FullscreenDockButton(stringResource(R.string.visual_tab_keyboard), Icons.Outlined.Keyboard, active = keyboardVisible, modifier = Modifier.weight(1f)) { keyboardVisible = !keyboardVisible }
                    FullscreenDockDivider()
                    FullscreenDockButton(stringResource(R.string.trackpad_left_click), Icons.Outlined.Mouse, modifier = Modifier.weight(1f)) { dispatch(AndroidHomeAction.TrackpadClick(MouseButtonType.Left)) }
                    FullscreenDockDivider()
                    FullscreenDockButton(if (dragLocked) stringResource(R.string.trackpad_release_drag) else stringResource(R.string.trackpad_drag), Icons.Outlined.TouchApp, active = dragLocked, modifier = Modifier.weight(1f)) {
                        dragLocked = !dragLocked
                        dispatch(AndroidHomeAction.TrackpadSetButton(MouseButtonType.Left, dragLocked))
                    }
                    FullscreenDockDivider()
                    FullscreenDockButton(stringResource(R.string.trackpad_right_click), Icons.Outlined.Mouse, modifier = Modifier.weight(1f)) { dispatch(AndroidHomeAction.TrackpadClick(MouseButtonType.Right)) }
                }
            }
        }
    }
}

private const val FULLSCREEN_CHROME_TIMEOUT_MILLIS = 3_000L

@Composable
private fun FullscreenDockButton(
    label: String,
    icon: ImageVector,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (active) OnyxColors.PrimaryContainer else Color.Transparent),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (active) OnyxColors.Primary else OnyxColors.OnSurface)
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            color = if (active) OnyxColors.Primary else OnyxColors.OnSurface,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FullscreenDockDivider() {
    Divider(color = OnyxColors.OutlineVariant, modifier = Modifier.height(30.dp).width(1.dp))
}

@Composable
private fun ConnectionTelemetryStrip(visual: VisualUiState) {
    val values =
        listOfNotNull(
            "${stringResource(R.string.visual_connection)} · ${stringResource(if (visual.streaming) R.string.visual_streaming else R.string.visual_waiting)}",
            visual.route?.localizedStatusMessage(),
            visual.video?.localizedStatusMessage(),
            visual.stats?.localizedStatusMessage(),
        )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        values.forEachIndexed { index, value ->
            if (index > 0) Text("|", color = OnyxColors.OutlineVariant)
            Text(value, color = if (index == 0 && visual.streaming) OnyxColors.Primary else OnyxColors.OnSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun RemoteControlTabs(
    selected: VisualPanel,
    onSelected: (VisualPanel) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        VisualPanel.entries.forEach { item ->
            val label =
                when (item) {
                    VisualPanel.Trackpad -> stringResource(R.string.visual_tab_trackpad)
                    VisualPanel.Keyboard -> stringResource(R.string.visual_tab_keyboard)
                    VisualPanel.Clipboard -> stringResource(R.string.visual_tab_clipboard)
                    VisualPanel.Status -> stringResource(R.string.visual_tab_status)
                }
            val icon =
                when (item) {
                    VisualPanel.Trackpad -> Icons.Outlined.Mouse
                    VisualPanel.Keyboard -> Icons.Outlined.Keyboard
                    VisualPanel.Clipboard -> Icons.Outlined.ContentPaste
                    VisualPanel.Status -> Icons.Outlined.Visibility
                }
            val active = item == selected
            Column(
                modifier = Modifier.weight(1f).clickable { onSelected(item) }.padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = if (active) OnyxColors.Primary else OnyxColors.OnSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    color = if (active) OnyxColors.Primary else OnyxColors.OnSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(9.dp))
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) OnyxColors.Primary else Color.Transparent))
            }
        }
    }
}

@Composable
private fun VisualKeyboardPanel(
    visual: VisualUiState,
    remoteInput: RemoteInputUiState,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = remoteInput.text,
            onValueChange = { dispatch(AndroidHomeAction.UpdateRemoteInputText(it)) },
            label = { Text(stringResource(R.string.type_on_pc)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions =
                KeyboardActions(
                    onSend = { dispatch(AndroidHomeAction.SendVisualText) },
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { dispatch(AndroidHomeAction.SendVisualText) },
            enabled = visual.streaming && !remoteInput.busy && remoteInput.text.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.PrimaryContainer),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.send_text))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VisualKeyTapButton(stringResource(R.string.enter_key), KeyCode.Enter, visual.streaming, dispatch, Modifier.weight(1f))
            VisualKeyTapButton(stringResource(R.string.backspace_key), KeyCode.Backspace, visual.streaming, dispatch, Modifier.weight(1f))
            VisualKeyTapButton(stringResource(R.string.escape_key), KeyCode.Escape, visual.streaming, dispatch, Modifier.weight(1f))
        }
        remoteInput.message?.let { Text(it.localizedStatusMessage(), color = OnyxColors.OnSurfaceVariant, fontSize = 12.sp) }
    }
}

@Composable
private fun VisualClipboardPanel(
    visual: VisualUiState,
    clipboard: ClipboardUiState,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = clipboard.text, onValueChange = { dispatch(AndroidHomeAction.UpdateClipboardText(it)) }, label = { Text(stringResource(R.string.clipboard_text)) }, minLines = 3, modifier = Modifier.fillMaxWidth().weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { dispatch(AndroidHomeAction.ReadLocalClipboard) }, enabled = !clipboard.busy, colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.Container), shape = RoundedCornerShape(999.dp), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.clipboard_read_phone)) }
            Button(onClick = { dispatch(AndroidHomeAction.PrepareClipboardDataChannelPayload) }, enabled = visual.streaming && !clipboard.busy, colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.PrimaryContainer), shape = RoundedCornerShape(999.dp), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.clipboard_send_pc)) }
        }
        clipboard.message?.let { Text(it.localizedStatusMessage(), color = OnyxColors.OnSurfaceVariant, fontSize = 12.sp) }
    }
}

@Composable
private fun VisualStatusPanel(
    visual: VisualUiState,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.visual_quality_section), color = OnyxColors.OnSurface, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.visual_quality_hint),
            color = OnyxColors.OnSurfaceVariant,
            fontSize = 12.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QualityMode.entries.forEach { mode ->
                StreamingQualityOption(stringResource(mode.labelRes()), mode, visual, dispatch)
            }
        }
        Divider(color = OnyxColors.Hairline)
        visual.videoState?.let { LabelValue(stringResource(R.string.visual_connection), it.localizedStatusMessage()) }
        visual.route?.let { LabelValue(stringResource(R.string.visual_route), it.localizedStatusMessage()) }
        visual.video?.let { LabelValue(stringResource(R.string.visual_quality), it.localizedStatusMessage()) }
        visual.stats?.let { LabelValue(stringResource(R.string.visual_live_status), it.localizedStatusMessage()) }
        visual.monitors?.let { LabelValue(stringResource(R.string.visual_monitors), it.localizedStatusMessage()) }
        visual.message?.let { Text(it.localizedStatusMessage(), color = OnyxColors.OnSurfaceVariant, fontSize = 12.sp) }
        Button(onClick = { dispatch(AndroidHomeAction.PrepareVisualSession) }, enabled = !visual.busy, colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.PrimaryContainer), shape = RoundedCornerShape(999.dp), modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (visual.busy) R.string.relay_connecting else R.string.visual_reconnect)) }
        Button(onClick = { dispatch(AndroidHomeAction.CloseVisual) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = OnyxColors.Error), shape = RoundedCornerShape(999.dp), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.visual_close)) }
    }
}

@Composable
private fun StreamingQualityOption(
    label: String,
    mode: QualityMode,
    visual: VisualUiState,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    val selected = visual.qualityMode == mode
    Button(
        onClick = { dispatch(AndroidHomeAction.SetStreamingQuality(mode)) },
        enabled = visual.streaming && !visual.qualityChanging,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (selected) OnyxColors.PrimaryContainer else OnyxColors.ContainerHigh,
                contentColor = if (selected) OnyxColors.Primary else OnyxColors.OnSurface,
            ),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
internal fun TrackpadCard(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    var dragLocked by remember { mutableStateOf(false) }
    val latestDragLocked by rememberUpdatedState(dragLocked)
    LaunchedEffect(enabled) {
        if (!enabled && dragLocked) {
            dispatch(AndroidHomeAction.TrackpadSetButton(MouseButtonType.Left, pressed = false))
            dragLocked = false
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (latestDragLocked) {
                dispatch(AndroidHomeAction.TrackpadSetButton(MouseButtonType.Left, pressed = false))
            }
        }
    }
    Column(
        modifier = modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .background(OnyxColors.PrimaryContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Mouse,
                    contentDescription = null,
                    tint = OnyxColors.OnPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.trackpad_title),
                    color = OnyxColors.OnSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.trackpad_subtitle),
                    color = OnyxColors.OnSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (enabled) OnyxColors.Primary else OnyxColors.Outline,
                        RoundedCornerShape(999.dp),
                    ),
            )
        }
        TrackpadSurface(
            enabled = enabled,
            dragLocked = dragLocked,
            modifier = Modifier.fillMaxWidth().weight(1f),
            dispatch = dispatch,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TrackpadButton(
                label = stringResource(R.string.trackpad_left_click),
                enabled = enabled && !dragLocked,
                active = false,
                modifier = Modifier.weight(1f),
            ) {
                dispatch(AndroidHomeAction.TrackpadClick(MouseButtonType.Left))
            }
            TrackpadButton(
                label =
                    stringResource(
                        if (dragLocked) R.string.trackpad_release_drag else R.string.trackpad_drag,
                    ),
                enabled = enabled,
                active = dragLocked,
                modifier = Modifier.weight(1f),
            ) {
                dragLocked = !dragLocked
                dispatch(AndroidHomeAction.TrackpadSetButton(MouseButtonType.Left, dragLocked))
            }
            TrackpadButton(
                label = stringResource(R.string.trackpad_right_click),
                enabled = enabled,
                active = false,
                modifier = Modifier.weight(1f),
            ) {
                dispatch(AndroidHomeAction.TrackpadClick(MouseButtonType.Right))
            }
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod") // Ordered one/two-finger gesture state machine plus transparent presentation.
internal fun TrackpadSurface(
    enabled: Boolean,
    dragLocked: Boolean,
    modifier: Modifier = Modifier,
    transparent: Boolean = false,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    Box(
        modifier =
            modifier
                .then(
                    if (transparent) {
                        Modifier
                    } else {
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF101512))
                            .border(
                                1.dp,
                                if (enabled) OnyxColors.Primary.copy(alpha = 0.45f) else OnyxColors.OutlineVariant,
                                RoundedCornerShape(14.dp),
                            )
                    },
                ).pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        dispatch(AndroidHomeAction.ResetTrackpadGesture)
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var lastX = down.position.x
                        var lastY = down.position.y
                        val startX = down.position.x
                        val startY = down.position.y
                        var previousCentroidX = lastX
                        var previousCentroidY = lastY
                        var twoFingerStartX = lastX
                        var twoFingerStartY = lastY
                        var moved = false
                        var usedTwoFingers = false
                        val touchSlopSquared = viewConfiguration.touchSlop * viewConfiguration.touchSlop

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            when {
                                pressed.size >= 2 -> {
                                    val centroidX = pressed.map { it.position.x }.average().toFloat()
                                    val centroidY = pressed.map { it.position.y }.average().toFloat()
                                    if (!usedTwoFingers) {
                                        usedTwoFingers = true
                                        twoFingerStartX = centroidX
                                        twoFingerStartY = centroidY
                                        previousCentroidX = centroidX
                                        previousCentroidY = centroidY
                                    } else {
                                        val deltaX = centroidX - previousCentroidX
                                        val deltaY = centroidY - previousCentroidY
                                        val totalX = centroidX - twoFingerStartX
                                        val totalY = centroidY - twoFingerStartY
                                        if (!moved && totalX * totalX + totalY * totalY > touchSlopSquared) {
                                            moved = true
                                        }
                                        if (moved && (deltaX != 0f || deltaY != 0f)) {
                                            dispatch(AndroidHomeAction.TrackpadScroll(deltaX, deltaY))
                                        }
                                        previousCentroidX = centroidX
                                        previousCentroidY = centroidY
                                    }
                                    event.changes.forEach { it.consume() }
                                }

                                pressed.size == 1 && !usedTwoFingers -> {
                                    val change = pressed.first()
                                    val deltaX = change.position.x - lastX
                                    val deltaY = change.position.y - lastY
                                    val totalX = change.position.x - startX
                                    val totalY = change.position.y - startY
                                    if (!moved && totalX * totalX + totalY * totalY > touchSlopSquared) {
                                        moved = true
                                    }
                                    if (moved && (deltaX != 0f || deltaY != 0f)) {
                                        dispatch(AndroidHomeAction.TrackpadMove(deltaX, deltaY))
                                        change.consume()
                                    }
                                    lastX = change.position.x
                                    lastY = change.position.y
                                }
                            }

                            if (event.changes.none { it.pressed }) {
                                if (!moved && (usedTwoFingers || !dragLocked)) {
                                    dispatch(
                                        AndroidHomeAction.TrackpadClick(
                                            if (usedTwoFingers) MouseButtonType.Right else MouseButtonType.Left,
                                        ),
                                    )
                                }
                                dispatch(AndroidHomeAction.ResetTrackpadGesture)
                                break
                            }
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        if (!transparent) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.Mouse,
                    contentDescription = null,
                    tint = if (enabled) OnyxColors.Primary else OnyxColors.Outline,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.trackpad_hint),
                    color = OnyxColors.OnSurfaceVariant,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@Composable
internal fun TrackpadButton(
    label: String,
    enabled: Boolean,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (active) OnyxColors.PrimaryContainer else OnyxColors.ContainerHigh,
                contentColor = OnyxColors.OnSurface,
            ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Text(label, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun VisualKeyTapButton(
    label: String,
    key: KeyCode,
    enabled: Boolean,
    dispatch: (AndroidHomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = { dispatch(AndroidHomeAction.VisualKeyTap(key)) },
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ContainerHigh),
        shape = RoundedCornerShape(999.dp),
        modifier = modifier,
    ) {
        Text(label, color = OnyxColors.OnSurface, fontSize = 11.sp)
    }
}

@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming") // Gesture modes are one ordered pointer state machine.
internal fun VisualRenderer(
    inputEnabled: Boolean,
    modifier: Modifier = Modifier,
    clipCorners: Boolean = false,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    val eglBase = remember { EglBase.create() }
    val rendererRef = remember { arrayOfNulls<SurfaceViewRenderer>(1) }
    // SurfaceViewRenderer only honors SCALE_ASPECT_FIT when it can self-measure.
    // Compose measures AndroidView with exact constraints, which makes the
    // renderer crop the video to fill the view instead. Track the incoming
    // frame aspect ratio and size the view to match it so the full remote
    // screen stays visible (letterboxed) and tap coordinates map 1:1.
    var frameAspect by remember { mutableStateOf(0f) }
    DisposableEffect(Unit) {
        onDispose {
            rendererRef[0]?.let { renderer ->
                dispatch(AndroidHomeAction.DetachVisualRenderer(renderer))
                renderer.release()
                rendererRef[0] = null
            }
            eglBase.release()
        }
    }
    Box(
        modifier =
            modifier
                .then(if (clipCorners) Modifier.clip(RoundedCornerShape(12.dp)) else Modifier)
                .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .then(if (frameAspect > 0f) Modifier.aspectRatio(frameAspect) else Modifier.fillMaxSize())
                    .pointerInput(inputEnabled) {
                        if (!inputEnabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var lastPosition = down.position
                            var moved = false
                            var multiTouch = false
                            var previousCentroidY = down.position.y
                            val touchSlopSquared = viewConfiguration.touchSlop * viewConfiguration.touchSlop

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    val centroidY = pressed.map { it.position.y }.average().toFloat()
                                    if (!multiTouch) {
                                        moved = false
                                        multiTouch = true
                                        previousCentroidY = centroidY
                                    } else {
                                        val deltaY = centroidY - previousCentroidY
                                        if (kotlin.math.abs(deltaY) >= 8f) {
                                            dispatch(AndroidHomeAction.VisualPointerScroll(if (deltaY < 0f) 1f else -1f))
                                            previousCentroidY = centroidY
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (!multiTouch && event.changes.isNotEmpty()) {
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                                    val currentPosition = change.position
                                    val deltaX = currentPosition.x - down.position.x
                                    val deltaY = currentPosition.y - down.position.y
                                    if (!moved && deltaX * deltaX + deltaY * deltaY > touchSlopSquared) {
                                        moved = true
                                    }
                                    if (moved && change.pressed) {
                                        val motionX = currentPosition.x - lastPosition.x
                                        val motionY = currentPosition.y - lastPosition.y
                                        if (motionX != 0f || motionY != 0f) {
                                            dispatch(AndroidHomeAction.TrackpadMove(motionX, motionY))
                                        }
                                        change.consume()
                                    }
                                    lastPosition = currentPosition
                                }

                                if (event.changes.none { it.pressed }) {
                                    if (!moved && !multiTouch) {
                                        dispatch(
                                            AndroidHomeAction.VisualPointerTap(
                                                VisualPointerPosition(
                                                    lastPosition.x,
                                                    lastPosition.y,
                                                    size.width.toFloat(),
                                                    size.height.toFloat(),
                                                ),
                                            ),
                                        )
                                    }
                                    break
                                }
                            }
                        }
                    },
        ) {
            AndroidView(
                factory = { context ->
                    SurfaceViewRenderer(context).apply {
                        init(
                            eglBase.eglBaseContext,
                            object : RendererCommon.RendererEvents {
                                override fun onFirstFrameRendered() = Unit

                                override fun onFrameResolutionChanged(
                                    videoWidth: Int,
                                    videoHeight: Int,
                                    rotation: Int,
                                ) {
                                    val rotated = rotation == 90 || rotation == 270
                                    val width = if (rotated) videoHeight else videoWidth
                                    val height = if (rotated) videoWidth else videoHeight
                                    if (width > 0 && height > 0) {
                                        frameAspect = width.toFloat() / height.toFloat()
                                    }
                                }
                            },
                        )
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        setEnableHardwareScaler(true)
                        rendererRef[0] = this
                        dispatch(AndroidHomeAction.AttachVisualRenderer(this))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
