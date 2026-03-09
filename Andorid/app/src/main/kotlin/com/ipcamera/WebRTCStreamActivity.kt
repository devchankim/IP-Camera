package com.ipcamera

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ipcamera.databinding.WebrtcStreamActivityBinding
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.webrtc.*
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * WebRTC Camera (offerer) Activity
 *
 * Features:
 *  #1  Infinite reconnection with exponential back-off (capped at 30 s)
 *  #2  Keepalive ping every 15 s to detect silent WebSocket drops
 *  #4  Battery-optimisation exemption request on startup
 *  #5  Cry detection → broadcasts "cry_detected" to all viewers via signaling
 *  #6  H.264 codec preference (SDP munging) + night-mode low-fps option
 *  #8  Motion detection (VideoSink frame-diff) → ntfy push notification
 *  #9  TURN server support from Settings
 *  #10 Adaptive max-bitrate via RtpSender.setParameters() + periodic stats
 *  #15 Dynamic room name from Settings (default: "baby")
 */
class WebRTCStreamActivity : AppCompatActivity() {

    private lateinit var binding: WebrtcStreamActivityBinding
    private val TAG = "WebRTCStreamTag"

    private var eglBase: EglBase? = null

    // WebRTC
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private val peerSessions = ConcurrentHashMap<String, PeerSession>()

    // Signaling
    private var signalingClient: WebSocketClient? = null
    private var roomName = "baby"
    private var signalingToken = ""
    private var signalingServerAddress: String = ""
    private var reconnectAttempts = 0
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var userStopped = false
    private var sessionActive = false
    private var localClientId: String = ""

    // Settings
    private var allowStunFallback = false
    private var cameraFacingPref = "back"
    private var qualityPref = "medium"
    private var nightModePref = false
    private var cryDetectEnabled = false
    private var ntfyTopicPref = ""
    private var ntfyBaseUrl = "https://ntfy.sh"
    private var turnUrl = ""
    private var turnUsername = ""
    private var turnCredential = ""

    // #2 Keepalive ping
    private val pingHandler = Handler(Looper.getMainLooper())
    private val pingRunnable = object : Runnable {
        override fun run() {
            try { signalingClient?.sendPing() } catch (_: Exception) {}
            pingHandler.postDelayed(this, 15_000L)
        }
    }

    // #5 Cry detection
    private var cryDetector: CryDetector? = null
    private var lastCrySentMs = 0L
    private val CRY_COOLDOWN_MS = 10_000L

    // #8 Motion detection
    private var motionDetector: MotionDetector? = null
    private var lastMotionSentMs = 0L
    private val MOTION_COOLDOWN_MS = 60_000L

    // #10 Adaptive bitrate
    private val bitrateHandler = Handler(Looper.getMainLooper())
    private val bitrateRunnable = object : Runnable {
        override fun run() {
            adjustBitrate()
            bitrateHandler.postDelayed(this, 5_000L)
        }
    }

    // Foreground service
    private var streamingService: StreamingForegroundService? = null
    private var serviceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            streamingService = (binder as StreamingForegroundService.LocalBinder).getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            streamingService = null
            serviceBound = false
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 2000
    }

    private data class PeerSession(
        val viewerId: String,
        val peerConnection: PeerConnection,
        @Volatile var remoteDescriptionSet: Boolean = false,
        val pendingRemoteIceCandidates: ArrayDeque<IceCandidate> = ArrayDeque(),
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        EdgeToEdge.setDecorFitsSystemWindows(window, fitSystemWindows = false)
        EdgeToEdge.enableImmersiveMode(window)

        binding = WebrtcStreamActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        EdgeToEdge.setInsetsHandler(
            root = binding.root,
            handler = StreamActivityInsetsHandler { systemBarInsets ->
                binding.btnToggle.setPadding(0, 0, 0, systemBarInsets.bottom + 20)
            }
        )

        // #4 Battery optimisation exemption
        requestBatteryOptimizationExemption()

        loadPrefs()

        binding.tvStatus.text = "Status: Not connected"

        binding.btnBack.setOnClickListener {
            if (sessionActive) stopStream()
            finish()
        }

        binding.btnMute.isEnabled = false
        binding.btnMute.setOnClickListener {
            val enabled = localAudioTrack?.enabled() ?: true
            localAudioTrack?.setEnabled(!enabled)
            binding.btnMute.text = if (enabled) "Unmute" else "Mute"
        }

        binding.btnToggle.setOnClickListener {
            if (sessionActive) {
                stopStream()
            } else {
                if (checkPermissions()) {
                    startStream(signalingServerAddress)
                } else {
                    requestPermissions()
                }
            }
        }
    }

    private fun loadPrefs() {
        val prefs = SettingsPreferences(applicationContext)
        signalingServerAddress = prefs.getIpAddress() ?: "192.168.0.101:8081"
        signalingToken         = prefs.getSignalingToken() ?: ""
        roomName               = prefs.getRoomName()
        allowStunFallback      = prefs.isStunFallbackEnabled()
        cameraFacingPref       = prefs.getCameraFacing()
        qualityPref            = prefs.getQualityPreset()
        nightModePref          = prefs.isNightModeEnabled()
        cryDetectEnabled       = prefs.isCryDetectEnabled()
        ntfyTopicPref          = prefs.getNtfyTopic()
        ntfyBaseUrl            = prefs.getNtfyBaseUrl()
        turnUrl                = prefs.getTurnUrl()
        turnUsername           = prefs.getTurnUsername()
        turnCredential         = prefs.getTurnCredential()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun checkPermissions(): Boolean {
        val base = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else base
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                loadPrefs()
                startStream(signalingServerAddress)
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── #4 Battery optimisation ───────────────────────────────────────────────

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {}
            }
        }
    }

    // ── Stream lifecycle ──────────────────────────────────────────────────────

    private fun startStream(serverAddress: String) {
        sessionActive = true
        binding.btnToggle.text = "Stop"
        binding.tvStatus.text  = "Starting..."
        userStopped     = false
        reconnectAttempts = 0
        signalingServerAddress = serverAddress

        // Start foreground service
        val serviceIntent = Intent(this, StreamingForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)

        // Initialise WebRTC
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()

        // Camera capturer
        videoCapturer = createCameraCapturer(Camera2Enumerator(this), cameraFacingPref)
        if (videoCapturer == null) {
            Toast.makeText(this, "Failed to open camera", Toast.LENGTH_SHORT).show()
            return
        }

        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        val videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer!!.initialize(surfaceTextureHelper, this, videoSource.capturerObserver)

        // #6 Resolve capture parameters
        val (capW, capH, capFps) = resolveCaptureParams()
        videoCapturer!!.startCapture(capW, capH, capFps)

        localVideoTrack = peerConnectionFactory.createVideoTrack("video0", videoSource)
        localVideoTrack?.setEnabled(true)

        // Audio
        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource)
        localAudioTrack?.setEnabled(true)
        binding.btnMute.text = "Mute"
        binding.btnMute.isEnabled = true

        // Local preview
        binding.localView.init(eglBase!!.eglBaseContext, null)
        binding.localView.setMirror(cameraFacingPref == "front")
        binding.localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        localVideoTrack?.addSink(binding.localView)

        // #8 Motion detector — attach as additional VideoSink
        motionDetector = MotionDetector(
            onMotionDetected = {
                val now = System.currentTimeMillis()
                if (now - lastMotionSentMs > MOTION_COOLDOWN_MS) {
                    lastMotionSentMs = now
                    NtfyNotifier.send(
                        topic    = ntfyTopicPref,
                        title    = "👶 Motion detected",
                        message  = "Movement detected by the baby cam",
                        priority = "default",
                        baseUrl  = ntfyBaseUrl,
                    )
                }
            },
            onMotionStopped = { /* nothing needed */ },
        )
        localVideoTrack?.addSink(motionDetector!!)

        // #5 Cry detector — starts its own AudioRecord thread
        if (cryDetectEnabled) {
            cryDetector = CryDetector(
                onCryDetected = {
                    val now = System.currentTimeMillis()
                    if (now - lastCrySentMs > CRY_COOLDOWN_MS) {
                        lastCrySentMs = now
                        sendCryDetectedSignal()
                    }
                },
                onSilenceRestored = { /* nothing needed */ },
            ).also { it.start() }
        }

        connectSignaling(serverAddress)
    }

    // #6 Resolve capture resolution / fps considering night mode + quality preset + power saver
    private fun resolveCaptureParams(): Triple<Int, Int, Int> {
        if (nightModePref) return Triple(640, 480, 15)
        val pm = getSystemService(PowerManager::class.java)
        val effective = if (pm?.isPowerSaveMode == true) "low" else qualityPref
        return when (effective) {
            "low"  -> Triple(480, 360, 15)
            "high" -> Triple(1280, 720, 30)
            else   -> Triple(640, 480, 24)
        }
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator, preferredFacing: String): CameraVideoCapturer? {
        val wantBack = preferredFacing != "front"
        fun tryFacing(isWanted: (String) -> Boolean): CameraVideoCapturer? {
            for (device in enumerator.deviceNames) if (isWanted(device)) return enumerator.createCapturer(device, null)
            return null
        }
        return if (wantBack) tryFacing { enumerator.isBackFacing(it) } ?: tryFacing { enumerator.isFrontFacing(it) }
        else                  tryFacing { enumerator.isFrontFacing(it) } ?: tryFacing { enumerator.isBackFacing(it) }
    }

    // ── Signaling ─────────────────────────────────────────────────────────────

    private fun connectSignaling(serverAddress: String) {
        binding.tvStatus.text = "Connecting to signaling..."
        val uri = URI("ws://$serverAddress")
        signalingClient = object : WebSocketClient(uri) {
            override fun onOpen(h: ServerHandshake?) {
                reconnectAttempts = 0
                runOnUiThread { binding.tvStatus.text = "Signaling: connected, joining room..." }
                // #2 Start keepalive
                pingHandler.post(pingRunnable)
                send(JSONObject().apply {
                    put("type", "join")
                    put("room", roomName)
                    put("role", "camera")
                    put("token", signalingToken)
                }.toString())
            }

            override fun onMessage(message: String?) {
                message ?: return
                Log.d(TAG, "Signaling: $message")
                try {
                    val json = JSONObject(message)
                    when (json.getString("type")) {
                        "joined"     -> onJoined(json.optString("clientId", ""))
                        "peer_joined"-> onPeerJoined(json.optString("role",""), json.optString("clientId",""))
                        "peer_left"  -> onPeerLeft(json.optString("role",""), json.optString("clientId",""))
                        "answer"     -> onAnswer(json.optString("from",""), json.getString("sdp"))
                        "ice"        -> onIceCandidate(json.optString("from",""), json.getJSONObject("candidate"))
                        "error"      -> {
                            val err = json.optString("message","Unknown error")
                            runOnUiThread {
                                Toast.makeText(this@WebRTCStreamActivity, "Signaling error: $err", Toast.LENGTH_SHORT).show()
                                binding.tvStatus.text = "Error: $err"
                            }
                        }
                    }
                } catch (e: Exception) { Log.e(TAG, "Failed to parse signaling message", e) }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "Signaling closed: $reason")
                pingHandler.removeCallbacks(pingRunnable)
                closeAllPeerSessions()
                runOnUiThread { binding.tvStatus.text = "Signaling: disconnected" }
                if (!userStopped) scheduleReconnect()
            }

            override fun onError(ex: Exception?) { Log.e(TAG, "Signaling error", ex) }
        }
        signalingClient?.connect()
    }

    // #1 Infinite reconnect with exponential back-off capped at 30 s
    private fun scheduleReconnect() {
        reconnectAttempts++
        val delayMs = (1000L * reconnectAttempts * reconnectAttempts).coerceAtMost(30_000L)
        runOnUiThread { binding.tvStatus.text = "Reconnecting (attempt $reconnectAttempts)..." }
        reconnectHandler.postDelayed({
            if (!userStopped) connectSignaling(signalingServerAddress)
        }, delayMs)
    }

    // ── Signaling message handlers ────────────────────────────────────────────

    private fun onJoined(clientId: String) {
        localClientId = clientId
        runOnUiThread { binding.tvStatus.text = "Joined room \"$roomName\", waiting for viewer..." }
    }

    private fun onPeerJoined(role: String, clientId: String) {
        if (role != "viewer" || clientId.isBlank()) return
        runOnUiThread { binding.tvStatus.text = "Viewer connected (${peerSessions.size + 1}), creating offer..." }
        if (peerSessions.containsKey(clientId)) return
        try {
            createPeerSession(clientId) ?: run {
                runOnUiThread { binding.tvStatus.text = "Failed to prepare session for viewer" }
                return
            }
            createOffer(clientId)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start WebRTC for viewer=$clientId", t)
            runOnUiThread {
                binding.tvStatus.text = "WebRTC error: ${t.javaClass.simpleName}"
                Toast.makeText(this, "WebRTC failed: ${t.message}", Toast.LENGTH_LONG).show()
            }
            closeViewerSession(clientId)
        }
    }

    private fun onPeerLeft(role: String, clientId: String) {
        if (role != "viewer" || clientId.isBlank()) return
        closeViewerSession(clientId)
        runOnUiThread {
            binding.tvStatus.text = if (peerSessions.isEmpty())
                "Joined room, waiting for viewer..."
            else
                "Viewer disconnected, active viewers: ${peerSessions.size}"
        }
    }

    // ── Peer session ──────────────────────────────────────────────────────────

    private fun createPeerSession(viewerId: String): PeerSession? {
        peerSessions[viewerId]?.let { return it }

        val iceServers = buildList {
            if (allowStunFallback) {
                add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            }
            // #9 TURN server
            if (turnUrl.isNotBlank()) {
                val builder = PeerConnection.IceServer.builder(turnUrl)
                if (turnUsername.isNotBlank()) builder.setUsername(turnUsername)
                if (turnCredential.isNotBlank()) builder.setPassword(turnCredential)
                add(builder.createIceServer())
            }
        }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics             = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy             = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy            = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate?) {
                c ?: return
                signalingClient?.send(JSONObject().apply {
                    put("type", "ice"); put("room", roomName); put("to", viewerId)
                    put("candidate", JSONObject().apply {
                        put("sdpMid", c.sdpMid)
                        put("sdpMLineIndex", c.sdpMLineIndex)
                        put("candidate", c.sdp)
                    })
                }.toString())
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE state (viewer=$viewerId): $state")
                runOnUiThread {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            binding.tvStatus.text = "Streaming to ${peerSessions.size} viewer(s)"
                            streamingService?.updateNotification("Streaming active")
                            // #10 Start adaptive bitrate polling
                            bitrateHandler.post(bitrateRunnable)
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED -> {
                            closeViewerSession(viewerId)
                            bitrateHandler.removeCallbacks(bitrateRunnable)
                            binding.tvStatus.text = if (peerSessions.isEmpty())
                                "Connection lost, waiting for viewer..."
                            else
                                "Viewer lost, active viewers: ${peerSessions.size}"
                            streamingService?.updateNotification("Connection lost")
                        }
                        else -> {}
                    }
                }
            }
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onIceConnectionReceivingChange(r: Boolean) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(c: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        }) ?: return null

        pc.addTrack(localVideoTrack ?: return null, listOf("stream0"))
        pc.addTrack(localAudioTrack ?: return null, listOf("stream0"))

        val session = PeerSession(viewerId = viewerId, peerConnection = pc)
        peerSessions[viewerId] = session
        return session
    }

    // ── Offer / Answer / ICE ──────────────────────────────────────────────────

    private fun createOffer(viewerId: String) {
        val session = peerSessions[viewerId] ?: return
        session.peerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                // #6 Prefer H.264
                val mungedSdp = SessionDescription(sdp.type, preferH264(sdp.description))
                session.peerConnection.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        signalingClient?.send(JSONObject().apply {
                            put("type", "offer"); put("room", roomName)
                            put("to", viewerId); put("sdp", mungedSdp.description)
                        }.toString())
                    }
                    override fun onSetFailure(e: String?) { Log.e(TAG, "setLocalDesc failed: $e") }
                    override fun onCreateSuccess(p: SessionDescription?) {}
                    override fun onCreateFailure(e: String?) {}
                }, mungedSdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String?) { Log.e(TAG, "createOffer failed: $e") }
            override fun onSetFailure(e: String?) {}
        }, MediaConstraints())
    }

    private fun onAnswer(fromClientId: String, sdp: String) {
        if (fromClientId.isBlank()) return
        val session = peerSessions[fromClientId] ?: return
        session.peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                session.remoteDescriptionSet = true
                flushPendingIce(session)
            }
            override fun onSetFailure(e: String?) { Log.e(TAG, "setRemoteDesc failed: $e") }
            override fun onCreateSuccess(p: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun onIceCandidate(fromClientId: String, candidate: JSONObject) {
        if (fromClientId.isBlank()) return
        val session = peerSessions[fromClientId] ?: return
        val ice = IceCandidate(
            candidate.getString("sdpMid"),
            candidate.getInt("sdpMLineIndex"),
            candidate.getString("candidate"),
        )
        if (!session.remoteDescriptionSet) {
            session.pendingRemoteIceCandidates.add(ice)
        } else {
            session.peerConnection.addIceCandidate(ice)
        }
    }

    private fun flushPendingIce(session: PeerSession) {
        if (!session.remoteDescriptionSet) return
        while (session.pendingRemoteIceCandidates.isNotEmpty()) {
            session.peerConnection.addIceCandidate(session.pendingRemoteIceCandidates.removeFirst())
        }
    }

    // ── #5 Cry detection signal ───────────────────────────────────────────────

    private fun sendCryDetectedSignal() {
        try {
            signalingClient?.send(JSONObject().apply {
                put("type", "cry_detected")
                put("room", roomName)
            }.toString())
            Log.d(TAG, "cry_detected sent to signaling server")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send cry_detected", e)
        }
    }

    // ── #6 H.264 SDP munging ─────────────────────────────────────────────────

    private fun preferH264(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val h264Types = lines
            .filter { it.startsWith("a=rtpmap:") && it.contains("H264", ignoreCase = true) }
            .mapNotNull { Regex("a=rtpmap:(\\d+)").find(it)?.groupValues?.get(1) }
        if (h264Types.isEmpty()) return sdp

        for (i in lines.indices) {
            if (lines[i].startsWith("m=video ")) {
                val parts = lines[i].split(" ").toMutableList()
                if (parts.size < 4) break
                val prefix   = parts.take(3)
                val payloads = parts.drop(3)
                val h264First = h264Types.filter { it in payloads }
                val others    = payloads.filter { it !in h264Types }
                lines[i] = (prefix + h264First + others).joinToString(" ")
                break
            }
        }
        return lines.joinToString("\r\n")
    }

    // ── #10 Adaptive bitrate ──────────────────────────────────────────────────

    private fun adjustBitrate() {
        val session = peerSessions.values.firstOrNull() ?: return
        session.peerConnection.getStats { report ->
            var packetsSent = 1L
            var packetsLost = 0L
            report.statsMap.values.forEach { stat ->
                if (stat.type == "remote-inbound-rtp") {
                    (stat.members["packetsLost"] as? Double)?.let { packetsLost += it.toLong() }
                }
                if (stat.type == "outbound-rtp" &&
                    stat.members["kind"]?.toString() == "video") {
                    (stat.members["packetsSent"] as? Double)?.let { packetsSent += it.toLong() }
                }
            }
            val lossRate = packetsLost.toFloat() / packetsSent.coerceAtLeast(1)
            val targetBps = when {
                lossRate > 0.10f -> 300_000
                lossRate > 0.05f -> 800_000
                else             -> 2_000_000
            }
            Log.d(TAG, "Adaptive bitrate: lossRate=${"%.2f".format(lossRate*100)}% → ${targetBps/1000} kbps")
            applyMaxBitrate(targetBps)
        }
    }

    private fun applyMaxBitrate(bps: Int) {
        peerSessions.values.forEach { session ->
            session.peerConnection.senders.forEach { sender ->
                if (sender.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    val params = sender.parameters
                    params.encodings?.forEach { it.maxBitrateBps = bps }
                    sender.parameters = params
                }
            }
        }
    }

    // ── Session cleanup ───────────────────────────────────────────────────────

    private fun closeViewerSession(viewerId: String) {
        val session = peerSessions.remove(viewerId) ?: return
        try { session.peerConnection.close() } catch (_: Exception) {}
        session.pendingRemoteIceCandidates.clear()
    }

    private fun closeAllPeerSessions() {
        peerSessions.keys.toList().forEach(::closeViewerSession)
    }

    // ── Stop stream ───────────────────────────────────────────────────────────

    private fun stopStream() {
        if (!sessionActive) { binding.btnToggle.text = "Start"; return }
        binding.tvStatus.text = "Stopping..."
        userStopped = true

        reconnectHandler.removeCallbacksAndMessages(null)
        pingHandler.removeCallbacks(pingRunnable)
        bitrateHandler.removeCallbacks(bitrateRunnable)

        // #5 Stop cry detector
        cryDetector?.stop(); cryDetector = null

        // #8 Stop motion detector
        motionDetector?.stop(); motionDetector = null

        try { signalingClient?.close() } catch (_: Exception) {}
        signalingClient = null
        closeAllPeerSessions()
        localClientId = ""

        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        try { videoCapturer?.dispose() }    catch (_: Exception) {}
        videoCapturer = null

        try { localVideoTrack?.dispose() } catch (_: Exception) {}
        try { localAudioTrack?.dispose() } catch (_: Exception) {}
        localVideoTrack = null
        localAudioTrack = null

        binding.btnMute.isEnabled = false
        binding.btnMute.text = "Mute"

        try { binding.localView.release() } catch (_: Exception) {}
        try { eglBase?.release() }          catch (_: Exception) {}
        eglBase = null

        try { if (::peerConnectionFactory.isInitialized) peerConnectionFactory.dispose() } catch (_: Exception) {}

        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
        stopService(Intent(this, StreamingForegroundService::class.java))

        binding.tvStatus.text = "Status: Stopped"
        binding.btnToggle.text = "Start"
        sessionActive = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (sessionActive) stopStream()
    }
}
