package ru.feryafox.shiori.diagnostics

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.CompletableFuture
import org.json.JSONObject

/** Debug-only stock-ksync endpoint experiment. Never accepts arbitrary target IDs. */
class NativeClusterLab(context: Context) {
    private val context: Context

    init {
        this.context = context
    }

    @Throws(java.lang.Exception::class)
    private fun cluster(): JSONObject {
        return NativeSystemConfigProbe().read()
    }

    @Throws(java.lang.Exception::class)
    private fun library(): JSONObject {
        val identities: MutableList<String> = ArrayList<String>()
        var documents: Int = 0
        context
            .getContentResolver()
            .query(
                NativeNotes.Companion.NOTES,
                arrayOf<String>("uniqueId", "type", "status"),
                null,
                null,
                null,
            )
            .use { c ->
                if (c == null) throw IOException("No native metadata cursor")
                while (c.moveToNext()) {
                    identities.add(c.getString(0) + ":" + c.getInt(1) + ":" + c.getInt(2))
                    if (c.getInt(1) == 1) documents++
                }
            }
        Collections.sort<String?>(identities)
        return JSONObject()
            .put("rows", identities.size)
            .put("documents", documents)
            .put(
                "identityStatusSha256",
                NativeInk.digest(
                    java.lang.String.join("\n", identities).toByteArray(StandardCharsets.UTF_8)
                ),
            )
    }

    @Throws(java.lang.Exception::class)
    fun snapshot(): JSONObject {
        val config: JSONObject = cluster()
        return JSONObject()
            .put("clusterId", config.getString("curClusterId"))
            .put("configurationSource", "live ordinary-app system-service read")
            .put("availableClusterIds", config.getJSONObject("clusterMap").names())
            .put("library", library())
    }

    @Throws(java.lang.Exception::class)
    fun baseline(): JSONObject {
        val baseline: File = File(context.getFilesDir(), "cluster-baseline.json")
        if (baseline.exists())
            return JSONObject().put("alreadyRecorded", true).put("current", snapshot())
        val config: JSONObject = cluster()
        if (NativeClusterLab.Companion.LAB_ID == config.getString("curClusterId"))
            throw IOException("Already on lab cluster without baseline")
        val report: JSONObject = snapshot().put("clusterConfiguration", config)
        ReportFiles.write(context, "cluster-baseline.json", report)
        return snapshot().put("baselineRecorded", true)
    }

    @Throws(java.lang.Exception::class)
    fun update(): JSONObject {
        return call(81, null).put("current", snapshot())
    }

    @Throws(java.lang.Exception::class)
    fun change(restore: Boolean): JSONObject {
        val baseline: File = File(context.getFilesDir(), "cluster-baseline.json")
        if (!baseline.isFile()) throw IOException("Record cluster baseline first")
        val before: JSONObject =
            JSONObject(
                String(java.nio.file.Files.readAllBytes(baseline.toPath()), StandardCharsets.UTF_8)
            )
        val target: String =
            if (restore) before.getString("clusterId") else NativeClusterLab.Companion.LAB_ID
        val config: JSONObject = cluster()
        if (!config.getJSONObject("clusterMap").has(target))
            throw IOException("Target missing from native cluster list")
        if (
            !restore &&
                !(before.getJSONObject("library").getString("identityStatusSha256") ==
                    library().getString("identityStatusSha256"))
        ) {
            // A previous isolated round trip added an explicitly allowlisted lab notebook.
            // Require the existing ownership/status baseline for every other row.
            if (
                !File(context.getFilesDir(), "gateway-preservation-baseline.json").isFile() ||
                    !NativeGatewayLab(context).preservation().getBoolean("otherRowsPreserved")
            )
                throw IOException(
                    "Library identities changed outside preserved lab fixtures; inspect before switch"
                )
        }
        ReportFiles.write(
            context,
            "cluster-transition.json",
            JSONObject().put("target", target).put("phase", "requested"),
        )
        val result: JSONObject =
            call(80, target)
                .put("library", library())
                .put("requestedTarget", target)
                .put("configurationObservationRequiresRefresh", true)
        val preserved: Boolean =
            (before.getJSONObject("library").getString("identityStatusSha256") ==
                result.getJSONObject("library").getString("identityStatusSha256"))
        result.put("libraryIdentitiesAndStatusesPreserved", preserved)
        ReportFiles.write(context, "cluster-transition.json", result)
        return result
    }

    @Throws(java.lang.Exception::class)
    private fun call(transaction: Int, id: String?): JSONObject {
        val result: CompletableFuture<JSONObject> = CompletableFuture<JSONObject>()
        val callback: Binder =
            object : Binder() {
                @Throws(RemoteException::class)
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    if (code == IBinder.INTERFACE_TRANSACTION) {
                        if (reply != null) reply?.writeString(NativeClusterLab.Companion.CALLBACK)
                        return true
                    }
                    if (code != 1) return super.onTransact(code, data, reply, flags)
                    data.enforceInterface(NativeClusterLab.Companion.CALLBACK)
                    try {
                        if (data.readInt() == 0) throw IOException("Empty cluster callback")
                        val callbackId: String? = data.readString()
                        val status: Int = data.readInt()
                        result.complete(
                            JSONObject()
                                .put("callbackStatus", status)
                                .put("success", status == 2)
                                .put(
                                    "callbackClusterId",
                                    if (callbackId == null) JSONObject.NULL else callbackId,
                                )
                        )
                    } catch (e: java.lang.Exception) {
                        result.completeExceptionally(e)
                    }
                    if (reply != null) reply?.writeNoException()
                    return true
                }
            }
        RemoteBinding(
                context,
                "com.onyx.android.ksync",
                "com.onyx.android.ksync.service.KSyncService",
            )
            .use { binding ->
                val binder: IBinder = binding.await()
                if (!(NativeClusterLab.Companion.DESCRIPTOR == binder.getInterfaceDescriptor()))
                    throw IOException("Unexpected ksync Binder")
                val data: Parcel = Parcel.obtain()
                try {
                    data.writeInterfaceToken(NativeClusterLab.Companion.DESCRIPTOR)
                    data.writeInt(1)
                    data.writeString(id)
                    data.writeInt(2)
                    data.writeStrongBinder(callback)
                    if (!binder.transact(transaction, data, null, IBinder.FLAG_ONEWAY))
                        throw IOException("Unsupported cluster transaction")
                } finally {
                    data.recycle()
                }
                return result.get(60, java.util.concurrent.TimeUnit.SECONDS)
            }
    }

    companion object {
        const val LAB_ID: String = "powersync_lab"
        private const val DESCRIPTOR: String =
            "com.onyx.android.sdk.sync.ksync.service.ISyncService"
        private const val CALLBACK: String =
            "com.onyx.android.sdk.sync.ksync.service.SyncClusterCallback"
    }
}
