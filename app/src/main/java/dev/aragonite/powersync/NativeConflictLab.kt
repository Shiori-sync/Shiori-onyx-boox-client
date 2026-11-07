package dev.aragonite.powersync

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/** Only the pre-existing disposable Binder fixture may be changed here. */
class NativeConflictLab(context: Context) {
    private val context: Context

    init {
        this.context = context
    }

    @Throws(java.lang.Exception::class)
    fun capture(): JSONObject {
        val rows: JSONArray =
            NativeNotes(context)
                .notes(
                    "uniqueId=? AND type=? AND userId=?",
                    arrayOf<String>(NativeConflictLab.Companion.DOC, "1", "powersync-lab"),
                )
        if (rows.length() != 1) throw IOException("Expected owned disposable conflict notebook")
        val row: JSONObject = rows.getJSONObject(0)
        if (!row.optString("title").startsWith("PowerSync fixture "))
            throw IOException("Unexpected conflict fixture title")
        return row
    }

    @Throws(java.lang.Exception::class)
    fun change(operation: String?): JSONObject {
        if (
            !(NativeClusterLab.Companion.LAB_ID ==
                NativeSystemConfigProbe().read().getString("curClusterId"))
        )
            throw IOException("Requires lab cluster")
        val before: JSONObject = capture()
        val notes: NativeNotes = NativeNotes(context)
        var expected: String? = null
        if ("edit" == operation) {
            val request: File = File(context.getFilesDir(), "conflict-request.json")
            if (request.length() > 1024) throw IOException("Oversized conflict request")
            expected =
                JSONObject(
                        String(
                            java.nio.file.Files.readAllBytes(request.toPath()),
                            StandardCharsets.UTF_8,
                        )
                    )
                    .getString("title")
            if (!expected.matches("PowerSync fixture conflict [A-Za-z0-9 _-]{1,80}".toRegex()))
                throw IOException("Invalid synthetic title")
            notes.send(
                "RENAME_NOTE",
                JSONObject()
                    .put(
                        "noteBean",
                        JSONObject()
                            .put("documentId", NativeConflictLab.Companion.DOC)
                            .put("parentUniqueId", before.optString("parentUniqueId", ""))
                            .put("title", expected),
                    ),
                false,
            )
        } else if ("remove" == operation || "restore" == operation) {
            // TinyNoteModel is not the provider row schema: page-list blobs and SQL
            // date values cannot be passed through as its structured Java properties.
            val tiny: JSONObject = JSONObject()
            for (key in
                arrayOf<String>(
                    "uniqueId",
                    "parentUniqueId",
                    "title",
                    "type",
                    "status",
                    "noteSyncStatus",
                    "pageCount",
                    "activeScene",
                )) tiny.put(key, before.opt(key))
            notes.send(
                if ("remove" == operation) "DELETE_NOTE" else "RESTORE_REMOVE_NOTE",
                JSONObject().put("noteModelList", JSONArray().put(tiny)),
                false,
            )
        } else throw IOException("Unsupported conflict operation")
        for (i in 0..79) {
            val row: JSONObject = capture()
            val done: Boolean =
                if ("edit" == operation) (expected == row.optString("title"))
                else row.optInt("status", -1) == (if ("remove" == operation) 0 else 1)
            if (done)
                return JSONObject()
                    .put("operation", operation)
                    .put("uid", android.os.Process.myUid())
                    .put("before", before)
                    .put("after", row)
            java.lang.Thread.sleep(250)
        }
        throw IOException("Native conflict mutation not observed")
    }

    companion object {
        private const val DOC: String = "9e6004bb-7bc3-4a7e-87d9-79b9f9ba6206"
    }
}
