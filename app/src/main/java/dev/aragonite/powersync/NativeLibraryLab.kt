package dev.aragonite.powersync

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/** Read/export only: original library ownership and sync flags are untouched. */
class NativeLibraryLab(context: Context) {
    private val context: Context

    init {
        this.context = context
    }

    @Throws(java.lang.Exception::class)
    fun inventory(): JSONObject {
        val rows: JSONArray = NativeNotes(context).notes(null, null)
        var notes: Int = 0
        val types: JSONObject = JSONObject()
        val statuses: JSONObject = JSONObject()
        for (i in 0..<rows.length()) {
            val row: JSONObject = rows.getJSONObject(i)
            val t: String = "" + row.optInt("type", -1)
            val s: String = "" + row.optInt("status", -1)
            types.put(t, types.optInt(t) + 1)
            statuses.put(s, statuses.optInt(s) + 1)
            if (row.optInt("type", -1) == 1) notes++
        }
        val report: JSONObject =
            JSONObject()
                .put("at", java.lang.System.currentTimeMillis())
                .put("uid", android.os.Process.myUid())
                .put("metadataRows", rows.length())
                .put("notebooks", notes)
                .put("types", types)
                .put("statuses", statuses)
                .put("rows", rows)
                .put("payloadsExported", false)
        ReportFiles.write(context, "library-inventory-private.json", report)
        return JSONObject()
            .put("metadataRows", rows.length())
            .put("notebooks", notes)
            .put("types", types)
            .put("statuses", statuses)
            .put("privateInventory", "library-inventory-private.json")
            .put("payloadsExported", false)
    }

    @Throws(java.lang.Exception::class)
    fun exportOne(): JSONObject {
        val request: File = File(context.getFilesDir(), "library-export-request.json")
        if (request.length() > 1024) throw IOException("Oversized export request")
        val id: String =
            JSONObject(
                    String(
                        java.nio.file.Files.readAllBytes(request.toPath()),
                        StandardCharsets.UTF_8,
                    )
                )
                .getString("documentId")
        if (!id.matches("[0-9a-f-]{32,36}".toRegex())) throw IOException("Invalid document ID")
        val inventory: JSONObject =
            JSONObject(
                String(
                    java.nio.file.Files.readAllBytes(
                        File(context.getFilesDir(), "library-inventory-private.json").toPath()
                    ),
                    StandardCharsets.UTF_8,
                )
            )
        var inventoried: Boolean = false
        val rows: JSONArray = inventory.getJSONArray("rows")
        for (i in 0..<rows.length()) if (
            id == rows.getJSONObject(i).optString("uniqueId") &&
                rows.getJSONObject(i).optInt("type", -1) == 1
        )
            inventoried = true
        if (!inventoried) throw IOException("Export requires inventoried notebook")
        val current: JSONArray =
            NativeNotes(context).notes("uniqueId=? AND type=?", arrayOf<String>(id, "1"))
        if (current.length() != 1) throw IOException("Expected one current notebook")
        return FixtureRunner(context).exportRow(current.getJSONObject(0))
    }
}
