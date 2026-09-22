package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.desktop.capture.CaptureConfig
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PortalPipeWireCaptureTest {
    @Test
    fun portalSelectionAndReconfigureRemainConsentGated() =
        runTest {
            val session =
                PortalPipeWireCaptureSession(
                    initialConfig = CaptureConfig(null, 1280, 720, 30),
                    streamFactory = PipeWireFrameStreamFactory { error("stream must not open") },
                    scope = this,
                )

            val unknownMonitorError =
                try {
                    session.selectMonitor(MonitorId("other"))
                    null
                } catch (error: IllegalStateException) {
                    error
                }
            assertTrue(unknownMonitorError?.message.orEmpty().contains("CAP-5003"))
            val consentError =
                try {
                    session.selectMonitor(MonitorId("portal-screen"))
                    null
                } catch (error: IllegalStateException) {
                    error
                }
            assertTrue(consentError?.message.orEmpty().contains("CAP-5001"))
            val reconfigureError =
                try {
                    session.reconfigure(CaptureConfig(null, 640, 480, 15))
                    null
                } catch (error: IllegalStateException) {
                    error
                }
            assertTrue(reconfigureError?.message.orEmpty().contains("CAP-5001"))

            session.close()
            session.close()
        }

    @Test
    fun i420ConversionUsesTheDeclaredPixelFormat() {
        DesktopWebRtcRuntime.ensureLoaded()
        val target = NativeI420Buffer.allocate(2, 2)
        try {
            writeI420(
                target,
                PipeWireRawVideoFrame(
                    width = 2,
                    height = 2,
                    stride = 8,
                    format = PipeWireRawVideoFormat.Bgrx,
                    timestampNanos = 1L,
                    pixels = ByteArray(16) { index -> if (index % 4 == 2) 0xff.toByte() else 0 },
                ),
            )

            assertTrue((target.dataY.get(0).toInt() and 0xff) > 50)
            assertTrue((target.dataU.get(0).toInt() and 0xff) < 150)
            assertTrue((target.dataV.get(0).toInt() and 0xff) > 150)
        } finally {
            target.release()
        }
    }

    @Test
    fun processFrameStreamRejectsMalformedWireDataAndAllowsOnlyOneCollector() =
        runTest {
            val malformed = frameBytes(magic = 0xdeadbeef.toInt())
            val stream = ProcessPipeWireFrameStream(TestProcess(malformed))
            assertFailsWith<IOException> { stream.frames.first() }
            stream.close()

            val validBytes = frameBytes()
            assertEquals(40, validBytes.size)
            val valid = ProcessPipeWireFrameStream(TestProcess(validBytes))
            valid.frames.first()
            assertFailsWith<IllegalStateException> { valid.frames.first() }
            valid.close()
            valid.close()
        }

    @Test
    fun rawFrameRejectsZeroDimensionsStrideMismatchAndNegativeTimestamp() {
        assertFailsWith<IllegalArgumentException> {
            PipeWireRawVideoFrame(0, 2, 8, PipeWireRawVideoFormat.Rgba, 0L, ByteArray(16))
        }
        assertFailsWith<IllegalArgumentException> {
            PipeWireRawVideoFrame(2, 2, 4, PipeWireRawVideoFormat.Rgba, 0L, ByteArray(8))
        }
        assertFailsWith<IllegalArgumentException> {
            PipeWireRawVideoFrame(2, 2, 8, PipeWireRawVideoFormat.Rgba, -1L, ByteArray(16))
        }
    }
}

private fun frameBytes(
    magic: Int = 0x41454753,
): ByteArray =
    ByteArrayOutputStream()
        .also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(magic)
                data.writeInt(1)
                data.writeInt(1)
                data.writeInt(1)
                data.writeInt(4)
                data.writeInt(2)
                data.writeInt(4)
                data.writeLong(7L)
                data.write(byteArrayOf(0, 0, 0, 0))
            }
        }.toByteArray()

private class TestProcess(
    bytes: ByteArray,
) : Process() {
    private val input = ByteArrayInputStream(bytes)
    private val error = ByteArrayInputStream(ByteArray(0))
    private var alive = true

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = input

    override fun getErrorStream(): InputStream = error

    override fun waitFor(): Int {
        alive = false
        return 0
    }

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        alive = false
        return true
    }

    override fun exitValue(): Int = 0

    override fun destroy() {
        alive = false
    }

    override fun destroyForcibly(): Process {
        alive = false
        return this
    }

    override fun isAlive(): Boolean = alive
}
