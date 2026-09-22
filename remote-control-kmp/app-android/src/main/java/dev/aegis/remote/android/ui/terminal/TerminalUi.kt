@file:Suppress("FunctionNaming")

package dev.aegis.remote.android.ui.terminal

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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun TerminalScreen(
    terminal: TerminalUiState,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    val outputScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val viewportSizer = remember { TerminalViewportSizer(minColumns = 80, minRows = 24) }
    val textBuffer = remember { TerminalTextBuffer() }
    val renderedOutput = remember(terminal.output) { textBuffer.render(terminal.output) }
    val terminalFont = remember { FontFamily(Font(R.font.jetbrains_mono_nerd_font)) }
    var terminalFontSize by rememberSaveable { mutableIntStateOf(DEFAULT_TERMINAL_FONT_SIZE_SP) }
    val cellWidthPx = with(density) { (terminalFontSize * TERMINAL_CELL_WIDTH_RATIO).sp.toPx() }
    val cellHeightPx = with(density) { (terminalFontSize * TERMINAL_CELL_HEIGHT_RATIO).sp.toPx() }
    var lastAutoPtyColumns by remember { mutableIntStateOf(0) }
    var lastAutoPtyRows by remember { mutableIntStateOf(0) }
    val terminalFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val terminalInteractionSource = remember { MutableInteractionSource() }
    var imeBuffer by remember { mutableStateOf("") }

    LaunchedEffect(renderedOutput) {
        outputScrollState.scrollTo(outputScrollState.maxValue)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        when {
                            terminal.connected -> OnyxColors.Primary
                            terminal.connecting -> OnyxColors.Warning
                            else -> OnyxColors.Outline
                        },
                        RoundedCornerShape(999.dp),
                    ),
            )
            Text(
                stringResource(R.string.terminal_auto_size, terminal.columns, terminal.rows),
                color = OnyxColors.Outline,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            TextButton(
                onClick = { terminalFontSize = (terminalFontSize - 1).coerceAtLeast(MIN_TERMINAL_FONT_SIZE_SP) },
                enabled = terminalFontSize > MIN_TERMINAL_FONT_SIZE_SP,
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) { Text("A−", fontSize = 12.sp) }
            TextButton(
                onClick = { terminalFontSize = (terminalFontSize + 1).coerceAtMost(MAX_TERMINAL_FONT_SIZE_SP) },
                enabled = terminalFontSize < MAX_TERMINAL_FONT_SIZE_SP,
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) { Text("A+", fontSize = 12.sp) }
            TextButton(
                onClick = {
                    if (renderedOutput.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(renderedOutput))
                    }
                },
                enabled = renderedOutput.isNotEmpty(),
            ) {
                Text(stringResource(R.string.terminal_copy), fontSize = 11.sp)
            }
            TextButton(
                onClick = { dispatch(AndroidHomeAction.ClearTerminalOutput) },
                enabled = terminal.output.isNotEmpty(),
            ) {
                Text(stringResource(R.string.terminal_clear), fontSize = 11.sp)
            }
        }
        Divider(color = OnyxColors.Hairline)
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(
                        enabled = terminal.connected && !terminal.connecting,
                        interactionSource = terminalInteractionSource,
                        indication = null,
                    ) {
                        terminalFocusRequester.requestFocus()
                        keyboardController?.show()
                    }.onSizeChanged { size ->
                        if (!terminal.connected || terminal.connecting || size.width <= 0 || size.height <= 0) {
                            return@onSizeChanged
                        }
                        val pty =
                            viewportSizer.calculate(
                                TerminalViewportMetrics(
                                    viewportWidthPx = size.width.toFloat(),
                                    viewportHeightPx = size.height.toFloat(),
                                    cellWidthPx = cellWidthPx,
                                    cellHeightPx = cellHeightPx,
                                ),
                            )
                        if (lastAutoPtyColumns != pty.columns || lastAutoPtyRows != pty.rows) {
                            lastAutoPtyColumns = pty.columns
                            lastAutoPtyRows = pty.rows
                            dispatch(AndroidHomeAction.AutoResizeTerminal(pty.columns, pty.rows))
                        }
                    },
        ) {
            SelectionContainer(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .horizontalScroll(horizontalScrollState)
                        .verticalScroll(outputScrollState)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text =
                        if (renderedOutput.isEmpty()) {
                            stringResource(R.string.terminal_empty)
                        } else if (terminal.connected) {
                            "$renderedOutput▌"
                        } else {
                            renderedOutput
                        },
                    color = if (renderedOutput.isEmpty()) OnyxColors.Outline else Color(0xFFD5E7D9),
                    fontFamily = terminalFont,
                    fontSize = terminalFontSize.sp,
                    lineHeight = (terminalFontSize * TERMINAL_LINE_HEIGHT_RATIO).sp,
                    softWrap = false,
                )
            }
            BasicTextField(
                value = imeBuffer,
                onValueChange = { next ->
                    dispatchTerminalImeDelta(imeBuffer, next, dispatch)
                    imeBuffer = next.replace("\r", "").replace("\n", "")
                },
                enabled = terminal.connected && !terminal.connecting,
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrect = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Send,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onSend = {
                            dispatch(AndroidHomeAction.SendTerminalKey(TerminalKeyStroke.Enter))
                            imeBuffer = ""
                        },
                        onGo = {
                            dispatch(AndroidHomeAction.SendTerminalKey(TerminalKeyStroke.Enter))
                            imeBuffer = ""
                        },
                        onDone = {
                            dispatch(AndroidHomeAction.SendTerminalKey(TerminalKeyStroke.Enter))
                            imeBuffer = ""
                        },
                    ),
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.Transparent),
                cursorBrush =
                    androidx.compose.ui.graphics
                        .SolidColor(Color.Transparent),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .focusRequester(terminalFocusRequester),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TerminalKeyButton("Ctrl+C", TerminalKeyStroke.Interrupt, terminal.connected, dispatch)
            TerminalKeyButton("Tab", TerminalKeyStroke.Tab, terminal.connected, dispatch)
            TerminalKeyButton("Esc", TerminalKeyStroke.Escape, terminal.connected, dispatch)
            TerminalKeyButton("←", TerminalKeyStroke.ArrowLeft, terminal.connected, dispatch)
            TerminalKeyButton("↑", TerminalKeyStroke.ArrowUp, terminal.connected, dispatch)
            TerminalKeyButton("↓", TerminalKeyStroke.ArrowDown, terminal.connected, dispatch)
            TerminalKeyButton("→", TerminalKeyStroke.ArrowRight, terminal.connected, dispatch)
            TerminalKeyButton("Ctrl+L", TerminalKeyStroke.ClearScreen, terminal.connected, dispatch)
            TerminalKeyButton("Ctrl+D", TerminalKeyStroke.EndOfFile, terminal.connected, dispatch)
        }
        Button(
            onClick = { dispatch(AndroidHomeAction.DisconnectTerminal) },
            colors = ButtonDefaults.buttonColors(containerColor = OnyxColors.ContainerHigh),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.terminal_disconnect), color = OnyxColors.Error)
        }
    }
}

private const val DEFAULT_TERMINAL_FONT_SIZE_SP = 12
private const val MIN_TERMINAL_FONT_SIZE_SP = 8
private const val MAX_TERMINAL_FONT_SIZE_SP = 22
private const val TERMINAL_CELL_WIDTH_RATIO = 0.60f
private const val TERMINAL_CELL_HEIGHT_RATIO = 1.42f
private const val TERMINAL_LINE_HEIGHT_RATIO = 1.42f

internal fun dispatchTerminalImeDelta(
    previous: String,
    next: String,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    val shared = previous.commonPrefixWith(next).length
    repeat(previous.length - shared) {
        dispatch(AndroidHomeAction.SendTerminalKey(TerminalKeyStroke.Backspace))
    }
    next.substring(shared).forEach { character ->
        when (character) {
            '\n', '\r' -> dispatch(AndroidHomeAction.SendTerminalKey(TerminalKeyStroke.Enter))
            else -> dispatch(AndroidHomeAction.SendTerminalText(character.toString()))
        }
    }
}

@Composable
internal fun TerminalKeyButton(
    label: String,
    key: TerminalKeyStroke,
    enabled: Boolean,
    dispatch: (AndroidHomeAction) -> Unit,
) {
    Button(
        onClick = { dispatch(AndroidHomeAction.SendTerminalKey(key)) },
        enabled = enabled,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = OnyxColors.ContainerHigh,
                contentColor = OnyxColors.OnSurface,
            ),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}
