package ru.feryafox.shiori

import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object EndpointProfile {
    // These three stock services do not receive the enrolled account token.
    @JvmField
    val STOCK =
        mapOf(
            "onyxCloudDataHostBaseUrl" to "https://en-data.boox.com",
            "onyxLogHostBaseUrl" to "https://en-lon.boox.com",
            "shopBookUrl" to "https://push.boox.com",
        )

    @JvmStatic
    @Throws(Exception::class)
    fun validate(base: String, profile: JSONObject): JSONObject {
        if (profile.getInt("version") != 1 || profile.toString().length > 65536)
            throw UserError(R.string.error_unsupported_server_profile)
        UUID.fromString(profile.getString("libraryId"))
        UUID.fromString(profile.getString("deviceId"))
        if (!profile.getString("nativeUid").matches(Regex("[a-zA-Z0-9_-]{1,80}")))
            throw UserError(R.string.error_invalid_native_library_identity)
        val cluster = profile.getJSONObject("cluster")
        val id = cluster.getString("clusterId")
        if (!id.matches(Regex("powersync_[a-z0-9]{8,32}")) || id != cluster.getString("lang"))
            throw UserError(R.string.error_invalid_custom_cluster_identity)
        val hosts = cluster.getJSONObject("clusterHost")
        for (key in
            listOf(
                "onyxHostBaseUrl",
                "onyxCloudDataHostBaseUrl",
                "onyxLogHostBaseUrl",
                "onyxContentHostBaseUrl",
                "onyxSend2BooxHostBaseUrl",
                "shopBookUrl",
                "onyxMaterialHostBaseUrl",
                "onyxCouchbaseWSSHost",
                "onyxMessageReplicator",
            )) {
            val value = hosts.getString(key)
            if (value != STOCK[key]) SafeURL.sameOrigin(base, value)
        }
        val oss = cluster.getJSONObject("ossConfig")
        for (key in
            listOf(
                "ossNoteEndPoint",
                "ossDeprecatedEndPoint",
                "ossAIEndPoint",
                "ossLogEndpoint",
                "ossTestEndpoint",
            )) SafeURL.sameOrigin(base, oss.getString(key))
        for (key in
            listOf(
                "ossNoteBucket",
                "ossDeprecatedBucket",
                "ossAIBucket",
                "ossLogBucket",
                "ossTestBucket",
            )) if (!oss.getString(key).matches(Regex("[a-z0-9-]{3,63}")))
            throw UserError(R.string.error_invalid_native_bucket)
        val account = profile.getJSONObject("account")
        if (
            account.getString("account").length > 200 ||
                account.getString("code").length !in 6..128 ||
                profile.getString("companionToken").length < 32
        )
            throw UserError(R.string.error_invalid_enrollment_credentials)
        return profile.put("serverURL", base)
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun marker(profile: JSONObject): JSONObject {
        val cluster = profile.getJSONObject("cluster")
        return JSONObject()
            .put("serverId", cluster.getString("clusterId"))
            .put("lang", cluster.getString("lang"))
            .put("name", cluster.getString("name"))
            .put("region", cluster.getString("region"))
            .put("host", cluster.getJSONObject("clusterHost"))
            .put("ossConfig", cluster.getJSONObject("ossConfig"))
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun updatedEntry(entry: JSONObject, next: JSONObject, changes: JSONArray): JSONObject {
        val updated = JSONObject(entry.toString())
        for (part in listOf("clusterHost", "ossConfig", "couchbase", "messageCouchbase")) {
            val target = next.optJSONObject(part) ?: continue
            val source = entry.optJSONObject(part) ?: JSONObject()
            for (key in target.keys()) if (target.getString(key) != source.opt(key))
                changes.put("$part.$key")
            updated.put(part, JSONObject(target.toString()))
        }
        return updated
    }

    @JvmStatic
    @Throws(Exception::class)
    fun replaceMarker(raw: ByteArray, profile: JSONObject): ByteArray {
        val id = profile.getJSONObject("cluster").getString("clusterId")
        val markers = JSONArray(String(raw, Charsets.UTF_8))
        var at = -1
        for (index in 0 until markers.length()) if (
            id == markers.getJSONObject(index).optString("serverId")
        ) {
            if (at >= 0) throw UserError(R.string.error_marker_lists_this_server_twice)
            at = index
        }
        if (at < 0) throw UserError(R.string.error_marker_no_longer_lists_this_server_inspect)
        markers.put(at, marker(profile))
        return markers.toString().toByteArray(Charsets.UTF_8)
    }
}
