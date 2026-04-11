package com.example.dronzer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

class ScreenRecorder(
    private val context: Context,
    private val onMaxLimitReached: (File) -> Unit,
    private val onInterrupted: (File) -> Unit
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var videoFile: File? = null
    private var isRecording = false
    private var isStarted = false
    private var projection: MediaProjection? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.w("ScreenRecorder", "Screen projection stopped by system")
            stopRecording()?.let { onInterrupted(it) }
        }
    }

    @SuppressLint("WrongConstant")
    fun startRecording(projection: MediaProjection): File? {
        if (isRecording) return null
        this.projection = projection

        try {
            val metrics = context.resources.displayMetrics
            val width = 1280
            val height = 720
            val density = metrics.densityDpi

            handlerThread = HandlerThread("ScreenRecorderThread").apply { start() }
            handler = Handler(handlerThread!!.looper)

            videoFile = File(context.cacheDir, "rec_screen_${System.currentTimeMillis()}.mp4")
            
            setupMediaRecorder()

            val surface = mediaRecorder?.surface ?: return null
            virtualDisplay = projection.createVirtualDisplay(
                "ScreenRecorder", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, handler
            )

            projection.registerCallback(projectionCallback, handler)
            mediaRecorder?.start()
            isRecording = true
            isStarted = true
            return videoFile
        } catch (e: Exception) {
            Log.e("ScreenRecorder", "Start failed: ${e.message}")
            stopRecording() // Clean up
            return null
        }
    }

    private fun setupMediaRecorder() {
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(videoFile?.absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            
            setVideoEncodingBitRate(5_000_000)
            setVideoFrameRate(60)
            setVideoSize(1280, 720)
            
            setMaxFileSize(7 * 1024 * 1024)
            
            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    stopRecording()?.let { onMaxLimitReached(it) }
                }
            }
            setOnErrorListener { _, what, extra ->
                 Log.e("ScreenRecorder", "MediaRecorder Error: what=$what, extra=$extra")
                 stopRecording()?.let { onInterrupted(it) }
            }
            
            prepare()
        }
    }

    fun takeSnapshot(onCapture: (ByteArray) -> Unit) {
        // Implementation remains the same
    }

    fun stopRecording(): File? {
        if (!isRecording && !isStarted) return null
        isRecording = false
        
        projection?.unregisterCallback(projectionCallback)

        try {
            if (isStarted) {
                mediaRecorder?.stop()
            }
        } catch (e: Exception) {
            Log.e("ScreenRecorder", "Stop error: ${e.message}")
        }
        mediaRecorder?.release()
        mediaRecorder = null
        virtualDisplay?.release()
        virtualDisplay = null
        handlerThread?.quitSafely()
        handlerThread = null
        isStarted = false

        val resultFile = videoFile
        videoFile = null
        return if (resultFile?.exists() == true && resultFile.length() > 0) resultFile else null
    }

    fun isRecording() = isRecording
}
