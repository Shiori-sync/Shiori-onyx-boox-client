package ru.feryafox.shiori.diagnostics

import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

/** Bounded diagnostics and native operations for one exact, locally authored PDF. */
class NativeFreshReaderPdfLab(context: Context) {
    private val context: Context

    init {
        this.context = context
    }

    @Throws(java.lang.Exception::class)
    fun notifyConfigChange(): JSONObject {
        val probe: NativeSystemConfigProbe = NativeSystemConfigProbe()
        if (!("powersync_lab" == probe.read().getString("curClusterId")))
            throw IOException("Config notification requires lab endpoint")
        val config: JSONObject = probe.read("ONYX_SYSTEM_CONFIG_KEY")
        if (
            !("true" ==
                config.getJSONObject("configMap").getString("key_enable_sync_reader_data")) ||
                !("false" ==
                    config.getJSONObject("configMap").getString("key_enable_sync_note_data"))
        )
            throw IOException("Reader-only lab flags required")
        for (target in arrayOf<String>("com.onyx.kreader", "com.onyx.android.ksync")) context
            .sendBroadcast(Intent("com.onyx.action.system.config.changed").setPackage(target))
        return JSONObject()
            .put("callerUid", android.os.Process.myUid())
            .put("requested", true)
            .put("deliveryObserved", false)
            .put("systemFlagsWritten", false)
            .put("action", "com.onyx.action.system.config.changed")
    }

    @Throws(java.lang.Exception::class)
    fun enableAutoSync(): JSONObject {
        if (!("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")))
            throw IOException("Fresh reader auto sync requires lab endpoint")
        capture()
        val data: JSONObject =
            JSONObject(
                String(
                    java.nio.file.Files.readAllBytes(
                        File(context.getFilesDir(), "reader-fresh-pdf-private.json").toPath()
                    ),
                    java.nio.charset.StandardCharsets.UTF_8,
                )
            )
        val rows: JSONArray = data.getJSONArray("metadata")
        if (rows.length() != 1) throw IOException("One fresh fixture row required")
        val row: JSONObject = rows.getJSONObject(0)
        val id: String = "ea6ad9be04304a399583fe557a21f63e"
        val path: String = "/storage/emulated/0/Download/PowerSync-reader-clean-v2.pdf"
        if (!(id == row.getString("uuid")) || !(path == row.getString("nocasePath")))
            throw IOException("Unexpected fresh fixture identity")
        val extra: JSONObject = JSONObject(row.getString("extraInfo"))
        ReportFiles.write(
            context,
            "reader-fresh-auto-sync-baseline.json",
            JSONObject()
                .put("id", row.getLong("id"))
                .put("uuid", id)
                .put("path", path)
                .put("extraInfo", extra.toString()),
        )
        extra.put("autoSync", true)
        val values: ContentValues = ContentValues()
        values.put("extraInfo", extra.toString())
        val changed: Int =
            context
                .getContentResolver()
                .update(
                    Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),
                    values,
                    "id=? AND uuid=? AND nocasePath=?",
                    arrayOf<String>(row.getString("id"), id, path),
                )
        if (changed != 1) throw IOException("One fresh fixture auto-sync update required")
        return JSONObject()
            .put("ordinaryUid", android.os.Process.myUid())
            .put("changedRows", changed)
            .put("autoSync", true)
    }

    @Throws(java.lang.Exception::class)
    fun stagePeerMetadata(): JSONObject {
        val cloud: String = "ea6ad9be04304a399583fe557a21f63e"
        val path: String =
            "/storage/emulated/0/.ksync/powersync-lab/reader/" + cloud + "/book/" + cloud + ".pdf"
        if (
            !("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")) ||
                !("37962785464fb87d6ae1fedf7850aca2ea01dc4245af43ce49bb3e4c99b58086" ==
                    NativeInk.digest(java.nio.file.Files.readAllBytes(File(path).toPath())))
        )
            throw IOException("Owned lab PDF required")
        val request: File =
            File(context.getFilesDir(), "reader-fresh-pdf-peer-metadata-request.json")
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
                        "/storage/emulated/0/Download/PowerSync-reader-clean-v2.pdf" ==
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
        val cloud: String = "ea6ad9be04304a399583fe557a21f63e"
        val local: String = "dd9c0167293c411b9df712f89ff98af6"
        val path: String =
            "/storage/emulated/0/.ksync/powersync-lab/reader/" + cloud + "/book/" + cloud + ".pdf"
        if (
            !("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")) ||
                !("37962785464fb87d6ae1fedf7850aca2ea01dc4245af43ce49bb3e4c99b58086" ==
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
        val cloud: String = "ea6ad9be04304a399583fe557a21f63e"
        val baseline: File = File(context.getFilesDir(), "reader-fresh-pdf-sync-baseline.json")
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
                    !("/storage/emulated/0/Download/PowerSync-reader-clean-v2.pdf" ==
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
                        File(context.getFilesDir(), "reader-fresh-pdf-private.json").toPath()
                    ),
                    java.nio.charset.StandardCharsets.UTF_8,
                )
            )
        val rows: JSONArray = captured.getJSONArray("metadata")
        val id: String = "ea6ad9be04304a399583fe557a21f63e"
        if (rows.length() != 1 || !(id == rows.getJSONObject(0).getString("uuid")))
            throw IOException("Exact owned source identity required")
        val row: JSONObject = rows.getJSONObject(0)
        ReportFiles.write(
            context,
            "reader-fresh-pdf-full-pull-extra-before.json",
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
                        "/storage/emulated/0/Download/PowerSync-reader-clean-v2.pdf",
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
                            "/storage/emulated/0/Download/PowerSync-reader-clean-v2.pdf",
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
                            "/storage/emulated/0/Download/PowerSync-reader-clean-v2.pdf",
                        ),
                    ) != 1
            )
                throw IOException("Full-pull flag restoration failed")
        }
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
                        File(context.getFilesDir(), "reader-fresh-pdf-private.json").toPath()
                    ),
                    java.nio.charset.StandardCharsets.UTF_8,
                )
            )
        val id: String = "ea6ad9be04304a399583fe557a21f63e"
        val rows: JSONArray = data.getJSONArray("metadata")
        if (
            rows.length() != 1 ||
                !(id == rows.getJSONObject(0).getString("uuid")) ||
                !("37962785464fb87d6ae1fedf7850aca2ea01dc4245af43ce49bb3e4c99b58086" ==
                    data.getString("bookSha256"))
        )
            throw IOException("Exact source PDF metadata required")
        val row: JSONObject = rows.getJSONObject(0)
        val baseline: File = File(context.getFilesDir(), "reader-fresh-pdf-sync-baseline.json")
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
                        "/storage/emulated/0/Download/PowerSync-reader-clean-v2.pdf",
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
        val request: File = File(context.getFilesDir(), "reader-fresh-pdf-metadata-request.json")
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
    fun restartOwnedDocJob(): JSONObject {
        val io: NativeSystemConfigProbe = NativeSystemConfigProbe()
        val flags: JSONObject = io.read("ONYX_SYSTEM_CONFIG_KEY").getJSONObject("configMap")
        if (
            !("powersync_lab" == io.read().getString("curClusterId")) ||
                !("true" == flags.getString("key_enable_sync_reader_data")) ||
                !("false" == flags.getString("key_enable_sync_note_data")) ||
                !("d13306a5e1166eeb9325271dda84cad325fa10ac82b7efc4f367f70a1645d523" ==
                    capture().getString("bookSha256"))
        )
            throw IOException("Exact pending owned Z lab source required")
        val book: String = "ea6ad9be04304a399583fe557a21f63e"
        RemoteBinding(
                context,
                "com.onyx.android.ksync",
                "com.onyx.android.ksync.service.KSyncService",
            )
            .use { binding ->
                val binder: IBinder = binding.await()
                val descriptor: String = "com.onyx.android.sdk.sync.ksync.service.ISyncService"
                if (!(descriptor == binder.getInterfaceDescriptor()))
                    throw IOException("Unexpected owned-job Binder")
                val parcel: Parcel = android.os.Parcel.obtain()
                try {
                    parcel.writeInterfaceToken(descriptor)
                    parcel.writeString(book)
                    if (!binder.transact(14, parcel, null, android.os.IBinder.FLAG_ONEWAY))
                        throw IOException("Owned document stop rejected")
                } finally {
                    parcel.recycle()
                }
            }
        java.lang.Thread.sleep(1500)
        return startLibrary(true)
            .put("ownedDocStopRequested", true)
            .put("completed", false)
            .put("callerUid", android.os.Process.myUid())
            .put("bodyFieldsChanged", false)
    }

    @Throws(java.lang.Exception::class)
    fun requeueCurrentUserData(): JSONObject {
        val io: NativeSystemConfigProbe = NativeSystemConfigProbe()
        val flags: JSONObject = io.read("ONYX_SYSTEM_CONFIG_KEY").getJSONObject("configMap")
        if (
            !("powersync_lab" == io.read().getString("curClusterId")) ||
                !("true" == flags.getString("key_enable_sync_reader_data")) ||
                !("false" == flags.getString("key_enable_sync_note_data"))
        )
            throw IOException("Owned reader-only lab requeue required")
        val captured: JSONObject = capture()
        if (
            !("d13306a5e1166eeb9325271dda84cad325fa10ac82b7efc4f367f70a1645d523" ==
                captured.getString("bookSha256"))
        )
            throw IOException("Exact saved Z source required")
        queryFull()
        val current: JSONObject =
            JSONObject(
                String(
                    java.nio.file.Files.readAllBytes(
                        File(context.getFilesDir(), "reader-fresh-pdf-full-userdata-private.json")
                            .toPath()
                    ),
                    java.nio.charset.StandardCharsets.UTF_8,
                )
            )
        val models: JSONArray = current.getJSONArray("noteDocumentModelList")
        val book: String = "ea6ad9be04304a399583fe557a21f63e"
        if (models.length() != 1) throw IOException("Exactly one current owned model required")
        val model: JSONObject = models.getJSONObject(0)
        if (
            model.getInt("modeType") != 7 ||
                !(book == model.getString("documentId")) ||
                !("e61b286c49594b1686b4bca987a8274b" == model.getString("uniqueId")) ||
                !("powersync-lab" == model.getString("user")) ||
                !("powersync-lab-READER_LIBRARY" == model.getString("dbId"))
        )
            throw IOException("Owned model identity required")
        val payload: JSONObject = JSONObject().put("noteDocumentModelList", models)
        for (list in arrayOf<String>("annotationModelList", "bookmarkModelList")) {
            val values: JSONArray = current.getJSONArray(list)
            for (i in 0..<values.length()) if (
                !(book == values.getJSONObject(i).optString("documentId")) ||
                    !("powersync-lab" == values.getJSONObject(i).optString("user"))
            )
                throw IOException("Owned user-data rows required")
            payload.put(list, values)
        }
        val body: File = File(context.getFilesDir(), "reader-fresh-requeue-payload-private.json")
        ReportFiles.write(context, body.getName(), payload)
        val hash: String = NativeInk.digest(java.nio.file.Files.readAllBytes(body.toPath()))
        RemoteBinding(
                context,
                "com.onyx.android.ksync",
                "com.onyx.android.ksync.service.KSyncService",
            )
            .use { binding ->
                android.os.ParcelFileDescriptor.open(
                        body,
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                    .use { fd ->
                        val binder: IBinder = binding.await()
                        if (
                            !("com.onyx.android.sdk.sync.ksync.service.ISyncService" ==
                                binder.getInterfaceDescriptor())
                        )
                            throw IOException("Unexpected requeue Binder")
                        val parcel: Parcel = android.os.Parcel.obtain()
                        try {
                            writeArgs(parcel, book, null, fd, null)
                            if (!binder.transact(3, parcel, null, android.os.IBinder.FLAG_ONEWAY))
                                throw IOException("Owned native requeue rejected")
                        } finally {
                            parcel.recycle()
                        }
                    }
            }
        return JSONObject()
            .put("requested", true)
            .put("completed", false)
            .put("callerUid", android.os.Process.myUid())
            .put("fixedBookScope", book)
            .put("payloadSha256", hash)
            .put("bodyFieldsChanged", false)
            .put("nativeApplicationObserved", false)
    }

    @Throws(java.lang.Exception::class)
    fun publishConfigConflict(): JSONObject {
        queryFull()
        val request: File = File(context.getFilesDir(), "reader-fresh-config-request.json")
        if (!request.isFile() || request.length() > 1048576)
            throw IOException("Bounded fresh config request required")
        val proposed: JSONObject =
            JSONObject(
                String(
                    java.nio.file.Files.readAllBytes(request.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8,
                )
            )
        val currentBody: JSONObject =
            JSONObject(
                String(
                    java.nio.file.Files.readAllBytes(
                        File(context.getFilesDir(), "reader-fresh-pdf-full-userdata-private.json")
                            .toPath()
                    ),
                    java.nio.charset.StandardCharsets.UTF_8,
                )
            )
        val models: JSONArray = currentBody.getJSONArray("noteDocumentModelList")
        if (models.length() != 1) throw IOException("Exactly one native configuration required")
        val baseline: JSONObject = models.getJSONObject(0)
        val expected: JSONObject = JSONObject(baseline.toString())
        val book: String = "ea6ad9be04304a399583fe557a21f63e"
        val id: String = "e61b286c49594b1686b4bca987a8274b"
        if (
            baseline.getInt("modeType") != 7 ||
                !(book == baseline.getString("documentId")) ||
                !(id == baseline.getString("uniqueId")) ||
                !("powersync-lab" == baseline.getString("user")) ||
                !("powersync-lab-READER_LIBRARY" == baseline.getString("dbId"))
        )
            throw IOException("Unexpected native configuration identity")
        val width: Double = proposed.getDouble("strokeWidth")
        val stamp: Long = proposed.getLong("updatedAt")
        if (
            (width != 3.0 && width != 4.0) ||
                abs(java.lang.System.currentTimeMillis() - stamp) > 300000
        )
            throw IOException("Fixture config changes out of bounds")
        expected.put("strokeWidth", width).put("updatedAt", stamp)
        expected.remove("_id")
        expected.remove("_rev")
        proposed.remove("_id")
        proposed.remove("_rev")
        if (expected.length() != proposed.length())
            throw IOException("Configuration field count changed")
        val keys: MutableIterator<String?> = expected.keys()
        while (keys.hasNext()) {
            val key: String? = keys.next()
            if (
                !proposed.has(key) ||
                    !(expected.get(key).toString() == proposed.get(key).toString())
            )
                throw IOException("Unexpected configuration field change: " + key)
        }
        ReportFiles.write(context, "reader-fresh-config-baseline-private.json", baseline)
        val payload: JSONObject =
            JSONObject()
                .put("annotationModelList", JSONArray())
                .put("bookmarkModelList", JSONArray())
                .put("noteDocumentModelList", JSONArray().put(expected))
        val body: File = File(context.getFilesDir(), "reader-fresh-config-publication-private.json")
        ReportFiles.write(context, body.getName(), payload)
        android.os.ParcelFileDescriptor.open(body, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
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
                            throw IOException("Unexpected configuration Binder")
                        val parcel: Parcel = android.os.Parcel.obtain()
                        try {
                            writeArgs(parcel, book, null, fd, null)
                            if (!binder.transact(3, parcel, null, android.os.IBinder.FLAG_ONEWAY))
                                throw IOException("Configuration save rejected")
                        } finally {
                            parcel.recycle()
                        }
                    }
            }
        return JSONObject()
            .put("requested", true)
            .put("completed", false)
            .put("nativeCblSaveRequested", true)
            .put("strokeWidth", width)
            .put("updatedAt", stamp)
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
        val id: String = "ea6ad9be04304a399583fe557a21f63e"
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
                            "reader-fresh-pdf-full-userdata-private.json",
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
        val id: String = "ea6ad9be04304a399583fe557a21f63e"
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
        val cloudId: String = "ea6ad9be04304a399583fe557a21f63e"
        val id: String = cloudId
        val path: String =
            if (peer)
                "/storage/emulated/0/.ksync/powersync-lab/reader/" +
                    cloudId +
                    "/book/" +
                    cloudId +
                    ".pdf"
            else "/storage/emulated/0/Download/PowerSync-reader-clean-v2.pdf"
        val file: File = File(path)
        if (!file.isFile() || file.length() > 1048576)
            throw IOException("Exact owned PDF required for native sync")
        val pdfDigest: String = NativeInk.digest(java.nio.file.Files.readAllBytes(file.toPath()))
        val annotated: Boolean =
            file.length() == 14954L &&
                "7930cf31cc219cd1171a5e030b4e4059663ad628bd24d55d89f150a24efb072d" == pdfDigest
        val original: Boolean =
            !peer &&
                file.length() == 2340L &&
                "37962785464fb87d6ae1fedf7850aca2ea01dc4245af43ce49bb3e4c99b58086" == pdfDigest
        val erased: Boolean =
            !peer &&
                !pushing &&
                file.length() == 27316L &&
                "b08e9ad5e105beb38e404fd180bfb5615dd5fd8ebeda39815ed8d11da4ebdb00" == pdfDigest
        val concurrent: Boolean =
            !peer &&
                !pushing &&
                ((file.length() == 36768L &&
                    "d9d7560d3bd96f74123de2f7d5507d78a30f9f0030d14dd489a54067293ccd10" ==
                        pdfDigest) ||
                    (file.length() == 8739L &&
                        "0db1571f6e2a37293f28197aa8bf2dc60086e05ffbdf54d90f8421a75673f548" ==
                            pdfDigest))
        val eraseConflict: Boolean =
            !peer &&
                !pushing &&
                ((file.length() == 61789L &&
                    "379cb58a840c151d8da42b237f4b125146df3503b8bedab3b499dcb2869714b7" ==
                        pdfDigest) ||
                    (file.length() == 9243L &&
                        "e0010ed52c8c4d14cb8a3b9f81234eea796c2d5416b6dced55b714e775d0c5e3" ==
                            pdfDigest))
        val knownFragmentDelete: Boolean =
            !peer &&
                !pushing &&
                file.length() == 9747L &&
                "5fdc19a98b8cd8a89b8b4d3ac552ce25cc5659903d4145c4258705f38c7607ba" == pdfDigest
        val uploadZ: Boolean =
            !peer &&
                !pushing &&
                file.length() == 160865L &&
                "d13306a5e1166eeb9325271dda84cad325fa10ac82b7efc4f367f70a1645d523" == pdfDigest
        val uploadY: Boolean =
            !peer &&
                !pushing &&
                file.length() == 143130L &&
                "093b37531c50885146872d94956f8ff0c590e84661f9bda113eff0486e622bf1" == pdfDigest
        val outageX: Boolean =
            !peer &&
                !pushing &&
                ((file.length() == 127750L &&
                    "34b74956cc8f561052c794dbd744747874a61bf949b4e846e48750e6ab5d71a9" ==
                        pdfDigest) ||
                    (file.length() == 136411L &&
                        "75df8c3c6a1f6cc68315762a17c5dcb89ea7b2ae8ec32ea7c1fd6583df698874" ==
                            pdfDigest))
        val outageW: Boolean =
            !peer &&
                !pushing &&
                ((file.length() == 114957L &&
                    "c2b02313b16efb3a47e32a56ee2aa4047af196c31eda5d955e00372d4fa24ece" ==
                        pdfDigest) ||
                    (file.length() == 123626L &&
                        "05fac939312d7597414b6f7b91dcebb063b2af59dc84a4523c68a5d7a87a240d" ==
                            pdfDigest))
        val unattendedV: Boolean =
            !peer &&
                !pushing &&
                ((file.length() == 97531L &&
                    "1642b7925b2406fc5e6098d8f5f2df8de09eb1852ce18845225b6b5660a32a3d" ==
                        pdfDigest) ||
                    (file.length() == 106207L &&
                        "d5f2a2836a209a822d962a52a59aa45cf7cbf16c95d8fdeecd316bcc3bd400e4" ==
                            pdfDigest))
        val unattendedU: Boolean =
            !peer &&
                !pushing &&
                ((file.length() == 80799L &&
                    "5dafb8b0046c8bef1531fd198db0272a9c32e93200d431c40e61bb1b4e398477" ==
                        pdfDigest) ||
                    (file.length() == 89482L &&
                        "049ad2f875b26e7ec147c02188cb56e6d3f972b2ec477ccb0a0a558b38532952" ==
                            pdfDigest))
        val eraseRestoreConflict: Boolean =
            !peer &&
                !pushing &&
                ((file.length() == 71572L &&
                    "fd59291c795bedbb44dc97850d75559f4fa775bd6043686b6f1d2957eba6bcc7" ==
                        pdfDigest) ||
                    (file.length() == 62881L &&
                        "2b9d3ada159e5972ec9182df483bcdc5b15b05bd91639aa4b9e67ee1cac4d101" ==
                            pdfDigest) ||
                    (file.length() == 62375L &&
                        "4040deb8c6dee297bc3db62e6ad891d3a2e2edceccf96a5277974aa9dd5a1006" ==
                            pdfDigest) ||
                    (file.length() == 52987L &&
                        "34770e3d44abc508b78f46336a87997350381316305f778440a789d44a09b4fd" ==
                            pdfDigest))
        if (
            !uploadZ &&
                !uploadY &&
                !outageX &&
                !outageW &&
                !unattendedV &&
                !unattendedU &&
                !annotated &&
                !original &&
                !erased &&
                !concurrent &&
                !eraseConflict &&
                !knownFragmentDelete &&
                !eraseRestoreConflict
        )
            throw IOException("Exact owned PDF revision required for native sync")
        if (!peer && pushing) {
            capture()
            val captured: JSONObject =
                JSONObject(
                    String(
                        java.nio.file.Files.readAllBytes(
                            File(context.getFilesDir(), "reader-fresh-pdf-private.json").toPath()
                        ),
                        java.nio.charset.StandardCharsets.UTF_8,
                    )
                )
            val rows: JSONArray = captured.getJSONArray("metadata")
            if (rows.length() != 1 || !(id == rows.getJSONObject(0).getString("uuid")))
                throw IOException("Owned source PDF identity required")
            val baseline: File = File(context.getFilesDir(), "reader-fresh-pdf-sync-baseline.json")
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
                        ReportFiles.write(
                            context,
                            "reader-fresh-pdf-sync-callback-private.json",
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

    @Throws(java.lang.Exception::class)
    fun transfer(upload: Boolean): JSONObject {
        if (!("powersync_lab" == NativeSystemConfigProbe().read().getString("curClusterId")))
            throw IOException("PDF transfer requires lab endpoint")
        capture()
        val request: File = File(context.getFilesDir(), "reader-fresh-pdf-transfer-request.json")
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
            !("ea6ad9be04304a399583fe557a21f63e" == id) ||
                !(id == model.getString("uuid")) ||
                !("powersync-lab" == model.getString("user")) ||
                !("powersync-lab-READER_LIBRARY" == model.getString("dbId")) ||
                model.getInt("modeType") != 4 ||
                !("PowerSync-reader-clean-v2.pdf" == model.getString("name")) ||
                !("/storage/emulated/0/Download/PowerSync-reader-clean-v2.pdf" ==
                    model.getString("nativeAbsolutePath")) ||
                model.getLong("fileOriginSize") != 2340L
        )
            throw IOException("PDF transfer outside exact owned fixture")
        if (upload) {
            val current: JSONObject =
                JSONObject(
                    String(
                        java.nio.file.Files.readAllBytes(
                            File(context.getFilesDir(), "reader-fresh-pdf-private.json").toPath()
                        ),
                        java.nio.charset.StandardCharsets.UTF_8,
                    )
                )
            if (
                !("37962785464fb87d6ae1fedf7850aca2ea01dc4245af43ce49bb3e4c99b58086" ==
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
        var source: File = File("/storage/emulated/0/Download/PowerSync-reader-clean-v2.pdf")
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
        ReportFiles.write(context, "reader-fresh-pdf-export-request.json", request)
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
                                "reader-fresh-pdf-export-callbacks.json",
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
        val name: String = "PowerSync-reader-clean-v2.pdf"
        var digest: String = "37962785464fb87d6ae1fedf7850aca2ea01dc4245af43ce49bb3e4c99b58086"
        val file: File = File("/storage/emulated/0/Download/" + name).getCanonicalFile()
        if (!file.isFile() || file.length() > 1048576)
            throw IOException("Exact authored geometry PDF required")
        val actual: String = NativeInk.digest(java.nio.file.Files.readAllBytes(file.toPath()))
        // Exact user-authored native derivative observed after the owned pen control.
        if (
            !("8d6c8d2e9cf21c4d4f70a815278489c7ac4f9f3ca03ba46c55d8867f246b9091" == actual) &&
                !("5a42b55895a28d61073e63fb245cf468ee1a4f42b831755dc91f608566f78e5a" == actual) &&
                !("3945945fbb6d155067a14d257f943c28a1f031e67158cb536f33e325b80164a0" == actual) &&
                !("0e15a4483159deb0667f7fc1b7cd668934b62c34c7758836b972aa2a009e9179" == actual) &&
                !("f9a289ffd2e2f86bd298519290092381dce4a718212857840ef7f174764ac235" == actual) &&
                !("af2f34bc90d8fd1f89b8055698ae4a04cd7d07029cf8c662a99fb0879ea2d24a" == actual) &&
                !("369320f68a3ac8f56de14ec7c387b86ee4b38844b65b958fdb49af79fe9f684c" == actual) &&
                !("3238b63a12fe7efc9b17af7c56f9fb05de78c5f4f739d3e0c8b90050c6d8105a" == actual) &&
                !("d13306a5e1166eeb9325271dda84cad325fa10ac82b7efc4f367f70a1645d523" == actual) &&
                !("093b37531c50885146872d94956f8ff0c590e84661f9bda113eff0486e622bf1" == actual) &&
                !("34b74956cc8f561052c794dbd744747874a61bf949b4e846e48750e6ab5d71a9" == actual) &&
                !("75df8c3c6a1f6cc68315762a17c5dcb89ea7b2ae8ec32ea7c1fd6583df698874" == actual) &&
                !("c2b02313b16efb3a47e32a56ee2aa4047af196c31eda5d955e00372d4fa24ece" == actual) &&
                !("05fac939312d7597414b6f7b91dcebb063b2af59dc84a4523c68a5d7a87a240d" == actual) &&
                !("1642b7925b2406fc5e6098d8f5f2df8de09eb1852ce18845225b6b5660a32a3d" == actual) &&
                !("d5f2a2836a209a822d962a52a59aa45cf7cbf16c95d8fdeecd316bcc3bd400e4" == actual) &&
                !("049ad2f875b26e7ec147c02188cb56e6d3f972b2ec477ccb0a0a558b38532952" == actual) &&
                !("5dafb8b0046c8bef1531fd198db0272a9c32e93200d431c40e61bb1b4e398477" == actual) &&
                !("fd59291c795bedbb44dc97850d75559f4fa775bd6043686b6f1d2957eba6bcc7" == actual) &&
                !("852f4dda74f06642fbc8481fe0de01b30055c10e7991635feebec415e383649c" == actual) &&
                !("2b9d3ada159e5972ec9182df483bcdc5b15b05bd91639aa4b9e67ee1cac4d101" == actual) &&
                !("4040deb8c6dee297bc3db62e6ad891d3a2e2edceccf96a5277974aa9dd5a1006" == actual) &&
                !("34770e3d44abc508b78f46336a87997350381316305f778440a789d44a09b4fd" == actual) &&
                !("bb6111ea548fcc870d5d5ae8e7d739b6d6f153abe88bf5ef78caa251f221294e" == actual) &&
                !("5fdc19a98b8cd8a89b8b4d3ac552ce25cc5659903d4145c4258705f38c7607ba" == actual) &&
                !("379cb58a840c151d8da42b237f4b125146df3503b8bedab3b499dcb2869714b7" == actual) &&
                !("e0010ed52c8c4d14cb8a3b9f81234eea796c2d5416b6dced55b714e775d0c5e3" == actual) &&
                !("d9d7560d3bd96f74123de2f7d5507d78a30f9f0030d14dd489a54067293ccd10" == actual) &&
                !("0db1571f6e2a37293f28197aa8bf2dc60086e05ffbdf54d90f8421a75673f548" == actual) &&
                !(digest == actual) &&
                !("7930cf31cc219cd1171a5e030b4e4059663ad628bd24d55d89f150a24efb072d" == actual) &&
                !("b08e9ad5e105beb38e404fd180bfb5615dd5fd8ebeda39815ed8d11da4ebdb00" == actual)
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
        ReportFiles.write(context, "reader-fresh-pdf-private.json", data)
        return JSONObject()
            .put("callerUid", android.os.Process.myUid())
            .put("readOnly", true)
            .put("metadataRows", metadata.length())
            .put("bookSha256", digest)
            .put("privateCapture", "reader-fresh-pdf-private.json")
            .put("nativeGeometryQualified", false)
            .put("nativeInkQualified", false)
    }
}
