package ru.feryafox.shiori

import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

/** Exact known-defect replacement, with a durable private copy before native application. */
class AudioCacheRepair
@Throws(Exception::class)
constructor(packet: JSONObject, expectedUid: String) {
    @JvmField val operation: String = packet.getString("operationId").also { UUID.fromString(it) }
    @JvmField val uid: String = packet.getString("nativeUid")
    @JvmField val document: String = packet.getString("documentId")
    @JvmField val name: String = packet.getString("fileName")
    @JvmField val sha: String
    @JvmField val damagedSHA: String
    @JvmField val content: ByteArray

    init {
        if (
            uid != expectedUid ||
                !document.matches(Regex("[A-Za-z0-9_-]{1,80}")) ||
                !name.matches(Regex("[A-Za-z0-9_-]{1,100}\\.mp3"))
        )
            throw UserError(R.string.error_audio_identity_is_not_supported)
        sha = packet.getString("sha256")
        damagedSHA = packet.getString("damagedSHA256")
        if (!sha.matches(Regex("[a-f0-9]{64}")) || !damagedSHA.matches(Regex("[a-f0-9]{64}")))
            throw UserError(R.string.error_invalid_audio_integrity_proof)
        val size = packet.getLong("bytes")
        val encoded = packet.getString("content")
        if (size < 0 || size > MAX_BYTES || encoded.length > ((MAX_BYTES + 2) / 3) * 4)
            throw UserError(R.string.error_recording_exceeds_the_recovery_limit)
        content = Base64.getDecoder().decode(encoded)
        if (content.size.toLong() != size || hash(content) != sha)
            throw UserError(R.string.error_audio_integrity_check_failed)
    }

    private fun target(storage: Path): Path {
        val root = storage.toAbsolutePath().normalize()
        val path =
            root.resolve(".ksync/document").resolve(document).resolve("resource/data").resolve(name)
        var check = root
        for (part in root.relativize(path)) {
            check = check.resolve(part)
            if (Files.isSymbolicLink(check))
                throw UserError(R.string.error_linked_audio_paths_are_not_supported)
        }
        if (path.toFile().canonicalFile.toPath() != path)
            throw UserError(R.string.error_audio_path_changed)
        return path
    }

    @Throws(Exception::class)
    fun inspect(storage: Path): String {
        val path = target(storage)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return "missing"
        if (Files.size(path) != content.size.toLong()) return "different"
        return when (hash(Files.readAllBytes(path))) {
            sha -> "healthy"
            damagedSHA -> "repairable"
            else -> "different"
        }
    }

    @Throws(Exception::class)
    fun apply(storage: Path, privateBackup: Path) {
        if (inspect(storage) != "repairable")
            throw UserError(R.string.error_recording_changed_or_does_not_match_the)
        val path = target(storage)
        val before = Files.readAllBytes(path)
        if (hash(before) != damagedSHA)
            throw UserError(R.string.error_recording_changed_before_backup)
        Files.createDirectories(privateBackup)
        val backup = privateBackup.resolve("$operation-$damagedSHA.mp3")
        if (Files.exists(backup)) {
            if (hash(Files.readAllBytes(backup)) != damagedSHA)
                throw UserError(R.string.error_previous_audio_backup_differs)
        } else writeSynced(backup, before)
        val staging = storage.resolve(".powersync-audio-recovery")
        if (Files.isSymbolicLink(staging))
            throw UserError(R.string.error_linked_staging_directory_is_not_supported)
        Files.createDirectories(staging)
        val temp = Files.createTempFile(staging, "recording-", ".tmp")
        try {
            writeSynced(temp, content)
            if (inspect(storage) != "repairable")
                throw UserError(
                    R.string.error_recording_changed_during_recovery_backup_kept_nothing
                )
            Files.move(
                temp,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            if (inspect(storage) != "healthy")
                throw UserError(
                    R.string.error_recording_changed_after_replacement_backup_retained_for
                )
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024

        private fun writeSynced(path: Path, data: ByteArray) {
            FileOutputStream(path.toFile()).use {
                it.write(data)
                it.fd.sync()
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun hash(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                String.format(Locale.ROOT, "%02x", it.toInt() and 255)
            }
    }
}
