// ============================================================
// data/enrollment/AndroidConnectivityMonitor.kt
// ConnectivityManager default-network callback exposed as a cold Flow.
// ============================================================
package com.orion.data.enrollment

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Emits whether a default network is currently available. Cold: the callback is
 * registered only while collected (e.g. inside repeatOnLifecycle(STARTED)) and
 * always unregistered in awaitClose, so it cannot leak past the collector.
 *
 * "Available" means the platform has a default network, not that the enrollment
 * backend is reachable; the verification call itself is the reachability check.
 * Requires ACCESS_NETWORK_STATE. registerDefaultNetworkCallback is API 24+. If the
 * callback cannot be registered, the flow only emits the initial value.
 */
class AndroidConnectivityMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    val isConnected: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        trySend(connectivityManager.activeNetwork != null)
        val registered = try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            true
        } catch (e: RuntimeException) {
            // SecurityException (permission missing) or the platform's per-app callback
            // limit (ConnectivityManager.TooManyRequestsException, a RuntimeException that is
            // not public API). Degrade to "no connectivity-return trigger": launch,
            // foreground, periodic, and manual checks still run; the app must not crash.
            false
        }
        awaitClose { if (registered) connectivityManager.unregisterNetworkCallback(callback) }
    }
        .conflate()
        .distinctUntilChanged()
}
