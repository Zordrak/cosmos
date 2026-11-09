package com.cosmos.screensaver.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/** Reports whether the current connection is limited-data. Injectable so policy can be tested without a device. */
internal fun interface NetworkProbe {
    fun meteredness(): Meteredness
}

/**
 * [hasActiveNetwork] false means there is nothing to measure. A network without the "not metered"
 * capability is metered, whatever its transport: a phone hotspot is Wi-Fi and limited.
 */
internal fun meterednessOf(hasActiveNetwork: Boolean, notMetered: Boolean): Meteredness = when {
    !hasActiveNetwork -> Meteredness.UNKNOWN_NOW
    notMetered -> Meteredness.UNMETERED
    else -> Meteredness.METERED
}

/** Asks the system's ConnectivityManager. Cosmos works without the permission: the answer is then UNAVAILABLE. */
internal class AndroidNetworkProbe(private val context: Context) : NetworkProbe {
    override fun meteredness(): Meteredness {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
            logOnce("The network-state permission is not granted; connection limits cannot be checked")
            return Meteredness.UNAVAILABLE
        }
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            logOnce("This TV has no connectivity service; connection limits cannot be checked")
            return Meteredness.UNAVAILABLE
        }
        try {
            val network = manager.activeNetwork
            val capabilities = network?.let { manager.getNetworkCapabilities(it) }
            return meterednessOf(
                hasActiveNetwork = capabilities != null,
                notMetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
            )
        } catch (error: SecurityException) {
            logOnce("Connection limits cannot be checked: ${error.message}")
            return Meteredness.UNAVAILABLE
        }
    }

    private companion object {
        const val TAG = "CosmosNetwork"

        @Volatile
        var logged = false

        fun logOnce(message: String) {
            if (logged) return
            logged = true
            Log.w(TAG, message)
        }
    }
}
