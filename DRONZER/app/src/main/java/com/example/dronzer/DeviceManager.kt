package com.example.dronzer

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.*

object DeviceManager {
    private const val PREFS_NAME = "dronzer_device_config"
    private const val KEY_IS_TARGETED = "is_targeted"
    
    var deviceId: String = "Unknown"
        private set

    fun initialize(context: Context) {
        deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "Unknown"
    }

    fun isTargeted(context: Context): Boolean {
        // Default to false so commands are locked until 'select' or 'all' is used
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_TARGETED, false)
    }

    fun setTargeted(context: Context, targeted: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_TARGETED, targeted)
            .apply()
    }

    fun getDeviceInfo(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return "📱 **Device:** `${Build.MODEL}`\n🆔 **ID:** `$deviceId`\n🕒 **Last Seen:** `$timestamp`"
    }
}
