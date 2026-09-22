package dev.aegis.remote.desktop

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Entries rendered by the custom tray menu. Heights are fixed so the popup window can be
 * sized before its content is composed.
 */
internal sealed interface TrayMenuEntry {
    val height: Dp

    data class Header(
        val title: String,
        val subtitle: String,
        val ready: Boolean,
    ) : TrayMenuEntry {
        override val height: Dp = 66.dp
    }

    data class Info(
        val text: String,
        val accent: Boolean = false,
    ) : TrayMenuEntry {
        override val height: Dp = 26.dp
    }

    data object Divider : TrayMenuEntry {
        override val height: Dp = 13.dp
    }

    data class Toggle(
        val label: String,
        val checked: Boolean,
        val onToggle: (Boolean) -> Unit,
    ) : TrayMenuEntry {
        override val height: Dp = 38.dp
    }

    data class Action(
        val label: String,
        val onClick: () -> Unit,
        val destructive: Boolean = false,
        val trailing: String? = null,
        val keepOpen: Boolean = false,
    ) : TrayMenuEntry {
        override val height: Dp = 38.dp
    }
}
