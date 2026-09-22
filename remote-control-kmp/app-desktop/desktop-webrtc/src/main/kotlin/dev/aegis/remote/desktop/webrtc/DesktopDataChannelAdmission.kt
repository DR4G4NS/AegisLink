package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.protocol.ProtocolDataChannelKind
import dev.onvoid.webrtc.RTCDataChannel

/**
 * Admits at most one contract-compliant reserved channel per peer generation.
 * Rejected native channels never reach a protocol handler. Their generation owner closes them.
 */
internal class DesktopDataChannelAdmission {
    private val boundKinds = mutableSetOf<ProtocolDataChannelKind>()

    fun admit(channel: RTCDataChannel): ProtocolDataChannelKind? =
        synchronized(boundKinds) {
            val kind = ProtocolDataChannelKind.fromLabel(channel.label)
            val normalizedMaxRetransmits = if (channel.isReliable) -1 else channel.maxRetransmits
            if (kind == null || !kind.matchesNegotiatedProperties(channel.isOrdered, normalizedMaxRetransmits)) {
                return null
            }
            if (!boundKinds.add(kind)) {
                return null
            }
            kind
        }

    fun reset() {
        synchronized(boundKinds) {
            boundKinds.clear()
        }
    }
}
