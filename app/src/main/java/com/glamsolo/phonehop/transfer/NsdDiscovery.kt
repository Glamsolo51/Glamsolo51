package com.glamsolo.phonehop.transfer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.glamsolo.phonehop.core.Protocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.InetAddress

/** Makes receivers visible on the local network and finds them. */
interface Discovery {
    /** Announces this phone as a receiver until the returned handle is closed. */
    fun advertise(name: String, port: Int): AutoCloseable

    /** Receivers currently visible on the network. */
    fun discover(): Flow<List<PeerDevice>>
}

/** DNS-SD (mDNS) through Android's NsdManager: works on any Wi-Fi network or hotspot. */
class NsdDiscovery(context: Context) : Discovery {
    private val nsd: NsdManager? = runCatching { context.getSystemService(NsdManager::class.java) }.getOrNull()
    private val main = Handler(Looper.getMainLooper())

    override fun advertise(name: String, port: Int): AutoCloseable {
        val manager = nsd ?: return AutoCloseable { }
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = Protocol.SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Advertised as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        val registered = runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "registerService failed", it) }
            .isSuccess
        return AutoCloseable {
            if (registered) runCatching { manager.unregisterService(listener) }
        }
    }

    override fun discover(): Flow<List<PeerDevice>> = callbackFlow {
        val manager = nsd
        if (manager == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        // All bookkeeping happens on the main thread, NSD callbacks are posted there.
        val found = LinkedHashMap<String, PeerDevice>()
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        var closed = false

        fun publish() {
            trySend(found.values.toList())
        }

        fun resolveNext() {
            if (resolving || closed) return
            val next = queue.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                            main.postDelayed({ queue.addLast(info); resolveNext() }, 300)
                        } else {
                            resolveNext()
                        }
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        val host = hostOf(info)
                        if (host != null && !closed) {
                            found[info.serviceName] = PeerDevice(info.serviceName, host, info.port)
                            publish()
                        }
                        resolveNext()
                    }
                }
            }
            runCatching {
                @Suppress("DEPRECATION")
                manager.resolveService(next, listener)
            }.onFailure {
                resolving = false
                main.post { resolveNext() }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    queue.removeAll { it.serviceName == info.serviceName }
                    queue.addLast(info)
                    resolveNext()
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (found.remove(info.serviceName) != null) publish()
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        trySend(emptyList())
        val started = runCatching {
            manager.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.isSuccess
        awaitClose {
            main.post { closed = true }
            if (started) runCatching { manager.stopServiceDiscovery(discoveryListener) }
        }
    }

    private fun hostOf(info: NsdServiceInfo): String? {
        val addresses: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host)
        }
        // IPv4 first: link-local IPv6 needs a scope id that is easy to get wrong.
        val best = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
        return best?.hostAddress
    }

    private companion object {
        const val TAG = "PhoneHopNsd"
    }
}
