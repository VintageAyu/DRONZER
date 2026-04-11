package com.example.dronzer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream

object ScreenshotCapturer {

    @SuppressLint("WrongConstant")
    fun captureScreen(context: Context, onCapture: (ByteArray) -> Unit) {
        try {
            // Reuse the existing projection from LiveScreenStreamer to avoid conflicts
            val projection = LiveScreenStreamer.getProjection(context) ?: return
            
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val handlerThread = HandlerThread("ScreenshotThread").apply { start() }
            val handler = Handler(handlerThread.looper)

            // Using a slightly lower resolution or specific format can sometimes help stability
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            
            val virtualDisplay = projection.createVirtualDisplay(
                "Screenshot", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, handler
            )

            imageReader.setOnImageAvailableListener({ reader ->
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
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                    
                    onCapture(outputStream.toByteArray())

                    // Clean up resources but DON'T stop the projection (it's shared)
                    imageReader.setOnImageAvailableListener(null, null)
                    virtualDisplay?.release()
                    imageReader.close()
                    handlerThread.quitSafely()
                } catch (e: Exception) {
                    Log.e("Screenshot", "Error: ${e.message}")
                }
            }, handler)

        } catch (e: Exception) {
            Log.e("Screenshot", "Failed: ${e.message}")
        }
    }
}
