package com.xnotes.sync.filen

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class FilenException(message: String, val code: String? = null) : IOException(message)

/**
 * Thin Filen HTTP API over HttpURLConnection (no networking library is pulled in for one feature).
 * Responses use the {status,message,code,data} envelope except raw chunk downloads.
 */
class FilenApi(@Volatile var apiKey: String? = null) {
    private val gateway = listOf("https://gateway.filen.io", "https://gateway.filen.net")
    private val ingest = listOf("https://ingest.filen.io", "https://ingest.filen.net")
    private val egest = listOf("https://egest.filen.io", "https://egest.filen.net")

    data class AuthInfo(val authVersion: Int, val salt: String)
    data class LoginResult(val apiKey: String, val masterKeys: String?, val publicKey: String?, val privateKey: String?)
    data class RemoteFolder(val uuid: String, val name: String, val parent: String)
    data class RemoteFile(
        val uuid: String, val metadata: String, val parent: String, val chunks: Int,
        val size: Long, val region: String, val bucket: String, val version: Int,
    )
    data class DirContent(val folders: List<RemoteFolder>, val files: List<RemoteFile>)

    fun authInfo(email: String): AuthInfo {
        val data = post(gateway, "/v3/auth/info", JSONObject().put("email", email), auth = false)
        return AuthInfo(data.getInt("authVersion"), data.getString("salt"))
    }

    fun login(email: String, password: String, twoFactorCode: String, authVersion: Int): LoginResult {
        val body = JSONObject()
            .put("email", email).put("password", password)
            .put("twoFactorCode", twoFactorCode.ifBlank { "XXXXXX" }).put("authVersion", authVersion)
        val data = post(gateway, "/v3/login", body, auth = false)
        return LoginResult(
            data.getString("apiKey"),
            data.optStringOrNull("masterKeys"),
            data.optStringOrNull("publicKey"),
            data.optStringOrNull("privateKey"),
        )
    }

    fun baseFolderUuid(): String = get(gateway, "/v3/user/baseFolder").getString("uuid")

    fun dirContent(uuid: String): DirContent {
        val data = post(gateway, "/v3/dir/content", JSONObject().put("uuid", uuid))
        val folders = data.optJSONArray("folders")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RemoteFolder(o.getString("uuid"), o.getString("name"), o.optString("parent"))
            }
        } ?: emptyList()
        val files = data.optJSONArray("uploads")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RemoteFile(
                    o.getString("uuid"), o.getString("metadata"), o.optString("parent"),
                    o.optInt("chunks"), o.optLong("size"), o.optString("region"),
                    o.optString("bucket"), o.optInt("version", 2),
                )
            }
        } ?: emptyList()
        return DirContent(folders, files)
    }

    fun createDir(uuid: String, encryptedName: String, nameHashed: String, parent: String): String {
        val body = JSONObject().put("uuid", uuid).put("name", encryptedName).put("nameHashed", nameHashed).put("parent", parent)
        return post(gateway, "/v3/dir/create", body).getString("uuid")
    }

    fun trashFile(uuid: String) {
        post(gateway, "/v3/file/trash", JSONObject().put("uuid", uuid))
    }

    fun trashDir(uuid: String) {
        post(gateway, "/v3/dir/trash", JSONObject().put("uuid", uuid))
    }

    /** Upload one encrypted chunk. Returns the assigned (bucket, region). */
    fun uploadChunk(uuid: String, index: Int, parent: String, uploadKey: String, encrypted: ByteArray): Pair<String, String> {
        val bufferHash = FilenCrypto.sha512Hex(encrypted)
        val query = "uuid=$uuid&index=$index&parent=$parent&uploadKey=$uploadKey"
        val endpoint = "/v3/upload?$query&hash=$bufferHash"
        // Checksum is the SHA-512 of the URL params JSON, in the order uuid,index,parent,uploadKey,hash.
        val paramsJson = "{\"uuid\":\"$uuid\",\"index\":\"$index\",\"parent\":\"$parent\",\"uploadKey\":\"$uploadKey\",\"hash\":\"$bufferHash\"}"
        val checksum = FilenCrypto.sha512Hex(paramsJson.toByteArray(Charsets.UTF_8))
        val data = postBinary(ingest, endpoint, encrypted, checksum)
        return data.optString("bucket") to data.optString("region")
    }

    fun uploadDone(
        uuid: String, encryptedName: String, nameHashed: String, encryptedSize: String,
        chunks: Int, encryptedMime: String, rm: String, metadata: String, uploadKey: String,
    ) {
        val body = JSONObject()
            .put("uuid", uuid).put("name", encryptedName).put("nameHashed", nameHashed)
            .put("size", encryptedSize).put("chunks", chunks).put("mime", encryptedMime)
            .put("rm", rm).put("metadata", metadata).put("version", 2).put("uploadKey", uploadKey)
        post(gateway, "/v3/upload/done", body)
    }

    fun downloadChunk(region: String, bucket: String, uuid: String, index: Int): ByteArray {
        var lastError: IOException? = null
        for (host in egest) {
            try {
                val conn = open(host + "/$region/$bucket/$uuid/$index", "GET")
                conn.connectTimeout = 30_000; conn.readTimeout = 60_000
                apiKey?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                conn.connect()
                if (conn.responseCode !in 200..299) { lastError = IOException("download HTTP ${conn.responseCode}"); conn.disconnect(); continue }
                return conn.inputStream.use { it.readBytes() }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("download failed")
    }

    private fun post(hosts: List<String>, endpoint: String, body: JSONObject, auth: Boolean = true): JSONObject =
        request(hosts, endpoint, "POST", body.toString().toByteArray(Charsets.UTF_8), "application/json", auth, null)

    private fun get(hosts: List<String>, endpoint: String): JSONObject =
        request(hosts, endpoint, "GET", null, null, true, null)

    private fun postBinary(hosts: List<String>, endpoint: String, body: ByteArray, checksum: String): JSONObject =
        request(hosts, endpoint, "POST", body, "application/octet-stream", true, checksum)

    private fun request(
        hosts: List<String>, endpoint: String, method: String, body: ByteArray?,
        contentType: String?, auth: Boolean, checksum: String?,
    ): JSONObject {
        var lastError: IOException? = null
        for (host in hosts) {
            try {
                val conn = open(host + endpoint, method)
                conn.connectTimeout = 30_000
                conn.readTimeout = if (contentType == "application/octet-stream") 180_000 else 60_000
                if (auth) apiKey?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                checksum?.let { conn.setRequestProperty("Checksum", it) }
                if (body != null) {
                    conn.doOutput = true
                    contentType?.let { conn.setRequestProperty("Content-Type", it) }
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                }
                val ok = conn.responseCode in 200..299
                val text = (if (ok) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                conn.disconnect()
                if (!ok) throw FilenException("Filen HTTP ${conn.responseCode}: ${text.take(200)}")
                val json = JSONObject(text)
                if (!json.optBoolean("status", false)) {
                    throw FilenException(json.optString("message", "request failed"), json.optString("code").ifEmpty { null })
                }
                return json.optJSONObject("data") ?: JSONObject()
            } catch (e: FilenException) {
                throw e
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("request failed")
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Accept", "application/json")
        }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key).ifEmpty { null }
}
