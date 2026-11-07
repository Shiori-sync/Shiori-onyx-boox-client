package ru.feryafox.shiori

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Diagnostics only; firmware versions never form an admission policy. */
object NativeCompatibility {
    const val UNREADABLE = "unreadable"

    @JvmStatic
    fun describe(
        model: String?,
        sdk: Int,
        firmware: String?,
        ksync: Long,
        ksyncSha256: String?,
    ): String =
        "model=${field(model)}; API=$sdk; firmware=${field(firmware)}; ksync=$ksync; ksyncSha256=${field(ksyncSha256)}; attempting native interfaces"

    @JvmStatic
    fun apkSha256(apk: File): String =
        try {
            apk.inputStream().use(::sha256)
        } catch (_: IOException) {
            UNREADABLE
        } catch (_: SecurityException) {
            UNREADABLE
        }

    @JvmStatic
    @Throws(IOException::class)
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun field(value: String?): String =
        if (value.isNullOrEmpty()) "unknown" else value.replace('\n', ' ').replace('\r', ' ')
}
