package dev.aragonite.powersync

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/** Delayed, request-only ordinary-UID probe for the fixed disposable editor. */
class NativeEditorCloseLab(context: Context) {
    private val context: Context

    init {
        this.context = context.getApplicationContext()
    }

    @Throws(java.lang.Exception::class)
    private fun receipt(name: String?, value: JSONObject) {
        FileOutputStream(File(context.getFilesDir(), name)).use { out ->
            out.write(value.toString(2).toByteArray(StandardCharsets.UTF_8))
            out.getFD().sync()
        }
    }

    @Throws(java.lang.Exception::class)
    fun arm(quit: Boolean): JSONObject {
        val lab: NativeGatewayLab = NativeGatewayLab(context)
        val open: Intent? = lab.selectedOpen() // Existing lab-cluster and selected-fixture guards.
        val id: String = lab.selectedCapture().getJSONObject("note").getString("uniqueId")
        if (!("f7f635344f624650aa300f84d114a1d3" == id))
            throw IOException("Fixed synthetic notebook only")
        val name: String = if (quit) "editor-quit-dispatch.json" else "editor-noop-dispatch.json"
        File(context.getFilesDir(), name).delete()
        val armed: Long = SystemClock.elapsedRealtime()
        android.os
            .Handler(Looper.getMainLooper())
            .postDelayed(
                java.lang.Runnable {
                    try {
                        val event: JSONObject =
                            JSONObject()
                                .put("uid", android.os.Process.myUid())
                                .put("documentId", id)
                                .put("armedElapsedMs", armed)
                                .put("dispatchElapsedMs", SystemClock.elapsedRealtime())
                                .put("dispatchEpochMs", java.lang.System.currentTimeMillis())
                                .put("quitRequested", quit)
                                .put("completed", false)
                                .put("editorIdentityAtDispatchVerified", false)
                        if (quit)
                            context.sendBroadcast(
                                Intent("com.onyx.action.QUIT_NOTE")
                                    .setPackage(NativeNotes.Companion.NOTE_PACKAGE)
                            )
                        receipt(name, event)
                    } catch (e: java.lang.Exception) {
                        try {
                            receipt(name, JSONObject().put("error", e.toString()))
                        } catch (ignored: java.lang.Exception) {}
                    }
                },
                45000,
            )
        context.startActivity(open)
        return JSONObject()
            .put("armed", true)
            .put("delayMs", 45000)
            .put("quit", quit)
            .put("completed", false)
    }

    @Throws(java.lang.Exception::class)
    fun switchArm(different: Boolean): JSONObject {
        val lab: NativeGatewayLab = NativeGatewayLab(context)
        val source: Intent? = lab.selectedOpen()
        val selected: JSONObject = lab.selectedCapture().getJSONObject("note")
        val id: String = selected.getString("uniqueId")
        if (
            !("f7f635344f624650aa300f84d114a1d3" == id) ||
                !("powersync-lab" == selected.optString("userId"))
        )
            throw IOException("Owned fixed source only")
        val destination: String = if (different) "9e6004bb-7bc3-4a7e-87d9-79b9f9ba6206" else id
        val rows: JSONArray = lab.sharedCapture().getJSONArray("notes")
        var target: JSONObject? = null
        for (i in 0..<rows.length()) if (destination == rows.getJSONObject(i).getString("uniqueId"))
            target = rows.getJSONObject(i)
        if (target == null || target.optInt("status") != 1 || target.optInt("type") != 1)
            throw IOException("Live allowlisted lab destination required")
        val next: Intent? = NativeNotes(context).openIntent(target)
        val name: String =
            if (different) "editor-switch-dispatch.json" else "editor-same-dispatch.json"
        File(context.getFilesDir(), name).delete()
        val armed: Long = SystemClock.elapsedRealtime()
        android.os
            .Handler(Looper.getMainLooper())
            .postDelayed(
                java.lang.Runnable {
                    try {
                        // Revalidate cluster and owned source without foregrounding our activity.
                        lab.selectedOpen()
                        context.startActivity(next)
                        receipt(
                            name,
                            JSONObject()
                                .put("uid", android.os.Process.myUid())
                                .put("documentId", id)
                                .put("destinationId", destination)
                                .put("different", different)
                                .put("armedElapsedMs", armed)
                                .put("dispatchElapsedMs", SystemClock.elapsedRealtime())
                                .put("dispatchEpochMs", java.lang.System.currentTimeMillis())
                                .put("completed", false)
                                .put("editorIdentityAtDispatchVerified", false),
                        )
                    } catch (e: java.lang.Exception) {
                        try {
                            receipt(name, JSONObject().put("error", e.toString()))
                        } catch (ignored: java.lang.Exception) {}
                    }
                },
                45000,
            )
        context.startActivity(source)
        return JSONObject()
            .put("armed", true)
            .put("delayMs", 45000)
            .put("destinationId", destination)
            .put("completed", false)
    }

    @Throws(java.lang.Exception::class)
    fun switchForeground(): JSONObject {
        val lab: NativeGatewayLab = NativeGatewayLab(context)
        lab.selectedOpen()
        val source: JSONObject = lab.selectedCapture().getJSONObject("note")
        if (
            !("f7f635344f624650aa300f84d114a1d3" == source.getString("uniqueId")) ||
                !("powersync-lab" == source.optString("userId"))
        )
            throw IOException("Owned source required")
        val id: String = "9e6004bb-7bc3-4a7e-87d9-79b9f9ba6206"
        val rows: JSONArray = lab.sharedCapture().getJSONArray("notes")
        var target: JSONObject? = null
        for (i in 0..<rows.length()) if (id == rows.getJSONObject(i).getString("uniqueId"))
            target = rows.getJSONObject(i)
        if (target == null || target.optInt("status") != 1 || target.optInt("type") != 1)
            throw IOException("Live allowlisted destination required")
        context.startActivity(NativeNotes(context).openIntent(target))
        return JSONObject()
            .put("uid", android.os.Process.myUid())
            .put("destinationId", id)
            .put("dispatchEpochMs", java.lang.System.currentTimeMillis())
            .put("explicitHelperForegroundTransition", true)
            .put("completed", false)
    }
}
