package dev.aragonite.powersync

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.CompletableFuture
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Notebook operations restricted to a separately journaled disposable lab fixture. */
class NativeGatewayLab(context: Context) {
    private val context: Context
    private val fixture: FixtureRunner

    init {
        this.context = context
        fixture = FixtureRunner(context, "gatewayFixture")
    }

    @Throws(java.lang.Exception::class)
    private fun guard() {
        if (
            !(NativeClusterLab.Companion.LAB_ID ==
                NativeClusterLab(context).snapshot().getString("clusterId"))
        )
            throw IOException("Gateway experiment requires fresh lab cluster observation")
    }

    @Throws(java.lang.Exception::class)
    fun preservation(): JSONObject {
        val fixtureId: String? =
            context.getSharedPreferences("gatewayFixture", 0).getString("fixtureId", null)
        val allowed: MutableSet<String> = HashSet<String>()
        if (fixtureId != null) allowed.add(fixtureId)
        val allowedFile: File = File(context.getFilesDir(), "gateway-allowed-fixtures.json")
        if (allowedFile.isFile()) {
            if (allowedFile.length() > 65536) throw IOException("Fixture allowlist exceeds bound")
            val ids: JSONArray =
                JSONArray(
                    String(
                        java.nio.file.Files.readAllBytes(allowedFile.toPath()),
                        StandardCharsets.UTF_8,
                    )
                )
            for (i in 0..<ids.length()) {
                val id: String = ids.getString(i)
                if (!id.matches("[0-9a-f-]{32,36}".toRegex()))
                    throw IOException("Invalid allowed fixture ID")
                allowed.add(id)
            }
        }
        val rows: MutableList<String> = ArrayList<String>()
        var labRows: Int = 0
        context
            .getContentResolver()
            .query(
                NativeNotes.Companion.NOTES,
                arrayOf<String>("uniqueId", "type", "status", "userId"),
                null,
                null,
                null,
            )
            .use { c ->
                if (c == null) throw IOException("Missing native library cursor")
                while (c.moveToNext()) {
                    if ("powersync-lab" == c.getString(3)) labRows++
                    if (!allowed.contains(c.getString(0)))
                        rows.add(
                            c.getString(0) +
                                ":" +
                                c.getInt(1) +
                                ":" +
                                c.getInt(2) +
                                ":" +
                                c.getString(3)
                        )
                }
            }
        Collections.sort<String?>(rows)
        val report: JSONObject =
            JSONObject()
                .put("otherRows", rows.size)
                .put("labAccountRows", labRows)
                .put(
                    "otherIdentityStatusOwnershipSha256",
                    NativeInk.digest(
                        java.lang.String.join("\n", rows).toByteArray(StandardCharsets.UTF_8)
                    ),
                )
        val baseline: File = File(context.getFilesDir(), "gateway-preservation-baseline.json")
        if (!baseline.exists()) ReportFiles.write(context, baseline.getName(), report)
        val before: JSONObject =
            JSONObject(
                String(java.nio.file.Files.readAllBytes(baseline.toPath()), StandardCharsets.UTF_8)
            )
        return report.put(
            "otherRowsPreserved",
            before.getString("otherIdentityStatusOwnershipSha256") ==
                report.getString("otherIdentityStatusOwnershipSha256"),
        )
    }

    @Throws(java.lang.Exception::class)
    fun create(): JSONObject {
        guard()
        preservation()
        val result: JSONObject = fixture.create()
        if (!("powersync-lab" == fixture.find().optString("userId")))
            throw IOException("Disposable fixture is not owned by lab account")
        return result.put("preservation", preservation())
    }

    @Throws(java.lang.Exception::class)
    fun enable(): JSONObject {
        guard()
        var row: JSONObject = fixture.find()
        if (!("powersync-lab" == row.optString("userId")))
            throw IOException("Fixture is not owned by lab account")
        ReportFiles.write(
            context,
            "gateway-fixture-transition.json",
            JSONObject()
                .put("phase", "enable_requested")
                .put("documentId", row.getString("uniqueId")),
        )
        NativeNotes(context)
            .send(
                "UPDATE_SYNC_STATUS",
                JSONObject().put("documentId", row.getString("uniqueId")).put("noteSyncStatus", 1),
                false,
            )
        for (i in 0..39) {
            row = fixture.find()
            if (row.optInt("noteSyncStatus", -1) == 1) break
            java.lang.Thread.sleep(250)
        }
        if (row.optInt("noteSyncStatus", -1) != 1)
            throw IOException("Fixture sync enable not observed")
        return JSONObject().put("note", row).put("preservation", preservation())
    }

    @Throws(java.lang.Exception::class)
    fun capture(): JSONObject {
        return JSONObject().put("note", fixture.find()).put("preservation", preservation())
    }

    @Throws(java.lang.Exception::class)
    fun rename(): JSONObject {
        guard()
        return fixture.rename().put("preservation", preservation())
    }

    @Throws(java.lang.Exception::class)
    fun disable(): JSONObject {
        return fixture.disableNativeCloud().put("preservation", preservation())
    }

    @Throws(java.lang.Exception::class)
    fun sharedCapture(): JSONObject {
        val file: File = File(context.getFilesDir(), "gateway-allowed-fixtures.json")
        if (!file.isFile() || file.length() > 65536)
            throw IOException("Stage bounded fixture allowlist first")
        val ids: JSONArray =
            JSONArray(
                String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
            )
        val rows: JSONArray = JSONArray()
        for (i in 0..<ids.length()) {
            val id: String = ids.getString(i)
            if (!id.matches("[0-9a-f-]{32,36}".toRegex())) throw IOException("Invalid fixture ID")
            val found: JSONArray =
                NativeNotes(context)
                    .notes("uniqueId=? AND userId=?", arrayOf<String>(id, "powersync-lab"))
            for (j in 0..<found.length()) rows.put(found.getJSONObject(j))
        }
        return JSONObject().put("notes", rows).put("preservation", preservation())
    }

    @Throws(java.lang.Exception::class)
    fun renameShared(): JSONObject {
        guard()
        val captured: JSONObject = sharedCapture()
        val rows: JSONArray = selectedRows()
        if (rows.length() != 1) throw IOException("Expected one shared disposable fixture")
        val row: JSONObject = rows.getJSONObject(0)
        val id: String = row.getString("uniqueId")
        val title: String = "PowerSync fixture shared edit from " + android.os.Build.MODEL
        val bean: JSONObject =
            JSONObject()
                .put("documentId", id)
                .put("title", title)
                .put("parentUniqueId", row.optString("parentUniqueId", ""))
        NativeNotes(context).send("RENAME_NOTE", JSONObject().put("noteBean", bean), false)
        for (i in 0..39) {
            val now: JSONObject = sharedCapture()
            if (title == now.getJSONArray("notes").getJSONObject(0).optString("title"))
                return now.put("sameIdentity", true)
            java.lang.Thread.sleep(250)
        }
        throw IOException("Shared fixture rename not observed")
    }

    @JvmOverloads
    @Throws(java.lang.Exception::class)
    fun syncShared(pull: Boolean = false): JSONObject {
        guard()
        val rows: JSONArray = selectedRows()
        if (rows.length() != 1) throw IOException("Expected one disposable fixture")
        val id: String = rows.getJSONObject(0).getString("uniqueId")
        RemoteBinding(
                context,
                "com.onyx.android.ksync",
                "com.onyx.android.ksync.service.KSyncService",
            )
            .use { binding ->
                val binder: IBinder = binding.await()
                val descriptor: String = "com.onyx.android.sdk.sync.ksync.service.ISyncService"
                if (!(descriptor == binder.getInterfaceDescriptor()))
                    throw IOException("Unexpected sync Binder")
                val p: Parcel = android.os.Parcel.obtain()
                try {
                    p.writeInterfaceToken(descriptor)
                    p.writeInt(1)
                    p.writeString("com.onyx.android.sdk.sync.ksync.KSyncContext")
                    p.writeString("dev.aragonite.powersync")
                    p.writeString("powersync-lab")
                    p.writeString(id)
                    p.writeString(null)
                    p.writeString(JSONArray().put(id).toString())
                    p.writeParcelable(null, 0)
                    p.writeInt(2)
                    p.writeByte(0.toByte())
                    p.writeLong(0)
                    p.writeInt(0)
                    p.writeString(null)
                    p.writeString(null)
                    p.writeByte(0.toByte())
                    p.writeInt(0)
                    p.writeByte(0.toByte())
                    p.writeLong(0)
                    p.writeString(null)
                    p.writeInt(0)
                    p.writeString(null)
                    if (pull) p.writeStrongBinder(null)
                    if (
                        !binder.transact(
                            if (pull) 23 else 25,
                            p,
                            null,
                            android.os.IBinder.FLAG_ONEWAY,
                        )
                    )
                        throw IOException("Unsupported scoped sync transaction")
                } finally {
                    p.recycle()
                }
            }
        return JSONObject().put("requested", true).put("documentId", id).put("completed", false)
    }

    @Throws(java.lang.Exception::class)
    fun exportShared(): JSONObject {
        val rows: JSONArray = selectedRows()
        if (rows.length() != 1) throw IOException("Expected one disposable fixture")
        return fixture.exportRow(rows.getJSONObject(0)).put("preservation", preservation())
    }

    @Throws(java.lang.Exception::class)
    private fun selectedRows(): JSONArray {
        val rows: JSONArray = sharedCapture().getJSONArray("notes")
        val selected: File = File(context.getFilesDir(), "gateway-selected-fixture.json")
        if (!selected.isFile()) return rows
        if (selected.length() > 1024) throw IOException("Selection exceeds bound")
        val id: String =
            JSONObject(
                    String(
                        java.nio.file.Files.readAllBytes(selected.toPath()),
                        StandardCharsets.UTF_8,
                    )
                )
                .getString("documentId")
        val result: JSONArray = JSONArray()
        for (i in 0..<rows.length()) if (id == rows.getJSONObject(i).getString("uniqueId"))
            result.put(rows.getJSONObject(i))
        return result
    }

    @Throws(java.lang.Exception::class)
    fun selectedOpen(): Intent {
        guard()
        val rows: JSONArray = selectedRows()
        if (rows.length() != 1) throw IOException("Expected one disposable fixture")
        return NativeNotes(context).openIntent(rows.getJSONObject(0))
    }

    @Throws(java.lang.Exception::class)
    fun selectedCapture(): JSONObject {
        val rows: JSONArray = selectedRows()
        if (rows.length() != 1) throw IOException("Expected one selected lab fixture")
        return JSONObject().put("note", rows.getJSONObject(0)).put("preservation", preservation())
    }

    @Throws(java.lang.Exception::class)
    fun selectedEnable(): JSONObject {
        guard()
        val row: JSONObject = selectedCapture().getJSONObject("note")
        val id: String = row.getString("uniqueId")
        for (status in intArrayOf(0, 1)) {
            NativeNotes(context)
                .send(
                    "UPDATE_SYNC_STATUS",
                    JSONObject().put("documentId", id).put("noteSyncStatus", status),
                    false,
                )
            var observed: Boolean = false
            for (i in 0..39) {
                if (
                    selectedCapture().getJSONObject("note").optInt("noteSyncStatus", -1) == status
                ) {
                    observed = true
                    break
                }
                java.lang.Thread.sleep(250)
            }
            if (!observed) throw IOException("Selected fixture sync status not observed")
        }
        return selectedCapture()
    }

    @Throws(java.lang.Exception::class)
    fun restartTree(): JSONObject {
        guard()
        RemoteBinding(
                context,
                "com.onyx.android.ksync",
                "com.onyx.android.ksync.service.KSyncService",
            )
            .use { binding ->
                val binder: IBinder = binding.await()
                val descriptor: String = "com.onyx.android.sdk.sync.ksync.service.ISyncService"
                if (!(descriptor == binder.getInterfaceDescriptor()))
                    throw IOException("Unexpected sync Binder")
                val p: Parcel = android.os.Parcel.obtain()
                try {
                    p.writeInterfaceToken(descriptor)
                    p.writeInt(0)
                    if (!binder.transact(29, p, null, android.os.IBinder.FLAG_ONEWAY))
                        throw IOException("Unsupported tree restart")
                } finally {
                    p.recycle()
                }
            }
        return JSONObject().put("requested", true).put("completed", false)
    }

    // Native pending-tree pull; exports records and may advance bookkeeping. Not a full CBL
    // snapshot.
    @Throws(java.lang.Exception::class)
    fun querySelectedTree(): JSONObject {
        guard()
        val id: String = selectedCapture().getJSONObject("note").getString("uniqueId")
        val requested: String = id
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
                    val descriptor: String =
                        "com.onyx.android.sdk.sync.ksync.service.SyncIntentCallback"
                    if (code == android.os.IBinder.INTERFACE_TRANSACTION) {
                        if (reply != null) reply?.writeString(descriptor)
                        return true
                    }
                    if (code != 1) return super.onTransact(code, data, reply, flags)
                    try {
                        data.enforceInterface(descriptor)
                        if (data.readInt() == 0) throw IOException("Empty tree callback")
                        if (!("com.onyx.android.sdk.sync.ksync.KSyncContext" == data.readString()))
                            throw IOException("Unexpected tree context")
                        data.readString()
                        val user: String? = data.readString()
                        val document: String? = data.readString()
                        data.readString()
                        if (!("powersync-lab" == user) || !(requested == document))
                            throw IOException("Tree callback identity mismatch")
                        val inlineContent: String? = data.readString()
                        val payload: ParcelFileDescriptor? =
                            data.readParcelable<ParcelFileDescriptor?>(
                                ParcelFileDescriptor::class.java.getClassLoader()
                            )
                        val status: Int = data.readInt()
                        val completed: Boolean = data.readByte().toInt() != 0
                        data.readLong()
                        val offset: Int = data.readInt()
                        data.readString()
                        data.readString()
                        data.readByte()
                        data.readInt()
                        data.readByte()
                        val total: Long = data.readLong()
                        data.readString()
                        data.readInt()
                        val protoPath: String? = data.readString()
                        if (status != 2) throw IOException("Tree query failed: " + status)
                        val body: JSONObject
                        if (payload == null) {
                            val value: Any? =
                                JSONTokener(if (inlineContent == null) "{}" else inlineContent)
                                    .nextValue()
                            body =
                                if (value is JSONObject) value as JSONObject
                                else JSONObject().put("tinyRecordList", value)
                        } else
                            ParcelFileDescriptor.AutoCloseInputStream(payload).use { stream ->
                                val bytes: ByteArrayOutputStream = ByteArrayOutputStream()
                                val buffer: ByteArray = ByteArray(8192)
                                var count: Int
                                while ((stream.read(buffer).also { count = it }) != -1) {
                                    if (bytes.size() + count > 4194304)
                                        throw IOException("Tree query exceeds bound")
                                    bytes.write(buffer, 0, count)
                                }
                                body =
                                    JSONObject(
                                        String(
                                            bytes.toByteArray(),
                                            java.nio.charset.StandardCharsets.UTF_8,
                                        )
                                    )
                            }
                        ReportFiles.write(
                            context,
                            "gateway-tree-query-private.json",
                            JSONObject()
                                .put("inlineContent", body)
                                .put("protoFilePath", protoPath)
                                .put("offset", offset)
                                .put("totalCount", total)
                                .put("completed", completed),
                        )
                        result.complete(
                            JSONObject()
                                .put("nativeTreePullRequested", true)
                                .put("fullDatabaseSnapshot", false)
                                .put("callbackStatus", status)
                                .put("completed", completed)
                                .put("offset", offset)
                                .put("totalCount", total)
                                .put(
                                    "tinyRecords",
                                    if (body.optJSONArray("tinyRecordList") == null) 0
                                    else body.getJSONArray("tinyRecordList").length(),
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
                val descriptor: String = "com.onyx.android.sdk.sync.ksync.service.ISyncService"
                if (!(descriptor == binder.getInterfaceDescriptor()))
                    throw IOException("Unexpected tree Binder")
                val parcel: Parcel = android.os.Parcel.obtain()
                try {
                    writeTreeQueryArgs(parcel, id, callback)
                    if (!binder.transact(21, parcel, null, android.os.IBinder.FLAG_ONEWAY))
                        throw IOException("Tree query rejected")
                } finally {
                    parcel.recycle()
                }
                return result.get(35, java.util.concurrent.TimeUnit.SECONDS)
            }
    }

    private fun writeTreeQueryArgs(p: Parcel, id: String?, callback: IBinder?) {
        p.writeInterfaceToken("com.onyx.android.sdk.sync.ksync.service.ISyncService")
        p.writeInt(1)
        p.writeString("com.onyx.android.sdk.sync.ksync.KSyncContext")
        p.writeString("dev.aragonite.powersync")
        p.writeString("powersync-lab")
        p.writeString(id)
        p.writeString(null)
        p.writeString(null)
        p.writeParcelable(null, 0)
        p.writeInt(2)
        p.writeByte(0.toByte())
        p.writeLong(0)
        p.writeInt(0)
        p.writeString(null)
        p.writeString(null)
        p.writeByte(0.toByte())
        p.writeInt(0)
        p.writeByte(0.toByte())
        p.writeLong(0)
        p.writeString(null)
        p.writeInt(0)
        p.writeString(null)
        p.writeStrongBinder(callback)
    }
}
