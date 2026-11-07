package ru.feryafox.shiori.diagnostics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.onyx.android.sdk.note.INoteContentService
import com.onyx.android.sdk.note.NoteContentIntentArgs
import com.onyx.android.sdk.note.NoteContentIntentCallback
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import org.json.JSONArray
import org.json.JSONObject

class NativeNotes(context: Context) {
    private val context: Context

    init {
        this.context = context
    }

    @Throws(java.lang.Exception::class)
    fun notes(selection: String?, args: Array<String>?): JSONArray {
        return rows(NativeNotes.Companion.NOTES, selection, args, true)
    }

    @Throws(java.lang.Exception::class)
    fun rows(uri: Uri, selection: String?, args: Array<String>?, complete: Boolean): JSONArray {
        val out: JSONArray = JSONArray()
        context.getContentResolver().query(uri, null, selection, args, null).use { c ->
            if (c == null) throw IOException("Provider returned null cursor")
            if (!complete)
                return JSONArray()
                    .put(
                        JSONObject()
                            .put("count", c.getCount())
                            .put("columns", JSONArray(c.getColumnNames()))
                    )
            while (c.moveToNext()) {
                val row: JSONObject = JSONObject()
                for (i in 0..<c.getColumnCount()) {
                    val value: Any? =
                        when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            Cursor.FIELD_TYPE_BLOB ->
                                JSONObject()
                                    .put("encoding", "base64")
                                    .put(
                                        "value",
                                        android.util.Base64.encodeToString(
                                            c.getBlob(i),
                                            android.util.Base64.NO_WRAP,
                                        ),
                                    )
                            Cursor.FIELD_TYPE_STRING -> c.getString(i)
                            else -> throw IOException("Unexpected cursor value type")
                        }
                    row.put(c.getColumnName(i), value)
                }
                out.put(row)
            }
        }
        return out
    }

    @Throws(java.lang.Exception::class)
    fun pages(docId: String): JSONObject {
        RemoteBinding(
                context,
                NativeNotes.Companion.NOTE_PACKAGE,
                NativeNotes.Companion.NOTE_SERVICE,
            )
            .use { binding ->
                val binder: IBinder = binding.await()
                if (
                    !("com.onyx.android.sdk.note.INoteContentService" ==
                        binder.getInterfaceDescriptor())
                )
                    throw IOException("Unexpected Notes interface")
                val result: CompletableFuture<JSONObject> = CompletableFuture<JSONObject>()
                INoteContentService.Stub.asInterface(binder)
                    .loadVirtualPageIdList(
                        docId,
                        object : NoteContentIntentCallback.Stub() {
                            override fun read(args: NoteContentIntentArgs?) {
                                try {
                                    if (args == null || !(docId == args.docId))
                                        throw IOException("Unexpected callback document")
                                    var content: String? = args.content
                                    if (args.pfd != null) {
                                        ParcelFileDescriptor.AutoCloseInputStream(args.pfd).use {
                                            stream ->
                                            val buffer: ByteArrayOutputStream =
                                                ByteArrayOutputStream()
                                            val chunk: ByteArray = ByteArray(8192)
                                            var n: Int
                                            while ((stream.read(chunk).also { n = it }) != -1) {
                                                if (buffer.size() + n > 4 * 1024 * 1024)
                                                    throw IOException("Page callback exceeds bound")
                                                buffer.write(chunk, 0, n)
                                            }
                                            content =
                                                String(buffer.toByteArray(), StandardCharsets.UTF_8)
                                        }
                                    }
                                    result.complete(
                                        JSONObject()
                                            .put("documentId", docId)
                                            .put("pageIds", JSONArray(content))
                                    )
                                } catch (e: java.lang.Exception) {
                                    result.completeExceptionally(e)
                                }
                            }
                        },
                    )
                return result.get(20, java.util.concurrent.TimeUnit.SECONDS)
            }
    }

    @Throws(java.lang.Exception::class)
    fun send(action: String?, bean: JSONObject, direct: Boolean) {
        RemoteBinding(
                context,
                NativeNotes.Companion.NOTE_PACKAGE,
                NativeNotes.Companion.NOTE_SERVICE,
            )
            .use { binding ->
                val binder: IBinder = binding.await()
                if (
                    !("com.onyx.android.sdk.note.INoteContentService" ==
                        binder.getInterfaceDescriptor())
                )
                    throw IOException("Unexpected Notes interface")
                val command: Bundle = Bundle()
                command.putString("SERVICE_ACTION", action)
                command.putString(
                    if (direct) "OPEN_NOTE_BEAN" else "note_service_bean",
                    bean.toString(),
                )
                INoteContentService.Stub.asInterface(binder).sendMessageAction(command)
            }
    }

    @Throws(java.lang.Exception::class)
    fun openIntent(row: JSONObject): Intent {
        val bean: JSONObject =
            JSONObject()
                .put("documentId", row.getString("uniqueId"))
                .put("parentUniqueId", row.optString("parentUniqueId", ""))
                .put("title", row.optString("title", ""))
                .put("activeScene", row.optInt("activeScene", 0))
        return Intent()
            .setComponent(
                ComponentName(
                    NativeNotes.Companion.NOTE_PACKAGE,
                    NativeNotes.Companion.NOTE_PACKAGE + ".note.ui.ScribbleActivity",
                )
            )
            .putExtra("OPEN_NOTE_BEAN", bean.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Throws(java.lang.Exception::class)
    fun notifyFixtureData(documentId: String?) {
        RemoteBinding(
                context,
                NativeNotes.Companion.NOTE_PACKAGE,
                NativeNotes.Companion.NOTE_SERVICE,
            )
            .use { binding ->
                val binder: IBinder = binding.await()
                if (
                    !("com.onyx.android.sdk.note.INoteContentService" ==
                        binder.getInterfaceDescriptor())
                )
                    throw IOException("Unexpected Notes interface")
                val command: Bundle = Bundle()
                command.putString("KSYNC_NOTE_ACTION", "KSYNC_NOTE_DATA_UPDATE_ACTION")
                command.putString("KEY_DOCUMENT_ID", documentId)
                INoteContentService.Stub.asInterface(binder).sendMessageAction(command)
            }
    }

    companion object {
        const val NOTE_PACKAGE: String = "com.onyx.android.note"
        val NOTE_SERVICE: String = NativeNotes.Companion.NOTE_PACKAGE + ".note.NoteService"
        val NOTES: Uri = Uri.parse("content://com.onyx.android.sdk.note.ContentProvider/NoteModel")
    }
}
