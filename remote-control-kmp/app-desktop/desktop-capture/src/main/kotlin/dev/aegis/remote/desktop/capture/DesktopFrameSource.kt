package dev.aegis.remote.desktop.capture

import dev.aegis.remote.core.model.MonitorId
import kotlinx.coroutines.flow.Flow

data class FrameSourceCapabilities(
    val backend: String,
    val supportsMonitorSelection: Boolean,
    val supportsLiveReconfigure: Boolean,
    val hardwareAccelerated: Boolean,
)

data class CaptureConfig(
    val monitorId: MonitorId?,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Int,
) {
    init {
        require(maxWidth > 0 && maxHeight > 0)
        require(maxFps > 0)
    }
}

data class DesktopVideoFrame(
    val width: Int,
    val height: Int,
    val capturedAtNanos: Long,
)

interface DesktopFrameSource {
    val capabilities: FrameSourceCapabilities

    suspend fun open(config: CaptureConfig): CaptureSession
}

interface CaptureSession {
    val frames: Flow<DesktopVideoFrame>

    suspend fun selectMonitor(id: MonitorId)

    suspend fun reconfigure(config: CaptureConfig)

    suspend fun close()
}
