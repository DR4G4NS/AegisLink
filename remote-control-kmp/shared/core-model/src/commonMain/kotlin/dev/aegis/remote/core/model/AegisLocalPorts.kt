package dev.aegis.remote.core.model

/** Well-known local Aegis ports shared by Android, desktop, and tests. */
object AegisLocalPorts {
    const val VISUAL_PROTOCOL = 48_291
    const val SSH = 48_222
    const val LAN_ANNOUNCE_UDP = VISUAL_PROTOCOL
}

/** Link-local IPv4 multicast group in 224.0.0.0/24 so Android devices accept membership. */
object AegisLanDiscovery {
    const val MULTICAST_GROUP = "224.0.0.191"
    const val PORT = AegisLocalPorts.LAN_ANNOUNCE_UDP
}
