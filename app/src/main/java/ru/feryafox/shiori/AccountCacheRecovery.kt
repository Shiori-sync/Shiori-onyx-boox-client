ввpackage ru.feryafox.shiori

import java.io.IOException

/** Credential-bearing rows must never appear in diagnostics. */
@JvmSuppressWildcards
object AccountCacheRecovery {
    const val SERVER_ACCOUNT_ID = 2000000001L

    interface Store {
        @Throws(Exception::class) fun read(): List<Map<String, Any?>>

        @Throws(Exception::class) fun delete(expected: Map<String, Any?>)

        @Throws(Exception::class)
        fun update(expected: Map<String, Any?>, restored: Map<String, Any?>)

        @Throws(Exception::class) fun insert(restored: Map<String, Any?>)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun id(row: Map<String, Any?>): Long {
        val value = row["id"]
        if (value !is Number || value.toLong() <= 0)
            throw UserError(R.string.error_invalid_cached_account_row_id)
        return value.toLong()
    }

    @JvmStatic
    fun affected(row: Map<String, Any?>, uid: String): Boolean =
        row["uid"] == uid || row["accountId"] == SERVER_ACCOUNT_ID

    @JvmStatic
    fun loggedOut(row: Map<String, Any?>): Map<String, Any?> =
        HashMap(row).apply { put("loggedIn", 0L) }

    private fun index(rows: List<Map<String, Any?>>): Map<Long, Map<String, Any?>> {
        if (rows.size > 32) throw UserError(R.string.error_unexpected_native_account_cache_size)
        return linkedMapOf<Long, Map<String, Any?>>().apply {
            for (row in rows) if (put(id(row), row) != null)
                throw UserError(R.string.error_duplicate_cached_account_row_id)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun capture(rows: List<Map<String, Any?>>, uid: String): List<Map<String, Any?>> {
        index(rows)
        return rows.filter { affected(it, uid) }.map { HashMap(it) }
    }

    private fun onlyLogoutChanged(now: Map<String, Any?>, old: Map<String, Any?>): Boolean {
        val a = HashMap(now)
        val b = HashMap(old)
        for (key in listOf("loggedIn", "logOutAt", "logoutReason", "updatedAt")) {
            a.remove(key)
            b.remove(key)
        }
        if (old["createdAt"] == null && (now["createdAt"] == null || now["createdAt"] is Number)) {
            a.remove("createdAt")
            b.remove("createdAt")
        }
        return a == b
    }

    @JvmStatic
    @Throws(IOException::class)
    fun verifyBeforeLogin(
        saved: List<Map<String, Any?>>,
        current: List<Map<String, Any?>>,
        uid: String,
    ) {
        val old = index(saved)
        val now = index(capture(current, uid))
        if (old.keys != now.keys)
            throw UserError(R.string.error_account_cache_changed_before_login_snapshot_preserved)
        for ((id, row) in old) if (!onlyLogoutChanged(now.getValue(id), row))
            throw UserError(
                R.string.error_cached_credentials_changed_before_login_snapshot_preserved
            )
    }

    @JvmStatic
    @Throws(Exception::class)
    fun restore(saved: List<Map<String, Any?>>, store: Store, uid: String, custom: String) {
        val originals = index(saved)
        if (saved.any { !affected(it, uid) })
            throw UserError(R.string.error_saved_account_outside_restoration_scope)
        val initial = store.read()
        index(initial)
        val savedUIDs = saved.map { it["uid"] }.toSet()
        val owned = mutableListOf<Map<String, Any?>>()
        for (row in initial) {
            if (row["uid"] == uid && row["clusterId"] == custom) {
                owned.add(row)
                continue
            }
            val old = originals[id(row)]
            if (old != null) {
                if (!onlyLogoutChanged(row, old))
                    throw UserError(
                        R.string.error_original_account_row_changed_outside_shiori_snapshot
                    )
            } else if (affected(row, uid) || row["uid"] in savedUIDs)
                throw UserError(
                    R.string.error_account_cache_changed_outside_shiori_snapshot_preserved
                )
        }
        if (owned.size > 1)
            throw UserError(R.string.error_ambiguous_custom_account_cache_snapshot_preserved)
        val expected = initial.toMutableList()
        for (row in owned) {
            unchanged(store, expected)
            store.delete(row)
            expected.remove(row)
        }
        for (old in saved) {
            unchanged(store, expected)
            val restored = loggedOut(old)
            val now = index(expected)[id(old)]
            if (now == null) {
                store.insert(restored)
                expected.add(restored)
            } else if (now != restored) {
                store.update(now, restored)
                expected.remove(now)
                expected.add(restored)
            }
        }
        unchanged(store, expected)
    }

    private fun unchanged(store: Store, expected: List<Map<String, Any?>>) {
        if (index(store.read()) != index(expected))
            throw UserError(
                R.string.error_account_cache_changed_during_restoration_snapshot_preserved
            )
    }
}
