package androidx.core.content
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
object ContextCompat {
    const val RECEIVER_NOT_EXPORTED = 4
    fun startForegroundService(context: Context, intent: Intent) {}
    fun registerReceiver(context: Context, receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? = null
}
