package com.openminis.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.openminis.app.sandbox.RootfsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Monitors network connectivity changes using ConnectivityManager.NetworkCallback.
 * Evicts the OkHttp connection pool on network transitions to prevent stale connections.
 */
class NetworkMonitor {

    enum class NetworkStatus {
        CONNECTED,
        DISCONNECTED
    }

    companion object {
        private const val TAG = "NetworkMonitor"

        /**
         * [T-android-stale-conn-retry-hang] App-wide ConnectionPool shared by
         * every long-lived LLM provider OkHttpClient (OpenAI / Anthropic /
         * Gemini) — OkHttp explicitly supports sharing one pool across
         * clients. Routing them all through this instance is what lets
         * [evictConnectionPool] actually reach provider connections:
         * previously eviction only covered the single client registered via
         * [start], and MinisApp registers none, so eviction was a no-op.
         * Through a local VPN/proxy (e.g. clash at 127.0.0.1:7890) the TCP
         * socket to localhost survives network flaps, so the pool kept
         * handing the dead h2 tunnel to every retry — requests wrote into it
         * and hung forever waiting for response headers.
         */
        val sharedLLMConnectionPool = okhttp3.ConnectionPool(
            5, 5, java.util.concurrent.TimeUnit.MINUTES,
        )
    }

    private val _status = MutableStateFlow(NetworkStatus.DISCONNECTED)
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private val availableNetworks = mutableSetOf<Network>()
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var okHttpClient: OkHttpClient? = null
    private var appContext: Context? = null

    /**
     * Background scope for DNS-refresh side effects. Kept off the callback
     * thread so ConnectivityManager isn't held up by file I/O.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Registers a network callback to observe connectivity changes.
     * Optionally accepts a shared OkHttpClient whose connection pool will be
     * evicted on network transitions.
     *
     * @param context Application or activity context.
     * @param client Optional shared OkHttpClient for connection pool eviction.
     */
    fun start(context: Context, client: OkHttpClient? = null) {
        okHttpClient = client
        appContext = context.applicationContext
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager

        val cm = connectivityManager ?: run {
            Log.e(TAG, "ConnectivityManager not available")
            return
        }

        synchronized(availableNetworks) {
            availableNetworks.clear()
        }

        // Set initial state
        val activeNetwork = cm.activeNetwork
        val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (activeNetwork != null && capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            synchronized(availableNetworks) {
                availableNetworks.add(activeNetwork)
            }
        }
        _status.value = if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            NetworkStatus.CONNECTED
        } else {
            NetworkStatus.DISCONNECTED
        }
        Log.d(TAG, "Initial network status: ${_status.value}")

        // Mirror iOS NetworkMonitor.swift:23-26 — do an immediate DNS write so
        // resolv.conf is populated before the first NetworkCallback fires. The
        // callback is async and can lag by hundreds of ms on cold start.
        refreshSandboxDns("initial")

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                val previousStatus = _status.value
                synchronized(availableNetworks) {
                    availableNetworks.add(network)
                }
                _status.value = NetworkStatus.CONNECTED
                if (previousStatus == NetworkStatus.DISCONNECTED) {
                    Log.d(TAG, "Network transition: DISCONNECTED -> CONNECTED")
                }
                // onAvailable can mean a Wi-Fi/cellular route was added while
                // another network remained available. The old pool is still
                // unsafe in that case, so evict on every new route rather than
                // only after a DISCONNECTED state.
                evictConnectionPool()
                refreshSandboxDns("onAvailable")
            }

            override fun onLost(network: Network) {
                val hasAvailableNetwork = synchronized(availableNetworks) {
                    availableNetworks.remove(network)
                    availableNetworks.isNotEmpty()
                }
                val previousStatus = _status.value
                _status.value = if (hasAvailableNetwork) {
                    NetworkStatus.CONNECTED
                } else {
                    NetworkStatus.DISCONNECTED
                }
                if (previousStatus != _status.value) {
                    Log.d(TAG, "Network transition: $previousStatus -> ${_status.value}")
                }
                // Connections bound to the lost route must not be reused,
                // even when another route keeps the app logically connected.
                evictConnectionPool()
                refreshSandboxDns("onLost")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                val hasAvailableNetwork = synchronized(availableNetworks) {
                    if (hasInternet) availableNetworks.add(network) else availableNetworks.remove(network)
                    availableNetworks.isNotEmpty()
                }
                val newStatus = if (hasAvailableNetwork) NetworkStatus.CONNECTED else NetworkStatus.DISCONNECTED
                if (newStatus != _status.value) {
                    Log.d(TAG, "Network capabilities changed: ${_status.value} -> $newStatus")
                    _status.value = newStatus
                }
                // Capability changes can accompany validated-route and
                // metered-network changes without changing CONNECTED state.
                // They can still invalidate the route selected by OkHttp and
                // can carry different DNS servers.
                evictConnectionPool()
                refreshSandboxDns("onCapabilitiesChanged")
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties
            ) {
                // DNS, routes, and proxy settings can change while the
                // network remains available. Treat that as a new transport so
                // no pooled HTTP/2 connection survives the route change.
                evictConnectionPool()
                refreshSandboxDns("onLinkPropertiesChanged")
            }
        }

        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
        Log.d(TAG, "Network monitoring started")
    }

    /**
     * Unregisters the network callback. Should be called during cleanup.
     */
    fun stop() {
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
                Log.d(TAG, "Network monitoring stopped")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Callback was not registered: ${e.message}")
            }
        }
        networkCallback = null
        connectivityManager = null
        okHttpClient = null
        appContext = null
        synchronized(availableNetworks) {
            availableNetworks.clear()
        }
    }

    /**
     * Evicts all idle connections from the OkHttp connection pools
     * to prevent stale connection reuse after a network change.
     * Always evicts [sharedLLMConnectionPool] (all LLM provider clients),
     * plus the optional client registered via [start].
     */
    private fun evictConnectionPool() {
        sharedLLMConnectionPool.evictAll()
        okHttpClient?.connectionPool?.evictAll()
        Log.d(TAG, "OkHttp connection pools evicted (shared LLM pool + registered client)")
    }

    /**
     * Refresh the sandbox rootfs' /etc/resolv.conf from the current system
     * DNS configuration. Runs on IO so we don't block the ConnectivityManager
     * callback thread with file I/O. Safe to call before the rootfs has been
     * extracted — [RootfsManager.refreshDns] no-ops when the rootfs is missing.
     *
     * Mirrors iOS NetworkMonitor.swift:26,60 which calls refreshDns() on every
     * NWPath update so already-running shells pick up the new nameservers the
     * next time they resolve a hostname (musl's getaddrinfo re-reads
     * resolv.conf on each lookup — no cache to invalidate).
     */
    private fun refreshSandboxDns(reason: String) {
        val ctx = appContext ?: return
        scope.launch {
            try {
                RootfsManager.getInstance(ctx).refreshDns()
                Log.d(TAG, "[DNS] sandbox resolv.conf refreshed ($reason)")
            } catch (t: Throwable) {
                Log.w(TAG, "[DNS] refresh failed ($reason): ${t.message}")
            }
        }
    }
}