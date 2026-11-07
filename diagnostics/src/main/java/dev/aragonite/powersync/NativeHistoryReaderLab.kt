package dev.aragonite.powersync

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

/** Pull/export diagnostics for four separately journaled disposable history books. */
class NativeHistoryReaderLab(context: Context) {
    private val context: Context
    private val bookId: String

    init {
        this.context = context
        val f: File = File(context.getFilesDir(), "reader-history-request.json")
        if (!f.isFile() || f.length() > 1048576)
            throw IOException("Bounded history request required")
        val request: JSONObject =
            JSONObject(
                String(
                    java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8,
                )
            )
        val index: Int = request.getInt("fixtureIndex")
        if (index < 0 || index >= NativeHistoryReaderLab.Companion.BOOKS.size)
            throw IOException("Unknown history fixture")
        bookId = NativeHistoryReaderLab.Companion.BOOKS[index]
        if (!("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")))
            throw IOException("History requires lab endpoint")
    }

    @Throws(java.lang.Exception::class)
    fun enroll(): JSONObject {
        val path: String = "/storage/emulated/0/Download/PowerSync-history-" + bookId + ".pdf"
        val pdf: File = File(path)
        if (
            !pdf.isFile() ||
                pdf.length() != 2340L ||
                !(NativeHistoryReaderLab.Companion.PDF_SHA ==
                    NativeInk.digest(java.nio.file.Files.readAllBytes(pdf.toPath())))
        )
            throw IOException("Exact owned history PDF required")
        val uri: Uri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")
        context
            .getContentResolver()
            .query(
                uri,
                arrayOf<String>("uuid"),
                "uuid=? OR nocasePath=?",
                arrayOf<String>(bookId, path),
                null,
            )
            .use { c ->
                if (c == null || c.getCount() != 0)
                    throw IOException("New history metadata must be absent")
            }
        val request: JSONObject =
            JSONObject(
                String(
                    java.nio.file.Files.readAllBytes(
                        File(context.getFilesDir(), "reader-history-request.json").toPath()
                    ),
                    java.nio.charset.StandardCharsets.UTF_8,
                )
            )
        val row: JSONObject = request.getJSONObject("metadata")
        if (
            !(bookId == row.getString("uuid")) ||
                !(path == row.getString("nocasePath")) ||
                !(path == row.getString("idString")) ||
                row.has("id") ||
                row.getInt("userDataSyncStatus") != 1 ||
                row.getInt("status") != 0
        )
            throw IOException("History metadata identity mismatch")
        val values: ContentValues = ContentValues()
        val keys: MutableIterator<String?> = row.keys()
        while (keys.hasNext()) {
            val key: String? = keys.next()
            val v: Any? = row.get(key)
            if (v === JSONObject.NULL) values.putNull(key)
            else if (v is Number) values.put(key, (v as Number).toLong())
            else if (v is String) values.put(key, v as String)
            else throw IOException("Unexpected metadata value")
        }
        if (context.getContentResolver().insert(uri, values) == null)
            throw IOException("History enrollment failed")
        return JSONObject()
            .put("bookId", bookId)
            .put("newMetadataInserted", true)
            .put("physicalFreshInstall", false)
            .put("privateDatabasesReset", false)
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
                        File(context.getFilesDir(), "reader-history-pdf-private.json").toPath()
                    ),
                    java.nio.charset.StandardCharsets.UTF_8,
                )
            )
        val rows: JSONArray = captured.getJSONArray("metadata")
        val id: String = bookId
        if (rows.length() != 1 || !(id == rows.getJSONObject(0).getString("uuid")))
            throw IOException("Exact owned source identity required")
        val row: JSONObject = rows.getJSONObject(0)
        ReportFiles.write(
            context,
            "reader-history-pdf-full-pull-extra-before.json",
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
                        "/storage/emulated/0/Download/PowerSync-history-" + bookId + ".pdf",
                    ),
                ) != 1
        )
            throw IOException("One owned full-pull flag update required")
        try {
            return syncNative(false, false)
        } finally {
            val current: JSONArray =
                NativeNotes(context)
                    .rows(
                        Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),
                        "uuid=? AND nocasePath=?",
                        arrayOf<String>(
                            id,
                            "/storage/emulated/0/Download/PowerSync-history-" + bookId + ".pdf",
                        ),
                        true,
                    )
            if (current.length() != 1)
                throw IOException("One owned full-pull flag restoration row required")
            val restored: JSONObject = JSONObject(current.getJSONObject(0).getString("extraInfo"))
            restored.put(
                "pullFullUserDataFromSync",
                JSONObject(row.getString("extraInfo")).optBoolean("pullFullUserDataFromSync", false),
            )
            val restore: ContentValues = ContentValues()
            restore.put("extraInfo", restored.toString())
            if (
                context
                    .getContentResolver()
                    .update(
                        Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),
                        restore,
                        "uuid=? AND nocasePath=?",
                        arrayOf<String>(
                            id,
                            "/storage/emulated/0/Download/PowerSync-history-" + bookId + ".pdf",
                        ),
                    ) != 1
            )
                throw IOException("Full-pull flag restoration failed")
        }
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
        val id: String = bookId
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
                        ReportFiles.write(
                            context,
                            "reader-history-pdf-full-userdata-private.json",
                            body,
                        )
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
        p.writeString("dev.aragonite.powersync")
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
        val id: String? = bookId
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
                    p.writeString("dev.aragonite.powersync")
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
        val cloudId: String = bookId
        val id: String = cloudId
        val path: String = "/storage/emulated/0/Download/PowerSync-history-" + bookId + ".pdf"
        val file: File = File(path)
        if (!file.isFile() || file.length() > 1048576)
            throw IOException("Exact owned PDF required for native sync")
        val pdfDigest: String = NativeInk.digest(java.nio.file.Files.readAllBytes(file.toPath()))
        if (peer || pushing) throw IOException("History helper is pull-only")
        if (file.length() != 2340L || !(NativeHistoryReaderLab.Companion.PDF_SHA == pdfDigest))
            throw IOException("Exact history PDF required")
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
                        ReportFiles.write(
                            context,
                            "reader-history-pdf-sync-callback-private.json",
                            event,
                        )
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

    @JvmOverloads
    @Throws(java.lang.Exception::class)
    fun export(managed: Boolean = false): JSONObject {
        capture() // Revalidate the exact authored source before contacting native code.
        var source: File = File("/storage/emulated/0/Download/PowerSync-history-" + bookId + ".pdf")
        if (managed) {
            source =
                File(
                    "/storage/emulated/0/.ksync/powersync-lab/reader/ea6ad9be04304a399583fe557a21f63e/book/ea6ad9be04304a399583fe557a21f63e.pdf"
                )
            if (
                !source.isFile() ||
                    source.length() != 2340L ||
                    !("37962785464fb87d6ae1fedf7850aca2ea01dc4245af43ce49bb3e4c99b58086" ==
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
        ReportFiles.write(context, "reader-history-pdf-export-request.json", request)
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
                                "reader-history-pdf-export-callbacks.json",
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
        val name: String = "PowerSync-history-" + bookId + ".pdf"
        var digest: String = "37962785464fb87d6ae1fedf7850aca2ea01dc4245af43ce49bb3e4c99b58086"
        val file: File = File("/storage/emulated/0/Download/" + name).getCanonicalFile()
        if (!file.isFile() || file.length() > 1048576)
            throw IOException("Exact authored geometry PDF required")
        val actual: String = NativeInk.digest(java.nio.file.Files.readAllBytes(file.toPath()))
        if (file.length() != 2340L || !(NativeHistoryReaderLab.Companion.PDF_SHA == actual))
            throw IOException("Unqualified history PDF revision")
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
        if (metadata.length() != 1 || !(bookId == metadata.getJSONObject(0).getString("uuid")))
            throw IOException("Exact history identity required")
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
        ReportFiles.write(context, "reader-history-pdf-private.json", data)
        return JSONObject()
            .put("callerUid", android.os.Process.myUid())
            .put("readOnly", true)
            .put("metadataRows", metadata.length())
            .put("bookSha256", digest)
            .put("privateCapture", "reader-history-pdf-private.json")
            .put("nativeGeometryQualified", false)
            .put("nativeInkQualified", false)
    }

    companion object {
        private const val PDF_SHA: String =
            "37962785464fb87d6ae1fedf7850aca2ea01dc4245af43ce49bb3e4c99b58086"
        private val BOOKS: Array<String> =
            arrayOf<String>(
                "39d55e7921014fc28fd1693f8610c0aa",
                "4ed0e47687b74f55b1ce0dbd3bf0c05b",
                "425f03983b99494585e208345030af3b",
                "219d0921ee1f44c1a089e08764714ab4",
            )
    }
}
