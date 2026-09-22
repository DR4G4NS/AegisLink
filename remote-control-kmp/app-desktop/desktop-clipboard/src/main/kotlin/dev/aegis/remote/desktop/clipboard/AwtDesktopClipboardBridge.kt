package dev.aegis.remote.desktop.clipboard

import dev.aegis.remote.core.clipboard.ClipboardBridge
import dev.aegis.remote.core.clipboard.ClipboardLoopGuard
import dev.aegis.remote.core.model.AppError
import dev.aegis.remote.core.model.ClipboardPayload
import dev.aegis.remote.core.model.DesktopSessionBackend
import dev.aegis.remote.core.model.DesktopSessionEnvironment
import dev.aegis.remote.core.model.selectBackend
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.FlavorListener
import java.awt.datatransfer.StringSelection

class DesktopClipboardException(
    val appError: AppError,
) : RuntimeException(appError.message)

enum class DesktopClipboardBackend {
    AwtToolkit,
    LinuxX11Xclip,
    LinuxWaylandWlClipboard,
    Unavailable,
}

data class DesktopClipboardBridgeSelection(
    val backend: DesktopClipboardBackend,
    val bridge: ClipboardBridge,
)

interface DesktopClipboardAccess {
    fun readText(): String?

    fun writeText(text: String)
}

class AwtDesktopClipboardAccess : DesktopClipboardAccess {
    override fun readText(): String? {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null
        return clipboard.getData(DataFlavor.stringFlavor) as? String
    }

    override fun writeText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

class AwtDesktopClipboardBridge(
    private val access: DesktopClipboardAccess = AwtDesktopClipboardAccess(),
    private val headless: () -> Boolean = { GraphicsEnvironment.isHeadless() },
    clock: () -> Long = { System.currentTimeMillis() },
    private val loopGuard: DesktopClipboardLoopGuard = DesktopClipboardLoopGuard(clock),
) : ClipboardBridge {
    private val changeEvents = MutableSharedFlow<ClipboardPayload>(extraBufferCapacity = 1)
    private val localDuplicateGuard = ClipboardLoopGuard(clock)

    override val changes: Flow<ClipboardPayload> =
        callbackFlow {
            ensureAvailable()
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val listener =
                FlavorListener {
                    runCatching { access.readText() }
                        .getOrNull()
                        ?.let { text -> trySend(ClipboardPayload.Text(text)) }
                }
            clipboard.addFlavorListener(listener)
            val writes =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    changeEvents.collect { trySend(it) }
                }
            awaitClose {
                writes.cancel()
                clipboard.removeFlavorListener(listener)
            }
        }

    override suspend fun read(): ClipboardPayload? {
        ensureAvailable()
        return runCatching { access.readText()?.let(ClipboardPayload::Text) }
            .getOrElse { error -> throw failure("read", error) }
    }

    override suspend fun write(payload: ClipboardPayload) {
        ensureAvailable()
        when (payload) {
            is ClipboardPayload.Text -> {
                runCatching { access.writeText(payload.value) }
                    .getOrElse { error -> throw failure("write", error) }
                changeEvents.emit(payload)
            }
        }
    }

    override suspend fun writeFromRemote(payload: ClipboardPayload) {
        loopGuard.markRemoteWrite(payload)
        runCatching { write(payload) }
            .onFailure { loopGuard.cancelRemoteWrite(payload) }
            .getOrThrow()
    }

    override fun shouldForwardChange(payload: ClipboardPayload): Boolean {
        val remoteEcho = loopGuard.isRemoteEcho(payload)
        return !remoteEcho && localDuplicateGuard.shouldForwardLocalChange(payload)
    }

    private fun ensureAvailable() {
        if (headless()) {
            throw DesktopClipboardException(AppError.CapabilityUnavailable("Desktop clipboard is unavailable in a headless environment"))
        }
    }

    private fun failure(
        operation: String,
        error: Throwable,
    ): DesktopClipboardException =
        DesktopClipboardException(
            AppError.CapabilityUnavailable("Desktop clipboard $operation failed: ${error.message ?: error::class.simpleName}"),
        )
}

class UnavailableDesktopClipboardBridge(
    private val reason: String,
) : ClipboardBridge {
    override val changes: Flow<ClipboardPayload> = MutableSharedFlow<ClipboardPayload>().asSharedFlow()

    override suspend fun read(): ClipboardPayload? = throw DesktopClipboardException(AppError.CapabilityUnavailable(reason))

    override suspend fun write(payload: ClipboardPayload): Unit = throw DesktopClipboardException(AppError.CapabilityUnavailable(reason))
}

class DesktopClipboardBridgeFactory(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val sessionType: String? = System.getenv("XDG_SESSION_TYPE"),
    private val waylandDisplay: String? = System.getenv("WAYLAND_DISPLAY"),
    private val display: String? = System.getenv("DISPLAY"),
    private val xclipAvailable: () -> Boolean = { XclipLocator.isAvailable() },
    private val wlClipboardAvailable: () -> Boolean = { WlClipboardLocator.isAvailable() },
    private val headless: () -> Boolean = { GraphicsEnvironment.isHeadless() },
    clock: () -> Long = { System.currentTimeMillis() },
) {
    private val sharedLoopGuard = DesktopClipboardLoopGuard(clock)

    fun create(): DesktopClipboardBridgeSelection {
        if (headless()) {
            return unavailable(
                DesktopClipboardBackend.Unavailable,
                "Desktop clipboard is unavailable in a headless environment",
            )
        }

        val backend =
            DesktopSessionEnvironment(
                osName = osName,
                sessionType = sessionType,
                waylandDisplay = waylandDisplay,
                x11Display = display,
            ).selectBackend().backend
        if (backend == DesktopSessionBackend.LinuxWayland) {
            return if (wlClipboardAvailable()) {
                DesktopClipboardBridgeSelection(
                    backend = DesktopClipboardBackend.LinuxWaylandWlClipboard,
                    bridge = LinuxWaylandClipboardBridge(loopGuard = sharedLoopGuard),
                )
            } else {
                unavailable(
                    DesktopClipboardBackend.LinuxWaylandWlClipboard,
                    "Wayland clipboard requires wl-copy and wl-paste; install wl-clipboard or configure a supported native backend",
                )
            }
        }

        if (backend == DesktopSessionBackend.LinuxX11) {
            return if (xclipAvailable()) {
                DesktopClipboardBridgeSelection(
                    backend = DesktopClipboardBackend.LinuxX11Xclip,
                    bridge = LinuxX11ClipboardBridge(loopGuard = sharedLoopGuard),
                )
            } else {
                unavailable(
                    DesktopClipboardBackend.LinuxX11Xclip,
                    "X11 clipboard requires xclip; install it or configure a supported native backend",
                )
            }
        }

        return DesktopClipboardBridgeSelection(
            backend = DesktopClipboardBackend.AwtToolkit,
            bridge = AwtDesktopClipboardBridge(headless = headless, loopGuard = sharedLoopGuard),
        )
    }

    private fun unavailable(
        backend: DesktopClipboardBackend,
        reason: String,
    ): DesktopClipboardBridgeSelection =
        DesktopClipboardBridgeSelection(
            backend = backend,
            bridge = UnavailableDesktopClipboardBridge(reason),
        )
}

/** One factory-scoped guard prevents a write from device A echoing through another active device session. */
class DesktopClipboardLoopGuard(
    clock: () -> Long,
) {
    private val delegate = ClipboardLoopGuard(clock)
    private val lock = Any()

    fun markRemoteWrite(payload: ClipboardPayload) = synchronized(lock) { delegate.markRemoteWrite(payload) }

    fun cancelRemoteWrite(payload: ClipboardPayload) = synchronized(lock) { delegate.cancelRemoteWrite(payload) }

    fun isRemoteEcho(payload: ClipboardPayload): Boolean = synchronized(lock) { delegate.isRemoteEcho(payload) }
}
