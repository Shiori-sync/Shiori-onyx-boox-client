package dev.aragonite.powersync

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

class ProbeRunner(private val context: Context) {
    private val notes = NativeNotes(context)
    private val report = JSONObject()
    private val checks = JSONArray()

    private fun check(name: String, action: () -> Any?) {
        val result = JSONObject().put("name", name)
        try {
            result.put("status", "pass").put("result", action())
        } catch (error: Exception) {
            result
                .put("status", "fail")
                .put("errorClass", error.javaClass.name)
                .put("message", error.message.toString())
        }
        checks.put(result)
        save()
    }

    fun run(): JSONObject {
        report
            .put("formatVersion", 1)
            .put("createdAt", System.currentTimeMillis())
            .put("uid", android.os.Process.myUid())
            .put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT)
            .put("allFilesAccess", Environment.isExternalStorageManager())
            .put("checks", checks)
        for (pkg in arrayOf(NativeNotes.NOTE_PACKAGE, "com.onyx.android.ksync")) check(
            "$pkg.package"
        ) {
            val info =
                context.packageManager.getPackageInfo(
                    pkg,
                    PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS,
                )
            val result =
                JSONObject()
                    .put("versionCode", info.longVersionCode)
                    .put("versionName", info.versionName)
            val services = JSONArray()
            info.services?.forEach { service ->
                if (service.name.endsWith(".NoteService") || service.name.endsWith(".KSyncService"))
                    services.put(
                        JSONObject()
                            .put("name", service.name)
                            .put("exported", service.exported)
                            .put("permission", service.permission ?: JSONObject.NULL)
                    )
            }
            result.put("services", services)
        }
        check("notes.provider") { notes.rows(NativeNotes.NOTES, null, null, false) }
        check("notes.shapeProvider") {
            notes.rows(
                Uri.parse("content://com.onyx.android.sdk.note.ContentProvider/ShapeModel"),
                null,
                null,
                false,
            )
        }
        check("notes.newShapeProvider") {
            notes.rows(
                Uri.parse("content://com.onyx.android.sdk.shape.ContentProvider/ShapeModel"),
                null,
                null,
                false,
            )
        }
        for ((pkg, service) in
            listOf(
                NativeNotes.NOTE_PACKAGE to NativeNotes.NOTE_SERVICE,
                "com.onyx.android.ksync" to "com.onyx.android.ksync.service.KSyncService",
            )) {
            check("$service.bind") {
                RemoteBinding(context, pkg, service).use {
                    JSONObject().put("descriptor", it.await().interfaceDescriptor)
                }
            }
        }
        check("notes.virtualPages") {
            context.contentResolver
                .query(
                    NativeNotes.NOTES,
                    arrayOf("uniqueId"),
                    "type=? AND status=?",
                    arrayOf("1", "1"),
                    "updatedAt DESC LIMIT 1",
                )
                .use { cursor ->
                    if (cursor == null || !cursor.moveToFirst())
                        JSONObject().put("available", false)
                    else notes.pages(cursor.getString(0))
                }
        }
        check("storage.ksync") {
            val entries =
                File(Environment.getExternalStorageDirectory(), ".ksync").listFiles()
                    ?: throw IOException("Cannot enumerate .ksync")
            JSONObject().put("entries", JSONArray().apply { entries.forEach { put(it.name) } })
        }
        save()
        return report
    }

    private fun save() {
        ReportFiles.write(context, "probe.json", report)
    }
}
