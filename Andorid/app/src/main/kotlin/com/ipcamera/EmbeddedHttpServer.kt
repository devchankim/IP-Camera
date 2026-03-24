package com.ipcamera

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD

/**
 * Lightweight HTTP server that serves the WebRTC viewer page from Android assets.
 * Replaces the Mac Mini's StaticFileServer.
 */
class EmbeddedHttpServer(
    private val context: Context,
    port: Int = 8080,
) : NanoHTTPD(port) {

    private val TAG = "EmbeddedHttp"

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimStart('/')
        val assetName = if (uri.isEmpty() || uri == "index.html") "webrtc_viewer.html" else uri

        if (assetName == "health") {
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"status":"ok"}"""
            )
        }

        return try {
            val stream = context.assets.open(assetName)
            val bytes = stream.readBytes()
            stream.close()

            val contentType = when {
                assetName.endsWith(".html") -> "text/html; charset=utf-8"
                assetName.endsWith(".js") -> "application/javascript"
                assetName.endsWith(".css") -> "text/css"
                assetName.endsWith(".svg") -> "image/svg+xml"
                else -> "application/octet-stream"
            }

            newFixedLengthResponse(Response.Status.OK, contentType, String(bytes)).apply {
                addHeader("Cache-Control", "no-cache")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Asset not found: $assetName")
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }
}
