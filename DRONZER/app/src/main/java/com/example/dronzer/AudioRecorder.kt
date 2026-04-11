package com.example.dronzer

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class AudioRecorder(
    private val context: Context,
    private val onMaxLimitReached: (File) -> Unit,
    private val onInterrupted: (File) -> Unit
) {
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false
    private var isStarted = false

    fun startRecording(): File? {
        if (isRecording) return null
        
        try {
            audioFile = File(context.cacheDir, "rec_audio_${System.currentTimeMillis()}.amr")
            
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                setOutputFile(audioFile?.absolutePath)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                
                setMaxFileSize(7 * 1024 * 1024)
                
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                        stopRecording()?.let { onMaxLimitReached(it) }
                    }
                }
                
                setOnErrorListener { _, what, extra ->
                    Log.e("AudioRecorder", "MediaRecorder error: $what, $extra")
                    stopRecording()?.let { onInterrupted(it) }
                }
                
                prepare()
                start()
            }
            
            isRecording = true
            isStarted = true
            return audioFile
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Start failed: ${e.message}")
            stopRecording()
            return null
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
            Log.e("AudioRecorder", "Stop error: ${e.message}")
        }
        mediaRecorder?.release()
        mediaRecorder = null
        isStarted = false
        
        val result = audioFile
        audioFile = null
        return if (result?.exists() == true && result.length() > 0) result else null
    }

    fun isRecording() = isRecording
}
