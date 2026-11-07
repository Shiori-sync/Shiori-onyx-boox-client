package ru.feryafox.shiori

import java.io.IOException
import java.net.URI

object SafeURL {
    @JvmStatic
    @Throws(IOException::class)
    fun base(text: String): String {
        try {
            val uri = URI.create(text.trim())
            if (
                uri.scheme != "https" ||
                    uri.host == null ||
                    uri.userInfo != null ||
                    uri.rawQuery != null ||
                    uri.rawFragment != null ||
                    uri.path !in listOf("", "/") ||
                    uri.port == 0 ||
                    uri.port < -1 ||
                    uri.port > 65535
            )
                throw UserError(R.string.error_use_an_https_server_address_without_a)
            return "https://${uri.rawAuthority}"
        } catch (_: IllegalArgumentException) {
            throw UserError(R.string.error_invalid_https_server_address)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun sameOrigin(base: String, endpoint: String) {
        try {
            val origin = URI.create(base)
            val uri = URI.create(endpoint)
            val scheme = if (uri.scheme == "wss") "https" else uri.scheme
            if (
                scheme != "https" ||
                    origin.rawAuthority != uri.rawAuthority ||
                    uri.userInfo != null ||
                    uri.rawQuery != null ||
                    uri.rawFragment != null
            )
                throw UserError(R.string.error_endpoint_is_outside_the_enrolled_server)
        } catch (_: IllegalArgumentException) {
            throw UserError(R.string.error_invalid_server_endpoint)
        }
    }
}
