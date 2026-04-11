package com.example.dronzer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log

class UltraCamMode(private val context: Context, private val onFrame: (String, ByteArray) -> Unit) {
    private val activeCameras = mutableMapOf<String, CameraInstance>()
    private var isStreaming = false

    private class CameraInstance(
        var cameraDevice: CameraDevice? = null,
        var captureSession: CameraCaptureSession? = null,
        var imageReader: ImageReader? = null,
        var handlerThread: HandlerThread? = null,
        var facing: String = "UNKNOWN"
    )

    @SuppressLint("MissingPermission")
    fun startMonsterMode() {
        if (isStreaming) return
        isStreaming = true

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraIds = manager.cameraIdList
            
            // Prioritize one front and one back first, then others
            val frontIds = mutableListOf<String>()
            val backIds = mutableListOf<String>()
            val otherIds = mutableListOf<String>()

            for (id in cameraIds) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> frontIds.add(id)
                    CameraCharacteristics.LENS_FACING_BACK -> backIds.add(id)
                    else -> otherIds.add(id)
                }
            }

            // We try to open them with a small delay to avoid ISP overload
            val handler = Handler(Looper.getMainLooper())
            var delay = 0L

            // Try all front
            frontIds.forEach { id ->
                handler.postDelayed({ if (isStreaming) startSingleCamera(manager, id, "FRONT") }, delay)
                delay += 800
            }
            // Try all back
            backIds.forEach { id ->
                handler.postDelayed({ if (isStreaming) startSingleCamera(manager, id, "BACK") }, delay)
                delay += 800
            }
            // Try others
            otherIds.forEach { id ->
                handler.postDelayed({ if (isStreaming) startSingleCamera(manager, id, "EXT") }, delay)
                delay += 800
            }

        } catch (e: Exception) {
            Log.e("UltraCam", "Failed to start Monster Mode: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSingleCamera(manager: CameraManager, cameraId: String, facingLabel: String) {
        try {
            val thread = HandlerThread("CamThread_$cameraId").apply { start() }
            val handler = Handler(thread.looper)
            
            // Lower resolution and quality to reduce ISP load when multiple cams are open
            val reader = ImageReader.newInstance(320, 240, ImageFormat.JPEG, 2)
            
            val instance = CameraInstance(imageReader = reader, handlerThread = thread, facing = facingLabel)
            activeCameras[cameraId] = instance

            reader.setOnImageAvailableListener({ r ->
                try {
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()
                    if (isStreaming) {
                        // Include facing info in the ID for the host
                        onFrame("${facingLabel}_$cameraId", bytes)
                    }
                } catch (e: Exception) {}
            }, handler)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    instance.cameraDevice = camera
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    builder.addTarget(reader.surface)
                    
                    // Reduce frame rate to save bandwidth and ISP resources
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(10, 15))

                    camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            instance.captureSession = session
                            try {
                                session.setRepeatingRequest(builder.build(), null, handler)
                            } catch (e: Exception) {
                                Log.e("UltraCam", "Repeat error $cameraId: ${e.message}")
                            }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            Log.e("UltraCam", "Configure failed for $cameraId")
                        }
                    }, handler)
                }
                override fun onDisconnected(camera: CameraDevice) { stopCamera(cameraId) }
                override fun onError(camera: CameraDevice, error: Int) { 
                    Log.e("UltraCam", "Error on cam $cameraId: $error")
                    stopCamera(cameraId) 
                }
            }, handler)

        } catch (e: Exception) {
            Log.e("UltraCam", "Error starting cam $cameraId: ${e.message}")
        }
    }

    fun stopMonsterMode() {
        isStreaming = false
        val ids = activeCameras.keys.toList()
        for (id in ids) {
            stopCamera(id)
        }
        activeCameras.clear()
    }

    private fun stopCamera(cameraId: String) {
        activeCameras[cameraId]?.let {
            try {
                it.captureSession?.stopRepeating()
                it.captureSession?.close()
                it.cameraDevice?.close()
                it.imageReader?.close()
                it.handlerThread?.quitSafely()
            } catch (e: Exception) {}
        }
        activeCameras.remove(cameraId)
    }
}
