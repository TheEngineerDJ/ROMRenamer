package com.romrenamer.app.core.rename

import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.romrenamer.app.core.storage.SafHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One file to rename. */
data class RenameOperation(
    val uri: Uri,
    val parentUri: Uri,
    val currentName: String,
    val targetName: String,
    /** Path shown in the UI, for error messages. */
    val displayPath: String = currentName,
)

/** What happened to a single [RenameOperation]. */
sealed interface RenameOutcome {
    data object Renamed : RenameOutcome
    data object AlreadyCorrect : RenameOutcome
    data class Blocked(val reason: String) : RenameOutcome
    data class Failed(val reason: String) : RenameOutcome
}

data class RenameResult(
    val operation: RenameOperation,
    val outcome: RenameOutcome,
    /** Document URI after a successful rename — providers may issue a new one. */
    val newUri: Uri? = null,
    /** Name the provider actually settled on, which can differ from what we asked for. */
    val finalName: String? = null,
)

data class RenameReport(val results: List<RenameResult>) {
    val renamed: Int get() = results.count { it.outcome is RenameOutcome.Renamed }
    val alreadyCorrect: Int get() = results.count { it.outcome is RenameOutcome.AlreadyCorrect }
    val blocked: Int get() = results.count { it.outcome is RenameOutcome.Blocked }
    val failed: Int get() = results.count { it.outcome is RenameOutcome.Failed }
    val hasProblems: Boolean get() = blocked > 0 || failed > 0
}

/**
 * Applies renames in place through the Storage Access Framework.
 *
 * Renaming is the one destructive thing this app does, so it is deliberately conservative:
 * every batch is validated as a whole before a single file is touched, operations run one
 * at a time, and anything questionable is reported back rather than forced through. Files
 * are never moved, copied, or deleted — only [DocumentFile.renameTo] is used.
 */
class BatchRenamer(
    private val safHandler: SafHandler,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Executes [operations], reporting progress after each file.
     *
     * The whole batch is checked for collisions first: two ROMs that would end up with the
     * same name, or a target name already taken by another file in that folder. Those are
     * marked [RenameOutcome.Blocked] and skipped, while the rest of the batch still runs —
     * one bad entry should not strand the other 499.
     */
    suspend fun rename(
        operations: List<RenameOperation>,
        onProgress: ((done: Int, total: Int, current: RenameOperation) -> Unit)? = null,
    ): RenameReport = withContext(ioDispatcher) {
        val blocked = detectCollisions(operations)
        val ordered = orderOperations(operations)
        val results = ArrayList<RenameResult>(operations.size)

        ordered.forEachIndexed { index, operation ->
            currentCoroutineContext().ensureActive()
            val result = when {
                operation.targetName == operation.currentName ->
                    RenameResult(operation, RenameOutcome.AlreadyCorrect)

                blocked.containsKey(operation.uri) ->
                    RenameResult(operation, RenameOutcome.Blocked(blocked.getValue(operation.uri)))

                else -> execute(operation)
            }
            results += result
            onProgress?.invoke(index + 1, operations.size, operation)
        }

        RenameReport(results)
    }

    private suspend fun execute(operation: RenameOperation): RenameResult {
        val document = safHandler.documentFile(operation.uri)
            ?: return RenameResult(operation, RenameOutcome.Failed("File could not be opened."))

        if (!document.exists()) {
            return RenameResult(operation, RenameOutcome.Failed("File no longer exists."))
        }
        if (!document.canWrite()) {
            return RenameResult(
                operation,
                RenameOutcome.Failed("No write permission — re-grant access to this folder."),
            )
        }

        // FAT and exFAT are case-insensitive, so "GAME.SFC" -> "Game.sfc" can be rejected
        // or silently ignored. Bouncing through a temporary name makes it a real change.
        val caseOnlyChange = operation.currentName != operation.targetName &&
            operation.currentName.equals(operation.targetName, ignoreCase = true)
        if (caseOnlyChange) {
            val temp = temporaryNameFor(operation.targetName)
            if (!renameTo(document, temp)) {
                return RenameResult(operation, RenameOutcome.Failed("Rename was rejected by the storage provider."))
            }
            currentCoroutineContext().ensureActive()
        }

        if (!renameTo(document, operation.targetName)) {
            val hint = if (caseOnlyChange) {
                "Rename failed; the file is now named ${document.name ?: "unknown"}."
            } else {
                "Rename was rejected by the storage provider."
            }
            return RenameResult(operation, RenameOutcome.Failed(hint))
        }

        val finalName = document.name
        return if (finalName != null && finalName != operation.targetName) {
            // Some providers sanitise or de-duplicate the name they were given. Report what
            // actually landed on disk instead of claiming success on our own terms.
            RenameResult(
                operation = operation,
                outcome = RenameOutcome.Blocked("Provider stored it as \"$finalName\"."),
                newUri = document.uri,
                finalName = finalName,
            )
        } else {
            RenameResult(operation, RenameOutcome.Renamed, document.uri, finalName)
        }
    }

    private fun renameTo(document: DocumentFile, name: String): Boolean = try {
        document.renameTo(name)
    } catch (e: UnsupportedOperationException) {
        Log.w(TAG, "Provider does not support renaming ${document.uri}", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Permission denied renaming ${document.uri}", e)
        false
    }

    /**
     * Orders the batch so a file gives up its name before another file claims it.
     *
     * Without this, renaming `A -> B` while `B -> C` is also queued would fail whenever `A`
     * happened to run first. Operations whose target is still held by a queued file are
     * deferred; a genuine cycle (`A -> B`, `B -> A`) cannot be untangled this way and is
     * left in place to fail with a clear message.
     */
    private fun orderOperations(operations: List<RenameOperation>): List<RenameOperation> {
        if (operations.size < 2) return operations

        val remaining = operations.toMutableList()
        val ordered = ArrayList<RenameOperation>(operations.size)

        while (remaining.isNotEmpty()) {
            val heldNames = remaining
                .mapTo(mutableSetOf()) { it.parentUri to it.currentName.lowercase() }
            val ready = remaining.filter { operation ->
                val target = operation.parentUri to operation.targetName.lowercase()
                val ownName = operation.parentUri to operation.currentName.lowercase()
                target == ownName || target !in heldNames
            }
            if (ready.isEmpty()) {
                ordered += remaining
                break
            }
            ordered += ready
            remaining -= ready.toSet()
        }
        return ordered
    }

    /**
     * Returns a reason string for every operation that must not run.
     *
     * Two sources of collision are checked: other operations in the same batch, and files
     * already sitting in the target directory that the batch will not move out of the way.
     */
    private fun detectCollisions(operations: List<RenameOperation>): Map<Uri, String> {
        val blocked = mutableMapOf<Uri, String>()

        // Within the batch: group by folder + case-insensitive target name.
        operations
            .groupBy { it.parentUri to it.targetName.lowercase() }
            .filterValues { it.size > 1 }
            .forEach { (_, clashing) ->
                clashing.forEach { operation ->
                    val others = clashing.filter { it.uri != operation.uri }.joinToString(", ") { it.currentName }
                    blocked[operation.uri] =
                        "Would collide with $others — these files claim the same official name."
                }
            }

        // Against files already on disk, one directory listing per folder.
        val existingByParent = mutableMapOf<Uri, Map<String, Uri>>()
        operations.asSequence()
            .filter { it.uri !in blocked && it.targetName != it.currentName }
            .forEach { operation ->
                val existing = existingByParent.getOrPut(operation.parentUri) {
                    listNames(operation.parentUri)
                }
                val occupant = existing[operation.targetName.lowercase()]
                if (occupant != null && occupant != operation.uri) {
                    // Harmless if the occupant is itself being renamed away in this batch.
                    val occupantMovesAway = operations.any {
                        it.uri == occupant && it.targetName != it.currentName && it.uri !in blocked
                    }
                    if (!occupantMovesAway) {
                        blocked[operation.uri] =
                            "\"${operation.targetName}\" already exists in this folder."
                    }
                }
            }

        return blocked
    }

    private fun listNames(parentUri: Uri): Map<String, Uri> {
        val parent = safHandler.documentFile(parentUri) ?: return emptyMap()
        return try {
            parent.listFiles().mapNotNull { child ->
                child.name?.lowercase()?.let { it to child.uri }
            }.toMap()
        } catch (e: Exception) {
            Log.w(TAG, "Could not list $parentUri for collision checks", e)
            emptyMap()
        }
    }

    private fun temporaryNameFor(target: String): String =
        ".romrenamer-${System.nanoTime().toString(16)}-$target".take(200)

    private companion object {
        const val TAG = "BatchRenamer"
    }
}
