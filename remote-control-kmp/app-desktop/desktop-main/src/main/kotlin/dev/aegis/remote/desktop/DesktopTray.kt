package dev.aegis.remote.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

internal const val TRAY_MENU_WIDTH_DP = 288
private const val TRAY_MENU_VERTICAL_PADDING_DP = 8

internal fun trayMenuHeightDp(entries: List<TrayMenuEntry>): Int = entries.sumOf { it.height.value.toInt() } + TRAY_MENU_VERTICAL_PADDING_DP * 2

/**
 * Registers an AWT tray icon with Onyx-styled behaviour: left click opens the app, right click
 * opens a Compose popup menu. Returns whether the icon could actually be added so callers can
 * fall back to "closing the window exits" when no tray host is available (common on Wayland).
 */
@Composable
internal fun rememberAegisTrayIcon(
    icon: BufferedImage,
    tooltip: String,
    onPrimaryClick: () -> Unit,
    onOpenMenu: (Point) -> Unit,
): Boolean {
    var available by remember { mutableStateOf(false) }
    val trayIcon =
        remember(icon) {
            TrayIcon(scaleTrayImage(icon), tooltip).apply { isImageAutoSize = true }
        }
    SideEffect { trayIcon.toolTip = tooltip }

    DisposableEffect(trayIcon) {
        val listener =
            object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger) {
                        onOpenMenu(currentPointerLocation(e))
                    }
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) onPrimaryClick()
                }
            }
        trayIcon.addMouseListener(listener)
        val tray = runCatching { SystemTray.getSystemTray() }.getOrNull()
        available = tray != null && runCatching { tray.add(trayIcon) }.isSuccess
        onDispose {
            trayIcon.removeMouseListener(listener)
            if (available) runCatching { tray?.remove(trayIcon) }
        }
    }
    return available
}

private fun currentPointerLocation(event: MouseEvent): Point = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: Point(event.x, event.y)

private fun scaleTrayImage(source: BufferedImage): Image {
    val target = runCatching { SystemTray.getSystemTray().trayIconSize }.getOrNull() ?: return source
    if (target.width <= 0 || target.height <= 0) return source
    return source.getScaledInstance(target.width, target.height, Image.SCALE_SMOOTH)
}

/**
 * Places a [TRAY_MENU_WIDTH_DP] x [heightDp] popup next to [anchor], flipping it so it stays inside
 * the usable area of the monitor that contains the pointer (above a bottom taskbar, below a top bar).
 */
internal fun trayMenuPosition(
    anchor: Point,
    widthDp: Int,
    heightDp: Int,
    screen: Rectangle,
): Point {
    val x = if (anchor.x + widthDp > screen.x + screen.width) anchor.x - widthDp else anchor.x
    val y = if (anchor.y + heightDp > screen.y + screen.height) anchor.y - heightDp else anchor.y
    return Point(
        x.coerceIn(screen.x, maxOf(screen.x, screen.x + screen.width - widthDp)),
        y.coerceIn(screen.y, maxOf(screen.y, screen.y + screen.height - heightDp)),
    )
}

private fun usableScreenBounds(anchor: Point): Rectangle {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val device =
        environment.screenDevices.firstOrNull { it.defaultConfiguration.bounds.contains(anchor) }
            ?: environment.defaultScreenDevice
    val configuration = device.defaultConfiguration
    val bounds = Rectangle(configuration.bounds)
    val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(configuration) }.getOrNull()
    val hasInsets = insets != null && (insets.left or insets.top or insets.right or insets.bottom) != 0
    if (hasInsets) {
        bounds.x += insets.left
        bounds.y += insets.top
        bounds.width -= insets.left + insets.right
        bounds.height -= insets.top + insets.bottom
    } else if (device == environment.defaultScreenDevice) {
        // Some toolkits (Windows with per-monitor DPI, several Wayland setups) report zero insets;
        // the maximum window bounds still exclude the taskbar/panel on the primary screen.
        runCatching { environment.maximumWindowBounds }.getOrNull()?.let { return Rectangle(it) }
    }
    return bounds
}

@Composable
internal fun TrayMenuWindow(
    anchor: Point,
    entries: List<TrayMenuEntry>,
    zoomPercent: Int,
    onDismiss: () -> Unit,
) {
    val scale = zoomPercent / 100f
    val widthDp = (TRAY_MENU_WIDTH_DP * scale).toInt()
    val heightDp = (trayMenuHeightDp(entries) * scale).toInt()
    val position = remember(anchor, widthDp, heightDp) { trayMenuPosition(anchor, widthDp, heightDp, usableScreenBounds(anchor)) }
    val windowState =
        rememberWindowState(
            position = WindowPosition.Absolute(position.x.dp, position.y.dp),
            size = DpSize(widthDp.dp, heightDp.dp),
        )

    Window(
        onCloseRequest = onDismiss,
        state = windowState,
        title = "Aegis",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
    ) {
        var focusGained by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            window.toFront()
            window.requestFocus()
        }
        DisposableEffect(window) {
            val listener =
                object : WindowFocusListener {
                    override fun windowGainedFocus(e: WindowEvent) {
                        focusGained = true
                    }

                    override fun windowLostFocus(e: WindowEvent) {
                        if (focusGained) onDismiss()
                    }
                }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }

        val baseDensity = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides DesktopZoomPreferences.effectiveDensity(baseDensity, zoomPercent),
        ) {
            OnyxTheme {
                val appear = remember { MutableTransitionState(false).apply { targetState = true } }
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                    onDismiss()
                                    true
                                } else {
                                    false
                                }
                            },
                ) {
                    AnimatedVisibility(
                        visibleState = appear,
                        enter = fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.96f),
                    ) {
                        TrayMenuSurface(entries, onDismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrayMenuSurface(
    entries: List<TrayMenuEntry>,
    onDismiss: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(Onyx.ContainerLow)
                .border(1.dp, Onyx.OutlineVariant.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                .padding(vertical = TRAY_MENU_VERTICAL_PADDING_DP.dp),
    ) {
        entries.forEach { entry ->
            when (entry) {
                is TrayMenuEntry.Header -> {
                    TrayHeader(entry)
                }

                is TrayMenuEntry.Info -> {
                    TrayInfo(entry)
                }

                TrayMenuEntry.Divider -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(entry.height)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .background(Onyx.OutlineVariant.copy(alpha = 0.45f)),
                    )
                }

                is TrayMenuEntry.Toggle -> {
                    TrayToggleRow(entry)
                }

                is TrayMenuEntry.Action -> {
                    TrayActionRow(entry) {
                        entry.onClick()
                        if (!entry.keepOpen) onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun TrayHeader(entry: TrayMenuEntry.Header) {
    Row(
        modifier = Modifier.fillMaxWidth().height(entry.height).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusPulseDot(ready = entry.ready)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, color = Onyx.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(entry.subtitle, color = Onyx.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StatusPulseDot(ready: Boolean) {
    val pulse = rememberInfiniteTransition()
    val haloAlpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
    )
    val haloScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
    )
    val color = if (ready) Onyx.Primary else Onyx.Warning
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        if (ready) {
            Box(
                Modifier
                    .size((8 * haloScale).dp)
                    .alpha(haloAlpha)
                    .background(color, CircleShape),
            )
        }
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}

@Composable
private fun TrayInfo(entry: TrayMenuEntry.Info) {
    Box(Modifier.fillMaxWidth().height(entry.height).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            entry.text,
            color = if (entry.accent) Onyx.Warning else Onyx.OnSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = if (entry.accent) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun TrayActionRow(
    entry: TrayMenuEntry.Action,
    onActivate: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        if (hovered) {
            if (entry.destructive) Onyx.ErrorContainer.copy(alpha = 0.55f) else Onyx.ContainerHigh
        } else {
            Color.Transparent
        },
        tween(120),
    )
    val labelColor = if (entry.destructive) Onyx.Error else Onyx.OnSurface
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(entry.height)
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(background)
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onActivate)
                .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(entry.label, color = labelColor, fontSize = 13.sp, modifier = Modifier.weight(1f))
        entry.trailing?.let { Text(it, color = Onyx.Muted, fontSize = 11.sp) }
    }
}

@Composable
private fun TrayToggleRow(entry: TrayMenuEntry.Toggle) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (hovered) Onyx.ContainerHigh else Color.Transparent, tween(120))
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(entry.height)
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(background)
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { entry.onToggle(!entry.checked) }
                .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(entry.label, color = Onyx.OnSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        MiniSwitch(entry.checked)
    }
}

@Composable
private fun MiniSwitch(checked: Boolean) {
    val track by animateColorAsState(if (checked) Onyx.PrimaryContainer else Onyx.ContainerHigh, tween(160))
    val knob by animateColorAsState(if (checked) Onyx.Primary else Onyx.Muted, tween(160))
    val offset by animateDpAsState(if (checked) 14.dp else 2.dp, tween(160))
    Box(
        Modifier
            .width(30.dp)
            .height(18.dp)
            .background(track, RoundedCornerShape(999.dp))
            .border(1.dp, Onyx.OutlineVariant.copy(alpha = 0.6f), RoundedCornerShape(999.dp)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = offset)
                .size(14.dp)
                .background(knob, CircleShape),
        )
    }
}
