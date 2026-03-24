package com.ipcamera

import android.content.Context
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Manages the lifecycle of the embedded HTTP + WebSocket servers.
 * Detects the device's WiFi IP and provides a connection URL for viewers.
 */
class EmbeddedServerManager(
    private val context: Context,
    private val token: String,
    private val httpPort: Int = 8080,
    private val wsPort: Int = 8081,
) {
    private val TAG = "EmbeddedServerMgr"

    private var httpServer: EmbeddedHttpServer? = null
    private var signalingServer: EmbeddedSignalingServer? = null

    /** Start both servers. Returns the viewer URL or null on failure. */
    fun start(): String? {
        return try {
            httpServer = EmbeddedHttpServer(context, httpPort).also { it.start() }
            signalingServer = EmbeddedSignalingServer(wsPort, token).also { it.start() }

            val ip = getWifiIpAddress()
            val url = if (ip != null) "http://$ip:$httpPort" else null
            Log.d(TAG, "Servers started — viewer URL: $url")
            url
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start embedded servers", e)
            stop()
            null
        }
    }

    /** Stop both servers gracefully. */
    fun stop() {
        try { httpServer?.stop() } catch (_: Exception) {}
        try { signalingServer?.stop() } catch (_: Exception) {}
        httpServer = null
        signalingServer = null
        Log.d(TAG, "Servers stopped")
    }

    /** Returns the device's non-loopback IPv4 address, or null. */
    private fun getWifiIpAddress(): String? {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get WiFi IP", e)
        }
        return null
    }

    /** The WebSocket port for the local signaling server. */
    fun getWsPort(): Int = wsPort
}
