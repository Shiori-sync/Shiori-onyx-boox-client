package dev.aragonite.powersync

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import org.json.JSONObject

object ReportFiles {
    @Throws(java.lang.Exception::class)
    fun write(context: Context, name: String?, report: JSONObject) {
        val file: AtomicFile = AtomicFile(File(context.getFilesDir(), name))
        var output: FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(report.toString(2).toByteArray(StandardCharsets.UTF_8))
            file.finishWrite(output)
        } catch (e: java.lang.Exception) {
            if (output != null) file.failWrite(output)
            throw e
        }
    }
}
