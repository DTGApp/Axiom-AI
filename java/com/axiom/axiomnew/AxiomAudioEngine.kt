/*
 * Axiom — On-Device AI Assistant for Android
 * Copyright (C) 2024 Rayad
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
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