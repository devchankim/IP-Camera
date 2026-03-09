import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant

/**
 * Minimal HTTP static file server (classpath resources).
 *
 * Extra endpoints:
 *  GET /health  → {"status":"ok","uptime":...}   (#3 health check)
 *
 * Logs every request via AccessLogger (#17).
 */
class StaticFileServer(private val port: Int = 8080) {

    private val startTime = Instant.now()

    fun start() {
        val server = HttpServer.create(InetSocketAddress(port), 0)

        // ── /health  (#3) ────────────────────────────────────────────────────
        server.createContext("/health") { exchange ->
            val ip     = exchange.remoteAddress?.address?.hostAddress ?: "?"
            val uptime = Instant.now().epochSecond - startTime.epochSecond
            val body   = """{"status":"ok","uptime":$uptime}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            AccessLogger.log(ip, "GET /health")
        }

        // ── static files ──────────────────────────────────────────────────────
        server.createContext("/") { exchange ->
            val ip   = exchange.remoteAddress?.address?.hostAddress ?: "?"
            val path = exchange.requestURI.path.trimStart('/')
            val resourceName = if (path.isEmpty()) "webrtc_viewer.html" else path

            val resource = StaticFileServer::class.java.classLoader.getResourceAsStream(resourceName)

            if (resource == null) {
                val response = "Not Found".toByteArray()
                exchange.sendResponseHeaders(404, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
                AccessLogger.log(ip, "GET /$resourceName", "404")
            } else {
                val bytes = resource.readBytes()
                val contentType = when {
                    resourceName.endsWith(".html") -> "text/html; charset=utf-8"
                    resourceName.endsWith(".js")   -> "application/javascript"
                    resourceName.endsWith(".css")  -> "text/css"
                    resourceName.endsWith(".svg")  -> "image/svg+xml"
                    else -> "application/octet-stream"
                }
                exchange.responseHeaders.add("Content-Type", contentType)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                AccessLogger.log(ip, "GET /$resourceName", "200")
            }
        }

        server.executor = null
        server.start()
        println("Static file server: started on http://0.0.0.0:$port")
    }
}
