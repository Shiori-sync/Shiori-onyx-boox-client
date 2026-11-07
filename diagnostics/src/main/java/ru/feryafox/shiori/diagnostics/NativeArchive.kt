package ru.feryafox.shiori.diagnostics

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import java.util.Enumeration
import java.util.Locale
import java.util.TreeMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/** Inspect without extracting. Hash logical entries independently of ZIP timestamps. */
object NativeArchive {
    @Throws(java.lang.Exception::class)
    fun inkIdentity(file: File?, documentId: String?): JSONObject {
        val shapes: JSONArray = JSONArray()
        ZipFile(file).use { zip ->
            val entries: Enumeration<out ZipEntry> = zip.entries()
            while (entries.hasMoreElements()) {
                val entry: ZipEntry = entries.nextElement()
                val name: String = entry.getName()
                if (!name.startsWith(documentId + "/shape/") || !name.endsWith(".zip")) continue
                val shapeBytes: ByteArray
                zip.getInputStream(entry).use { `in` -> shapeBytes = NativeInk.read(`in`) }
                val shape: MutableMap<Int, Any?>
                ZipInputStream(ByteArrayInputStream(shapeBytes)).use { nested ->
                    if (nested.getNextEntry() == null) throw IOException("Missing shape envelope")
                    val envelope: Any? = NativeInk.fields(NativeInk.read(nested)).get(1)
                    if (!(envelope is ByteArray)) throw IOException("Missing shape")
                    shape = NativeInk.fields(envelope as ByteArray)
                }
                if (!(3L == shape.get(12)))
                    throw IOException("Ink identity currently supports single oil-pen shapes only")
                val page: String =
                    name
                        .substring(name.lastIndexOf('/') + 1)
                        .split("#".toRegex())
                        .dropLastWhile { it.isEmpty() }
                        .toTypedArray()[0]
                val revision: String =
                    String(shape.get(16) as ByteArray, java.nio.charset.StandardCharsets.UTF_8)
                val point: ZipEntry =
                    zip.getEntry(
                        documentId + "/point/" + page + "/" + page + "#" + revision + "#points"
                    )
                if (point == null) throw IOException("Missing referenced point asset")
                zip.getInputStream(point).use { `in` ->
                    shapes.put(
                        JSONObject()
                            .put("pageId", page)
                            .put(
                                "shapeId",
                                String(
                                    shape.get(1) as ByteArray,
                                    java.nio.charset.StandardCharsets.UTF_8,
                                ),
                            )
                            .put("sha256", NativeInk.semanticHash(shapeBytes, NativeInk.read(`in`)))
                    )
                }
            }
        }
        return JSONObject()
            .put("scope", "single-oil-pen-shape-records")
            .put("shapes", shapes)
            .put("completeNotebookIdentity", false)
    }

    @Throws(java.lang.Exception::class)
    fun inspect(file: File): JSONObject {
        if (file.length() > 268435456L) throw IOException("Archive exceeds lab size limit")
        val entries: TreeMap<String?, JSONObject> = TreeMap<String?, JSONObject>()
        var total: Long = 0
        ZipFile(file).use { zip ->
            val list: Enumeration<out ZipEntry> = zip.entries()
            val buffer: ByteArray = ByteArray(16384)
            while (list.hasMoreElements()) {
                val entry: ZipEntry = list.nextElement()
                val name: String = entry.getName()
                if (
                    name.startsWith("/") ||
                        name.contains("\\") ||
                        Arrays.asList<String>(*name.split("/".toRegex()).toTypedArray())
                            .contains("..")
                )
                    throw IOException("Unsafe archive entry")
                if (entry.isDirectory()) continue
                if (entries.size >= 10000 || entries.containsKey(name))
                    throw IOException("Duplicate or excessive entries")
                val hash: MessageDigest = MessageDigest.getInstance("SHA-256")
                val crc: CRC32 = CRC32()
                var size: Long = 0
                zip.getInputStream(entry).use { `in` ->
                    var n: Int
                    while ((`in`.read(buffer).also { n = it }) != -1) {
                        size += n.toLong()
                        total += n.toLong()
                        if (total > 536870912L)
                            throw IOException("Expanded archive exceeds lab limit")
                        hash.update(buffer, 0, n)
                        crc.update(buffer, 0, n)
                    }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc())
                    throw IOException("Archive integrity mismatch")
                entries.put(
                    name,
                    JSONObject()
                        .put("name", name)
                        .put("bytes", size)
                        .put("sha256", NativeArchive.hex(hash.digest())),
                )
            }
        }
        if (entries.isEmpty()) throw IOException("Empty native archive")
        val manifest: JSONArray = JSONArray()
        val logical: MessageDigest = MessageDigest.getInstance("SHA-256")
        for (entry in entries.values) {
            manifest.put(entry)
            logical.update(
                (entry.getString("name") +
                        "\u0000" +
                        entry.getLong("bytes") +
                        "\u0000" +
                        entry.getString("sha256") +
                        "\n")
                    .toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            )
        }
        return JSONObject()
            .put("entries", manifest)
            .put("expandedBytes", total)
            .put("logicalSha256", NativeArchive.hex(logical.digest()))
            .put("zipIntegrityVerified", true)
    }

    private fun hex(bytes: ByteArray): String {
        val s: java.lang.StringBuilder = java.lang.StringBuilder()
        for (b in bytes) s.append(String.format(Locale.ROOT, "%02x", b.toInt() and 255))
        return s.toString()
    }
}
