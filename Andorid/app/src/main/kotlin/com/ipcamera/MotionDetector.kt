package com.ipcamera

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import kotlin.math.abs

/**
 * Implements [VideoSink] to detect motion by comparing successive video frames.
 *
 * Algorithm:
 *  - Processes 1 in every 8 frames (~4 fps at 30 fps input) on a background thread.
 *  - Downsamples 4x for fast luma-only comparison.
 *  - Triggers [onMotionDetected] after [HOLD_FRAMES] consecutive high-diff frames.
 *  - Triggers [onMotionStopped] after [CALM_FRAMES] low-diff frames.
 */
class MotionDetector(
    private val onMotionDetected: () -> Unit,
    private val onMotionStopped: () -> Unit,
) : VideoSink {

    private val TAG = "MotionDetector"

    private val DIFF_THRESHOLD    = 30     // per-pixel luma diff to count as changed (0–255)
    private val MOTION_PIXEL_RATIO = 0.04  // 4 % of pixels changed → motion
    private val HOLD_FRAMES        = 3
    private val CALM_FRAMES        = 30

    private val processingThread = HandlerThread("MotionDetector").also { it.start() }
    private val handler = Handler(processingThread.looper)

    private var prevLuma: ByteArray? = null
    private var prevW = 0
    private var prevH = 0
    private var aboveCount = 0
    private var calmCount  = 0
    private var motionActive = false
    private var frameSkip  = 0

    @Volatile var enabled = true

    // ── VideoSink ────────────────────────────────────────────────────────────
    override fun onFrame(frame: VideoFrame) {
        if (!enabled) return
        frameSkip++
        if (frameSkip % 8 != 0) return   // ~4 fps
        frame.retain()
        handler.post {
            try { processFrame(frame) }
            finally { frame.release() }
        }
    }

    // ── Frame processing (background thread) ─────────────────────────────────
    private fun processFrame(frame: VideoFrame) {
        try {
            val i420   = frame.buffer.toI420()
            val srcW   = i420.width
            val srcH   = i420.height
            val stride = i420.strideY
            val dataY  = i420.dataY

            val dw = (srcW / 4).coerceAtLeast(1)
            val dh = (srcH / 4).coerceAtLeast(1)
            val luma = ByteArray(dw * dh)

            for (y in 0 until dh) {
                val srcRow = y * 4 * stride
                for (x in 0 until dw) {
                    luma[y * dw + x] = dataY.get(srcRow + x * 4)
                }
            }
            i420.release()

            val prev = prevLuma
            if (prev != null && prevW == dw && prevH == dh) {
                var diffCount = 0
                for (i in luma.indices) {
                    if (abs((luma[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF)) > DIFF_THRESHOLD)
                        diffCount++
                }
                val ratio = diffCount.toDouble() / luma.size

                if (ratio > MOTION_PIXEL_RATIO) {
                    aboveCount++; calmCount = 0
                    if (!motionActive && aboveCount >= HOLD_FRAMES) {
                        motionActive = true
                        onMotionDetected()
                    }
                } else {
                    calmCount++; aboveCount = 0
                    if (motionActive && calmCount >= CALM_FRAMES) {
                        motionActive = false
                        onMotionStopped()
                    }
                }
            }
            prevLuma = luma; prevW = dw; prevH = dh

        } catch (e: Exception) {
            Log.w(TAG, "processFrame error", e)
        }
    }

    fun reset() {
        prevLuma = null; prevW = 0; prevH = 0
        aboveCount = 0; calmCount = 0; motionActive = false
    }

    fun stop() {
        enabled = false
        processingThread.quit()
    }
}
