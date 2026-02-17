package com.ipcamera

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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

    // WebRTC
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null

    // Signaling
    private var signalingClient: WebSocketClient? = null
    private val roomName = "baby"
    private var signalingToken = ""

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
                // Adjust bottom margin for button
                binding.btnToggle.setPadding(0, 0, 0, systemBarInsets.bottom + 20)
            }
        )

        // Read server IP + token from preferences
        val prefs = SettingsPreferences(applicationContext)
        val serverAddress = prefs.getIpAddress() ?: "192.168.0.101:8081"
        signalingToken = prefs.getSignalingToken() ?: ""

        binding.tvStatus.text = "Status: Not connected"

        binding.btnToggle.setOnClickListener {
            if (peerConnection != null) {
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
                startStream(serverAddress)
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startStream(serverAddress: String) {
        binding.tvStatus.text = "Initializing WebRTC..."

        // Start foreground service
        val serviceIntent = Intent(this, StreamingForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)

        // Initialize WebRTC
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(
            EglBase.create().eglBaseContext,
            enableIntelVp8Encoder = true,
            enableH264HighProfile = true
        )
        val decoderFactory = DefaultVideoDecoderFactory(EglBase.create().eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        // Video capturer (front camera)
        videoCapturer = createCameraCapturer(Camera2Enumerator(this))
        if (videoCapturer == null) {
            Toast.makeText(this, "Failed to open camera", Toast.LENGTH_SHORT).show()
            return
        }

        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", EglBase.create().eglBaseContext)
        val videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer!!.initialize(surfaceTextureHelper, this, videoSource.capturerObserver)
        videoCapturer!!.startCapture(640, 480, 24)

        localVideoTrack = peerConnectionFactory.createVideoTrack("video0", videoSource)
        localVideoTrack?.setEnabled(true)

        // Audio
        val audioConstraints = MediaConstraints()
        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource)
        localAudioTrack?.setEnabled(true)

        // Render local preview
        binding.localView.init(EglBase.create().eglBaseContext, null)
        localVideoTrack?.addSink(binding.localView)

        // Connect to signaling
        connectSignaling(serverAddress)
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): CameraVideoCapturer? {
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        // Fallback to back camera
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        return null
    }

    private fun connectSignaling(serverAddress: String) {
        binding.tvStatus.text = "Connecting to signaling..."

        val uri = URI("ws://$serverAddress")
        signalingClient = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "Signaling: connected")
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
                        "joined" -> onJoined()
                        "peer_joined" -> onPeerJoined()
                        "answer" -> onAnswer(json.getString("sdp"))
                        "ice" -> onIceCandidate(json.getJSONObject("candidate"))
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
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "Signaling error", ex)
            }
        }
        signalingClient?.connect()
    }

    private fun onJoined() {
        runOnUiThread {
            binding.tvStatus.text = "Joined room, waiting for viewer..."
        }
    }

    private fun onPeerJoined() {
        // Viewer joined, create offer
        runOnUiThread {
            binding.tvStatus.text = "Viewer joined, creating offer..."
        }
        createPeerConnection()
        createOffer()
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            // P2P on tailnet, no STUN/TURN needed
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                Log.d(TAG, "onIceCandidate: ${candidate.sdp}")
                val iceMsg = JSONObject().apply {
                    put("type", "ice")
                    put("room", roomName)
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
                Log.d(TAG, "onIceConnectionChange: $state")
                runOnUiThread {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            binding.tvStatus.text = "Streaming (P2P connected)"
                            binding.btnToggle.text = "Stop"
                            streamingService?.updateNotification("Streaming active")
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED -> {
                            binding.tvStatus.text = "Connection lost"
                            streamingService?.updateNotification("Connection lost")
                        }
                        else -> {}
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, "onConnectionChange: $newState")
            }

            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })

        // Add local tracks
        peerConnection?.addTrack(localVideoTrack, listOf("stream0"))
        peerConnection?.addTrack(localAudioTrack, listOf("stream0"))
    }

    private fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "setLocalDescription success")
                        val offerMsg = JSONObject().apply {
                            put("type", "offer")
                            put("room", roomName)
                            put("sdp", sdp.description)
                        }
                        signalingClient?.send(offerMsg.toString())
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "setLocalDescription failed: $error")
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "createOffer failed: $error")
            }

            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private fun onAnswer(sdp: String) {
        Log.d(TAG, "Received answer")
        val answerSdp = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "setRemoteDescription (answer) success")
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription (answer) failed: $error")
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, answerSdp)
    }

    private fun onIceCandidate(candidate: JSONObject) {
        val iceCandidate = IceCandidate(
            candidate.getString("sdpMid"),
            candidate.getInt("sdpMLineIndex"),
            candidate.getString("candidate")
        )
        peerConnection?.addIceCandidate(iceCandidate)
        Log.d(TAG, "Added remote ICE candidate")
    }

    private fun stopStream() {
        signalingClient?.close()
        signalingClient = null

        peerConnection?.close()
        peerConnection = null

        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null

        localVideoTrack?.dispose()
        localAudioTrack?.dispose()

        binding.localView.release()

        // Stop foreground service
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        stopService(Intent(this, StreamingForegroundService::class.java))

        binding.tvStatus.text = "Status: Stopped"
        binding.btnToggle.text = "Start"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStream()
    }
}
