import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class StaticFileServer(private val port: Int = 8080) {

    fun start() {
        val server = HttpServer.create(InetSocketAddress(port), 0)

        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path.trimStart('/')
            val resourceName = if (path.isEmpty()) "webrtc_viewer.html" else path

            val resource = StaticFileServer::class.java.classLoader.getResourceAsStream(resourceName)

            if (resource == null) {
                val response = "Not Found".toByteArray()
                exchange.sendResponseHeaders(404, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            } else {
                val bytes = resource.readBytes()
                val contentType = when {
                    resourceName.endsWith(".html") -> "text/html; charset=utf-8"
                    resourceName.endsWith(".js") -> "application/javascript"
                    resourceName.endsWith(".css") -> "text/css"
                    resourceName.endsWith(".svg") -> "image/svg+xml"
                    else -> "application/octet-stream"
                }
                exchange.responseHeaders.add("Content-Type", contentType)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }

        server.executor = null
        server.start()
        println("Static file server: started on http://0.0.0.0:$port")
    }
}
