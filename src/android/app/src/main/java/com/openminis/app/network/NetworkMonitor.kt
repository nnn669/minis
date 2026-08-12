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
         * Gemini) - OkHttp explicitly supports sharing one pool across
         * clients. Routing them all through this instance is what lets
         * [evictConnectionPool] actually reach provider connections:
         * previously eviction only covered the single client registered via
         * [start], and MinisApp registers none, so eviction was a no-op.
         * Through a local VPN/proxy (e.g. clash at 127.0.0.1:7890) the TCP
         * socket to localhost survives network flaps, so the pool kept
         * handing the dead h2 tunnel to every retry - requests wrote into it
         * and hung forever waiting for response headers.
         */
        val sharedLLMConnectionPool = okhttp3.ConnectionPool(
            5, 5, java.util.concurrent.TimeUnit.MINUTES,
        )
    }

    private val _status = MutableStateFlow(NetworkStatus.DISCONNECTED)
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private val availableNetworks = AvailableNetworkTracker<Network>()
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var okHttpClient: OkHttpClient? = null
    private var appContext: Context? = null

    /** Keep DNS-refresh file I/O off the ConnectivityManager callback thread. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Registers a network callback to observe connectivity changes.
     * Optionally accepts a shared OkHttpClient whose connection pool will be
     * evicted on network transitions.
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

        availableNetworks.clear()

        val activeNetwork = cm.activeNetwork
        val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val initiallyConnected = capabilities?.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        ) == true
        if (activeNetwork != null && initiallyConnected) {
            availableNetworks.update(activeNetwork, available = true)
        }
        updateStatus(initiallyConnected, "Initial network status")

        // Populate resolv.conf before the asynchronous callback first fires.
        refreshSandboxDns("initial")

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val update = availableNetworks.update(network, available = true)
                updateStatus(update.isConnected, "Network available")
                if (update.membershipChanged) {
                    handleRouteChange("onAvailable")
                }
            }

            override fun onLost(network: Network) {
                val update = availableNetworks.update(network, available = false)
                updateStatus(update.isConnected, "Network lost")
                if (update.membershipChanged) {
                    handleRouteChange("onLost")
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                val update = availableNetworks.update(network, hasInternet)
                updateStatus(update.isConnected, "Network capabilities changed")
                if (update.membershipChanged) {
                    handleRouteChange("onCapabilitiesChanged")
                }
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties
            ) {
                // DNS, routes, and proxy settings can change while the same
                // network remains available. Existing HTTP/2 connections may
                // still be pinned to the obsolete route.
                Log.d(TAG, "Link properties changed for $network: $linkProperties")
                handleRouteChange("onLinkPropertiesChanged")
            }
        }

        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
        Log.d(TAG, "Network monitoring started")
    }

    /** Unregisters the network callback. */
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
        availableNetworks.clear()
        _status.value = NetworkStatus.DISCONNECTED
    }

    private fun updateStatus(isConnected: Boolean, reason: String) {
        val newStatus = if (isConnected) {
            NetworkStatus.CONNECTED
        } else {
            NetworkStatus.DISCONNECTED
        }
        val previousStatus = _status.value
        if (previousStatus != newStatus) {
            _status.value = newStatus
            Log.d(TAG, "$reason: $previousStatus -> $newStatus")
        } else if (reason == "Initial network status") {
            Log.d(TAG, "$reason: $newStatus")
        }
    }

    private fun handleRouteChange(reason: String) {
        evictConnectionPool()
        refreshSandboxDns(reason)
    }

    /**
     * Evicts all idle connections from the OkHttp connection pools.
     * Always evicts [sharedLLMConnectionPool], plus the optional client
     * registered via [start].
     */
    private fun evictConnectionPool() {
        sharedLLMConnectionPool.evictAll()
        okHttpClient?.connectionPool?.let { pool ->
            if (pool !== sharedLLMConnectionPool) pool.evictAll()
        }
        Log.d(TAG, "OkHttp connection pools evicted")
    }

    /**
     * Refresh the sandbox rootfs' /etc/resolv.conf from the current system
     * DNS configuration. Safe before rootfs extraction; refreshDns no-ops.
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
