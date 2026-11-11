package com.rayo.isladinamica

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class IslandNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Skip our own foreground-service notification.
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        // Ongoing / foreground-service notifications (e.g. music players) are
        // handled through MediaSession, not through this path.
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val icon = try {
            packageManager.getApplicationIcon(sbn.packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val appName = try {
            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            sbn.packageName
        }
        // Direct-reply action (e.g. WhatsApp "Responder"): the action whose
        // RemoteInput allows free-form text. Kept in memory only.
        val replyAction = n.actions?.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        val replyRemoteInput =
            replyAction?.remoteInputs?.firstOrNull { it.allowFreeFormInput }
        IslandBridge.onNotification?.invoke(
            IslandBridge.Notif(
                appIcon = icon,
                appName = appName,
                title = title,
                text = text,
                contentIntent = n.contentIntent,
                packageName = sbn.packageName,
                key = sbn.key,
                replyAction = replyAction,
                replyRemoteInput = replyRemoteInput
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        IslandBridge.onNotificationRemoved?.invoke(sbn.key)
    }
}
