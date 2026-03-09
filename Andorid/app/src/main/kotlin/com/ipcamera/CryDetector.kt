package com.ipcamera

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Monitors microphone audio level in a background thread.
 * When sustained loud sound (e.g. crying) is detected, calls [onCryDetected].
 * When silence is restored, calls [onSilenceRestored].
 *
 * Note: Creates a secondary AudioRecord (MIC source) alongside WebRTC's own capture.
 * On devices that don't support concurrent capture the AudioRecord will fail to
 * initialize and cry-detection is silently skipped.
 */
class CryDetector(
    private val onCryDetected: () -> Unit,
    private val onSilenceRestored: () -> Unit,
) {
    private val TAG = "CryDetector"

    // ~30 % of full-scale PCM16 amplitude → crying level
    private val AMPLITUDE_THRESHOLD = 9_000
    // Number of consecutive loud frames before triggering
    private val HOLD_FRAMES = 6
    // Number of consecutive quiet frames before "silence restored"
    private val CALM_FRAMES = 25

    private val sampleRate = 8_000
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    ).coerceAtLeast(1024)

    @Volatile private var running = false
    private var detectorThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        detectorThread = Thread {
            try {
                val ar = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
                if (ar.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioRecord not initialized — cry detection unavailable on this device")
                    return@Thread
                }
                ar.startRecording()

                val buf = ShortArray(bufferSize / 2)
                var loudCount = 0
                var calmCount = 0
                var crying = false

                while (running) {
                    val read = ar.read(buf, 0, buf.size)
                    if (read <= 0) continue

                    val maxAmp = buf.take(read).maxOf { kotlin.math.abs(it.toInt()) }

                    if (maxAmp > AMPLITUDE_THRESHOLD) {
                        loudCount++; calmCount = 0
                        if (!crying && loudCount >= HOLD_FRAMES) {
                            crying = true
                            onCryDetected()
                        }
                    } else {
                        calmCount++; loudCount = 0
                        if (crying && calmCount >= CALM_FRAMES) {
                            crying = false
                            onSilenceRestored()
                        }
                    }
                }

                ar.stop()
                ar.release()
            } catch (e: Exception) {
                Log.e(TAG, "CryDetector thread error", e)
            }
        }.apply {
            name = "CryDetectorThread"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        detectorThread?.interrupt()
        detectorThread = null
    }
}
