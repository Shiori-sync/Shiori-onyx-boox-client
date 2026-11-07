package ru.feryafox.shiori.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import org.json.JSONObject

/** Fixed disposable account only. Login ABI recovered from this Palma APK. */
class NativeAccountLab(context: Context) {
    private val context: Context

    init {
        this.context = context
    }

    @Throws(java.lang.Exception::class)
    fun probeLabAccountInfo(): JSONObject {
        if (
            !(NativeClusterLab.Companion.LAB_ID ==
                NativeSystemConfigProbe().read().getString("curClusterId"))
        )
            throw IOException("Requires lab cluster")
        context
            .getContentResolver()
            .query(
                NativeAccountLab.Companion.ACCOUNTS,
                arrayOf<String>("uid", "clusterId"),
                "loggedIn=1",
                null,
                null,
            )
            .use { c ->
                if (
                    c == null ||
                        c.getCount() != 1 ||
                        !c.moveToFirst() ||
                        !("powersync-lab" == c.getString(0)) ||
                        !(NativeClusterLab.Companion.LAB_ID == c.getString(1))
                )
                    throw IOException("Requires sole logged-in disposable account")
            }
        RemoteBinding(
                context,
                "com.onyx.android.ksync",
                "com.onyx.android.ksync.service.KSyncService",
            )
            .use { binding ->
                val binder: IBinder = binding.await()
                if (!(NativeAccountLab.Companion.DESCRIPTOR == binder.getInterfaceDescriptor()))
                    throw IOException("Unexpected account Binder")
                val p: Parcel = Parcel.obtain()
                try {
                    p.writeInterfaceToken(NativeAccountLab.Companion.DESCRIPTOR)
                    p.writeInt(0)
                    p.writeStrongBinder(null)
                    if (!binder.transact(69, p, null, IBinder.FLAG_ONEWAY))
                        throw IOException("Unsupported account-info transaction")
                } finally {
                    p.recycle()
                }
            }
        return JSONObject()
            .put("requested", true)
            .put("completed", false)
            .put("disposableAccountOnly", true)
    }

    @Throws(java.lang.Exception::class)
    fun baseline(): JSONObject {
        if (!("Go103_2Lumi" == Build.MODEL))
            throw IOException("Cached account restoration qualified on Lumi only")
        val file: File = File(context.getFilesDir(), "account-baseline.json")
        if (file.exists()) return JSONObject().put("alreadyRecorded", true)
        val account: JSONObject
        context
            .getContentResolver()
            .query(
                NativeAccountLab.Companion.ACCOUNTS,
                arrayOf<String>("uid", "parent", "clusterId"),
                "loggedIn=1",
                null,
                null,
            )
            .use { c ->
                if (c == null || c.getCount() != 1 || !c.moveToFirst())
                    throw IOException("Expected one logged-in original account")
                if (c.getString(1) != null && !c.getString(1).isEmpty())
                    throw IOException("Original account is not a master account")
                account = JSONObject().put("uid", c.getString(0)).put("clusterId", c.getString(2))
            }
        if (masterCount(account.getString("uid")) != 1)
            throw IOException("Original cached master is not unique")
        ReportFiles.write(context, file.getName(), account)
        return JSONObject().put("recorded", true).put("credentialsReadOrExported", false)
    }

    @Throws(java.lang.Exception::class)
    private fun masterCount(required: String): Int {
        if ("powersync-lab" == required) return 0
        context
            .getContentResolver()
            .query(
                NativeAccountLab.Companion.ACCOUNTS,
                arrayOf<String>("uid"),
                "(parent IS NULL OR parent='') AND uid<>?",
                arrayOf<String>("powersync-lab"),
                null,
            )
            .use { c ->
                if (
                    c == null ||
                        c.getCount() != 1 ||
                        !c.moveToFirst() ||
                        !(required == c.getString(0))
                )
                    return 0
                return 1
            }
    }

    @Throws(java.lang.Exception::class)
    fun restoreOriginal(): JSONObject {
        if (!("Go103_2Lumi" == Build.MODEL))
            throw IOException("Cached restoration qualified on Lumi only")
        val file: File = File(context.getFilesDir(), "account-baseline.json")
        val saved: JSONObject =
            JSONObject(
                String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
            )
        val current: JSONObject = NativeClusterLab(context).snapshot()
        if (
            !(saved.getString("clusterId") == current.getString("clusterId")) ||
                masterCount(saved.getString("uid")) != 1
        )
            throw IOException("Original cluster and sole cached master required before restore")
        // Native MASTER selects the most recent master, not a requested UID. Remove
        // only the fixed disposable account cache so the preserved original is unique.
        val removed: Int =
            context
                .getContentResolver()
                .delete(
                    NativeAccountLab.Companion.ACCOUNTS,
                    "uid=?",
                    arrayOf<String>("powersync-lab"),
                )
        context
            .getContentResolver()
            .query(
                NativeAccountLab.Companion.ACCOUNTS,
                arrayOf<String>("uid"),
                "parent IS NULL OR parent=''",
                null,
                null,
            )
            .use { c ->
                if (
                    c == null ||
                        c.getCount() != 1 ||
                        !c.moveToFirst() ||
                        !(saved.getString("uid") == c.getString(0))
                )
                    throw IOException(
                        "Original master must be unique after disposable cache cleanup"
                    )
            }
        val result: JSONObject =
            call(current, true).put("disposableAccountCacheRowsRemoved", removed)
        context
            .getContentResolver()
            .query(
                NativeAccountLab.Companion.ACCOUNTS,
                arrayOf<String>("uid"),
                "loggedIn=1",
                null,
                null,
            )
            .use { c ->
                result.put(
                    "originalAccountRestored",
                    c != null &&
                        c.getCount() == 1 &&
                        c.moveToFirst() &&
                        saved.getString("uid") == c.getString(0),
                )
            }
        return result
    }

    @Throws(java.lang.Exception::class)
    fun login(): JSONObject {
        val before: JSONObject = NativeClusterLab(context).snapshot()
        if (!(NativeClusterLab.Companion.LAB_ID == before.getString("clusterId")))
            throw IOException("Disposable login requires observed lab cluster")
        if (!("Palma2_Pro_C" == Build.MODEL) && !("Go103_2Lumi" == Build.MODEL))
            throw IOException("Account ABI qualified only on the two lab devices")
        if (
            "Go103_2Lumi" == Build.MODEL &&
                !File(context.getFilesDir(), "account-baseline.json").isFile()
        )
            throw IOException("Record original Lumi account first")
        return call(before, false)
    }

    @Throws(java.lang.Exception::class)
    private fun call(before: JSONObject, restore: Boolean): JSONObject {
        ReportFiles.write(
            context,
            "account-transition.json",
            JSONObject().put("phase", "login_requested").put("before", before),
        )
        val result: CompletableFuture<Int> = CompletableFuture<Int>()
        val callback: Binder =
            object : Binder() {
                @Throws(RemoteException::class)
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    if (code == IBinder.INTERFACE_TRANSACTION) {
                        if (reply != null) reply?.writeString(NativeAccountLab.Companion.CALLBACK)
                        return true
                    }
                    if (code != 1) return super.onTransact(code, data, reply, flags)
                    data.enforceInterface(NativeAccountLab.Companion.CALLBACK)
                    if (data.readInt() == 0)
                        result.completeExceptionally(IOException("Empty account callback"))
                    else {
                        // Read only the bounded, fixed Parcelable fields. Never retain credentials.
                        data.readString()
                        data.readString()
                        data.readString()
                        data.readInt()
                        data.readString()
                        data.readString()
                        data.readString()
                        data.readString()
                        val status: Int = data.readInt()
                        data.readLong()
                        data.readInt()
                        data.readInt()
                        data.readString()
                        result.complete(status)
                    }
                    if (reply != null) reply?.writeNoException()
                    return true
                }
            }
        RemoteBinding(
                context,
                "com.onyx.android.ksync",
                "com.onyx.android.ksync.service.KSyncService",
            )
            .use { binding ->
                val binder: IBinder = binding.await()
                if (!(NativeAccountLab.Companion.DESCRIPTOR == binder.getInterfaceDescriptor()))
                    throw IOException("Unexpected account Binder")
                val p: Parcel = Parcel.obtain()
                try {
                    p.writeInterfaceToken(NativeAccountLab.Companion.DESCRIPTOR)
                    p.writeInt(1)
                    p.writeString(if (restore) null else "powersync-lab@example.invalid")
                    p.writeString(if (restore) null else "000000")
                    p.writeString(null)
                    p.writeInt(if (restore) 21 else 1)
                    p.writeString(null)
                    p.writeString("PowerSync lab")
                    p.writeString(null)
                    p.writeString(null)
                    p.writeInt(0)
                    p.writeLong(0)
                    p.writeInt(if (restore) 0 else 2)
                    p.writeInt(0)
                    p.writeString(null)
                    p.writeStrongBinder(callback)
                    if (!binder.transact(65, p, null, IBinder.FLAG_ONEWAY))
                        throw IOException("Unsupported login transaction")
                } finally {
                    p.recycle()
                }
                val status: Int = result.get(60, java.util.concurrent.TimeUnit.SECONDS)
                val after: JSONObject = NativeClusterLab(context).snapshot()
                return JSONObject()
                    .put("resultCode", status)
                    .put("success", status == 0)
                    .put("after", after)
                    .put(
                        "libraryIdentitiesAndStatusesPreserved",
                        before.getJSONObject("library").getString("identityStatusSha256") ==
                            after.getJSONObject("library").getString("identityStatusSha256"),
                    )
            }
    }

    companion object {
        private const val DESCRIPTOR: String =
            "com.onyx.android.sdk.sync.ksync.service.ISyncService"
        private const val CALLBACK: String =
            "com.onyx.android.sdk.sync.ksync.service.AccountIntentCallback"
        private val ACCOUNTS: Uri =
            Uri.parse(
                "content://com.onyx.account.database.ContentProvider.KSyncAccountContentProvider/OnyxAccountModel"
            )
    }
}
