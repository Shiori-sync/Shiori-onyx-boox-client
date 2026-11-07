package dev.aragonite.powersync.client

import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject

object ServerClient {
    /** Bounded prefix compatible with API 30, including streams returning zero bytes. */
    @JvmStatic
    @Throws(IOException::class)
    fun readPrefix(input: InputStream, limit: Int): ByteArray {
        require(limit >= 0) { "negative limit" }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(minOf(4096, limit))
        while (output.size() < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            if (count == 0) {
                val value = input.read()
                if (value < 0) break
                output.write(value)
            } else output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    @JvmStatic
    @Throws(Exception::class)
    fun redeem(
        base: String,
        code: String,
        installation: String,
        nativeDevice: String,
        nativeUid: String,
    ): JSONObject =
        request(
            "$base/api/v1/boox/enroll",
            "POST",
            JSONObject()
                .put("code", code)
                .put("installationId", installation)
                .put("nativeDeviceId", nativeDevice)
                .put("model", Build.MODEL)
                .put("nativeUid", nativeUid)
                .put("preserveNativeIdentity", true),
            null,
        )

    @JvmStatic
    @JvmOverloads
    @Throws(Exception::class)
    fun request(
        url: String,
        method: String,
        body: JSONObject?,
        token: String?,
        limit: Int = 262144,
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        if (connection.url.protocol != "https") throw UserError(R.string.error_https_required)
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            if (status == 409) {
                if (!url.endsWith("/api/v1/boox/enroll"))
                    throw UserError(R.string.error_server_data_changed_or_recovery_is_unavailable)
                connection.errorStream?.use {
                    val detail = JSONObject(String(readPrefix(it, 4096), Charsets.UTF_8))
                    if (detail.optString("error") == "native_identity_required")
                        throw UserError(R.string.error_sign_in_to_your_boox_account_in)
                }
                throw UserError(R.string.error_this_server_is_linked_to_a_different)
            }
            if (status !in 200..299) throw UserError(R.string.error_server_http_status, status)
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > limit)
                        throw UserError(R.string.error_server_reply_exceeds_limit)
                    output.write(buffer, 0, count)
                }
                return JSONObject(String(output.toByteArray(), Charsets.UTF_8))
            }
        } finally {
            connection.disconnect()
        }
    }
}
