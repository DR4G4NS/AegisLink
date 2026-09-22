package dev.aegis.remote.android.webrtc

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.aegis.remote.core.network.NetworkChangeDebouncer
import dev.aegis.remote.core.network.NetworkKind
import dev.aegis.remote.core.network.networkChangeFingerprint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

internal fun androidNetworkChanges(context: Context): Flow<Unit> =
    callbackFlow {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val debouncer = NetworkChangeDebouncer()

        fun fingerprintAndOnline(): Pair<String, Boolean> {
            val network = manager.activeNetwork
            val capabilities = network?.let(manager::getNetworkCapabilities)
            val online =
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val validated =
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            val kind =
                when {
                    capabilities == null || !online -> NetworkKind.Offline
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.Cellular
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.Ethernet
                    vpn -> NetworkKind.Vpn
                    else -> NetworkKind.Unknown
                }
            val addresses =
                network
                    ?.let(manager::getLinkProperties)
                    ?.linkAddresses
                    ?.mapNotNull { address -> address.address.hostAddress }
                    .orEmpty()
            return networkChangeFingerprint(kind, validated, vpn, addresses) to online
        }

        fun maybeEmit() {
            val (fingerprint, online) = fingerprintAndOnline()
            if (debouncer.shouldEmit(fingerprint, System.currentTimeMillis(), online)) {
                trySend(Unit)
            }
        }

        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = maybeEmit()

                override fun onLost(network: Network) = maybeEmit()

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) = maybeEmit()

                override fun onLinkPropertiesChanged(
                    network: Network,
                    properties: android.net.LinkProperties,
                ) = maybeEmit()
            }
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }
