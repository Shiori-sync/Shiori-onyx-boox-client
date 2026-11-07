package ru.feryafox.shiori

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Persisted credentials and preservation metadata remain encrypted and atomic. */
class PrivateState(context: Context) {
    private val context = context.applicationContext

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(
                            ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                generateKey()
            }
        }
        return store.getKey(ALIAS, null) as SecretKey
    }

    private fun file(name: String) = AtomicFile(File(context.filesDir, "$name.sealed"))

    @Synchronized
    @Throws(Exception::class)
    fun read(name: String): JSONObject? {
        val bytes =
            try {
                file(name).readFully()
            } catch (_: FileNotFoundException) {
                return null
            }
        if (bytes.size < 29 || bytes.size > 2097152)
            throw UserError(R.string.error_invalid_saved_state)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOf(12)))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return JSONObject(String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8))
    }

    @Synchronized
    @Throws(Exception::class)
    fun write(name: String, value: JSONObject) {
        val raw = value.toString().toByteArray(Charsets.UTF_8)
        if (raw.size > 1048576) throw UserError(R.string.error_saved_state_too_large)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(raw)
        val file = file(name)
        val output = file.startWrite()
        try {
            output.write(cipher.iv)
            output.write(encrypted)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun delete(name: String) {
        file(name).delete()
    }

    @Throws(Exception::class)
    fun installationId(): String {
        val value =
            read("installation")
                ?: JSONObject().put("id", UUID.randomUUID().toString()).also {
                    write("installation", it)
                }
        return value.getString("id")
    }

    private companion object {
        const val ALIAS = "powersync-state-v1"
    }
}
