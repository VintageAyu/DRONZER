package com.example.dronzer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

class LiveCameraStreamer(private val context: Context, private val onFrame: (ByteArray) -> Unit) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var isStreaming = false

    @SuppressLint("MissingPermission")
    fun startStreaming() {
        if (isStreaming) return
        isStreaming = true

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList.find { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: return

            handlerThread = HandlerThread("CameraStreamThread").apply { start() }
            handler = Handler(handlerThread!!.looper)

            // Low resolution for faster streaming over network
            imageReader = ImageReader.newInstance(320, 240, ImageFormat.JPEG, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                if (isStreaming) onFrame(bytes)
            }, handler)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    builder.addTarget(imageReader!!.surface)
                    camera.createCaptureSession(listOf(imageReader!!.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            try {
                                session.setRepeatingRequest(builder.build(), null, handler)
                            } catch (e: Exception) {
                                Log.e("LiveStream", "Error: ${e.message}")
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    }, handler)
                }
                override fun onDisconnected(camera: CameraDevice) { stopStreaming() }
                override fun onError(camera: CameraDevice, error: Int) { stopStreaming() }
            }, handler)

        } catch (e: Exception) {
            Log.e("LiveStream", "Failed to start: ${e.message}")
            stopStreaming()
        }
    }

    fun stopStreaming() {
        isStreaming = false
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            cameraDevice?.close()
            imageReader?.close()
            handlerThread?.quitSafely()
        } catch (e: Exception) {}
        captureSession = null
        cameraDevice = null
        imageReader = null
        handlerThread = null
        handler = null
    }
}
