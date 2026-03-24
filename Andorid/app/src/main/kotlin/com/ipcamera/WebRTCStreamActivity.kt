package com.ipcamera

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
 * WebRTC Camera (offerer) Activity — Self-hosted mode
 * - Starts embedded HTTP + WebSocket servers on the phone
 * - Captures camera+mic
 * - Supports multiple viewers (1:N PeerConnections)
 * - Sends offer → receives answer → streams P2P to browser(s)
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

    // Multi-viewer: viewerClientId → PeerConnection
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val remoteDescriptionSetMap = ConcurrentHashMap<String, Boolean>()
    private val pendingIceCandidatesMap = ConcurrentHashMap<String, ArrayDeque<IceCandidate>>()

    // Bitrate for current quality preset (set in startStream)
    private var maxVideoBitrate = 2_000_000

    // Embedded server (self-hosted mode)
    private var embeddedServerManager: EmbeddedServerManager? = null

    // Signaling
    private var signalingClient: WebSocketClient? = null
    private val roomName = "baby"
    private var signalingToken = ""
    private var signalingServerAddress: String = ""
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var userStopped = false
    private var sessionActive = false
    private var cameraFacingPref = "back"

    // Foreground service
    private var streamingService: StreamingForegroundService? = null
    private var serviceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as StreamingForegroundService.LocalBinder
            streamingService = localBinder.getService()
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

        // Read preferences
        val prefs = SettingsPreferences(applicationContext)
        signalingToken = prefs.getSignalingToken() ?: ""
        cameraFacingPref = prefs.getCameraFacing()

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
                    startStream()
                } else {
                    requestPermissions()
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val basePerms = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            basePerms && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            basePerms
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                val prefs = SettingsPreferences(applicationContext)
                signalingToken = prefs.getSignalingToken() ?: ""
                cameraFacingPref = prefs.getCameraFacing()
                startStream()
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startStream() {
        sessionActive = true
        binding.btnToggle.text = "Stop"
        binding.tvStatus.text = "Starting embedded servers..."
        userStopped = false
        reconnectAttempts = 0

        // Start embedded HTTP + WebSocket servers
        embeddedServerManager = EmbeddedServerManager(this, signalingToken).also { mgr ->
            val viewerUrl = mgr.start()
            if (viewerUrl != null) {
                binding.tvServerInfo.text = "Viewer: $viewerUrl"
                binding.tvServerInfo.visibility = View.VISIBLE
            }
            signalingServerAddress = "127.0.0.1:${mgr.getWsPort()}"
        }

        // Start foreground service
        val serviceIntent = Intent(this, StreamingForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)

        // Initialize WebRTC
        eglBase = EglBase.create()
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        // Video capturer
        videoCapturer = createCameraCapturer(Camera2Enumerator(this), cameraFacingPref)
        if (videoCapturer == null) {
            Toast.makeText(this, "Failed to open camera", Toast.LENGTH_SHORT).show()
            return
        }

        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        val videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer!!.initialize(surfaceTextureHelper, this, videoSource.capturerObserver)

        // Always capture at max quality (1080p, 4Mbps)
        maxVideoBitrate = 4_000_000
        val w = 1920; val h = 1080; val fps = 30
        videoCapturer!!.startCapture(w, h, fps)

        localVideoTrack = peerConnectionFactory.createVideoTrack("video0", videoSource)
        localVideoTrack?.setEnabled(true)

        // Audio
        val audioConstraints = MediaConstraints()
        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource)
        localAudioTrack?.setEnabled(true)
        binding.btnMute.text = "Mute"
        binding.btnMute.isEnabled = true

        // Render local preview
        binding.localView.init(eglBase!!.eglBaseContext, null)
        localVideoTrack?.addSink(binding.localView)

        // Connect to local signaling server
        connectSignaling(signalingServerAddress)
    }

    private fun createCameraCapturer(
        enumerator: CameraEnumerator,
        preferredFacing: String
    ): CameraVideoCapturer? {
        val wantBack = preferredFacing != "front"

        fun tryFacing(isWanted: (String) -> Boolean): CameraVideoCapturer? {
            for (deviceName in enumerator.deviceNames) {
                if (isWanted(deviceName)) {
                    return enumerator.createCapturer(deviceName, null)
                }
            }
            return null
        }

        return if (wantBack) {
            tryFacing { enumerator.isBackFacing(it) } ?: tryFacing { enumerator.isFrontFacing(it) }
        } else {
            tryFacing { enumerator.isFrontFacing(it) } ?: tryFacing { enumerator.isBackFacing(it) }
        }
    }

    private fun connectSignaling(serverAddress: String) {
        binding.tvStatus.text = "Connecting to signaling..."

        val uri = URI("ws://$serverAddress")
        signalingClient = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "Signaling: connected")
                reconnectAttempts = 0
                runOnUiThread {
                    binding.tvStatus.text = "Signaling: connected, joining room..."
                }

                val joinMsg = JSONObject().apply {
                    put("type", "join")
                    put("room", roomName)
                    put("role", "camera")
                    put("token", signalingToken)
                }
                send(joinMsg.toString())
            }

            override fun onMessage(message: String?) {
                Log.d(TAG, "Signaling: $message")
                message ?: return
                try {
                    val json = JSONObject(message)
                    when (json.getString("type")) {
                        "joined" -> onJoined()
                        "peer_joined" -> {
                            val clientId = json.optString("clientId", "")
                            if (clientId.isNotEmpty()) {
                                onPeerJoined(clientId)
                            }
                        }
                        "peer_left" -> {
                            val clientId = json.optString("clientId", "")
                            if (clientId.isNotEmpty()) {
                                onPeerLeft(clientId)
                            }
                        }
                        "answer" -> {
                            val from = json.optString("from", "")
                            onAnswer(from, json.getString("sdp"))
                        }
                        "ice" -> {
                            val from = json.optString("from", "")
                            onRemoteIceCandidate(from, json.getJSONObject("candidate"))
                        }
                        "error" -> {
                            val err = json.optString("message", "Unknown error")
                            runOnUiThread {
                                Toast.makeText(this@WebRTCStreamActivity, "Signaling error: $err", Toast.LENGTH_SHORT).show()
                                binding.tvStatus.text = "Error: $err"
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse signaling message", e)
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "Signaling: closed $reason")
                runOnUiThread {
                    binding.tvStatus.text = "Signaling: disconnected"
                }

                if (!userStopped) {
                    scheduleReconnect()
                }
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "Signaling error", ex)
            }
        }
        signalingClient?.connect()
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) return
        reconnectAttempts += 1
        val delayMs = (1000L * reconnectAttempts * reconnectAttempts).coerceAtMost(10_000L)
        runOnUiThread {
            binding.tvStatus.text = "Reconnecting... ($reconnectAttempts/$maxReconnectAttempts)"
        }
        reconnectHandler.postDelayed({
            if (!userStopped) {
                connectSignaling(signalingServerAddress)
            }
        }, delayMs)
    }

    private fun onJoined() {
        runOnUiThread {
            binding.tvStatus.text = "Joined room, waiting for viewer..."
        }
    }

    // --- Multi-viewer: create a PeerConnection per viewer ---

    private fun onPeerJoined(viewerClientId: String) {
        Log.d(TAG, "Viewer joined: $viewerClientId")
        runOnUiThread {
            val count = peerConnections.size + 1
            binding.tvStatus.text = "Viewer joined ($count connected), creating offer..."
        }
        try {
            createPeerConnectionForViewer(viewerClientId)
            createOfferForViewer(viewerClientId)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start WebRTC session for viewer $viewerClientId", t)
            runOnUiThread {
                Toast.makeText(this, "WebRTC failed: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onPeerLeft(viewerClientId: String) {
        Log.d(TAG, "Viewer left: $viewerClientId")
        try { peerConnections.remove(viewerClientId)?.close() } catch (_: Exception) {}
        remoteDescriptionSetMap.remove(viewerClientId)
        pendingIceCandidatesMap.remove(viewerClientId)
        runOnUiThread {
            val count = peerConnections.size
            binding.tvStatus.text = if (count > 0) "Streaming ($count viewer(s))" else "Joined room, waiting for viewer..."
        }
    }

    private fun createPeerConnectionForViewer(viewerClientId: String) {
        remoteDescriptionSetMap[viewerClientId] = false
        pendingIceCandidatesMap[viewerClientId] = ArrayDeque()

        // Always use STUN for reliability
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                Log.d(TAG, "onIceCandidate [$viewerClientId]: ${candidate.sdp}")
                val iceMsg = JSONObject().apply {
                    put("type", "ice")
                    put("room", roomName)
                    put("to", viewerClientId)
                    put("candidate", JSONObject().apply {
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("candidate", candidate.sdp)
                    })
                }
                signalingClient?.send(iceMsg.toString())
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "onIceConnectionChange [$viewerClientId]: $state")
                runOnUiThread {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            val count = peerConnections.size
                            binding.tvStatus.text = "Streaming ($count viewer(s))"
                            binding.btnToggle.text = "Stop"
                            streamingService?.updateNotification("Streaming to $count viewer(s)")
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            // Try ICE restart before giving up
                            Log.w(TAG, "ICE disconnected [$viewerClientId], attempting restart...")
                            binding.tvStatus.text = "Reconnecting viewer..."
                            attemptIceRestart(viewerClientId)
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            Log.e(TAG, "ICE failed [$viewerClientId], removing")
                            onPeerLeft(viewerClientId)
                        }
                        else -> {}
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })

        // Add local tracks to this viewer's PeerConnection
        pc?.addTrack(localVideoTrack, listOf("stream0"))
        pc?.addTrack(localAudioTrack, listOf("stream0"))

        // Bitrate will be set after setLocalDescription (when encodings are finalized)
        peerConnections[viewerClientId] = pc!!
    }

    private fun createOfferForViewer(viewerClientId: String) {
        val pc = peerConnections[viewerClientId] ?: return
        val constraints = MediaConstraints()

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "setLocalDescription success [$viewerClientId]")

                        // Set bitrate AFTER encodings are finalized
                        applyVideoBitrate(pc)

                        val offerMsg = JSONObject().apply {
                            put("type", "offer")
                            put("room", roomName)
                            put("to", viewerClientId)
                            put("sdp", sdp.description)
                        }
                        signalingClient?.send(offerMsg.toString())
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "setLocalDescription failed [$viewerClientId]: $error")
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "createOffer failed [$viewerClientId]: $error")
            }

            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /** Apply min/max bitrate to the video sender of a PeerConnection. */
    private fun applyVideoBitrate(pc: PeerConnection) {
        pc.senders.forEach { sender ->
            if (sender.track()?.kind() == "video") {
                val params = sender.parameters
                params.encodings?.forEach { encoding ->
                    encoding.maxBitrateBps = maxVideoBitrate
                    encoding.minBitrateBps = maxVideoBitrate / 4  // floor = 25% of max
                }
                // Prefer maintaining resolution over framerate when bandwidth is limited
                params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                sender.parameters = params
                Log.d(TAG, "Bitrate set: min=${maxVideoBitrate/4} max=$maxVideoBitrate")
            }
        }
    }

    /** ICE restart: re-create offer with iceRestart=true to recover a stalled connection. */
    private fun attemptIceRestart(viewerClientId: String) {
        val pc = peerConnections[viewerClientId] ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        remoteDescriptionSetMap[viewerClientId] = false
        pendingIceCandidatesMap[viewerClientId] = ArrayDeque()

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "ICE restart offer sent [$viewerClientId]")
                        applyVideoBitrate(pc)
                        signalingClient?.send(JSONObject().apply {
                            put("type", "offer")
                            put("room", roomName)
                            put("to", viewerClientId)
                            put("sdp", sdp.description)
                        }.toString())
                    }
                    override fun onSetFailure(e: String?) { Log.e(TAG, "ICE restart setLocal failed: $e") }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String?) { Log.e(TAG, "ICE restart createOffer failed: $e") }
            override fun onSetFailure(e: String?) {}
        }, constraints)
    }

    private fun onAnswer(fromClientId: String, sdp: String) {
        Log.d(TAG, "Received answer from $fromClientId")
        val pc = peerConnections[fromClientId] ?: return
        val answerSdp = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "setRemoteDescription success [$fromClientId]")
                remoteDescriptionSetMap[fromClientId] = true
                flushPendingRemoteIceCandidates(fromClientId)
                // Re-apply bitrate after full negotiation
                applyVideoBitrate(pc)
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription failed [$fromClientId]: $error")
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, answerSdp)
    }

    private fun onRemoteIceCandidate(fromClientId: String, candidate: JSONObject) {
        val pc = peerConnections[fromClientId] ?: return
        val iceCandidate = IceCandidate(
            candidate.getString("sdpMid"),
            candidate.getInt("sdpMLineIndex"),
            candidate.getString("candidate")
        )
        if (remoteDescriptionSetMap[fromClientId] != true) {
            pendingIceCandidatesMap.getOrPut(fromClientId) { ArrayDeque() }.add(iceCandidate)
            Log.d(TAG, "Queued remote ICE candidate [$fromClientId]")
            return
        }
        pc.addIceCandidate(iceCandidate)
        Log.d(TAG, "Added remote ICE candidate [$fromClientId]")
    }

    private fun flushPendingRemoteIceCandidates(clientId: String) {
        val pc = peerConnections[clientId] ?: return
        val queue = pendingIceCandidatesMap[clientId] ?: return
        while (queue.isNotEmpty()) {
            val candidate = queue.removeFirst()
            pc.addIceCandidate(candidate)
            Log.d(TAG, "Added queued remote ICE candidate [$clientId]")
        }
    }

    private fun stopStream() {
        if (!sessionActive) {
            binding.btnToggle.text = "Start"
            return
        }
        binding.tvStatus.text = "Stopping..."
        userStopped = true
        reconnectHandler.removeCallbacksAndMessages(null)
        try {
            signalingClient?.close()
        } catch (_: Exception) {
        }
        signalingClient = null

        // Close all viewer PeerConnections
        for ((id, pc) in peerConnections) {
            try { pc.close() } catch (_: Exception) {}
        }
        peerConnections.clear()
        remoteDescriptionSetMap.clear()
        pendingIceCandidatesMap.clear()

        try {
            videoCapturer?.stopCapture()
        } catch (_: Exception) {
        }
        try {
            videoCapturer?.dispose()
        } catch (_: Exception) {
        }
        videoCapturer = null

        try {
            localVideoTrack?.dispose()
        } catch (_: Exception) {
        }
        try {
            localAudioTrack?.dispose()
        } catch (_: Exception) {
        }
        localVideoTrack = null
        localAudioTrack = null
        binding.btnMute.isEnabled = false
        binding.btnMute.text = "Mute"

        try {
            binding.localView.release()
        } catch (_: Exception) {
        }

        try {
            eglBase?.release()
        } catch (_: Exception) {
        }
        eglBase = null

        try {
            if (::peerConnectionFactory.isInitialized) {
                peerConnectionFactory.dispose()
            }
        } catch (_: Exception) {
        }

        // Stop embedded servers
        embeddedServerManager?.stop()
        embeddedServerManager = null
        binding.tvServerInfo.visibility = View.GONE

        // Stop foreground service
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        stopService(Intent(this, StreamingForegroundService::class.java))

        binding.tvStatus.text = "Status: Stopped"
        binding.btnToggle.text = "Start"
        sessionActive = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (sessionActive) {
            stopStream()
        }
    }
}
