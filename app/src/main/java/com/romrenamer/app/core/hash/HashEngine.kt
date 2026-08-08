package com.romrenamer.app.core.hash

import android.content.ContentResolver
import android.net.Uri
import com.romrenamer.app.core.storage.RomFile
import java.io.BufferedInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Raised when a file cannot be read for hashing. */
class HashingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The result of hashing one file.
 *
 * When [archiveEntryName] is set, the hashes describe a file *inside* a zip rather than the
 * zip itself — which is what a DAT records, since ROMs are distributed compressed but
 * catalogued uncompressed.
 */
data class HashOutcome(
    val hashes: FileHashes,
    val archiveEntryName: String? = null,
    val archiveEntryCount: Int = 0,
) {
    val isFromArchive: Boolean get() = archiveEntryName != null
}

/**
 * Reads ROM files and computes their hashes off the main thread.
 *
 * Every algorithm the caller asks for is computed in a single pass over the bytes: storage
 * I/O dominates, so hashing CRC32 and SHA-1 together costs barely more than CRC32 alone,
 * while a second pass would double the read. All work happens on [Dispatchers.IO] and
 * checks for cancellation on every chunk, so a scan aborts promptly when the user leaves.
 */
class HashEngine(
    private val resolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bufferBytes: Int = DEFAULT_BUFFER_BYTES,
) {

    /**
     * Hashes [file], transparently looking inside single-entry zips when [inspectArchives]
     * is set.
     *
     * @param onBytes called with the running byte count so the UI can show progress on
     *   multi-gigabyte disc images.
     */
    suspend fun hashRomFile(
        file: RomFile,
        algorithms: Set<HashAlgorithm> = setOf(HashAlgorithm.CRC32),
        inspectArchives: Boolean = true,
        onBytes: ((Long) -> Unit)? = null,
    ): HashOutcome {
        if (inspectArchives && file.extension == "zip") {
            hashZipEntry(file.uri, algorithms, onBytes)?.let { return it }
        }
        return HashOutcome(hashes = hash(file.uri, algorithms, onBytes))
    }

    /** Hashes the raw bytes of [uri] with every algorithm in [algorithms]. */
    suspend fun hash(
        uri: Uri,
        algorithms: Set<HashAlgorithm> = setOf(HashAlgorithm.CRC32),
        onBytes: ((Long) -> Unit)? = null,
    ): FileHashes = withContext(ioDispatcher) {
        if (algorithms.isEmpty()) return@withContext FileHashes()
        openStream(uri).use { stream -> digest(stream, algorithms, onBytes) }
    }

    /**
     * Hashes the payload of a zip archive.
     *
     * Returns `null` when the file is not a readable zip or holds no usable entry, so the
     * caller can fall back to hashing the container itself. Archives with several entries
     * are reported through [HashOutcome.archiveEntryCount]; only the first is hashed,
     * because a rename can only ever name the archive as a whole.
     */
    suspend fun hashZipEntry(
        uri: Uri,
        algorithms: Set<HashAlgorithm> = setOf(HashAlgorithm.CRC32),
        onBytes: ((Long) -> Unit)? = null,
    ): HashOutcome? = withContext(ioDispatcher) {
        if (algorithms.isEmpty()) return@withContext null
        try {
            ZipInputStream(BufferedInputStream(openStream(uri), bufferBytes)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                var payload: ZipEntry? = null
                var entryCount = 0
                var hashes: FileHashes? = null

                while (entry != null) {
                    currentCoroutineContext().ensureActive()
                    if (!entry.isDirectory && !entry.name.isMetadata()) {
                        entryCount++
                        if (payload == null) {
                            payload = entry
                            // Zip stores the CRC32 of the uncompressed payload in its own
                            // header. When that is all we need, trusting it skips
                            // decompressing the entry entirely.
                            val storedCrc = entry.crc
                            hashes = if (algorithms == CRC32_ONLY && storedCrc >= 0) {
                                FileHashes(
                                    crc32 = Hashes.crc32ToHex(storedCrc),
                                    bytesHashed = entry.size.coerceAtLeast(0L),
                                )
                            } else {
                                digest(zip, algorithms, onBytes, closeStream = false)
                            }
                        }
                    }
                    entry = zip.nextEntry
                }

                val name = payload?.name ?: return@withContext null
                HashOutcome(
                    hashes = hashes ?: return@withContext null,
                    archiveEntryName = name,
                    archiveEntryCount = entryCount,
                )
            }
        } catch (e: IOException) {
            // Not a zip, or a corrupt one: fall back to hashing the raw file.
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private suspend fun digest(
        source: InputStream,
        algorithms: Set<HashAlgorithm>,
        onBytes: ((Long) -> Unit)?,
        closeStream: Boolean = true,
    ): FileHashes {
        val crc = if (HashAlgorithm.CRC32 in algorithms) CRC32() else null
        val md5 = if (HashAlgorithm.MD5 in algorithms) MessageDigest.getInstance("MD5") else null
        val sha1 = if (HashAlgorithm.SHA1 in algorithms) MessageDigest.getInstance("SHA-1") else null

        val buffer = ByteArray(bufferBytes)
        var total = 0L
        var sinceReport = 0L

        val input = if (closeStream) BufferedInputStream(source, bufferBytes) else source
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                crc?.update(buffer, 0, read)
                md5?.update(buffer, 0, read)
                sha1?.update(buffer, 0, read)
                total += read
                sinceReport += read
                if (onBytes != null && sinceReport >= PROGRESS_INTERVAL_BYTES) {
                    onBytes(total)
                    sinceReport = 0
                }
            }
        } catch (e: IOException) {
            throw HashingException("Could not read the file: ${e.message}", e)
        } finally {
            if (closeStream) runCatching { input.close() }
        }

        onBytes?.invoke(total)
        return FileHashes(
            crc32 = crc?.let { Hashes.crc32ToHex(it.value) },
            md5 = md5?.let { Hashes.toHex(it.digest()) },
            sha1 = sha1?.let { Hashes.toHex(it.digest()) },
            bytesHashed = total,
        )
    }

    private fun openStream(uri: Uri): InputStream = try {
        resolver.openInputStream(uri)
            ?: throw HashingException("The storage provider returned no data for $uri.")
    } catch (e: FileNotFoundException) {
        throw HashingException("File no longer exists.", e)
    } catch (e: SecurityException) {
        throw HashingException("Permission to read this file was revoked.", e)
    }

    /** Archive bookkeeping that is never the ROM itself. */
    private fun String.isMetadata(): Boolean {
        val leaf = substringAfterLast('/')
        return startsWith("__MACOSX/") || leaf == ".DS_Store" || leaf == "Thumbs.db" || leaf.isEmpty()
    }

    private companion object {
        const val DEFAULT_BUFFER_BYTES = 128 * 1024
        const val PROGRESS_INTERVAL_BYTES = 4L * 1024 * 1024
        val CRC32_ONLY = setOf(HashAlgorithm.CRC32)
    }
}
