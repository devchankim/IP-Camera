fun main(args: Array<String>) {

    // Default to WebRTC signaling server.
    // To run legacy MJPEG/WebSocket pipeline, pass: legacy
    if (args.any { it.equals("legacy", ignoreCase = true) }) {
        val cameraServer = CameraServer()

        val viewerServer = ViewerWebSocketServer(object : ViewerWebSocketServer.Listener {
            override fun onConnection(listener: CameraServer.OnFrameAvailable) {
                println("Connection")
                cameraServer.addListener(listener)
            }

            override fun onDisconnection(listener: CameraServer.OnFrameAvailable) {
                println("Disconnection")
                cameraServer.removeListener(listener)
            }
        })

        val mjpegServer = MJpegServer(object: ViewerConnectionListener {
            override fun onConnect(onFrameAvailable: CameraServer.OnFrameAvailable) {
                cameraServer.addListener(onFrameAvailable)
            }

            override fun onDisconnect(onFrameAvailable: CameraServer.OnFrameAvailable) {
                cameraServer.removeListener(onFrameAvailable)
            }
        })

        viewerServer.start()
        mjpegServer.start()
        cameraServer.start()
        return
    }

    SignalingWebSocketServer(bindPort = 8081).start()
}