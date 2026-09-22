package dev.aegis.remote.desktop.webrtc

import dev.onvoid.webrtc.PeerConnectionFactory

data class WindowsNativePreflightProbe(
    val available: Boolean,
    val degraded: Boolean = false,
    val cause: String,
    val nextAction: String,
    val backend: String? = null,
) {
    companion object {
        fun unavailable(
            cause: String,
            nextAction: String,
            backend: String? = null,
        ) = WindowsNativePreflightProbe(
            available = false,
            degraded = false,
            cause = cause,
            nextAction = nextAction,
            backend = backend,
        )

        fun degraded(
            cause: String,
            nextAction: String,
            backend: String? = null,
        ) = WindowsNativePreflightProbe(
            available = false,
            degraded = true,
            cause = cause,
            nextAction = nextAction,
            backend = backend,
        )

        fun available(
            cause: String,
            nextAction: String = "No action required",
            backend: String? = null,
        ) = WindowsNativePreflightProbe(
            available = true,
            degraded = false,
            cause = cause,
            nextAction = nextAction,
            backend = backend,
        )
    }
}

fun probeWindowsWebRtcNativeRuntime(): WindowsNativePreflightProbe =
    runCatching {
        PeerConnectionFactory().also { it.dispose() }
        WindowsNativePreflightProbe.available(
            cause = "The Windows webrtc-java native runtime loaded successfully",
            backend = "windows-x86_64",
        )
    }.getOrElse { error ->
        WindowsNativePreflightProbe.unavailable(
            cause = "The Windows webrtc-java native runtime failed to load: ${error.message ?: error.javaClass.simpleName}",
            nextAction = "Rebuild the Windows distributable on a Windows x64 host",
            backend = "windows-x86_64",
        )
    }

fun probeWindowsDesktopDuplication(
    interactiveSessionAvailable: Boolean,
    webrtcNativeAvailable: Boolean,
): WindowsNativePreflightProbe {
    if (!interactiveSessionAvailable || !webrtcNativeAvailable) {
        return WindowsNativePreflightProbe.unavailable(
            cause = "Desktop Duplication requires an interactive session and the webrtc-java runtime",
            nextAction = "Repair the interactive-session and webrtc-native capabilities above",
            backend = "windows-desktop-duplication",
        )
    }
    return runCatching {
        val backend = NativeDesktopCaptureBackend.WindowsDesktopDuplication
        val sources = enumerateDesktopSources(backend)
        if (sources.isEmpty()) {
            WindowsNativePreflightProbe.degraded(
                cause = "Desktop Duplication loaded but reported no capturable display",
                nextAction = "Verify the session is unlocked and at least one monitor is active",
                backend = "windows-desktop-duplication",
            )
        } else {
            WindowsNativePreflightProbe.available(
                cause = "Desktop Duplication reported ${sources.size} capturable display source(s)",
                backend = "windows-desktop-duplication",
            )
        }
    }.getOrElse { error ->
        WindowsNativePreflightProbe.unavailable(
            cause = "Desktop Duplication failed before capture: ${error.message ?: error.javaClass.simpleName}",
            nextAction = "Run Aegis from an unlocked interactive session on Windows 10/11 x64",
            backend = "windows-desktop-duplication",
        )
    }
}

fun isWindowsDesktopDuplicationSelected(osName: String): Boolean =
    resolveNativeDesktopCaptureBackend(
        osName = osName,
        sessionType = null,
        display = null,
        waylandDisplay = null,
    ) == NativeDesktopCaptureBackend.WindowsDesktopDuplication
