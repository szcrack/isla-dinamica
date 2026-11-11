package com.rayo.isladinamica

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.graphics.drawable.Drawable

/** Simple in-process bridge between the notification listener and the island service. */
object IslandBridge {

    data class Notif(
        val appIcon: Drawable?,
        val appName: String,
        val title: String,
        val text: String,
        /** Tap action of the original notification (opens the app). */
        val contentIntent: PendingIntent? = null,
        val packageName: String = "",
        /** StatusBarNotification key, to match removals to queued items. */
        val key: String = "",
        /** Direct-reply action of the original notification, if it has one. */
        val replyAction: Notification.Action? = null,
        val replyRemoteInput: RemoteInput? = null
    )

    var onNotification: ((Notif) -> Unit)? = null
    var onNotificationRemoved: ((String) -> Unit)? = null
}
