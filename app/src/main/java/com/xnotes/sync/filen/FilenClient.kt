package com.xnotes.sync.filen

import org.json.JSONObject

/**
 * High-level Filen operations built on [FilenApi] + [FilenCrypto]: login, folder
 * resolution, and whole-file upload/download. Only the v2 data scheme is used; on a
 * v3 account the Argon2id login still works and files round-trip within this app.
 */
class FilenClient(private val api: FilenApi, private val session: FilenSession) {

    // The current (last) master key encrypts new metadata; all keys are tried when decrypting.
    private val encKey: String get() = session.masterKeys.last()
    private val allKeys: List<String> get() = session.masterKeys

    data class FileMetadata(
        val name: String, val size: Long, val mime: String,
        val key: String, val lastModified: Long, val hash: String?,
    )

    companion object {
        private const val CHUNK_SIZE = 1024 * 1024

        fun login(api: FilenApi, email: String, password: String, twoFactorCode: String): FilenSession {
            val info = api.authInfo(email)
            val derived = FilenCrypto.deriveAuth(password, info.salt, info.authVersion)
            val result = api.login(email, derived.password, twoFactorCode, info.authVersion)
            api.apiKey = result.apiKey
            val keys = LinkedHashSet<String>()
            result.masterKeys?.let { blob ->
                FilenCrypto.decryptMetadata(blob, listOf(derived.masterKey))?.split("|")?.forEach {
                    if (it.isNotBlank()) keys.add(it)
                }
            }
            keys.add(derived.masterKey)
            val base = api.baseFolderUuid()
            return FilenSession(email, result.apiKey, keys.toList(), info.authVersion, base)
        }
    }

    fun listFolders(parentUuid: String): List<Pair<String, FilenApi.RemoteFolder>> =
        api.dirContent(parentUuid).folders.mapNotNull { f ->
            decryptName(f.name)?.let { it to f }
        }

    /** Decrypted (name, file, metadata) triples for the files directly in a folder. */
    fun listFiles(parentUuid: String): List<Triple<String, FilenApi.RemoteFile, FileMetadata>> =
        api.dirContent(parentUuid).files.mapNotNull { file ->
            decryptFileMetadata(file.metadata)?.let { meta -> Triple(meta.name, file, meta) }
        }

    fun dirContent(parentUuid: String): FilenApi.DirContent = api.dirContent(parentUuid)

    data class DecryptedContent(
        val folders: List<Pair<String, FilenApi.RemoteFolder>>,
        val files: List<Triple<String, FilenApi.RemoteFile, FileMetadata>>,
    )

    /** One request per folder: decrypted subfolder names and file metadata together. */
    fun listDecrypted(parentUuid: String): DecryptedContent {
        val content = api.dirContent(parentUuid)
        val folders = content.folders.mapNotNull { f -> decryptName(f.name)?.let { it to f } }
        val files = content.files.mapNotNull { file ->
            decryptFileMetadata(file.metadata)?.let { meta -> Triple(meta.name, file, meta) }
        }
        return DecryptedContent(folders, files)
    }

    /** Resolve (creating as needed) a chain of subfolders under [parentUuid]. Returns the deepest uuid. */
    fun ensureFolderPath(parentUuid: String, segments: List<String>): String {
        var current = parentUuid
        for (seg in segments) {
            val existing = listFolders(current).firstOrNull { it.first.equals(seg, ignoreCase = true) }
            current = existing?.second?.uuid ?: createFolder(current, seg)
        }
        return current
    }

    fun createFolder(parentUuid: String, name: String): String {
        val uuid = FilenCrypto.uuidV4()
        val nameEnc = FilenCrypto.encryptMetadata(JSONObject().put("name", name).toString(), encKey)
        api.createDir(uuid, nameEnc, FilenCrypto.hashFileNameV2(name), parentUuid)
        return uuid
    }

    fun uploadFile(parentUuid: String, fileName: String, bytes: ByteArray, lastModified: Long): String {
        val key = FilenCrypto.generateFileKey()
        val uploadKey = FilenCrypto.randomString(32)
        val rm = FilenCrypto.randomString(32)
        val uuid = FilenCrypto.uuidV4()
        val chunks = if (bytes.isEmpty()) 0 else (bytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        for (index in 0 until chunks) {
            val from = index * CHUNK_SIZE
            val chunk = bytes.copyOfRange(from, minOf(from + CHUNK_SIZE, bytes.size))
            api.uploadChunk(uuid, index, parentUuid, uploadKey, FilenCrypto.encryptChunk(chunk, key))
        }
        val mime = "application/octet-stream"
        val metadataJson = JSONObject()
            .put("name", fileName).put("size", bytes.size).put("mime", mime)
            .put("key", key).put("lastModified", lastModified).put("hash", FilenCrypto.sha512Hex(bytes))
            .toString()
        api.uploadDone(
            uuid = uuid,
            encryptedName = FilenCrypto.encryptMetadata(fileName, key),
            nameHashed = FilenCrypto.hashFileNameV2(fileName),
            encryptedSize = FilenCrypto.encryptMetadata(bytes.size.toString(), key),
            chunks = chunks,
            encryptedMime = FilenCrypto.encryptMetadata(mime, key),
            rm = rm,
            metadata = FilenCrypto.encryptMetadata(metadataJson, encKey),
            uploadKey = uploadKey,
        )
        return uuid
    }

    fun downloadFile(file: FilenApi.RemoteFile, meta: FileMetadata): ByteArray {
        val out = java.io.ByteArrayOutputStream(file.size.toInt().coerceAtLeast(0))
        for (index in 0 until file.chunks) {
            val enc = api.downloadChunk(file.region, file.bucket, file.uuid, index)
            out.write(FilenCrypto.decryptChunk(enc, meta.key))
        }
        return out.toByteArray()
    }

    fun trashFile(uuid: String) = api.trashFile(uuid)

    fun trashFolder(uuid: String) = api.trashDir(uuid)

    private fun decryptName(encrypted: String): String? {
        val plain = FilenCrypto.decryptMetadata(encrypted, allKeys) ?: return null
        return try {
            JSONObject(plain).optString("name").ifEmpty { null }
        } catch (e: Exception) {
            plain.ifBlank { null }
        }
    }

    private fun decryptFileMetadata(encrypted: String): FileMetadata? {
        val plain = FilenCrypto.decryptMetadata(encrypted, allKeys) ?: return null
        return try {
            val o = JSONObject(plain)
            val key = o.optString("key").ifEmpty { return null }
            val name = o.optString("name").ifEmpty { return null }
            FileMetadata(name, o.optLong("size"), o.optString("mime", "application/octet-stream"), key, o.optLong("lastModified"), o.optString("hash").ifEmpty { null })
        } catch (e: Exception) {
            null
        }
    }
}

/** Persisted Filen session. Held encrypted at rest by [FilenSecureStore]. */
data class FilenSession(
    val email: String,
    val apiKey: String,
    val masterKeys: List<String>,
    val authVersion: Int,
    val baseFolderUuid: String,
)
