package dev.aragonite.powersync.client

import android.content.*
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.*
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Runtime-checked native interfaces, accessed as an ordinary application UID. */
class NativeBridge(context: Context) {
    val context: Context = context.applicationContext

    @Throws(IOException::class)
    fun requireKsyncAvailable() {
        try {
            observeFirmware(context.packageManager.getPackageInfo("com.onyx.android.ksync", 0))
        } catch (_: PackageManager.NameNotFoundException) {
            throw UserError(R.string.error_native_ksync_is_unavailable)
        }
    }

    private fun call(vararg args: String): ByteArray {
        requireKsyncAvailable()
        val command = listOf("/system/bin/service", "call", "onyx_device_service") + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output =
            CompletableFuture.supplyAsync {
                try {
                    process.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            if (output.size() + count > 1048576)
                                throw UserError(R.string.error_native_reply_exceeds_limit)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                } catch (error: IOException) {
                    throw CompletionException(error)
                }
            }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw UserError(R.string.error_native_service_timed_out)
        }
        val raw = String(output.get(2, TimeUnit.SECONDS), Charsets.UTF_8)
        val decoded = ByteArrayOutputStream()
        fun words(text: String) {
            for (word in text.trim().split(Regex("\\s+"))) decoded.write(
                ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(word.toLong(16).toInt())
                    .array()
            )
        }
        val pattern = Regex("(?m)^\\s*0x[0-9a-fA-F]+:\\s*((?:[0-9a-fA-F]{8}(?:\\s+|$)){1,4})")
        pattern.findAll(raw).forEach { words(it.groupValues[1]) }
        if (decoded.size() == 0)
            Regex("Parcel\\(\\s*((?:[0-9a-fA-F]{8}\\s+)+)").find(raw)?.let {
                words(it.groupValues[1])
            }
        val reply = decoded.toByteArray()
        if (reply.size < 8 || ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN).int != 0)
            throw UserError(R.string.error_native_configuration_access_denied)
        return reply
    }

    @Throws(Exception::class)
    fun read(key: String): JSONObject {
        if (key != CLUSTER && key != SYSTEM)
            throw UserError(R.string.error_unexpected_configuration_key)
        val reply = call("3", "s16", key)
        val length = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
        if (length < 0 || length > 524288 || 8L + length * 2L > reply.size)
            throw UserError(R.string.error_malformed_native_configuration)
        return NativeConfig.parse(key, String(reply, 8, length * 2, Charsets.UTF_16LE))
    }

    @Throws(Exception::class)
    fun write(key: String, value: JSONObject) {
        read(key)
        val reply = call("4", "s16", key, "s16", value.toString())
        if (
            ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN).getInt(4) == 0 ||
                canonical(read(key)) != canonical(value)
        )
            throw UserError(R.string.error_native_configuration_readback_differs)
        if (key == CLUSTER) {
            context.sendBroadcast(Intent("SERVER_INFO_CHANGED_ACTION"))
            Thread.sleep(1000)
        }
    }

    @Throws(Exception::class)
    fun flags(): JSONObject {
        val config = read(SYSTEM).getJSONObject("configMap")
        return JSONObject()
            .put("notes", NativeConfig.flag(config, "key_enable_sync_note_data"))
            .put("reader", NativeConfig.flag(config, "key_enable_sync_reader_data"))
    }

    @Throws(Exception::class)
    fun flags(flags: JSONObject) {
        val config = read(SYSTEM)
        config
            .getJSONObject("configMap")
            .put("key_enable_sync_note_data", flags.getString("notes"))
            .put("key_enable_sync_reader_data", flags.getString("reader"))
        write(SYSTEM, config)
        context.sendBroadcast(Intent("com.onyx.action.system.config.changed"))
    }

    @Throws(Exception::class)
    fun account(): JSONObject {
        val result = JSONObject().put("loggedIn", false)
        context.contentResolver
            .query(ACCOUNTS, arrayOf("uid", "clusterId"), "loggedIn=1", null, null)
            .use { cursor ->
                val c =
                    cursor ?: throw UserError(R.string.error_native_account_provider_unavailable)
                if (c.count > 1) throw UserError(R.string.error_multiple_active_native_accounts)
                if (c.moveToFirst())
                    result
                        .put("loggedIn", true)
                        .put("uid", c.getString(0))
                        .put("clusterId", c.getString(1))
            }
        return result
    }

    @Throws(Exception::class)
    fun fingerprint(): String {
        val rows = mutableListOf<String>()
        context.contentResolver
            .query(
                Uri.parse("content://com.onyx.android.sdk.note.ContentProvider/NoteModel"),
                arrayOf("uniqueId", "type", "status", "userId"),
                null,
                null,
                null,
            )
            .use { cursor ->
                val c = cursor ?: throw UserError(R.string.error_native_library_unavailable)
                while (c.moveToNext()) rows.add(
                    "${c.getString(0)}:${c.getInt(1)}:${c.getInt(2)}:${c.getString(3)}"
                )
            }
        return hex(
            MessageDigest.getInstance("SHA-256")
                .digest(rows.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
        )
    }

    private inner class Binding : AutoCloseable {
        private val main = Handler(Looper.getMainLooper())
        private val future = CompletableFuture<IBinder>()
        private var bound = false
        private val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    future.complete(binder)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    future.completeExceptionally(
                        UserError(R.string.error_native_service_disconnected)
                    )
                }

                override fun onNullBinding(name: ComponentName) {
                    future.completeExceptionally(
                        UserError(R.string.error_native_binding_unavailable)
                    )
                }
            }

        init {
            main.post {
                try {
                    bound =
                        context.bindService(
                            Intent()
                                .setComponent(
                                    ComponentName(
                                        "com.onyx.android.ksync",
                                        "com.onyx.android.ksync.service.KSyncService",
                                    )
                                ),
                            connection,
                            Context.BIND_AUTO_CREATE,
                        )
                    if (!bound)
                        future.completeExceptionally(
                            UserError(R.string.error_native_service_denied_binding)
                        )
                } catch (error: Exception) {
                    future.completeExceptionally(error)
                }
            }
        }

        fun get(): IBinder {
            val binder = future.get(15, TimeUnit.SECONDS)
            if (binder.interfaceDescriptor != SERVICE)
                throw UserError(R.string.error_unexpected_native_interface)
            return binder
        }

        override fun close() {
            main.post {
                if (bound) {
                    context.unbindService(connection)
                    bound = false
                }
            }
        }
    }

    @Throws(Exception::class)
    fun clusterCall(transaction: Int, id: String?) {
        requireKsyncAvailable()
        if (id != null && !id.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
            throw UserError(R.string.error_invalid_cluster_identity)
        val done = CompletableFuture<Int>()
        val callback =
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    if (code == INTERFACE_TRANSACTION) {
                        reply?.writeString(CLUSTER_CALLBACK)
                        return true
                    }
                    if (code != 1) return super.onTransact(code, data, reply, flags)
                    data.enforceInterface(CLUSTER_CALLBACK)
                    if (data.readInt() == 0)
                        done.completeExceptionally(
                            UserError(R.string.error_empty_native_cluster_callback)
                        )
                    else {
                        data.readString()
                        done.complete(data.readInt())
                    }
                    reply?.writeNoException()
                    return true
                }
            }
        Binding().use { binding ->
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(SERVICE)
                data.writeInt(1)
                data.writeString(id)
                data.writeInt(2)
                data.writeStrongBinder(callback)
                if (!binding.get().transact(transaction, data, null, IBinder.FLAG_ONEWAY))
                    throw UserError(R.string.error_cluster_switch_unsupported)
            } finally {
                data.recycle()
            }
            if (done.get(45, TimeUnit.SECONDS) != 2)
                throw UserError(R.string.error_native_cluster_switch_failed)
        }
    }

    @Throws(Exception::class) fun updateClusters() = clusterCall(81, null)

    @Throws(Exception::class)
    fun switchCluster(id: String) {
        clusterCall(80, id)
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (read(CLUSTER).getString("curClusterId") != id) {
            if (System.nanoTime() > end)
                throw UserError(R.string.error_native_selected_cluster_differs)
            Thread.sleep(300)
        }
    }

    @Throws(Exception::class)
    fun login(account: String?, code: String?, master: Boolean) {
        requireKsyncAvailable()
        val done = CompletableFuture<Int>()
        val callback =
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    if (code == INTERFACE_TRANSACTION) {
                        reply?.writeString(ACCOUNT_CALLBACK)
                        return true
                    }
                    if (code != 1) return super.onTransact(code, data, reply, flags)
                    data.enforceInterface(ACCOUNT_CALLBACK)
                    if (data.readInt() == 0)
                        done.completeExceptionally(UserError(R.string.error_empty_account_callback))
                    else {
                        data.readString()
                        data.readString()
                        data.readString()
                        data.readInt()
                        data.readString()
                        data.readString()
                        data.readString()
                        data.readString()
                        val status = data.readInt()
                        data.readLong()
                        data.readInt()
                        data.readInt()
                        data.readString()
                        done.complete(status)
                    }
                    reply?.writeNoException()
                    return true
                }
            }
        Binding().use { binding ->
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(SERVICE)
                data.writeInt(1)
                data.writeString(if (master) null else account)
                data.writeString(if (master) null else code)
                data.writeString(null)
                data.writeInt(if (master) 21 else 1)
                data.writeString(null)
                data.writeString("Shiori")
                data.writeString(null)
                data.writeString(null)
                data.writeInt(0)
                data.writeLong(0)
                data.writeInt(if (master) 0 else 2)
                data.writeInt(0)
                data.writeString(null)
                data.writeStrongBinder(callback)
                if (!binding.get().transact(65, data, null, IBinder.FLAG_ONEWAY))
                    throw UserError(R.string.error_native_login_unsupported)
            } finally {
                data.recycle()
            }
            val result = done.get(60, TimeUnit.SECONDS)
            if (result != 0) throw UserError(R.string.error_native_sign_in_failed, result)
        }
    }

    @Throws(Exception::class)
    fun catalog(): JSONArray {
        val result = JSONArray()
        context.contentResolver.query(CATALOG, null, null, null, null).use { cursor ->
            val c = cursor ?: throw UserError(R.string.error_reader_catalog_unavailable)
            while (c.moveToNext()) {
                if (result.length() > 10000)
                    throw UserError(R.string.error_reader_catalog_exceeds_snapshot_limit)
                val row = JSONObject()
                for (i in 0 until c.columnCount) {
                    val value: Any =
                        when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            Cursor.FIELD_TYPE_STRING -> c.getString(i)
                            else -> throw UserError(R.string.error_unsupported_catalog_value)
                        }
                    row.put(c.getColumnName(i), value)
                }
                result.put(row)
            }
        }
        return result
    }

    @Throws(Exception::class)
    fun catalogFiles(catalog: JSONArray): JSONObject =
        CatalogRecovery.fileAvailability(catalog) { it.isNotEmpty() && File(it).isFile }

    @Throws(Exception::class)
    fun repairMissingCatalog(before: JSONArray): Int =
        CatalogRecovery.restore(
            before,
            object : CatalogRecovery.Store {
                override fun read() = catalog()

                override fun insert(row: JSONObject) {
                    context.contentResolver
                        .query(
                            CATALOG,
                            arrayOf("id"),
                            "id=? OR uuid=?",
                            arrayOf(row.get("id").toString(), row.getString("uuid")),
                            null,
                        )
                        .use { cursor ->
                            if (cursor == null || cursor.moveToFirst())
                                throw UserError(
                                    R.string.error_original_catalog_id_unavailable_for_repair
                                )
                        }
                    val values = ContentValues()
                    for (key in row.keys()) when (val value = row.get(key)) {
                        JSONObject.NULL -> values.putNull(key)
                        is Int -> values.put(key, value.toLong())
                        is Long -> values.put(key, value)
                        is Number -> values.put(key, value.toDouble())
                        is String -> values.put(key, value)
                        else -> throw UserError(R.string.error_unsupported_catalog_field)
                    }
                    if (context.contentResolver.insert(CATALOG, values) == null)
                        throw UserError(R.string.error_reader_catalog_repair_failed)
                }
            },
        )

    @Throws(Exception::class)
    fun restoreAccount(previous: JSONObject, nativeUid: String): Boolean {
        if (!previous.optBoolean("loggedIn")) return !account().optBoolean("loggedIn")
        val wanted = previous.getString("uid")
        if (wanted == account().optString("uid")) return true
        if (Build.MODEL != "Go103_2Lumi") return false
        var matches = 0
        var total = 0
        context.contentResolver
            .query(
                ACCOUNTS,
                arrayOf("uid"),
                "(parent IS NULL OR parent='') AND uid<>?",
                arrayOf(nativeUid),
                null,
            )
            .use { cursor ->
                val c = cursor ?: return false
                while (c.moveToNext()) {
                    total++
                    if (wanted == c.getString(0)) matches++
                }
            }
        if (total != 1 || matches != 1) return false
        context.contentResolver.delete(ACCOUNTS, "uid=?", arrayOf(nativeUid))
        login(null, null, true)
        return wanted == account().optString("uid")
    }

    companion object {
        const val CLUSTER = "ONYX_CLUSTER_SYSTEM_KEY"
        const val SYSTEM = "ONYX_SYSTEM_CONFIG_KEY"
        const val SERVICE = "com.onyx.android.sdk.sync.ksync.service.ISyncService"
        private const val CLUSTER_CALLBACK =
            "com.onyx.android.sdk.sync.ksync.service.SyncClusterCallback"
        private const val ACCOUNT_CALLBACK =
            "com.onyx.android.sdk.sync.ksync.service.AccountIntentCallback"
        @JvmField
        val ACCOUNTS: Uri =
            Uri.parse(
                "content://com.onyx.account.database.ContentProvider.KSyncAccountContentProvider/OnyxAccountModel"
            )
        private val CATALOG =
            Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")
        private var lastFirmwareObservation: String? = null
        private var observedApk: String? = null
        private val apkHasher =
            Executors.newSingleThreadExecutor {
                Thread(it, "ShioriApkHash").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }

        @Synchronized
        private fun observeFirmware(info: PackageInfo) {
            val ksync = info.longVersionCode
            val path = info.applicationInfo?.sourceDir
            val apk = "$path|$ksync|${info.lastUpdateTime}"
            if (apk == observedApk) return
            observedApk = apk
            apkHasher.execute {
                val sha =
                    path?.let { NativeCompatibility.apkSha256(File(it)) }
                        ?: NativeCompatibility.UNREADABLE
                val observation =
                    NativeCompatibility.describe(
                        Build.MODEL,
                        Build.VERSION.SDK_INT,
                        Build.DISPLAY,
                        ksync,
                        sha,
                    )
                synchronized(NativeBridge::class.java) {
                    if (observation != lastFirmwareObservation) {
                        Log.i("ShioriCompatibility", observation)
                        lastFirmwareObservation = observation
                    }
                }
            }
        }

        @JvmStatic
        @Throws(JSONException::class)
        fun canonical(value: Any?): String = NativeConfig.canonical(value)

        @JvmStatic
        fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 255) }
    }
}
