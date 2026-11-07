package ru.feryafox.shiori.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HashMap
import java.util.Locale
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Strict validator for the single-stroke lab payload, not a general importer. */
object NativeInk {
    @Throws(IOException::class)
    fun semanticHash(shapeZip: ByteArray, points: ByteArray): String {
        val list: ByteArray
        ZipInputStream(ByteArrayInputStream(shapeZip)).use { zip ->
            if (zip.getNextEntry() == null) throw IOException("Missing shape envelope")
            list = NativeInk.read(zip)
        }
        val envelope: MutableMap<Int, Any?> = NativeInk.fields(list)
        if (!(envelope.get(1) is ByteArray)) throw IOException("Missing shape")
        val shapeBody: ByteArray = envelope.get(1) as ByteArray
        val shape: MutableMap<Int, Any?> = NativeInk.fields(shapeBody)
        val wireTypes: MutableMap<Int, Int> = HashMap<Int, Int>()
        val raw: ByteBuffer = ByteBuffer.wrap(shapeBody)
        while (raw.hasRemaining()) {
            val key: Long = NativeInk.varint(raw)
            val field: Int = (key ushr 3).toInt()
            val wire: Int = (key and 7L).toInt()
            wireTypes.put(field, wire)
            if (wire == 0) NativeInk.varint(raw)
            else {
                val length: Int =
                    (if (wire == 2) NativeInk.varint(raw) else (if (wire == 1) 8 else 4).toLong())
                        .toInt()
                raw.position(raw.position() + length)
            }
        }
        val page: String = NativeInk.padded(points, 4)
        val revision: String = NativeInk.string(shape.get(16))
        NativeInk.validate(shapeZip, points, page, revision)
        val b: ByteBuffer = ByteBuffer.wrap(points).order(ByteOrder.BIG_ENDIAN)
        val index: Int = b.getInt(points.size - 4)
        val offset: Int = b.getInt(index + 36)
        val length: Int = b.getInt(index + 40)
        val bytes: ByteArrayOutputStream = ByteArrayOutputStream()
        DataOutputStream(bytes).use { canonical ->
            for (entry in TreeMap<Int, Any?>(shape).entries) {
                // Container revisions are regenerated on native export. Their referent
                // point records are included below, so ignoring the IDs loses no ink data.
                if (entry.key == 16 || entry.key == 18) continue
                canonical.writeInt(entry.key)
                canonical.writeByte(wireTypes.getValue(entry.key))
                if (entry.value is Long) canonical.writeLong(entry.value as Long)
                else {
                    val value: ByteArray = entry.value as ByteArray
                    canonical.writeInt(value.size)
                    canonical.write(value)
                }
            }
            canonical.writeInt(length)
            canonical.write(points, offset, length)
        }
        return NativeInk.digest(bytes.toByteArray())
    }

    fun digest(data: ByteArray): String {
        try {
            val bytes: ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(data)
            val out: java.lang.StringBuilder = java.lang.StringBuilder()
            for (b in bytes) out.append(String.format(Locale.ROOT, "%02x", b.toInt() and 255))
            return out.toString()
        } catch (e: NoSuchAlgorithmException) {
            throw java.lang.AssertionError(e)
        }
    }

    @Throws(IOException::class)
    fun read(`in`: InputStream): ByteArray {
        val out: ByteArrayOutputStream = ByteArrayOutputStream()
        val buffer: ByteArray = ByteArray(8192)
        var n: Int
        while ((`in`.read(buffer).also { n = it }) != -1) {
            if (out.size() + n > 1048576) throw IOException("Ink entry exceeds limit")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    @Throws(IOException::class)
    fun validate(shapeZip: ByteArray, points: ByteArray, page: String, pointRevision: String) {
        val list: ByteArray
        ZipInputStream(ByteArrayInputStream(shapeZip)).use { zip ->
            val first: ZipEntry = zip.getNextEntry()
            if (first == null || first.isDirectory()) throw IOException("Missing shape PB")
            list = NativeInk.read(zip)
            if (zip.getNextEntry() != null) throw IOException("Expected one shape PB")
        }
        val envelope: MutableMap<Int, Any?> = NativeInk.fields(list)
        if (envelope.size != 1 || !(envelope.get(1) is ByteArray))
            throw IOException("Expected single shape envelope")
        val shape: MutableMap<Int, Any?> = NativeInk.fields(envelope.get(1) as ByteArray)
        val shapeId: String = NativeInk.string(shape.get(1))
        if (
            !shapeId.matches("[0-9a-fA-F-]{32,36}".toRegex()) ||
                !(pointRevision == NativeInk.string(shape.get(16)))
        )
            throw IOException("Shape point reference mismatch")
        if (points.size < 128 || points.size > 1048576) throw IOException("Invalid point file size")
        val b: ByteBuffer = ByteBuffer.wrap(points).order(ByteOrder.BIG_ENDIAN)
        if (
            b.getShort(2).toInt() != 1 ||
                !(page == NativeInk.padded(points, 4)) ||
                !(pointRevision == NativeInk.padded(points, 40))
        )
            throw IOException("Point header mismatch")
        val xref: Int = b.getInt(points.size - 4)
        if (xref < 80 || xref != points.size - 48 || !(shapeId == NativeInk.padded(points, xref)))
            throw IOException("Invalid point index")
        val offset: Int = b.getInt(xref + 36)
        val length: Int = b.getInt(xref + 40)
        if (offset != 76 || length != xref - 76 || length < 20 || (length - 4) % 16 != 0)
            throw IOException("Invalid point span")
        var p: Int = 80
        while (p < xref) {
            if (
                !java.lang.Float.isFinite(b.getFloat(p)) ||
                    !java.lang.Float.isFinite(b.getFloat(p + 4))
            )
                throw IOException("Non-finite stroke coordinate")
            p += 16
        }
    }

    @Throws(IOException::class)
    private fun padded(data: ByteArray, offset: Int): String {
        if (offset < 0 || offset > data.size - 36) throw IOException("Point string outside bounds")
        return String(data, offset, 36, StandardCharsets.US_ASCII).trim { it <= ' ' }
    }

    @Throws(IOException::class)
    private fun string(value: Any?): String {
        if (!(value is ByteArray)) throw IOException("Missing string field")
        return String(value as ByteArray, StandardCharsets.UTF_8)
    }

    @Throws(IOException::class)
    private fun varint(b: ByteBuffer): Long {
        var value: Long = 0
        var shift: Int = 0
        while (shift < 64) {
            if (!b.hasRemaining()) throw IOException("Truncated protobuf varint")
            val next: Int = b.get().toInt() and 255
            if (shift == 63 && (next and 254) != 0) throw IOException("Overflowing protobuf varint")
            value = value or ((next and 127).toLong() shl shift)
            if (next < 128) return value
            shift += 7
        }
        throw IOException("Oversized protobuf varint")
    }

    @Throws(IOException::class)
    fun fields(data: ByteArray): MutableMap<Int, Any?> {
        val b: ByteBuffer = ByteBuffer.wrap(data)
        val out: MutableMap<Int, Any?> = HashMap<Int, Any?>()
        while (b.hasRemaining()) {
            val key: Long = NativeInk.varint(b)
            val field: Int = (key ushr 3).toInt()
            val wire: Int = (key and 7L).toInt()
            if (field <= 0 || key ushr 3 > 536870911 || out.containsKey(field))
                throw IOException("Invalid or duplicate protobuf field")
            val value: Any?
            if (wire == 0) value = NativeInk.varint(b)
            else {
                val length: Long =
                    if (wire == 2) NativeInk.varint(b)
                    else (if (wire == 1) 8 else if (wire == 5) 4 else -1).toLong()
                if (length < 0 || length > b.remaining())
                    throw IOException("Invalid protobuf field span")
                val bytes: ByteArray = ByteArray(length.toInt())
                b.get(bytes)
                value = bytes
            }
            out.put(field, value)
        }
        return out
    }
}
