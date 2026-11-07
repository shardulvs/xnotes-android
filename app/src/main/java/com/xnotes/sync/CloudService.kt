package com.xnotes.sync

import android.content.Context
import com.xnotes.sync.filen.FilenService
import com.xnotes.sync.nextcloud.NextcloudService

/**
 * A cloud provider notes can sync with. Each one owns its sign-in and session storage and hands the
 * shared [SyncEngine] a [CloudStorage]; the engine, baseline, settings sync and scheduling stay generic.
 */
interface CloudService {
    /** Stable id, stored in preferences and used to name the per-service sync files. */
    val id: String
    val displayName: String

    /** The signed-in account's name, or null when signed out. */
    fun account(context: Context): String?
    fun signOut(context: Context)

    /** The folder the folder picker starts in. Only called while signed in. */
    fun rootFolderId(context: Context): String

    /** Storage for the signed-in account. Throws when signed out. */
    fun storage(context: Context): CloudStorage
}

/** The remote file operations the sync engine needs. Folder and file ids are opaque to the engine. */
interface CloudStorage {
    interface RemoteFile {
        val id: String
        val name: String
        val size: Long
        val lastModified: Long
        /** Content hash in the format [hash] produces, or null when the service does not know it. */
        val hash: String?
    }

    data class RemoteFolder(val id: String, val name: String)

    class Listing(val folders: List<RemoteFolder>, val files: List<RemoteFile>)

    /** The uploaded file's id and the [RemoteFile.lastModified] it now lists with, which a service may round. */
    data class Uploaded(val id: String, val lastModified: Long)

    fun list(folderId: String): Listing
    fun createFolder(parentId: String, name: String): String

    /** Resolves (creating as needed) a chain of subfolders, matched case-insensitively. Returns the deepest id. */
    fun ensureFolderPath(parentId: String, segments: List<String>): String

    /** Uploads [file] as [name]. Streamed, so a note's size is not bound by memory. */
    fun upload(parentId: String, name: String, file: java.io.File, lastModified: Long): Uploaded

    /** Writes [file]'s content into [to], replacing what it held. Streamed, like [upload]. */
    fun download(file: RemoteFile, to: java.io.File)
    fun trashFile(id: String)
    fun trashFolder(id: String)

    /** [file]'s content hash in the format of [RemoteFile.hash]. */
    fun hash(file: java.io.File): String

    /** For small files (the settings), held in memory. */
    fun upload(parentId: String, name: String, bytes: ByteArray, lastModified: Long): Uploaded =
        withTemp { it.writeBytes(bytes); upload(parentId, name, it, lastModified) }

    fun download(file: RemoteFile): ByteArray = withTemp { download(file, it); it.readBytes() }
}

private inline fun <T> withTemp(block: (java.io.File) -> T): T {
    val f = java.io.File.createTempFile("cloud", null)
    try {
        return block(f)
    } finally {
        f.delete()
    }
}

object CloudServices {
    const val NONE = "none"

    val all: List<CloudService> = listOf(FilenService, NextcloudService)

    fun byId(id: String): CloudService? = all.firstOrNull { it.id == id }
}
