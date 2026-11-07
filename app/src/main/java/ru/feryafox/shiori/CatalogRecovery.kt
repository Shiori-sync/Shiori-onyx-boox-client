package ru.feryafox.shiori

import java.util.TreeMap
import org.json.JSONArray
import org.json.JSONObject

/** Catalog membership is independent of downloaded book bytes. */
object CatalogRecovery {
    interface Store {
        @Throws(Exception::class) fun read(): JSONArray

        @Throws(Exception::class) fun insert(row: JSONObject)
    }

    fun interface Files {
        fun exists(path: String): Boolean
    }

    private fun signature(row: JSONObject): String =
        TreeMap<String, String>()
            .apply {
                for (key in row.keys()) {
                    val value = row.get(key)
                    if (value != JSONObject.NULL && value !is String && value !is Number)
                        throw UserError(R.string.error_unsupported_catalog_field)
                    put(key, JSONArray().put(value).toString())
                }
            }
            .toString()

    private fun index(rows: JSONArray): MutableMap<String, String> {
        val result = linkedMapOf<String, String>()
        val ids = mutableSetOf<Long>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val uuid = row.getString("uuid")
            val id = row.getLong("id")
            if (
                uuid.isEmpty() ||
                    id <= 0 ||
                    !ids.add(id) ||
                    result.put(uuid, signature(row)) != null
            )
                throw UserError(R.string.error_catalog_identity_conflict)
        }
        return result
    }

    @JvmStatic
    @Throws(Exception::class)
    fun restore(saved: JSONArray, store: Store): Int {
        index(saved)
        val initial = store.read()
        val expected = index(initial)
        val occupied =
            (0 until initial.length()).map { initial.getJSONObject(it).getLong("id") }.toSet()
        for (i in 0 until saved.length()) {
            val row = saved.getJSONObject(i)
            if (!expected.containsKey(row.getString("uuid")) && row.getLong("id") in occupied)
                throw UserError(R.string.error_original_catalog_id_unavailable_for_repair)
        }
        var restored = 0
        for (i in 0 until saved.length()) {
            val row = saved.getJSONObject(i)
            val uuid = row.getString("uuid")
            if (expected.containsKey(uuid)) continue
            unchanged(store, expected)
            store.insert(JSONObject(row.toString()))
            expected[uuid] = signature(row)
            unchanged(store, expected)
            restored++
        }
        unchanged(store, expected)
        return restored
    }

    private fun unchanged(store: Store, expected: Map<String, String>) {
        if (index(store.read()) != expected)
            throw UserError(R.string.error_restored_catalog_readback_differs)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun fileAvailability(catalog: JSONArray, files: Files): JSONObject =
        JSONObject().apply {
            for (i in 0 until catalog.length()) {
                val row = catalog.getJSONObject(i)
                put(
                    row.getString("uuid"),
                    files.exists(row.optString("nativeAbsolutePath")) ||
                        files.exists(row.optString("nocasePath")),
                )
            }
        }

    @JvmStatic
    fun newlyUnavailable(before: JSONObject?, now: JSONObject): Int =
        before?.keys()?.asSequence()?.count {
            before.optBoolean(it) && now.has(it) && !now.optBoolean(it)
        } ?: 0
}
