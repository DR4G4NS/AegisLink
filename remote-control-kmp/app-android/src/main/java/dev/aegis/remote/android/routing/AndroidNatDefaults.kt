package dev.aegis.remote.android.routing

/**
 * Public STUN bootstrap used consistently by route probing and WebRTC plans.
 * TURN credentials remain short-lived and are still obtained from the relay.
 */
internal val DEFAULT_ANDROID_STUN_URLS: List<String> =
    listOf("stun:stun.l.google.com:19302")
