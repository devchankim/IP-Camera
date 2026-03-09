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
 * WebRTC Camera (offerer) Activity
 * - Connects to signaling server (ws://<IP>:8081)
 * - Captures camera+mic
 * - Sends offer → receives answer → streams P2P to browser
 * - Uses foreground service for 24/7 operation
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
    private val roomName = "baby"
    private var signalingToken = ""
    private var signalingServerAddress: String = ""
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var userStopped = false
    private var sessionActive = false
    private var allowStunFallback = false
    private var cameraFacingPref = "back"
    private var qualityPref = "medium"
    private var localClientId: String = ""

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

    private data class PeerSession(
        val viewerId: String,
        val peerConnection: PeerConnection,
        @Volatile var remoteDescriptionSet: Boolean = false,
        val pendingRemoteIceCandidates: ArrayDeque<IceCandidate> = ArrayDeque(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        EdgeToEdge.setDecorFitsSystemWindows(window, fitSystemWindows = false)
        EdgeToEdge.enableImmersiveMode(window)

        binding = WebrtcStreamActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        EdgeToEdge.setInsetsHandler(
            root = binding.root,
            handler = StreamActivityInsetsHandler { systemBarInsets ->
                // Adjust bottom margin for button
                binding.btnToggle.setPadding(0, 0, 0, systemBarInsets.bottom + 20)
            }
        )

        // Read server IP + token from preferences
        val prefs = SettingsPreferences(applicationContext)
        val serverAddress = prefs.getIpAddress() ?: "192.168.0.101:8081"
        signalingToken = prefs.getSignalingToken() ?: ""
        allowStunFallback = prefs.isStunFallbackEnabled()
        cameraFacingPref = prefs.getCameraFacing()
        qualityPref = prefs.getQualityPreset()
        signalingServerAddress = serverAddress

        binding.tvStatus.text = "Status: Not connected"

        binding.btnBack.setOnClickListener {
            // Ensure resources are released before leaving.
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
                    startStream(serverAddress)
                } else {
                    requestPermissions()
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val basePerms = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        
        // Android 13+ requires POST_NOTIFICATIONS for foreground service
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
                val serverAddress = prefs.getIpAddress() ?: "192.168.0.101:8081"
                signalingToken = prefs.getSignalingToken() ?: ""
                allowStunFallback = prefs.isStunFallbackEnabled()
                cameraFacingPref = prefs.getCameraFacing()
                qualityPref = prefs.getQualityPreset()
                signalingServerAddress = serverAddress
                startStream(serverAddress)
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startStream(serverAddress: String) {
        sessionActive = true
        binding.btnToggle.text = "Stop"
        binding.tvStatus.text = "Starting..."
        userStopped = false
        reconnectAttempts = 0
        signalingServerAddress = serverAddress

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

        // Video capturer (front camera)
        videoCapturer = createCameraCapturer(Camera2Enumerator(this), cameraFacingPref)
        if (videoCapturer == null) {
            Toast.makeText(this, "Failed to open camera", Toast.LENGTH_SHORT).show()
            return
        }

        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        val videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer!!.initialize(surfaceTextureHelper, this, videoSource.capturerObserver)

        val powerManager = getSystemService(PowerManager::class.java)
        val effectivePreset = if (powerManager?.isPowerSaveMode == true) "low" else qualityPref
        val (w, h, fps) = when (effectivePreset) {
            "low" -> Triple(480, 360, 15)
            "high" -> Triple(1280, 720, 30)
            else -> Triple(640, 480, 24)
        }
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

        // Connect to signaling
        connectSignaling(serverAddress)
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

                // Send join
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
                        "joined" -> onJoined(json.optString("clientId", ""))
                        "peer_joined" -> onPeerJoined(
                            json.optString("role", ""),
                            json.optString("clientId", "")
                        )
                        "peer_left" -> onPeerLeft(
                            json.optString("role", ""),
                            json.optString("clientId", "")
                        )
                        "answer" -> onAnswer(
                            json.optString("from", ""),
                            json.getString("sdp")
                        )
                        "ice" -> onIceCandidate(
                            json.optString("from", ""),
                            json.getJSONObject("candidate")
                        )
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
                closeAllPeerSessions()
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

    private fun onJoined(clientId: String) {
        localClientId = clientId
        runOnUiThread {
            binding.tvStatus.text = "Joined room, waiting for viewer..."
        }
    }

    private fun onPeerJoined(role: String, clientId: String) {
        if (role != "viewer" || clientId.isBlank()) {
            return
        }

        runOnUiThread {
            binding.tvStatus.text = "Viewer connected (${peerSessions.size + 1}), creating offer..."
        }

        if (peerSessions.containsKey(clientId)) {
            return
        }

        try {
            val session = createPeerSession(clientId)
            if (session == null) {
                runOnUiThread {
                    binding.tvStatus.text = "Failed to prepare session for viewer"
                }
                return
            }
            createOffer(session.viewerId)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start WebRTC session for viewer=$clientId", t)
            runOnUiThread {
                binding.tvStatus.text = "WebRTC error: ${t.javaClass.simpleName}"
                Toast.makeText(this, "WebRTC failed: ${t.message}", Toast.LENGTH_LONG).show()
            }
            closeViewerSession(clientId)
        }
    }

    private fun onPeerLeft(role: String, clientId: String) {
        if (role != "viewer" || clientId.isBlank()) {
            return
        }
        closeViewerSession(clientId)
        runOnUiThread {
            binding.tvStatus.text = if (peerSessions.isEmpty()) {
                "Joined room, waiting for viewer..."
            } else {
                "Viewer disconnected, active viewers: ${peerSessions.size}"
            }
        }
    }

    private fun createPeerSession(viewerId: String): PeerSession? {
        peerSessions[viewerId]?.let { return it }

        val iceServers = if (allowStunFallback) {
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
        } else {
            emptyList()
        }

        // Keep RTCConfiguration minimal for broad device compatibility.
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                Log.d(TAG, "onIceCandidate(viewer=$viewerId): ${candidate.sdp}")
                val iceMsg = JSONObject().apply {
                    put("type", "ice")
                    put("room", roomName)
                    put("to", viewerId)
                    put("candidate", JSONObject().apply {
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("candidate", candidate.sdp)
                    })
                }
                signalingClient?.send(iceMsg.toString())
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "onIceGatheringChange: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "onIceConnectionChange(viewer=$viewerId): $state")
                runOnUiThread {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            binding.tvStatus.text = "Streaming to ${peerSessions.size} viewer(s)"
                            binding.btnToggle.text = "Stop"
                            streamingService?.updateNotification("Streaming active")
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED -> {
                            closeViewerSession(viewerId)
                            binding.tvStatus.text = if (peerSessions.isEmpty()) {
                                "Connection lost, waiting for viewer..."
                            } else {
                                "Viewer connection lost, active viewers: ${peerSessions.size}"
                            }
                            streamingService?.updateNotification("Connection lost")
                        }
                        else -> {}
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, "onConnectionChange: $newState")
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })

        if (peerConnection == null) {
            return null
        }

        val videoTrack = localVideoTrack ?: return null
        val audioTrack = localAudioTrack ?: return null

        // Add local tracks to each viewer-specific peer connection.
        peerConnection.addTrack(videoTrack, listOf("stream0"))
        peerConnection.addTrack(audioTrack, listOf("stream0"))

        val session = PeerSession(viewerId = viewerId, peerConnection = peerConnection)
        peerSessions[viewerId] = session
        return session
    }

    private fun createOffer(viewerId: String) {
        val session = peerSessions[viewerId] ?: return
        val constraints = MediaConstraints()

        session.peerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                session.peerConnection.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "setLocalDescription success viewer=$viewerId")
                        val offerMsg = JSONObject().apply {
                            put("type", "offer")
                            put("room", roomName)
                            put("to", viewerId)
                            put("sdp", sdp.description)
                        }
                        signalingClient?.send(offerMsg.toString())
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "setLocalDescription failed viewer=$viewerId: $error")
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "createOffer failed viewer=$viewerId: $error")
            }

            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private fun onAnswer(fromClientId: String, sdp: String) {
        if (fromClientId.isBlank()) {
            Log.w(TAG, "Received answer without from client id")
            return
        }
        val session = peerSessions[fromClientId] ?: run {
            Log.w(TAG, "No session for answer from viewer=$fromClientId")
            return
        }
        Log.d(TAG, "Received answer from viewer=$fromClientId")
        val answerSdp = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        session.peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "setRemoteDescription (answer) success viewer=$fromClientId")
                session.remoteDescriptionSet = true
                flushPendingRemoteIceCandidates(session)
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription (answer) failed viewer=$fromClientId: $error")
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, answerSdp)
    }

    private fun onIceCandidate(fromClientId: String, candidate: JSONObject) {
        if (fromClientId.isBlank()) {
            Log.w(TAG, "Received ICE without from client id")
            return
        }
        val session = peerSessions[fromClientId] ?: run {
            Log.w(TAG, "No session for ICE from viewer=$fromClientId")
            return
        }
        val iceCandidate = IceCandidate(
            candidate.getString("sdpMid"),
            candidate.getInt("sdpMLineIndex"),
            candidate.getString("candidate")
        )
        if (!session.remoteDescriptionSet) {
            session.pendingRemoteIceCandidates.add(iceCandidate)
            Log.d(TAG, "Queued remote ICE candidate viewer=$fromClientId")
            return
        }
        session.peerConnection.addIceCandidate(iceCandidate)
        Log.d(TAG, "Added remote ICE candidate viewer=$fromClientId")
    }

    private fun flushPendingRemoteIceCandidates(session: PeerSession) {
        if (!session.remoteDescriptionSet) return
        while (session.pendingRemoteIceCandidates.isNotEmpty()) {
            val candidate = session.pendingRemoteIceCandidates.removeFirst()
            session.peerConnection.addIceCandidate(candidate)
            Log.d(TAG, "Added queued remote ICE candidate viewer=${session.viewerId}")
        }
    }

    private fun closeViewerSession(viewerId: String) {
        val session = peerSessions.remove(viewerId) ?: return
        try {
            session.peerConnection.close()
        } catch (_: Exception) {
        }
        session.pendingRemoteIceCandidates.clear()
    }

    private fun closeAllPeerSessions() {
        val viewerIds = peerSessions.keys.toList()
        viewerIds.forEach { viewerId ->
            closeViewerSession(viewerId)
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

        closeAllPeerSessions()
        localClientId = ""

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
