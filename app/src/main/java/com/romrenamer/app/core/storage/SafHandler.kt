package com.romrenamer.app.core.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Everything the app does with the Storage Access Framework: holding on to the directory
 * grant the user gave us, and walking that directory tree.
 *
 * The app declares no storage permissions. The user picks a folder with
 * `ACTION_OPEN_DOCUMENT_TREE`, we persist that grant, and every later read and rename goes
 * through the returned tree URI.
 */
class SafHandler(context: Context) {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver

    /**
     * Persists read/write access to a tree the user just granted, so the folder is still
     * usable after a reboot without re-prompting.
     *
     * @return `true` when the grant was taken; `false` if the provider refused it, in which
     *   case the URI is still usable for this process but must not be stored.
     */
    fun persistTreePermission(treeUri: Uri): Boolean =
        persist(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    /**
     * Persists read-only access, which is all a DAT file or a folder of DAT files needs.
     *
     * Taking only the read flag keeps the app's standing access to the smallest thing that
     * works — the DAT selection is never written to.
     */
    fun persistReadPermission(uri: Uri): Boolean =
        persist(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun persist(uri: Uri, modeFlags: Int): Boolean = try {
        resolver.takePersistableUriPermission(uri, modeFlags)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "Could not persist permission for $uri", e)
        false
    }

    fun releaseTreePermission(treeUri: Uri) {
        try {
            resolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not release permission for $treeUri", e)
        }
    }

    /** `true` if a previously saved tree URI is still readable and writable. */
    fun hasPersistedPermission(treeUri: Uri): Boolean =
        resolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }

    /** `true` if a previously saved URI can still be read. */
    fun hasPersistedReadPermission(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    fun persistedTrees(): List<Uri> =
        resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }

    /** Folder name to show in the UI, falling back to the raw document id. */
    fun treeDisplayName(treeUri: Uri): String =
        runCatching { DocumentFile.fromTreeUri(appContext, treeUri)?.name }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: treeUri.lastPathSegment
            ?: treeUri.toString()

    /**
     * Resolves a document URI to a writable [DocumentFile].
     *
     * Always built with [DocumentFile.fromTreeUri] — a `SingleDocumentFile` (what
     * `fromSingleUri` returns) throws on `renameTo`, so tree-backed instances are the only
     * ones the renamer can use.
     */
    fun documentFile(documentUri: Uri): DocumentFile? =
        runCatching { DocumentFile.fromTreeUri(appContext, documentUri) }.getOrNull()

    /**
     * Name and size of a standalone document, as returned by `ACTION_OPEN_DOCUMENT`.
     *
     * Files picked individually are not part of a granted tree, so they have to be read
     * through [DocumentFile.fromSingleUri]; a single document supports the metadata queries
     * even though it cannot be renamed.
     */
    fun documentInfo(documentUri: Uri): DocumentInfo? {
        val document = runCatching { DocumentFile.fromSingleUri(appContext, documentUri) }
            .getOrNull() ?: return null
        val name = runCatching { document.name }.getOrNull()
            ?: documentUri.lastPathSegment?.substringAfterLast('/')
            ?: return null
        val size = runCatching { document.length() }.getOrNull()?.takeIf { it > 0 }
        return DocumentInfo(uri = documentUri, name = name, sizeBytes = size)
    }

    /**
     * Recursively lists every file under [treeUri] that [filter] accepts.
     *
     * Directory contents are read through a single [DocumentsContract] child-documents query
     * per folder rather than [DocumentFile.listFiles]: `DocumentFile` re-queries the provider
     * for each of `name`, `length` and `isDirectory`, which turns a few thousand ROMs into
     * tens of thousands of IPC round trips. The same data arrives here in one cursor per
     * directory. [DocumentFile] is still used wherever a mutation is needed — see
     * [documentFile] and the batch renamer.
     *
     * Cancelling the calling coroutine stops the walk between directories.
     */
    suspend fun listFilesRecursively(
        treeUri: Uri,
        filter: RomFileFilter = RomFileFilter(),
        onProgress: ((filesFound: Int, currentPath: String) -> Unit)? = null,
    ): List<RomFile> = withContext(ioDispatcher) {
        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: IllegalArgumentException) {
            throw StorageAccessException("That folder grant is no longer valid. Pick the folder again.", e)
        }

        val results = mutableListOf<RomFile>()
        val queue = ArrayDeque<PendingDir>()
        queue += PendingDir(documentId = rootId, relativePath = "", depth = 0)
        val visited = HashSet<String>()

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = queue.removeFirst()
            // Providers can expose the same document twice (shortcuts, bind mounts); without
            // this guard a cycle would make the walk run forever.
            if (!visited.add(dir.documentId)) continue

            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dir.documentId)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dir.documentId)

            queryChildren(childrenUri) { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: continue
                    val mimeType = cursor.getString(mimeCol)

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (dir.depth >= filter.maxDepth) continue
                        if (!filter.includeHidden && name.startsWith(".")) continue
                        queue += PendingDir(
                            documentId = documentId,
                            relativePath = if (dir.relativePath.isEmpty()) name else "${dir.relativePath}/$name",
                            depth = dir.depth + 1,
                        )
                        continue
                    }

                    val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                    if (!filter.accepts(name, size)) continue

                    results += RomFile(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                        parentUri = parentUri,
                        name = name,
                        size = size,
                        mimeType = mimeType,
                        relativePath = dir.relativePath,
                        lastModified = if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) {
                            cursor.getLong(modifiedCol)
                        } else {
                            0L
                        },
                    )
                }
            }

            onProgress?.invoke(results.size, dir.relativePath)
        }

        results.sortedBy { it.displayPath.lowercase() }
    }

    private fun queryChildren(childrenUri: Uri, body: (Cursor) -> Unit) {
        val cursor = try {
            resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
        } catch (e: SecurityException) {
            throw StorageAccessException("Access to that folder was revoked. Pick the folder again.", e)
        } catch (e: Exception) {
            // A single unreadable subdirectory should not abort the whole scan.
            Log.w(TAG, "Could not list $childrenUri", e)
            null
        } ?: return

        cursor.use(body)
    }

    private data class PendingDir(val documentId: String, val relativePath: String, val depth: Int)

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private companion object {
        const val TAG = "SafHandler"

        val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/** Metadata for a standalone document the user picked. */
data class DocumentInfo(val uri: Uri, val name: String, val sizeBytes: Long?)

/** Raised when a directory grant is missing, revoked, or otherwise unusable. */
class StorageAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)
