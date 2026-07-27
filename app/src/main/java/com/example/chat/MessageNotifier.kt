package com.example.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat

object MessageNotifier {
    private const val CHANNEL_ID = "active_message_channel"

    fun sendNotification(
        context: Context,
        title: String,
        message: String,
        sessionId: String,
        session: ChatSession? = null
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 检查通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val notifySound = session?.notifySound ?: true
        val notifyVibrate = session?.notifyVibrate ?: true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = when {
                notifySound || notifyVibrate -> NotificationManager.IMPORTANCE_HIGH
                else -> NotificationManager.IMPORTANCE_LOW
            }
            val channel = NotificationChannel(CHANNEL_ID, "主动消息", importance).apply {
                description = "小鲸鱼主动发来的消息"
                if (notifyVibrate) {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 200, 300)
                } else {
                    enableVibration(false)
                }
                if (notifySound) {
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
                } else {
                    setSound(null, null)
                }
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("SESSION_ID", sessionId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),  // ← 用会话 ID 作为 requestCode
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val defaults = when {
            notifySound && notifyVibrate -> NotificationCompat.DEFAULT_ALL
            notifySound -> NotificationCompat.DEFAULT_SOUND
            notifyVibrate -> NotificationCompat.DEFAULT_VIBRATE
            else -> 0
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("🐳 $title")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(if (notifySound || notifyVibrate) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setDefaults(defaults)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationId = (sessionId + "_" + System.currentTimeMillis()).hashCode()
    }
}