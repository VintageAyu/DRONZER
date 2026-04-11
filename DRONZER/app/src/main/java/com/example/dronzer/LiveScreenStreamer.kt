package com.example.dronzer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream

object LiveScreenStreamer {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var isStreaming = false
    private var projectionIntent: Intent? = null
    private var projectionResultCode: Int = 0

    fun setProjectionData(resultCode: Int, intent: Intent) {
        projectionResultCode = resultCode
        projectionIntent = intent
    }

    fun hasProjectionData(): Boolean {
        return projectionIntent != null
    }

    fun getProjection(context: Context): MediaProjection? {
        if (mediaProjection == null) {
            val data = (projectionIntent?.clone() as? Intent) ?: return null
            val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            try {
                mediaProjection = mpManager.getMediaProjection(projectionResultCode, data)
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.w("LiveScreenStreamer", "Projection stopped by user or system.")
                        stopProjection()
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.e("LiveScreen", "Failed to get projection: ${e.message}")
                projectionIntent = null
                return null
            }
        }
        return mediaProjection
    }

    @SuppressLint("WrongConstant")
    fun startStreaming(context: Context, onFrame: (ByteArray) -> Unit) {
        if (isStreaming) return

        stopStreaming()

        val projection = getProjection(context) ?: run {
            Log.e("LiveScreenStreamer", "Cannot start streaming, projection is null.")
            return
        }
        isStreaming = true

        try {
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels / 2
            val height = metrics.heightPixels / 2
            val density = metrics.densityDpi

            handlerThread = HandlerThread("ScreenStreamThread").apply { start() }
            val handler = Handler(handlerThread!!.looper)

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection.createVirtualDisplay(
                "LiveScreen", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, handler
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()

                    val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    val outputStream = ByteArrayOutputStream()
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)

                    if (isStreaming) onFrame(outputStream.toByteArray())
                } catch (e: Exception) {
                     // This can happen if the session is closed while a frame is processing
                }
            }, handler)

        } catch (e: Exception) {
            Log.e("LiveScreen", "Failed to start display: ${e.message}")
            stopStreaming()
        }
    }

    fun stopStreaming() {
        isStreaming = false
        try {
            virtualDisplay?.release()
            imageReader?.close()
            handlerThread?.quitSafely()
        } catch (e: Exception) {}
        virtualDisplay = null
        imageReader = null
        handlerThread = null
    }

    fun stopProjection() {
        stopStreaming()
        mediaProjection?.stop()
        mediaProjection = null
        projectionIntent = null
    }
}
