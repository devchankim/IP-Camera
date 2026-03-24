package com.ipcamera

import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import java.nio.ByteBuffer

/**
 * A [CapturerObserver] wrapper that boosts luma (Y channel) of each video frame
 * to improve visibility in dark environments (e.g. nighttime baby monitoring).
 *
 * Intercepts frames before they reach the WebRTC encoder, so both the local
 * preview and the remote viewer see the brightened image.
 *
 * Uses a pre-computed LUT for fast per-pixel mapping with no per-frame division.
 *
 * @param downstream  the real CapturerObserver from VideoSource.capturerObserver
 * @param gain        brightness multiplier (1.0 = no change, 3.0 = 3× brighter)
 */
class NightModeProcessor(
    private val downstream: CapturerObserver,
    val gain: Float = 3.0f,
) : CapturerObserver {

    /** Pre-computed lookup table: newY = lut[oldY] */
    private val lut = ByteArray(256) { i -> (i * gain).toInt().coerceIn(0, 255).toByte() }

    override fun onCapturerStarted(success: Boolean) = downstream.onCapturerStarted(success)
    override fun onCapturerStopped() = downstream.onCapturerStopped()

    override fun onFrameCaptured(frame: VideoFrame) {
        val i420 = frame.buffer.toI420()
        if (i420 == null) {
            // toI420() failed — pass frame through unchanged
            downstream.onFrameCaptured(frame)
            return
        }
        try {
            val w       = i420.width
            val h       = i420.height
            val strideY = i420.strideY
            val strideU = i420.strideU
            val strideV = i420.strideV
            val uvH     = (h + 1) / 2

            // Boost Y (luma) channel using LUT, write into a new direct ByteBuffer
            val srcY = i420.dataY
            val newY = ByteBuffer.allocateDirect(strideY * h)
            for (row in 0 until h) {
                val off = row * strideY
                for (col in 0 until w) {
                    newY.put(off + col, lut[srcY.get(off + col).toInt() and 0xFF])
                }
            }

            // Copy U and V planes unchanged (chroma is not affected by luma gain)
            val newU = ByteBuffer.allocateDirect(strideU * uvH).also {
                it.put(i420.dataU.duplicate())
                it.rewind()
            }
            val newV = ByteBuffer.allocateDirect(strideV * uvH).also {
                it.put(i420.dataV.duplicate())
                it.rewind()
            }

            // Safe to release the original I420 — we have our own copies now
            i420.release()

            val boostedBuffer = JavaI420Buffer.wrap(
                w, h,
                newY, strideY,
                newU, strideU,
                newV, strideV,
            ) { /* newY/U/V are plain Java ByteBuffers — GC'd normally */ }

            downstream.onFrameCaptured(VideoFrame(boostedBuffer, frame.rotation, frame.timestampNs))
            boostedBuffer.release()

        } catch (e: Exception) {
            // On any error, release i420 and forward the original frame
            try { i420.release() } catch (_: Exception) {}
            downstream.onFrameCaptured(frame)
        }
    }
}
