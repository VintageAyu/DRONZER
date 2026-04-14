package com.example.dronzer

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.*

object DeviceManager {
    private const val PREFS_NAME = "dronzer_device_config"
    private const val KEY_IS_TARGETED = "is_targeted"
    private const val KEY_UNIQUE_ID = "unique_device_id"
    
    var deviceId: String = "Unknown"
        private set

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var savedId = prefs.getString(KEY_UNIQUE_ID, null)
        
        if (savedId == null) {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: UUID.randomUUID().toString().take(8)
            val model = Build.MODEL.replace(" ", "_").take(8)
            savedId = "${model}_${androidId.takeLast(6)}"
            prefs.edit().putString(KEY_UNIQUE_ID, savedId).apply()
        }
        
        deviceId = savedId
    }

    fun isTargeted(context: Context): Boolean {
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
