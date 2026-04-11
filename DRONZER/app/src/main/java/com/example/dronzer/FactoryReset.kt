package com.example.dronzer

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

object FactoryReset {
    fun wipeData(context: Context) {
        val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, DronzerAdminReceiver::class.java)

        if (devicePolicyManager.isAdminActive(adminComponent)) {
            try {
                Log.d("FactoryReset", "Wiping data...")
                devicePolicyManager.wipeData(0)
            } catch (e: SecurityException) {
                Log.e("FactoryReset", "SecurityException: ${e.message}")
            }
        } else {
            Log.e("FactoryReset", "Admin not active")
        }
    }

    fun isAdminActive(context: Context): Boolean {
        val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, DronzerAdminReceiver::class.java)
        return devicePolicyManager.isAdminActive(adminComponent)
    }
}