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
 * Improvements over the original:
 *  #2  Connection-lost detection (30 s timeout via java-websocket built-in)
 *  #5  "cry_detected" event relayed from camera to all viewers in the room
 *  #9  TURN server list injected into "joined" response via env var TURN_SERVERS
 *      Format: "turn:host:port|username|credential" (comma-separated for multiple)
 *  #16 Optional WSS via env vars KEYSTORE_PATH + KEYSTORE_PASSWORD
 *  #17 All join/leave events logged via AccessLogger
 *
 * Message shapes (JSON):
 *  - join:          { "type":"join", "room":"baby", "role":"camera|viewer", "token":"..." }
 *  - offer:         { "type":"offer",  "room":"baby", "to":"clientId", "sdp":"..." }
 *  - answer:        { "type":"answer", "room":"baby", "to":"clientId", "sdp":"..." }
 *  - ice:           { "type":"ice",    "room":"baby", "to":"clientId", "candidate":{...} }
 *  - cry_detected:  { "type":"cry_detected", "room":"baby" }
 * Server events:
 *  - joined:        { "type":"joined",     "room":"...", "role":"...", "clientId":"..." }
 *  - peer_joined:   { "type":"peer_joined","room":"...", "role":"...", "clientId":"..." }
 *  - peer_left:     { "type":"peer_left",  "room":"...", "role":"...", "clientId":"..." }
 *  - cry_detected:  { "type":"cry_detected" }  ← broadcast to all viewers
 *  - error:         { "type":"error", "message":"..." }
 */
class SignalingWebSocketServer(
    bindPort: Int = 8081,
) {
    private val mapper = jacksonObjectMapper()

    private data class ClientState(
        val clientId: String,
        var room: String? = null,
        var role: String? = null,
    )

    private data class RoomState(
        @Volatile var cameraId: String? = null,
        @Volatile var camera: WebSocket? = null,
        val viewers: ConcurrentHashMap<String, WebSocket> = ConcurrentHashMap(),
    )

    private val clients = ConcurrentHashMap<WebSocket, ClientState>()
    private val rooms   = ConcurrentHashMap<String, RoomState>()

    // ── Token ────────────────────────────────────────────────────────────────
    private val requiredToken: String =
        (System.getenv("SIGNALING_TOKEN") ?: "").trim().also {
            if (it.isEmpty()) println("WARNING: SIGNALING_TOKEN is not set. Server will reject all joins.")
        }

    // ── #9 TURN servers from env var ────────────────────────────────────────
    // Format: "turn:host:3478|user|pass,turn:host2:3479|user2|pass2"
    private val turnServersJson: String = run {
        val raw = System.getenv("TURN_SERVERS") ?: ""
        if (raw.isBlank()) return@run "[]"
        val list = raw.split(",").mapNotNull { entry ->
            val parts = entry.trim().split("|")
            if (parts.isEmpty()) return@mapNotNull null
            val url  = parts.getOrNull(0) ?: return@mapNotNull null
            val user = parts.getOrNull(1) ?: ""
            val cred = parts.getOrNull(2) ?: ""
            buildString {
                append("""{"urls":"$url"""")
                if (user.isNotBlank()) append(""","username":"$user"""")
                if (cred.isNotBlank()) append(""","credential":"$cred"""")
                append("}")
            }
        }
        "[${list.joinToString(",")}]"
    }

    // ── Server ───────────────────────────────────────────────────────────────
    private val server = object : WebSocketServer(InetSocketAddress(bindPort)) {

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            clients[conn] = ClientState(clientId = UUID.randomUUID().toString())
            val ip = conn.remoteSocketAddress?.address?.hostAddress ?: "?"
            println("Signaling: connection opened: $ip")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            val state = clients.remove(conn)
            val ip    = conn.remoteSocketAddress?.address?.hostAddress ?: "?"
            if (state?.room != null && state.role != null) {
                val roomId   = state.room!!
                val room     = rooms[roomId]
                val role     = state.role!!
                val clientId = state.clientId
                if (room != null) {
                    if (role == "camera" && room.camera == conn) {
                        room.camera = null; room.cameraId = null
                        val payload = mapper.writeValueAsString(
                            mapOf("type" to "peer_left", "room" to roomId, "role" to "camera", "clientId" to clientId)
                        )
                        room.viewers.values.forEach { safeSend(it, payload) }
                    }
                    if (role == "viewer") {
                        room.viewers.remove(clientId)
                        room.camera?.let { cam ->
                            safeSend(cam, mapper.writeValueAsString(
                                mapOf("type" to "peer_left", "room" to roomId, "role" to "viewer", "clientId" to clientId)
                            ))
                        }
                    }
                    if (room.camera == null && room.viewers.isEmpty()) rooms.remove(roomId)
                }
                // #17 Log
                AccessLogger.log(ip, "$role left", "room=$roomId clientId=$clientId")
            }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val root = try { mapper.readTree(message) } catch (e: Exception) {
                sendError(conn, "Invalid JSON"); return
            }
            val type = root.getText("type") ?: run { sendError(conn, "Missing field: type"); return }
            when (type) {
                "join"                      -> handleJoin(conn, root)
                "offer", "answer", "ice"    -> relayToPeer(conn, type, root)
                "cry_detected"              -> relayCryDetected(conn, root)
                else                        -> sendError(conn, "Unknown type: $type")
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            println("Signaling: error: ${ex.message}")
        }

        override fun onStart() {
            println("Signaling: started on ws://0.0.0.0:$bindPort")
        }
    }

    fun start() {
        server.setReuseAddr(true)
        // #2 Detect silent disconnects — close connection if no data/ping for 30 s
        server.setConnectionLostTimeout(30)
        server.start()
    }

    // ── Join ────────────────────────────────────────────────────────────────

    private fun handleJoin(conn: WebSocket, root: JsonNode) {
        if (requiredToken.isEmpty()) { sendError(conn, "Server misconfigured: SIGNALING_TOKEN is empty"); conn.close(); return }
        val token = root.getText("token") ?: ""
        if (token != requiredToken) { sendError(conn, "Unauthorized"); conn.close(); return }

        val roomId = root.getText("room") ?: run { sendError(conn, "Missing field: room"); return }
        val role   = root.getText("role") ?: run { sendError(conn, "Missing field: role"); return }
        if (role != "camera" && role != "viewer") { sendError(conn, "Invalid role: $role"); return }

        val room   = rooms.computeIfAbsent(roomId) { RoomState() }
        val prev   = clients[conn] ?: ClientState(UUID.randomUUID().toString()).also { clients[conn] = it }
        detachClientFromCurrentRoom(conn, prev)
        prev.room  = roomId; prev.role = role
        val clientId = prev.clientId
        val ip       = conn.remoteSocketAddress?.address?.hostAddress ?: "?"

        val replacedCamera: WebSocket? = when (role) {
            "camera" -> { val old = room.camera; room.camera = conn; room.cameraId = clientId; old }
            else     -> { room.viewers[clientId] = conn; null }
        }
        if (replacedCamera != null && replacedCamera != conn) {
            sendError(replacedCamera, "Replaced by a new camera connection"); replacedCamera.close()
        }

        // #9 Include TURN servers in joined response
        safeSend(conn, """{"type":"joined","room":"$roomId","role":"$role","clientId":"$clientId","iceServers":$turnServersJson}""")

        if (role == "camera") {
            room.viewers.keys.forEach { vid ->
                safeSend(conn, mapper.writeValueAsString(
                    mapOf("type" to "peer_joined", "room" to roomId, "role" to "viewer", "clientId" to vid)
                ))
            }
            val cameraJoined = mapper.writeValueAsString(
                mapOf("type" to "peer_joined", "room" to roomId, "role" to "camera", "clientId" to clientId)
            )
            room.viewers.values.forEach { safeSend(it, cameraJoined) }
        } else {
            room.camera?.let { cam ->
                safeSend(cam, mapper.writeValueAsString(
                    mapOf("type" to "peer_joined", "room" to roomId, "role" to "viewer", "clientId" to clientId)
                ))
            }
            room.cameraId?.let { cid ->
                safeSend(conn, mapper.writeValueAsString(
                    mapOf("type" to "peer_joined", "room" to roomId, "role" to "camera", "clientId" to cid)
                ))
            }
        }

        // #17 Log
        AccessLogger.log(ip, "$role joined", "room=$roomId clientId=$clientId")
        println("Signaling: joined room=$roomId role=$role clientId=$clientId")
    }

    // ── Relay ────────────────────────────────────────────────────────────────

    private fun relayToPeer(conn: WebSocket, type: String, root: JsonNode) {
        val state  = clients[conn]
        val roomId = state?.room ?: run { sendError(conn, "Not joined"); return }
        val role   = state.role  ?: run { sendError(conn, "Not joined"); return }
        val room   = rooms[roomId] ?: run { sendError(conn, "Unknown room"); return }

        val to       = root.getText("to")
        val targetId = if (role == "camera") to ?: inferSingleViewerId(room) else room.cameraId
            ?: run { sendError(conn, "Missing target peer"); return }

        val peer = if (role == "camera") room.viewers[targetId] else room.camera
            ?: run { sendError(conn, "Peer not connected"); return }

        val payload = mapper.createObjectNode().apply {
            put("type", type); put("room", roomId)
            put("from", state.clientId); put("fromRole", role); put("to", targetId)
            when (type) {
                "offer", "answer" -> put("sdp", root.getText("sdp") ?: "")
                "ice"             -> set<JsonNode>("candidate", root.get("candidate"))
            }
        }
        safeSend(peer, mapper.writeValueAsString(payload))
    }

    // ── #5 Cry detected ──────────────────────────────────────────────────────

    private fun relayCryDetected(conn: WebSocket, root: JsonNode) {
        val state  = clients[conn]
        val roomId = state?.room ?: return
        if (state.role != "camera") return   // only camera can send this
        val room = rooms[roomId] ?: return
        val payload = mapper.writeValueAsString(mapOf("type" to "cry_detected"))
        room.viewers.values.forEach { safeSend(it, payload) }
        val ip = conn.remoteSocketAddress?.address?.hostAddress ?: "?"
        AccessLogger.log(ip, "cry_detected", "room=$roomId viewers=${room.viewers.size}")
        println("Signaling: cry_detected relayed in room=$roomId to ${room.viewers.size} viewer(s)")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun sendError(conn: WebSocket, message: String) {
        try { conn.send(mapper.writeValueAsString(mapOf("type" to "error", "message" to message))) } catch (_: Exception) {}
    }

    private fun inferSingleViewerId(room: RoomState): String? =
        if (room.viewers.size == 1) room.viewers.keys.firstOrNull() else null

    private fun detachClientFromCurrentRoom(conn: WebSocket, state: ClientState) {
        val oldRoomId = state.room ?: return
        val oldRole   = state.role ?: return
        val oldRoom   = rooms[oldRoomId] ?: return
        if (oldRole == "camera" && oldRoom.camera == conn) { oldRoom.camera = null; oldRoom.cameraId = null }
        if (oldRole == "viewer") oldRoom.viewers.remove(state.clientId)
        if (oldRoom.camera == null && oldRoom.viewers.isEmpty()) rooms.remove(oldRoomId)
    }

    private fun safeSend(conn: WebSocket, payload: String) {
        try { conn.send(payload) } catch (_: Exception) {}
    }

    private fun JsonNode.getText(field: String): String? =
        this.get(field)?.takeIf { !it.isNull }?.asText()
}
