package com.ipcamera

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Embedded WebRTC signaling server — supports 1 camera + N viewers per room.
 *
 * Each client gets a unique clientId on join. Messages are routed via "to" field.
 * Camera creates a separate PeerConnection (offer/answer/ICE) for each viewer.
 */
class EmbeddedSignalingServer(
    private val port: Int = 8081,
    private val requiredToken: String,
) {
    private val TAG = "EmbeddedSignaling"
    private val clientIdCounter = AtomicInteger(0)

    private data class ClientState(
        val clientId: String,
        var room: String? = null,
        var role: String? = null,
    )

    private data class RoomState(
        @Volatile var camera: WebSocket? = null,
        @Volatile var cameraClientId: String? = null,
        val viewers: ConcurrentHashMap<String, WebSocket> = ConcurrentHashMap(), // clientId → WS
    )

    private val clients = ConcurrentHashMap<WebSocket, ClientState>()
    private val rooms = ConcurrentHashMap<String, RoomState>()

    private val server = object : WebSocketServer(InetSocketAddress(port)) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val clientId = "c${clientIdCounter.incrementAndGet()}"
            clients[conn] = ClientState(clientId)
            Log.d(TAG, "Connection opened: $clientId ${conn.remoteSocketAddress}")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            val state = clients.remove(conn) ?: return
            val roomId = state.room ?: return
            val room = rooms[roomId] ?: return
            val role = state.role ?: return

            when (role) {
                "camera" -> {
                    if (room.camera == conn) {
                        room.camera = null
                        room.cameraClientId = null
                    }
                    // Notify all viewers that camera left
                    for ((vid, vws) in room.viewers) {
                        try {
                            vws.send(JSONObject().apply {
                                put("type", "peer_left")
                                put("room", roomId)
                                put("role", "camera")
                            }.toString())
                        } catch (_: Exception) {}
                    }
                }
                "viewer" -> {
                    room.viewers.remove(state.clientId)
                    // Notify camera that this viewer left
                    room.camera?.let { cam ->
                        try {
                            cam.send(JSONObject().apply {
                                put("type", "peer_left")
                                put("room", roomId)
                                put("role", "viewer")
                                put("clientId", state.clientId)
                            }.toString())
                        } catch (_: Exception) {}
                    }
                }
            }

            Log.d(TAG, "${role} ${state.clientId} left room=$roomId")

            if (room.camera == null && room.viewers.isEmpty()) {
                rooms.remove(roomId)
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
        val state = clients[conn] ?: return
        state.room = roomId
        state.role = role

        when (role) {
            "camera" -> {
                // Replace old camera if any
                val oldCam = room.camera
                room.camera = conn
                room.cameraClientId = state.clientId
                if (oldCam != null && oldCam != conn) {
                    sendError(oldCam, "Replaced by a new camera connection")
                    oldCam.close()
                }

                // Send joined to camera
                conn.send(JSONObject().apply {
                    put("type", "joined")
                    put("room", roomId)
                    put("role", role)
                    put("clientId", state.clientId)
                }.toString())

                // Notify camera about all existing viewers
                for ((vid, _) in room.viewers) {
                    conn.send(JSONObject().apply {
                        put("type", "peer_joined")
                        put("room", roomId)
                        put("role", "viewer")
                        put("clientId", vid)
                    }.toString())
                }
            }
            "viewer" -> {
                // Add to viewers map (no replacement — multiple viewers allowed)
                room.viewers[state.clientId] = conn

                // Send joined to viewer with their clientId
                conn.send(JSONObject().apply {
                    put("type", "joined")
                    put("room", roomId)
                    put("role", role)
                    put("clientId", state.clientId)
                }.toString())

                // If camera exists, notify both sides
                if (room.camera != null) {
                    // Tell camera a new viewer joined
                    room.camera!!.send(JSONObject().apply {
                        put("type", "peer_joined")
                        put("room", roomId)
                        put("role", "viewer")
                        put("clientId", state.clientId)
                    }.toString())

                    // Tell viewer the camera is here
                    conn.send(JSONObject().apply {
                        put("type", "peer_joined")
                        put("room", roomId)
                        put("role", "camera")
                        put("clientId", room.cameraClientId)
                    }.toString())
                }
            }
        }

        Log.d(TAG, "Joined room=$roomId role=$role clientId=${state.clientId}")
    }

    private fun relayToPeer(conn: WebSocket, type: String, json: JSONObject) {
        val state = clients[conn] ?: run { sendError(conn, "Not joined"); return }
        val roomId = state.room ?: run { sendError(conn, "Not joined"); return }
        val room = rooms[roomId] ?: run { sendError(conn, "Unknown room"); return }

        val targetId = json.optString("to", "")

        val payload = JSONObject().apply {
            put("type", type)
            put("room", roomId)
            put("from", state.clientId)
            when (type) {
                "offer", "answer" -> put("sdp", json.optString("sdp", ""))
                "ice" -> put("candidate", json.optJSONObject("candidate"))
            }
        }

        when (state.role) {
            "camera" -> {
                // Camera → specific viewer (by clientId in "to" field)
                val viewerWs = if (targetId.isNotEmpty()) room.viewers[targetId] else null
                if (viewerWs != null) {
                    viewerWs.send(payload.toString())
                } else {
                    sendError(conn, "Viewer not found: $targetId")
                }
            }
            "viewer" -> {
                // Viewer → camera
                val cam = room.camera
                if (cam != null) {
                    cam.send(payload.toString())
                } else {
                    sendError(conn, "Camera not connected")
                }
            }
        }
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
