package dev.aragonite.powersync

import android.content.Context
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
import java.util.function.ToIntFunction
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/** Explicit recovery of one owned lab rich-text shape; never a general restore. */
class NativeRichTextRecoveryLab(context: Context) {
    private val context: Context

    init {
        this.context = context
    }

    @Throws(java.lang.Exception::class)
    private fun current(): JSONObject {
        val report: JSONObject = NativeGatewayLab(context).exportShared()
        ZipFile(report.getString("archivePath")).use { z ->
            var found: MutableMap<Int, Any?>? = null
            val entries: Enumeration<out ZipEntry> = z.entries()
            while (entries.hasMoreElements()) {
                val e: ZipEntry = entries.nextElement()
                if (
                    !e.getName().startsWith(NativeRichTextRecoveryLab.Companion.DOC + "/shape/") ||
                        !e.getName().endsWith(".zip")
                )
                    continue
                val row: MutableMap<Int, Any?>
                z.getInputStream(e).use { `in` ->
                    row = NativeRichTextRecoveryLab.Companion.shape(NativeInk.read(`in`))
                }
                if (!(29L == row.get(12))) continue
                if (
                    found != null ||
                        !(NativeRichTextRecoveryLab.Companion.SHAPE ==
                            NativeRichTextRecoveryLab.Companion.str(row, 1))
                )
                    throw IOException("Ambiguous current text shape")
                found = row
            }
            if (found == null) throw IOException("Missing current text shape")
            val ref: JSONObject = JSONObject(NativeRichTextRecoveryLab.Companion.str(found, 14))
            val id: String = ref.getString("uniqueId")
            if (
                !(NativeRichTextRecoveryLab.Companion.DOC == ref.getString("documentId")) ||
                    !id.matches("[0-9a-f-]{36}".toRegex()) ||
                    !(("/" + id + ".html") == ref.getString("relativePath"))
            )
                throw IOException("Unsafe current text reference")
            val e: ZipEntry =
                z.getEntry(
                    NativeRichTextRecoveryLab.Companion.DOC + "/resource/data/" + id + ".html"
                )
            if (e == null) throw IOException("Missing active text asset")
            val raw: ByteArray
            z.getInputStream(e).use { `in` -> raw = NativeInk.read(`in`) }
            return JSONObject().put("resourceId", id).put("activeSha256", NativeInk.digest(raw))
        }
    }

    @Throws(java.lang.Exception::class)
    fun apply(): JSONObject {
        val config: NativeSystemConfigProbe = NativeSystemConfigProbe()
        if (!(NativeClusterLab.Companion.LAB_ID == config.read().getString("curClusterId")))
            throw IOException("Lab cluster required")
        if (
            !("false" ==
                config
                    .read("ONYX_SYSTEM_CONFIG_KEY")
                    .getJSONObject("configMap")
                    .optString("key_enable_sync_note_data"))
        )
            throw IOException("Pause native note sync before recovery")
        val note: JSONObject = NativeGatewayLab(context).selectedCapture().getJSONObject("note")
        if (
            !(NativeRichTextRecoveryLab.Companion.DOC == note.getString("uniqueId")) ||
                !("powersync-lab" == note.optString("userId")) ||
                note.optInt("activeScene") != 1 ||
                note.optInt("status") != 1
        )
            throw IOException("Owned live text fixture required")
        val requestFile: File = File(context.getFilesDir(), "richtext-recovery-request.json")
        if (requestFile.length() > 8192) throw IOException("Oversized recovery request")
        val request: JSONObject =
            JSONObject(
                String(
                    java.nio.file.Files.readAllBytes(requestFile.toPath()),
                    StandardCharsets.UTF_8,
                )
            )
        if (
            !request.optBoolean("explicitRecovery") ||
                !request.optBoolean("textOnly") ||
                !(NativeRichTextRecoveryLab.Companion.DOC == request.getString("documentId")) ||
                !(NativeRichTextRecoveryLab.Companion.SHAPE == request.getString("shapeId"))
        )
            throw IOException("Explicit scoped recovery required")
        val exchange: File =
            File(Environment.getExternalStorageDirectory(), "PowerSyncProbe/richtext-recovery.zip")
        val incoming: ByteArray
        FileInputStream(exchange).use { `in` -> incoming = NativeInk.read(`in`) }
        val receipt: String = NativeInk.digest(incoming)
        if (!(receipt == request.getString("payloadSha256")))
            throw IOException("Recovery payload hash mismatch")
        val inbox: File = File(context.getFilesDir(), "richtext-recovery-inbox")
        if (!inbox.isDirectory() && !inbox.mkdirs())
            throw IOException("Cannot create private recovery inbox")
        val source: File = File(inbox, receipt + ".zip")
        if (!source.exists()) {
            val saved: AtomicFile = AtomicFile(source)
            val out: FileOutputStream = saved.startWrite()
            try {
                out.write(incoming)
                saved.finishWrite(out)
            } catch (e: java.lang.Exception) {
                saved.failWrite(out)
                throw e
            }
        } else if (
            !(receipt == NativeInk.digest(java.nio.file.Files.readAllBytes(source.toPath())))
        )
            throw IOException("Recovery receipt corrupted")
        NativeArchive.inspect(source)
        val journalName: String = "richtext-recovery-" + receipt + ".json"
        ZipFile(source).use { z ->
            val paths: MutableList<String> = ArrayList<String>()
            val entries: Enumeration<out ZipEntry> = z.entries()
            val q: String = java.util.regex.Pattern.quote(NativeRichTextRecoveryLab.Companion.DOC)
            while (entries.hasMoreElements()) {
                val e: ZipEntry = entries.nextElement()
                val n: String = e.getName()
                if (
                    e.isDirectory() ||
                        !(n.matches(
                            ("document/" + q + "/resource/data/[0-9a-f-]{36}\\.html").toRegex()
                        ) ||
                            n.matches(
                                ("document/" + q + "/resource/pb/[0-9a-f-]{36}#[0-9]+").toRegex()
                            ) ||
                            n.matches(
                                ("document/" +
                                        q +
                                        "/shape/[0-9a-f-]{36}#[0-9a-f-]{36}#[0-9]+\\.zip")
                                    .toRegex()
                            ) ||
                            n.matches(
                                ("point/" + q + "/[0-9a-f-]{36}/[0-9a-f-]{36}#[0-9a-f-]{36}#points")
                                    .toRegex()
                            ))
                )
                    throw IOException("Recovery path outside fixture")
                paths.add(n)
            }
            if (paths.size != 4) throw IOException("Require one complete text revision")
            val html: String = NativeRichTextRecoveryLab.Companion.one(paths, "/resource/data/")
            val resourcePath: String =
                NativeRichTextRecoveryLab.Companion.one(paths, "/resource/pb/")
            val shapePath: String = NativeRichTextRecoveryLab.Companion.one(paths, "/shape/")
            val pointPath: String = NativeRichTextRecoveryLab.Companion.one(paths, "point/")
            val shapeParts: Array<String> =
                shapePath
                    .substring(shapePath.lastIndexOf('/') + 1)
                    .split("#".toRegex())
                    .dropLastWhile { it.isEmpty() }
                    .toTypedArray()
            val page: String = shapeParts[0]
            val pointParts: Array<String> =
                pointPath
                    .substring(pointPath.lastIndexOf('/') + 1)
                    .split("#".toRegex())
                    .dropLastWhile { it.isEmpty() }
                    .toTypedArray()
            if (
                !(page == request.getString("pageId")) ||
                    !(page == pointParts[0]) ||
                    !(page ==
                        pointPath
                            .split("/".toRegex())
                            .dropLastWhile { it.isEmpty() }
                            .toTypedArray()[2])
            )
                throw IOException("Recovery page mismatch")
            val pages: JSONArray =
                JSONObject(note.getString("richTextPageNameList")).getJSONArray("pageNameList")
            if (pages.length() != 1 || !(page == pages.getString(0)))
                throw IOException("Recovery page is not current text page")
            val shapes: ByteArray = NativeRichTextRecoveryLab.Companion.read(z, shapePath)
            val points: ByteArray = NativeRichTextRecoveryLab.Companion.read(z, pointPath)
            val raw: ByteArray = NativeRichTextRecoveryLab.Companion.read(z, html)
            NativeInk.validate(shapes, points, page, pointParts[1])
            val row: MutableMap<Int, Any?> = NativeRichTextRecoveryLab.Companion.shape(shapes)
            if (
                !(29L == row.get(12)) ||
                    !(NativeRichTextRecoveryLab.Companion.SHAPE ==
                        NativeRichTextRecoveryLab.Companion.str(row, 1)) ||
                    !(shapeParts[1] == NativeRichTextRecoveryLab.Companion.str(row, 18))
            )
                throw IOException("Recovery shape mismatch")
            val env: MutableMap<Int, Any?> =
                NativeInk.fields(NativeRichTextRecoveryLab.Companion.read(z, resourcePath))
            if (env.size != 1 || !(env.get(1) is ByteArray))
                throw IOException("Recovery resource envelope mismatch")
            val res: MutableMap<Int, Any?> = NativeInk.fields(env.get(1) as ByteArray)
            val ref: JSONObject = JSONObject(NativeRichTextRecoveryLab.Companion.str(row, 14))
            val uid: String = NativeRichTextRecoveryLab.Companion.str(res, 1)
            val relative: String = NativeRichTextRecoveryLab.Companion.str(res, 8)
            val rev: String = NativeRichTextRecoveryLab.Companion.str(res, 11)
            if (
                !(uid == request.getString("newResourceId")) ||
                    !(NativeRichTextRecoveryLab.Companion.DOC ==
                        NativeRichTextRecoveryLab.Companion.str(res, 2)) ||
                    !(3L == res.get(7)) ||
                    !(0L == res.get(13)) ||
                    !(("/" + uid + ".html") == relative) ||
                    !(rev ==
                        resourcePath
                            .substring(resourcePath.lastIndexOf('/') + 1)
                            .split("#".toRegex())
                            .dropLastWhile { it.isEmpty() }
                            .toTypedArray()[0]) ||
                    !(uid == ref.getString("uniqueId")) ||
                    !(NativeRichTextRecoveryLab.Companion.DOC == ref.getString("documentId")) ||
                    !(relative == ref.getString("relativePath")) ||
                    !(rev == ref.getString("revisionId")) ||
                    ref.optInt("type") != 3 ||
                    ref.optInt("status") != 0 ||
                    !(html ==
                        "document/" +
                            NativeRichTextRecoveryLab.Companion.DOC +
                            "/resource/data" +
                            relative)
            )
                throw IOException("Recovery resource reference mismatch")
            val body: JSONObject = JSONObject(String(raw, StandardCharsets.UTF_8))
            if (
                body.optInt("docVersion") != 2 ||
                    !("doc" == body.getJSONObject("content").optString("type")) ||
                    !(NativeInk.digest(raw) == request.getString("retainedSha256"))
            )
                throw IOException("Recovery content mismatch")
            NativeRichTextRecoveryLab.Companion.textOnly(body.getJSONObject("content"), 0)
            val before: JSONObject = current()
            if (
                uid == before.getString("resourceId") &&
                    request.getString("retainedSha256") == before.getString("activeSha256") &&
                    File(context.getFilesDir(), journalName).exists()
            )
                return JSONObject()
                    .put("alreadyApplied", true)
                    .put("receipt", receipt)
                    .put("nativeEditorVerified", false)
            if (
                !(request.getString("expectedResourceId") == before.getString("resourceId")) ||
                    !(request.getString("expectedActiveSha256") == before.getString("activeSha256"))
            )
                throw IOException("Active text changed since recovery was prepared")
            val root: File =
                File(Environment.getExternalStorageDirectory(), ".ksync").getCanonicalFile()
            for (path in paths) {
                val target: File = File(root, path).getCanonicalFile()
                if (!target.toPath().startsWith(root.toPath()))
                    throw IOException("Unsafe recovery target")
                if (
                    target.exists() &&
                        !java.nio.file.Files.readAllBytes(target.toPath())
                            .contentEquals(NativeRichTextRecoveryLab.Companion.read(z, path))
                )
                    throw IOException("Immutable recovery revision conflict")
            }
            paths.sortWith(
                Comparator.comparingInt<String>(
                    ToIntFunction { n: String ->
                        if (n.contains("/resource/data/")) 0
                        else if (n.contains("/resource/pb/")) 1
                        else if (n.startsWith("point/")) 2 else 3
                    }
                )
            )
            val journal: JSONObject =
                JSONObject()
                    .put("request", request)
                    .put("receipt", receipt)
                    .put("before", before)
                    .put("phase", "prepared")
            ReportFiles.write(context, journalName, journal)
            for (path in paths) {
                val target: File = File(root, path).getCanonicalFile()
                if (target.exists()) continue
                if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs())
                    throw IOException("Cannot stage recovery directory")
                val saved: AtomicFile = AtomicFile(target)
                val out: FileOutputStream = saved.startWrite()
                try {
                    out.write(NativeRichTextRecoveryLab.Companion.read(z, path))
                    saved.finishWrite(out)
                } catch (e: java.lang.Exception) {
                    saved.failWrite(out)
                    throw e
                }
            }
            ReportFiles.write(context, journalName, journal.put("phase", "staged"))
            NativeNotes(context).notifyFixtureData(NativeRichTextRecoveryLab.Companion.DOC)
            ReportFiles.write(context, journalName, journal.put("phase", "native_import_requested"))
            return JSONObject()
                .put("documentId", NativeRichTextRecoveryLab.Companion.DOC)
                .put("shapeId", NativeRichTextRecoveryLab.Companion.SHAPE)
                .put("newResourceId", uid)
                .put("staged", true)
                .put("receipt", receipt)
                .put("nativeImportRequested", true)
                .put("nativeEditorVerified", false)
        }
    }

    companion object {
        private const val DOC: String = "f7f635344f624650aa300f84d114a1d3"
        private const val SHAPE: String = "c57e650a-6226-4ab5-a0a9-5e6830f40c84"

        @Throws(java.lang.Exception::class)
        private fun str(row: MutableMap<Int, Any?>, field: Int): String {
            if (!(row.get(field) is ByteArray)) throw IOException("Missing recovery string")
            return String(row.get(field) as ByteArray, StandardCharsets.UTF_8)
        }

        @Throws(java.lang.Exception::class)
        private fun shape(raw: ByteArray?): MutableMap<Int, Any?> {
            ZipInputStream(ByteArrayInputStream(raw)).use { z ->
                if (z.getNextEntry() == null) throw IOException("Missing shape envelope")
                val env: MutableMap<Int, Any?> = NativeInk.fields(NativeInk.read(z))
                if (z.getNextEntry() != null || env.size != 1 || !(env.get(1) is ByteArray))
                    throw IOException("Ambiguous shape envelope")
                return NativeInk.fields(env.get(1) as ByteArray)
            }
        }

        @Throws(java.lang.Exception::class)
        private fun textOnly(node: Any?, depth: Int) {
            if (depth > 64) throw IOException("Recovery content nesting exceeds bound")
            if (node is JSONObject) {
                val type: String? = node.optString("type")
                if (
                    mutableListOf<String?>("image", "audio", "video", "file", "attachment")
                        .contains(type)
                )
                    throw IOException("Recovery attachments need a closed asset graph")
                val keys: MutableIterator<String?> = node.keys()
                while (keys.hasNext()) NativeRichTextRecoveryLab.Companion.textOnly(
                    node.get(keys.next()),
                    depth + 1,
                )
            } else if (node is JSONArray)
                for (i in 0..<node.length()) NativeRichTextRecoveryLab.Companion.textOnly(
                    node.get(i),
                    depth + 1,
                )
        }

        @Throws(java.lang.Exception::class)
        private fun one(paths: MutableList<String>, token: String): String {
            val found: MutableList<String> = ArrayList<String>()
            for (p in paths) if (p.contains(token)) found.add(p)
            if (found.size != 1) throw IOException("Ambiguous recovery entry")
            return found.get(0)
        }

        @Throws(java.lang.Exception::class)
        private fun read(z: ZipFile, name: String?): ByteArray {
            z.getInputStream(z.getEntry(name)).use { `in` ->
                return NativeInk.read(`in`)
            }
        }
    }
}
