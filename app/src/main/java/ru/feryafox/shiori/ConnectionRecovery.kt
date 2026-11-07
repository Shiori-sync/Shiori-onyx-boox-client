package ru.feryafox.shiori

import org.json.JSONObject

object ConnectionRecovery {
    private val phases =
        setOf(
            "failed",
            "switch_requested",
            "switched",
            "catalog_restore_requested",
            "login_requested",
            "authenticated",
            "enable_requested",
        )

    @JvmStatic
    fun canResume(status: JSONObject): Boolean =
        status.optString("phase") in phases &&
            status.optBoolean("hasBaseline") &&
            status.optBoolean("available") &&
            status.optBoolean("allFilesAccess") &&
            !status.optBoolean("endpointInterrupted") &&
            status.optBoolean("configurationMatches") &&
            status.optBoolean("enrolledClusterSelected") &&
            (!status.optBoolean("accountLoggedIn") || status.optBoolean("identityMatches"))
}
