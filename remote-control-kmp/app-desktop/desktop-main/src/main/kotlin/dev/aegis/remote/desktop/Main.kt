package dev.aegis.remote.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Shapes
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.Typography
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.aegis.remote.core.model.DeviceAuthorization
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.desktop.agent.AegisOpenSshManager
import dev.aegis.remote.desktop.agent.AgentLogEntry
import dev.aegis.remote.desktop.agent.CapabilityStatus
import dev.aegis.remote.desktop.agent.DesktopAgent
import dev.aegis.remote.desktop.agent.DesktopAgentFactory
import dev.aegis.remote.desktop.agent.DesktopAgentState
import dev.aegis.remote.desktop.agent.DesktopPairingRequest
import dev.aegis.remote.desktop.agent.DesktopRelaySessionRequest
import dev.aegis.remote.desktop.agent.DeviceRecordActionOutcome
import dev.aegis.remote.desktop.agent.DeviceRecordActionResult
import dev.aegis.remote.desktop.agent.KtorLocalPairingServer
import dev.aegis.remote.desktop.agent.LinuxAegisOpenSshManager
import dev.aegis.remote.desktop.agent.LinuxPreflightCapability
import dev.aegis.remote.desktop.agent.LinuxPreflightStatus
import dev.aegis.remote.desktop.agent.LinuxUfwApplyResult
import dev.aegis.remote.desktop.agent.LinuxUfwInspection
import dev.aegis.remote.desktop.agent.LinuxUfwOnboardingManager
import dev.aegis.remote.desktop.agent.ManualConnectionValue
import dev.aegis.remote.desktop.agent.ManualConnectionValueStatus
import dev.aegis.remote.desktop.agent.RelayConfigurationMessageCode
import dev.aegis.remote.desktop.agent.WindowsAegisOpenSshProvisioner
import dev.aegis.remote.desktop.agent.WindowsPreflightCapability
import dev.aegis.remote.desktop.agent.WindowsPreflightStatus
import dev.aegis.remote.desktop.agent.localizedAutostartMessage
import dev.aegis.remote.desktop.agent.localizedMessage
import dev.aegis.remote.desktop.agent.localizedRelayConfigurationMessage
import dev.aegis.remote.desktop.agent.locateWindowsOpenSshScriptDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.Closeable
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.concurrent.thread

fun main() {
    LinuxDesktopEnvironment.prepare()
    val instanceCoordinator = SingleInstanceCoordinator.acquireOrActivate() ?: return

    try {
        application {
            val agent =
                remember {
                    DesktopAgentFactory.production(
                        pairingServer = KtorLocalPairingServer(),
                        openSshProvisioner = packagedAegisOpenSshManager(),
                    )
                }
            val state by agent.state.collectAsState()
            val windowState = rememberWindowState(width = 1120.dp, height = 760.dp)
            var windowVisible by remember { mutableStateOf(true) }
            var selectedSection by remember { mutableStateOf(DesktopSection.Home) }
            var foregroundRequest by remember { mutableStateOf(0) }
            var language by remember { mutableStateOf(DesktopLanguageStore.load()) }
            val zoomPreferences = remember { DesktopZoomPreferences() }
            var zoomPercent by remember { mutableStateOf(zoomPreferences.load()) }
            val isMac = remember { System.getProperty("os.name", "").contains("Mac", ignoreCase = true) }
            val appIcon = painterResource("icons/aegis-app-icon.png")

            fun changeZoom(action: DesktopZoomAction) {
                zoomPercent = zoomPreferences.applyAction(zoomPercent, action)
            }

            LaunchedEffect(agent) {
                agent.start()
            }
            DisposableEffect(agent) {
                onDispose { agent.stop() }
            }
            DisposableEffect(instanceCoordinator) {
                instanceCoordinator.setActivationHandler {
                    EventQueue.invokeLater {
                        windowVisible = true
                        foregroundRequest += 1
                    }
                }
                onDispose { instanceCoordinator.setActivationHandler(null) }
            }

            val approvalSignal =
                remember(state.pendingPairingRequests, state.pendingRelaySessions) {
                    buildString {
                        append(state.pendingPairingRequests.joinToString(",") { it.requestId })
                        append('|')
                        append(state.pendingRelaySessions.joinToString(",") { it.sessionId.value })
                    }.takeIf { it != "|" }.orEmpty()
                }
            var lastApprovalSignal by remember { mutableStateOf("") }
            LaunchedEffect(approvalSignal) {
                if (approvalSignal.isNotBlank() && approvalSignal != lastApprovalSignal) {
                    windowVisible = true
                    selectedSection =
                        if (state.pendingPairingRequests.isNotEmpty()) {
                            DesktopSection.Home
                        } else {
                            DesktopSection.Activity
                        }
                    foregroundRequest += 1
                }
                lastApprovalSignal = approvalSignal
            }

            LaunchedEffect(state.trustStoreRecoveryRequired) {
                if (state.trustStoreRecoveryRequired) {
                    windowVisible = true
                    selectedSection = DesktopSection.Settings
                    foregroundRequest += 1
                }
            }

            fun exitAegis() {
                agent.stop()
                exitApplication()
            }

            fun showMainWindow(section: DesktopSection? = null) {
                section?.let { selectedSection = it }
                windowVisible = true
                foregroundRequest += 1
            }

            var trayMenuAnchor by remember { mutableStateOf<java.awt.Point?>(null) }
            LaunchedEffect(Unit) {
                if (System.getenv("AEGIS_DEBUG_TRAY_MENU") == "1") {
                    delay(4_000)
                    trayMenuAnchor = java.awt.Point(1500, 900)
                }
            }
            var trayCopiedAt by remember { mutableStateOf(0L) }
            val trayImage = remember { loadTrayImage() }
            val trayAvailable =
                if (isTraySupported && trayImage != null) {
                    rememberAegisTrayIcon(
                        icon = trayImage,
                        tooltip = if (state.pairingServerRunning) language.text("tray.ready") else language.text("tray.default"),
                        onPrimaryClick = { EventQueue.invokeLater { showMainWindow() } },
                        onOpenMenu = { point -> EventQueue.invokeLater { trayMenuAnchor = point } },
                    )
                } else {
                    false
                }

            trayMenuAnchor?.let { anchor ->
                val pendingCount = state.pendingPairingRequests.size + state.pendingRelaySessions.size
                val pairedCount = state.authorizedDevices.count { it.revokedAtEpochMillis == null }
                val entries =
                    buildList {
                        add(
                            TrayMenuEntry.Header(
                                title = "Aegis Remote Desktop",
                                subtitle =
                                    if (state.pairingServerRunning) {
                                        language.text("tray.menu.status.ready")
                                    } else {
                                        language.text("tray.menu.status.starting")
                                    },
                                ready = state.pairingServerRunning,
                            ),
                        )
                        add(
                            TrayMenuEntry.Info(
                                when (pairedCount) {
                                    0 -> language.text("tray.menu.devices.none")
                                    1 -> language.text("tray.menu.devices.one")
                                    else -> language.text("tray.menu.devices.many").format(pairedCount)
                                },
                            ),
                        )
                        if (pendingCount > 0) {
                            add(
                                TrayMenuEntry.Info(
                                    if (pendingCount == 1) {
                                        language.text("tray.menu.pending.one")
                                    } else {
                                        language.text("tray.menu.pending.many").format(pendingCount)
                                    },
                                    accent = true,
                                ),
                            )
                        }
                        add(TrayMenuEntry.Divider)
                        add(
                            TrayMenuEntry.Action(
                                label = if (windowVisible) language.text("tray.menu.hide") else language.text("tray.menu.open"),
                                onClick = { if (windowVisible) windowVisible = false else showMainWindow() },
                            ),
                        )
                        add(TrayMenuEntry.Action(language.text("tray.menu.devices_section"), onClick = { showMainWindow(DesktopSection.Devices) }))
                        add(
                            TrayMenuEntry.Action(
                                language.text("tray.menu.activity"),
                                onClick = { showMainWindow(DesktopSection.Activity) },
                                trailing = pendingCount.takeIf { it > 0 }?.toString(),
                            ),
                        )
                        add(TrayMenuEntry.Action(language.text("tray.menu.settings"), onClick = { showMainWindow(DesktopSection.Settings) }))
                        add(TrayMenuEntry.Divider)
                        add(TrayMenuEntry.Toggle(language.text("tray.menu.remote_input"), state.remoteInputEnabled, agent::setRemoteInputEnabled))
                        add(TrayMenuEntry.Toggle(language.text("tray.menu.clipboard"), state.clipboardSyncEnabled, agent::setClipboardSyncEnabled))
                        add(TrayMenuEntry.Divider)
                        add(
                            TrayMenuEntry.Action(
                                language.text("tray.menu.refresh_qr"),
                                onClick = agent::refreshPairingQr,
                                keepOpen = true,
                            ),
                        )
                        state.pairingCode?.let { code ->
                            val copiedRecently = System.currentTimeMillis() - trayCopiedAt < 2_000
                            add(
                                TrayMenuEntry.Action(
                                    language.text("tray.menu.copy_code"),
                                    onClick = {
                                        copyToSystemClipboard(code)
                                        trayCopiedAt = System.currentTimeMillis()
                                    },
                                    trailing = if (copiedRecently) language.text("tray.menu.copied") else null,
                                    keepOpen = true,
                                ),
                            )
                        }
                        add(TrayMenuEntry.Divider)
                        add(TrayMenuEntry.Action(language.text("tray.menu.exit"), onClick = ::exitAegis, destructive = true))
                    }
                TrayMenuWindow(
                    anchor = anchor,
                    entries = entries,
                    zoomPercent = zoomPercent,
                    onDismiss = { trayMenuAnchor = null },
                )
            }

            if (windowVisible) {
                Window(
                    onCloseRequest = { exitAegis() },
                    title = "Aegis Remote Desktop",
                    state = windowState,
                    icon = appIcon,
                ) {
                    val baseDensity = LocalDensity.current
                    val rootFocusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) {
                        rootFocusRequester.requestFocus()
                        window.minimumSize = Dimension(720, 520)
                        window.background = java.awt.Color(0, 0, 0)
                        applyDarkWindowsFrame(window)
                    }
                    LaunchedEffect(foregroundRequest) {
                        if (foregroundRequest > 0) {
                            window.extendedState = Frame.NORMAL
                            window.isVisible = true
                            window.toFront()
                            window.requestFocus()
                        }
                    }

                    val effectiveDensity = DesktopZoomPreferences.effectiveDensity(baseDensity, zoomPercent)
                    CompositionLocalProvider(LocalDensity provides effectiveDensity) {
                        OnyxTheme {
                            CompositionLocalProvider(LocalDesktopLanguage provides language) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .focusRequester(rootFocusRequester)
                                            .focusable()
                                            .onPreviewKeyEvent { event ->
                                                if (event.type != KeyEventType.KeyDown) {
                                                    false
                                                } else {
                                                    desktopZoomActionForShortcut(
                                                        key = desktopZoomShortcutKey(event.key),
                                                        ctrlPressed = event.isCtrlPressed,
                                                        metaPressed = event.isMetaPressed,
                                                        isMac = isMac,
                                                    )?.let { action ->
                                                        changeZoom(action)
                                                        true
                                                    } ?: false
                                                }
                                            },
                                ) {
                                    DesktopAgentApp(
                                        state = state,
                                        selectedSection = selectedSection,
                                        onSelectSection = { selectedSection = it },
                                        approve = agent::approvePairing,
                                        reject = agent::rejectPairing,
                                        refreshPairingQr = agent::refreshPairingQr,
                                        revoke = agent::revoke,
                                        updateDevicePermissions = agent::updateDevicePermissions,
                                        retrySshKeyRemoval = agent::retrySshKeyRemoval,
                                        deleteRecord = agent::deleteRevokedDevice,
                                        approveRelay = agent::approveRelaySession,
                                        rejectRelay = agent::rejectRelaySession,
                                        setRemoteAccess = agent::setRemoteAccessEnabled,
                                        setRemoteInput = agent::setRemoteInputEnabled,
                                        setClipboardSync = agent::setClipboardSyncEnabled,
                                        setAutostart = agent::setAutostartEnabled,
                                        configureRelay = agent::configureRelay,
                                        clearRelay = agent::clearRelayConfiguration,
                                        rotateRelayIdentity = agent::rotateRelayIdentity,
                                        restoreTrustStoreFromBackup = agent::restoreTrustStoreFromBackup,
                                        resetTrustStoreAfterConsent = agent::resetTrustStoreAfterConsent,
                                        zoomPercent = zoomPercent,
                                        decreaseZoom = { changeZoom(DesktopZoomAction.Decrease) },
                                        increaseZoom = { changeZoom(DesktopZoomAction.Increase) },
                                        resetZoom = { changeZoom(DesktopZoomAction.Reset) },
                                        language = language,
                                        setLanguage = {
                                            language = it
                                            DesktopLanguageStore.save(it)
                                        },
                                        exitAegis = ::exitAegis,
                                        trayAvailable = trayAvailable,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            state.pendingPairingRequests.firstOrNull()?.let { request ->
                val approvalScale = zoomPercent / 100f
                val approvalWidth = (560 * approvalScale).dp
                val approvalHeight = (420 * approvalScale).dp
                Window(
                    onCloseRequest = { agent.rejectPairing(request.requestId) },
                    title = language.text("approval.required"),
                    state = rememberWindowState(width = approvalWidth, height = approvalHeight),
                    icon = appIcon,
                    resizable = false,
                    alwaysOnTop = true,
                ) {
                    val approvalBaseDensity = LocalDensity.current
                    LaunchedEffect(request.requestId, zoomPercent) {
                        window.minimumSize =
                            Dimension(
                                (560 * approvalScale).toInt(),
                                (420 * approvalScale).toInt(),
                            )
                        window.isVisible = true
                        window.toFront()
                        window.requestFocus()
                    }
                    val approvalDensity = DesktopZoomPreferences.effectiveDensity(approvalBaseDensity, zoomPercent)
                    CompositionLocalProvider(LocalDensity provides approvalDensity) {
                        OnyxTheme {
                            CompositionLocalProvider(LocalDesktopLanguage provides language) {
                                StandalonePairingApproval(
                                    request = request,
                                    approve = agent::approvePairing,
                                    reject = agent::rejectPairing,
                                )
                            }
                        }
                    }
                }
            }
        }
    } finally {
        instanceCoordinator.close()
    }
}

private fun loadTrayImage(): BufferedImage? =
    runCatching {
        Thread
            .currentThread()
            .contextClassLoader
            .getResourceAsStream("icons/aegis-tray-icon.png")
            ?.use(ImageIO::read)
    }.getOrNull()

internal fun copyToSystemClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

internal fun packagedAegisOpenSshManager(): AegisOpenSshManager? {
    val osName = System.getProperty("os.name", "")
    if (osName.contains("Linux", ignoreCase = true)) return LinuxAegisOpenSshManager()
    if (!osName.contains("Windows", ignoreCase = true)) return null

    val scriptDirectory = locateWindowsOpenSshScriptDirectory() ?: return null
    return WindowsAegisOpenSshProvisioner(scriptDirectory)
}

@Composable
internal fun OnyxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors =
            darkColors(
                primary = Onyx.Primary,
                primaryVariant = Onyx.PrimaryContainer,
                secondary = Onyx.Secondary,
                background = Onyx.Background,
                surface = Onyx.ContainerLow,
                error = Onyx.Error,
                onPrimary = Onyx.OnPrimary,
                onSecondary = Onyx.OnSecondary,
                onBackground = Onyx.OnSurface,
                onSurface = Onyx.OnSurface,
                onError = Onyx.OnSurface,
            ),
        typography =
            Typography(
                defaultFontFamily = FontFamily.SansSerif,
                h5 = TextStyle(fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
                h6 = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
                body1 = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                body2 = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                button = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
                caption = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            ),
        shapes =
            Shapes(
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(22.dp),
            ),
        content = content,
    )
}

@Composable
private fun t(key: String): String = LocalDesktopLanguage.current.text(key)

@Composable
private fun tf(
    key: String,
    vararg values: Any,
): String = t(key).format(*values)

@Composable
@Suppress("FunctionNaming", "LongParameterList", "LongMethod")
private fun DesktopAgentApp(
    state: DesktopAgentState,
    selectedSection: DesktopSection,
    onSelectSection: (DesktopSection) -> Unit,
    approve: (String) -> Unit,
    reject: (String) -> Unit,
    refreshPairingQr: () -> Unit,
    revoke: (String) -> Unit,
    updateDevicePermissions: (String, DevicePermissions) -> Unit,
    retrySshKeyRemoval: (String) -> Unit,
    deleteRecord: (String) -> Unit,
    approveRelay: (String) -> Unit,
    rejectRelay: (String) -> Unit,
    setRemoteAccess: (Boolean) -> Unit,
    setRemoteInput: (Boolean) -> Unit,
    setClipboardSync: (Boolean) -> Unit,
    setAutostart: (Boolean) -> Unit,
    configureRelay: (String, String) -> Unit,
    clearRelay: () -> Unit,
    rotateRelayIdentity: () -> Unit,
    restoreTrustStoreFromBackup: (String) -> Unit,
    resetTrustStoreAfterConsent: () -> Unit,
    zoomPercent: Int,
    decreaseZoom: () -> Unit,
    increaseZoom: () -> Unit,
    resetZoom: () -> Unit,
    language: DesktopLanguage,
    setLanguage: (DesktopLanguage) -> Unit,
    exitAegis: () -> Unit,
    trayAvailable: Boolean = true,
) {
    var revokeCandidate by remember { mutableStateOf<DeviceAuthorization?>(null) }
    var deleteCandidate by remember { mutableStateOf<DeviceAuthorization?>(null) }
    var deviceActionToastVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.lastDeviceRecordAction?.timestampEpochMillis) {
        if (state.lastDeviceRecordAction != null) {
            deviceActionToastVisible = true
            delay(DEVICE_ACTION_TOAST_MILLIS)
            deviceActionToastVisible = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Onyx.Background,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compactSidebar = maxWidth < COMPACT_SIDEBAR_BREAKPOINT
            val contentPadding = if (maxWidth < 1_000.dp) 20.dp else 32.dp
            Row(modifier = Modifier.fillMaxSize()) {
                Sidebar(
                    state = state,
                    selectedSection = selectedSection,
                    onSelectSection = onSelectSection,
                    compact = compactSidebar,
                )
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Onyx.Background)
                            .padding(horizontal = contentPadding, vertical = 24.dp),
                ) {
                    PageHeader(selectedSection, state)
                    Spacer(Modifier.height(20.dp))
                    AnimatedContent(
                        targetState = selectedSection,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 40 })
                                .togetherWith(fadeOut(tween(120)))
                        },
                        label = "section",
                    ) { section ->
                        when (section) {
                            DesktopSection.Home -> {
                                HomeScreen(
                                    state = state,
                                    approve = approve,
                                    reject = reject,
                                    refreshPairingQr = refreshPairingQr,
                                    approveRelay = approveRelay,
                                    rejectRelay = rejectRelay,
                                )
                            }

                            DesktopSection.Devices -> {
                                DevicesScreen(
                                    state = state,
                                    onRevoke = { revokeCandidate = it },
                                    onRetrySshKeyRemoval = retrySshKeyRemoval,
                                    onUpdatePermissions = updateDevicePermissions,
                                    onDelete = { deleteCandidate = it },
                                )
                            }

                            DesktopSection.Activity -> {
                                ActivityScreen(
                                    state = state,
                                    approve = approve,
                                    reject = reject,
                                    approveRelay = approveRelay,
                                    rejectRelay = rejectRelay,
                                )
                            }

                            DesktopSection.Settings -> {
                                SettingsScreen(
                                    state = state,
                                    setRemoteAccess = setRemoteAccess,
                                    setRemoteInput = setRemoteInput,
                                    setClipboardSync = setClipboardSync,
                                    setAutostart = setAutostart,
                                    configureRelay = configureRelay,
                                    clearRelay = clearRelay,
                                    rotateRelayIdentity = rotateRelayIdentity,
                                    restoreTrustStoreFromBackup = restoreTrustStoreFromBackup,
                                    resetTrustStoreAfterConsent = resetTrustStoreAfterConsent,
                                    zoomPercent = zoomPercent,
                                    decreaseZoom = decreaseZoom,
                                    increaseZoom = increaseZoom,
                                    resetZoom = resetZoom,
                                    language = language,
                                    setLanguage = setLanguage,
                                    exitAegis = exitAegis,
                                    trayAvailable = trayAvailable,
                                )
                            }
                        }
                    }
                }
            }
            DeviceActionToast(
                result = state.lastDeviceRecordAction,
                visible = deviceActionToastVisible,
                onDismiss = { deviceActionToastVisible = false },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            )
        }
    }

    revokeCandidate?.let { device ->
        AlertDialog(
            onDismissRequest = { revokeCandidate = null },
            title = { Text(t("revoke.title"), color = Onyx.OnSurface, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    tf("revoke.body", device.displayName),
                    color = Onyx.OnSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        revoke(device.remoteDeviceId.value)
                        revokeCandidate = null
                    },
                ) {
                    Text(t("revoke.action"), color = Onyx.Error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { revokeCandidate = null }) {
                    Text(t("cancel"), color = Onyx.OnSurface)
                }
            },
            backgroundColor = Onyx.Container,
            contentColor = Onyx.OnSurface,
            shape = RoundedCornerShape(18.dp),
        )
    }

    deleteCandidate?.let { device ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(t("delete.title"), color = Onyx.OnSurface, fontWeight = FontWeight.SemiBold) },
            text = { Text(tf("delete.body", device.displayName), color = Onyx.OnSurfaceVariant) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteRecord(device.remoteDeviceId.value)
                        deleteCandidate = null
                    },
                ) { Text(t("delete.action"), color = Onyx.Error, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text(t("cancel"), color = Onyx.OnSurface) }
            },
            backgroundColor = Onyx.Container,
            contentColor = Onyx.OnSurface,
            shape = RoundedCornerShape(18.dp),
        )
    }
}

private const val DEVICE_ACTION_TOAST_MILLIS = 5_000L
private val COMPACT_SIDEBAR_BREAKPOINT = 1_040.dp

private fun deviceRecordActionPresentation(outcome: DeviceRecordActionOutcome): Pair<String, StatusTone> =
    when (outcome) {
        DeviceRecordActionOutcome.Deleted -> "delete.result.deleted" to StatusTone.Positive
        DeviceRecordActionOutcome.MustRevokeFirst -> "delete.result.must_revoke" to StatusTone.Warning
        DeviceRecordActionOutcome.NotFound -> "delete.result.not_found" to StatusTone.Neutral
        DeviceRecordActionOutcome.Unsupported -> "delete.result.unsupported" to StatusTone.Warning
        DeviceRecordActionOutcome.SshKeyRemovalPending -> "delete.result.ssh_removal_pending" to StatusTone.Warning
        DeviceRecordActionOutcome.Failed -> "delete.result.failed" to StatusTone.Danger
    }

@Composable
private fun DeviceActionToast(
    result: DeviceRecordActionResult?,
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && result != null,
        modifier = modifier,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
    ) {
        val (messageKey, tone) = deviceRecordActionPresentation(result?.outcome ?: DeviceRecordActionOutcome.Failed)
        Surface(
            color = tone.background,
            contentColor = tone.color,
            shape = RoundedCornerShape(999.dp),
            border = BorderStroke(1.dp, tone.color.copy(alpha = 0.42f)),
            elevation = 6.dp,
            modifier = Modifier.widthIn(max = 560.dp).clickable(onClick = onDismiss),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(Modifier.size(8.dp).background(tone.color, CircleShape))
                Text(
                    t(messageKey),
                    color = tone.color,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StandalonePairingApproval(
    request: DesktopPairingRequest,
    approve: (String) -> Unit,
    reject: (String) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Onyx.Background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                t("approval.required"),
                color = Onyx.OnSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(t("approval.verify"), color = Onyx.OnSurfaceVariant, fontSize = 14.sp)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Onyx.Container,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Onyx.Primary.copy(alpha = 0.45f)),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(request.deviceName, color = Onyx.OnSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        tf("approval.local_phone", formatTime(request.requestedAtEpochMillis)),
                        color = Onyx.OnSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    SelectionContainer {
                        Text(
                            request.fingerprint,
                            color = Onyx.Muted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                PillButton(t("reject"), onClick = { reject(request.requestId) }, variant = ButtonVariant.Secondary)
                PillButton(t("approve"), onClick = { approve(request.requestId) }, variant = ButtonVariant.Primary)
            }
        }
    }
}

@Composable
private fun Sidebar(
    state: DesktopAgentState,
    selectedSection: DesktopSection,
    onSelectSection: (DesktopSection) -> Unit,
    compact: Boolean = false,
) {
    val pendingCount = state.pendingPairingRequests.size + state.pendingRelaySessions.size
    val edgeColor = Onyx.OutlineVariant
    val sidebarWidth by animateDpAsState(if (compact) 76.dp else 216.dp, tween(220))
    Column(
        modifier =
            Modifier
                .width(sidebarWidth)
                .fillMaxHeight()
                .background(Onyx.Sidebar)
                .drawBehind {
                    val edge = 1.dp.toPx()
                    drawLine(edgeColor, Offset(size.width - edge / 2f, 0f), Offset(size.width - edge / 2f, size.height), edge)
                }.padding(horizontal = 14.dp, vertical = 24.dp),
        horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AegisMark(active = state.pairingServerRunning)
            if (!compact) {
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("Aegis", color = Onyx.OnSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(t("brand.subtitle"), color = Onyx.OnSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(28.dp))

        DesktopSection.entries.forEach { section ->
            val badge =
                when (section) {
                    DesktopSection.Home -> state.pendingPairingRequests.size
                    DesktopSection.Activity -> state.pendingRelaySessions.size
                    else -> 0
                }
            NavigationItem(
                section = section,
                selected = section == selectedSection,
                badge = badge,
                compact = compact,
                onClick = { onSelectSection(section) },
            )
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.weight(1f))
        AnimatedVisibility(visible = pendingCount > 0 && !compact, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PulsingDot(color = Onyx.Warning)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(t("attention"), color = Onyx.Warning, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
                    Text(
                        tf(if (pendingCount == 1) "request.pending.one" else "request.pending.many", pendingCount),
                        color = Onyx.OnSurface,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 0.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.pairingServerRunning) PulsingDot(color = Onyx.Primary) else StatusDot(false)
            if (!compact) {
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        if (state.pairingServerRunning) t("agent.available") else t("agent.starting"),
                        color = Onyx.OnSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        tf("devices.active", state.authorizedDevices.count { it.revokedAtEpochMillis == null }),
                        color = Onyx.OnSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PulsingDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val pulse = rememberInfiniteTransition()
    val haloAlpha by pulse.animateFloat(0.4f, 0f, infiniteRepeatable(tween(1_800), RepeatMode.Restart))
    val haloScale by pulse.animateFloat(1f, 2.4f, infiniteRepeatable(tween(1_800), RepeatMode.Restart))
    Box(modifier.size(18.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size((7 * haloScale).dp).alpha(haloAlpha).background(color, CircleShape))
        Box(Modifier.size(7.dp).background(color, CircleShape))
    }
}

@Composable
private fun AegisMark(active: Boolean) {
    Image(
        painter = painterResource("icons/aegis-app-icon.png"),
        contentDescription = "Aegis",
        modifier =
            Modifier
                .size(40.dp)
                .alpha(if (active) 1f else 0.58f),
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NavigationItem(
    section: DesktopSection,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    compact: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highlighted = selected || hovered
    val background by animateColorAsState(
        when {
            selected -> Onyx.Primary.copy(alpha = 0.10f)
            hovered -> Onyx.OnSurface.copy(alpha = 0.05f)
            else -> Color.Transparent
        },
        tween(140),
    )
    val indicatorHeight by animateDpAsState(if (selected) 20.dp else 0.dp, tween(200))
    val labelColor by animateColorAsState(
        if (highlighted) Onyx.OnSurface else Onyx.OnSurfaceVariant,
        tween(140),
    )
    val label = t("section.${section.key}.label")
    val row: @Composable () -> Unit = {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(background)
                    .hoverable(interaction)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(role = Role.Button, onClick = onClick)
                    .semantics { this.selected = selected }
                    .padding(horizontal = if (compact) 0.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start,
        ) {
            if (!compact) {
                Box(Modifier.width(3.dp).height(indicatorHeight).background(Onyx.Primary, CircleShape))
                Spacer(Modifier.width(11.dp))
            }
            NavigationStatusGlyph(section, highlighted, compact && badge > 0)
            if (!compact) {
                Spacer(Modifier.width(12.dp))
                Text(
                    label,
                    color = labelColor,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                AnimatedVisibility(visible = badge > 0, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    Box(
                        modifier =
                            Modifier
                                .background(Onyx.PrimaryContainer, CircleShape)
                                .heightIn(min = 22.dp)
                                .widthIn(min = 22.dp)
                                .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(badge.toString(), color = Onyx.OnSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    if (compact) {
        TooltipArea(
            tooltip = {
                Box(
                    Modifier
                        .background(Onyx.ContainerHigh, RoundedCornerShape(8.dp))
                        .border(1.dp, Onyx.OutlineVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(label, color = Onyx.OnSurface, fontSize = 12.sp)
                }
            },
            delayMillis = 350,
            tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(16.dp, 8.dp)),
        ) {
            row()
        }
    } else {
        row()
    }
}

@Composable
private fun NavigationStatusGlyph(
    section: DesktopSection,
    highlighted: Boolean,
    showBadge: Boolean,
) {
    Box(contentAlignment = Alignment.TopEnd) {
        NavigationGlyph(section, highlighted)
        if (showBadge) {
            Box(Modifier.offset(x = 5.dp, y = (-4).dp).size(8.dp).background(Onyx.Warning, CircleShape))
        }
    }
}

@Composable
private fun NavigationGlyph(
    section: DesktopSection,
    selected: Boolean,
) {
    val color = if (selected) Onyx.Primary else Onyx.OnSurfaceVariant
    Canvas(Modifier.size(20.dp)) {
        val stroke = Stroke(width = 1.6.dp.toPx())
        when (section) {
            DesktopSection.Home -> {
                val path =
                    Path().apply {
                        moveTo(size.width * 0.15f, size.height * 0.47f)
                        lineTo(size.width * 0.50f, size.height * 0.18f)
                        lineTo(size.width * 0.85f, size.height * 0.47f)
                        lineTo(size.width * 0.78f, size.height * 0.47f)
                        lineTo(size.width * 0.78f, size.height * 0.82f)
                        lineTo(size.width * 0.22f, size.height * 0.82f)
                        lineTo(size.width * 0.22f, size.height * 0.47f)
                        close()
                    }
                drawPath(path, color, style = stroke)
            }

            DesktopSection.Devices -> {
                drawCircle(
                    color,
                    radius = size.minDimension * 0.17f,
                    center = Offset(size.width * 0.37f, size.height * 0.34f),
                    style = stroke,
                )
                drawCircle(
                    color,
                    radius = size.minDimension * 0.13f,
                    center = Offset(size.width * 0.70f, size.height * 0.43f),
                    style = stroke,
                )
                drawArc(
                    color,
                    startAngle = 205f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft =
                        Offset(
                            size.width * 0.12f,
                            size.height * 0.48f,
                        ),
                    size = Size(size.width * 0.52f, size.height * 0.38f),
                    style = stroke,
                )
                drawArc(
                    color,
                    startAngle = 210f,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft =
                        Offset(
                            size.width * 0.50f,
                            size.height * 0.58f,
                        ),
                    size = Size(size.width * 0.36f, size.height * 0.25f),
                    style = stroke,
                )
            }

            DesktopSection.Activity -> {
                val path =
                    Path().apply {
                        moveTo(size.width * 0.08f, size.height * 0.55f)
                        lineTo(size.width * 0.28f, size.height * 0.55f)
                        lineTo(size.width * 0.40f, size.height * 0.28f)
                        lineTo(size.width * 0.58f, size.height * 0.75f)
                        lineTo(size.width * 0.70f, size.height * 0.48f)
                        lineTo(size.width * 0.92f, size.height * 0.48f)
                    }
                drawPath(path, color, style = stroke)
            }

            DesktopSection.Settings -> {
                drawCircle(color, radius = size.minDimension * 0.36f, style = stroke)
                drawCircle(color, radius = size.minDimension * 0.13f, style = stroke)
                drawLine(color, Offset(size.width * 0.5f, 0f), Offset(size.width * 0.5f, size.height * 0.16f), strokeWidth = stroke.width)
                drawLine(
                    color,
                    Offset(size.width * 0.5f, size.height * 0.84f),
                    Offset(size.width * 0.5f, size.height),
                    strokeWidth = stroke.width,
                )
                drawLine(color, Offset(0f, size.height * 0.5f), Offset(size.width * 0.16f, size.height * 0.5f), strokeWidth = stroke.width)
                drawLine(
                    color,
                    Offset(size.width * 0.84f, size.height * 0.5f),
                    Offset(size.width, size.height * 0.5f),
                    strokeWidth = stroke.width,
                )
            }
        }
    }
}

@Composable
private fun PageHeader(
    section: DesktopSection,
    state: DesktopAgentState,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t("section.${section.key}.title"), color = Onyx.OnSurface, style = MaterialTheme.typography.h5, textAlign = TextAlign.Center)
            Spacer(Modifier.height(3.dp))
            Text(t("section.${section.key}.subtitle"), color = Onyx.OnSurfaceVariant, style = MaterialTheme.typography.body2, textAlign = TextAlign.Center)
        }
        val pendingCount = desktopHomePairingUi(state).pendingBadgeCount
        if (pendingCount > 0) {
            StatusChip(tf(if (pendingCount == 1) "status.pending.one" else "status.pending.many", pendingCount), StatusTone.Warning)
        } else {
            StatusChip(
                if (state.pairingServerRunning) t("status.ready") else t("status.starting"),
                if (state.pairingServerRunning) StatusTone.Positive else StatusTone.Neutral,
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun HomeScreen(
    state: DesktopAgentState,
    approve: (String) -> Unit,
    reject: (String) -> Unit,
    refreshPairingQr: () -> Unit,
    approveRelay: (String) -> Unit,
    rejectRelay: (String) -> Unit,
) {
    val homePairingUi = desktopHomePairingUi(state)
    val homeScrollState = rememberScrollState()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(homeScrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (homePairingUi.approvalState == DesktopApprovalUiState.Pending) {
            PendingApprovalsCard(
                localRequests = state.pendingPairingRequests,
                relayRequests = state.pendingRelaySessions,
                approve = approve,
                reject = reject,
                approveRelay = approveRelay,
                rejectRelay = rejectRelay,
            )
        }

        HostHealthBanners(state)

        QuickStatusGrid(state)

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth >= 760.dp) {
                HomePairingSplit(
                    qr = { PairingQrCard(state, refreshPairingQr, Modifier.fillMaxWidth()) },
                    overview = { ConnectionOverviewCard(state, Modifier.fillMaxWidth()) },
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PairingQrCard(state, refreshPairingQr, Modifier.fillMaxWidth())
                    Divider(color = Onyx.OutlineVariant)
                    ConnectionOverviewCard(state, Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
@Suppress("FunctionNaming")
private fun HomePairingSplit(
    qr: @Composable () -> Unit,
    overview: @Composable () -> Unit,
) {
    val dividerColor = Onyx.OutlineVariant.copy(alpha = 0.7f)
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Box { qr() }
            Box(Modifier.width(1.dp).background(dividerColor))
            Box { overview() }
        },
    ) { measurables, constraints ->
        val qrWidth = 320.dp.roundToPx()
        val spacing = 28.dp.roundToPx()
        val dividerWidth = 1.dp.roundToPx()
        val qrPlaceable =
            measurables[0].measure(
                Constraints(
                    minWidth = qrWidth,
                    maxWidth = qrWidth,
                    minHeight = 0,
                    maxHeight = constraints.maxHeight,
                ),
            )
        val overviewMaxWidth =
            if (constraints.hasBoundedWidth) {
                (constraints.maxWidth - qrWidth - spacing * 2 - dividerWidth).coerceAtLeast(0)
            } else {
                Constraints.Infinity
            }
        val overviewPlaceable =
            measurables[2].measure(
                Constraints(
                    minWidth = if (constraints.hasBoundedWidth) overviewMaxWidth else 0,
                    maxWidth = overviewMaxWidth,
                    minHeight = 0,
                    maxHeight = constraints.maxHeight,
                ),
            )
        val height = maxOf(qrPlaceable.height, overviewPlaceable.height)
        val dividerPlaceable = measurables[1].measure(Constraints.fixed(dividerWidth, height))
        val width =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                qrWidth + spacing * 2 + dividerWidth + overviewPlaceable.width
            }
        layout(width, height) {
            qrPlaceable.placeRelative(0, 0)
            dividerPlaceable.placeRelative(qrWidth + spacing, 0)
            overviewPlaceable.placeRelative(qrWidth + spacing + dividerWidth + spacing, 0)
        }
    }
}

internal enum class DesktopApprovalUiState {
    None,
    Pending,
}

internal enum class DesktopHomeContent {
    PendingApprovals,
    PairingQr,
}

internal data class DesktopHomePairingUi(
    val pendingBadgeCount: Int,
    val approvalState: DesktopApprovalUiState,
    val contentOrder: List<DesktopHomeContent>,
)

internal fun desktopHomePairingUi(state: DesktopAgentState): DesktopHomePairingUi {
    val pendingCount = state.pendingPairingRequests.size + state.pendingRelaySessions.size
    val hasPendingApproval = pendingCount > 0
    return DesktopHomePairingUi(
        pendingBadgeCount = pendingCount,
        approvalState = if (hasPendingApproval) DesktopApprovalUiState.Pending else DesktopApprovalUiState.None,
        contentOrder =
            if (hasPendingApproval) {
                listOf(DesktopHomeContent.PendingApprovals, DesktopHomeContent.PairingQr)
            } else {
                listOf(DesktopHomeContent.PairingQr)
            },
    )
}

@Composable
@Suppress("FunctionNaming")
private fun PairingQrCard(
    state: DesktopAgentState,
    refreshPairingQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expiresAt = state.pairingQrExpiresAtEpochMillis
    var now by remember(expiresAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        while (expiresAt != null) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val remainingSeconds = expiresAt?.let { ((it - now + 999L) / 1_000L).coerceAtLeast(0L) }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(t("pair.title"), color = Onyx.OnSurface, style = MaterialTheme.typography.h6, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            t("pair.body"),
            color = Onyx.OnSurfaceVariant,
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Box(modifier = Modifier.widthIn(max = QR_DISPLAY_MAX + 20.dp)) {
            QrCode(state.pairingQrPayload)
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.pairingServerRunning) PulsingDot(Onyx.Primary) else StatusDot(false)
                Text(
                    if (state.pairingServerRunning) t("pair.qr.ready") else t("pair.qr.preparing"),
                    color = Onyx.OnSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
            remainingSeconds?.let { seconds ->
                Text(
                    if (seconds > 0) tf("pair.qr.expires", seconds) else t("pair.qr.expired"),
                    color = if (seconds > 10) Onyx.Muted else Onyx.Warning,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(12.dp))
            PillButton(
                text = if (state.pairingQrRefreshing) t("pair.qr.refreshing") else t("pair.qr.refresh"),
                onClick = refreshPairingQr,
                variant = ButtonVariant.Secondary,
                enabled = state.pairingServerRunning && !state.pairingQrRefreshing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ConnectionOverviewCard(
    state: DesktopAgentState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(t("computer.title"), color = Onyx.OnSurface, style = MaterialTheme.typography.h6)
                Text(t("computer.local_details"), color = Onyx.OnSurfaceVariant, style = MaterialTheme.typography.body2)
            }
            StatusChip(
                if (state.pairingServerRunning) t("status.online") else t("status.starting"),
                if (state.pairingServerRunning) StatusTone.Positive else StatusTone.Neutral,
            )
        }
        Spacer(Modifier.height(20.dp))
        DataField(t("field.local_address"), state.pairingUrl ?: t("field.detecting_network"), monospace = true, copyable = state.pairingUrl != null)
        Divider(color = Onyx.OutlineVariant.copy(alpha = 0.7f), modifier = Modifier.padding(vertical = 12.dp))
        DataField(t("field.backup_code"), state.pairingCode ?: "······", monospace = true, copyable = state.pairingCode != null, emphasis = true)
        Divider(color = Onyx.OutlineVariant.copy(alpha = 0.7f), modifier = Modifier.padding(vertical = 12.dp))
        DataField(t("field.agent_identity"), state.agentFingerprint ?: t("field.generating_identity"), monospace = true)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(2.dp).height(40.dp).background(Onyx.Primary.copy(alpha = 0.7f), CircleShape))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t("approval.visible"), color = Onyx.Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    t("approval.visible.body"),
                    color = Onyx.OnSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun QuickStatusGrid(state: DesktopAgentState) {
    val metrics =
        listOf(
            MetricSpec(
                MetricIconKind.Devices,
                t("metric.devices"),
                state.authorizedDevices.count { it.revokedAtEpochMillis == null }.toString(),
                t("metric.active_access"),
            ),
            MetricSpec(
                MetricIconKind.Capture,
                t("metric.capture"),
                capabilityLabel(state.captureCapability),
                t("metric.system_capability"),
                tone = capabilityTone(state.captureCapability),
            ),
            MetricSpec(
                MetricIconKind.Input,
                t("metric.remote_input"),
                if (state.remoteInputEnabled) t("metric.allowed") else t("metric.paused"),
                t("metric.safety_switch"),
                tone = if (state.remoteInputEnabled) StatusTone.Positive else StatusTone.Warning,
            ),
            MetricSpec(
                MetricIconKind.Relay,
                t("metric.relay"),
                if (state.openSshAvailable) t("metric.connected") else t("metric.disconnected"),
                t("metric.outside_lan"),
                tone = if (state.openSshAvailable) StatusTone.Positive else StatusTone.Neutral,
            ),
        )
    Column(modifier = Modifier.fillMaxWidth()) {
        Divider(color = Onyx.OutlineVariant.copy(alpha = 0.7f))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 760.dp) 4 else 2
            metrics.chunked(columns).forEachIndexed { rowIndex, rowMetrics ->
                if (rowIndex > 0) Divider(color = Onyx.OutlineVariant.copy(alpha = 0.7f))
                MetricRow(rowMetrics)
            }
        }
        Divider(color = Onyx.OutlineVariant.copy(alpha = 0.7f))
    }
}

@Composable
@Suppress("FunctionNaming")
private fun MetricRow(metrics: List<MetricSpec>) {
    val dividerColor = Onyx.OutlineVariant.copy(alpha = 0.7f)
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            metrics.forEach { metric -> MetricTile(metric) }
            repeat(metrics.size - 1) {
                Box(Modifier.width(1.dp).padding(vertical = 14.dp).background(dividerColor))
            }
        },
    ) { measurables, constraints ->
        val tileCount = metrics.size
        val dividerCount = (tileCount - 1).coerceAtLeast(0)
        val dividerWidth = 1.dp.roundToPx()
        val tileMeasurables = measurables.take(tileCount)
        val dividerMeasurables = measurables.drop(tileCount)
        val availableWidth =
            if (constraints.hasBoundedWidth) {
                (constraints.maxWidth - dividerCount * dividerWidth).coerceAtLeast(0)
            } else {
                Constraints.Infinity
            }
        val tileWidth = if (availableWidth == Constraints.Infinity || tileCount == 0) 0 else availableWidth / tileCount
        val tilePlaceables =
            tileMeasurables.mapIndexed { index, measurable ->
                val width =
                    if (availableWidth == Constraints.Infinity || index < tileCount - 1) {
                        tileWidth
                    } else {
                        availableWidth - tileWidth * (tileCount - 1)
                    }
                measurable.measure(
                    Constraints(
                        minWidth = width,
                        maxWidth = width,
                        minHeight = 0,
                        maxHeight = constraints.maxHeight,
                    ),
                )
            }
        val height = tilePlaceables.maxOfOrNull { it.height } ?: 0
        val dividerPlaceables =
            dividerMeasurables.map { measurable ->
                measurable.measure(Constraints.fixed(dividerWidth, height))
            }
        val width =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                tileWidth * tileCount + dividerWidth * dividerCount
            }
        layout(width, height) {
            var x = 0
            tilePlaceables.forEachIndexed { index, placeable ->
                placeable.placeRelative(x, 0)
                x += placeable.width
                if (index < dividerPlaceables.size) {
                    dividerPlaceables[index].placeRelative(x, 0)
                    x += dividerPlaceables[index].width
                }
            }
        }
    }
}

private data class MetricSpec(
    val icon: MetricIconKind,
    val label: String,
    val value: String,
    val supporting: String,
    val tone: StatusTone? = null,
)

private fun capabilityTone(status: CapabilityStatus): StatusTone =
    when (status) {
        CapabilityStatus.Available -> StatusTone.Positive
        CapabilityStatus.Degraded, CapabilityStatus.PermissionRequired -> StatusTone.Warning
        CapabilityStatus.Unavailable -> StatusTone.Danger
        CapabilityStatus.Unknown -> StatusTone.Neutral
    }

@Composable
@Suppress("FunctionNaming")
private fun MetricTile(
    metric: MetricSpec,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 3.dp)) { MetricIcon(metric.icon) }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(metric.label.uppercase(), color = Onyx.Muted, fontSize = 10.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                metric.tone?.let { tone -> Box(Modifier.size(7.dp).background(tone.color, CircleShape)) }
                Text(
                    metric.value,
                    color = Onyx.OnSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(metric.supporting, color = Onyx.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private enum class MetricIconKind { Devices, Capture, Input, Relay }

@Composable
@Suppress("FunctionNaming")
private fun MetricIcon(kind: MetricIconKind) {
    Canvas(Modifier.size(18.dp)) {
        val color = Onyx.Primary
        val stroke = 1.6.dp.toPx()
        val outline = Stroke(width = stroke, cap = StrokeCap.Round)
        when (kind) {
            MetricIconKind.Devices -> {
                drawRect(color, Offset(size.width * 0.12f, size.height * 0.12f), Size(size.width * 0.76f, size.height * 0.66f), style = outline)
                drawLine(color, Offset(size.width * 0.36f, size.height * 0.88f), Offset(size.width * 0.64f, size.height * 0.88f), stroke, cap = StrokeCap.Round)
            }

            MetricIconKind.Capture -> {
                drawCircle(color, size.minDimension * 0.37f, style = outline)
                drawCircle(color, size.minDimension * 0.12f)
            }

            MetricIconKind.Input -> {
                drawLine(color, Offset(size.width * 0.18f, size.height * 0.42f), Offset(size.width * 0.40f, size.height * 0.64f), stroke, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.40f, size.height * 0.64f), Offset(size.width * 0.58f, size.height * 0.32f), stroke, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.58f, size.height * 0.32f), Offset(size.width * 0.82f, size.height * 0.54f), stroke, cap = StrokeCap.Round)
            }

            MetricIconKind.Relay -> {
                drawLine(color, Offset(size.width * 0.22f, size.height * 0.78f), Offset(size.width * 0.78f, size.height * 0.22f), stroke, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.50f, size.height * 0.22f), Offset(size.width * 0.78f, size.height * 0.22f), stroke, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.78f, size.height * 0.22f), Offset(size.width * 0.78f, size.height * 0.50f), stroke, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun PendingApprovalsCard(
    localRequests: List<DesktopPairingRequest>,
    relayRequests: List<DesktopRelaySessionRequest>,
    approve: (String) -> Unit,
    reject: (String) -> Unit,
    approveRelay: (String) -> Unit,
    rejectRelay: (String) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Onyx.PrimaryContainer.copy(alpha = 0.14f)),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(Onyx.Primary, size = Size(3.dp.toPx(), size.height))
                },
        )
        Column(Modifier.fillMaxWidth().padding(start = 23.dp, end = 20.dp, top = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PulsingDot(Onyx.Primary)
                Column(Modifier.weight(1f)) {
                    Text(t("approval.required"), color = Onyx.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(t("approval.verify"), color = Onyx.OnSurfaceVariant, fontSize = 12.sp)
                }
            }
            localRequests.forEachIndexed { index, request ->
                if (index > 0) Divider(color = Onyx.Primary.copy(alpha = 0.18f))
                LocalApprovalRow(request, approve, reject)
            }
            relayRequests.forEachIndexed { index, request ->
                if (localRequests.isNotEmpty() || index > 0) Divider(color = Onyx.Primary.copy(alpha = 0.18f))
                RelayApprovalRow(request, approveRelay, rejectRelay)
            }
        }
    }
}

@Composable
private fun LocalApprovalRow(
    request: DesktopPairingRequest,
    approve: (String) -> Unit,
    reject: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(request.deviceName, color = Onyx.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    tf("approval.local_phone", formatTime(request.requestedAtEpochMillis)),
                    color = Onyx.OnSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(5.dp))
                SelectionContainer {
                    Text(request.fingerprint, color = Onyx.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    t("approval.permissions"),
                    color = Onyx.OnSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(t("reject"), onClick = { reject(request.requestId) }, variant = ButtonVariant.Secondary)
                PillButton(t("approve"), onClick = { approve(request.requestId) }, variant = ButtonVariant.Primary)
            }
        }
    }
}

@Composable
private fun RelayApprovalRow(
    request: DesktopRelaySessionRequest,
    approve: (String) -> Unit,
    reject: (String) -> Unit,
) {
    val displayName = request.sourceDisplayName?.takeIf { it.isNotBlank() } ?: t("approval.remote_device")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(displayName, color = Onyx.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    tf("approval.relay_request", formatTime(request.receivedAtEpochMillis)),
                    color = Onyx.OnSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(5.dp))
                SelectionContainer {
                    Text(
                        request.sourcePublicKeyFingerprint?.takeIf { it.isNotBlank() } ?: request.sourceRelayDeviceId.value,
                        color = Onyx.Muted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    t("reject"),
                    onClick = { reject(request.sessionId.value) },
                    variant = ButtonVariant.Secondary,
                    enabled = !request.decisionInProgress,
                )
                PillButton(
                    if (request.decisionInProgress) t("approve.processing") else t("approve"),
                    onClick = { approve(request.sessionId.value) },
                    variant = ButtonVariant.Primary,
                    enabled = !request.decisionInProgress,
                )
            }
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming")
private fun DevicesScreen(
    state: DesktopAgentState,
    onRevoke: (DeviceAuthorization) -> Unit,
    onUpdatePermissions: (String, DevicePermissions) -> Unit,
    onRetrySshKeyRemoval: (String) -> Unit,
    onDelete: (DeviceAuthorization) -> Unit,
) {
    val devices = state.authorizedDevices
    val activeCount = devices.count { it.revokedAtEpochMillis == null }
    var selectedDeviceId by remember(devices) { mutableStateOf(devices.firstOrNull()?.remoteDeviceId?.value) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("devices.authorized_access"), color = Onyx.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (activeCount) {
                            0 -> t("devices.none")
                            1 -> t("devices.sessions.one")
                            else -> tf("devices.sessions.many", activeCount)
                        },
                        color = Onyx.OnSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                StatusChip(tf("devices.active_count", activeCount), if (activeCount > 0) StatusTone.Positive else StatusTone.Neutral)
            }
        }

        if (devices.isEmpty()) {
            EmptyState(
                title = t("devices.empty.title"),
                message = t("devices.empty.body"),
            )
        } else {
            val sortedDevices = devices.sortedWith(compareBy<DeviceAuthorization> { it.revokedAtEpochMillis != null }.thenBy { it.displayName })
            val selectedDevice = sortedDevices.firstOrNull { it.remoteDeviceId.value == selectedDeviceId } ?: sortedDevices.first()
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val sideBySide = maxWidth >= 820.dp
                val detailsPane: @Composable (Modifier) -> Unit = { paneModifier ->
                    AnimatedContent(
                        targetState = selectedDevice.remoteDeviceId.value,
                        modifier = paneModifier,
                        transitionSpec = { fadeIn(tween(160)).togetherWith(fadeOut(tween(100))) },
                        label = "device-details",
                    ) {
                        DeviceDetailsPane(
                            device = selectedDevice,
                            onRevoke = onRevoke,
                            onUpdatePermissions = onUpdatePermissions,
                            onRetrySshKeyRemoval = onRetrySshKeyRemoval,
                            onDelete = onDelete,
                            actionInProgress = selectedDevice.remoteDeviceId.value in state.deviceRecordActionsInProgress,
                            sshRemovalInProgress = selectedDevice.remoteDeviceId.value in state.sshKeyRemovalActionsInProgress,
                            permissionsBusy = selectedDevice.remoteDeviceId.value in state.permissionChangesInProgress,
                            permissionsError = state.permissionChangeErrors[selectedDevice.remoteDeviceId.value],
                        )
                    }
                }
                if (sideBySide) {
                    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 440.dp)) {
                        Column(modifier = Modifier.weight(1f).padding(end = 28.dp)) {
                            sortedDevices.forEach { device ->
                                DeviceCard(
                                    device = device,
                                    selected = device.remoteDeviceId.value == selectedDevice.remoteDeviceId.value,
                                    onSelect = { selectedDeviceId = device.remoteDeviceId.value },
                                )
                            }
                        }
                        Divider(color = Onyx.OutlineVariant, modifier = Modifier.fillMaxHeight().width(1.dp))
                        detailsPane(Modifier.width(340.dp).padding(start = 28.dp))
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        detailsPane(Modifier.fillMaxWidth())
                        Divider(color = Onyx.OutlineVariant)
                        sortedDevices.forEach { device ->
                            DeviceCard(
                                device = device,
                                selected = device.remoteDeviceId.value == selectedDevice.remoteDeviceId.value,
                                onSelect = { selectedDeviceId = device.remoteDeviceId.value },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
@Suppress("FunctionNaming")
private fun DeviceCard(
    device: DeviceAuthorization,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val active = device.revokedAtEpochMillis == null
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            selected -> Onyx.Primary.copy(alpha = 0.08f)
            hovered -> Onyx.OnSurface.copy(alpha = 0.04f)
            else -> Color.Transparent
        },
        tween(140),
    )
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(background)
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onSelect)
                .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            DeviceAvatar(device.displayName, active)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        device.displayName,
                        color = Onyx.OnSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(
                        when {
                            active -> t("devices.authorized")
                            device.sshKeyRemovalPending -> t("devices.ssh_removal_pending")
                            else -> t("devices.revoked")
                        },
                        when {
                            active -> StatusTone.Positive
                            device.sshKeyRemovalPending -> StatusTone.Warning
                            else -> StatusTone.Neutral
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(device.remoteDeviceId.value, color = Onyx.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(permissionSummary(device.permissions), color = Onyx.OnSurfaceVariant, fontSize = 12.sp)
                if (device.sshKeyRemovalPending) {
                    Text(
                        tf("devices.ssh_removal_pending_detail", device.sshKeyRemovalFailureCode ?: "SSH-7326"),
                        color = Onyx.Warning,
                        fontSize = 11.sp,
                    )
                }
                Text(tf("devices.approved_at", formatDateTime(device.approvedAtEpochMillis)), color = Onyx.Muted, fontSize = 11.sp)
            }
        }
        Divider(color = Onyx.OutlineVariant, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
@Suppress("FunctionNaming", "LongParameterList")
private fun DeviceDetailsPane(
    device: DeviceAuthorization,
    onRevoke: (DeviceAuthorization) -> Unit,
    onUpdatePermissions: (String, DevicePermissions) -> Unit,
    onRetrySshKeyRemoval: (String) -> Unit,
    onDelete: (DeviceAuthorization) -> Unit,
    actionInProgress: Boolean,
    sshRemovalInProgress: Boolean,
    permissionsBusy: Boolean,
    permissionsError: String?,
    modifier: Modifier = Modifier,
) {
    val active = device.revokedAtEpochMillis == null
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(device.displayName, active)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.displayName, color = Onyx.OnSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(device.remoteDeviceId.value, color = Onyx.Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1)
            }
            StatusChip(if (active) t("devices.authorized") else t("devices.revoked"), if (active) StatusTone.Positive else StatusTone.Neutral)
        }
        Divider(color = Onyx.OutlineVariant)
        DataField(t("field.agent_identity"), device.publicIdentity?.fingerprint ?: device.remoteDeviceId.value, monospace = true)
        Divider(color = Onyx.OutlineVariant)
        if (active) {
            DevicePermissionEditor(device, onUpdatePermissions, permissionsBusy, permissionsError)
        } else {
            DataField(t("devices.authorized_access"), permissionSummary(device.permissions))
        }
        Divider(color = Onyx.OutlineVariant)
        DataField(t("devices.approved_label"), formatDateTime(device.approvedAtEpochMillis))
        Spacer(Modifier.height(4.dp))
        if (active) {
            PillButton(t("revoke.action"), onClick = { onRevoke(device) }, variant = ButtonVariant.Danger)
        } else if (device.sshKeyRemovalPending) {
            PillButton(
                if (sshRemovalInProgress) t("devices.ssh_removal_retrying") else t("devices.ssh_removal_retry"),
                onClick = { onRetrySshKeyRemoval(device.remoteDeviceId.value) },
                variant = ButtonVariant.Secondary,
                enabled = !sshRemovalInProgress,
            )
        } else {
            PillButton(
                if (actionInProgress) t("delete.in_progress") else t("delete.action"),
                onClick = { onDelete(device) },
                variant = ButtonVariant.Danger,
                enabled = !actionInProgress,
            )
        }
    }
}

@Composable
private fun DeviceAvatar(
    name: String,
    active: Boolean,
) {
    val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = Modifier.size(42.dp).background(if (active) Onyx.PrimaryContainer else Onyx.ContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            color = if (active) Onyx.OnPrimaryContainer else Onyx.OnSurfaceVariant,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ActivityScreen(
    state: DesktopAgentState,
    approve: (String) -> Unit,
    reject: (String) -> Unit,
    approveRelay: (String) -> Unit,
    rejectRelay: (String) -> Unit,
) {
    val orderedLogs = state.logs.asReversed()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.pendingPairingRequests.isNotEmpty() || state.pendingRelaySessions.isNotEmpty()) {
            item(key = "approvals") {
                PendingApprovalsCard(
                    localRequests = state.pendingPairingRequests,
                    relayRequests = state.pendingRelaySessions,
                    approve = approve,
                    reject = reject,
                    approveRelay = approveRelay,
                    rejectRelay = rejectRelay,
                )
            }
        }
        item(key = "activity-header") {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t("activity.title"), color = Onyx.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(t("activity.body"), color = Onyx.OnSurfaceVariant, fontSize = 12.sp)
                    }
                    Text(
                        if (state.logs.size == 1) t("activity.events.one") else tf("activity.events.many", state.logs.size),
                        color = Onyx.Muted,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        if (state.logs.isEmpty()) {
            item(key = "empty-logs") {
                EmptyState(t("activity.empty.title"), t("activity.empty.body"))
            }
        } else {
            itemsIndexed(
                items = orderedLogs,
                key = { _, item -> "${item.timestampEpochMillis}-${item.level}-${item.message.hashCode()}" },
            ) { index, log ->
                LogRow(log, first = index == 0, last = index == orderedLogs.lastIndex)
            }
        }
        item(key = "activity-bottom-space") { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun LogRow(
    log: AgentLogEntry,
    first: Boolean,
    last: Boolean,
) {
    val language = LocalDesktopLanguage.current.resolved()
    val tone =
        when (log.level.lowercase()) {
            "error" -> StatusTone.Danger
            "warn", "warning" -> StatusTone.Warning
            else -> StatusTone.Neutral
        }
    val railColor = Onyx.OutlineVariant
    val nodeColor = tone.color
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    val railX = 12.dp.toPx()
                    val nodeY = 20.dp.toPx()
                    if (!first) drawLine(railColor, Offset(railX, 0f), Offset(railX, nodeY), 1.dp.toPx())
                    if (!last) drawLine(railColor, Offset(railX, nodeY), Offset(railX, size.height), 1.dp.toPx())
                    drawCircle(nodeColor.copy(alpha = 0.22f), 8.dp.toPx(), Offset(railX, nodeY))
                    drawCircle(nodeColor, 3.5.dp.toPx(), Offset(railX, nodeY))
                }.padding(start = 34.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                SelectionContainer {
                    Text(
                        log.localizedMessage(language.persistedValue),
                        color = Onyx.OnSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                val levelLabel =
                    when (log.level.lowercase()) {
                        "error" -> t("log.level.error")
                        "warn", "warning" -> t("log.level.warning")
                        else -> t("log.level.info")
                    }
                Text(
                    "$levelLabel · ${formatDateTime(log.timestampEpochMillis)}",
                    color = Onyx.Muted,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp,
                )
            }
        }
        Divider(color = Onyx.OutlineVariant, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "LongParameterList")
private fun SettingsScreen(
    state: DesktopAgentState,
    setRemoteAccess: (Boolean) -> Unit,
    setRemoteInput: (Boolean) -> Unit,
    setClipboardSync: (Boolean) -> Unit,
    setAutostart: (Boolean) -> Unit,
    configureRelay: (String, String) -> Unit,
    clearRelay: () -> Unit,
    rotateRelayIdentity: () -> Unit,
    restoreTrustStoreFromBackup: (String) -> Unit,
    resetTrustStoreAfterConsent: () -> Unit,
    zoomPercent: Int,
    decreaseZoom: () -> Unit,
    increaseZoom: () -> Unit,
    resetZoom: () -> Unit,
    language: DesktopLanguage,
    setLanguage: (DesktopLanguage) -> Unit,
    exitAegis: () -> Unit,
    trayAvailable: Boolean = true,
) {
    var relayUrlDraft by remember(state.relayUrl) { mutableStateOf(state.relayUrl.orEmpty()) }
    var relayDeviceIdDraft by remember(state.relayDeviceId) { mutableStateOf(state.relayDeviceId.orEmpty()) }
    var relayGuideExpanded by remember { mutableStateOf(false) }
    var connectionDetailsVisible by remember { mutableStateOf(false) }
    var identityRotationConfirmationVisible by remember { mutableStateOf(false) }
    var trustStoreBackupPath by remember { mutableStateOf("") }
    var trustStoreResetConfirmationVisible by remember { mutableStateOf(false) }
    val ufwManager = remember { LinuxUfwOnboardingManager() }
    val ufwScope = rememberCoroutineScope()
    var ufwInspection by remember { mutableStateOf<LinuxUfwInspection?>(null) }
    var ufwApplyResult by remember { mutableStateOf<LinuxUfwApplyResult?>(null) }
    var ufwConsentVisible by remember { mutableStateOf(false) }
    val isLinux = remember { System.getProperty("os.name", "").contains("Linux", ignoreCase = true) }
    val languageTag = LocalDesktopLanguage.current.resolved().persistedValue
    val settingsScrollState = rememberScrollState()
    val settingsScope = rememberCoroutineScope()
    val sectionOffsets = remember { mutableStateMapOf<String, Float>() }
    val navEntries =
        remember(isLinux, state.monitors.isNotEmpty()) {
            buildList {
                add("settings.nav.access")
                add("settings.nav.privacy")
                if (isLinux) add("settings.nav.firewall")
                add("settings.nav.ssh")
                add("settings.nav.capabilities")
                if (state.monitors.isNotEmpty()) add("settings.nav.displays")
                add("settings.nav.app")
            }
        }
    val activeSettingsGroup by remember(navEntries) {
        derivedStateOf {
            val scroll = settingsScrollState.value
            when {
                settingsScrollState.maxValue > 0 && scroll >= settingsScrollState.maxValue - 2 -> {
                    navEntries.last()
                }

                else -> {
                    navEntries.lastOrNull { key ->
                        (sectionOffsets[key] ?: Float.MAX_VALUE) <= scroll + SETTINGS_ANCHOR_THRESHOLD_PX
                    } ?: navEntries.first()
                }
            }
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val showRail = maxWidth >= 720.dp
        Row(modifier = Modifier.fillMaxSize()) {
            if (showRail) {
                SettingsNavigationRail(
                    entries = navEntries,
                    selected = activeSettingsGroup,
                    onNavigate = { key ->
                        settingsScope.launch {
                            settingsScrollState.animateScrollTo((sectionOffsets[key] ?: 0f).toInt().coerceAtLeast(0))
                        }
                    },
                )
                Divider(color = Onyx.OutlineVariant, modifier = Modifier.fillMaxHeight().width(1.dp))
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = if (showRail) 28.dp else 0.dp).verticalScroll(settingsScrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.trustStoreRecoveryRequired) {
                    SectionCard(modifier = Modifier.fillMaxWidth(), contentPadding = 18.dp) {
                        Text(t("trust.recovery.title"), color = Onyx.Error, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            t("trust.recovery.body"),
                            color = Onyx.OnSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        RelayTextField(
                            value = trustStoreBackupPath,
                            onValueChange = { trustStoreBackupPath = it },
                            label = t("trust.recovery.path"),
                            placeholder = t("trust.recovery.path.placeholder"),
                            enabled = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(
                                text = t("trust.recovery.restore"),
                                onClick = { restoreTrustStoreFromBackup(trustStoreBackupPath) },
                                variant = ButtonVariant.Primary,
                                enabled = trustStoreBackupPath.isNotBlank(),
                            )
                            PillButton(
                                text = t("trust.recovery.reset"),
                                onClick = { trustStoreResetConfirmationVisible = true },
                                variant = ButtonVariant.Danger,
                            )
                        }
                    }
                }
                SectionCard(modifier = Modifier.fillMaxWidth(), contentPadding = 18.dp) {
                    Text(t("settings.zoom.title"), color = Onyx.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(t("settings.zoom.body"), color = Onyx.OnSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(
                            text = "−",
                            onClick = decreaseZoom,
                            variant = ButtonVariant.Secondary,
                            enabled = zoomPercent > DESKTOP_ZOOM_MIN_PERCENT,
                        )
                        Text(
                            "$zoomPercent%",
                            color = Onyx.OnSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(min = 64.dp),
                        )
                        PillButton(
                            text = "+",
                            onClick = increaseZoom,
                            variant = ButtonVariant.Secondary,
                            enabled = zoomPercent < DESKTOP_ZOOM_MAX_PERCENT,
                        )
                        PillButton(
                            text = t("settings.zoom.reset"),
                            onClick = resetZoom,
                            variant = ButtonVariant.Secondary,
                            enabled = zoomPercent != DESKTOP_ZOOM_DEFAULT_PERCENT,
                        )
                    }
                }
                Column(Modifier.fillMaxWidth().settingsSectionAnchor("settings.nav.access", sectionOffsets)) {
                    DirectAccessGuideCard(state)
                    TextButton(onClick = { connectionDetailsVisible = !connectionDetailsVisible }) {
                        Text(t("direct.diagnostics"), color = Onyx.OnSurfaceVariant)
                    }
                }
                if (connectionDetailsVisible) {
                    SectionCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = 0.dp,
                    ) {
                        SettingsGroupHeader(
                            t("settings.relay.title"),
                            t("settings.relay.body"),
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RelayTextField(
                                value = relayUrlDraft,
                                onValueChange = { relayUrlDraft = it },
                                label = t("settings.relay.url"),
                                placeholder = t("settings.relay.url.placeholder"),
                                enabled = !state.relayConfigurationBusy,
                            )
                            RelayTextField(
                                value = relayDeviceIdDraft,
                                onValueChange = { relayDeviceIdDraft = it },
                                label = t("settings.relay.id"),
                                placeholder = t("settings.relay.id.placeholder"),
                                enabled = !state.relayConfigurationBusy,
                            )
                            PermissionChoice(
                                t("settings.remote.title"),
                                state.remoteAccessEnabled,
                                enabled = state.relayUrl != null && !state.relayConfigurationBusy,
                                change = setRemoteAccess,
                            )
                            state.localizedRelayConfigurationMessage(languageTag)?.let { message ->
                                val isError =
                                    state.relayConfigurationMessageCode in
                                        setOf(
                                            RelayConfigurationMessageCode.ValidationFailed,
                                            RelayConfigurationMessageCode.SaveFailed,
                                            RelayConfigurationMessageCode.RemoveFailed,
                                            RelayConfigurationMessageCode.ConnectionFailed,
                                            RelayConfigurationMessageCode.IdentityRotationPending,
                                            RelayConfigurationMessageCode.IdentityRotationFailed,
                                        )
                                Text(
                                    message,
                                    color =
                                        if (isError) {
                                            Onyx.Error
                                        } else if (state.relayConnected) {
                                            Onyx.Primary
                                        } else {
                                            Onyx.OnSurfaceVariant
                                        },
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PillButton(
                                    text = if (state.relayConfigurationBusy) t("settings.relay.saving") else t("settings.relay.save"),
                                    onClick = { configureRelay(relayUrlDraft, relayDeviceIdDraft) },
                                    variant = ButtonVariant.Primary,
                                    enabled = !state.relayConfigurationBusy,
                                )
                                if (state.relayUrl != null) {
                                    PillButton(
                                        text = t("settings.relay.remove"),
                                        onClick = {
                                            relayUrlDraft = ""
                                            relayDeviceIdDraft = ""
                                            clearRelay()
                                        },
                                        variant = ButtonVariant.Secondary,
                                        enabled = !state.relayConfigurationBusy,
                                    )
                                }
                            }
                            if (state.relayConnected || state.relayConfigurationMessageCode == RelayConfigurationMessageCode.IdentityRotationPending) {
                                PillButton(
                                    text = t("settings.relay.rotate_identity"),
                                    onClick = { identityRotationConfirmationVisible = true },
                                    variant = ButtonVariant.Secondary,
                                    enabled = !state.relayConfigurationBusy,
                                )
                            }
                            PillButton(
                                text = if (relayGuideExpanded) t("settings.relay.hide_guide") else t("settings.relay.show_guide"),
                                onClick = { relayGuideExpanded = !relayGuideExpanded },
                                variant = ButtonVariant.Secondary,
                            )
                            if (relayGuideExpanded) {
                                RelayGuideCard()
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }

                SectionCard(
                    modifier = Modifier.fillMaxWidth().settingsSectionAnchor("settings.nav.privacy", sectionOffsets),
                    contentPadding = 0.dp,
                ) {
                    SettingsGroupHeader(t("settings.access.title"), t("settings.access.body"))
                    SettingsSwitchRow(
                        title = t("settings.input.title"),
                        description = if (state.remoteInputEnabled) t("settings.input.enabled") else t("settings.input.paused"),
                        checked = state.remoteInputEnabled,
                        onCheckedChange = setRemoteInput,
                        emphasizedOff = true,
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        title = t("settings.clipboard.title"),
                        description = t("settings.clipboard.body"),
                        checked = state.clipboardSyncEnabled,
                        onCheckedChange = setClipboardSync,
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        title = t("settings.autostart.title"),
                        description =
                            state.localizedAutostartMessage(languageTag)
                                ?: if (state.autostartAvailable) t("settings.autostart.available") else t("settings.autostart.unavailable"),
                        checked = state.autostartEnabled,
                        enabled = state.autostartAvailable,
                        onCheckedChange = setAutostart,
                    )
                }

                if (isLinux) {
                    SectionCard(
                        modifier = Modifier.fillMaxWidth().settingsSectionAnchor("settings.nav.firewall", sectionOffsets),
                        contentPadding = 18.dp,
                    ) {
                        Text(t("ufw.title"), color = Onyx.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            t("ufw.body"),
                            color = Onyx.OnSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        PillButton(
                            text = t("ufw.inspect"),
                            onClick = {
                                ufwApplyResult = null
                                ufwScope.launch {
                                    ufwInspection = withContext(Dispatchers.IO) { ufwManager.inspect() }
                                }
                            },
                            variant = ButtonVariant.Secondary,
                        )
                        when (val inspection = ufwInspection) {
                            is LinuxUfwInspection.Ready -> {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    tf("ufw.scope", inspection.scope.interfaceName, inspection.scope.cidr),
                                    color = Onyx.OnSurface,
                                    fontSize = 12.sp,
                                )
                                Spacer(Modifier.height(10.dp))
                                PillButton(
                                    text = t("ufw.review"),
                                    onClick = { ufwConsentVisible = true },
                                    variant = ButtonVariant.Primary,
                                )
                            }

                            LinuxUfwInspection.UfwInactive -> {
                                Text(t("ufw.inactive"), color = Onyx.Warning, fontSize = 12.sp)
                            }

                            is LinuxUfwInspection.Rejected -> {
                                Text(inspection.reason, color = Onyx.Error, fontSize = 12.sp)
                            }

                            else -> {
                                Unit
                            }
                        }
                        when (val result = ufwApplyResult) {
                            LinuxUfwApplyResult.Applied -> Text(t("ufw.applied"), color = Onyx.Primary, fontSize = 12.sp)
                            is LinuxUfwApplyResult.Failed -> Text(result.reason, color = Onyx.Error, fontSize = 12.sp)
                            else -> Unit
                        }
                    }
                }

                ManualConnectionCard(state, modifier = Modifier.settingsSectionAnchor("settings.nav.ssh", sectionOffsets))

                BoxWithConstraints(modifier = Modifier.fillMaxWidth().settingsSectionAnchor("settings.nav.capabilities", sectionOffsets)) {
                    if (maxWidth >= 720.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CapabilityCard(state, Modifier.weight(1f))
                            RelayDetailsCard(state, Modifier.weight(1f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CapabilityCard(state, Modifier.fillMaxWidth())
                            RelayDetailsCard(state, Modifier.fillMaxWidth())
                        }
                    }
                }

                if (state.monitors.isNotEmpty()) {
                    SectionCard(
                        modifier = Modifier.fillMaxWidth().settingsSectionAnchor("settings.nav.displays", sectionOffsets),
                        contentPadding = 18.dp,
                    ) {
                        Text(t("settings.displays"), color = Onyx.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        state.monitors.forEachIndexed { index, monitor ->
                            if (index > 0) Divider(color = Onyx.OutlineVariant, modifier = Modifier.padding(vertical = 11.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(monitor.name, color = Onyx.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        tf("settings.display.origin", monitor.width, monitor.height, monitor.originX, monitor.originY),
                                        color = Onyx.OnSurfaceVariant,
                                        fontSize = 11.sp,
                                    )
                                }
                                if (monitor.primary) StatusChip(t("settings.display.primary"), StatusTone.Neutral)
                            }
                        }
                    }
                }
                SectionCard(modifier = Modifier.fillMaxWidth(), contentPadding = 18.dp) {
                    Text(t("settings.language.title"), color = Onyx.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(t("settings.language.body"), color = Onyx.OnSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LanguageChoice(t("settings.language.system"), language == DesktopLanguage.System) { setLanguage(DesktopLanguage.System) }
                        LanguageChoice(t("settings.language.spanish"), language == DesktopLanguage.Spanish) { setLanguage(DesktopLanguage.Spanish) }
                        LanguageChoice(t("settings.language.english"), language == DesktopLanguage.English) { setLanguage(DesktopLanguage.English) }
                    }
                }
                SectionCard(
                    modifier = Modifier.fillMaxWidth().settingsSectionAnchor("settings.nav.app", sectionOffsets),
                    contentPadding = 18.dp,
                ) {
                    Text(t("settings.application"), color = Onyx.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (trayAvailable) t("settings.application.body") else t("tray.unavailable.close_exits"),
                        color = if (trayAvailable) Onyx.OnSurfaceVariant else Onyx.Warning,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    PillButton(t("settings.exit"), onClick = exitAegis, variant = ButtonVariant.Danger)
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    if (identityRotationConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { identityRotationConfirmationVisible = false },
            title = { Text(t("settings.relay.rotate_identity.title"), color = Onyx.OnSurface) },
            text = { Text(t("settings.relay.rotate_identity.body"), color = Onyx.OnSurfaceVariant) },
            confirmButton = {
                TextButton(
                    onClick = {
                        identityRotationConfirmationVisible = false
                        rotateRelayIdentity()
                    },
                ) {
                    Text(t("settings.relay.rotate_identity.confirm"), color = Onyx.Warning)
                }
            },
            dismissButton = {
                TextButton(onClick = { identityRotationConfirmationVisible = false }) {
                    Text(t("cancel"), color = Onyx.OnSurface)
                }
            },
            backgroundColor = Onyx.Container,
            contentColor = Onyx.OnSurface,
            shape = RoundedCornerShape(18.dp),
        )
    }
    if (trustStoreResetConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { trustStoreResetConfirmationVisible = false },
            title = { Text(t("trust.reset.title"), color = Onyx.OnSurface) },
            text = {
                Text(
                    t("trust.reset.body"),
                    color = Onyx.OnSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        trustStoreResetConfirmationVisible = false
                        resetTrustStoreAfterConsent()
                    },
                ) { Text(t("trust.reset.confirm"), color = Onyx.Error) }
            },
            dismissButton = {
                TextButton(onClick = { trustStoreResetConfirmationVisible = false }) {
                    Text(t("cancel"), color = Onyx.OnSurface)
                }
            },
            backgroundColor = Onyx.Container,
            contentColor = Onyx.OnSurface,
            shape = RoundedCornerShape(18.dp),
        )
    }
    val consentScope = (ufwInspection as? LinuxUfwInspection.Ready)?.scope
    if (ufwConsentVisible && consentScope != null) {
        AlertDialog(
            onDismissRequest = { ufwConsentVisible = false },
            title = { Text(t("ufw.consent.title"), color = Onyx.OnSurface) },
            text = {
                Text(
                    tf("ufw.consent.body", consentScope.interfaceName, consentScope.cidr),
                    color = Onyx.OnSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ufwConsentVisible = false
                        ufwScope.launch {
                            ufwApplyResult =
                                withContext(Dispatchers.IO) {
                                    ufwManager.apply(consentScope, explicitConsent = true)
                                }
                        }
                    },
                ) { Text(t("ufw.consent.confirm"), color = Onyx.Primary) }
            },
            dismissButton = {
                TextButton(onClick = { ufwConsentVisible = false }) {
                    Text(t("cancel"), color = Onyx.OnSurface)
                }
            },
            backgroundColor = Onyx.Container,
            contentColor = Onyx.OnSurface,
            shape = RoundedCornerShape(18.dp),
        )
    }
}

private const val SETTINGS_ANCHOR_THRESHOLD_PX = 120f

private fun Modifier.settingsSectionAnchor(
    key: String,
    offsets: MutableMap<String, Float>,
): Modifier = onGloballyPositioned { coordinates -> offsets[key] = coordinates.positionInParent().y }

@Composable
@Suppress("FunctionNaming")
private fun SettingsNavigationRail(
    entries: List<String>,
    selected: String,
    onNavigate: (String) -> Unit,
) {
    Column(modifier = Modifier.width(190.dp).padding(end = 22.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(t("settings.nav.heading"), color = Onyx.Muted, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 10.dp))
        entries.forEach { key ->
            val active = selected == key
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val indicatorHeight by animateDpAsState(if (active) 18.dp else 0.dp, tween(180))
            val labelColor by animateColorAsState(if (active || hovered) Onyx.OnSurface else Onyx.OnSurfaceVariant, tween(140))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .hoverable(interaction)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onNavigate(key) }
                        .padding(vertical = 9.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(3.dp).height(indicatorHeight).background(Onyx.Primary, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(
                    t(key),
                    color = labelColor,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun ManualConnectionCard(
    state: DesktopAgentState,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier.fillMaxWidth(), contentPadding = 18.dp) {
        Text(t("manual.title"), color = Onyx.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(t("manual.body"), color = Onyx.OnSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(16.dp))
        ManualConnectionDataField(t("manual.host"), state.manualConnectionInfo.lanHost)
        Divider(color = Onyx.OutlineVariant, modifier = Modifier.padding(vertical = 12.dp))
        ManualConnectionDataField(t("manual.username"), state.manualConnectionInfo.localUsername)
        Divider(color = Onyx.OutlineVariant, modifier = Modifier.padding(vertical = 12.dp))
        ManualConnectionDataField(t("manual.fingerprint"), state.manualConnectionInfo.sshHostKeyFingerprint)
        state.manualConnectionInfo.sshHostKeyAlgorithm?.takeIf { it.isNotBlank() }?.let { algorithm ->
            Spacer(Modifier.height(7.dp))
            DataField(t("manual.algorithm"), algorithm, monospace = true)
        }
        Spacer(Modifier.height(14.dp))
        Surface(color = Onyx.WarningContainer.copy(alpha = 0.72f), shape = RoundedCornerShape(10.dp)) {
            Text(t("manual.credential"), color = Onyx.Warning, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun ManualConnectionDataField(
    label: String,
    data: ManualConnectionValue,
) {
    val displayValue =
        when (data.status) {
            ManualConnectionValueStatus.Available -> data.value?.takeIf { it.isNotBlank() } ?: t("manual.unavailable")
            ManualConnectionValueStatus.PermissionRequired -> t("manual.permission")
            ManualConnectionValueStatus.Error -> t("manual.error")
            ManualConnectionValueStatus.NotAvailable -> t("manual.unavailable")
        }
    DataField(label, displayValue, monospace = true)
}

@Composable
private fun RelayGuideCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Onyx.PrimaryContainer.copy(alpha = 0.16f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Onyx.Primary.copy(alpha = 0.32f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("settings.relay.guide.title"), color = Onyx.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(t("settings.relay.guide.summary"), color = Onyx.OnSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
            Text(t("settings.relay.guide.step1"), color = Onyx.OnSurface, fontSize = 12.sp, lineHeight = 18.sp)
            Text(t("settings.relay.guide.step2"), color = Onyx.OnSurface, fontSize = 12.sp, lineHeight = 18.sp)
            Text(t("settings.relay.guide.step3"), color = Onyx.OnSurface, fontSize = 12.sp, lineHeight = 18.sp)
            Text(t("settings.relay.guide.security"), color = Onyx.Warning, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun LanguageChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors =
            ButtonDefaults.buttonColors(
                backgroundColor = if (selected) Onyx.PrimaryContainer else Onyx.ContainerHigh,
                contentColor = if (selected) Onyx.OnPrimaryContainer else Onyx.OnSurfaceVariant,
            ),
        elevation = ButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        modifier = Modifier.heightIn(min = 40.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun RelayTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = Onyx.Muted) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            TextFieldDefaults.outlinedTextFieldColors(
                textColor = Onyx.OnSurface,
                cursorColor = Onyx.Primary,
                focusedBorderColor = Onyx.Primary,
                unfocusedBorderColor = Onyx.OutlineVariant,
                focusedLabelColor = Onyx.Primary,
                unfocusedLabelColor = Onyx.OnSurfaceVariant,
                backgroundColor = Onyx.Background,
                disabledTextColor = Onyx.Disabled,
                disabledBorderColor = Onyx.OutlineVariant,
                disabledLabelColor = Onyx.Disabled,
            ),
    )
}

@Composable
private fun SettingsGroupHeader(
    title: String,
    subtitle: String,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 17.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Onyx.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, color = Onyx.OnSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    emphasizedOff: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) Onyx.OnSurface else Onyx.Disabled,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                color =
                    when {
                        !enabled -> Onyx.Disabled
                        emphasizedOff && !checked -> Onyx.Error
                        else -> Onyx.OnSurfaceVariant
                    },
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
        Spacer(Modifier.width(18.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = Onyx.OnSurface,
                    checkedTrackColor = Onyx.Primary,
                    checkedTrackAlpha = 1f,
                    uncheckedThumbColor = Onyx.OnSurfaceVariant,
                    uncheckedTrackColor = Onyx.ContainerHigh,
                    uncheckedTrackAlpha = 1f,
                    disabledCheckedThumbColor = Onyx.Disabled,
                    disabledUncheckedThumbColor = Onyx.Disabled,
                ),
        )
    }
}

@Composable
private fun SettingsDivider() {
    Divider(color = Onyx.OutlineVariant, modifier = Modifier.padding(horizontal = 18.dp))
}

@Composable
private fun CapabilityCard(
    state: DesktopAgentState,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier, contentPadding = 18.dp) {
        Text(t("capabilities.title"), color = Onyx.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        StatusDataRow(t("capabilities.pairing_server"), state.pairingServerRunning)
        Spacer(Modifier.height(11.dp))
        StatusDataRow(t("capabilities.openssh"), state.openSshAvailable)
        Spacer(Modifier.height(11.dp))
        CapabilityDataRow(t("capabilities.capture"), state.captureCapability)
        Spacer(Modifier.height(11.dp))
        CapabilityDataRow(t("capabilities.input"), state.inputCapability)
        state.linuxPreflight?.let { report ->
            Spacer(Modifier.height(14.dp))
            Text(
                "${t("preflight.linux")} · ${report.sessionBackend} · ${report.compositor ?: t("preflight.compositor.unknown")}",
                color = Onyx.OnSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            report.capabilities.forEach { (name, capability) ->
                LinuxPreflightDataRow(name, capability)
                Spacer(Modifier.height(5.dp))
            }
        }
        state.windowsPreflight?.let { report ->
            Spacer(Modifier.height(14.dp))
            Text(
                "${t("preflight.windows")} · ${report.sessionBackend}",
                color = Onyx.OnSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            report.capabilities.forEach { (name, capability) ->
                WindowsPreflightDataRow(name, capability)
                Spacer(Modifier.height(5.dp))
            }
        }
    }
}

@Composable
private fun RelayDetailsCard(
    state: DesktopAgentState,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier, contentPadding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                t("relay.title"),
                color = Onyx.OnSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            StatusChip(
                if (state.relayConnected) t("relay.connected") else t("relay.not_connected"),
                if (state.relayConnected) StatusTone.Positive else StatusTone.Neutral,
            )
        }
        Spacer(Modifier.height(14.dp))
        DataField(t("relay.url"), state.relayUrl ?: t("relay.not_configured"), monospace = true)
        Spacer(Modifier.height(11.dp))
        DataField(t("relay.device_id"), state.relayDeviceId ?: t("relay.not_registered"), monospace = true)
        Spacer(Modifier.height(11.dp))
        DataField(t("relay.pending"), state.pendingRelaySessions.size.toString(), monospace = true)
    }
}

@Composable
private fun StatusDataRow(
    label: String,
    active: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(active)
        Spacer(Modifier.width(9.dp))
        Text(label, color = Onyx.OnSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            if (active) t("status.available") else t("status.unavailable"),
            color = if (active) Onyx.Primary else Onyx.Muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun CapabilityDataRow(
    label: String,
    status: CapabilityStatus,
) {
    val tone =
        when (status) {
            CapabilityStatus.Available -> StatusTone.Positive
            CapabilityStatus.Degraded -> StatusTone.Warning
            CapabilityStatus.PermissionRequired -> StatusTone.Warning
            CapabilityStatus.Unavailable -> StatusTone.Danger
            CapabilityStatus.Unknown -> StatusTone.Neutral
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(tone.color, CircleShape))
        Spacer(Modifier.width(9.dp))
        Text(label, color = Onyx.OnSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(capabilityLabel(status), color = tone.color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Divider(color = Onyx.OutlineVariant)
        Column(
            modifier = Modifier.padding(horizontal = contentPadding, vertical = contentPadding.coerceAtMost(20.dp)),
            content = content,
        )
    }
}

@Composable
private fun DataField(
    label: String,
    value: String,
    monospace: Boolean = false,
    copyable: Boolean = false,
    emphasis: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label.uppercase(), color = Onyx.Muted, fontSize = 10.sp, letterSpacing = 0.7.sp, fontWeight = FontWeight.Medium)
            SelectionContainer {
                Text(
                    value,
                    color = Onyx.OnSurface,
                    fontSize = if (emphasis) 18.sp else 12.sp,
                    lineHeight = if (emphasis) 24.sp else 18.sp,
                    letterSpacing = if (emphasis) 2.sp else 0.sp,
                    fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
                )
            }
        }
        if (copyable) CopyButton(value)
    }
}

@Composable
private fun CopyButton(value: String) {
    var copiedAt by remember { mutableStateOf(0L) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copiedAt) {
        if (copiedAt > 0L) {
            copied = true
            delay(1_600L)
            copied = false
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            copied -> Onyx.PrimaryContainer.copy(alpha = 0.35f)
            hovered -> Onyx.ContainerHigh
            else -> Color.Transparent
        },
        tween(140),
    )
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(background)
                .border(1.dp, Onyx.OutlineVariant.copy(alpha = if (hovered || copied) 0.9f else 0.5f), RoundedCornerShape(999.dp))
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable {
                    copyToSystemClipboard(value)
                    copiedAt = System.currentTimeMillis()
                }.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            if (copied) t("action.copied") else t("action.copy"),
            color = if (copied) Onyx.Primary else Onyx.OnSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EmptyState(
    title: String,
    message: String,
) {
    SectionCard(modifier = Modifier.fillMaxWidth(), contentPadding = 28.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(42.dp).background(Onyx.ContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(9.dp).background(Onyx.OnSurfaceVariant, CircleShape))
            }
            Spacer(Modifier.height(13.dp))
            Text(title, color = Onyx.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                color = Onyx.OnSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
    }
}

@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    variant: ButtonVariant,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val background =
        when (variant) {
            ButtonVariant.Primary -> Onyx.PrimaryContainer
            ButtonVariant.Secondary -> Onyx.ContainerHigh
            ButtonVariant.Danger -> Onyx.ErrorContainer.copy(alpha = 0.70f)
        }
    val foreground =
        when (variant) {
            ButtonVariant.Primary -> Onyx.OnPrimaryContainer
            ButtonVariant.Secondary -> Onyx.OnSurface
            ButtonVariant.Danger -> Onyx.Error
        }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = CircleShape,
        colors =
            ButtonDefaults.buttonColors(
                backgroundColor = background,
                contentColor = foreground,
                disabledBackgroundColor = Onyx.ContainerHigh.copy(alpha = 0.5f),
                disabledContentColor = Onyx.Disabled,
            ),
        elevation = ButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 0.dp, disabledElevation = 0.dp),
    ) {
        Text(text, color = if (enabled) foreground else Onyx.Disabled, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StatusChip(
    text: String,
    tone: StatusTone,
) {
    Box(
        modifier =
            Modifier
                .background(tone.background, RoundedCornerShape(999.dp))
                .heightIn(min = 28.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = tone.color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    Box(
        modifier =
            Modifier
                .size(9.dp)
                .background(if (active) Onyx.Primary else Onyx.Muted, CircleShape),
    )
}

@Composable
private fun QrCode(payload: String?) {
    val qrDescription = t("qr.description")
    val density = LocalDensity.current.density
    val encodePx = (QR_DISPLAY_MAX.value * density).toInt().coerceAtLeast(160)
    val qrImage =
        remember(payload, encodePx) {
            payload?.takeIf(String::isNotBlank)?.let { encodeQrImage(it, encodePx) }
        }
    Box(
        modifier =
            Modifier
                .widthIn(max = QR_DISPLAY_MAX + 20.dp)
                .fillMaxWidth()
                .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 6.dp.toPx()
            val arm = 24.dp.toPx()
            val stroke = 2.dp.toPx()
            val right = size.width - inset
            val bottom = size.height - inset
            drawLine(Onyx.Primary, Offset(inset, inset), Offset(inset + arm, inset), stroke, cap = StrokeCap.Round)
            drawLine(Onyx.Primary, Offset(inset, inset), Offset(inset, inset + arm), stroke, cap = StrokeCap.Round)
            drawLine(Onyx.Primary, Offset(right - arm, inset), Offset(right, inset), stroke, cap = StrokeCap.Round)
            drawLine(Onyx.Primary, Offset(right, inset), Offset(right, inset + arm), stroke, cap = StrokeCap.Round)
            drawLine(Onyx.Primary, Offset(inset, bottom), Offset(inset + arm, bottom), stroke, cap = StrokeCap.Round)
            drawLine(Onyx.Primary, Offset(inset, bottom - arm), Offset(inset, bottom), stroke, cap = StrokeCap.Round)
            drawLine(Onyx.Primary, Offset(right - arm, bottom), Offset(right, bottom), stroke, cap = StrokeCap.Round)
            drawLine(Onyx.Primary, Offset(right, bottom - arm), Offset(right, bottom), stroke, cap = StrokeCap.Round)
        }
        Surface(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            color = Color.White,
            shape = RoundedCornerShape(0.dp),
            elevation = 0.dp,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                if (payload.isNullOrBlank() || qrImage == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val spinnerTransition = rememberInfiniteTransition()
                        val spinnerAngle by spinnerTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
                        )
                        Canvas(Modifier.size(18.dp)) {
                            drawArc(
                                color = Onyx.PrimaryContainer,
                                startAngle = spinnerAngle,
                                sweepAngle = 270f,
                                useCenter = false,
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(t("qr.preparing"), color = Onyx.PrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    }
                } else {
                    Image(
                        bitmap = qrImage,
                        contentDescription = qrDescription,
                        contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private val QR_DISPLAY_MAX = 236.dp

private fun encodeQrMatrix(payload: String): BitMatrix =
    QRCodeWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        1,
        1,
        mapOf(
            EncodeHintType.MARGIN to 4,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        ),
    )

private fun encodeQrImage(
    payload: String,
    targetPixels: Int,
): ImageBitmap {
    val matrix = encodeQrMatrix(payload)
    val module = (targetPixels / matrix.width).coerceAtLeast(2)
    val width = matrix.width * module
    val height = matrix.height * module
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            val color = if (matrix[x, y]) black else white
            val left = x * module
            val top = y * module
            fillQrModule(image, color, left, top, module)
        }
    }
    return image.toComposeImageBitmap()
}

private fun fillQrModule(
    image: BufferedImage,
    color: Int,
    left: Int,
    top: Int,
    module: Int,
) {
    for (dy in 0 until module) {
        for (dx in 0 until module) {
            image.setRGB(left + dx, top + dy, color)
        }
    }
}

@Composable
private fun permissionSummary(permissions: DevicePermissions): String {
    val labels =
        buildList {
            if (permissions.visual) add(t("permission.screen"))
            if (permissions.input) add(t("permission.input"))
            if (permissions.terminal) add(t("permission.terminal"))
            if (permissions.sftp) add(t("permission.files"))
            if (permissions.clipboard) add(t("permission.clipboard"))
            if (permissions.wakeOnLan) add(t("permission.wol"))
            if (permissions.remoteAccess) add(t("permission.remote"))
        }
    return if (labels.isEmpty()) t("permissions.none") else tf("permissions.prefix", labels.joinToString(" · "))
}

@Composable
private fun capabilityLabel(status: CapabilityStatus): String =
    when (status) {
        CapabilityStatus.Unknown -> t("status.checking")
        CapabilityStatus.Available -> t("status.available")
        CapabilityStatus.Degraded -> t("status.degraded")
        CapabilityStatus.PermissionRequired -> t("status.permission_required")
        CapabilityStatus.Unavailable -> t("status.unavailable")
    }

@Composable
private fun preflightCapabilityLabel(name: String): String {
    val key = "preflight.capability.$name"
    val translated = t(key)
    return if (translated.startsWith("preflight.capability.")) name else translated
}

@Composable
private fun preflightStatusLabel(statusName: String): String =
    when (statusName) {
        "Available" -> t("status.available")
        "Degraded" -> t("status.degraded")
        "Unavailable" -> t("status.unavailable")
        "Unknown" -> t("status.checking")
        else -> statusName
    }

@Composable
private fun HostHealthBanners(state: DesktopAgentState) {
    val publicNetwork =
        state.windowsPreflight
            ?.capabilities
            ?.get("network-profile")
            ?.cause == "NETWORK_PUBLIC"
    if (!state.openSshAvailable) {
        AttentionBanner(t("home.ssh.unavailable.title"), t("home.ssh.unavailable.body"))
        Spacer(Modifier.height(12.dp))
    }
    if (publicNetwork) {
        AttentionBanner(t("home.network.public.title"), t("home.network.public.body"))
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AttentionBanner(
    title: String,
    body: String,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Onyx.WarningContainer.copy(alpha = 0.35f)),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(Onyx.Warning, size = Size(3.dp.toPx(), size.height))
                },
        )
        Column(Modifier.fillMaxWidth().padding(start = 21.dp, end = 18.dp, top = 14.dp, bottom = 14.dp)) {
            Text(title, color = Onyx.Warning, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(body, color = Onyx.OnSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun LinuxPreflightDataRow(
    label: String,
    capability: LinuxPreflightCapability,
) {
    val color =
        when (capability.status) {
            LinuxPreflightStatus.Available -> Onyx.Primary
            LinuxPreflightStatus.Degraded -> Onyx.Warning
            LinuxPreflightStatus.Unavailable -> Onyx.Error
        }
    preflightDetailRow(preflightCapabilityLabel(label), preflightStatusLabel(capability.status.name), capability.cause, capability.nextAction, color)
}

@Composable
@Suppress("FunctionNaming")
private fun WindowsPreflightDataRow(
    label: String,
    capability: WindowsPreflightCapability,
) {
    val color =
        when (capability.status) {
            WindowsPreflightStatus.Available -> Onyx.Primary
            WindowsPreflightStatus.Degraded -> Onyx.Warning
            WindowsPreflightStatus.Unavailable -> Onyx.Error
        }
    preflightDetailRow(preflightCapabilityLabel(label), preflightStatusLabel(capability.status.name), capability.cause, capability.nextAction, color)
}

@Composable
private fun preflightDetailRow(
    label: String,
    statusName: String,
    cause: String,
    nextAction: String,
    color: Color,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Onyx.OnSurfaceVariant, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(statusName, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        val detail =
            when {
                cause == "NETWORK_PUBLIC" -> t("preflight.network.public.detail")
                LocalDesktopLanguage.current.resolved() == DesktopLanguage.Spanish -> null
                else -> "$cause. $nextAction"
            }
        if (!detail.isNullOrBlank()) {
            Text(
                detail,
                color = Onyx.Muted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(start = 15.dp),
            )
        }
    }
}

@Composable
private fun formatTime(epochMillis: Long): String {
    val language = LocalDesktopLanguage.current.resolved()
    return runCatching {
        DateTimeFormatter
            .ofPattern("HH:mm", language.locale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMillis))
    }.getOrDefault(t("time.now"))
}

@Composable
private fun formatDateTime(epochMillis: Long): String {
    val language = LocalDesktopLanguage.current.resolved()
    return runCatching {
        DateTimeFormatter
            .ofPattern("dd MMM yyyy · HH:mm", language.locale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMillis))
    }.getOrDefault(t("time.recently"))
}

private enum class DesktopSection(
    val key: String,
) {
    Home("home"),
    Devices("devices"),
    Activity("activity"),
    Settings("settings"),
}

private enum class ButtonVariant {
    Primary,
    Secondary,
    Danger,
}

private enum class StatusTone(
    val color: Color,
    val background: Color,
) {
    Positive(Onyx.Primary, Onyx.PrimaryContainer.copy(alpha = 0.30f)),
    Warning(Onyx.Warning, Onyx.WarningContainer),
    Danger(Onyx.Error, Onyx.ErrorContainer.copy(alpha = 0.55f)),
    Neutral(Onyx.OnSurfaceVariant, Onyx.ContainerHigh),
}

internal object Onyx {
    val Background = Color(0xFF000000)
    val Sidebar = Color(0xFF0E0E0E)
    val ContainerLow = Color(0xFF1B1B1B)
    val Container = Color(0xFF1F1F1F)
    val ContainerHigh = Color(0xFF2A2A2A)
    val OnSurface = Color(0xFFE2E2E2)
    val OnSurfaceVariant = Color(0xFFBFC9C0)
    val Muted = Color(0xFF89938B)
    val Disabled = Color(0xFF686E69)
    val OutlineVariant = Color(0xFF3F4942)
    val Primary = Color(0xFF89D7AC)
    val PrimaryContainer = Color(0xFF1D6E4B)
    val OnPrimary = Color(0xFF003823)
    val OnPrimaryContainer = Color(0xFFB9F5D2)
    val Secondary = Color(0xFFC9C6C5)
    val OnSecondary = Color(0xFF313030)
    val Error = Color(0xFFFFB4AB)
    val ErrorContainer = Color(0xFF5A1B1B)
    val Warning = Color(0xFFFFD18B)
    val WarningContainer = Color(0xFF44351F)
}

private class SingleInstanceCoordinator private constructor(
    private val serverSocket: ServerSocket?,
) : Closeable {
    @Volatile
    private var activationHandler: (() -> Unit)? = null
    private val pendingActivation = AtomicBoolean(false)

    init {
        if (serverSocket != null) listenForActivation()
    }

    fun setActivationHandler(handler: (() -> Unit)?) {
        activationHandler = handler
        if (handler != null && pendingActivation.getAndSet(false)) {
            handler()
        }
    }

    private fun listenForActivation() {
        thread(name = "aegis-single-instance", isDaemon = true) {
            val server = serverSocket ?: return@thread
            while (!server.isClosed) {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = INSTANCE_IO_TIMEOUT_MILLIS
                        val request = socket.getInputStream().bufferedReader().readLine()
                        if (request == ACTIVATE_MESSAGE) {
                            socket.getOutputStream().bufferedWriter().apply {
                                write(ACTIVATE_ACK)
                                newLine()
                                flush()
                            }
                            val handler = activationHandler
                            if (handler == null) pendingActivation.set(true) else handler()
                        }
                    }
                }.onFailure {
                    if (!server.isClosed) Thread.yield()
                }
            }
        }
    }

    override fun close() {
        activationHandler = null
        runCatching { serverSocket?.close() }
    }

    companion object {
        fun acquireOrActivate(): SingleInstanceCoordinator? {
            val loopback = InetAddress.getLoopbackAddress()
            return try {
                val server =
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(loopback, INSTANCE_PORT), 4)
                    }
                SingleInstanceCoordinator(server)
            } catch (_: BindException) {
                if (activateExisting(loopback)) null else SingleInstanceCoordinator(null)
            } catch (_: Exception) {
                SingleInstanceCoordinator(null)
            }
        }

        private fun activateExisting(loopback: InetAddress): Boolean =
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(loopback, INSTANCE_PORT), INSTANCE_CONNECT_TIMEOUT_MILLIS)
                    socket.soTimeout = INSTANCE_IO_TIMEOUT_MILLIS
                    socket.getOutputStream().bufferedWriter().apply {
                        write(ACTIVATE_MESSAGE)
                        newLine()
                        flush()
                    }
                    socket.getInputStream().bufferedReader().readLine() == ACTIVATE_ACK
                }
            }.getOrDefault(false)
    }
}

private const val INSTANCE_PORT = 48292
private const val INSTANCE_CONNECT_TIMEOUT_MILLIS = 450
private const val INSTANCE_IO_TIMEOUT_MILLIS = 900
private const val ACTIVATE_MESSAGE = "AEGIS_ACTIVATE_V1"
private const val ACTIVATE_ACK = "AEGIS_ACTIVE_V1"

@Composable
private fun DirectAccessGuideCard(state: DesktopAgentState) {
    var publicGuide by remember { mutableStateOf(false) }
    SectionCard(Modifier.fillMaxWidth(), contentPadding = 18.dp) {
        Text(
            t("direct.title"),
            color = Onyx.OnSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                t("direct.simple"),
                { publicGuide = false },
                if (!publicGuide) ButtonVariant.Primary else ButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            PillButton(
                t("direct.public"),
                { publicGuide = true },
                if (publicGuide) ButtonVariant.Primary else ButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            t(if (publicGuide) "direct.public.guide" else "direct.private.guide"),
            color = Onyx.OnSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        state.manualConnectionInfo.lanHost.value
            ?.let { DataField(t("direct.local.address"), it, monospace = true) }
        Spacer(Modifier.height(10.dp))
        Text(
            t("direct.lifecycle"),
            color = Onyx.OnSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DevicePermissionEditor(
    device: DeviceAuthorization,
    save: (String, DevicePermissions) -> Unit,
    busy: Boolean,
    error: String?,
) {
    var draft by remember(device.remoteDeviceId, device.permissions) { mutableStateOf(device.permissions) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("permissions.title"), color = Onyx.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        PermissionChoice(t("permission.visual"), draft.visual, enabled = !busy) { draft = draft.copy(visual = it, input = draft.input && it) }
        PermissionChoice(t("permission.input"), draft.input, enabled = draft.visual && !busy) { draft = draft.copy(input = it) }
        PermissionChoice(t("permission.clipboard"), draft.clipboard, enabled = !busy) { draft = draft.copy(clipboard = it) }
        PermissionChoice(t("permissions.files"), draft.terminal || draft.sftp, enabled = !busy) { draft = draft.copy(terminal = it, sftp = it) }
        PermissionChoice(t("permission.wol"), draft.wakeOnLan, enabled = !busy) { draft = draft.copy(wakeOnLan = it) }
        error?.let { Text(it, color = Onyx.Error, fontSize = 12.sp) }
        Text(t("permissions.hint"), color = Onyx.OnSurfaceVariant, fontSize = 11.sp, lineHeight = 17.sp)
        PillButton(
            t("permissions.save"),
            { save(device.remoteDeviceId.value, draft) },
            ButtonVariant.Primary,
            enabled = !busy && draft != device.permissions,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PermissionChoice(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    change: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material.Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, color = if (enabled) Onyx.OnSurface else Onyx.Disabled, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}
