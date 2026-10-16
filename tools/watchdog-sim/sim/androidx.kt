package androidx.core.app
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
object ServiceCompat {
    const val STOP_FOREGROUND_REMOVE = 1
    fun startForeground(service: Service, id: Int, notification: Notification, foregroundServiceType: Int) {}
    fun stopForeground(service: Service, flags: Int) {}
}
class NotificationCompat {
    abstract class Style
    class BigTextStyle : Style() { fun bigText(cs: CharSequence?): BigTextStyle = this }
    class Action(icon: Int, title: CharSequence?, intent: PendingIntent?)
}
