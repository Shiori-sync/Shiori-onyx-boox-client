package ru.feryafox.shiori

import org.json.JSONException
import org.json.JSONObject

/** Compatibility derives from the saved pre-transition schema, never a model allowlist. */
object NativeEndpointSchema {
    const val MATERIAL = "onyxMaterialHostBaseUrl"
    private val required =
        listOf(
            "onyxHostBaseUrl",
            "onyxCloudDataHostBaseUrl",
            "onyxLogHostBaseUrl",
            "onyxContentHostBaseUrl",
            "onyxSend2BooxHostBaseUrl",
            "shopBookUrl",
            "onyxCouchbaseWSSHost",
            "onyxMessageReplicator",
        )

    @JvmStatic
    fun lacksMaterialHost(baselineCluster: JSONObject?): Boolean {
        val entries = baselineCluster?.optJSONObject("clusterMap") ?: return false
        if (entries.length() == 0) return false
        for (key in entries.keys()) {
            val hosts = entries.optJSONObject(key)?.optJSONObject("clusterHost") ?: return false
            if (hosts.has(MATERIAL) || required.any { hosts.opt(it) !is String }) return false
        }
        return true
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun expectedHosts(
        expected: JSONObject,
        actual: JSONObject,
        baselineCluster: JSONObject?,
    ): JSONObject =
        JSONObject(expected.toString()).apply {
            if (!actual.has(MATERIAL) && lacksMaterialHost(baselineCluster)) remove(MATERIAL)
        }

    @JvmStatic
    @Throws(JSONException::class)
    fun hostsMatch(
        expected: JSONObject,
        actual: JSONObject,
        baselineCluster: JSONObject?,
    ): Boolean {
        val comparable = expectedHosts(expected, actual, baselineCluster)
        return comparable.length() == actual.length() &&
            comparable.keys().asSequence().all {
                comparable.opt(it) is String && comparable.getString(it) == actual.opt(it)
            }
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun expectedEntry(
        expected: JSONObject,
        actual: JSONObject,
        baselineCluster: JSONObject?,
    ): JSONObject =
        JSONObject(expected.toString()).apply {
            put(
                "clusterHost",
                expectedHosts(
                    expected.getJSONObject("clusterHost"),
                    actual.getJSONObject("clusterHost"),
                    baselineCluster,
                ),
            )
        }

    /** Firmware may restore exactly the enrolled template host from the existing marker. */
    @JvmStatic
    @Throws(JSONException::class)
    fun installedEntryMatches(
        installed: JSONObject?,
        actual: JSONObject?,
        enrolled: JSONObject?,
        baselineCluster: JSONObject?,
    ): Boolean {
        if (installed == null || actual == null) return false
        val expected = JSONObject(installed.toString())
        val savedHosts = expected.optJSONObject("clusterHost")
        val liveHosts = actual.optJSONObject("clusterHost")
        val enrolledHosts = enrolled?.optJSONObject("clusterHost")
        if (
            savedHosts != null &&
                liveHosts != null &&
                enrolledHosts != null &&
                !savedHosts.has(MATERIAL) &&
                liveHosts.has(MATERIAL) &&
                lacksMaterialHost(baselineCluster) &&
                enrolledHosts.opt(MATERIAL) is String &&
                enrolledHosts.getString(MATERIAL) == liveHosts.opt(MATERIAL)
        ) {
            savedHosts.put(MATERIAL, enrolledHosts.getString(MATERIAL))
        }
        return NativeConfig.canonical(expected) == NativeConfig.canonical(actual)
    }
}
