package com.glamsolo.phonehop.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.stateIn
import java.net.Inet4Address
import java.net.NetworkInterface

/** Local addresses other phones can reach us on (Wi-Fi, hotspot, Wi-Fi Direct, USB tethering). */
data class NetworkStatus(val addresses: List<String>) {
    val isConnected: Boolean get() = addresses.isNotEmpty()
}

class NetworkMonitor(context: Context, scope: CoroutineScope) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    val status: StateFlow<NetworkStatus> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(current())
            }

            override fun onLost(network: Network) {
                trySend(current())
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                trySend(current())
            }
        }
        trySend(current())
        val registered = runCatching {
            connectivity?.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        }.isSuccess
        awaitClose {
            if (registered) runCatching { connectivity?.unregisterNetworkCallback(callback) }
        }
    }.conflate().stateIn(scope, SharingStarted.WhileSubscribed(5_000), current())

    fun current(): NetworkStatus = NetworkStatus(LocalAddresses.find())
}

object LocalAddresses {
    /** Interface name prefixes of mobile data, VPN and other links that are not the local network. */
    private val EXCLUDED = listOf("rmnet", "ccmni", "pdp", "v4-", "clat", "tun", "ppp", "dummy", "ifb", "ims", "radio", "sit", "ip6", "gre", "lo")

    fun find(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { iface -> runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false) }
            .filter { iface -> EXCLUDED.none { iface.name.startsWith(it) } }
            .sortedBy { iface -> if (iface.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())
}
