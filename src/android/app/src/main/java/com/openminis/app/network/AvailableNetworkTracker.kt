package com.openminis.app.network

/**
 * Thread-safe membership tracker for ConnectivityManager callbacks.
 * Android may report Wi-Fi and cellular at the same time, so losing one
 * route does not necessarily mean the process is offline.
 */
internal class AvailableNetworkTracker<T> {
    data class Update(
        val membershipChanged: Boolean,
        val isConnected: Boolean,
    )

    private val networks = mutableSetOf<T>()

    @Synchronized
    fun update(network: T, available: Boolean): Update {
        val changed = if (available) {
            networks.add(network)
        } else {
            networks.remove(network)
        }
        return Update(
            membershipChanged = changed,
            isConnected = networks.isNotEmpty(),
        )
    }

    @Synchronized
    fun clear() {
        networks.clear()
    }
}
