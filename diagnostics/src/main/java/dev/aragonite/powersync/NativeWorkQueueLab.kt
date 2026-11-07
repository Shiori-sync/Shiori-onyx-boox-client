package dev.aragonite.powersync

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/** Synthetic V2 work only, bound to the existing disposable UI notebook. */
class NativeWorkQueueLab(context: Context) {
    private val context: Context

    init {
        this.context = context
    }

    @Throws(java.lang.Exception::class)
    private fun request(): JSONObject {
        val file: File = File(context.getFilesDir(), "v2-work-request.json")
        if (file.length() > 2048) throw IOException("Oversized V2 request")
        val r: JSONObject =
            JSONObject(
                String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
            )
        if (!r.getString("recordId").matches("powersync-v2-[a-f0-9-]{36}".toRegex()))
            throw IOException("Invalid synthetic record ID")
        return r
    }

    @Throws(java.lang.Exception::class)
    fun capture(): JSONObject {
        val record: String = request().getString("recordId")
        return JSONObject()
            .put("uid", android.os.Process.myUid())
            .put(
                "records",
                NativeNotes(context)
                    .rows(
                        NativeWorkQueueLab.Companion.URI,
                        "documentId=? AND user=? AND recordId=?",
                        arrayOf<String>(NativeWorkQueueLab.Companion.DOC, "powersync-lab", record),
                        true,
                    ),
            )
    }

    @Throws(java.lang.Exception::class)
    fun insert(): JSONObject {
        if (
            !(NativeClusterLab.Companion.LAB_ID ==
                NativeSystemConfigProbe().read().getString("curClusterId"))
        )
            throw IOException("Requires lab cluster")
        val req: JSONObject = request()
        val record: String = req.getString("recordId")
        val notes: JSONArray =
            NativeNotes(context)
                .notes(
                    "uniqueId=? AND userId=?",
                    arrayOf<String>(NativeWorkQueueLab.Companion.DOC, "powersync-lab"),
                )
        if (notes.length() != 1) throw IOException("Missing owned disposable notebook")
        if (capture().getJSONArray("records").length() != 0)
            throw IOException("Synthetic record already exists")
        val local: String =
            "/sdcard/.ksync/document/" +
                NativeWorkQueueLab.Companion.DOC +
                "/resource/data/" +
                record +
                ".html"
        if (File(local).exists()) throw IOException("Synthetic destination already exists")
        val status: Int = req.optInt("syncStatus", 1)
        if (status != 1 && status != 3)
            throw IOException("Only synthetic pull/delete work is supported")
        if (req.optBoolean("seedExisting")) {
            val target: File = File(local)
            if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs())
                throw IOException("Cannot create synthetic directory")
            FileOutputStream(target).use { out ->
                out.write(
                    "PowerSync stable pre-replacement fixture".toByteArray(StandardCharsets.UTF_8)
                )
                out.getFD().sync()
            }
        }
        val v: ContentValues = ContentValues()
        v.put("documentId", NativeWorkQueueLab.Companion.DOC)
        v.put("recordId", record)
        v.put("user", "powersync-lab")
        v.put("recordType", 4)
        v.put("syncStatus", status)
        v.put("recordFilePath", local)
        v.put("guid", record)
        v.put("idString", record)
        v.put("createdAt", java.lang.System.currentTimeMillis())
        v.put("updatedAt", java.lang.System.currentTimeMillis())
        val inserted: Uri? =
            context.getContentResolver().insert(NativeWorkQueueLab.Companion.URI, v)
        if (inserted == null) throw IOException("Queue insert failed")
        return capture()
            .put("localPath", local)
            .put("ossKey", "powersync-lab/resource/" + record + ".html")
    }

    @Throws(java.lang.Exception::class)
    fun remove(): JSONObject {
        val record: String = request().getString("recordId")
        val deleted: Int =
            context
                .getContentResolver()
                .delete(
                    NativeWorkQueueLab.Companion.URI,
                    "documentId=? AND user=? AND recordId=? AND guid=?",
                    arrayOf<String>(
                        NativeWorkQueueLab.Companion.DOC,
                        "powersync-lab",
                        record,
                        record,
                    ),
                )
        return JSONObject().put("deletedSyntheticRows", deleted)
    }

    companion object {
        private const val DOC: String = "f7f635344f624650aa300f84d114a1d3"
        private val URI: Uri =
            Uri.parse(
                "content://com.onyx.android.sdk.sync.KSyncRecordContentProvider/LocalRecordModel"
            )
    }
}
