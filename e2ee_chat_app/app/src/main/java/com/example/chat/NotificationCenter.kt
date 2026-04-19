package com.example.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.math.max

object NotificationCenter {
    const val CHANNEL_ID = "e2ee_chat_messages"
    private const val CALL_CHANNEL_ID = "e2ee_chat_calls"
    private const val MESSAGE_NOTIFICATION_ID = 1001
    private const val INCOMING_CALL_NOTIFICATION_ID = 1002

    fun ensureChannel(context: Context, importance: Int = NotificationManager.IMPORTANCE_HIGH) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "E2EE Chat", importance).apply {
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
        val callChannel = NotificationChannel(
            CALL_CHANNEL_ID,
            "E2EE Chat Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        manager.createNotificationChannel(callChannel)
    }

    fun showUnreadNotification(
        context: Context,
        unreadCount: Int,
        localeTag: String,
        titleOverride: String? = null,
        bodyOverride: String? = null,
    ) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            MESSAGE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = titleOverride?.takeIf { it.isNotBlank() } ?: "E2EE Chat"
        val body = bodyOverride?.takeIf { it.isNotBlank() } ?: defaultBody(localeTag, unreadCount)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setLargeIcon(loadAppIconBitmap(context))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setNumber(max(unreadCount, 0))
            .build()
        NotificationManagerCompat.from(context).notify(MESSAGE_NOTIFICATION_ID, notification)
    }

    fun clearAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    fun showIncomingCallNotification(
        context: Context,
        invite: PendingIncomingCallInvite,
        localeTag: String,
    ) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_incoming_call", true)
            putExtra("conversation_id", invite.conversationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            INCOMING_CALL_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (localeTag.startsWith("zh", ignoreCase = true)) "语音来电" else "Incoming call"
        val body = if (localeTag.startsWith("zh", ignoreCase = true)) {
            "${invite.peerUsername} 正在呼叫你"
        } else {
            "${invite.peerUsername} is calling you"
        }
        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setLargeIcon(loadAppIconBitmap(context))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        NotificationManagerCompat.from(context).notify(INCOMING_CALL_NOTIFICATION_ID, notification)
    }

    fun clearIncomingCallNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(INCOMING_CALL_NOTIFICATION_ID)
    }

    private fun defaultBody(localeTag: String, unreadCount: Int): String {
        val count = max(unreadCount, 1)
        return if (localeTag.startsWith("zh", ignoreCase = true)) {
            if (count <= 1) "\u6709\u65b0\u6d88\u606f" else "\u6709 $count \u6761\u65b0\u6d88\u606f"
        } else {
            if (count <= 1) "New message" else "$count new messages"
        }
    }

    private fun loadAppIconBitmap(context: Context): Bitmap? =
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(context.applicationInfo)
            when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                else -> {
                    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 192
                    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 192
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        val canvas = Canvas(bitmap)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                    }
                }
            }
        }.getOrNull()
}
