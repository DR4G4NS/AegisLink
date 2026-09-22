package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.desktop.capture.CaptureConfig
import dev.aegis.remote.desktop.capture.CaptureSession
import dev.aegis.remote.desktop.capture.DesktopVideoFrame
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A raw frame copied from the short-lived Portal/PipeWire helper. */
data class PipeWireRawVideoFrame(
    val width: Int,
    val height: Int,
    val stride: Int,
    val format: PipeWireRawVideoFormat,
    val timestampNanos: Long,
    val pixels: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "PipeWire frame dimensions must be positive" }
        require(stride >= width * 4) { "PipeWire frame stride is smaller than the negotiated width" }
        require(stride.toLong() * height.toLong() == pixels.size.toLong()) {
            "PipeWire frame payload does not match its stride"
        }
        require(pixels.size <= PIPEWIRE_MAX_FRAME_BYTES) { "PipeWire frame exceeds the bounded payload limit" }
        require(timestampNanos >= 0) { "PipeWire frame timestamp must not be negative" }
    }
}

enum class PipeWireRawVideoFormat {
    Bgrx,
    Rgba,
}

interface PipeWireFrameStream {
    val frames: Flow<PipeWireRawVideoFrame>

    suspend fun close()
}

fun interface PipeWireFrameStreamFactory {
    fun open(config: CaptureConfig): PipeWireFrameStream
}

interface DesktopWebRtcCaptureSession : CaptureSession {
    fun createVideoTrack(factory: PeerConnectionFactory): VideoTrack

    fun bind(track: VideoTrack)
}

/**
 * Reads the helper's fixed, private wire format. A single collector is
 * allowed so that one PipeWire buffer can never be consumed twice.
 */
class ProcessPipeWireFrameStream(
    private val process: Process,
    private val cleanup: suspend () -> Unit = {},
) : PipeWireFrameStream {
    private val collected = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override val frames: Flow<PipeWireRawVideoFrame> =
        flow {
            check(collected.compareAndSet(false, true)) {
                "PipeWire frame stream supports only one collector"
            }
            val input = DataInputStream(BufferedInputStream(process.inputStream))
            try {
                while (!closed.get()) {
                    val frame = withContext(Dispatchers.IO) { input.readFrame() }
                    emit(frame)
                }
            } finally {
                input.close()
            }
        }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) {
            process.destroy()
            if (!process.waitFor(PIPEWIRE_PROCESS_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(PIPEWIRE_PROCESS_FORCE_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }
        }
        cleanup()
    }
}

/** Starts the native helper without introducing a long-lived runtime daemon. */
class ProcessPipeWireFrameStreamFactory(
    private val processStarter: (List<String>) -> Process = { command ->
        ProcessBuilder(command).redirectErrorStream(false).start()
    },
    private val bridgeExecutable: () -> Path = ::extractPortalPipeWireBridge,
) : PipeWireFrameStreamFactory {
    override fun open(config: CaptureConfig): PipeWireFrameStream {
        val executable = bridgeExecutable()
        val process =
            runCatching {
                processStarter(
                    listOf(
                        executable.toString(),
                        config.maxWidth.toString(),
                        config.maxHeight.toString(),
                        config.maxFps.toString(),
                    ),
                )
            }.getOrElse { error ->
                runCatching { Files.deleteIfExists(executable) }
                throw IllegalStateException(
                    "${AegisFailureCodes.CAPTURE_BACKEND_UNAVAILABLE}: " +
                        "Portal/PipeWire capture helper could not start",
                    error,
                )
            }
        // Drain diagnostics so a native error cannot deadlock the helper. The
        // bytes are deliberately not surfaced to logs because they may contain
        // compositor-specific details or paths.
        Thread {
            process.errorStream.use(InputStream::readBytes)
        }.apply {
            name = "aegis-pipewire-diagnostics"
            isDaemon = true
            start()
        }
        return ProcessPipeWireFrameStream(
            process = process,
            cleanup = {
                runCatching { Files.deleteIfExists(executable) }
            },
        )
    }
}

/**
 * Portal-backed capture session. The selector and consent remain owned by
 * xdg-desktop-portal; this class never falls back to XWayland or X11.
 */
class PortalPipeWireCaptureSession(
    private val initialConfig: CaptureConfig,
    private val streamFactory: PipeWireFrameStreamFactory,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : DesktopWebRtcCaptureSession {
    private val frameEvents = MutableSharedFlow<DesktopVideoFrame>(replay = 1, extraBufferCapacity = 1)
    private var videoSource: CustomVideoSource? = null
    private var stream: PipeWireFrameStream? = null
    private var readerJob: Job? = null
    private var track: VideoTrack? = null
    private var closed = false
    private var lastTimestampNanos = -1L

    override val frames: Flow<DesktopVideoFrame> = frameEvents.asSharedFlow()

    override fun createVideoTrack(factory: PeerConnectionFactory): VideoTrack {
        check(!closed) { "${AegisFailureCodes.CAPTURE_SESSION_CLOSED}: Capture session is closed" }
        val source = CustomVideoSource().also { videoSource = it }
        return factory.createVideoTrack("aegis-wayland-portal-video", source)
    }

    override fun bind(track: VideoTrack) {
        check(!closed) { "${AegisFailureCodes.CAPTURE_SESSION_CLOSED}: Capture session is closed" }
        check(this.track == null) { "Portal/PipeWire capture is already bound" }
        this.track = track
        val opened = streamFactory.open(initialConfig)
        stream = opened
        readerJob =
            scope.launch {
                try {
                    opened.frames.collect { frame ->
                        check(frame.timestampNanos >= lastTimestampNanos) {
                            "PipeWire frame timestamp moved backwards"
                        }
                        lastTimestampNanos = frame.timestampNanos
                        pushFrame(frame)
                        frameEvents.tryEmit(
                            DesktopVideoFrame(
                                width = frame.width,
                                height = frame.height,
                                capturedAtNanos = frame.timestampNanos,
                            ),
                        )
                    }
                } finally {
                    runCatching { opened.close() }
                }
            }
    }

    override suspend fun selectMonitor(id: MonitorId) {
        check(!closed) { "${AegisFailureCodes.CAPTURE_SESSION_CLOSED}: Capture session is closed" }
        if (id.value != PORTAL_MONITOR_ID) {
            error("${AegisFailureCodes.CAPTURE_UNKNOWN_SOURCE}: Portal selected screen id ${id.value} is not active")
        }
        error("${AegisFailureCodes.CAPTURE_BACKEND_UNAVAILABLE}: Portal screen selection requires a new consent session")
    }

    override suspend fun reconfigure(config: CaptureConfig) {
        check(!closed) { "${AegisFailureCodes.CAPTURE_SESSION_CLOSED}: Capture session is closed" }
        require(config.maxWidth > 0 && config.maxHeight > 0 && config.maxFps > 0)
        error(
            "${AegisFailureCodes.CAPTURE_BACKEND_UNAVAILABLE}: " +
                "Portal/PipeWire capture requires a new consent session to change its negotiated limits",
        )
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        readerJob?.cancelAndJoin()
        readerJob = null
        stream = null
        track?.dispose()
        track = null
        videoSource?.dispose()
        videoSource = null
    }

    private fun pushFrame(frame: PipeWireRawVideoFrame) {
        val i420 = NativeI420Buffer.allocate(frame.width, frame.height)
        try {
            writeI420(i420, frame)
        } catch (error: Throwable) {
            i420.release()
            throw error
        }
        val videoFrame = VideoFrame(i420, frame.timestampNanos)
        try {
            checkNotNull(videoSource) { "Portal/PipeWire video source is not bound" }.pushFrame(videoFrame)
        } finally {
            // VideoFrame owns the same initial buffer reference; releasing both
            // objects would decrement NativeI420Buffer twice.
            videoFrame.release()
        }
    }
}

internal fun writeI420(
    target: NativeI420Buffer,
    frame: PipeWireRawVideoFrame,
) {
    writeLumaPlane(target, frame)
    writeChromaPlanes(target, frame)
}

private fun writeLumaPlane(
    target: NativeI420Buffer,
    frame: PipeWireRawVideoFrame,
) {
    val redOffset = if (frame.format == PipeWireRawVideoFormat.Bgrx) 2 else 0
    val blueOffset = if (frame.format == PipeWireRawVideoFormat.Bgrx) 0 else 2
    for (row in 0 until frame.height) {
        val sourceRow = row * frame.stride
        val targetRow = row * target.strideY
        for (column in 0 until frame.width) {
            val offset = sourceRow + column * 4
            val red = frame.pixels[offset + redOffset].unsigned()
            val green = frame.pixels[offset + 1].unsigned()
            val blue = frame.pixels[offset + blueOffset].unsigned()
            target.dataY.put(targetRow + column, rgbToY(red, green, blue))
        }
    }
}

private fun writeChromaPlanes(
    target: NativeI420Buffer,
    frame: PipeWireRawVideoFrame,
) {
    val redOffset = if (frame.format == PipeWireRawVideoFormat.Bgrx) 2 else 0
    val blueOffset = if (frame.format == PipeWireRawVideoFormat.Bgrx) 0 else 2
    val chromaWidth = (frame.width + 1) / 2
    val chromaHeight = (frame.height + 1) / 2
    for (row in 0 until chromaHeight) {
        for (column in 0 until chromaWidth) {
            var sumU = 0
            var sumV = 0
            var sampleCount = 0
            val firstX = column * 2
            val firstY = row * 2
            for (sampleY in firstY until minOf(firstY + 2, frame.height)) {
                val sourceRow = sampleY * frame.stride
                for (sampleX in firstX until minOf(firstX + 2, frame.width)) {
                    val offset = sourceRow + sampleX * 4
                    val red = frame.pixels[offset + redOffset].unsigned()
                    val green = frame.pixels[offset + 1].unsigned()
                    val blue = frame.pixels[offset + blueOffset].unsigned()
                    sumU += rgbToU(red, green, blue)
                    sumV += rgbToV(red, green, blue)
                    sampleCount++
                }
            }
            target.dataU.put(row * target.strideU + column, (sumU / sampleCount).toByte())
            target.dataV.put(row * target.strideV + column, (sumV / sampleCount).toByte())
        }
    }
}

private fun Byte.unsigned(): Int = toInt() and 0xff

private fun rgbToY(
    red: Int,
    green: Int,
    blue: Int,
): Byte = (((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16).coerceIn(0, 255).toByte()

private fun rgbToU(
    red: Int,
    green: Int,
    blue: Int,
): Int = (((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128).coerceIn(0, 255)

private fun rgbToV(
    red: Int,
    green: Int,
    blue: Int,
): Int = (((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128).coerceIn(0, 255)

private fun DataInputStream.readFrame(): PipeWireRawVideoFrame {
    if (readInt() != PIPEWIRE_FRAME_MAGIC) invalidFrame("Portal/PipeWire frame magic mismatch")
    if (readInt() != PIPEWIRE_FRAME_VERSION) invalidFrame("Portal/PipeWire frame version is unsupported")
    val width = readInt()
    val height = readInt()
    val stride = readInt()
    val format =
        when (readInt()) {
            PIPEWIRE_FORMAT_BGRX -> PipeWireRawVideoFormat.Bgrx
            PIPEWIRE_FORMAT_RGBA -> PipeWireRawVideoFormat.Rgba
            else -> invalidFrame("Portal/PipeWire frame format is unsupported")
        }
    val payload = readInt()
    val timestamp = readLong()
    if (invalidFrameHeader(width, height, stride, payload)) {
        invalidFrame("Portal/PipeWire frame header is invalid")
    }
    val expected = stride.toLong() * height.toLong()
    if (expected != payload.toLong() || expected > PIPEWIRE_MAX_FRAME_BYTES) {
        invalidFrame("Portal/PipeWire frame payload is invalid")
    }
    val pixels = ByteArray(payload)
    readFully(pixels)
    return PipeWireRawVideoFrame(width, height, stride, format, timestamp, pixels)
}

private fun invalidFrameHeader(
    width: Int,
    height: Int,
    stride: Int,
    payload: Int,
): Boolean = width <= 0 || height <= 0 || stride <= 0 || payload <= 0 || payload > PIPEWIRE_MAX_FRAME_BYTES

private fun invalidFrame(message: String): Nothing = throw IOException(message)

internal fun extractPortalPipeWireBridge(): Path {
    val configured = System.getProperty("aegis.pipewireBridge")?.takeIf(String::isNotBlank)
    if (configured != null) {
        val path = Path.of(configured)
        require(Files.isRegularFile(path) && Files.isExecutable(path)) {
            "Configured Portal/PipeWire bridge is not executable: $path"
        }
        return path
    }
    val resource = "native/linux-x86_64/aegis-pipewire-portal-capture"
    val stream =
        ProcessPipeWireFrameStreamFactory::class.java.classLoader.getResourceAsStream(resource)
            ?: error(
                "${AegisFailureCodes.CAPTURE_BACKEND_UNAVAILABLE}: " +
                    "The Linux Portal/PipeWire bridge is not packaged",
            )
    val extracted = Files.createTempFile("aegis-pipewire-portal-", "")
    stream.use { input -> Files.copy(input, extracted, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
    runCatching {
        Files.setPosixFilePermissions(
            extracted,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
        )
    }.getOrElse { error ->
        Files.deleteIfExists(extracted)
        throw IllegalStateException("Portal/PipeWire bridge cannot be made executable", error)
    }
    return extracted
}

private const val PORTAL_MONITOR_ID = "portal-screen"
private const val PIPEWIRE_FRAME_MAGIC = 0x41454753
private const val PIPEWIRE_FRAME_VERSION = 1
private const val PIPEWIRE_FORMAT_BGRX = 1
private const val PIPEWIRE_FORMAT_RGBA = 2
private const val PIPEWIRE_MAX_FRAME_BYTES = 64 * 1024 * 1024
private const val PIPEWIRE_PROCESS_STOP_TIMEOUT_MILLIS = 2_000L
private const val PIPEWIRE_PROCESS_FORCE_STOP_TIMEOUT_MILLIS = 2_000L
