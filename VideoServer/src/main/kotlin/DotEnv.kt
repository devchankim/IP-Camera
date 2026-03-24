import java.io.File

/**
 * Minimal .env file loader.
 *
 * Reads KEY=VALUE pairs from a .env file in the working directory.
 * Supports:
 *  - Blank lines and # comments (ignored)
 *  - Optional quoted values: KEY="value" or KEY='value'
 *
 * Usage:
 *  DotEnv.load()           // call once at startup
 *  DotEnv.get("MY_KEY")    // returns value from .env, falls back to System.getenv()
 */
object DotEnv {
    private val values = mutableMapOf<String, String>()

    fun load(path: String = ".env") {
        val file = File(path)
        if (!file.exists()) {
            println("DotEnv: $path not found, relying on system environment variables.")
            return
        }
        file.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
            val eqIndex = trimmed.indexOf('=')
            if (eqIndex < 1) return@forEachLine
            val key = trimmed.substring(0, eqIndex).trim()
            var value = trimmed.substring(eqIndex + 1).trim()
            // Strip surrounding quotes
            if (value.length >= 2 &&
                (value.startsWith('"') && value.endsWith('"') ||
                 value.startsWith('\'') && value.endsWith('\''))) {
                value = value.substring(1, value.length - 1)
            }
            values[key] = value
        }
        println("DotEnv: loaded ${values.size} variable(s) from $path")
    }

    /** Returns value from .env first, then falls back to System.getenv(). */
    fun get(key: String): String? = values[key] ?: System.getenv(key)
}
