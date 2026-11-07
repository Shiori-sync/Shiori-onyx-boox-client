package ru.feryafox.shiori.diagnostics

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/** Single catalog-row repair after the explicitly authorized original-endpoint restoration. */
object NativeCatalogRestoreLab {
    @Throws(java.lang.Exception::class)
    fun wake(context: Context): JSONObject {
        if (!("en" == NativeSystemConfigProbe().read().getString("curClusterId")))
            throw IOException("Original endpoint required")
        RemoteBinding(
                context,
                "com.onyx.android.ksync",
                "com.onyx.android.ksync.service.KSyncService",
            )
            .use { binding ->
                if (
                    !("com.onyx.android.sdk.sync.ksync.service.ISyncService" ==
                        binding.await().getInterfaceDescriptor())
                )
                    throw IOException("Unexpected native interface")
                context.sendBroadcast(Intent("com.onyx.action.system.config.changed"))
            }
        return JSONObject().put("nativeServiceStarted", true).put("originalEndpointVerified", true)
    }

    @Throws(java.lang.Exception::class)
    fun restore(context: Context): JSONObject {
        if (!("Palma2_Pro_C" == android.os.Build.MODEL))
            throw IOException("Qualified Palma restoration only")
        val io: NativeSystemConfigProbe = NativeSystemConfigProbe()
        if (!("en" == io.read().getString("curClusterId")))
            throw IOException("Original endpoint required")
        val flags: JSONObject = io.read("ONYX_SYSTEM_CONFIG_KEY").getJSONObject("configMap")
        if (
            !("false" == flags.getString("key_enable_sync_note_data")) ||
                !("false" == flags.getString("key_enable_sync_reader_data"))
        )
            throw IOException("Sync must remain paused")
        val raw: ByteArray =
            java.nio.file.Files.readAllBytes(
                File(context.getFilesDir(), "catalog-restore-private.json").toPath()
            )
        if (
            !("1a76450d1eac2e3a6134ac86d30d6ab8f70fa25a5c7b7a96190d3f03f9510cf8" ==
                NativeInk.digest(raw))
        )
            throw IOException("Exact escrowed pre-transition row required")
        val row: JSONObject = JSONObject(String(raw, java.nio.charset.StandardCharsets.UTF_8))
        val uuid: String = "dd9c0167293c411b9df712f89ff98af6"
        if (!(uuid == row.getString("uuid"))) throw IOException("Unexpected catalog identity")
        val uri: Uri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")
        val notes: NativeNotes = NativeNotes(context)
        val existing: JSONArray = notes.rows(uri, "uuid=?", arrayOf<String>(uuid), true)
        if (existing.length() != 0)
            throw IOException("Catalog row already present; do not overwrite")
        if (notes.rows(uri, "id=?", arrayOf<String>(row.getString("id")), true).length() != 0)
            throw IOException("Original row ID reused")
        if (!File(row.getString("nativeAbsolutePath")).isFile())
            throw IOException("Original backing book missing")
        val values: ContentValues = ContentValues()
        var keys: MutableIterator<String?> = row.keys()
        while (keys.hasNext()) {
            val k: String? = keys.next()
            val v: Any? = row.get(k)
            if (v === JSONObject.NULL) values.putNull(k)
            else if (v is Int || v is Long) values.put(k, (v as Number).toLong())
            else if (v is Number) values.put(k, (v as Number).toDouble())
            else if (v is String) values.put(k, v as String)
            else throw IOException("Unsupported value")
        }
        if (context.getContentResolver().insert(uri, values) == null)
            throw IOException("Native catalog insert failed")
        val after: JSONArray = notes.rows(uri, "uuid=?", arrayOf<String>(uuid), true)
        if (after.length() != 1) throw IOException("Catalog identity not restored")
        val restored: JSONObject = after.getJSONObject(0)
        keys = row.keys()
        while (keys.hasNext()) {
            val k: String? = keys.next()
            if (!(row.get(k).toString() == restored.get(k).toString()))
                throw IOException("Restored catalog field differs: " + k)
        }
        return JSONObject()
            .put("singleOriginalCatalogRowRestoredExact", true)
            .put("bookFileUntouched", true)
            .put("notesUntouched", true)
    }
}
