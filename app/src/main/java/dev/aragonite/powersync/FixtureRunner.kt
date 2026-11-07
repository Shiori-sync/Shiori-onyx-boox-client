package dev.aragonite.powersync

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory.Options
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Comparator
import java.util.Enumeration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.ToIntFunction
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/** Only operates on the unique disposable fixture recorded by this installation. */
class FixtureRunner @JvmOverloads constructor(context: Context, fixture: String? = "fixture") {
    private val context: Context
    private val notes: NativeNotes
    private val prefs: SharedPreferences

    init {
        this.context = context
        notes = NativeNotes(context)
        prefs = context.getSharedPreferences(fixture, 0)
    }

    @Throws(java.lang.Exception::class)
    fun createInkIntent(): Intent {
        if (prefs.contains("fixtureTitle")) {
            try {
                return open()
            } catch (e: IOException) {
                throw IOException(
                    "Ink creation already requested; inspect native creation screen before retry",
                    e,
                )
            }
        }
        val title: String = "PowerSync ink " + UUID.randomUUID()
        if (!prefs.edit().putString("fixtureTitle", title).commit())
            throw IOException("Cannot journal ink fixture")
        val bean: JSONObject =
            JSONObject()
                .put("title", title)
                .put("parentUniqueId", "")
                .put("create", true)
                .put("createNoteType", "SCRIBBLE_NOTE")
                .put("activeScene", 0)
        return Intent()
            .setComponent(
                ComponentName(
                    NativeNotes.Companion.NOTE_PACKAGE,
                    NativeNotes.Companion.NOTE_PACKAGE + ".note.ui.ScribbleActivity",
                )
            )
            .putExtra("OPEN_NOTE_BEAN", bean.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Throws(java.lang.Exception::class)
    fun find(): JSONObject {
        val title =
            prefs.getString("fixtureTitle", null)
                ?: throw IOException("Create a disposable fixture first")
        val id: String? = prefs.getString("fixtureId", null)
        if (title == null) throw IOException("Create a disposable fixture first")
        // The native text importer may append a numeric suffix even to a unique title.
        val rows: JSONArray =
            notes.notes(
                if (id == null) "(title=? OR title LIKE ?) AND type=?" else "uniqueId=? AND type=?",
                if (id == null) arrayOf<String>(title, title + "-%", "1")
                else arrayOf<String>(id, "1"),
            )
        if (rows.length() != 1)
            throw IOException("Expected exactly one fixture, found " + rows.length())
        val row: JSONObject = rows.getJSONObject(0)
        if (id == null) {
            val actual: String = row.optString("title", "")
            if (
                !(actual == title) &&
                    !actual.matches((java.util.regex.Pattern.quote(title) + "-[0-9]+").toRegex())
            )
                throw IOException("Unexpected fixture title")
            if (!prefs.edit().putString("fixtureId", row.getString("uniqueId")).commit())
                throw IOException("Cannot journal fixture ID")
        }
        return row
    }

    @Throws(java.lang.Exception::class)
    fun create(): JSONObject {
        if (prefs.contains("fixtureTitle")) {
            disableNativeCloud()
            return JSONObject().put("alreadyRequested", true).put("note", find())
        }
        if (!Environment.isExternalStorageManager())
            throw IOException("Grant All Files Access first")
        val title: String = "PowerSync fixture " + UUID.randomUUID()
        val text: String =
            "Disposable PowerSync adapter fixture. Created through native Notes Binder."
        val exchange: File =
            File(
                Environment.getExternalStorageDirectory(),
                "PowerSyncProbe/" + UUID.randomUUID() + ".txt",
            )
        if (!exchange.getParentFile().isDirectory() && !exchange.getParentFile().mkdirs())
            throw IOException("Cannot create exchange directory")
        FileOutputStream(exchange).use { out ->
            out.write(text.toByteArray(StandardCharsets.UTF_8))
            out.getFD().sync()
        }
        if (
            !prefs
                .edit()
                .putString("fixtureTitle", title)
                .putString("exchangeFile", exchange.getAbsolutePath())
                .commit()
        )
            throw IOException("Cannot journal fixture request")
        val bean: JSONObject =
            JSONObject()
                .put("title", title)
                .put("text", text)
                .put("parentUniqueId", "")
                .put("richTextCachePath", exchange.getAbsolutePath())
                .put("activeScene", 1)
                .put("createNoteType", "TEXT_NOTE")
        notes.send("CREATE_NOTE_FROM_TEXT", bean, true)
        val deadline: Long =
            java.lang.System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
        while (java.lang.System.nanoTime() < deadline) {
            try {
                find()
                disableNativeCloud()
                return JSONObject()
                    .put("note", find())
                    .put("pages", notes.pages(find().getString("uniqueId")))
            } catch (e: IOException) {
                java.lang.Thread.sleep(500)
            }
        }
        throw IOException(
            "Native creation not observed; request retained, will not create duplicates"
        )
    }

    @Throws(java.lang.Exception::class)
    fun rename(): JSONObject {
        val row: JSONObject = find()
        val id: String = row.getString("uniqueId")
        val desired: String? = prefs.getString("fixtureTitle", "") + " renamed"
        val bean: JSONObject =
            JSONObject()
                .put("documentId", id)
                .put("title", desired)
                .put("parentUniqueId", row.optString("parentUniqueId", ""))
        notes.send("RENAME_NOTE", JSONObject().put("noteBean", bean), false)
        for (i in 0..39) {
            val current: JSONObject = find()
            if (desired == current.optString("title"))
                return JSONObject()
                    .put("sameIdentity", id == current.getString("uniqueId"))
                    .put("note", current)
            java.lang.Thread.sleep(250)
        }
        throw IOException("Rename command not observed")
    }

    @Throws(java.lang.Exception::class)
    fun disableNativeCloud(): JSONObject {
        var row: JSONObject = find()
        val id: String = row.getString("uniqueId")
        if (row.optInt("noteSyncStatus", -1) != 0) {
            notes.send(
                "UPDATE_SYNC_STATUS",
                JSONObject().put("documentId", id).put("noteSyncStatus", 0),
                false,
            )
            for (i in 0..39) {
                row = find()
                if (row.optInt("noteSyncStatus", -1) == 0) break
                java.lang.Thread.sleep(250)
            }
        }
        if (row.optInt("noteSyncStatus", -1) != 0)
            throw IOException("Native cloud disable not observed")
        return JSONObject().put("documentId", id).put("nativeCloudSyncDisabled", true)
    }

    @Throws(java.lang.Exception::class)
    fun export(): JSONObject {
        return exportRow(find())
    }

    @Throws(java.lang.Exception::class)
    fun exportRow(row: JSONObject): JSONObject {
        val token: String = "PowerSync-export-" + UUID.randomUUID()
        val result: CompletableFuture<JSONObject> = CompletableFuture<JSONObject>()
        val receiver: BroadcastReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent) {
                    try {
                        val raw: String? = intent.getStringExtra("SERVICE_INTENT_RESULT_JSON")
                        if (raw == null || raw.length > 1048576) return
                        val event: JSONObject = JSONObject(raw)
                        if (!(token == event.optString("exportTitle"))) return
                        if (event.optInt("status", -1) == 0) result.complete(event)
                        else if (event.optInt("status", -1) == 1)
                            result.completeExceptionally(IOException("Native export failed"))
                    } catch (ignored: java.lang.Exception) {}
                }
            }
        val filter: IntentFilter =
            IntentFilter("com.onyx.android.sdk.note.ui.service.SERVICE_INTENT_RESULT")
        registerNativeResult(receiver, filter)
        try {
            val args: JSONObject? =
                JSONObject()
                    .put("format", 5)
                    .put("remoteExport", true)
                    .put("documentId", row.getString("uniqueId"))
                    .put("parentUniqueId", row.optString("parentUniqueId", ""))
                    .put("title", token)
                    .put("noteTitle", token)
                    .put("noteScene", row.optInt("activeScene", 1))
                    .put("userId", row.optString("userId", ""))
                    .put("exportInvisibleLayers", true)
            notes.send("EXPORT_NOTE", JSONObject().put("exportNoteArgs", args), false)
            val event: JSONObject = result.get(90, java.util.concurrent.TimeUnit.SECONDS)
            val path: String = event.getJSONObject("exportNoteArgs").getString("exportPath")
            val file: File = File(path).getCanonicalFile()
            val storage: File = Environment.getExternalStorageDirectory().getCanonicalFile()
            if (!file.toPath().startsWith(storage.toPath()) || !(file.getName() == token + ".note"))
                throw IOException("Unexpected export path")
            val manifest: JSONObject = NativeArchive.inspect(file)
            try {
                manifest.put(
                    "inkIdentity",
                    NativeArchive.inkIdentity(file, row.getString("uniqueId")),
                )
            } catch (e: java.lang.Exception) {
                manifest.put(
                    "inkIdentity",
                    JSONObject().put("supported", false).put("reason", e.toString()),
                )
            }
            return JSONObject()
                .put("documentId", row.getString("uniqueId"))
                .put("archivePath", file.getPath())
                .put("archive", manifest)
                .put("completeNativeSnapshot", false)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerNativeResult(receiver: BroadcastReceiver?, filter: IntentFilter?) {
        // Flags are explicit on Android 13+. The legacy branch only runs on API 30-32,
        // whose two-argument registration is exported so the vendor UID can respond.
        if (android.os.Build.VERSION.SDK_INT >= 33)
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }

    @Throws(java.lang.Exception::class)
    fun open(): Intent {
        return notes.openIntent(find())
    }

    @Throws(java.lang.Exception::class)
    fun insertImage(): Intent {
        disableNativeCloud()
        val row: JSONObject = find()
        if (prefs.contains("imageRequest")) return notes.openIntent(row)
        val image: File =
            File(
                Environment.getExternalStorageDirectory(),
                "PowerSyncProbe/fixture-image-" + UUID.randomUUID() + ".png",
            )
        if (!image.getParentFile().isDirectory() && !image.getParentFile().mkdirs())
            throw IOException("Cannot create image exchange directory")
        val bitmap: Bitmap =
            android.graphics.Bitmap.createBitmap(256, 128, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas: Canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint: Paint = Paint()
        paint.setColor(android.graphics.Color.BLACK)
        paint.setStrokeWidth(6f)
        canvas.drawLine(0f, 0f, 256f, 128f, paint)
        canvas.drawLine(0f, 128f, 256f, 0f, paint)
        paint.setStyle(android.graphics.Paint.Style.STROKE)
        canvas.drawRect(3f, 3f, 253f, 125f, paint)
        try {
            FileOutputStream(image).use { out ->
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out))
                    throw IOException("Cannot encode fixture image")
                out.getFD().sync()
            }
        } finally {
            bitmap.recycle()
        }
        if (!prefs.edit().putString("imageRequest", image.getPath()).commit())
            throw IOException("Cannot journal image insertion")
        val intent: Intent = notes.openIntent(row)
        val bean: JSONObject = JSONObject(intent.getStringExtra("OPEN_NOTE_BEAN"))
        bean.put("insertFiles", JSONArray().put(image.getPath())).put("insertFileType", 0)
        return intent.putExtra("OPEN_NOTE_BEAN", bean.toString())
    }

    @Throws(java.lang.Exception::class)
    fun writeTextResource(): JSONObject {
        disableNativeCloud()
        val row: JSONObject = find()
        val id: String = row.getString("uniqueId")
        if (!id.matches("[0-9a-fA-F-]{36}".toRegex())) throw IOException("Unexpected fixture ID")
        val dir: File =
            File(
                    Environment.getExternalStorageDirectory(),
                    ".ksync/document/" + id + "/resource/data",
                )
                .getCanonicalFile()
        val files: Array<File> = dir.listFiles()
        if (files == null) throw IOException("No fixture resources")
        var target: File? = null
        var content: JSONObject? = null
        for (file in files) {
            if (!file.getName().endsWith(".html")) continue
            if (!(file.getCanonicalFile().getParentFile() == dir) || file.length() > 1048576)
                throw IOException("Unexpected fixture resource")
            val candidate: JSONObject =
                JSONObject(
                    String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                )
            if (candidate.optInt("docVersion") == 2) {
                if (target != null) throw IOException("Ambiguous editor resource; refusing write")
                target = file
                content = candidate
            }
        }
        if (target == null || content == null)
            throw IOException("No current native editor resource")
        var marker: String? = prefs.getString("writeMarker", null)
        if (marker == null) {
            marker = "PowerSync inbound " + UUID.randomUUID()
            ReportFiles.write(
                context,
                "fixture-resource-before.json",
                JSONObject()
                    .put("documentId", id)
                    .put("path", target.getPath())
                    .put("content", content),
            )
            if (
                !prefs
                    .edit()
                    .putString("writeMarker", marker)
                    .putString("writeResource", target.getPath())
                    .commit()
            )
                throw IOException("Cannot journal resource write")
        } else if (!(target.getPath() == prefs.getString("writeResource", ""))) {
            throw IOException("Native resource changed since journal; inspect before retry")
        }
        val text: JSONObject = JSONObject().put("type", "text").put("text", marker)
        val paragraph: JSONObject =
            JSONObject().put("type", "paragraph").put("content", JSONArray().put(text))
        content.put(
            "content",
            JSONObject().put("type", "doc").put("content", JSONArray().put(paragraph)),
        )
        content.put("currentPos", 1)
        val atomic: AtomicFile = AtomicFile(target)
        val out: FileOutputStream = atomic.startWrite()
        try {
            out.write(content.toString().toByteArray(StandardCharsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: java.lang.Exception) {
            atomic.failWrite(out)
            throw e
        }
        notes.notifyFixtureData(id)
        return JSONObject()
            .put("documentId", id)
            .put("resource", target.getName())
            .put("marker", marker)
            .put("sameIdentity", id == find().getString("uniqueId"))
            .put("nativeEditorVerified", false)
    }

    @JvmOverloads
    @Throws(java.lang.Exception::class)
    fun stageInkPayload(interruptAfterPoints: Boolean = false): JSONObject {
        disableNativeCloud()
        val row: JSONObject = find()
        val id: String = row.getString("uniqueId")
        if (!id.matches("[0-9a-fA-F-]{32,36}".toRegex())) throw IOException("Unexpected fixture ID")
        val exchange: File =
            File(Environment.getExternalStorageDirectory(), "PowerSyncProbe/ink-inbound.zip")
        val received: ByteArray
        FileInputStream(exchange).use { `in` -> received = NativeInk.read(`in`) }
        val receipt: String = NativeInk.digest(received)
        val inbox: File = File(context.getFilesDir(), "ink-inbox")
        if (!inbox.isDirectory() && !inbox.mkdirs())
            throw IOException("Cannot create private inbox")
        val source: File = File(inbox, receipt + ".zip")
        if (!source.exists()) {
            val saved: AtomicFile = AtomicFile(source)
            val out: FileOutputStream = saved.startWrite()
            try {
                out.write(received)
                saved.finishWrite(out)
            } catch (e: java.lang.Exception) {
                saved.failWrite(out)
                throw e
            }
        } else if (
            !(receipt == NativeInk.digest(java.nio.file.Files.readAllBytes(source.toPath())))
        )
            throw IOException("Private receipt integrity mismatch")
        val journalName: String = "ink-apply-" + receipt + ".json"
        val manifest: JSONObject = NativeArchive.inspect(source)
        val root: File =
            File(Environment.getExternalStorageDirectory(), ".ksync").getCanonicalFile()
        val paths: ArrayList<String> = ArrayList<String>()
        ZipFile(source).use { zip ->
            val entries: Enumeration<out ZipEntry> = zip.entries()
            while (entries.hasMoreElements()) {
                val entry: ZipEntry = entries.nextElement()
                val path: String = entry.getName()
                if (entry.isDirectory()) continue
                val quoted: String = java.util.regex.Pattern.quote(id)
                if (
                    !path.matches(
                        ("document/" +
                                quoted +
                                "/shape/[0-9a-fA-F-]{32,36}#[0-9a-fA-F-]{32,36}#[0-9]+\\.zip")
                            .toRegex()
                    ) &&
                        !path.matches(
                            ("document/" + quoted + "/resource/data/[0-9a-fA-F-]{32,36}\\.png")
                                .toRegex()
                        ) &&
                        !path.matches(
                            ("document/" + quoted + "/resource/pb/[0-9a-fA-F-]{32,36}#[0-9]+")
                                .toRegex()
                        ) &&
                        !path.matches(
                            ("point/" +
                                    quoted +
                                    "/[0-9a-fA-F-]{32,36}/[0-9a-fA-F-]{32,36}#[0-9a-fA-F-]{32,36}#points")
                                .toRegex()
                        )
                )
                    throw IOException("Payload outside ink fixture scope")

                paths.add(path)
            }
            if (paths.size != 2 && paths.size != 4)
                throw IOException("Lab payload requires exactly one shape revision and point file")
            val shapePath: String =
                paths
                    .stream()
                    .filter { path: String -> path.contains("/shape/") }
                    .findFirst()
                    .orElseThrow()
            val pointPath: String =
                paths
                    .stream()
                    .filter { path: String -> path.startsWith("point/") }
                    .findFirst()
                    .orElseThrow()
            val page: String =
                shapePath
                    .substring(shapePath.lastIndexOf('/') + 1)
                    .split("#".toRegex())
                    .dropLastWhile { it.isEmpty() }
                    .toTypedArray()[0]
            val pointPage: String =
                pointPath
                    .substring(pointPath.lastIndexOf('/') + 1)
                    .split("#".toRegex())
                    .dropLastWhile { it.isEmpty() }
                    .toTypedArray()[0]
            val pointRevision: String =
                pointPath
                    .substring(pointPath.lastIndexOf('/') + 1)
                    .split("#".toRegex())
                    .dropLastWhile { it.isEmpty() }
                    .toTypedArray()[1]
            if (
                !(page == pointPage) ||
                    !(pointPath
                        .split("/".toRegex())
                        .dropLastWhile { it.isEmpty() }
                        .toTypedArray()[2] == page)
            )
                throw IOException("Payload page mismatch")
            val nativePages: JSONArray = notes.pages(id).getJSONArray("pageIds")
            var mapped: Boolean = false
            for (i in 0..<nativePages.length()) if (page == nativePages.getString(i)) mapped = true
            if (!mapped) throw IOException("Payload page is not mapped to ink fixture")
            zip.getInputStream(zip.getEntry(shapePath)).use { shapes ->
                zip.getInputStream(zip.getEntry(pointPath)).use { points ->
                    NativeInk.validate(
                        NativeInk.read(shapes),
                        NativeInk.read(points),
                        page,
                        pointRevision,
                    )
                }
            }
            if (paths.size == 4) {
                val assetPath: String =
                    paths
                        .stream()
                        .filter { path: String -> path.contains("/resource/data/") }
                        .findFirst()
                        .orElseThrow()
                val resourcePath: String =
                    paths
                        .stream()
                        .filter { path: String -> path.contains("/resource/pb/") }
                        .findFirst()
                        .orElseThrow()
                val shapeBytes: ByteArray
                val resourceBytes: ByteArray
                val imageBytes: ByteArray
                zip.getInputStream(zip.getEntry(shapePath)).use { `in` ->
                    shapeBytes = NativeInk.read(`in`)
                }
                zip.getInputStream(zip.getEntry(resourcePath)).use { `in` ->
                    resourceBytes = NativeInk.read(`in`)
                }
                zip.getInputStream(zip.getEntry(assetPath)).use { `in` ->
                    imageBytes = NativeInk.read(`in`)
                }
                val shape: MutableMap<Int, Any?>
                ZipInputStream(ByteArrayInputStream(shapeBytes)).use { nested ->
                    if (nested.getNextEntry() == null) throw IOException("Missing image shape")
                    shape =
                        NativeInk.fields(
                            NativeInk.fields(NativeInk.read(nested)).get(1) as ByteArray
                        )
                }
                if (!(19L == shape.get(12)))
                    throw IOException("Image resources require native image shape")
                val ref: JSONObject =
                    JSONObject(String(shape.get(14) as ByteArray, StandardCharsets.UTF_8))
                val resource: MutableMap<Int, Any?> =
                    NativeInk.fields(NativeInk.fields(resourceBytes).get(1) as ByteArray)
                val resourceId: String =
                    String(resource.get(1) as ByteArray, StandardCharsets.UTF_8)
                val resourceDoc: String =
                    String(resource.get(2) as ByteArray, StandardCharsets.UTF_8)
                val relative: String = String(resource.get(8) as ByteArray, StandardCharsets.UTF_8)
                val resourceRevision: String =
                    String(resource.get(11) as ByteArray, StandardCharsets.UTF_8)
                val expectedRevision: String =
                    resourcePath
                        .substring(resourcePath.lastIndexOf('/') + 1)
                        .split("#".toRegex())
                        .dropLastWhile { it.isEmpty() }
                        .toTypedArray()[0]
                if (
                    !(id == resourceDoc) ||
                        !(id == ref.getString("documentId")) ||
                        !(resourceId == ref.getString("uniqueId")) ||
                        !(relative == ref.getString("relativePath")) ||
                        !(assetPath == "document/" + id + "/resource/data" + relative) ||
                        !(resourceRevision == expectedRevision) ||
                        !(resourceRevision == ref.getString("revisionId"))
                )
                    throw IOException("Image resource references mismatch")
                val dimensions: Options = Options()
                dimensions.inJustDecodeBounds = true
                android.graphics.BitmapFactory.decodeByteArray(
                    imageBytes,
                    0,
                    imageBytes.size,
                    dimensions,
                )
                if (
                    dimensions.outWidth < 1 ||
                        dimensions.outHeight < 1 ||
                        dimensions.outWidth > 2048 ||
                        dimensions.outHeight > 2048
                )
                    throw IOException("Invalid fixture image dimensions")
            }
            // A retry may find some or all immutable files already written.
            for (path in paths) {
                val target: File = File(root, path).getCanonicalFile()
                if (!target.toPath().startsWith(root.toPath()))
                    throw IOException("Unexpected payload target")
                if (target.exists()) {
                    FileInputStream(target).use { actual ->
                        zip.getInputStream(zip.getEntry(path)).use { expected ->
                            if (!NativeInk.read(actual).contentEquals(NativeInk.read(expected)))
                                throw IOException("Conflicting bytes for immutable native revision")
                        }
                    }
                }
            }
            // Points must be durable before publishing the revision that references them.
            paths.sortWith(
                java.util.Comparator.comparingInt<String>(
                    ToIntFunction { path: String ->
                        if (path.contains("/resource/data/")) 0
                        else if (path.contains("/resource/pb/")) 1
                        else if (path.startsWith("point/")) 2 else 3
                    }
                )
            )
            val journal: JSONObject =
                JSONObject()
                    .put("documentId", id)
                    .put("manifest", manifest)
                    .put("paths", JSONArray(paths))
                    .put("receipt", receipt)
                    .put("phase", "prepared")
            ReportFiles.write(context, journalName, journal)
            for (path in paths) {
                val target: File = File(root, path).getCanonicalFile()
                if (!target.toPath().startsWith(root.toPath()))
                    throw IOException("Unexpected payload target")
                if (target.exists()) continue
                if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs())
                    throw IOException("Cannot create native payload directory")
                val atomic: AtomicFile = AtomicFile(target)
                val out: FileOutputStream = atomic.startWrite()
                try {
                    zip.getInputStream(zip.getEntry(path)).use { `in` ->
                        val buffer: ByteArray = ByteArray(8192)
                        var n: Int
                        var count: Long = 0
                        while ((`in`.read(buffer).also { n = it }) != -1) {
                            count += n.toLong()
                            if (count > 1048576) throw IOException("Fixture payload exceeds limit")
                            out.write(buffer, 0, n)
                        }
                        atomic.finishWrite(out)
                    }
                } catch (e: java.lang.Exception) {
                    atomic.failWrite(out)
                    throw e
                }
                if (interruptAfterPoints && path.startsWith("point/")) {
                    ReportFiles.write(context, journalName, journal.put("phase", "points-staged"))
                    throw IOException("Injected interruption after durable point staging")
                }
            }
            ReportFiles.write(context, journalName, journal.put("phase", "staged"))
            notes.notifyFixtureData(id)
            ReportFiles.write(context, journalName, journal.put("phase", "notified"))
        }
        return JSONObject()
            .put("documentId", id)
            .put("staged", true)
            .put("sameIdentity", id == find().getString("uniqueId"))
            .put("nativeEditorVerified", false)
    }

    @Throws(java.lang.Exception::class)
    fun capture(): JSONObject {
        val row: JSONObject = find()
        val id: String = row.getString("uniqueId")
        val report: JSONObject =
            JSONObject()
                .put("completeNativeSnapshot", false)
                .put("note", row)
                .put("pages", notes.pages(id))
        // Provider routing for per-document shapes is global; never pretend an arbitrary open DB
        // belongs to this fixture.
        return report
    }
}
