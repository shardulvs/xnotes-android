package com.xnotes.sync.filen

import android.content.Context
import com.xnotes.sync.CloudService
import com.xnotes.sync.CloudStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Filen end-to-end encrypted storage. The session lives encrypted in [FilenSecureStore]. */
object FilenService : CloudService {
    override val id = "filen"
    override val displayName = "Filen"

    fun session(context: Context): FilenSession? = FilenSecureStore(context).load()

    override fun account(context: Context): String? = session(context)?.email

    suspend fun signIn(context: Context, email: String, password: String, twoFactorCode: String): Result<FilenSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                val session = FilenClient.login(FilenApi(), email.trim(), password, twoFactorCode)
                FilenSecureStore(context).save(session)
                session
            }
        }

    override fun signOut(context: Context) = FilenSecureStore(context).clear()

    override fun rootFolderId(context: Context): String = session(context)?.baseFolderUuid.orEmpty()

    override fun storage(context: Context): CloudStorage {
        val session = session(context) ?: throw IllegalStateException("Not signed in to Filen")
        return FilenStorage(FilenClient(FilenApi(session.apiKey), session))
    }
}

private class FilenStorage(private val client: FilenClient) : CloudStorage {
    class File(val file: FilenApi.RemoteFile, val meta: FilenClient.FileMetadata) : CloudStorage.RemoteFile {
        override val id get() = file.uuid
        override val name get() = meta.name
        override val size get() = meta.size
        override val lastModified get() = meta.lastModified
        override val hash get() = meta.hash
    }

    override fun list(folderId: String): CloudStorage.Listing {
        val content = client.listDecrypted(folderId)
        return CloudStorage.Listing(
            content.folders.map { (name, folder) -> CloudStorage.RemoteFolder(folder.uuid, name) },
            content.files.map { (_, file, meta) -> File(file, meta) },
        )
    }

    override fun createFolder(parentId: String, name: String) = client.createFolder(parentId, name)
    override fun ensureFolderPath(parentId: String, segments: List<String>) = client.ensureFolderPath(parentId, segments)
    override fun upload(parentId: String, name: String, file: java.io.File, lastModified: Long) =
        client.uploadFile(parentId, name, file, lastModified)
    override fun download(file: CloudStorage.RemoteFile, to: java.io.File) = (file as File).let { client.downloadFile(it.file, it.meta, to) }
    override fun trashFile(id: String) = client.trashFile(id)
    override fun trashFolder(id: String) = client.trashFolder(id)
    override fun hash(file: java.io.File) = FilenCrypto.sha512Hex(file)
}
