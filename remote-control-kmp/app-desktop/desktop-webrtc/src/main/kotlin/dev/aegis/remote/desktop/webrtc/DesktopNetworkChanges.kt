package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.network.NetworkChangeDebouncer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.NetworkInterface

internal fun desktopNetworkChanges(
    pollIntervalMillis: Long = 2_000L,
    fingerprint: () -> String = ::desktopNetworkFingerprint,
    clock: () -> Long = { System.currentTimeMillis() },
    debounceMillis: Long = NetworkChangeDebouncer.DEFAULT_DEBOUNCE_MILLIS,
): Flow<Unit> =
    flow {
        val debouncer = NetworkChangeDebouncer(debounceMillis)
        val initial = fingerprint()
        debouncer.shouldEmit(initial, clock(), initial.isNotBlank())
        while (true) {
            delay(pollIntervalMillis)
            val current = fingerprint()
            val online = current.isNotBlank()
            if (debouncer.shouldEmit(current, clock(), online)) {
                emit(Unit)
            }
        }
    }

internal fun desktopNetworkFingerprint(): String =
    NetworkInterface
        .getNetworkInterfaces()
        .toList()
        .filter { it.isUp && !it.isLoopback }
        .sortedBy { it.name }
        .joinToString("|") { network ->
            val addresses =
                network.inetAddresses
                    .toList()
                    .map { it.hostAddress }
                    .sorted()
                    .joinToString(",")
            "${network.name}:$addresses"
        }
