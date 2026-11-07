package com.xnotes.sync.nextcloud

import android.content.Context
import com.xnotes.sync.CloudService
import com.xnotes.sync.CloudStorage
import com.xnotes.sync.SecureBlob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/** Nextcloud over WebDAV, signed in with an app password kept in a [SecureBlob]. */
object NextcloudService : CloudService {
    override val id = "nextcloud"
    override val displayName = "Nextcloud"

    private class Login(val server: String, val user: String, val userId: String, val appPassword: String) {
        fun dav() = NextcloudDav(server, user, appPassword)
    }

    private fun blob(context: Context) = SecureBlob(context, "nextcloud_secure", "n_notes_nextcloud_key")

    private fun login(context: Context): Login? = runCatching {
        val j = JSONObject(blob(context).load() ?: return null)
        Login(j.getString("server"), j.getString("user"), j.getString("userId"), j.getString("appPassword"))
    }.getOrNull()

    override fun account(context: Context): String? = login(context)?.let { "${it.user} on ${URL(it.server).host}" }

    suspend fun signIn(context: Context, server: String, user: String, appPassword: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = server.trim().trimEnd('/').let { if ("://" in it) it else "https://$it" }
                val userId = NextcloudDav(base, user.trim(), appPassword.trim()).userId()
                val json = JSONObject().put("server", base).put("user", user.trim()).put("userId", userId).put("appPassword", appPassword.trim())
                blob(context).save(json.toString())
            }
        }

    override fun signOut(context: Context) = blob(context).clear()

    override fun rootFolderId(context: Context): String = login(context)?.let { it.dav().rootHref(it.userId) }.orEmpty()

    override fun storage(context: Context): CloudStorage =
        NextcloudStorage(login(context)?.dav() ?: throw IllegalStateException("Not signed in to Nextcloud"))
}

/** Folder ids are hrefs; a file id is its href plus the etag it was listed with, so a delete only hits that version. */
private class NextcloudStorage(private val dav: NextcloudDav) : CloudStorage {
    class File(val entry: NextcloudDav.Entry) : CloudStorage.RemoteFile {
        override val id get() = "${entry.href}#${entry.etag}"
        override val name get() = nameOf(entry.href)
        override val size get() = entry.size
        override val lastModified get() = entry.lastModified
        override val hash: String? get() = null
    }

    /** Overwriting keeps the path, so the engine's "trash the old version" after an upload must not delete the new one. */
    private val uploaded = HashSet<String>()

    override fun list(folderId: String): CloudStorage.Listing {
        val self = NextcloudDav.decode(folderId).trimEnd('/')
        val entries = dav.propfind(folderId, 1).filter { NextcloudDav.decode(it.href).trimEnd('/') != self }
        return CloudStorage.Listing(
            entries.filter { it.isFolder }.map { CloudStorage.RemoteFolder(it.href, nameOf(it.href)) },
            entries.filter { !it.isFolder }.map { File(it) },
        )
    }

    override fun createFolder(parentId: String, name: String): String = (child(parentId, name) + "/").also { dav.mkcol(it) }

    override fun ensureFolderPath(parentId: String, segments: List<String>): String = segments.fold(parentId) { current, seg ->
        list(current).folders.firstOrNull { it.name.equals(seg, ignoreCase = true) }?.id ?: createFolder(current, seg)
    }

    override fun upload(parentId: String, name: String, file: java.io.File, lastModified: Long): CloudStorage.Uploaded {
        val href = child(parentId, name)
        dav.put(href, file, lastModified)
        uploaded.add(NextcloudDav.decode(href))
        val e = dav.propfind(href, 0).first()
        return CloudStorage.Uploaded("${e.href}#${e.etag}", e.lastModified)
    }

    override fun download(file: CloudStorage.RemoteFile, to: java.io.File) = dav.get((file as File).entry.href, to)

    override fun trashFile(id: String) {
        val href = id.substringBeforeLast('#')
        if (NextcloudDav.decode(href) in uploaded) return
        dav.delete(href, id.substringAfterLast('#').ifEmpty { null })
    }

    override fun trashFolder(id: String) = dav.delete(id, null)

    // Never called: files list without a hash, so the engine compares bytes instead.
    override fun hash(file: java.io.File): String = throw UnsupportedOperationException("Nextcloud lists no content hashes")

    private fun child(parentId: String, name: String) = parentId.trimEnd('/') + "/" + NextcloudDav.encode(name)
}

private fun nameOf(href: String) = NextcloudDav.decode(href.trimEnd('/').substringAfterLast('/'))
