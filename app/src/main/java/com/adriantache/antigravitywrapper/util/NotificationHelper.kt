package com.adriantache.antigravitywrapper.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.adriantache.antigravitywrapper.MainActivity
import com.adriantache.antigravitywrapper.R

object NotificationHelper {
    const val CHANNEL_ID = "antigravity_notifications"
    private const val CHANNEL_NAME = "Antigravity Alerts"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications and alerts from Antigravity remote sessions"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showNotification(
        context: Context,
        title: String,
        body: String,
        targetUrl: String?
    ) {
        createNotificationChannel(context)

        val normalizedUrl = if (!targetUrl.isNullOrBlank()) normalizeUrl(targetUrl) else "https://antigravity.google"

        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(normalizedUrl)
            putExtra("url", normalizedUrl)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationTitle = title.ifBlank { "Antigravity" }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(notificationTitle)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    fun normalizeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("antigravity://", ignoreCase = true) -> {
                val path = trimmed.substring("antigravity://".length).removePrefix("/")
                "https://antigravity.google/$path"
            }
            trimmed.startsWith("/") -> "https://antigravity.google$trimmed"
            else -> "https://antigravity.google/$trimmed"
        }
    }

    fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null

        val dataStr = intent.dataString
        if (!dataStr.isNullOrBlank()) {
            return normalizeUrl(dataStr)
        }

        val extras = intent.extras
        if (extras != null) {
            val candidateKeys = listOf("url", "link", "target_url", "uri", "destination", "click_action")
            for (key in candidateKeys) {
                val value = extras.getString(key)
                if (!value.isNullOrBlank()) {
                    return normalizeUrl(value)
                }
            }
        }

        return null
    }
}
