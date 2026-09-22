package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.protocol.JsonProtocolMessageChannel
import dev.aegis.remote.protocol.MAX_PROTOCOL_TEXT_PAYLOAD_BYTES
import dev.aegis.remote.protocol.ProtocolDataChannelKind
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageJsonCodec
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceConnectionState
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpReceiver
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCSignalingState
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Local-only native WebRTC harness. It creates two desktop peers, negotiates them
 * directly, and observes the production admission and text-transport boundary.
 */
class DesktopNativeDataChannelBoundaryIntegrationTest {
    @Test
    fun `native peers reject unknown duplicate and oversized channels before the handler`() =
        runBlocking {
            assumeTrue(
                "Set -Daegis.nativeWebRtcIntegration=true to run the native WebRTC harness.",
                System.getProperty("aegis.nativeWebRtcIntegration") == "true",
            )
            val factory = PeerConnectionFactory()
            val receiverAdmission = DesktopDataChannelAdmission()
            val receiverDataChannels = DesktopInboundDataChannelGeneration()
            val receivedLabels = CopyOnWriteArrayList<String>()
            val receivedProperties = CopyOnWriteArrayList<String>()
            val rejectedLabels = CopyOnWriteArrayList<String>()
            val handled = CopyOnWriteArrayList<ProtocolMessage>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val protectedTransport = AtomicReference<DesktopWebRtcDataChannelTextTransport>()

            lateinit var sender: RTCPeerConnection
            lateinit var receiver: RTCPeerConnection
            val descriptionsApplied = AtomicBoolean(false)
            val senderCandidates = CopyOnWriteArrayList<RTCIceCandidate>()
            val receiverCandidates = CopyOnWriteArrayList<RTCIceCandidate>()
            sender =
                factory.createPeerConnection(
                    RTCConfiguration(),
                    peerObserver(
                        onIceCandidate = { candidate ->
                            if (descriptionsApplied.get()) receiver.addIceCandidate(candidate) else senderCandidates += candidate
                        },
                    ) {},
                )
            receiver =
                factory.createPeerConnection(
                    RTCConfiguration(),
                    peerObserver(
                        onIceCandidate = { candidate ->
                            if (descriptionsApplied.get()) sender.addIceCandidate(candidate) else receiverCandidates += candidate
                        },
                    ) { channel ->
                        receiverDataChannels.receive(channel) { owner ->
                            receivedLabels += channel.label
                            receivedProperties += "${channel.label}:${channel.isOrdered}:${channel.maxRetransmits}"
                            val kind = receiverAdmission.admit(channel)
                            if (kind == null) {
                                rejectedLabels += channel.label
                                owner.close()
                            } else if (kind == ProtocolDataChannelKind.Control) {
                                val transport = DesktopWebRtcDataChannelTextTransport(owner)
                                protectedTransport.set(transport)
                                DesktopProtocolChannelBridge(
                                    channel = JsonProtocolMessageChannel(transport),
                                    scope = scope,
                                    processor = { message ->
                                        handled += message
                                        ProtocolHandlingResult.Handled("unexpected")
                                    },
                                ).start()
                            }
                        }
                    },
                )

            val control = sender.createDataChannel(ProtocolDataChannelKind.Control.label, dataChannelInit(ProtocolDataChannelKind.Control))
            sender.createDataChannel("untrusted-channel", dataChannelInit(ProtocolDataChannelKind.Control))
            sender.createDataChannel(ProtocolDataChannelKind.Control.label, dataChannelInit(ProtocolDataChannelKind.Control))
            negotiate(sender, receiver)
            descriptionsApplied.set(true)
            delay(100)
            senderCandidates.forEach(receiver::addIceCandidate)
            receiverCandidates.forEach(sender::addIceCandidate)

            val channelsReady =
                withTimeoutOrNull(15_000) {
                    while (protectedTransport.get() == null || rejectedLabels.size != 2) delay(25)
                    true
                }
            check(channelsReady == true) {
                "Native DataChannels did not open: sender=${sender.connectionState}/${sender.iceConnectionState}, " +
                    "receiver=${receiver.connectionState}/${receiver.iceConnectionState}, " +
                    "candidates=${senderCandidates.size}/${receiverCandidates.size}, labels=$receivedLabels, " +
                    "rejected=$rejectedLabels, properties=$receivedProperties, protected=${protectedTransport.get() != null}"
            }
            assertEquals(
                setOf(ProtocolDataChannelKind.Control.label, "untrusted-channel"),
                rejectedLabels.toSet(),
            )
            assertTrue(receivedLabels.containsAll(listOf(ProtocolDataChannelKind.Control.label, "untrusted-channel")))

            delay(100)
            control.sendProtocolText(
                ProtocolMessageJsonCodec().encode(
                    ProtocolMessage.Input(SessionId("session-1"), RemoteInputEvent.ClipboardSync("must not dispatch")),
                ),
            )
            control.sendProtocolText("x".repeat(MAX_PROTOCOL_TEXT_PAYLOAD_BYTES + 1))

            withTimeout(5_000) {
                while (control.state != RTCDataChannelState.CLOSED) delay(25)
            }
            assertEquals(emptyList(), handled)

            receiverDataChannels.seal()
            sender.close()
            receiver.close()
            receiverDataChannels.drain()
            factory.dispose()
            scope.cancel()
        }

    private fun RTCDataChannel.sendProtocolText(payload: String) {
        send(RTCDataChannelBuffer(ByteBuffer.wrap(payload.toByteArray(Charsets.UTF_8)), false))
    }

    private fun dataChannelInit(kind: ProtocolDataChannelKind): RTCDataChannelInit =
        RTCDataChannelInit().also { init ->
            init.ordered = kind.ordered
            init.maxRetransmits = kind.maxRetransmits ?: -1
        }

    private fun peerObserver(
        onIceCandidate: (RTCIceCandidate) -> Unit,
        onDataChannel: (RTCDataChannel) -> Unit,
    ): PeerConnectionObserver =
        object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) = onIceCandidate(candidate)

            override fun onDataChannel(channel: RTCDataChannel) = onDataChannel(channel)

            override fun onConnectionChange(state: RTCPeerConnectionState) = Unit

            override fun onIceConnectionChange(state: RTCIceConnectionState) = Unit

            override fun onIceGatheringChange(state: RTCIceGatheringState) = Unit

            override fun onSignalingChange(state: RTCSignalingState) = Unit

            override fun onAddStream(stream: MediaStream) = Unit

            override fun onRemoveStream(stream: MediaStream) = Unit

            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(
                receiver: RTCRtpReceiver,
                mediaStreams: Array<MediaStream>,
            ) = Unit

            override fun onTrack(transceiver: RTCRtpTransceiver) = Unit
        }

    private suspend fun negotiate(
        sender: RTCPeerConnection,
        receiver: RTCPeerConnection,
    ) {
        val offer = sender.createOffer()
        sender.setLocal(offer)
        receiver.setRemote(offer)
        val answer = receiver.createAnswer()
        receiver.setLocal(answer)
        sender.setRemote(answer)
    }

    private suspend fun RTCPeerConnection.createOffer(): RTCSessionDescription = awaitDescription { observer -> createOffer(RTCOfferOptions(), observer) }

    private suspend fun RTCPeerConnection.createAnswer(): RTCSessionDescription = awaitDescription { observer -> createAnswer(RTCAnswerOptions(), observer) }

    private suspend fun RTCPeerConnection.awaitDescription(
        create: (CreateSessionDescriptionObserver) -> Unit,
    ): RTCSessionDescription =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            create(
                object : CreateSessionDescriptionObserver {
                    override fun onSuccess(description: RTCSessionDescription) = continuation.resume(description) {}

                    override fun onFailure(error: String) = continuation.resumeWith(Result.failure(IllegalStateException(error)))
                },
            )
        }

    private suspend fun RTCPeerConnection.setLocal(description: RTCSessionDescription) {
        setDescription { observer ->
            setLocalDescription(description, observer)
        }
    }

    private suspend fun RTCPeerConnection.setRemote(description: RTCSessionDescription) {
        setDescription { observer ->
            setRemoteDescription(description, observer)
        }
    }

    private suspend fun RTCPeerConnection.setDescription(action: (SetSessionDescriptionObserver) -> Unit) {
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            action(
                object : SetSessionDescriptionObserver {
                    override fun onSuccess() = continuation.resume(Unit) {}

                    override fun onFailure(error: String) = continuation.resumeWith(Result.failure(IllegalStateException(error)))
                },
            )
        }
    }
}
