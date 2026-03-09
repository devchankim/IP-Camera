package com.ipcamera

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends push notifications through https://ntfy.sh (or a self-hosted ntfy instance).
 *
 * Usage:
 *   NtfyNotifier.send(topic = "my-baby-cam-secret", title = "👶 Motion detected", message = "...")
 *
 * iPhone setup: install the ntfy app, subscribe to the same topic.
 */
object NtfyNotifier {

    private val TAG = "NtfyNotifier"

    /**
     * @param topic   ntfy topic name — keep it unguessable (e.g. "babycam-xk83js")
     * @param title   Notification title shown on the device
     * @param message Notification body
     * @param priority "default" | "high" | "urgent"
     * @param baseUrl  ntfy server base URL (defaults to ntfy.sh)
     */
    fun send(
        topic: String,
        title: String,
        message: String,
        priority: String = "default",
        baseUrl: String = "https://ntfy.sh",
    ) {
        if (topic.isBlank()) return

        Thread {
            try {
                val url = URL("$baseUrl/$topic")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8_000
                conn.readTimeout    = 8_000
                conn.setRequestProperty("Title",    title)
                conn.setRequestProperty("Priority", priority)
                conn.setRequestProperty("Tags",     "baby,warning")
                conn.doOutput = true
                conn.outputStream.use { it.write(message.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                Log.d(TAG, "ntfy [$topic] → HTTP $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "ntfy send failed for topic=$topic", e)
            }
        }.apply { isDaemon = true }.start()
    }
}
