package com.example.dronzer

import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

class NotificationMonitor : NotificationListenerService() {

    companion object {
        private val notificationHistory = mutableListOf<String>()
        private var instance: NotificationMonitor? = null

        fun getNotifications(): String {
            val sb = StringBuilder()
            
            // 1. Get Active (Real-time) Notifications
            sb.append("\u001b[1;32m--- ACTIVE NOTIFICATIONS ---\u001b[0m\n")
            val active = instance?.activeNotifications
            if (active != null && active.isNotEmpty()) {
                active.forEach { sbn ->
                    val packageName = sbn.packageName
                    val extras = sbn.notification.extras
                    val title = extras.getString("android.title") ?: "No Title"
                    val text = extras.getCharSequence("android.text")?.toString() ?: "No Content"
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(sbn.postTime))
                    sb.append("[$time] [$packageName] $title: $text\n")
                }
            } else {
                sb.append("(No active notifications found - is the listener connected?)\n")
            }

            sb.append("\n\u001b[1;35m--- NOTIFICATION HISTORY (Last 50) ---\u001b[0m\n")
            synchronized(notificationHistory) {
                if (notificationHistory.isEmpty()) {
                    sb.append("(History is empty)\n")
                } else {
                    notificationHistory.forEach { sb.append("$it\n") }
                }
            }
            
            return sb.toString()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d("NotificationMonitor", "Notification Listener Connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.d("NotificationMonitor", "Notification Listener Disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val packageName = sbn.packageName
            // Filter out own notifications if necessary
            if (packageName == "com.example.dronzer") return

            val extras = sbn.notification.extras
            val title = extras.getString("android.title") ?: "No Title"
            val text = extras.getCharSequence("android.text")?.toString() ?: "No Content"
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(sbn.postTime))

            val logEntry = "[$time] [$packageName] $title: $text"
            
            synchronized(notificationHistory) {
                notificationHistory.add(0, logEntry)
                if (notificationHistory.size > 50) {
                    notificationHistory.removeAt(notificationHistory.size - 1)
                }
            }
            Log.d("NotificationMonitor", "Logged: $logEntry")
        } catch (e: Exception) {
            Log.e("NotificationMonitor", "Error: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        // If our main service notification is removed, bring it back!
        if (sbn.packageName == packageName && sbn.id == DronzerService.NOTIFICATION_ID) {
            Log.d("NotificationMonitor", "DronzerService notification was removed. Restarting service!")
            val intent = Intent(applicationContext, DronzerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
        }
    }
}
