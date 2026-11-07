package ru.feryafox.shiori.diagnostics

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Binder
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
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import org.json.JSONArray
import org.json.JSONObject

/** Bounded diagnostics and native operations for one exact, locally authored PDF. */
class NativeReaderPdfLab(context: Context) {
    private val context: Context

    init {
        this.context = context
    }

    @Throws(java.lang.Exception::class)
    fun stagePeerMetadata(): JSONObject {
        val cloud: String = "cbd2938779124b2b83413f76dbe8ab12"
        val path: String =
            "/storage/emulated/0/.ksync/powersync-lab/reader/" + cloud + "/book/" + cloud + ".pdf"
        if (
            !("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")) ||
                !("c0e26f46d2f365c13c2cf64bc8efc4b5eb756ba8b899060fdf9222d4d8a566a7" ==
                    NativeInk.digest(java.nio.file.Files.readAllBytes(File(path).toPath())))
        )
            throw IOException("Owned lab PDF required")
        val request: File = File(context.getFilesDir(), "reader-pdf-peer-metadata-request.json")
        if (!request.isFile() || request.length() > 1048576)
            throw IOException("Bounded fixture metadata required")
        val row: JSONObject =
            JSONObject(
                String(
                    java.nio.file.Files.readAllBytes(request.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8,
                )
            )
        if (!(cloud == row.getString("uuid")) || !(path == row.getString("nocasePath")))
            throw IOException("Unexpected peer fixture identity")
        val uri: Uri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")
        context
            .getContentResolver()
            .query(
                uri,
                arrayOf<String>("uuid", "nocasePath"),
                "uuid=? OR nocasePath=?",
                arrayOf<String>(cloud, path),
                null,
            )
            .use { c ->
                if (c == null) throw IOException("Missing peer metadata cursor")
                if (
                    c.getCount() == 1 &&
                        c.moveToFirst() &&
                        cloud == c.getString(0) &&
                        "/storage/emulated/0/Download/PowerSync-reader-geometry.pdf" ==
                            c.getString(1)
                ) {
                    val v: ContentValues = ContentValues()
                    v.put("nocasePath", path)
                    v.put("fileSyncStatus", 0)
                    v.put("userDataSyncStatus", 1)
                    if (
                        context
                            .getContentResolver()
                            .update(
                                uri,
                                v,
                                "uuid=? AND nocasePath=?",
                                arrayOf<String>(cloud, c.getString(1)),
                            ) != 1
                    )
                        throw IOException("Cloud fixture alias relocation failed")
                    return JSONObject()
                        .put("fixtureMetadataStaged", true)
                        .put("cloudFixtureAliasRelocated", true)
                        .put("ordinaryUid", android.os.Process.myUid())
                }
                if (c.getCount() != 0)
                    throw IOException("Peer fixture must be absent before staging")
            }
        val values: ContentValues = ContentValues()
        val keys: MutableIterator<String?> = row.keys()
        while (keys.hasNext()) {
            val key: String? = keys.next()
            val v: Any? = row.get(key)
            if (v === JSONObject.NULL) values.putNull(key)
            else if (v is Number) values.put(key, (v as Number).toLong())
            else if (v is String) values.put(key, v as String)
            else throw IOException("Unsupported fixture column")
        }
        if (context.getContentResolver().insert(uri, values) == null)
            throw IOException("Peer fixture insert failed")
        return JSONObject()
            .put("fixtureMetadataStaged", true)
            .put("ordinaryUid", android.os.Process.myUid())
    }

    @Throws(java.lang.Exception::class)
    fun enrollPeer(): JSONObject {
        val cloud: String = "cbd2938779124b2b83413f76dbe8ab12"
        val local: String = "dd9c0167293c411b9df712f89ff98af6"
        val path: String =
            "/storage/emulated/0/.ksync/powersync-lab/reader/" + cloud + "/book/" + cloud + ".pdf"
        if (
            !("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")) ||
                !("c0e26f46d2f365c13c2cf64bc8efc4b5eb756ba8b899060fdf9222d4d8a566a7" ==
                    NativeInk.digest(java.nio.file.Files.readAllBytes(File(path).toPath())))
        )
            throw IOException("Owned lab peer PDF required")
        val v: ContentValues = ContentValues()
        v.put("uuid", cloud)
        val changed: Int =
            context
                .getContentResolver()
                .update(
                    Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),
                    v,
                    "uuid=? AND nocasePath=?",
                    arrayOf<String>(local, path),
                )
        if (changed != 1) throw IOException("One owned managed peer identity required")
        return JSONObject().put("fixtureIdentityEnrolled", true).put("changedRows", changed)
    }

    @Throws(java.lang.Exception::class)
    fun restoreOwned(): JSONObject {
        val cloud: String = "cbd2938779124b2b83413f76dbe8ab12"
        val baseline: File = File(context.getFilesDir(), "reader-pdf-sync-baseline.json")
        var restored: Int = 0
        var removed: Int = 0
        if (baseline.isFile()) {
            val b: JSONObject =
                JSONObject(
                    String(
                        java.nio.file.Files.readAllBytes(baseline.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8,
                    )
                )
            if (
                !(cloud == b.getString("uuid")) ||
                    !("/storage/emulated/0/Download/PowerSync-reader-geometry.pdf" ==
                        b.getString("nocasePath"))
            )
                throw IOException("Unexpected source fixture baseline")
            val v: ContentValues = ContentValues()
            v.put("userDataSyncStatus", b.getInt("userDataSyncStatus"))
            v.put("fileSyncStatus", b.getInt("fileSyncStatus"))
            restored =
                context
                    .getContentResolver()
                    .update(
                        Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),
                        v,
                        "uuid=? AND nocasePath=?",
                        arrayOf<String>(cloud, b.getString("nocasePath")),
                    )
            if (restored != 1) throw IOException("Source flag restoration failed")
        } else {
            val path: String =
                "/storage/emulated/0/.ksync/powersync-lab/reader/" +
                    cloud +
                    "/book/" +
                    cloud +
                    ".pdf"
            removed =
                context
                    .getContentResolver()
                    .delete(
                        Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),
                        "(uuid=? OR uuid=?) AND nocasePath=?",
                        arrayOf<String>(cloud, "dd9c0167293c411b9df712f89ff98af6", path),
                    )
            if (removed != 1) throw IOException("One owned managed alias cleanup required")
        }
        return JSONObject()
            .put("sourceFlagsRestored", restored)
            .put("ownedManagedAliasesRemoved", removed)
    }

    @Throws(java.lang.Exception::class)
    fun pullFullSource(): JSONObject {
        if (!("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")))
            throw IOException("Full PDF pull requires lab endpoint")
        capture()
        val captured: JSONObject =
            JSONObject(
                String(
                    java.nio.file.Files.readAllBytes(
                        File(context.getFilesDir(), "reader-pdf-private.json").toPath()
                    ),
                    java.nio.charset.StandardCharsets.UTF_8,
                )
            )
        val rows: JSONArray = captured.getJSONArray("metadata")
        val id: String = "cbd2938779124b2b83413f76dbe8ab12"
        if (rows.length() != 1 || !(id == rows.getJSONObject(0).getString("uuid")))
            throw IOException("Exact owned source identity required")
        val row: JSONObject = rows.getJSONObject(0)
        ReportFiles.write(
            context,
            "reader-pdf-full-pull-extra-before.json",
            JSONObject().put("extraInfo", row.get("extraInfo")),
        )
        val extra: JSONObject = JSONObject(row.getString("extraInfo"))
        extra.put("pullFullUserDataFromSync", true)
        val v: ContentValues = ContentValues()
        v.put("extraInfo", extra.toString())
        if (
            context
                .getContentResolver()
                .update(
                    Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),
                    v,
                    "uuid=? AND nocasePath=?",
                    arrayOf<String>(
                        id,
                        "/storage/emulated/0/Download/PowerSync-reader-geometry.pdf",
                    ),
                ) != 1
        )
            throw IOException("One owned full-pull flag update required")
        return syncNative(false, false)
    }

    @Throws(java.lang.Exception::class)
    fun publishMetadata(): JSONObject {
        if (!("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")))
            throw IOException("PDF metadata publication requires lab endpoint")
        capture()
        val data: JSONObject =
            JSONObject(
                String(
                    java.nio.file.Files.readAllBytes(
                        File(context.getFilesDir(), "reader-pdf-private.json").toPath()
                    ),
                    java.nio.charset.StandardCharsets.UTF_8,
                )
            )
        val id: String = "cbd2938779124b2b83413f76dbe8ab12"
        val rows: JSONArray = data.getJSONArray("metadata")
        if (
            rows.length() != 1 ||
                !(id == rows.getJSONObject(0).getString("uuid")) ||
                !("c0e26f46d2f365c13c2cf64bc8efc4b5eb756ba8b899060fdf9222d4d8a566a7" ==
                    data.getString("bookSha256"))
        )
            throw IOException("Exact source PDF metadata required")
        val row: JSONObject = rows.getJSONObject(0)
        val baseline: File = File(context.getFilesDir(), "reader-pdf-sync-baseline.json")
        if (!baseline.exists()) ReportFiles.write(context, baseline.getName(), row)
        val enabled: ContentValues = ContentValues()
        enabled.put("userDataSyncStatus", 1)
        if (
            context
                .getContentResolver()
                .update(
                    Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),
                    enabled,
                    "uuid=? AND nocasePath=?",
                    arrayOf<String>(
                        id,
                        "/storage/emulated/0/Download/PowerSync-reader-geometry.pdf",
                    ),
                ) != 1
        )
            throw IOException("One owned PDF eligibility update required")
        row.put("uniqueId", id)
            .put("user", "powersync-lab")
            .put("dbId", "powersync-lab-READER_LIBRARY")
            .put("modeType", 4)
            .put("userDataSyncStatus", 1)
        val body: JSONObject =
            JSONObject()
                .put("metadataModelList", JSONArray().put(row))
                .put("libraryModelList", JSONArray())
                .put("collectionModelList", JSONArray())
                .put("requestType", "SYNC_DOCUMENT")
                .put("isSyncUserOtherData", false)
        val request: File = File(context.getFilesDir(), "reader-pdf-metadata-request.json")
        ReportFiles.write(context, request.getName(), body)
        android.os.ParcelFileDescriptor.open(
                request,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY,
            )
            .use { fd ->
                RemoteBinding(
                        context,
                        "com.onyx.android.ksync",
                        "com.onyx.android.ksync.service.KSyncService",
                    )
                    .use { binding ->
                        val binder: IBinder = binding.await()
                        if (
                            !("com.onyx.android.sdk.sync.ksync.service.ISyncService" ==
                                binder.getInterfaceDescriptor())
                        )
                            throw IOException("Unexpected PDF metadata interface")
                        val p: Parcel = android.os.Parcel.obtain()
                        try {
                            writeArgs(p, id, null, fd, null)
                            if (!binder.transact(1, p, null, android.os.IBinder.FLAG_ONEWAY))
                                throw IOException("PDF metadata publication rejected")
                        } finally {
                            p.recycle()
                        }
                    }
            }
        return JSONObject().put("requested", true).put("completed", false).put("fixedBookScope", id)
    }

    @Throws(java.lang.Exception::class)
    fun queryFull(): JSONObject {
        return queryFull(false)
    }

    @Throws(java.lang.Exception::class)
    fun queryMetadata(): JSONObject {
        return queryFull(true)
    }

    @Throws(java.lang.Exception::class)
    private fun queryFull(metadataOnly: Boolean): JSONObject {
        if (!("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")))
            throw IOException("PDF query requires lab endpoint")
        capture()
        val id: String = "cbd2938779124b2b83413f76dbe8ab12"
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
                        if (data.readInt() == 0) throw IOException("Empty reader callback")
                        if (!("com.onyx.android.sdk.sync.ksync.KSyncContext" == data.readString()))
                            throw IOException("Unexpected reader context")
                        data.readString()
                        val user: String? = data.readString()
                        val book: String? = data.readString()
                        data.readString()
                        if (!("powersync-lab" == user) || !(requested == book))
                            throw IOException("Reader callback identity mismatch")
                        data.readString()
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
                        data.readString()
                        if (status != 2 || payload == null)
                            throw IOException("Reader query failed: " + status)
                        val body: JSONObject
                        ParcelFileDescriptor.AutoCloseInputStream(payload).use { stream ->
                            val bytes: ByteArrayOutputStream = ByteArrayOutputStream()
                            val buffer: ByteArray = ByteArray(8192)
                            var count: Int
                            while ((stream.read(buffer).also { count = it }) != -1) {
                                if (bytes.size() + count > 4194304)
                                    throw IOException("Reader query exceeds bound")
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
                        ReportFiles.write(context, "reader-pdf-full-userdata-private.json", body)
                        result.complete(
                            JSONObject()
                                .put("readOnly", true)
                                .put("callbackStatus", status)
                                .put("completed", completed)
                                .put("offset", offset)
                                .put("totalCount", total)
                                .put(
                                    "metadataModels",
                                    if (body.optJSONArray("metadataModelList") == null) 0
                                    else body.getJSONArray("metadataModelList").length(),
                                )
                                .put(
                                    "bookmarkModels",
                                    if (body.optJSONArray("bookmarkModelList") == null) 0
                                    else body.getJSONArray("bookmarkModelList").length(),
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
                    throw IOException("Unexpected reader Binder")
                val parcel: Parcel = android.os.Parcel.obtain()
                try {
                    writeArgs(
                        parcel,
                        id,
                        if (metadataOnly) JSONObject().put("documentId", id).toString() else null,
                        null,
                        callback,
                    )
                    if (
                        !binder.transact(
                            if (metadataOnly) 5 else 11,
                            parcel,
                            null,
                            android.os.IBinder.FLAG_ONEWAY,
                        )
                    )
                        throw IOException("Reader query rejected")
                } finally {
                    parcel.recycle()
                }
                return result.get(35, java.util.concurrent.TimeUnit.SECONDS)
            }
    }

    private fun writeArgs(
        p: Parcel,
        id: String?,
        content: String?,
        payload: ParcelFileDescriptor?,
        callback: IBinder?,
    ) {
        p.writeInterfaceToken("com.onyx.android.sdk.sync.ksync.service.ISyncService")
        p.writeInt(1)
        p.writeString("com.onyx.android.sdk.sync.ksync.KSyncContext")
        p.writeString("ru.feryafox.shiori.diagnostics")
        p.writeString("powersync-lab")
        p.writeString(id)
        p.writeString(null)
        p.writeString(content)
        p.writeParcelable(payload, 0)
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

    @Throws(java.lang.Exception::class)
    fun restartLibrary(): JSONObject {
        return startLibrary(false)
    }

    @Throws(java.lang.Exception::class)
    fun startLibrary(scoped: Boolean): JSONObject {
        val io: NativeSystemConfigProbe = NativeSystemConfigProbe()
        if (
            !("powersync_lab" == io.read().getString("curClusterId")) ||
                !("true" ==
                    io.read("ONYX_SYSTEM_CONFIG_KEY")
                        .getJSONObject("configMap")
                        .getString("key_enable_sync_reader_data"))
        )
            throw IOException("PDF library restart requires enabled lab reader sync")
        capture()
        val id: String = "cbd2938779124b2b83413f76dbe8ab12"
        val content: String? =
            JSONObject()
                .put("startAllDoc", false)
                .put("startDocIdList", JSONArray().put(id))
                .toString()
        RemoteBinding(
                context,
                "com.onyx.android.ksync",
                "com.onyx.android.ksync.service.KSyncService",
            )
            .use { binding ->
                val binder: IBinder = binding.await()
                val descriptor: String = "com.onyx.android.sdk.sync.ksync.service.ISyncService"
                if (!(descriptor == binder.getInterfaceDescriptor()))
                    throw IOException("Unexpected PDF library interface")
                val p: Parcel = android.os.Parcel.obtain()
                try {
                    p.writeInterfaceToken(descriptor)
                    p.writeInt(1)
                    p.writeString("com.onyx.android.sdk.sync.ksync.KSyncContext")
                    p.writeString("ru.feryafox.shiori.diagnostics")
                    p.writeString("powersync-lab")
                    p.writeString(id)
                    p.writeString(null)
                    p.writeString(content)
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
                    p.writeStrongBinder(null)
                    if (
                        !binder.transact(
                            if (scoped) 17 else 16,
                            p,
                            null,
                            android.os.IBinder.FLAG_ONEWAY,
                        )
                    )
                        throw IOException("PDF library restart rejected")
                } finally {
                    p.recycle()
                }
            }
        return JSONObject()
            .put("requested", true)
            .put("completed", false)
            .put("requestedBookId", id)
            .put("nativeArgumentsHonored", scoped)
    }

    @JvmOverloads
    @Throws(java.lang.Exception::class)
    fun syncNative(peer: Boolean, pushing: Boolean = !peer): JSONObject {
        if (!("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")))
            throw IOException("PDF sync requires lab endpoint")
        val cloudId: String = "cbd2938779124b2b83413f76dbe8ab12"
        val id: String = cloudId
        val path: String =
            if (peer)
                "/storage/emulated/0/.ksync/powersync-lab/reader/" +
                    cloudId +
                    "/book/" +
                    cloudId +
                    ".pdf"
            else "/storage/emulated/0/Download/PowerSync-reader-geometry.pdf"
        val file: File = File(path)
        if (!file.isFile() || file.length() > 1048576)
            throw IOException("Exact owned PDF required for native sync")
        val pdfDigest: String = NativeInk.digest(java.nio.file.Files.readAllBytes(file.toPath()))
        val annotated: Boolean =
            file.length() == 22562L &&
                "c0e26f46d2f365c13c2cf64bc8efc4b5eb756ba8b899060fdf9222d4d8a566a7" == pdfDigest
        val original: Boolean =
            !peer &&
                file.length() == 2270L &&
                "6ccfeddb1f27d9d62975d78f0ec7d4c3f4c1bb8af69aa9786b5cd3974fa443bd" == pdfDigest
        if (!annotated && !original)
            throw IOException("Exact owned PDF revision required for native sync")
        if (!peer && pushing) {
            capture()
            val captured: JSONObject =
                JSONObject(
                    String(
                        java.nio.file.Files.readAllBytes(
                            File(context.getFilesDir(), "reader-pdf-private.json").toPath()
                        ),
                        java.nio.charset.StandardCharsets.UTF_8,
                    )
                )
            val rows: JSONArray = captured.getJSONArray("metadata")
            if (rows.length() != 1 || !(id == rows.getJSONObject(0).getString("uuid")))
                throw IOException("Owned source PDF identity required")
            val baseline: File = File(context.getFilesDir(), "reader-pdf-sync-baseline.json")
            if (!baseline.exists())
                ReportFiles.write(context, baseline.getName(), rows.getJSONObject(0))
            val values: ContentValues = ContentValues()
            values.put("userDataSyncStatus", 1)
            if (
                context
                    .getContentResolver()
                    .update(
                        Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),
                        values,
                        "uuid=? AND nocasePath=?",
                        arrayOf<String>(id, path),
                    ) != 1
            )
                throw IOException("One source PDF eligibility update required")
        }
        val type: Int = if (pushing) 10001 else 10002
        val done: CompletableFuture<JSONObject> = CompletableFuture<JSONObject>()
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
                        "com.onyx.android.sdk.sync.ksync.reader.client.service.ReaderSyncIntentCallback"
                    if (code == IBinder.INTERFACE_TRANSACTION) {
                        if (reply != null) reply?.writeString(descriptor)
                        return true
                    }
                    if (code != 1) return super.onTransact(code, data, reply, flags)
                    try {
                        data.enforceInterface(descriptor)
                        if (data.readInt() != 1) throw IOException("Empty PDF sync callback")
                        val receivedPath: String? = data.readString()
                        val receivedId: String? = data.readString()
                        val pull: Boolean = data.readByte().toInt() != 0
                        val push: Boolean = data.readByte().toInt() != 0
                        val payload: ParcelFileDescriptor? =
                            data.readParcelable<ParcelFileDescriptor?>(
                                ParcelFileDescriptor::class.java.getClassLoader()
                            )
                        val receivedType: Int = data.readInt()
                        val result: Int = data.readInt()
                        val message: String? = data.readString()
                        val newData: Boolean = data.readByte().toInt() != 0
                        val toId: String? = data.readString()
                        if (payload != null) payload.close()
                        if (!(path == receivedPath) || !(id == receivedId) || type != receivedType)
                            throw IOException("PDF callback scope mismatch")
                        val event: JSONObject =
                            JSONObject()
                                .put("path", receivedPath)
                                .put("fromId", receivedId)
                                .put("toId", toId)
                                .put("result", result)
                                .put("message", message)
                                .put("newData", newData)
                                .put("pullRequested", pull)
                                .put("pushRequested", push)
                        ReportFiles.write(context, "reader-pdf-sync-callback-private.json", event)
                        done.complete(event)
                    } catch (e: java.lang.Exception) {
                        done.completeExceptionally(e)
                    }
                    if (reply != null) reply?.writeNoException()
                    return true
                }
            }
        RemoteBinding(
                context,
                "com.onyx.kreader",
                "com.onyx.android.sdk.readerview.service.ReaderSyncService",
            )
            .use { binding ->
                val binder: IBinder = binding.await()
                val descriptor: String =
                    "com.onyx.android.sdk.sync.ksync.reader.client.service.IReaderSyncService"
                if (!(descriptor == binder.getInterfaceDescriptor()))
                    throw IOException("Unexpected native PDF sync interface")
                val p: Parcel = android.os.Parcel.obtain()
                try {
                    p.writeInterfaceToken(descriptor)
                    p.writeInt(1)
                    p.writeString(path)
                    p.writeString(id)
                    p.writeByte((if (pushing) 0 else 1).toByte())
                    p.writeByte((if (pushing) 1 else 0).toByte())
                    p.writeParcelable(null, 0)
                    p.writeInt(type)
                    p.writeInt(0)
                    p.writeString(null)
                    p.writeByte(0.toByte())
                    p.writeString(null)
                    p.writeStrongBinder(callback)
                    if (!binder.transact(3, p, null, android.os.IBinder.FLAG_ONEWAY))
                        throw IOException("Native PDF sync transaction rejected")
                } finally {
                    p.recycle()
                }
                return JSONObject()
                    .put("callerUid", android.os.Process.myUid())
                    .put("callback", done.get(40, java.util.concurrent.TimeUnit.SECONDS))
                    .put("nativeInkQualified", false)
            }
    }

    @Throws(java.lang.Exception::class)
    fun transfer(upload: Boolean): JSONObject {
        if (!("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")))
            throw IOException("PDF transfer requires lab endpoint")
        capture()
        val request: File = File(context.getFilesDir(), "reader-pdf-transfer-request.json")
        if (!request.isFile() || request.length() > 1048576)
            throw IOException("Bounded PDF request required")
        val body: JSONObject =
            JSONObject(
                String(
                    java.nio.file.Files.readAllBytes(request.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8,
                )
            )
        val models: JSONArray = body.getJSONArray("metadataModelList")
        if (models.length() != 1) throw IOException("One PDF model required")
        val model: JSONObject = models.getJSONObject(0)
        val id: String = model.getString("uniqueId")
        if (
            !("cbd2938779124b2b83413f76dbe8ab12" == id) ||
                !(id == model.getString("uuid")) ||
                !("powersync-lab" == model.getString("user")) ||
                !("powersync-lab-READER_LIBRARY" == model.getString("dbId")) ||
                model.getInt("modeType") != 4 ||
                !("PowerSync-reader-geometry.pdf" == model.getString("name")) ||
                !("/storage/emulated/0/Download/PowerSync-reader-geometry.pdf" ==
                    model.getString("nativeAbsolutePath")) ||
                model.getLong("fileOriginSize") != 22562L
        )
            throw IOException("PDF transfer outside exact owned fixture")
        if (upload) {
            val current: JSONObject =
                JSONObject(
                    String(
                        java.nio.file.Files.readAllBytes(
                            File(context.getFilesDir(), "reader-pdf-private.json").toPath()
                        ),
                        java.nio.charset.StandardCharsets.UTF_8,
                    )
                )
            if (
                !("c0e26f46d2f365c13c2cf64bc8efc4b5eb756ba8b899060fdf9222d4d8a566a7" ==
                    current.getString("bookSha256")) ||
                    current.getJSONArray("metadata").length() != 1 ||
                    !(id == current.getJSONArray("metadata").getJSONObject(0).getString("uuid"))
            )
                throw IOException("Native annotated source required")
        }
        android.os.ParcelFileDescriptor.open(
                request,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY,
            )
            .use { payload ->
                RemoteBinding(
                        context,
                        "com.onyx.android.ksync",
                        "com.onyx.android.ksync.service.KSyncService",
                    )
                    .use { binding ->
                        val binder: IBinder = binding.await()
                        val descriptor: String =
                            "com.onyx.android.sdk.sync.ksync.service.ISyncService"
                        if (!(descriptor == binder.getInterfaceDescriptor()))
                            throw IOException("Unexpected PDF transfer interface")
                        val p: Parcel = android.os.Parcel.obtain()
                        try {
                            p.writeInterfaceToken(descriptor)
                            p.writeInt(1)
                            p.writeString("com.onyx.android.sdk.sync.ksync.KSyncContext")
                            p.writeString("ru.feryafox.shiori.diagnostics")
                            p.writeString("powersync-lab")
                            p.writeString(id)
                            p.writeString(null)
                            p.writeString(null)
                            p.writeParcelable(payload, 0)
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
                            p.writeStrongBinder(null)
                            if (
                                !binder.transact(
                                    if (upload) 12 else 13,
                                    p,
                                    null,
                                    android.os.IBinder.FLAG_ONEWAY,
                                )
                            )
                                throw IOException("PDF transfer rejected")
                        } finally {
                            p.recycle()
                        }
                    }
            }
        return JSONObject()
            .put("callerUid", android.os.Process.myUid())
            .put("requested", true)
            .put("completed", false)
            .put("operation", if (upload) "owned-pdf-upload" else "owned-pdf-download")
    }

    @JvmOverloads
    @Throws(java.lang.Exception::class)
    fun export(managed: Boolean = false): JSONObject {
        capture() // Revalidate the exact authored source before contacting native code.
        var source: File = File("/storage/emulated/0/Download/PowerSync-reader-geometry.pdf")
        if (managed) {
            source =
                File(
                    "/storage/emulated/0/.ksync/powersync-lab/reader/cbd2938779124b2b83413f76dbe8ab12/book/cbd2938779124b2b83413f76dbe8ab12.pdf"
                )
            if (
                !source.isFile() ||
                    source.length() != 22562L ||
                    !("c0e26f46d2f365c13c2cf64bc8efc4b5eb756ba8b899060fdf9222d4d8a566a7" ==
                        NativeInk.digest(java.nio.file.Files.readAllBytes(source.toPath())))
            )
                throw IOException("Exact managed annotated PDF required")
        }
        val directory: File =
            File("/storage/emulated/0/Download/PowerSync-pdf-export-" + java.util.UUID.randomUUID())
        if (!directory.mkdir()) throw IOException("Fresh export directory required")
        val request: JSONObject =
            JSONObject()
                .put("exportDir", directory.getPath())
                .put("exportFileName", "PowerSync-pdf-ink")
                .put("pathList", JSONArray().put(source.getPath()))
                .put("convertToPDF", false)
        ReportFiles.write(context, "reader-pdf-export-request.json", request)
        val done: CompletableFuture<JSONObject> = CompletableFuture<JSONObject>()
        val callbacks: JSONArray = JSONArray()
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
                        reply?.writeString("com.onyx.android.sdk.reader.ReaderExportCallback")
                        return true
                    }
                    if (code != 1) return super.onTransact(code, data, reply, flags)
                    data.enforceInterface("com.onyx.android.sdk.reader.ReaderExportCallback")
                    val kind: Int = data.readInt()
                    val state: Int = data.readInt()
                    val path: String? = data.readString()
                    val complete: ArrayList<String>? = data.createStringArrayList()
                    val errors: ArrayList<String>? = data.createStringArrayList()
                    try {
                        val event: JSONObject =
                            JSONObject()
                                .put("kind", kind)
                                .put("state", state)
                                .put("outputPath", path)
                                .put("complete", JSONArray(complete))
                                .put("errors", JSONArray(errors))
                        synchronized(callbacks) {
                            callbacks.put(event)
                            ReportFiles.write(
                                context,
                                "reader-pdf-export-callbacks.json",
                                JSONObject().put("callbacks", callbacks),
                            )
                        }
                        if (kind == 0 && (state == 1 || state == 2)) done.complete(event)
                    } catch (e: java.lang.Exception) {
                        done.completeExceptionally(e)
                    }
                    reply?.writeNoException()
                    return true
                }
            }
        RemoteBinding(
                context,
                "com.onyx.kreader",
                "com.onyx.android.sdk.readerview.service.ReaderExportService",
            )
            .use { binding ->
                val binder: IBinder = binding.await()
                val descriptor: String = "com.onyx.android.sdk.reader.IReaderExportService"
                if (!(descriptor == binder.getInterfaceDescriptor()))
                    throw IOException("Unexpected reader export interface")
                val data: Parcel = android.os.Parcel.obtain()
                val reply: Parcel = android.os.Parcel.obtain()
                try {
                    data.writeInterfaceToken(descriptor)
                    data.writeString(request.toString())
                    data.writeStrongBinder(callback)
                    if (!binder.transact(4, data, reply, 0))
                        throw IOException("Reader export transaction rejected")
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
                val event: JSONObject = done.get(60, java.util.concurrent.TimeUnit.SECONDS)
                val output: File = File(event.getString("outputPath")).getCanonicalFile()
                if (
                    event.getInt("state") != 1 ||
                        event.getJSONArray("errors").length() != 0 ||
                        event.getJSONArray("complete").length() != 1 ||
                        !(source.getPath() == event.getJSONArray("complete").getString(0)) ||
                        !output
                            .getPath()
                            .startsWith(directory.getCanonicalPath() + File.separator) ||
                        !output.isFile() ||
                        output.length() > 67108864
                )
                    throw IOException("Reader export did not produce one bounded fixture archive")
                return JSONObject()
                    .put("callerUid", android.os.Process.myUid())
                    .put("nativeCallback", event)
                    .put("exportExists", true)
                    .put("exportBytes", output.length())
                    .put(
                        "exportSha256",
                        NativeInk.digest(java.nio.file.Files.readAllBytes(output.toPath())),
                    )
                    .put("nativeInkQualified", false)
            }
    }

    @Throws(java.lang.Exception::class)
    fun capture(): JSONObject {
        val name: String = "PowerSync-reader-geometry.pdf"
        var digest: String = "6ccfeddb1f27d9d62975d78f0ec7d4c3f4c1bb8af69aa9786b5cd3974fa443bd"
        val file: File = File("/storage/emulated/0/Download/" + name).getCanonicalFile()
        if (!file.isFile() || file.length() > 1048576)
            throw IOException("Exact authored geometry PDF required")
        val actual: String = NativeInk.digest(java.nio.file.Files.readAllBytes(file.toPath()))
        // Exact user-authored native derivative observed after the owned pen control.
        if (
            !(digest == actual) &&
                !("c0e26f46d2f365c13c2cf64bc8efc4b5eb756ba8b899060fdf9222d4d8a566a7" == actual)
        )
            throw IOException("Unqualified PDF revision")
        digest = actual
        val notes: NativeNotes = NativeNotes(context)
        val base: String = "content://com.onyx.content.database.ContentProvider/"
        val metadata: JSONArray =
            notes.rows(
                Uri.parse(base + "Metadata"),
                "nocasePath=? OR nocasePath=?",
                arrayOf<String>(file.getPath(), "/sdcard/Download/" + name),
                true,
            )
        if (metadata.length() > 8) throw IOException("PDF identity bound exceeded")
        val ids: LinkedHashSet<String> = LinkedHashSet<String>()
        for (i in 0..<metadata.length()) {
            val row: JSONObject = metadata.getJSONObject(i)
            for (key in arrayOf<String>("uuid", "idString")) {
                val id: String = row.getString(key)
                if (id.isEmpty()) throw IOException("Missing PDF identity")
                ids.add(id)
            }
        }
        val data: JSONObject = JSONObject().put("bookSha256", digest).put("metadata", metadata)
        if (!ids.isEmpty()) {
            val `in`: String =
                " IN (" +
                    java.lang.String.join(
                        ",",
                        java.util.Collections.nCopies<String>(ids.size, "?"),
                    ) +
                    ")"
            val args: Array<String> = ids.toTypedArray<String>()
            for (table in arrayOf<String>("Bookmark", "Annotation")) data.put(
                table,
                notes.rows(Uri.parse(base + table), "idString" + `in`, args, true),
            )
            val ink: String = "content://com.onyx.kreader.note.provider/"
            // Installed manifests name an implementation whose query may be disabled,
            // despite a generated sibling provider containing a usable query method.
            try {
                data.put(
                    "readerNoteDocuments",
                    notes.rows(
                        Uri.parse(ink + "ReaderNoteDocumentModel"),
                        "uniqueId" + `in`,
                        args,
                        true,
                    ),
                )
            } catch (e: java.lang.UnsupportedOperationException) {
                data.put("readerNoteDocumentsQueryError", e.toString())
            }
            try {
                data.put(
                    "readerNoteShapes",
                    notes.rows(
                        Uri.parse(ink + "ReaderNoteShapeModel"),
                        "documentUniqueId" + `in`,
                        args,
                        true,
                    ),
                )
            } catch (e: java.lang.UnsupportedOperationException) {
                data.put("readerNoteShapesQueryError", e.toString())
            }
        }
        ReportFiles.write(context, "reader-pdf-private.json", data)
        return JSONObject()
            .put("callerUid", android.os.Process.myUid())
            .put("readOnly", true)
            .put("metadataRows", metadata.length())
            .put("bookSha256", digest)
            .put("privateCapture", "reader-pdf-private.json")
            .put("nativeGeometryQualified", false)
            .put("nativeInkQualified", false)
    }
}
