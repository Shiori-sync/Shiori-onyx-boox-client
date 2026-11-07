package ru.feryafox.shiori

import org.json.JSONObject

/** Read-only presentation policy: a journal alone cannot establish live health. */
class ConnectionState(@JvmField val snapshot: JSONObject) {
    enum class Screen {
        SETUP,
        ENROLLED,
        CONNECTED,
        ATTENTION,
    }

    @JvmField val server: String = snapshot.optString("serverURL")
    private val phase = snapshot.optString("phase", "not_configured")
    @JvmField val canRestore = snapshot.optBoolean("hasBaseline") && phase != "restored"
    @JvmField val canResume = ConnectionRecovery.canResume(snapshot)
    @JvmField val hasAccess = snapshot.optBoolean("allFilesAccess")
    @JvmField val notesEnabled = snapshot.optJSONObject("flags")?.optString("notes") == "true"
    @JvmField val readerEnabled = snapshot.optJSONObject("flags")?.optString("reader") == "true"
    @JvmField
    val screen =
        when {
            !snapshot.optBoolean("available") || snapshot.optBoolean("endpointInterrupted") ->
                Screen.ATTENTION
            phase == "active" &&
                snapshot.optBoolean("identityMatches") &&
                snapshot.optBoolean("configurationMatches") -> Screen.CONNECTED
            phase != "not_configured" && phase != "restored" -> Screen.ATTENTION
            snapshot.optBoolean("enrollmentReady") -> Screen.ENROLLED
            else -> Screen.SETUP
        }

    fun canEnable(): Boolean =
        screen == Screen.CONNECTED && hasAccess && (!notesEnabled || !readerEnabled)
}
