package dev.aragonite.powersync

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/** Fixed local-lab endpoints; reversible provisioning entirely in the app UID. */
class NativeEndpointLab(context: Context) {
    private val context: Context
    private val io: NativeSystemConfigProbe = NativeSystemConfigProbe()

    init {
        this.context = context
    }

    @Throws(java.lang.Exception::class)
    fun status(): JSONObject {
        val keyguard: KeyguardManager =
            context.getSystemService<KeyguardManager?>(KeyguardManager::class.java)
        return JSONObject()
            .put("clusterId", io.read().getString("curClusterId"))
            .put("deviceSecure", keyguard.isDeviceSecure())
            .put("deviceLocked", keyguard.isDeviceLocked())
            .put("uid", android.os.Process.myUid())
    }

    private fun state(): File {
        return File(context.getFilesDir(), "endpoint-app-baseline.json")
    }

    @Throws(java.lang.Exception::class)
    private fun baseline(): JSONObject {
        if (!state().isFile()) throw IOException("Provision app endpoint first")
        return JSONObject(
            String(java.nio.file.Files.readAllBytes(state().toPath()), StandardCharsets.UTF_8)
        )
    }

    @Throws(java.lang.Exception::class)
    private fun definition(): JSONObject {
        var address: String = "127.0.0.1"
        var tlsSuffix: String = ""
        var tls: Boolean = false
        var accountTls: Boolean = true
        val override: File = File(context.getFilesDir(), "endpoint-lab-host.json")
        if (override.isFile()) {
            if (override.length() > 256) throw IOException("Lab host definition exceeds bound")
            val requested: JSONObject =
                JSONObject(
                    String(
                        java.nio.file.Files.readAllBytes(override.toPath()),
                        StandardCharsets.UTF_8,
                    )
                )
            address = requested.getString("host")
            tls = requested.optBoolean("https", false)
            if (tls) {
                if (requested.optBoolean("certificateNegativeLab")) {
                    if (!("192.168.8.95" == address) || requested.optInt("tlsPort") != 19443)
                        throw IOException("Fixed certificate-negative LAN target required")
                    tlsSuffix = ":19443"
                    accountTls = requested.optBoolean("accountHttps", true)
                } else if (!address.matches("[a-z0-9-]+\\.trycloudflare\\.com".toRegex()))
                    throw IOException("Disposable tunnel hostname required")
            } else {
                if (!address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}".toRegex()))
                    throw IOException("Numeric lab IPv4 required")
                val parsed: InetAddress = java.net.InetAddress.getByName(address)
                if (
                    parsed.isAnyLocalAddress() ||
                        (!parsed.isSiteLocalAddress() && !parsed.isLoopbackAddress())
                )
                    throw IOException("Private lab address required")
                address = parsed.getHostAddress()
            }
        }
        val http: String =
            if (tls && accountTls) "https://" + address + tlsSuffix
            else "http://" + address + ":18765"
        val ws: String =
            if (tls) "wss://" + address + tlsSuffix + "/neocloud"
            else "ws://" + address + ":18766/neocloud"
        val host: JSONObject = JSONObject()
        val oss: JSONObject = JSONObject()
        for (key in
            arrayOf<String>(
                "onyxHostBaseUrl",
                "onyxCloudDataHostBaseUrl",
                "onyxLogHostBaseUrl",
                "onyxContentHostBaseUrl",
                "onyxSend2BooxHostBaseUrl",
                "shopBookUrl",
                "onyxMaterialHostBaseUrl",
            )) host.put(key, http)
        host.put("onyxCouchbaseWSSHost", ws).put("onyxMessageReplicator", ws)
        for (key in
            arrayOf<String>(
                "ossNoteEndPoint",
                "ossDeprecatedEndPoint",
                "ossAIEndPoint",
                "ossLogEndpoint",
                "ossTestEndpoint",
            )) oss.put(key, http)
        for (key in
            arrayOf<String>(
                "ossNoteBucket",
                "ossDeprecatedBucket",
                "ossAIBucket",
                "ossLogBucket",
                "ossTestBucket",
            )) oss.put(key, "powersync-lab")
        return JSONObject()
            .put("clusterId", NativeClusterLab.Companion.LAB_ID)
            .put("lang", NativeClusterLab.Companion.LAB_ID)
            .put("name", "PowerSync local lab")
            .put("region", "POWERSYNC_LAB")
            .put("clusterHost", host)
            .put("ossConfig", oss)
            .put("couchbase", JSONObject().put("onyxCouchbaseWSSHost", ws))
            .put("messageCouchbase", JSONObject().put("onyxCouchbaseWSSHost", ws))
    }

    @Throws(java.lang.Exception::class)
    fun provision(marker: Boolean): JSONObject {
        val before: JSONObject = io.read()
        if (!("en" == before.getString("curClusterId")))
            throw IOException("Requires original en cluster before provisioning")
        if (!NativeGatewayLab(context).preservation().getBoolean("otherRowsPreserved"))
            throw IOException("Library preservation check failed")
        val test: File = File(Environment.getExternalStorageDirectory(), "onyx_test_server")
        if (!state().exists()) {
            if (
                test.exists() ||
                    before.getJSONObject("clusterMap").has(NativeClusterLab.Companion.LAB_ID)
            )
                throw IOException("Unexpected existing lab setup")
            ReportFiles.write(
                context,
                state().getName(),
                JSONObject()
                    .put("cluster", before)
                    .put("system", io.read(NativeEndpointLab.Companion.SYSTEM))
                    .put("markerOriginallyPresent", false),
            )
        }
        if (marker) {
            if (!Environment.isExternalStorageManager())
                throw IOException("All Files Access required for test definition")
            val d: JSONObject = definition()
            val server: JSONObject =
                JSONObject()
                    .put("serverId", NativeClusterLab.Companion.LAB_ID)
                    .put("lang", NativeClusterLab.Companion.LAB_ID)
                    .put("name", d.getString("name"))
                    .put("region", d.getString("region"))
                    .put("host", d.getJSONObject("clusterHost"))
                    .put("ossConfig", d.getJSONObject("ossConfig"))
            FileOutputStream(test).use { out ->
                out.write(JSONArray().put(server).toString().toByteArray(StandardCharsets.UTF_8))
                out.getFD().sync()
            }
        } else if (test.exists())
            throw IOException("Marker-free experiment requires marker absence")
        before.getJSONObject("clusterMap").put(NativeClusterLab.Companion.LAB_ID, definition())
        io.write(NativeEndpointLab.Companion.CLUSTER, before)
        return JSONObject()
            .put("uid", android.os.Process.myUid())
            .put("provisioned", true)
            .put("markerInstalledByApp", marker)
            .put("liveClusterId", io.read().getString("curClusterId"))
    }

    @Throws(java.lang.Exception::class)
    fun reassert(): JSONObject {
        val d: JSONObject = definition()
        val marker: File = File(Environment.getExternalStorageDirectory(), "onyx_test_server")
        if (marker.exists()) {
            if (baseline().optBoolean("markerOriginallyPresent", true))
                throw IOException("Only app-owned lab marker may be refreshed")
            val server: JSONObject =
                JSONObject()
                    .put("serverId", NativeClusterLab.Companion.LAB_ID)
                    .put("lang", NativeClusterLab.Companion.LAB_ID)
                    .put("name", d.getString("name"))
                    .put("region", d.getString("region"))
                    .put("host", d.getJSONObject("clusterHost"))
                    .put("ossConfig", d.getJSONObject("ossConfig"))
            FileOutputStream(marker).use { out ->
                out.write(JSONArray().put(server).toString().toByteArray(StandardCharsets.UTF_8))
                out.getFD().sync()
            }
        }
        val candidate: JSONObject = baseline().getJSONObject("cluster")
        candidate.getJSONObject("clusterMap").put(NativeClusterLab.Companion.LAB_ID, d)
        candidate.put("curClusterId", NativeClusterLab.Companion.LAB_ID)
        io.write(NativeEndpointLab.Companion.CLUSTER, candidate)
        return JSONObject()
            .put("liveClusterId", io.read().getString("curClusterId"))
            .put("uid", android.os.Process.myUid())
    }

    @Throws(java.lang.Exception::class)
    fun flags(restore: Boolean): JSONObject {
        val original: JSONObject = baseline().getJSONObject("system")
        val current: JSONObject = io.read(NativeEndpointLab.Companion.SYSTEM)
        if (!restore && !(NativeClusterLab.Companion.LAB_ID == io.read().getString("curClusterId")))
            throw IOException("Note enable requires lab cluster")
        for (key in
            arrayOf<String>("key_enable_sync_note_data", "key_enable_sync_reader_data")) current
            .getJSONObject("configMap")
            .put(
                key,
                if (restore) original.getJSONObject("configMap").get(key)
                else (if ("key_enable_sync_note_data" == key) "true" else "false"),
            )
        io.write(NativeEndpointLab.Companion.SYSTEM, current)
        context.sendBroadcast(Intent("com.onyx.action.system.config.changed"))
        return JSONObject().put("restored", restore).put("verified", true)
    }

    @Throws(java.lang.Exception::class)
    fun restore(): JSONObject {
        val saved: JSONObject = baseline()
        if (
            !(saved.getJSONObject("cluster").getString("curClusterId") ==
                io.read().getString("curClusterId"))
        )
            throw IOException("Native switch back required before exact restore")
        io.write(NativeEndpointLab.Companion.CLUSTER, saved.getJSONObject("cluster"))
        flags(true)
        val marker: File = File(Environment.getExternalStorageDirectory(), "onyx_test_server")
        if (marker.exists() && !marker.delete()) throw IOException("Cannot remove app test marker")
        return JSONObject()
            .put("exactClusterRestored", true)
            .put("markerRemoved", !marker.exists())
            .put("uid", android.os.Process.myUid())
    }

    companion object {
        private const val CLUSTER: String = "ONYX_CLUSTER_SYSTEM_KEY"
        private const val SYSTEM: String = "ONYX_SYSTEM_CONFIG_KEY"
    }
}
