package dev.aegis.remote.desktop.webrtc

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceConnectionState
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpReceiver
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCRtpTransceiverDirection
import dev.onvoid.webrtc.RTCRtpTransceiverInit
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCSignalingState
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaStream
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class DesktopNativeVideoBoundaryIntegrationTest {
    @Test
    fun `answerer track sends frames to an offered receive-only transceiver`() =
        runBlocking {
            assumeTrue(
                "Set -Daegis.nativeWebRtcIntegration=true to run the native WebRTC harness.",
                System.getProperty("aegis.nativeWebRtcIntegration") == "true",
            )
            val factory = PeerConnectionFactory()
            val descriptionsApplied = AtomicBoolean(false)
            val offererCandidates = CopyOnWriteArrayList<RTCIceCandidate>()
            val answererCandidates = CopyOnWriteArrayList<RTCIceCandidate>()
            val remoteTrack = AtomicReference<VideoTrack>()
            lateinit var offerer: RTCPeerConnection
            lateinit var answerer: RTCPeerConnection
            offerer =
                factory.createPeerConnection(
                    RTCConfiguration(),
                    observer(
                        onIceCandidate = { candidate ->
                            if (descriptionsApplied.get()) answerer.addIceCandidate(candidate) else offererCandidates += candidate
                        },
                        onTrack = { transceiver ->
                            (transceiver.receiver.track as? VideoTrack)?.let(remoteTrack::set)
                        },
                    ),
                )
            answerer =
                factory.createPeerConnection(
                    RTCConfiguration(),
                    observer(
                        onIceCandidate = { candidate ->
                            if (descriptionsApplied.get()) offerer.addIceCandidate(candidate) else answererCandidates += candidate
                        },
                    ),
                )
            val offerSource = CustomVideoSource()
            val offerTrack = factory.createVideoTrack("aegis-test-receiver", offerSource)
            val offerTransceiver =
                offerer.addTransceiver(
                    offerTrack,
                    RTCRtpTransceiverInit().also { it.direction = RTCRtpTransceiverDirection.RECV_ONLY },
                )
            val source = CustomVideoSource()
            val localTrack = factory.createVideoTrack("aegis-test-video", source)
            val videoSender = answerer.addTrack(localTrack, listOf("aegis-desktop"))

            val offer = offerer.createOffer()
            assertContains(offer.sdp, "a=recvonly")
            offerer.setLocal(offer)
            answerer.setRemote(offer)
            val answer = answerer.createAnswer()
            assertContains(answer.sdp, "a=sendonly")
            answerer.setLocal(answer)
            offerer.setRemote(answer)
            descriptionsApplied.set(true)
            offererCandidates.forEach(answerer::addIceCandidate)
            answererCandidates.forEach(offerer::addIceCandidate)

            withTimeout(10_000) {
                while (remoteTrack.get() == null) delay(20)
            }
            val receivedFrames = AtomicInteger()
            val sink =
                dev.onvoid.webrtc.media.video
                    .VideoTrackSink { receivedFrames.incrementAndGet() }
            remoteTrack.get().addSink(sink)
            withTimeout(10_000) {
                while (receivedFrames.get() == 0) {
                    val buffer = NativeI420Buffer.allocate(16, 16)
                    val frame = VideoFrame(buffer, System.nanoTime())
                    source.pushFrame(frame)
                    frame.release()
                    delay(50)
                }
            }
            assertTrue(receivedFrames.get() > 0)

            remoteTrack.get().removeSink(sink)
            answerer.removeTrack(videoSender)
            offerer.removeTrack(offerTransceiver.sender)
            offerer.close()
            answerer.close()
            localTrack.dispose()
            source.dispose()
            offerTrack.dispose()
            offerSource.dispose()
            factory.dispose()
        }

    private fun observer(
        onIceCandidate: (RTCIceCandidate) -> Unit,
        onTrack: (RTCRtpTransceiver) -> Unit = {},
    ): PeerConnectionObserver =
        object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) = onIceCandidate(candidate)

            override fun onTrack(transceiver: RTCRtpTransceiver) = onTrack(transceiver)

            override fun onDataChannel(channel: RTCDataChannel) = Unit

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
        setDescription { observer -> setLocalDescription(description, observer) }
    }

    private suspend fun RTCPeerConnection.setRemote(description: RTCSessionDescription) {
        setDescription { observer -> setRemoteDescription(description, observer) }
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
