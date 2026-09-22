package dev.aegis.remote.android.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dev.aegis.remote.core.clipboard.ClipboardBridge
import dev.aegis.remote.core.model.ClipboardPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

class AndroidClipboardBridge(
    context: Context,
) : ClipboardBridge {
    private val appContext = context.applicationContext
    private val clipboardManager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override val changes: Flow<ClipboardPayload> =
        callbackFlow {
            val listener =
                ClipboardManager.OnPrimaryClipChangedListener {
                    val payload = readCurrentText()
                    if (payload != null) {
                        trySend(payload)
                    }
                }
            clipboardManager.addPrimaryClipChangedListener(listener)
            awaitClose { clipboardManager.removePrimaryClipChangedListener(listener) }
        }

    override suspend fun read(): ClipboardPayload? =
        withContext(Dispatchers.Main.immediate) {
            readCurrentText()
        }

    override suspend fun write(payload: ClipboardPayload) =
        withContext(Dispatchers.Main.immediate) {
            when (payload) {
                is ClipboardPayload.Text -> {
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("Aegis clipboard", payload.value))
                }
            }
        }

    private fun readCurrentText(): ClipboardPayload.Text? {
        val item = clipboardManager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        val text = item.coerceToText(appContext)?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return ClipboardPayload.Text(text)
    }
}
