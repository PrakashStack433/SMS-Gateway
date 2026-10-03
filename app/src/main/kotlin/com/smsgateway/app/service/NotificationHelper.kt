package com.smsgateway.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.smsgateway.app.R
import com.smsgateway.app.ui.MainActivity

object NotificationHelper {
    const val CHANNEL_SERVICE = "sms_gateway"
    const val CHANNEL_EVENTS = "sms_events"
    const val ID_SERVICE = 1001
    private const val ID_TIMEOUT = 1002
    private const val ID_EVENT_BASE = 2000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "SMS Gateway", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the SMS gateway HTTP server running"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "SMS Activity", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Status of individual SMS messages"
            }
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun serviceNotification(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SMS Gateway running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .build()

    fun updateService(context: Context, text: String) = post(context, ID_SERVICE, serviceNotification(context, text))

    fun notifyEvent(context: Context, smsId: Long, status: String, phone: String) {
        val masked = "…" + phone.takeLast(4) // never put the full number on the lock screen
        val n = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SMS #$smsId $status")
            .setContentText("To $masked")
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        post(context, ID_EVENT_BASE + (smsId % 1000).toInt(), n)
    }

    fun notifyTimeout(context: Context) {
        val n = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SMS Gateway stopped")
            .setContentText("Android limits data-sync services to ~6 h. Tap to restart.")
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        post(context, ID_TIMEOUT, n)
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
