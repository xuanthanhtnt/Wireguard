package com.zaneschepke.wireguardautotunnel.xt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/** Result of looking up a server address source. */
data class XtLookupResult(
    val system: List<String> = emptyList(),
    val doh: List<String> = emptyList(),
    val error: String? = null,
) {
    /** System answers first: they are what the tunnel itself will use. */
    val all: List<String>
        get() = (system + doh).distinct()
}

/** Network operations of the watchdog, all performed outside the tunnel. */
interface XtNetworkApi {
    fun hasValidatedUnderlay(): Boolean

    suspend fun lookup(
        source: String,
        useDoh: Boolean,
        current: Collection<String> = emptyList(),
        forceDoh: Boolean = false,
    ): XtLookupResult

    suspend fun internetReachable(): Boolean

    suspend fun tcpReachable(ip: String, port: Int): Boolean

    suspend fun ping(ip: String): Boolean
}

/** Android services the watchdog needs; behind an interface so its logic can be unit tested. */
interface XtPlatform {
    fun string(id: Int, vararg args: Any): String

    fun isDeviceIdle(): Boolean

    /** Starts / stops the foreground service that keeps the app alive while a tunnel is paused. */
    fun startKeepAlive()

    fun stopKeepAlive()

    /** [onNetworkValidated]: a physical network became usable. [onScreenOn]: screen on / unlock. */
    fun registerSignals(onNetworkValidated: () -> Unit, onScreenOn: () -> Unit)
}

/** Timings of the watchdog loops (shortened in tests). */
data class XtTiming(
    val initialIpCheckDelayMs: Long = 20_000L,
    val outageTickMs: Long = 5_000L,
    val outageRecheckMs: Long = 30_000L,
    val verifyTimeoutMs: Long = 12_000L,
    val restoreFirstProbeDelayMs: Long = 20_000L,
    val probeSignalDebounceMs: Long = 45_000L,
    /** Length of one "second" of the user settings (pause after, probe interval, ...). */
    val secondMs: Long = 1_000L,
)

class AndroidXtPlatform(private val context: Context, private val powerManager: PowerManager) :
    XtPlatform {

    private val validatedNetworks = ConcurrentHashMap<Network, Boolean>()

    override fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    override fun isDeviceIdle(): Boolean = powerManager.isDeviceIdleMode

    override fun startKeepAlive() = ServerWatchdogService.start(context)

    override fun stopKeepAlive() = ServerWatchdogService.stop(context)

    override fun registerSignals(onNetworkValidated: () -> Unit, onScreenOn: () -> Unit) {
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        val validated =
                            networkCapabilities.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_VALIDATED
                            )
                        val previous = validatedNetworks.put(network, validated)
                        if (validated && previous != true) onNetworkValidated()
                    }

                    override fun onLost(network: Network) {
                        validatedNetworks.remove(network)
                    }
                },
            )
        } catch (e: Exception) {
            Timber.e(e, "XT: network callback registration failed")
        }

        try {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        onScreenOn()
                    }
                }
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (e: Exception) {
            Timber.e(e, "XT: screen receiver registration failed")
        }
    }
}
