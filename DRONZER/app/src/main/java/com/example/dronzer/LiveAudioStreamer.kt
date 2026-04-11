package com.example.dronzer

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*

class LiveAudioStreamer(private val onAudioFrame: (ByteArray) -> Unit) {
    private var audioRecord: AudioRecord? = null
    private var isStreaming = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    @SuppressLint("MissingPermission")
    fun startStreaming() {
        if (isStreaming) return
        isStreaming = true

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("LiveAudioStreamer", "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            job = scope.launch {
                val buffer = ByteArray(bufferSize)
                while (isActive && isStreaming) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        onAudioFrame(buffer.copyOfRange(0, read))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LiveAudioStreamer", "Error: ${e.message}")
            stopStreaming()
        }
    }

    fun stopStreaming() {
        isStreaming = false
        job?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
    }
}
