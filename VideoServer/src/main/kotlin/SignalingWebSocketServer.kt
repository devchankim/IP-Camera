import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal WebRTC signaling server.
 *
 * - One camera + one viewer per room
 * - Token protected (SIGNALING_TOKEN env var)
 * - Relays: offer/answer/ice
 *
 * Message shapes (JSON):
 *  - join:   { "type":"join", "room":"baby", "role":"camera|viewer", "token":"..." }
 *  - offer:  { "type":"offer",  "room":"baby", "sdp":"..." }
 *  - answer: { "type":"answer", "room":"baby", "sdp":"..." }
 *  - ice:    { "type":"ice",    "room":"baby", "candidate":{...RTCIceCandidateInit...} }
 * Server events:
 *  - joined:        { "type":"joined", "room":"baby", "role":"camera|viewer" }
 *  - peer_joined:   { "type":"peer_joined", "room":"baby", "role":"camera|viewer" }
 *  - peer_left:     { "type":"peer_left", "room":"baby", "role":"camera|viewer" }
 *  - error:         { "type":"error", "message":"..." }
 */
class SignalingWebSocketServer(
    bindPort: Int = 8081,
) {
    private val mapper = jacksonObjectMapper()

    private data class ClientState(
        var room: String? = null,
        var role: String? = null, // "camera" | "viewer"
    )

    private data class RoomState(
        @Volatile var camera: WebSocket? = null,
        @Volatile var viewer: WebSocket? = null,
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
            clients[conn] = ClientState()
            println("Signaling: connection opened: ${conn.remoteSocketAddress}")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            val state = clients.remove(conn)
            if (state?.room != null && state.role != null) {
                val roomId = state.room!!
                val room = rooms[roomId]
                if (room != null) {
                    val role = state.role!!
                    val peerRole = if (role == "camera") "viewer" else "camera"
                    val peer = if (role == "camera") room.viewer else room.camera
                    if (role == "camera" && room.camera == conn) room.camera = null
                    if (role == "viewer" && room.viewer == conn) room.viewer = null
                    peer?.send(
                        mapper.writeValueAsString(
                            mapOf("type" to "peer_left", "room" to roomId, "role" to role)
                        )
                    )
                    println("Signaling: $role left room=$roomId")

                    if (room.camera == null && room.viewer == null) {
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
        val prev = clients[conn] ?: ClientState().also { clients[conn] = it }
        prev.room = roomId
        prev.role = role

        // Enforce one-per-role.
        val replaced: WebSocket? = when (role) {
            "camera" -> {
                val old = room.camera
                room.camera = conn
                old
            }
            else -> {
                val old = room.viewer
                room.viewer = conn
                old
            }
        }
        if (replaced != null && replaced != conn) {
            sendError(replaced, "Replaced by a new $role connection")
            replaced.close()
        }

        conn.send(mapper.writeValueAsString(mapOf("type" to "joined", "room" to roomId, "role" to role)))

        val peer = if (role == "camera") room.viewer else room.camera
        if (peer != null) {
            // Notify both sides someone is ready.
            peer.send(mapper.writeValueAsString(mapOf("type" to "peer_joined", "room" to roomId, "role" to role)))
            conn.send(mapper.writeValueAsString(mapOf("type" to "peer_joined", "room" to roomId, "role" to (if (role == "camera") "viewer" else "camera"))))
        }

        println("Signaling: joined room=$roomId role=$role")
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

        val peer = if (role == "camera") room.viewer else room.camera
        if (peer == null) {
            sendError(conn, "Peer not connected")
            return
        }

        val payload = mapper.createObjectNode().apply {
            put("type", type)
            put("room", roomId)
            put("from", role)

            when (type) {
                "offer", "answer" -> put("sdp", root.getText("sdp") ?: "")
                "ice" -> set<JsonNode>("candidate", root.get("candidate"))
            }
        }

        peer.send(mapper.writeValueAsString(payload))
    }

    private fun sendError(conn: WebSocket, message: String) {
        try {
            conn.send(mapper.writeValueAsString(mapOf("type" to "error", "message" to message)))
        } catch (_: Exception) {
        }
    }

    private fun JsonNode.getText(field: String): String? = this.get(field)?.takeIf { !it.isNull }?.asText()
}

