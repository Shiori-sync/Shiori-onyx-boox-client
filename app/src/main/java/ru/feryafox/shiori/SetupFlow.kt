package ru.feryafox.shiori

import org.json.JSONObject

/** Durable enrollment: an ambiguously consumed code must never be retried. */
class SetupFlow(private val store: Store, private val connection: Connection) {
    interface Store {
        @Throws(Exception::class) fun pending(): JSONObject?

        @Throws(Exception::class) fun save(pending: JSONObject)

        @Throws(Exception::class) fun clear()
    }

    interface Connection {
        @Throws(Exception::class) fun enrolled(): Boolean

        @Throws(Exception::class) fun enroll(server: String, code: String)

        @Throws(Exception::class) fun connect()
    }

    class FreshCodeRequired : Exception()

    @Throws(Exception::class)
    fun confirm() {
        if (!connection.enrolled()) {
            val pending = store.pending()
            if (pending == null || pending.optString("stage") != "draft") throw FreshCodeRequired()
            val server = pending.getString("server")
            val code = pending.getString("code")
            store.save(JSONObject().put("stage", "redeeming").put("server", server))
            connection.enroll(server, code)
        }
        store.clear()
        connection.connect()
    }
}
