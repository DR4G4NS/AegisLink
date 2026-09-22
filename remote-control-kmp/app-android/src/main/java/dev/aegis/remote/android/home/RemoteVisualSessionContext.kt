package dev.aegis.remote.android.home

import dev.aegis.remote.android.webrtc.AndroidWebRtcViewerSession
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.nat.ValidatedIceRoute
import dev.aegis.remote.core.session.AdaptiveQualitySessionController
import dev.aegis.remote.core.session.ConnectionSupervisor
import dev.aegis.remote.core.session.ProtocolCapabilityHandshake
import dev.aegis.remote.core.session.SessionLivenessTracker
import dev.aegis.remote.core.session.VisualSessionPlan
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import org.webrtc.SurfaceViewRenderer

/** Shared ownership record for the visual session and all of its child jobs. */
internal class RemoteVisualSessionContext {
    val inputMutex = Mutex()
    var session: AndroidWebRtcViewerSession? = null
    var adaptiveQualityController: AdaptiveQualitySessionController? = null
    var renderer: SurfaceViewRenderer? = null
    var sessionId: SessionId? = null
    var plan: VisualSessionPlan? = null
    var reconnectAttempt: Int = 0
    var reconnectJob: Job? = null
    var stateJob: Job? = null
    var protocolJob: Job? = null
    var statsJob: Job? = null
    var pingJob: Job? = null
    var turnRefreshJob: Job? = null
    var capabilityJob: Job? = null
    val supervisor = ConnectionSupervisor()
    val liveness = SessionLivenessTracker(clock = { System.currentTimeMillis() })
    val capabilityHandshake = ProtocolCapabilityHandshake()
    var iceRestartInFlight: Boolean = false
    var lastSelectedPairType: ConnectionRouteType? = null
    var lastStableEpochMillis: Long? = null
    var lastBackgroundedAtEpochMillis: Long? = null
    var networkOnline: Boolean = true
    var protocolSkew: Boolean = false
    var lastSelectedIceRoute: ValidatedIceRoute? = null
}
