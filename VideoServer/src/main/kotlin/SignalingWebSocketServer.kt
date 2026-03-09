import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * Minimal WebRTC signaling server.
 *
 * - One camera + N viewers per room
 * - Token protected (SIGNALING_TOKEN env var)
 * - Relays: offer/answer/ice with explicit target routing
 *
 * Message shapes (JSON):
 *  - join:   { "type":"join", "room":"baby", "role":"camera|viewer", "token":"..." }
 *  - offer:  { "type":"offer",  "room":"baby", "to":"clientId", "sdp":"..." }
 *  - answer: { "type":"answer", "room":"baby", "to":"clientId", "sdp":"..." }
 *  - ice:    { "type":"ice",    "room":"baby", "to":"clientId", "candidate":{...RTCIceCandidateInit...} }
 * Server events:
 *  - joined:        { "type":"joined", "room":"baby", "role":"camera|viewer", "clientId":"..." }
 *  - peer_joined:   { "type":"peer_joined", "room":"baby", "role":"camera|viewer", "clientId":"..." }
 *  - peer_left:     { "type":"peer_left", "room":"baby", "role":"camera|viewer", "clientId":"..." }
 *  - error:         { "type":"error", "message":"..." }
 */
class SignalingWebSocketServer(
    bindPort: Int = 8081,
) {
    private val mapper = jacksonObjectMapper()

    private data class ClientState(
        val clientId: String,
        var room: String? = null,
        var role: String? = null, // "camera" | "viewer"
    )

    private data class RoomState(
        @Volatile var cameraId: String? = null,
        @Volatile var camera: WebSocket? = null,
        val viewers: ConcurrentHashMap<String, WebSocket> = ConcurrentHashMap(),
    )

    private val clients = ConcurrentHashMap<WebSocket, ClientState>()
    private val rooms = ConcurrentHashMap<String, RoomState>()

    private val requiredToken: String =
        (System.getenv("SIGNALING_TOKEN") ?: "").trim().also {
            if (it.isEmpty()) {
                println("WARNING: SIGNALING_TOKEN is not set. Server will reject all joins.")
            }
        }

    private val server = object : WebSocketServer(InetSocketAddress(bindPort)) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            clients[conn] = ClientState(clientId = UUID.randomUUID().toString())
            println("Signaling: connection opened: ${conn.remoteSocketAddress}")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            val state = clients.remove(conn)
            if (state?.room != null && state.role != null) {
                val roomId = state.room!!
                val room = rooms[roomId]
                if (room != null) {
                    val role = state.role!!
                    val clientId = state.clientId
                    if (role == "camera" && room.camera == conn) {
                        room.camera = null
                        room.cameraId = null
                        val payload = mapper.writeValueAsString(
                            mapOf(
                                "type" to "peer_left",
                                "room" to roomId,
                                "role" to "camera",
                                "clientId" to clientId,
                            )
                        )
                        room.viewers.values.forEach { viewer ->
                            safeSend(viewer, payload)
                        }
                    }
                    if (role == "viewer") {
                        room.viewers.remove(clientId)
                        room.camera?.let { camera ->
                            safeSend(
                                camera,
                                mapper.writeValueAsString(
                                    mapOf(
                                        "type" to "peer_left",
                                        "room" to roomId,
                                        "role" to "viewer",
                                        "clientId" to clientId,
                                    )
                                )
                            )
                        }
                    }
                    println("Signaling: $role left room=$roomId")

                    if (room.camera == null && room.viewers.isEmpty()) {
                        rooms.remove(roomId)
                    }
                }
            }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val root = try {
                mapper.readTree(message)
            } catch (e: Exception) {
                sendError(conn, "Invalid JSON")
                return
            }

            val type = root.getText("type") ?: run {
                sendError(conn, "Missing field: type")
                return
            }

            when (type) {
                "join" -> handleJoin(conn, root)
                "offer", "answer", "ice" -> relayToPeer(conn, type, root)
                else -> sendError(conn, "Unknown type: $type")
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            println("Signaling: error: ${ex.message}")
            ex.printStackTrace()
        }

        override fun onStart() {
            println("Signaling: started on ws://0.0.0.0:$bindPort (set env SIGNALING_TOKEN)")
        }
    }

    fun start() {
        // Allow quick restarts even if previous TCP connections are in TIME_WAIT.
        // Must be set before start().
        server.setReuseAddr(true)
        server.start()
    }

    private fun handleJoin(conn: WebSocket, root: JsonNode) {
        if (requiredToken.isEmpty()) {
            sendError(conn, "Server misconfigured: SIGNALING_TOKEN is empty")
            conn.close()
            return
        }

        val token = root.getText("token") ?: ""
        if (token != requiredToken) {
            sendError(conn, "Unauthorized")
            conn.close()
            return
        }

        val roomId = root.getText("room") ?: run {
            sendError(conn, "Missing field: room")
            return
        }
        val role = root.getText("role") ?: run {
            sendError(conn, "Missing field: role")
            return
        }
        if (role != "camera" && role != "viewer") {
            sendError(conn, "Invalid role: $role")
            return
        }

        val room = rooms.computeIfAbsent(roomId) { RoomState() }

        // Register client into room.
        val prev = clients[conn] ?: ClientState(clientId = UUID.randomUUID().toString()).also { clients[conn] = it }
        detachClientFromCurrentRoom(conn, prev)
        prev.room = roomId
        prev.role = role
        val clientId = prev.clientId

        // Enforce one camera per room, but allow multiple viewers.
        val replacedCamera: WebSocket? = when (role) {
            "camera" -> {
                val old = room.camera
                room.camera = conn
                room.cameraId = clientId
                old
            }
            else -> {
                room.viewers[clientId] = conn
                null
            }
        }
        if (replacedCamera != null && replacedCamera != conn) {
            sendError(replacedCamera, "Replaced by a new camera connection")
            replacedCamera.close()
        }

        safeSend(
            conn,
            mapper.writeValueAsString(
                mapOf("type" to "joined", "room" to roomId, "role" to role, "clientId" to clientId)
            )
        )

        if (role == "camera") {
            // Existing viewers must be announced to camera so it can create an offer per viewer.
            room.viewers.keys.forEach { viewerId ->
                safeSend(
                    conn,
                    mapper.writeValueAsString(
                        mapOf(
                            "type" to "peer_joined",
                            "room" to roomId,
                            "role" to "viewer",
                            "clientId" to viewerId,
                        )
                    )
                )
            }
            // Let viewers know camera is available.
            val cameraJoinedPayload = mapper.writeValueAsString(
                mapOf(
                    "type" to "peer_joined",
                    "room" to roomId,
                    "role" to "camera",
                    "clientId" to clientId,
                )
            )
            room.viewers.values.forEach { viewer ->
                safeSend(viewer, cameraJoinedPayload)
            }
        } else {
            room.camera?.let { camera ->
                safeSend(
                    camera,
                    mapper.writeValueAsString(
                        mapOf(
                            "type" to "peer_joined",
                            "room" to roomId,
                            "role" to "viewer",
                            "clientId" to clientId,
                        )
                    )
                )
            }
            room.cameraId?.let { cameraId ->
                safeSend(
                    conn,
                    mapper.writeValueAsString(
                        mapOf(
                            "type" to "peer_joined",
                            "room" to roomId,
                            "role" to "camera",
                            "clientId" to cameraId,
                        )
                    )
                )
            }
        }

        println("Signaling: joined room=$roomId role=$role clientId=$clientId")
    }

    private fun relayToPeer(conn: WebSocket, type: String, root: JsonNode) {
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

        val to = root.getText("to")
        val targetId = if (role == "camera") {
            to ?: inferSingleViewerId(room)
        } else {
            room.cameraId
        } ?: run {
            sendError(conn, "Missing target peer")
            return
        }

        val peer = if (role == "camera") {
            room.viewers[targetId]
        } else {
            room.camera
        }
        if (peer == null) {
            sendError(conn, "Peer not connected")
            return
        }

        val payload = mapper.createObjectNode().apply {
            put("type", type)
            put("room", roomId)
            put("from", state.clientId)
            put("fromRole", role)
            put("to", targetId)

            when (type) {
                "offer", "answer" -> put("sdp", root.getText("sdp") ?: "")
                "ice" -> set<JsonNode>("candidate", root.get("candidate"))
            }
        }

        safeSend(peer, mapper.writeValueAsString(payload))
    }

    private fun sendError(conn: WebSocket, message: String) {
        try {
            conn.send(mapper.writeValueAsString(mapOf("type" to "error", "message" to message)))
        } catch (_: Exception) {
        }
    }

    private fun inferSingleViewerId(room: RoomState): String? {
        if (room.viewers.size == 1) {
            return room.viewers.keys.firstOrNull()
        }
        return null
    }

    private fun detachClientFromCurrentRoom(conn: WebSocket, state: ClientState) {
        val oldRoomId = state.room ?: return
        val oldRole = state.role ?: return
        val oldRoom = rooms[oldRoomId] ?: return
        if (oldRole == "camera" && oldRoom.camera == conn) {
            oldRoom.camera = null
            oldRoom.cameraId = null
        }
        if (oldRole == "viewer") {
            oldRoom.viewers.remove(state.clientId)
        }
        if (oldRoom.camera == null && oldRoom.viewers.isEmpty()) {
            rooms.remove(oldRoomId)
        }
    }

    private fun safeSend(conn: WebSocket, payload: String) {
        try {
            conn.send(payload)
        } catch (_: Exception) {
        }
    }

    private fun JsonNode.getText(field: String): String? = this.get(field)?.takeIf { !it.isNull }?.asText()
}

