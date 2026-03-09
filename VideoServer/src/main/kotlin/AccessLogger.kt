import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * #17 Simple append-only access log.
 *
 * Log location: env var LOG_PATH (default: access.log in working dir)
 * Format: [timestamp] ip  action  details
 */
object AccessLogger {

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val logFile   = File(System.getenv("LOG_PATH") ?: "access.log")

    @Synchronized
    fun log(ip: String, action: String, details: String = "") {
        val ts   = LocalDateTime.now().format(formatter)
        val line = "[$ts] %-22s %-18s $details\n".format(ip, action)
        print(line)
        try {
            FileWriter(logFile, true).use { it.write(line) }
        } catch (_: Exception) {}
    }
}
