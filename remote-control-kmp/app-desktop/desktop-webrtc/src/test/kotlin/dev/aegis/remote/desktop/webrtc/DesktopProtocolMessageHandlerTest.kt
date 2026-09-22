package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.clipboard.ClipboardBridge
import dev.aegis.remote.core.clipboard.MAX_CLIPBOARD_TEXT_BYTES
import dev.aegis.remote.core.input.KeyCode
import dev.aegis.remote.core.input.RemoteInputAdmissionController
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.input.RemoteInputExecutor
import dev.aegis.remote.core.input.RemoteInputLimits
import dev.aegis.remote.core.model.AppError
import dev.aegis.remote.core.model.ClipboardPayload
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.ConnectionStats
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.MonitorInfo
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.VideoConfig
import dev.aegis.remote.core.monitor.MonitorProvider
import dev.aegis.remote.core.webrtc.RemoteVideoSession
import dev.aegis.remote.core.webrtc.VideoSessionState
import dev.aegis.remote.protocol.ControlCommand
import dev.aegis.remote.protocol.ProtocolMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DesktopProtocolMessageHandlerTest {
    @Test
    fun routesAuthorizedInputToExecutor() =
        runTest {
            val input = RecordingInputExecutor()
            val handler = handler(inputExecutor = input)
            val event = RemoteInputEvent.Key(KeyCode.Enter, pressed = true)

            val result = handler.handle(ProtocolMessage.Input(SessionId("session-1"), event))

            assertEquals(event, input.events.single())
            assertEquals(ProtocolHandlingResult.Handled("input"), result)
        }

    @Test
    fun routesAuthorizedClipboardSyncToClipboardBridge() =
        runTest {
            val clipboard = RecordingClipboardBridge()
            val handler = handler(clipboardBridge = clipboard)

            val result =
                handler.handle(
                    ProtocolMessage.Input(
                        sessionId = SessionId("session-1"),
                        event = RemoteInputEvent.ClipboardSync("copied"),
                    ),
                )

            assertEquals(ClipboardPayload.Text("copied"), clipboard.writes.single())
            assertEquals(ProtocolHandlingResult.Handled("clipboard"), result)
        }

    @Test
    fun routesVisualControlToVideoSession() =
        runTest {
            val video = RecordingVideoSession()
            val handler = handler(videoSession = video)
            val config = videoConfig()

            handler.handle(ProtocolMessage.Control(SessionId("session-1"), ControlCommand.StartVisualSession(config, StunTurnConfig())))
            handler.handle(ProtocolMessage.Control(SessionId("session-1"), ControlCommand.SelectMonitor(MonitorId("secondary"))))
            handler.handle(ProtocolMessage.Control(SessionId("session-1"), ControlCommand.SetQuality(config.copy(bitrateKbps = 1000))))
            handler.handle(ProtocolMessage.Control(SessionId("session-1"), ControlCommand.StopVisualSession))

            assertEquals(config, video.startedConfig)
            assertEquals("secondary", video.selectedMonitors.single())
            assertEquals(1000, video.qualityConfigs.single().bitrateKbps)
            assertEquals(true, video.stopped)
        }

    @Test
    fun pingReturnsPongResponse() =
        runTest {
            val handler = handler(clock = { 250L })

            val result =
                handler.handle(
                    ProtocolMessage.Control(SessionId("session-1"), ControlCommand.Ping(sentAtEpochMillis = 100L)),
                )

            val response = assertIs<ProtocolHandlingResult.Respond>(result).message
            val pong = assertIs<ControlCommand.Pong>(assertIs<ProtocolMessage.Control>(response).command)
            assertEquals(100L, pong.pingSentAtEpochMillis)
            assertEquals(250L, pong.receivedAtEpochMillis)
        }

    @Test
    fun rejectsInputWhenPermissionMissing() =
        runTest {
            val handler = handler(permissions = DevicePermissions(input = false, visual = true, clipboard = true))

            val error =
                assertFailsWith<DesktopProtocolMessageException> {
                    handler.handle(
                        ProtocolMessage.Input(
                            sessionId = SessionId("session-1"),
                            event = RemoteInputEvent.Key(KeyCode.Enter, pressed = true),
                        ),
                    )
                }

            assertIs<AppError.Authorization>(error.appError)
            assertEquals("INP-1001", error.appError.failure?.code)
        }

    @Test
    fun rejectsInputWhileVisibleKillSwitchIsPaused() =
        runTest {
            val input = RecordingInputExecutor()
            val handler = handler(inputExecutor = input, inputEnabled = { false })

            val error =
                assertFailsWith<DesktopProtocolMessageException> {
                    handler.handle(
                        ProtocolMessage.Input(
                            sessionId = SessionId("session-1"),
                            event = RemoteInputEvent.Key(KeyCode.Enter, pressed = true),
                        ),
                    )
                }

            assertIs<AppError.Authorization>(error.appError)
            assertEquals("INP-1002", error.appError.failure?.code)
            assertEquals(emptyList(), input.events)
            assertEquals(1, input.releaseCount)
        }

    @Test
    fun rejectsClipboardWhenPermissionMissing() =
        runTest {
            val handler = handler(permissions = DevicePermissions(input = true, visual = true, clipboard = false))

            val error =
                assertFailsWith<DesktopProtocolMessageException> {
                    handler.handle(
                        ProtocolMessage.Input(
                            sessionId = SessionId("session-1"),
                            event = RemoteInputEvent.ClipboardSync("blocked"),
                        ),
                    )
                }

            assertIs<AppError.Authorization>(error.appError)
            assertEquals("CLP-1001", error.appError.failure?.code)
        }

    @Test
    fun rejectsAbsolutePointerOutsideDeclaredMonitor() =
        runTest {
            val monitor = MonitorInfo(MonitorId("left"), "Left", width = 100, height = 100, originX = -100)
            val handler = handler(monitorProvider = StaticMonitorProvider(listOf(monitor)))

            val error =
                assertFailsWith<DesktopProtocolMessageException> {
                    handler.handle(
                        ProtocolMessage.Input(
                            SessionId("session-1"),
                            RemoteInputEvent.MouseMove(0, 50, monitor.id),
                        ),
                    )
                }

            assertEquals("INP-1003", error.appError.failure?.code)
        }

    @Test
    fun dropsExcessPointerSamplesButRejectsDiscreteFlood() =
        runTest {
            val admission =
                RemoteInputAdmissionController(
                    RemoteInputLimits(maxPointerEventsPerWindow = 1, maxDiscreteEventsPerWindow = 1),
                    clock = { 100L },
                )
            val handler = handler(inputAdmissionController = admission)

            handler.handle(ProtocolMessage.Input(SessionId("session-1"), RemoteInputEvent.MouseMoveRelative(1, 1)))
            val dropped = handler.handle(ProtocolMessage.Input(SessionId("session-1"), RemoteInputEvent.MouseMoveRelative(1, 1)))
            handler.handle(ProtocolMessage.Input(SessionId("session-1"), RemoteInputEvent.Key(KeyCode.Enter, true)))
            val rejected =
                assertFailsWith<DesktopProtocolMessageException> {
                    handler.handle(ProtocolMessage.Input(SessionId("session-1"), RemoteInputEvent.Key(KeyCode.Enter, false)))
                }

            assertEquals(ProtocolHandlingResult.Ignored("INP-1004"), dropped)
            assertEquals("INP-1004", rejected.appError.failure?.code)
        }

    @Test
    fun rejectsOversizedClipboardBeforeNativeWrite() =
        runTest {
            val clipboard = RecordingClipboardBridge()
            val handler = handler(clipboardBridge = clipboard)

            val error =
                assertFailsWith<DesktopProtocolMessageException> {
                    handler.handle(
                        ProtocolMessage.Input(
                            SessionId("session-1"),
                            RemoteInputEvent.ClipboardSync("x".repeat(MAX_CLIPBOARD_TEXT_BYTES + 1)),
                        ),
                    )
                }

            assertEquals("CLP-1002", error.appError.failure?.code)
            assertEquals(emptyList(), clipboard.writes)
        }

    @Test
    fun closeReleasesHeldInputStateIdempotentlyAtSessionBoundary() =
        runTest {
            val input = RecordingInputExecutor()
            val handler = handler(inputExecutor = input)

            handler.close()
            handler.close()

            assertEquals(2, input.releaseCount)
        }

    @Test
    fun rejectsMessagesForAnotherSession() =
        runTest {
            val handler = handler()

            val error =
                assertFailsWith<DesktopProtocolMessageException> {
                    handler.handle(
                        ProtocolMessage.Control(
                            sessionId = SessionId("session-2"),
                            command = ControlCommand.StopVisualSession,
                        ),
                    )
                }

            assertIs<AppError.Authorization>(error.appError)
        }

    private fun handler(
        permissions: DevicePermissions = DevicePermissions(input = true, visual = true, clipboard = true),
        inputExecutor: RecordingInputExecutor = RecordingInputExecutor(),
        clipboardBridge: RecordingClipboardBridge = RecordingClipboardBridge(),
        videoSession: RecordingVideoSession = RecordingVideoSession(),
        clock: () -> Long = { 100L },
        inputEnabled: () -> Boolean = { true },
        monitorProvider: MonitorProvider? = null,
        inputAdmissionController: RemoteInputAdmissionController = RemoteInputAdmissionController(clock = clock),
    ): DesktopProtocolMessageHandler =
        DesktopProtocolMessageHandler(
            sessionId = SessionId("session-1"),
            permissions = permissions,
            inputExecutor = inputExecutor,
            clipboardBridge = clipboardBridge,
            videoSession = videoSession,
            inputEnabled = inputEnabled,
            clock = clock,
            monitorProvider = monitorProvider,
            inputAdmissionController = inputAdmissionController,
        )

    private fun videoConfig() =
        VideoConfig(
            width = 1280,
            height = 720,
            fps = 30,
            bitrateKbps = 2500,
            monitorId = MonitorId("primary"),
            routeType = ConnectionRouteType.Lan,
        )
}

private class RecordingInputExecutor : RemoteInputExecutor {
    val events = mutableListOf<RemoteInputEvent>()
    var releaseCount = 0

    override suspend fun execute(event: RemoteInputEvent) {
        events += event
    }

    override suspend fun releaseAll() {
        releaseCount += 1
    }
}

private class RecordingClipboardBridge : ClipboardBridge {
    val writes = mutableListOf<ClipboardPayload>()
    override val changes: Flow<ClipboardPayload> = emptyFlow()

    override suspend fun read(): ClipboardPayload? = null

    override suspend fun write(payload: ClipboardPayload) {
        writes += payload
    }
}

private class RecordingVideoSession : RemoteVideoSession {
    var startedConfig: VideoConfig? = null
    val selectedMonitors = mutableListOf<String>()
    val qualityConfigs = mutableListOf<VideoConfig>()
    var stopped = false

    override val states: Flow<VideoSessionState> = MutableSharedFlow()
    override val stats: Flow<ConnectionStats> = emptyFlow()

    override suspend fun start(
        config: VideoConfig,
        ice: StunTurnConfig,
    ) {
        startedConfig = config
    }

    override suspend fun selectMonitor(monitorId: String) {
        selectedMonitors += monitorId
    }

    override suspend fun setQuality(config: VideoConfig) {
        qualityConfigs += config
    }

    override suspend fun stop() {
        stopped = true
    }
}

private class StaticMonitorProvider(
    private val monitors: List<MonitorInfo>,
) : MonitorProvider {
    override suspend fun listMonitors(): List<MonitorInfo> = monitors
}
