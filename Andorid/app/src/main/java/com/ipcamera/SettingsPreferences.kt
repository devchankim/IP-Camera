package com.ipcamera

import android.content.Context

class SettingsPreferences(context: Context) {

    companion object {
        private const val IP_KEY              = "ip"
        private const val TOKEN_KEY           = "signaling_token"
        private const val CAMERA_FACING_KEY   = "camera_facing"    // "back" | "front"
        private const val QUALITY_KEY         = "quality"          // "low" | "medium" | "high"
        private const val STUN_FALLBACK_KEY   = "stun_fallback"    // boolean
        // #15 Dynamic room name
        private const val ROOM_NAME_KEY       = "room_name"        // default "baby"
        // #6 Night mode
        private const val NIGHT_MODE_KEY      = "night_mode"       // boolean
        // #5 Cry detection
        private const val CRY_DETECT_KEY      = "cry_detect"       // boolean
        // #8 Motion detection / ntfy
        private const val NTFY_TOPIC_KEY      = "ntfy_topic"       // e.g. "babycam-xk83js"
        private const val NTFY_BASE_URL_KEY   = "ntfy_base_url"    // default "https://ntfy.sh"
        // #9 TURN server
        private const val TURN_URL_KEY        = "turn_url"         // e.g. "turn:my.turn.com:3478"
        private const val TURN_USERNAME_KEY   = "turn_username"
        private const val TURN_CREDENTIAL_KEY = "turn_credential"
    }

    private val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

    // ── Signaling ─────────────────────────────────────────────────────────────
    fun saveIpAddress(ip: String)   = prefs.edit().putString(IP_KEY, ip).apply()
    fun getIpAddress(): String?     = prefs.getString(IP_KEY, "192.168.0.101:8081")

    fun saveSignalingToken(token: String) = prefs.edit().putString(TOKEN_KEY, token).apply()
    fun getSignalingToken(): String?      = prefs.getString(TOKEN_KEY, "")

    // ── Room name (#15) ───────────────────────────────────────────────────────
    fun setRoomName(name: String) = prefs.edit().putString(ROOM_NAME_KEY, name.ifBlank { "baby" }).apply()
    fun getRoomName(): String     = prefs.getString(ROOM_NAME_KEY, "baby")?.ifBlank { "baby" } ?: "baby"

    // ── Camera ───────────────────────────────────────────────────────────────
    fun setCameraFacing(facing: String) = prefs.edit().putString(CAMERA_FACING_KEY, facing).apply()
    fun getCameraFacing(): String       = prefs.getString(CAMERA_FACING_KEY, "back") ?: "back"

    fun setQualityPreset(preset: String) = prefs.edit().putString(QUALITY_KEY, preset).apply()
    fun getQualityPreset(): String       = prefs.getString(QUALITY_KEY, "medium") ?: "medium"

    // ── STUN fallback ─────────────────────────────────────────────────────────
    fun setStunFallbackEnabled(enabled: Boolean) = prefs.edit().putBoolean(STUN_FALLBACK_KEY, enabled).apply()
    fun isStunFallbackEnabled(): Boolean         = prefs.getBoolean(STUN_FALLBACK_KEY, false)

    // ── Night mode (#6) ───────────────────────────────────────────────────────
    fun setNightModeEnabled(enabled: Boolean) = prefs.edit().putBoolean(NIGHT_MODE_KEY, enabled).apply()
    fun isNightModeEnabled(): Boolean         = prefs.getBoolean(NIGHT_MODE_KEY, false)

    // ── Cry detection (#5) ────────────────────────────────────────────────────
    fun setCryDetectEnabled(enabled: Boolean) = prefs.edit().putBoolean(CRY_DETECT_KEY, enabled).apply()
    fun isCryDetectEnabled(): Boolean         = prefs.getBoolean(CRY_DETECT_KEY, true)

    // ── ntfy (#8) ─────────────────────────────────────────────────────────────
    fun setNtfyTopic(topic: String)   = prefs.edit().putString(NTFY_TOPIC_KEY, topic).apply()
    fun getNtfyTopic(): String        = prefs.getString(NTFY_TOPIC_KEY, "") ?: ""

    fun setNtfyBaseUrl(url: String)   = prefs.edit().putString(NTFY_BASE_URL_KEY, url.ifBlank { "https://ntfy.sh" }).apply()
    fun getNtfyBaseUrl(): String      = prefs.getString(NTFY_BASE_URL_KEY, "https://ntfy.sh") ?: "https://ntfy.sh"

    // ── TURN (#9) ─────────────────────────────────────────────────────────────
    fun setTurnUrl(url: String)             = prefs.edit().putString(TURN_URL_KEY, url).apply()
    fun getTurnUrl(): String                = prefs.getString(TURN_URL_KEY, "") ?: ""

    fun setTurnUsername(user: String)       = prefs.edit().putString(TURN_USERNAME_KEY, user).apply()
    fun getTurnUsername(): String           = prefs.getString(TURN_USERNAME_KEY, "") ?: ""

    fun setTurnCredential(cred: String)     = prefs.edit().putString(TURN_CREDENTIAL_KEY, cred).apply()
    fun getTurnCredential(): String         = prefs.getString(TURN_CREDENTIAL_KEY, "") ?: ""
}
