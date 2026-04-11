package com.example.dronzer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

class DronzerWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    override fun doWork(): Result {
        Log.d("DronzerWorker", "WorkManager heartbeat triggered, checking service...")
        
        val intent = Intent(applicationContext, DronzerService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
        } catch (e: Exception) {
            Log.e("DronzerWorker", "Failed to start service from worker: ${e.message}")
        }
        
        return Result.success()
    }
}
