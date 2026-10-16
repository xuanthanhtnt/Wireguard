package com.zaneschepke.wireguardautotunnel.xt

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zaneschepke.wireguardautotunnel.util.Constants
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * Keeps WG VPN alive while a tunnel is paused because its server is down. Without a VPN
 * running, Android would otherwise freeze the app and the server would never be checked again.
 */
class ServerWatchdogService : Service(), KoinComponent {

    private val coordinator: ServerWatchdogCoordinator by inject()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_STICKY
    }

    private fun goForeground() {
        try {
            ServiceCompat.startForeground(
                this,
                ServerWatchdogCoordinator.ONGOING_NOTIFICATION_ID,
                coordinator.buildOngoingNotification(),
                Constants.SPECIAL_USE_SERVICE_TYPE_ID,
            )
        } catch (e: Exception) {
            Timber.e(e, "XT: watchdog service could not enter foreground")
            stopSelf()
        }
    }

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ServerWatchdogService::class.java),
                )
            } catch (e: Exception) {
                // Android 12+ may refuse a background start (battery optimisation not disabled).
                // The watchdog keeps working while the app process stays alive.
                Timber.w(e, "XT: could not start watchdog service")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ServerWatchdogService::class.java))
            } catch (e: Exception) {
                Timber.w(e, "XT: could not stop watchdog service")
            }
        }
    }
}
