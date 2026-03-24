package com.ipcamera

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded WebRTC signaling server that runs directly on the Android device.
 *
 * Ported from VideoServer/SignalingWebSocketServer.kt with Jackson replaced by org.json.
 * Protocol is identical: join/offer/answer/ice with token auth and one-camera-one-viewer rooms.
 */
class EmbeddedSignalingServer(
    private val port: Int = 8081,
    private val requiredToken: String,
) {
    private val TAG = "EmbeddedSignaling"

    private data class ClientState(
        var room: String? = null,
        var role: String? = null,
    )

    private data class RoomState(
        @Volatile var camera: WebSocket? = null,
        @Volatile var viewer: WebSocket? = null,
    )

    private val clients = ConcurrentHashMap<WebSocket, ClientState>()
    private val rooms = ConcurrentHashMap<String, RoomState>()

    private val server = object : WebSocketServer(InetSocketAddress(port)) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            clients[conn] = ClientState()
            Log.d(TAG, "Connection opened: ${conn.remoteSocketAddress}")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            val state = clients.remove(conn)
            if (state?.room != null && state.role != null) {
                val roomId = state.room!!
                val room = rooms[roomId]
                if (room != null) {
                    val role = state.role!!
                    val peer = if (role == "camera") room.viewer else room.camera
                    if (role == "camera" && room.camera == conn) room.camera = null
                    if (role == "viewer" && room.viewer == conn) room.viewer = null
                    peer?.send(JSONObject().apply {
                        put("type", "peer_left")
                        put("room", roomId)
                        put("role", role)
                    }.toString())
                    Log.d(TAG, "$role left room=$roomId")

                    if (room.camera == null && room.viewer == null) {
                        rooms.remove(roomId)
                    }
                }
            }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val json = try {
                JSONObject(message)
            } catch (e: Exception) {
                sendError(conn, "Invalid JSON")
                return
            }

            val type = json.optString("type", "").ifEmpty {
                sendError(conn, "Missing field: type")
                return
            }

            when (type) {
                "join" -> handleJoin(conn, json)
                "offer", "answer", "ice" -> relayToPeer(conn, type, json)
                else -> sendError(conn, "Unknown type: $type")
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            Log.e(TAG, "Error: ${ex.message}", ex)
        }

        override fun onStart() {
            Log.d(TAG, "Started on ws://0.0.0.0:$port")
        }
    }

    fun start() {
        server.setReuseAddr(true)
        server.start()
    }

    fun stop() {
        try {
            server.stop(500)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
        clients.clear()
        rooms.clear()
    }

    private fun handleJoin(conn: WebSocket, json: JSONObject) {
        val token = json.optString("token", "")
        if (token != requiredToken) {
            sendError(conn, "Unauthorized")
            conn.close()
            return
        }

        val roomId = json.optString("room", "").ifEmpty {
            sendError(conn, "Missing field: room")
            return
        }
        val role = json.optString("role", "").ifEmpty {
            sendError(conn, "Missing field: role")
            return
        }
        if (role != "camera" && role != "viewer") {
            sendError(conn, "Invalid role: $role")
            return
        }

        val room = rooms.computeIfAbsent(roomId) { RoomState() }

        val prev = clients[conn] ?: ClientState().also { clients[conn] = it }
        prev.room = roomId
        prev.role = role

        // Enforce one-per-role
        val replaced: WebSocket? = when (role) {
            "camera" -> { val old = room.camera; room.camera = conn; old }
            else -> { val old = room.viewer; room.viewer = conn; old }
        }
        if (replaced != null && replaced != conn) {
            sendError(replaced, "Replaced by a new $role connection")
            replaced.close()
        }

        conn.send(JSONObject().apply {
            put("type", "joined")
            put("room", roomId)
            put("role", role)
        }.toString())

        val peer = if (role == "camera") room.viewer else room.camera
        if (peer != null) {
            peer.send(JSONObject().apply {
                put("type", "peer_joined")
                put("room", roomId)
                put("role", role)
            }.toString())
            conn.send(JSONObject().apply {
                put("type", "peer_joined")
                put("room", roomId)
                put("role", if (role == "camera") "viewer" else "camera")
            }.toString())
        }

        Log.d(TAG, "Joined room=$roomId role=$role")
    }

    private fun relayToPeer(conn: WebSocket, type: String, json: JSONObject) {
        val state = clients[conn]
        val roomId = state?.room
        val role = state?.role
        if (roomId == null || role == null) {
            sendError(conn, "Not joined")
            return
        }

        val room = rooms[roomId] ?: run {
            sendError(conn, "Unknown room")
            return
        }

        val peer = if (role == "camera") room.viewer else room.camera
        if (peer == null) {
            sendError(conn, "Peer not connected")
            return
        }

        val payload = JSONObject().apply {
            put("type", type)
            put("room", roomId)
            put("from", role)
            when (type) {
                "offer", "answer" -> put("sdp", json.optString("sdp", ""))
                "ice" -> put("candidate", json.optJSONObject("candidate"))
            }
        }

        peer.send(payload.toString())
    }

    private fun sendError(conn: WebSocket, message: String) {
        try {
            conn.send(JSONObject().apply {
                put("type", "error")
                put("message", message)
            }.toString())
        } catch (_: Exception) {}
    }
}
