package com.axiom.axiomnew

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*

class AxiomAudioEngine(
    private val onBufferReady: (ShortArray) -> Unit
) {
    private var isRecording = false
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    @SuppressLint("MissingPermission")
    fun start() {
        isRecording = true
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        CoroutineScope(Dispatchers.IO).launch {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufSize.coerceAtLeast(6400)
            )

            recorder.startRecording()
            // 1280 samples = 80ms of audio at 16kHz
            val buffer = ShortArray(1280)

            while (isRecording) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    // Send only the actual read data to JNI
                    onBufferReady(buffer.copyOfRange(0, read))
                }
            }
            recorder.stop()
            recorder.release()
        }
    }

    fun stop() {
        isRecording = false
    }
}