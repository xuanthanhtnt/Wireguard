package com.zaneschepke.wireguardautotunnel.notification
import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.zaneschepke.wireguardautotunnel.domain.enums.NotificationAction
import com.zaneschepke.wireguardautotunnel.notification.AndroidNotificationService.NotificationChannels
interface NotificationService {
    val context: Context
    fun createNotification(
        channel: NotificationChannels,
        title: CharSequence = "",
        subText: String? = null,
        actions: Collection<NotificationCompat.Action> = emptyList(),
        description: String = "",
        showTimestamp: Boolean = true,
        onGoing: Boolean = false,
        onlyAlertOnce: Boolean = true,
        groupKey: String? = null,
        isGroupSummary: Boolean = false,
        style: NotificationCompat.Style? = null,
        requestPromotedOngoing: Boolean = false,
        shortCriticalText: String? = null,
        chronometerBaseMillis: Long? = null,
        color: Int? = null,
    ): Notification
    fun createNotificationAction(notificationAction: NotificationAction, extraId: Int? = null, authenticationRequired: Boolean = false): NotificationCompat.Action
    fun remove(notificationId: Int)
    fun show(notificationId: Int, notification: Notification)
}
class AndroidNotificationService {
    sealed class NotificationChannels(val channelId: Int, val importance: Int) {
        data object Events : NotificationChannels(1, 2)
        data object Errors : NotificationChannels(2, 4)
    }
}
