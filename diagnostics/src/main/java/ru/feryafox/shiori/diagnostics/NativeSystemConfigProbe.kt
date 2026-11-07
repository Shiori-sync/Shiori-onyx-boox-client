package ru.feryafox.shiori.diagnostics

import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Arrays
import java.util.TreeMap
import java.util.regex.Matcher
import org.json.JSONArray
import org.json.JSONObject

/** Calls the captured system ABI from the app process, without su/run-as/shell. */
class NativeSystemConfigProbe {
    @Throws(java.lang.Exception::class)
    private fun call(vararg args: String?): ByteArray {
        val command: ArrayList<String> =
            ArrayList<String>(
                mutableListOf<String?>("/system/bin/service", "call", "onyx_device_service")
            )
        command.addAll(Arrays.asList<String>(*args))
        val child: java.lang.Process =
            java.lang.ProcessBuilder(command).redirectErrorStream(true).start()
        if (!child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            child.destroyForcibly()
            throw IOException("Service command timeout")
        }
        val captured: ByteArrayOutputStream = ByteArrayOutputStream()
        val buffer: ByteArray = ByteArray(4096)
        var count: Int
        while ((child.getInputStream().read(buffer).also { count = it }) != -1) {
            captured.write(buffer, 0, count)
            if (captured.size() > 1048576) throw IOException("Oversized service reply")
        }
        val output: ByteArray = captured.toByteArray()
        if (output.size > 1048576) throw IOException("Oversized service reply")
        val bytes: ByteArrayOutputStream = ByteArrayOutputStream()
        val lines: Matcher =
            java.util.regex.Pattern.compile(
                    "(?m)^\\s*0x[0-9a-fA-F]+:\\s*((?:[0-9a-fA-F]{8}(?:\\s+|$)){1,4})"
                )
                .matcher(String(output, StandardCharsets.UTF_8))
        while (lines.find()) for (word in
            lines
                .group(1)
                .trim { it <= ' ' }
                .split("\\s+".toRegex())
                .dropLastWhile { it.isEmpty() }
                .toTypedArray()) bytes.write(
            ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(word.toLong(16) as Int)
                .array()
        )
        if (bytes.size() == 0) {
            val inline: Matcher =
                java.util.regex.Pattern.compile("Parcel\\(\\s*((?:[0-9a-fA-F]{8}\\s+)+)")
                    .matcher(String(output, StandardCharsets.UTF_8))
            if (inline.find())
                for (word in
                    inline
                        .group(1)
                        .trim { it <= ' ' }
                        .split("\\s+".toRegex())
                        .dropLastWhile { it.isEmpty() }
                        .toTypedArray()) bytes.write(
                    ByteBuffer.allocate(4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(word.toLong(16) as Int)
                        .array()
                )
        }
        val reply: ByteArray = bytes.toByteArray()
        if (reply.size < 8) throw IOException("No system Binder parcel; exit=" + child.exitValue())
        if (ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN).getInt() != 0)
            throw IOException(
                "System Binder exception code=" +
                    ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN).getInt()
            )
        return reply
    }

    @JvmOverloads
    @Throws(java.lang.Exception::class)
    fun read(key: String? = NativeSystemConfigProbe.Companion.KEY): JSONObject {
        if (
            android.os.Build.VERSION.SDK_INT < 35 ||
                !("Palma2_Pro_C" == android.os.Build.MODEL ||
                    "Go103_2Lumi" == android.os.Build.MODEL)
        )
            throw IOException("System ABI qualified only on the two API 35 lab devices")
        if (!(NativeSystemConfigProbe.Companion.KEY == key) && !("ONYX_SYSTEM_CONFIG_KEY" == key))
            throw IOException("Unsupported system key")
        val reply: ByteArray = call("3", "s16", key)
        val length: Int = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
        if (length < 0 || length > 524288 || 8L + length * 2L > reply.size)
            throw IOException("Invalid system configuration reply")
        return JSONObject(String(reply, 8, length * 2, StandardCharsets.UTF_16LE))
    }

    @Throws(java.lang.Exception::class)
    fun write(key: String?, value: JSONObject) {
        read(key)
        val reply: ByteArray = call("4", "s16", key, "s16", value.toString())
        if (
            ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN).getInt(4) == 0 ||
                !(NativeSystemConfigProbe.Companion.canonical(read(key)) ==
                    NativeSystemConfigProbe.Companion.canonical(value))
        )
            throw IOException("System write/readback failed")
    }

    @Throws(java.lang.Exception::class)
    fun run(): JSONObject {
        if (android.os.Build.VERSION.SDK_INT < 35)
            throw IOException("Captured system ABI qualified only on API 35 lab firmware")
        val report: JSONObject =
            JSONObject()
                .put("uid", android.os.Process.myUid())
                .put("pid", android.os.Process.myPid())
                .put(
                    "method",
                    "app ProcessBuilder /system/bin/service; no shell, su, run-as or host write",
                )
                .put(
                    "selinuxContext",
                    String(
                            java.nio.file.Files.readAllBytes(
                                java.nio.file.Paths.get("/proc/self/attr/current")
                            ),
                            StandardCharsets.UTF_8,
                        )
                        .trim { it <= ' ' },
                )
        val original: JSONObject?
        try {
            original = read()
            report.put("liveReadSucceeded", true)
        } catch (e: java.lang.Exception) {
            return report.put("liveReadSucceeded", false).put("failure", e.toString())
        }
        if (!("en" == original.optString("curClusterId")))
            throw IOException("Probe requires restored en baseline")
        val id: String = "powersync_uid_probe"
        if (original.getJSONObject("clusterMap").has(id))
            throw IOException("Probe target already exists")
        val candidate: JSONObject = JSONObject(original.toString())
        candidate
            .getJSONObject("clusterMap")
            .put(
                id,
                JSONObject()
                    .put("clusterId", id)
                    .put("name", "PowerSync unselected UID probe")
                    .put("clusterHost", JSONObject().put("host", "http://127.0.0.1:9")),
            )
        try {
            val result: ByteArray =
                call("4", "s16", NativeSystemConfigProbe.Companion.KEY, "s16", candidate.toString())
            report.put(
                "writeReturnedTrue",
                ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getInt(4) != 0,
            )
            val observed: JSONObject = read()
            report
                .put(
                    "arbitraryEntryReadBack",
                    NativeSystemConfigProbe.Companion.canonical(observed) ==
                        NativeSystemConfigProbe.Companion.canonical(candidate),
                )
                .put("activeClusterUnchanged", "en" == observed.optString("curClusterId"))
        } catch (e: java.lang.Exception) {
            report.put("failure", e.toString())
        } finally {
            call("4", "s16", NativeSystemConfigProbe.Companion.KEY, "s16", original.toString())
            report.put(
                "originalConfigurationRestored",
                NativeSystemConfigProbe.Companion.canonical(read()) ==
                    NativeSystemConfigProbe.Companion.canonical(original),
            )
            if (!report.getBoolean("originalConfigurationRestored"))
                throw IOException("System configuration restore failed")
        }
        return report
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun canonical(value: Any?): String {
            if (value is JSONObject) {
                val sorted: TreeMap<String?, String?> = TreeMap<String?, String?>()
                val keys: MutableIterator<String?> = value.keys()
                while (keys.hasNext()) {
                    val key: String? = keys.next()
                    sorted.put(key, NativeSystemConfigProbe.Companion.canonical(value.get(key)))
                }
                return sorted.toString()
            }
            if (value is JSONArray) {
                val entries: ArrayList<String> = ArrayList<String>()
                for (i in 0..<value.length()) entries.add(
                    NativeSystemConfigProbe.Companion.canonical(value.get(i))
                )
                return entries.toString()
            }
            return JSONArray().put(value).toString()
        }

        private const val KEY: String = "ONYX_CLUSTER_SYSTEM_KEY"
    }
}
