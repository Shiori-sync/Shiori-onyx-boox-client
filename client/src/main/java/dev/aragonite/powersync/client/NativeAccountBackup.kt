package dev.aragonite.powersync.client

import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Credentials stay in PrivateState on this device; never send or log these rows. */
class NativeAccountBackup(private val bridge: NativeBridge) {
    private fun rows(selection: String?, vararg args: String): JSONArray {
        val rows = JSONArray()
        bridge.context.contentResolver
            .query(NativeBridge.ACCOUNTS, null, selection, args, null)
            .use { cursor ->
                val c = cursor ?: throw UserError(R.string.error_native_account_cache_unavailable)
                while (c.moveToNext()) {
                    if (rows.length() >= 32)
                        throw UserError(R.string.error_unexpected_native_account_cache_size)
                    val row = JSONObject()
                    for (i in 0 until c.columnCount) {
                        val value: Any =
                            when (c.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                Cursor.FIELD_TYPE_STRING -> c.getString(i)
                                else ->
                                    throw UserError(R.string.error_unsupported_account_cache_field)
                            }
                        row.put(c.getColumnName(i), value)
                    }
                    rows.put(row)
                }
            }
        return rows
    }

    private fun maps(rows: JSONArray): List<Map<String, Any?>> =
        (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            row.keys().asSequence().associateWith { key ->
                when (val value = row.get(key)) {
                    JSONObject.NULL -> null
                    is Number -> value.toLong()
                    else -> value
                }
            }
        }

    private fun json(row: Map<String, Any?>): JSONObject =
        JSONObject().apply { for ((key, value) in row) put(key, value ?: JSONObject.NULL) }

    @Throws(Exception::class)
    fun capture(uid: String): JSONArray {
        val saved = AccountCacheRecovery.capture(maps(rows(null)), uid)
        val active = bridge.account()
        if (
            active.optBoolean("loggedIn") &&
                uid == active.optString("uid") &&
                saved.count {
                    it["uid"] == uid && it["clusterId"] == active.optString("clusterId")
                } != 1
        )
            throw UserError(R.string.error_cannot_preserve_your_current_boox_account)
        return JSONArray().apply { saved.forEach { put(json(it)) } }
    }

    private fun selection(row: Map<String, Any?>, args: MutableList<String>): String =
        row.keys.sorted().joinToString(" AND ") { key ->
            if (!key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
                throw UserError(R.string.error_unsupported_native_account_column)
            val value = row[key]
            if (value == null) "$key IS NULL"
            else {
                args.add(value.toString())
                "$key=?"
            }
        }

    @Throws(Exception::class)
    fun verifyBeforeLogin(saved: JSONArray, uid: String) =
        AccountCacheRecovery.verifyBeforeLogin(maps(saved), maps(rows(null)), uid)

    private fun values(row: JSONObject): ContentValues =
        ContentValues().apply {
            for (key in row.keys()) when (val value = row.get(key)) {
                JSONObject.NULL -> putNull(key)
                is Number -> put(key, value.toLong())
                is String -> put(key, value)
                else -> throw UserError(R.string.error_unsupported_saved_account_field)
            }
        }

    @Throws(Exception::class)
    fun restore(previous: JSONObject, saved: JSONArray, uid: String, custom: String): Boolean {
        var active = bridge.account()
        if (
            previous.optBoolean("loggedIn") &&
                active.optBoolean("loggedIn") &&
                previous.getString("uid") == active.optString("uid") &&
                previous.getString("clusterId") == active.optString("clusterId")
        ) {
            for (old in maps(saved)) {
                if (
                    old["uid"] == previous.getString("uid") &&
                        old["clusterId"] == previous.getString("clusterId")
                )
                    continue
                val actual = maps(rows("id=?", AccountCacheRecovery.id(old).toString()))
                if (actual.size != 1 || actual[0] != AccountCacheRecovery.loggedOut(old))
                    throw UserError(
                        R.string.error_other_cached_accounts_still_need_restoration_snapshot
                    )
            }
            return true
        }
        if (active.optBoolean("loggedIn"))
            throw UserError(R.string.error_an_account_is_still_active_preserve_the)
        val store =
            object : AccountCacheRecovery.Store {
                override fun read() = maps(rows(null))

                override fun delete(expected: Map<String, Any?>) {
                    val args = mutableListOf<String>()
                    val where = selection(expected, args)
                    if (
                        bridge.context.contentResolver.delete(
                            NativeBridge.ACCOUNTS,
                            where,
                            args.toTypedArray(),
                        ) != 1
                    )
                        throw UserError(
                            R.string.error_custom_account_changed_during_removal_snapshot_preserved
                        )
                }

                override fun update(expected: Map<String, Any?>, restored: Map<String, Any?>) {
                    val args = mutableListOf<String>()
                    val where = selection(expected, args)
                    if (
                        bridge.context.contentResolver.update(
                            NativeBridge.ACCOUNTS,
                            values(json(restored)),
                            where,
                            args.toTypedArray(),
                        ) != 1
                    )
                        throw UserError(
                            R.string.error_account_changed_during_restoration_snapshot_preserved
                        )
                }

                override fun insert(restored: Map<String, Any?>) {
                    if (rows("id=?", AccountCacheRecovery.id(restored).toString()).length() != 0)
                        throw UserError(R.string.error_original_account_row_id_is_occupied_snapshot)
                    val inserted =
                        bridge.context.contentResolver.insert(
                            NativeBridge.ACCOUNTS,
                            values(json(restored)),
                        )
                    if (
                        inserted == null ||
                            ContentUris.parseId(inserted) != AccountCacheRecovery.id(restored)
                    )
                        throw UserError(
                            R.string.error_account_cache_restoration_failed_snapshot_preserved
                        )
                }
            }
        AccountCacheRecovery.restore(maps(saved), store, uid, custom)
        if (!previous.optBoolean("loggedIn")) return !bridge.account().optBoolean("loggedIn")
        // Type 21 implicitly selects a cached master; never guess among candidates.
        val masters = rows("parent IS NULL OR parent=''")
        if (
            Build.MODEL != "Go103_2Lumi" ||
                masters.length() != 1 ||
                previous.getString("uid") != masters.getJSONObject(0).optString("uid")
        )
            return false
        val master = masters.getJSONObject(0)
        if (
            rows(
                    "uid=? OR accountId=?",
                    master.getString("uid"),
                    master.get("accountId").toString(),
                )
                .length() != 1
        )
            return false
        bridge.login(null, null, true)
        active = bridge.account()
        return active.optBoolean("loggedIn") &&
            previous.getString("uid") == active.optString("uid") &&
            previous.getString("clusterId") == active.optString("clusterId")
    }
}
