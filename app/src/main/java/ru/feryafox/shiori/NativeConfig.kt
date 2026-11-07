package ru.feryafox.shiori

import java.util.TreeMap
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object NativeConfig {
    @JvmStatic
    @Throws(JSONException::class)
    fun canonical(value: Any?): String =
        when (value) {
            is JSONObject ->
                TreeMap<String, String>()
                    .apply { value.keys().forEach { key -> put(key, canonical(value.get(key))) } }
                    .toString()
            is JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }.toString()
            else -> JSONArray().put(value).toString()
        }

    @JvmStatic
    @Throws(JSONException::class)
    fun parse(key: String, raw: String): JSONObject {
        if (key == "ONYX_SYSTEM_CONFIG_KEY" && raw.isEmpty())
            return JSONObject().put("configMap", JSONObject())
        return JSONObject(raw).apply {
            if (key == "ONYX_SYSTEM_CONFIG_KEY") {
                if (!has("configMap")) put("configMap", JSONObject())
                else getJSONObject("configMap")
            }
        }
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun flag(map: JSONObject, key: String): String {
        if (!map.has(key)) return "false"
        val value = map.get(key)
        if (value !is String || value !in listOf("true", "false"))
            throw JSONException("Invalid native sync flag")
        return value
    }
}
