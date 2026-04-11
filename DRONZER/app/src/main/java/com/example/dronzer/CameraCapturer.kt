package com.example.dronzer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

object CameraCapturer {
    private const val TAG = "CameraCapturer"

    @SuppressLint("MissingPermission")
    fun captureImage(context: Context, facingFront: Boolean, onCapture: (ByteArray) -> Unit) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList.find { id ->
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facingFront) facing == CameraCharacteristics.LENS_FACING_FRONT
                else facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: run {
                Log.e(TAG, "No suitable camera found.")
                return
            }

            val handlerThread = HandlerThread("CameraBackground")
            handlerThread.start()
            val handler = Handler(handlerThread.looper)

            // Use a common resolution for compatibility
            val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                var cameraDevice: CameraDevice? = null

                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        imageReader.setOnImageAvailableListener({ reader ->
                            val image = reader.acquireLatestImage()
                            if (image != null) {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                image.close()
                                
                                onCapture(bytes)
                                
                                // Clean up after successful capture
                                cameraDevice?.close()
                                handlerThread.quitSafely()
                            }
                        }, handler)

                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        builder.addTarget(imageReader.surface)

                        // Set auto-focus and auto-exposure modes to help certain hardware
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

                        camera.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    session.capture(builder.build(), null, handler)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Session capture failed: ${e.message}")
                                    cameraDevice?.close()
                                    handlerThread.quitSafely()
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "Configuration failed")
                                cameraDevice?.close()
                                handlerThread.quitSafely()
                            }
                        }, handler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting up capture: ${e.message}")
                        cameraDevice?.close()
                        handlerThread.quitSafely()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    handlerThread.quitSafely()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    handlerThread.quitSafely()
                }
            }, handler)

        } catch (e: Exception) {
            Log.e(TAG, "Global capture failure: ${e.message}")
        }
    }
}
