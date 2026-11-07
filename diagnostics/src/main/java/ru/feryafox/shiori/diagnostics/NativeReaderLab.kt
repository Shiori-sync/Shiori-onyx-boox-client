package ru.feryafox.shiori.diagnostics

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Environment
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
import java.util.concurrent.CompletableFuture
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

/** Native reader qualification restricted to one hash-verified, authored EPUB. */
class NativeReaderLab(context: Context) {
    private val context: Context

    init {
        this.context = context
    }

    @Throws(java.lang.Exception::class)
    fun libraryGuard(): JSONObject {
        val rows: JSONArray =
            NativeNotes(context)
                .rows(
                    Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),
                    "nocasePath NOT IN (?,?,?,?) OR nocasePath IS NULL",
                    arrayOf<String>(
                        "/storage/emulated/0/Download/" + NativeReaderLab.Companion.BOOK,
                        "/sdcard/Download/" + NativeReaderLab.Companion.BOOK,
                        "/storage/emulated/0/Download/PowerSync-reader-geometry.pdf",
                        "/sdcard/Download/PowerSync-reader-geometry.pdf",
                    ),
                    true,
                )
        if (rows.length() > 20000) throw IOException("Reader guard exceeds bound")
        val signatures: ArrayList<String> = ArrayList<String>()
        for (i in 0..<rows.length()) {
            val row: JSONObject = rows.getJSONObject(i)
            signatures.add(
                row.optString("uuid") +
                    ":" +
                    row.optString("idString") +
                    ":" +
                    row.optInt("status") +
                    ":" +
                    row.optInt("userDataSyncStatus") +
                    ":" +
                    row.optInt("fileSyncStatus")
            )
        }
        ReportFiles.write(
            context,
            "reader-library-guard-private.json",
            JSONObject().put("rows", rows).put("signatures", JSONArray(signatures)),
        )
        java.util.Collections.sort<String?>(signatures)
        val digest: String? =
            NativeInk.digest(
                java.lang.String.join("\n", signatures)
                    .toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            )
        val file: File = File(context.getFilesDir(), "reader-library-guard-baseline.json")
        if (!file.exists())
            ReportFiles.write(
                context,
                file.getName(),
                JSONObject().put("digest", digest).put("rows", rows.length()),
            )
        val baseline: JSONObject = JSONObject(NativeReaderLab.Companion.read(file))
        return JSONObject()
            .put("originalReaderRows", rows.length())
            .put(
                "originalReaderIdentityStatusFlagsPreserved",
                baseline.getString("digest") == digest,
            )
    }

    @Throws(java.lang.Exception::class)
    fun request(start: Boolean): JSONObject {
        return request(if (start) "start" else "metadata")
    }

    @Throws(java.lang.Exception::class)
    fun request(operation: String?): JSONObject {
        val start: Boolean = "start" == operation
        val bookmarkPublication: Boolean = "bookmarks" == operation
        val annotationPublication: Boolean = "annotations" == operation
        if (!start && !bookmarkPublication && !annotationPublication && !("metadata" == operation))
            throw IOException("Unknown reader operation")
        val io: NativeSystemConfigProbe = NativeSystemConfigProbe()
        if (!("powersync_lab" == io.read().getString("curClusterId")))
            throw IOException("Reader publication requires lab endpoint")
        capture()
        val captured: JSONObject =
            JSONObject(
                NativeReaderLab.Companion.read(
                    File(context.getFilesDir(), "reader-fixture-private.json")
                )
            )
        val rows: JSONArray = captured.getJSONArray("metadata")
        val baseline: File = File(context.getFilesDir(), "reader-fixture-sync-baseline.json")
        var selected: String? =
            if (baseline.exists())
                JSONObject(NativeReaderLab.Companion.read(baseline)).getString("uuid")
            else null
        val selection: File = File(context.getFilesDir(), "reader-publication-selection.json")
        if (selection.exists()) {
            if (!baseline.exists())
                throw IOException("Explicit alias selection requires original identity baseline")
            selected = JSONObject(NativeReaderLab.Companion.read(selection)).getString("uuid")
        }
        if (selected == null && rows.length() != 1)
            throw IOException("Initial fixture identity must be unambiguous")
        var row: JSONObject? = null
        for (i in 0..<rows.length()) {
            val candidate: JSONObject = rows.getJSONObject(i)
            if (selected == null || selected == candidate.getString("uuid")) {
                if (row != null) throw IOException("Duplicate selected fixture UUID")
                row = candidate
            }
        }
        if (row == null) throw IOException("Original fixture UUID is missing")
        val id: String = row.getString("uuid")
        val allowed: JSONArray =
            JSONObject(
                    NativeReaderLab.Companion.read(
                        File(context.getFilesDir(), "reader-sync-scope.json")
                    )
                )
                .getJSONArray("bookIds")
        var admitted: Boolean = false
        for (i in 0..<allowed.length()) {
            val value: String = allowed.getString(i)
            if (!value.matches("[0-9a-f-]{32,36}".toRegex()))
                throw IOException("Invalid reader scope")
            if (id == value) admitted = true
        }
        if (!admitted || allowed.length() > 8) throw IOException("Unenrolled fixture identity")
        if (!baseline.exists())
            ReportFiles.write(
                context,
                baseline.getName(),
                JSONObject()
                    .put("uuid", id)
                    .put("userDataSyncStatus", row.getInt("userDataSyncStatus"))
                    .put("fileSyncStatus", row.getInt("fileSyncStatus")),
            )
        val values: ContentValues = ContentValues()
        values.put("userDataSyncStatus", 1)
        val updated: Int =
            context
                .getContentResolver()
                .update(
                    Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),
                    values,
                    "uuid=? AND nocasePath=?",
                    arrayOf<String>(id, row.getString("nocasePath")),
                )
        if (updated != 1) throw IOException("Scoped reader enable did not update one row")
        val system: JSONObject = io.read("ONYX_SYSTEM_CONFIG_KEY")
        system
            .getJSONObject("configMap")
            .put("key_enable_sync_reader_data", "true")
            .put("key_enable_sync_note_data", "false")
        io.write("ONYX_SYSTEM_CONFIG_KEY", system)
        context.sendBroadcast(Intent("com.onyx.action.system.config.changed"))
        row.put("uniqueId", id)
            .put("user", "powersync-lab")
            .put("dbId", "powersync-lab-READER_LIBRARY")
            .put("modeType", 4)
            .put("userDataSyncStatus", 1)
        var content: JSONObject =
            if (start) JSONObject().put("startAllDoc", false).put("startDocIdList", allowed)
            else
                JSONObject()
                    .put("metadataModelList", JSONArray().put(row))
                    .put("libraryModelList", JSONArray())
                    .put("collectionModelList", JSONArray())
                    .put("requestType", "SYNC_DOCUMENT")
                    .put("isSyncUserOtherData", false)
        if (bookmarkPublication) {
            val nativeBookmarks: JSONArray = captured.getJSONArray("bookmarks")
            val models: JSONArray = JSONArray()
            if (nativeBookmarks.length() == 0)
                throw IOException("Create a native fixture bookmark before publication")
            for (i in 0..<nativeBookmarks.length()) {
                val bookmark: JSONObject = JSONObject(nativeBookmarks.getJSONObject(i).toString())
                if (!(id == bookmark.getString("idString"))) continue
                bookmark
                    .put("uniqueId", bookmark.getString("uuid"))
                    .put("documentId", id)
                    .put("modeType", 2)
                models.put(bookmark)
            }
            if (models.length() == 0) throw IOException("Selected native fixture has no bookmarks")
            content =
                JSONObject()
                    .put("bookmarkModelList", models)
                    .put("annotationModelList", JSONArray())
                    .put("noteDocumentModelList", JSONArray())
        }
        if (annotationPublication) {
            val annotations: JSONArray = captured.getJSONArray("annotations")
            val models: JSONArray = JSONArray()
            val fields: Array<String> =
                arrayOf<String>(
                    "createdAt",
                    "updatedAt",
                    "quote",
                    "startPosition",
                    "endPosition",
                    "startPositionInt",
                    "endPositionInt",
                    "note",
                    "linkNote",
                    "application",
                    "position",
                    "positionV2",
                    "positionV3",
                    "pageNumber",
                    "oldStartPosition",
                    "oldEndPosition",
                    "positionType",
                    "color",
                    "shape",
                    "chapter",
                    "status",
                    "pageXPath",
                    "startXPath",
                    "endXPath",
                    "positionVersion",
                )
            for (i in 0..<annotations.length()) {
                val annotation: JSONObject = annotations.getJSONObject(i)
                if (!(id == annotation.getString("idString"))) continue
                val model: JSONObject = JSONObject()
                for (field in fields) if (annotation.has(field))
                    model.put(field, annotation.get(field))
                model
                    .put("uniqueId", annotation.getString("uuid"))
                    .put("documentId", id)
                    .put("modeType", 1)
                // Native SQLite stores rectangles as JSON; the sync model needs the array.
                val rectangles: Any? = annotation.opt("rectangles")
                model.put(
                    "rectangles",
                    if (rectangles is String) JSONArray(rectangles as String)
                    else if (rectangles == null || rectangles === JSONObject.NULL) JSONArray()
                    else rectangles,
                )
                models.put(model)
            }
            if (models.length() == 0)
                throw IOException("Selected native fixture has no annotations")
            content =
                JSONObject()
                    .put("bookmarkModelList", JSONArray())
                    .put("annotationModelList", models)
                    .put("noteDocumentModelList", JSONArray())
        }
        if (!start) ReportFiles.write(context, "reader-publication-request.json", content)
        if (start) null
        else
            android.os.ParcelFileDescriptor.open(
                    File(context.getFilesDir(), "reader-publication-request.json"),
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
                                throw IOException("Unexpected reader Binder")
                            val p: Parcel = android.os.Parcel.obtain()
                            try {
                                p.writeInterfaceToken(descriptor)
                                p.writeInt(1)
                                p.writeString("com.onyx.android.sdk.sync.ksync.KSyncContext")
                                p.writeString("ru.feryafox.shiori.diagnostics")
                                p.writeString("powersync-lab")
                                p.writeString(id)
                                p.writeString(null)
                                p.writeString(if (start) content.toString() else null)
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
                                        if (start) 17
                                        else
                                            (if (bookmarkPublication || annotationPublication) 3
                                            else 1),
                                        p,
                                        null,
                                        android.os.IBinder.FLAG_ONEWAY,
                                    )
                                )
                                    throw IOException("Reader transaction rejected")
                            } finally {
                                p.recycle()
                            }
                        }
                }
        return JSONObject()
            .put("explicitAliasSelection", selection.exists())
            .put("requested", true)
            .put("completed", false)
            .put(
                "operation",
                if (start) "scoped-start"
                else
                    (if (bookmarkPublication) "fixture-bookmark-publication"
                    else if (annotationPublication) "fixture-annotation-publication"
                    else "single-book-metadata-publication"),
            )
    }

    @JvmOverloads
    @Throws(java.lang.Exception::class)
    fun pullFullUserData(metadataOnly: Boolean = false): JSONObject {
        if (!("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")))
            throw IOException("Reader query requires lab endpoint")
        var id: String =
            JSONObject(
                    NativeReaderLab.Companion.read(
                        File(context.getFilesDir(), "reader-fixture-sync-baseline.json")
                    )
                )
                .getString("uuid")
        val selection: File = File(context.getFilesDir(), "reader-publication-selection.json")
        if (selection.exists())
            id = JSONObject(NativeReaderLab.Companion.read(selection)).getString("uuid")
        val allowed: JSONArray =
            JSONObject(
                    NativeReaderLab.Companion.read(
                        File(context.getFilesDir(), "reader-sync-scope.json")
                    )
                )
                .getJSONArray("bookIds")
        var admitted: Boolean = false
        var present: Boolean = false
        for (i in 0..<allowed.length()) if (id == allowed.getString(i)) admitted = true
        val managed: File =
            File("/storage/emulated/0/.ksync/powersync-lab/reader/" + id + "/book/" + id + ".epub")
        val metadata: JSONArray
        if (
            managed.isFile() &&
                managed.length() <= 1048576 &&
                NativeReaderLab.Companion.DIGEST ==
                    NativeInk.digest(java.nio.file.Files.readAllBytes(managed.toPath()))
        )
            metadata =
                NativeNotes(context)
                    .rows(
                        Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),
                        "uuid=?",
                        arrayOf<String>(id),
                        true,
                    )
        else {
            capture()
            metadata =
                JSONObject(
                        NativeReaderLab.Companion.read(
                            File(context.getFilesDir(), "reader-fixture-private.json")
                        )
                    )
                    .getJSONArray("metadata")
        }
        for (i in 0..<metadata.length()) if (id == metadata.getJSONObject(i).getString("uuid"))
            present = true
        if (
            !admitted ||
                (!present && !metadataOnly) ||
                (metadataOnly &&
                    (!managed.isFile() ||
                        managed.length() > 1048576 ||
                        !(NativeReaderLab.Companion.DIGEST ==
                            NativeInk.digest(java.nio.file.Files.readAllBytes(managed.toPath())))))
        )
            throw IOException("Query identity is outside verified fixture scope")
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
                        ReportFiles.write(context, "reader-full-userdata-private.json", body)
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

    @JvmOverloads
    @Throws(java.lang.Exception::class)
    fun syncBookFromLibrary(libraryDelta: Boolean = false): JSONObject {
        if (!("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")))
            throw IOException("Library request requires lab endpoint")
        val escrow: File = File(context.getFilesDir(), "reader-book-escrow.epub")
        if (
            !escrow.isFile() ||
                escrow.length() > 1048576 ||
                !(NativeReaderLab.Companion.DIGEST ==
                    NativeInk.digest(java.nio.file.Files.readAllBytes(escrow.toPath())))
        )
            throw IOException("Authored book escrow required")
        val id: String =
            JSONObject(
                    NativeReaderLab.Companion.read(
                        File(context.getFilesDir(), "reader-publication-selection.json")
                    )
                )
                .getString("uuid")
        val allowed: JSONArray =
            JSONObject(
                    NativeReaderLab.Companion.read(
                        File(context.getFilesDir(), "reader-sync-scope.json")
                    )
                )
                .getJSONArray("bookIds")
        var admitted: Boolean = false
        for (i in 0..<allowed.length()) if (id == allowed.getString(i)) admitted = true
        if (!admitted || !id.matches("[0-9a-f-]{32,36}".toRegex()))
            throw IOException("Library request outside fixture scope")
        val path: String =
            "/storage/emulated/0/.ksync/powersync-lab/reader/" + id + "/book/" + id + ".epub"
        val book: File = File(path)
        if (
            !book.isFile() ||
                book.length() != escrow.length() ||
                !(NativeReaderLab.Companion.DIGEST ==
                    NativeInk.digest(java.nio.file.Files.readAllBytes(book.toPath())))
        )
            throw IOException("Exact managed fixture download required")
        val completed: CompletableFuture<JSONObject> = CompletableFuture<JSONObject>()
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
                    if (code == android.os.IBinder.INTERFACE_TRANSACTION) {
                        if (reply != null) reply?.writeString(descriptor)
                        return true
                    }
                    if (code != 1) return super.onTransact(code, data, reply, flags)
                    try {
                        data.enforceInterface(descriptor)
                        if (data.readInt() == 0) throw IOException("Empty library callback")
                        val receivedPath: String? = data.readString()
                        val receivedId: String? = data.readString()
                        val needPull: Boolean = data.readByte().toInt() != 0
                        val needPush: Boolean = data.readByte().toInt() != 0
                        val payload: ParcelFileDescriptor? =
                            data.readParcelable<ParcelFileDescriptor?>(
                                ParcelFileDescriptor::class.java.getClassLoader()
                            )
                        val type: Int = data.readInt()
                        val result: Int = data.readInt()
                        val message: String? = data.readString()
                        val newData: Boolean = data.readByte().toInt() != 0
                        val toId: String? = data.readString()
                        if (payload != null) payload.close()
                        if (!(path == receivedPath) || !(id == receivedId) || type != 10002)
                            throw IOException("Library callback scope mismatch")
                        ReportFiles.write(
                            context,
                            "reader-library-callback-private.json",
                            JSONObject()
                                .put("path", receivedPath)
                                .put("fromId", receivedId)
                                .put("toId", toId)
                                .put("message", message)
                                .put("result", result),
                        )
                        completed.complete(
                            JSONObject()
                                .put("requested", true)
                                .put("callbackReceived", true)
                                .put("nativeResult", result)
                                .put("newAnnotationDataPulled", newData)
                                .put("identityChanged", toId != null && !(id == toId))
                                .put("pushRequested", needPush)
                                .put("pullRequested", needPull)
                        )
                    } catch (e: java.lang.Exception) {
                        completed.completeExceptionally(e)
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
                    throw IOException("Unexpected native reader Binder")
                if (libraryDelta) {
                    val request: Parcel = android.os.Parcel.obtain()
                    try {
                        request.writeInterfaceToken(descriptor)
                        if (!binder.transact(2, request, null, android.os.IBinder.FLAG_ONEWAY))
                            throw IOException("Native library delta rejected")
                    } finally {
                        request.recycle()
                    }
                    return JSONObject()
                        .put("requested", true)
                        .put("completed", false)
                        .put("wholeNativeLibraryDelta", true)
                }

                val parcel: Parcel = android.os.Parcel.obtain()
                try {
                    parcel.writeInterfaceToken(descriptor)
                    parcel.writeInt(1)
                    parcel.writeString(path)
                    parcel.writeString(id)
                    parcel.writeByte(1.toByte())
                    parcel.writeByte(0.toByte())
                    parcel.writeParcelable(null, 0)
                    parcel.writeInt(10002)
                    parcel.writeInt(0)
                    parcel.writeString(null)
                    parcel.writeByte(0.toByte())
                    parcel.writeString(null)
                    parcel.writeStrongBinder(callback)
                    if (!binder.transact(3, parcel, null, android.os.IBinder.FLAG_ONEWAY))
                        throw IOException("Native library request rejected")
                } finally {
                    parcel.recycle()
                }
            }
        return completed.get(40, java.util.concurrent.TimeUnit.SECONDS)
    }

    @JvmOverloads
    @Throws(java.lang.Exception::class)
    fun startScopedCloudLibraryReplicator(restart: Boolean = false): JSONObject {
        if (!("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")))
            throw IOException("Cloud replicator requires lab endpoint")
        val id: String =
            JSONObject(
                    NativeReaderLab.Companion.read(
                        File(context.getFilesDir(), "reader-publication-selection.json")
                    )
                )
                .getString("uuid")
        val ids: JSONArray =
            JSONObject(
                    NativeReaderLab.Companion.read(
                        File(context.getFilesDir(), "reader-sync-scope.json")
                    )
                )
                .getJSONArray("bookIds")
        var admitted: Boolean = false
        if (ids.length() == 0 || ids.length() > 8) throw IOException("Invalid cloud scope bound")
        for (i in 0..<ids.length()) {
            val value: String = ids.getString(i)
            if (!value.matches("[0-9a-f-]{32,36}".toRegex()))
                throw IOException("Invalid cloud scope UUID")
            if (id == value) admitted = true
        }
        if (!admitted) throw IOException("Unenrolled cloud UUID")
        val book: File =
            File("/storage/emulated/0/.ksync/powersync-lab/reader/" + id + "/book/" + id + ".epub")
        if (
            !book.isFile() ||
                book.length() > 1048576 ||
                !(NativeReaderLab.Companion.DIGEST ==
                    NativeInk.digest(java.nio.file.Files.readAllBytes(book.toPath())))
        )
            throw IOException("Verified managed cloud EPUB required")
        val content: String? =
            JSONObject().put("startAllDoc", false).put("startDocIdList", ids).toString()
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
                    throw IOException("Unexpected cloud replicator Binder")
                val p: Parcel = android.os.Parcel.obtain()
                try {
                    writeArgs(p, id, content, null, null)
                    if (
                        !binder.transact(
                            if (restart) 16 else 17,
                            p,
                            null,
                            android.os.IBinder.FLAG_ONEWAY,
                        )
                    )
                        throw IOException("Cloud library replicator request rejected")
                } finally {
                    p.recycle()
                }
            }
        return JSONObject()
            .put("requested", true)
            .put("completed", false)
            .put("scopeCount", ids.length())
            .put("replicator", "READER_LIBRARY")
            .put("restartRequested", restart)
            .put("bookDataReplicatorStartRequested", false)
            .put("readerImportRequested", false)
    }

    @Throws(java.lang.Exception::class)
    fun captureCloudBook(): JSONObject {
        if (!("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")))
            throw IOException("Cloud book capture requires lab endpoint")
        val id: String =
            JSONObject(
                    NativeReaderLab.Companion.read(
                        File(context.getFilesDir(), "reader-publication-selection.json")
                    )
                )
                .getString("uuid")
        val scope: JSONArray =
            JSONObject(
                    NativeReaderLab.Companion.read(
                        File(context.getFilesDir(), "reader-sync-scope.json")
                    )
                )
                .getJSONArray("bookIds")
        var allowed: Boolean = false
        for (i in 0..<scope.length()) if (id == scope.getString(i)) allowed = true
        if (!allowed || !id.matches("[0-9a-f-]{32,36}".toRegex()))
            throw IOException("Cloud book outside fixture scope")
        val path: String =
            "/storage/emulated/0/.ksync/powersync-lab/reader/" + id + "/book/" + id + ".epub"
        val file: File = File(path)
        if (
            !file.isFile() ||
                file.length() > 1048576 ||
                !(NativeReaderLab.Companion.DIGEST ==
                    NativeInk.digest(java.nio.file.Files.readAllBytes(file.toPath())))
        )
            throw IOException("Exact managed authored EPUB required")
        val notes: NativeNotes = NativeNotes(context)
        val metadata: JSONArray =
            notes.rows(
                Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),
                "uuid=? AND nocasePath=?",
                arrayOf<String>(id, path),
                true,
            )
        if (metadata.length() != 1)
            throw IOException("Cloud UUID/path must identify one native book")
        val bookmarks: JSONArray =
            notes.rows(
                Uri.parse("content://com.onyx.content.database.ContentProvider/Bookmark"),
                "idString=?",
                arrayOf<String>(id),
                true,
            )
        val annotations: JSONArray =
            notes.rows(
                Uri.parse("content://com.onyx.content.database.ContentProvider/Annotation"),
                "idString=?",
                arrayOf<String>(id),
                true,
            )
        if (bookmarks.length() > 128 || annotations.length() > 128)
            throw IOException("Cloud fixture model bound exceeded")
        ReportFiles.write(
            context,
            "reader-cloud-book-private.json",
            JSONObject()
                .put("metadata", metadata)
                .put("bookmarks", bookmarks)
                .put("annotations", annotations),
        )
        return JSONObject()
            .put("readOnly", true)
            .put("cloudIdentityAndManagedPathMatched", true)
            .put("bookmarkRows", bookmarks.length())
            .put("annotationRows", annotations.length())
    }

    @Throws(java.lang.Exception::class)
    fun editCloudFixtureComment(): JSONObject {
        captureCloudBook()
        val before: JSONObject =
            JSONObject(
                NativeReaderLab.Companion.read(
                    File(context.getFilesDir(), "reader-cloud-book-private.json")
                )
            )
        val annotations: JSONArray = before.getJSONArray("annotations")
        if (annotations.length() != 1)
            throw IOException("Local-comment test requires exactly one fixture annotation")
        val annotation: JSONObject = annotations.getJSONObject(0)
        val book: String = before.getJSONArray("metadata").getJSONObject(0).getString("uuid")
        val id: String = annotation.getString("uuid")
        if (!(book == annotation.getString("idString")) || annotation.getInt("status") != 0)
            throw IOException("Local-comment test requires an enabled enrolled annotation")
        val timestamp: Long =
            max(java.lang.System.currentTimeMillis(), annotation.getLong("updatedAt") + 1)
        ReportFiles.write(context, "reader-cloud-comment-before-private.json", before)
        val values: ContentValues = ContentValues()
        values.put("note", "PowerSync newer local comment")
        values.put("updatedAt", timestamp)
        val changed: Int =
            context
                .getContentResolver()
                .update(
                    Uri.parse("content://com.onyx.content.database.ContentProvider/Annotation"),
                    values,
                    "uuid=? AND idString=?",
                    arrayOf<String>(id, book),
                )
        if (changed != 1)
            throw IOException("Local-comment test must update exactly one fixture annotation")
        captureCloudBook()
        val after: JSONObject =
            JSONObject(
                NativeReaderLab.Companion.read(
                    File(context.getFilesDir(), "reader-cloud-book-private.json")
                )
            )
        val saved: JSONObject = after.getJSONArray("annotations").getJSONObject(0)
        if (
            !(id == saved.getString("uuid")) ||
                !("PowerSync newer local comment" == saved.getString("note")) ||
                saved.getLong("updatedAt") != timestamp
        )
            throw IOException("Local comment did not persist exactly")
        return JSONObject()
            .put("updatedFixtureRows", changed)
            .put("unprivilegedLocalCommentSaved", true)
            .put("updatedAt", timestamp)
    }

    @Throws(java.lang.Exception::class)
    private fun selectedConflictRow(rows: JSONArray): JSONObject {
        val id: String =
            JSONObject(
                    NativeReaderLab.Companion.read(
                        File(context.getFilesDir(), "reader-conflict-selection.json")
                    )
                )
                .getString("annotationId")
        if (!id.matches("[0-9a-f-]{32,36}".toRegex()))
            throw IOException("Invalid synthetic annotation UUID")
        var selected: JSONObject? = null
        for (i in 0..<rows.length()) if (id == rows.getJSONObject(i).getString("uuid")) {
            if (selected != null) throw IOException("Duplicate conflict UUID")
            selected = rows.getJSONObject(i)
        }
        if (selected == null) throw IOException("Selected conflict annotation absent")
        return selected
    }

    @Throws(java.lang.Exception::class)
    fun editCloudConflict(): JSONObject {
        captureCloudBook()
        val before: JSONObject =
            JSONObject(
                NativeReaderLab.Companion.read(
                    File(context.getFilesDir(), "reader-cloud-book-private.json")
                )
            )
        val request: JSONObject =
            JSONObject(
                NativeReaderLab.Companion.read(
                    File(context.getFilesDir(), "reader-conflict-edit.json")
                )
            )
        val note: String = request.getString("note")
        val status: Int = request.getInt("status")
        if (
            !note.matches("PowerSync conflict (palma|lumi) (first|second|edit|delete)".toRegex()) ||
                (status != 0 && status != 1)
        )
            throw IOException("Invalid synthetic conflict edit")
        val row: JSONObject = selectedConflictRow(before.getJSONArray("annotations"))
        val book: String = before.getJSONArray("metadata").getJSONObject(0).getString("uuid")
        val id: String = row.getString("uuid")
        if (!(book == row.getString("idString"))) throw IOException("Conflict parent mismatch")
        val stamp: Long = max(java.lang.System.currentTimeMillis(), row.getLong("updatedAt") + 1)
        ReportFiles.write(context, "reader-conflict-before-private.json", before)
        val values: ContentValues = ContentValues()
        values.put("note", note)
        values.put("status", status)
        values.put("updatedAt", stamp)
        if (
            context
                .getContentResolver()
                .update(
                    Uri.parse("content://com.onyx.content.database.ContentProvider/Annotation"),
                    values,
                    "uuid=? AND idString=?",
                    arrayOf<String>(id, book),
                ) != 1
        )
            throw IOException("Conflict edit requires exactly one row")
        captureCloudBook()
        val saved: JSONObject =
            selectedConflictRow(
                JSONObject(
                        NativeReaderLab.Companion.read(
                            File(context.getFilesDir(), "reader-cloud-book-private.json")
                        )
                    )
                    .getJSONArray("annotations")
            )
        if (
            !(id == saved.getString("uuid")) ||
                !(note == saved.getString("note")) ||
                saved.getInt("status") != status ||
                saved.getLong("updatedAt") != stamp
        )
            throw IOException("Conflict edit verification failed")
        return JSONObject()
            .put("updatedFixtureRows", 1)
            .put("status", status)
            .put("updatedAt", stamp)
            .put("ordinaryUid", android.os.Process.myUid())
    }

    @Throws(java.lang.Exception::class)
    fun publishCloudConflict(): JSONObject {
        captureCloudBook()
        val nativeBook: JSONObject =
            JSONObject(
                NativeReaderLab.Companion.read(
                    File(context.getFilesDir(), "reader-cloud-book-private.json")
                )
            )
        val row: JSONObject = selectedConflictRow(nativeBook.getJSONArray("annotations"))
        val model: JSONObject =
            JSONObject(
                NativeReaderLab.Companion.read(
                    File(context.getFilesDir(), "reader-conflict-model.json")
                )
            )
        val book: String = nativeBook.getJSONArray("metadata").getJSONObject(0).getString("uuid")
        if (
            model.getInt("modeType") != 1 ||
                !(book == model.getString("documentId")) ||
                !(row.getString("uuid") == model.getString("uniqueId")) ||
                !(row.getString("note") == model.getString("note")) ||
                row.getInt("status") != model.getInt("status") ||
                row.getLong("updatedAt") != model.getLong("updatedAt") ||
                model.has("_deleted")
        )
            throw IOException("Conflict payload must match enrolled native row")
        model.remove("_id")
        model.remove("_rev")
        val payload: JSONObject =
            JSONObject()
                .put("annotationModelList", JSONArray().put(model))
                .put("bookmarkModelList", JSONArray())
                .put("noteDocumentModelList", JSONArray())
        ReportFiles.write(context, "reader-conflict-publication-private.json", payload)
        android.os.ParcelFileDescriptor.open(
                File(context.getFilesDir(), "reader-conflict-publication-private.json"),
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
                            throw IOException("Unexpected conflict Binder")
                        val parcel: Parcel = android.os.Parcel.obtain()
                        try {
                            writeArgs(parcel, book, null, fd, null)
                            if (!binder.transact(3, parcel, null, android.os.IBinder.FLAG_ONEWAY))
                                throw IOException("Conflict save rejected")
                        } finally {
                            parcel.recycle()
                        }
                    }
            }
        return JSONObject()
            .put("requested", true)
            .put("completed", false)
            .put("nativeCblSaveRequested", true)
    }

    @Throws(java.lang.Exception::class)
    fun prepareBookTransfer(): JSONObject {
        if (!("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")))
            throw IOException("Book transfer requires lab endpoint")
        capture()
        val captured: JSONObject =
            JSONObject(
                NativeReaderLab.Companion.read(
                    File(context.getFilesDir(), "reader-fixture-private.json")
                )
            )
        val id: String =
            JSONObject(
                    NativeReaderLab.Companion.read(
                        File(context.getFilesDir(), "reader-publication-selection.json")
                    )
                )
                .getString("uuid")
        var row: JSONObject? = null
        val rows: JSONArray = captured.getJSONArray("metadata")
        for (i in 0..<rows.length()) if (id == rows.getJSONObject(i).getString("uuid"))
            row = JSONObject(rows.getJSONObject(i).toString())
        if (row == null) throw IOException("Selected book absent from fixture capture")
        val book: File = File("/storage/emulated/0/Download/" + NativeReaderLab.Companion.BOOK)
        val bytes: ByteArray = java.nio.file.Files.readAllBytes(book.toPath())
        java.nio.file.Files.write(
            File(context.getFilesDir(), "reader-book-escrow.epub").toPath(),
            bytes,
        )
        row.put("uniqueId", id)
            .put("user", "powersync-lab")
            .put("dbId", "powersync-lab-READER_LIBRARY")
            .put("modeType", 4)
            .put("fileOriginSize", bytes.size)
        ReportFiles.write(
            context,
            "reader-book-transfer-request.json",
            JSONObject().put("metadataModelList", JSONArray().put(row)),
        )
        return JSONObject()
            .put("prepared", true)
            .put("bookSha256", NativeReaderLab.Companion.DIGEST)
            .put("bytes", bytes.size)
    }

    @Throws(java.lang.Exception::class)
    fun transferBook(upload: Boolean): JSONObject {
        if (!("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")))
            throw IOException("Book transfer requires lab endpoint")
        val escrow: File = File(context.getFilesDir(), "reader-book-escrow.epub")
        if (
            !escrow.isFile() ||
                escrow.length() > 1048576 ||
                !(NativeReaderLab.Companion.DIGEST ==
                    NativeInk.digest(java.nio.file.Files.readAllBytes(escrow.toPath())))
        )
            throw IOException("Book escrow must match exact authored EPUB")
        val request: File = File(context.getFilesDir(), "reader-book-transfer-request.json")
        val body: JSONObject = JSONObject(NativeReaderLab.Companion.read(request))
        val models: JSONArray = body.getJSONArray("metadataModelList")
        if (models.length() != 1) throw IOException("Only one synthetic book transfer allowed")
        val model: JSONObject = models.getJSONObject(0)
        val id: String = model.getString("uniqueId")
        val allowed: JSONArray =
            JSONObject(
                    NativeReaderLab.Companion.read(
                        File(context.getFilesDir(), "reader-sync-scope.json")
                    )
                )
                .getJSONArray("bookIds")
        var enrolled: Boolean = false
        for (i in 0..<allowed.length()) if (id == allowed.getString(i)) enrolled = true
        if (
            !enrolled ||
                !id.matches("[0-9a-f-]{32,36}".toRegex()) ||
                !("powersync-lab" == model.getString("user")) ||
                !(NativeReaderLab.Companion.BOOK == model.getString("name")) ||
                !(model.getString("nativeAbsolutePath") ==
                    "/storage/emulated/0/Download/" + NativeReaderLab.Companion.BOOK) ||
                model.getLong("fileOriginSize") != escrow.length()
        )
            throw IOException("Book transfer outside authored fixture scope")
        if (upload) capture()
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
                        if (
                            !("com.onyx.android.sdk.sync.ksync.service.ISyncService" ==
                                binder.getInterfaceDescriptor())
                        )
                            throw IOException("Unexpected book Binder")
                        val parcel: Parcel = android.os.Parcel.obtain()
                        try {
                            writeArgs(parcel, id, null, payload, null)
                            if (
                                !binder.transact(
                                    if (upload) 12 else 13,
                                    parcel,
                                    null,
                                    android.os.IBinder.FLAG_ONEWAY,
                                )
                            )
                                throw IOException("Book transfer rejected")
                        } finally {
                            parcel.recycle()
                        }
                    }
            }
        return JSONObject()
            .put("requested", true)
            .put("completed", false)
            .put("operation", if (upload) "fixture-book-upload" else "fixture-book-download")
    }

    @Throws(java.lang.Exception::class)
    fun refreshBookmark(): JSONObject {
        if (!("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")))
            throw IOException("Bookmark revision requires lab endpoint")
        capture()
        val captured: JSONObject =
            JSONObject(
                NativeReaderLab.Companion.read(
                    File(context.getFilesDir(), "reader-fixture-private.json")
                )
            )
        val target: String =
            JSONObject(
                    NativeReaderLab.Companion.read(
                        File(context.getFilesDir(), "reader-bookmark-revision-request.json")
                    )
                )
                .getString("uuid")
        val bookmarks: JSONArray = captured.getJSONArray("bookmarks")
        val allowed: JSONArray =
            JSONObject(
                    NativeReaderLab.Companion.read(
                        File(context.getFilesDir(), "reader-sync-scope.json")
                    )
                )
                .getJSONArray("bookIds")
        var selected: JSONObject? = null
        for (i in 0..<bookmarks.length()) if (
            target == bookmarks.getJSONObject(i).getString("uuid")
        ) {
            if (selected != null) throw IOException("Duplicate bookmark identity")
            selected = bookmarks.getJSONObject(i)
        }
        if (selected == null) throw IOException("Bookmark is not attached to verified fixture")
        var enrolled: Boolean = false
        for (i in 0..<allowed.length()) if (selected.getString("idString") == allowed.getString(i))
            enrolled = true
        if (!enrolled) throw IOException("Bookmark parent is outside lab scope")
        val timestamp: Long =
            max(java.lang.System.currentTimeMillis(), selected.getLong("updatedAt") + 1)
        ReportFiles.write(context, "reader-bookmark-revision-before-private.json", selected)
        val values: ContentValues = ContentValues()
        values.put("updatedAt", timestamp)
        val changed: Int =
            context
                .getContentResolver()
                .update(
                    Uri.parse("content://com.onyx.content.database.ContentProvider/Bookmark"),
                    values,
                    "uuid=? AND idString=?",
                    arrayOf<String>(target, selected.getString("idString")),
                )
        if (changed != 1) throw IOException("Bookmark revision must affect one scoped row")
        return JSONObject()
            .put("fixtureBookmarkTimestampRefreshed", true)
            .put("changedRows", changed)
    }

    @Throws(java.lang.Exception::class)
    fun reorderIdentity(restore: Boolean): JSONObject {
        val io: NativeSystemConfigProbe = NativeSystemConfigProbe()
        if (!restore && !("powersync_lab" == io.read().getString("curClusterId")))
            throw IOException("Identity test requires lab endpoint")
        if (
            !("false" ==
                io.read("ONYX_SYSTEM_CONFIG_KEY")
                    .getJSONObject("configMap")
                    .getString("key_enable_sync_reader_data"))
        )
            throw IOException("Stop reader sync before identity test")
        capture()
        val rows: JSONArray =
            JSONObject(
                    NativeReaderLab.Companion.read(
                        File(context.getFilesDir(), "reader-fixture-private.json")
                    )
                )
                .getJSONArray("metadata")
        val allowed: JSONArray =
            JSONObject(
                    NativeReaderLab.Companion.read(
                        File(context.getFilesDir(), "reader-sync-scope.json")
                    )
                )
                .getJSONArray("bookIds")
        for (i in 0..<rows.length()) {
            var enrolled: Boolean = false
            for (j in 0..<allowed.length()) if (
                rows.getJSONObject(i).getString("uuid") == allowed.getString(j)
            )
                enrolled = true
            if (!enrolled) throw IOException("Identity alias outside enrolled fixture scope")
        }
        val file: File = File(context.getFilesDir(), "reader-identity-time-baseline.json")
        if (!restore && !file.exists())
            ReportFiles.write(context, file.getName(), JSONObject().put("metadata", rows))
        val originals: JSONArray =
            JSONObject(NativeReaderLab.Companion.read(file)).getJSONArray("metadata")
        var earliest: Long = Long.Companion.MAX_VALUE
        for (i in 0..<originals.length()) earliest =
            min(earliest, originals.getJSONObject(i).getLong("createdAt"))
        if (earliest < 1000) throw IOException("Invalid fixture creation time")
        val target: String? =
            if (restore) null
            else
                JSONObject(
                        NativeReaderLab.Companion.read(
                            File(context.getFilesDir(), "reader-identity-request.json")
                        )
                    )
                    .getString("uuid")
        var changed: Int = 0
        for (i in 0..<originals.length()) {
            val original: JSONObject = originals.getJSONObject(i)
            if (!restore && !(original.getString("uuid") == target)) continue
            var present: Boolean = false
            for (j in 0..<rows.length()) if (
                original.getString("uuid") == rows.getJSONObject(j).getString("uuid") &&
                    original.getString("nocasePath") ==
                        rows.getJSONObject(j).getString("nocasePath")
            )
                present = true
            if (!present) throw IOException("Original fixture alias missing during identity test")
            val values: ContentValues = ContentValues()
            values.put("createdAt", if (restore) original.getLong("createdAt") else earliest - 1000)
            val count: Int =
                context
                    .getContentResolver()
                    .update(
                        Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),
                        values,
                        "uuid=? AND nocasePath=?",
                        arrayOf<String>(
                            original.getString("uuid"),
                            original.getString("nocasePath"),
                        ),
                    )
            if (count != 1)
                throw IOException("Identity timestamp write must affect one fixture row")
            changed++
        }
        if (changed != (if (restore) originals.length() else 1))
            throw IOException("Identity test selected unexpected row count")
        return JSONObject()
            .put("createdAtRestored", restore)
            .put("fixtureOnly", true)
            .put("changedRows", changed)
    }

    @Throws(java.lang.Exception::class)
    fun restoreFixtureFlags(): JSONObject {
        capture()
        val rows: JSONObject =
            JSONObject(
                NativeReaderLab.Companion.read(
                    File(context.getFilesDir(), "reader-fixture-private.json")
                )
            )
        val baseline: JSONObject =
            JSONObject(
                NativeReaderLab.Companion.read(
                    File(context.getFilesDir(), "reader-fixture-sync-baseline.json")
                )
            )
        val metadata: JSONArray = rows.getJSONArray("metadata")
        val allowed: JSONArray =
            JSONObject(
                    NativeReaderLab.Companion.read(
                        File(context.getFilesDir(), "reader-sync-scope.json")
                    )
                )
                .getJSONArray("bookIds")
        var restored: Int = 0
        var originalPresent: Boolean = false
        for (i in 0..<metadata.length()) {
            val row: JSONObject = metadata.getJSONObject(i)
            val uuid: String = row.getString("uuid")
            var enrolled: Boolean = false
            for (j in 0..<allowed.length()) if (uuid == allowed.getString(j)) enrolled = true
            if (!enrolled) throw IOException("Unexpected fixture alias; inspect before restoring")
            val original: Boolean = baseline.getString("uuid") == uuid
            originalPresent = originalPresent or original
            val values: ContentValues = ContentValues()
            values.put(
                "userDataSyncStatus",
                if (original) baseline.getInt("userDataSyncStatus") else 0,
            )
            values.put("fileSyncStatus", if (original) baseline.getInt("fileSyncStatus") else 0)
            val changed: Int =
                context
                    .getContentResolver()
                    .update(
                        Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),
                        values,
                        "uuid=? AND nocasePath=?",
                        arrayOf<String>(uuid, row.getString("nocasePath")),
                    )
            if (changed != 1) throw IOException("Reader flag restore must affect one scoped row")
            restored++
        }
        if (!originalPresent) throw IOException("Original fixture UUID missing during restoration")
        return JSONObject().put("fixtureFlagsRestored", true).put("scopedRowsRestored", restored)
    }

    @Throws(java.lang.Exception::class)
    fun capture(): JSONObject {
        val file: File =
            File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ),
                    NativeReaderLab.Companion.BOOK,
                )
                .getCanonicalFile()
        if (
            !file.isFile() ||
                file.length() > 1048576 ||
                !(NativeReaderLab.Companion.DIGEST ==
                    NativeInk.digest(java.nio.file.Files.readAllBytes(file.toPath())))
        )
            throw IOException("Expected exact authored EPUB fixture")
        val notes: NativeNotes = NativeNotes(context)
        val authority: String = "content://com.onyx.content.database.ContentProvider/"
        val metadata: JSONArray =
            notes.rows(
                Uri.parse(authority + "Metadata"),
                "nocasePath=? OR nocasePath=?",
                arrayOf<String>(
                    file.getPath(),
                    "/sdcard/Download/" + NativeReaderLab.Companion.BOOK,
                ),
                true,
            )
        if (metadata.length() > 8)
            throw IOException("Fixture alias count exceeds scoped diagnostic bound")
        var bookmarks: JSONArray = JSONArray()
        var annotations: JSONArray = JSONArray()
        val identities: LinkedHashSet<String> = LinkedHashSet<String>()
        for (i in 0..<metadata.length()) {
            val row: JSONObject = metadata.getJSONObject(i)
            val id: String = row.optString("idString", "")
            val uuid: String = row.optString("uuid", "")
            if (id.isEmpty() || uuid.isEmpty()) throw IOException("Fixture lacks native identity")
            identities.add(id)
            identities.add(uuid)
        }
        if (!identities.isEmpty()) {
            val selection: String =
                "idString IN (" +
                    java.lang.String.join(
                        ",",
                        java.util.Collections.nCopies<String>(identities.size, "?"),
                    ) +
                    ")"
            val args: Array<String> = identities.toTypedArray<String>()
            bookmarks = notes.rows(Uri.parse(authority + "Bookmark"), selection, args, true)
            annotations = notes.rows(Uri.parse(authority + "Annotation"), selection, args, true)
        }
        ReportFiles.write(
            context,
            "reader-fixture-private.json",
            JSONObject()
                .put("bookSha256", NativeReaderLab.Companion.DIGEST)
                .put("metadata", metadata)
                .put("bookmarks", bookmarks)
                .put("annotations", annotations),
        )
        return JSONObject()
            .put("callerUid", android.os.Process.myUid())
            .put("readOnly", true)
            .put("bookSha256", NativeReaderLab.Companion.DIGEST)
            .put("ambiguousNativeIdentity", metadata.length() > 1)
            .put("metadataRows", metadata.length())
            .put("bookmarkRows", bookmarks.length())
            .put("annotationRows", annotations.length())
            .put("privateCapture", "reader-fixture-private.json")
            .put("readerSyncQualified", false)
    }

    companion object {
        private const val BOOK: String = "PowerSync-reader-fixture.epub"
        private const val DIGEST: String =
            "c75e1ac3d91d1082b4597a3fb100eee03f2681d17976066595c030c8eecc5683"

        @Throws(IOException::class)
        private fun read(file: File): String {
            if (file.length() > 4194304) throw IOException("Reader journal exceeds bound")
            return String(
                java.nio.file.Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8,
            )
        }
    }
}
