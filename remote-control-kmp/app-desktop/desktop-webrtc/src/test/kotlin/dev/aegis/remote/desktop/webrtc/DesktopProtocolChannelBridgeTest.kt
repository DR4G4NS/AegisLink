package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.AppError
import dev.aegis.remote.core.model.FailureCategory
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.webrtc.SignalingMessage
import dev.aegis.remote.protocol.ControlCommand
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopProtocolChannelBridgeTest {
    @Test
    fun forwardsIncomingMessagesToProcessor() =
        runTest {
            val channel = BridgeRecordingProtocolMessageChannel()
            val handled = mutableListOf<ProtocolMessage>()
            val bridge =
                DesktopProtocolChannelBridge(
                    channel = channel,
                    scope = this,
                    processor = { message ->
                        handled += message
                        ProtocolHandlingResult.Handled("processed")
                    },
                )

            bridge.start()
            runCurrent()
            val message = bootstrap("session-1")
            channel.emit(message)
            runCurrent()
            bridge.stop()

            assertEquals(listOf<ProtocolMessage>(message), handled)
            assertEquals(emptyList<ProtocolMessage>(), channel.sent)
        }

    @Test
    fun sendsProtocolResponseReturnedByProcessor() =
        runTest {
            val channel = BridgeRecordingProtocolMessageChannel()
            val response =
                ProtocolMessage.Control(
                    sessionId = SessionId("session-1"),
                    command =
                        ControlCommand.Pong(
                            pingSentAtEpochMillis = 100L,
                            receivedAtEpochMillis = 150L,
                        ),
                )
            val bridge =
                DesktopProtocolChannelBridge(
                    channel = channel,
                    scope = this,
                    processor = { ProtocolHandlingResult.Respond(response) },
                )

            bridge.start()
            runCurrent()
            channel.emit(bootstrap("session-1"))
            runCurrent()
            bridge.stop()

            val sent = assertIs<ProtocolMessage.Control>(channel.sent.single())
            assertEquals(response, sent)
        }

    @Test
    fun ignoresNonResponseResults() =
        runTest {
            val channel = BridgeRecordingProtocolMessageChannel()
            var returnHandled = true
            val bridge =
                DesktopProtocolChannelBridge(
                    channel = channel,
                    scope = this,
                    processor = {
                        if (returnHandled) {
                            returnHandled = false
                            ProtocolHandlingResult.Handled("processed")
                        } else {
                            ProtocolHandlingResult.Ignored("not-for-bridge")
                        }
                    },
                )

            bridge.start()
            runCurrent()
            channel.emit(bootstrap("session-1"))
            channel.emit(ProtocolMessage.Control(SessionId("session-1"), ControlCommand.Pong(1L, 2L)))
            runCurrent()
            bridge.stop()

            assertEquals(emptyList<ProtocolMessage>(), channel.sent)
        }

    @Test
    fun stopCancelsCollectionAndClosesChannel() =
        runTest {
            val channel = BridgeRecordingProtocolMessageChannel()
            val handled = mutableListOf<ProtocolMessage>()
            val bridge =
                DesktopProtocolChannelBridge(
                    channel = channel,
                    scope = this,
                    processor = { message ->
                        handled += message
                        ProtocolHandlingResult.Handled("processed")
                    },
                )
            val job = bridge.start()
            runCurrent()

            bridge.stop()
            channel.emit(bootstrap("session-1"))
            runCurrent()

            assertFalse(job.isActive)
            assertEquals(true, channel.closed)
            assertEquals(emptyList<ProtocolMessage>(), handled)
        }

    @Test
    fun upstreamCompletionClosesResourcesExactlyOnce() =
        runTest {
            val channel = CompletingProtocolMessageChannel()
            var cleanupCalls = 0
            val bridge =
                DesktopProtocolChannelBridge(
                    channel = channel,
                    scope = this,
                    processor = { ProtocolHandlingResult.Handled("unused") },
                    onClosed = { cleanupCalls += 1 },
                )

            bridge.start().join()
            bridge.stop()

            assertEquals(1, cleanupCalls)
            assertEquals(1, channel.closeCalls)
        }

    @Test
    fun returnsSanitizedErrorAndKeepsProcessingAfterUnexpectedFailure() =
        runTest {
            val channel = BridgeRecordingProtocolMessageChannel()
            var calls = 0
            val bridge =
                DesktopProtocolChannelBridge(
                    channel = channel,
                    scope = this,
                    processor = {
                        calls += 1
                        if (calls == 1) throw DesktopProtocolMessageException(AppError.Authorization("Input is not authorized"))
                        ProtocolHandlingResult.Handled("processed")
                    },
                )

            bridge.start()
            runCurrent()
            channel.emit(bootstrap("session-1"))
            channel.emit(bootstrap("session-1"))
            runCurrent()
            bridge.stop()

            val error = assertIs<ProtocolMessage.Control>(channel.sent.single())
            val command = assertIs<ControlCommand.Error>(error.command)
            val failure = assertNotNull(command.failure)
            assertEquals(AegisFailureCodes.SESSION_REMOTE_ACTION_NOT_AUTHORIZED, command.code)
            assertEquals(command.code, failure.code)
            assertEquals(FailureCategory.AUTHORIZATION, failure.category)
            assertEquals("desktop-protocol-bridge", failure.component)
            assertEquals("process-protocol-message", failure.operation)
            assertEquals("authorization", failure.stage)
            assertEquals("session-1", failure.correlationId)
            assertEquals(2, calls)
        }

    @Test
    fun unexpectedFailureUsesStableCodeAndDoesNotExposeThrowableMessage() =
        runTest {
            val channel = BridgeRecordingProtocolMessageChannel()
            val bridge =
                DesktopProtocolChannelBridge(
                    channel = channel,
                    scope = this,
                    processor = { throw IllegalStateException("sensitive path C:/private/device.key") },
                )

            bridge.start()
            runCurrent()
            channel.emit(bootstrap("session-7"))
            runCurrent()
            bridge.stop()

            val response = assertIs<ProtocolMessage.Control>(channel.sent.single())
            val command = assertIs<ControlCommand.Error>(response.command)
            val failure = assertNotNull(command.failure)
            assertEquals(AegisFailureCodes.SESSION_PROTOCOL_PROCESSING_FAILED, command.code)
            assertEquals(FailureCategory.CAUSE_UNCONFIRMED, failure.category)
            assertEquals("session-7", failure.correlationId)
            assertTrue(failure.underlyingType.orEmpty().endsWith("IllegalStateException"))
            assertFalse(command.message.contains("device.key"))
            assertFalse(failure.technicalCause.orEmpty().contains("device.key"))
            assertNotNull(failure.expected)
            assertNotNull(failure.actual)
            assertNotNull(failure.nextAction)
        }

    @Test
    fun rejectsReservedInputBeforeItReachesTheBaseProcessor() =
        runTest {
            val channel = BridgeRecordingProtocolMessageChannel()
            val handled = mutableListOf<ProtocolMessage>()
            val bridge =
                DesktopProtocolChannelBridge(
                    channel = channel,
                    scope = this,
                    processor = { message ->
                        handled += message
                        ProtocolHandlingResult.Handled("must not process dedicated traffic")
                    },
                )

            bridge.start()
            runCurrent()
            channel.emit(ProtocolMessage.Input(SessionId("session-1"), RemoteInputEvent.ClipboardSync("secret")))
            channel.emit(ProtocolMessage.Control(SessionId("session-1"), ControlCommand.StopVisualSession))
            runCurrent()
            bridge.stop()

            assertEquals(emptyList<ProtocolMessage>(), handled)
        }

    @Test
    fun routesSignalingToDedicatedProcessorWithoutPassingItToControlHandler() =
        runTest {
            val channel = BridgeRecordingProtocolMessageChannel()
            val signals = mutableListOf<SignalingMessage>()
            val bridge =
                DesktopProtocolChannelBridge(
                    channel = channel,
                    scope = this,
                    processor = { _: ProtocolMessage -> ProtocolHandlingResult.Ignored("signaling is handled separately") },
                    signalingProcessor = signals::add,
                )

            bridge.start()
            runCurrent()
            val signal = SignalingMessage.Offer(SessionId("session-1"), "v=0")
            channel.emit(ProtocolMessage.Signaling(SessionId("session-1"), signal))
            runCurrent()
            bridge.stop()

            assertEquals<SignalingMessage>(signal, signals.single())
        }
}

private fun bootstrap(sessionId: String): ProtocolMessage.Control =
    ProtocolMessage.Control(
        sessionId = SessionId(sessionId),
        command =
            ControlCommand.StartVisualSession(
                videoConfig =
                    dev.aegis.remote.core.model.VideoConfig(
                        width = 1280,
                        height = 720,
                        fps = 30,
                        bitrateKbps = 1_000,
                        routeType = dev.aegis.remote.core.model.ConnectionRouteType.Lan,
                    ),
                iceConfig =
                    dev.aegis.remote.core.model
                        .StunTurnConfig(),
            ),
    )

private class CompletingProtocolMessageChannel : ProtocolMessageChannel {
    override val incoming: Flow<ProtocolMessage> = emptyFlow()
    var closeCalls = 0

    override suspend fun send(message: ProtocolMessage) = Unit

    override suspend fun close() {
        closeCalls += 1
    }
}

private class BridgeRecordingProtocolMessageChannel : ProtocolMessageChannel {
    private val incomingMessages = MutableSharedFlow<ProtocolMessage>(extraBufferCapacity = 16)
    val sent = mutableListOf<ProtocolMessage>()
    var closed = false

    override val incoming: Flow<ProtocolMessage> = incomingMessages

    suspend fun emit(message: ProtocolMessage) {
        incomingMessages.emit(message)
    }

    override suspend fun send(message: ProtocolMessage) {
        sent += message
    }

    override suspend fun close() {
        closed = true
    }
}
