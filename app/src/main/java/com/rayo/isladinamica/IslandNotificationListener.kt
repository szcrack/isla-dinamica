package com.rayo.isladinamica

import android.app.Notification
import android.content.pm.PackageManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class IslandNotificationListener : NotificationListenerService() {

    // Duplicate filter (guide §6, v1.11): WhatsApp posts several
    // notifications per message (message, group summary, updates), and
    // some apps re-post the same one. Same key + text within 2 s is a
    // repeat. Group summaries are never shown. Old entries are pruned
    // when the map grows, so it cannot leak.
    private val vistas = HashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Skip our own foreground-service notification.
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        // Group summaries (e.g. WhatsApp's "3 mensajes nuevos") are
        // duplicates of the individual messages; never show them.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // Ongoing / foreground-service notifications (e.g. music players) are
        // handled through MediaSession, not through this path.
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val clave = sbn.key + text
        val ahora = SystemClock.elapsedRealtime()
        if (ahora - (vistas[clave] ?: 0L) < 2000) return // repetida
        vistas[clave] = ahora
        if (vistas.size > 100) {
            val limite = ahora - 10_000L
            vistas.entries.removeIf { it.value < limite }
        }

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
