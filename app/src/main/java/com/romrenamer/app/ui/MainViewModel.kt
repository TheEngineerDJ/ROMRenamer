package com.romrenamer.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.romrenamer.app.core.dat.DatIndex
import com.romrenamer.app.core.dat.DatIndexBuilder
import com.romrenamer.app.core.dat.DatParseException
import com.romrenamer.app.core.dat.DatParser
import com.romrenamer.app.core.match.MatchStatus
import com.romrenamer.app.core.match.ScanSummary
import com.romrenamer.app.core.match.ScannedRom
import com.romrenamer.app.core.rename.BatchRenamer
import com.romrenamer.app.core.rename.NamingPolicy
import com.romrenamer.app.core.rename.RenameOperation
import com.romrenamer.app.core.rename.RenameOutcome
import com.romrenamer.app.core.rename.RenameReport
import com.romrenamer.app.core.scan.RomScanner
import com.romrenamer.app.core.scan.ScanEvent
import com.romrenamer.app.core.scan.ScanOptions
import com.romrenamer.app.core.hash.HashEngine
import com.romrenamer.app.core.storage.SafHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the app's state machine: hold the folder grant, load a DAT, run a scan, apply
 * renames.
 *
 * All long-running work lives in [viewModelScope] and is cancellable, so rotating the
 * device keeps a scan alive while leaving the screen kills it cleanly.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val safHandler = SafHandler(application)
    private val hashEngine = HashEngine(application.contentResolver)
    private val scanner = RomScanner(safHandler, hashEngine)
    private val renamer = BatchRenamer(safHandler)
    private val datParser = DatParser()
    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var datIndex: DatIndex = DatIndex.EMPTY
    private var treeUri: Uri? = null
    private var datUri: Uri? = null
    private var scanJob: Job? = null

    /** Mutable working copy of the row list, so per-file updates stay O(1). */
    private val workingRoms = mutableListOf<ScannedRom>()
    private val rowIndex = HashMap<String, Int>()
    private var lastEmitAt = 0L

    init {
        restoreSavedSelections()
    }

    // ---------------------------------------------------------------- folder & DAT

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        if (!safHandler.persistTreePermission(uri)) {
            _state.update { it.copy(message = "Android would not persist access to that folder.") }
        }
        treeUri = uri
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        clearResults()
        _state.update {
            it.copy(folderName = safHandler.treeDisplayName(uri), message = null)
        }
    }

    fun onDatPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch { loadDat(uri, persist = true) }
    }

    private suspend fun loadDat(uri: Uri, persist: Boolean) {
        val application = getApplication<Application>()
        val document = runCatching { DocumentFile.fromSingleUri(application, uri) }.getOrNull()
        val fileName = document?.name ?: uri.lastPathSegment ?: "DAT file"
        val totalBytes = document?.length()?.takeIf { it > 0 }

        _state.update {
            it.copy(phase = Phase.LoadingDat(fileName, 0, null), message = null)
        }

        try {
            if (persist) {
                runCatching {
                    application.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }

            val builder = DatIndexBuilder()
            withContext(Dispatchers.IO) {
                val stream = application.contentResolver.openInputStream(uri)
                    ?: throw DatParseException("Could not open the selected DAT file.")
                stream.use { input ->
                    datParser.parse(input, builder, totalBytes) { progress ->
                        _state.update {
                            it.copy(
                                phase = Phase.LoadingDat(
                                    fileName = fileName,
                                    gamesParsed = progress.gamesParsed,
                                    fraction = progress.fraction,
                                ),
                            )
                        }
                    }
                }
            }

            datIndex = builder.build()
            datUri = uri
            if (persist) prefs.edit().putString(KEY_DAT_URI, uri.toString()).apply()

            _state.update {
                it.copy(
                    phase = Phase.Idle,
                    datName = datIndex.displayName,
                    datGameCount = datIndex.gameCount,
                    datRomCount = datIndex.romCount,
                    message = "Loaded ${datIndex.gameCount} games from $fileName.",
                )
            }
            // Row targets were computed against the previous DAT, so they no longer apply.
            if (workingRoms.isNotEmpty()) clearResults()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "DAT load failed", e)
            _state.update {
                it.copy(phase = Phase.Idle, message = e.message ?: "Could not read that DAT file.")
            }
        }
    }

    // ---------------------------------------------------------------- scanning

    fun startScan() {
        val tree = treeUri ?: run {
            _state.update { it.copy(message = "Choose a ROM folder first.") }
            return
        }
        if (datIndex.isEmpty) {
            _state.update { it.copy(message = "Load a No-Intro or Redump DAT file first.") }
            return
        }
        if (scanJob?.isActive == true) return

        clearResults()
        val options = ScanOptions(
            namingPolicy = _state.value.namingPolicy,
            inspectArchives = _state.value.inspectArchives,
        )

        scanJob = viewModelScope.launch {
            _state.update { it.copy(phase = Phase.Listing(0, ""), scanCompleted = false) }
            try {
                scanner.scan(tree, datIndex, options).collect { event -> handleScanEvent(event) }
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = Phase.Idle, message = "Scan cancelled.") }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Scan failed", e)
                _state.update {
                    it.copy(phase = Phase.Idle, message = e.message ?: "The scan failed.")
                }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
    }

    private fun handleScanEvent(event: ScanEvent) {
        when (event) {
            is ScanEvent.Listing ->
                _state.update { it.copy(phase = Phase.Listing(event.filesFound, event.currentPath)) }

            is ScanEvent.Discovered -> {
                workingRoms.clear()
                rowIndex.clear()
                workingRoms.addAll(event.roms)
                event.roms.forEachIndexed { position, rom -> rowIndex[rom.id] = position }
                _state.update {
                    it.copy(
                        phase = Phase.Scanning(0, event.roms.size),
                        roms = workingRoms.toList(),
                        summary = ScanSummary.of(workingRoms),
                    )
                }
            }

            is ScanEvent.RomUpdated -> {
                val position = rowIndex[event.rom.id] ?: return
                workingRoms[position] = event.rom
                // Publishing every row would repaint the list thousands of times a second;
                // the throttle keeps the UI responsive while still feeling live.
                val now = System.currentTimeMillis()
                val isLast = event.completed == event.total
                if (isLast || now - lastEmitAt >= EMIT_INTERVAL_MS) {
                    lastEmitAt = now
                    _state.update {
                        it.copy(
                            phase = Phase.Scanning(event.completed, event.total),
                            roms = workingRoms.toList(),
                            summary = ScanSummary.of(workingRoms),
                        )
                    }
                } else {
                    _state.update { it.copy(phase = Phase.Scanning(event.completed, event.total)) }
                }
            }

            is ScanEvent.FileProgress -> Unit // Reserved for a future per-file progress bar.

            ScanEvent.Finished -> {
                _state.update {
                    it.copy(
                        phase = Phase.Idle,
                        roms = workingRoms.toList(),
                        summary = ScanSummary.of(workingRoms),
                        scanCompleted = true,
                        message = scanSummaryMessage(),
                    )
                }
                scanJob = null
            }

            is ScanEvent.Failed ->
                _state.update { it.copy(phase = Phase.Idle, message = event.message) }
        }
    }

    private fun scanSummaryMessage(): String {
        val summary = ScanSummary.of(workingRoms)
        return when {
            summary.total == 0 -> "No ROM files found in that folder."
            summary.needsRename == 0 && summary.matched > 0 ->
                "All ${summary.matched} matched files are already correctly named."
            summary.needsRename > 0 -> "${summary.needsRename} files can be renamed."
            else -> "No matches found. Is this the right DAT for these ROMs?"
        }
    }

    // ---------------------------------------------------------------- selection

    fun toggleSelection(romId: String) {
        val position = rowIndex[romId] ?: return
        val rom = workingRoms[position]
        if (!rom.needsRename) return
        workingRoms[position] = rom.copy(selected = !rom.selected)
        _state.update { it.copy(roms = workingRoms.toList()) }
    }

    fun setAllSelected(selected: Boolean) {
        var changed = false
        workingRoms.forEachIndexed { position, rom ->
            if (rom.needsRename && rom.selected != selected) {
                workingRoms[position] = rom.copy(selected = selected)
                changed = true
            }
        }
        if (changed) _state.update { it.copy(roms = workingRoms.toList()) }
    }

    fun setRowFilter(filter: RowFilter) = _state.update { it.copy(rowFilter = filter) }

    fun setNamingPolicy(policy: NamingPolicy) {
        if (policy == _state.value.namingPolicy) return
        _state.update { it.copy(namingPolicy = policy) }
        prefs.edit().putString(KEY_NAMING_POLICY, policy.name).apply()
        // Target names are derived from the policy, so previous results are now stale.
        if (workingRoms.isNotEmpty()) {
            clearResults()
            _state.update { it.copy(message = "Naming changed — run the scan again.") }
        }
    }

    fun setInspectArchives(enabled: Boolean) {
        if (enabled == _state.value.inspectArchives) return
        _state.update { it.copy(inspectArchives = enabled) }
        prefs.edit().putBoolean(KEY_INSPECT_ARCHIVES, enabled).apply()
        if (workingRoms.isNotEmpty()) {
            clearResults()
            _state.update { it.copy(message = "Archive handling changed — run the scan again.") }
        }
    }

    // ---------------------------------------------------------------- renaming

    fun showDialog(dialog: UiDialog) = _state.update { it.copy(dialog = dialog) }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun requestRename() {
        if (!_state.value.canRename) return
        _state.update { it.copy(dialog = UiDialog.ConfirmRename) }
    }

    /** Applies the ticked renames. Only called from the confirmation dialog. */
    fun confirmRename() {
        val operations = _state.value.selectedForRename.map { rom ->
            RenameOperation(
                uri = rom.file.uri,
                parentUri = rom.file.parentUri,
                currentName = rom.file.name,
                targetName = requireNotNull(rom.targetName),
                displayPath = rom.file.displayPath,
            )
        }
        if (operations.isEmpty()) {
            _state.update { it.copy(dialog = null) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(dialog = null, phase = Phase.Renaming(0, operations.size)) }
            try {
                val report = renamer.rename(operations) { done, total, _ ->
                    _state.update { it.copy(phase = Phase.Renaming(done, total)) }
                }
                applyRenameReport(report)
                _state.update {
                    it.copy(
                        phase = Phase.Idle,
                        roms = workingRoms.toList(),
                        summary = ScanSummary.of(workingRoms),
                        dialog = UiDialog.Report(report),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Batch rename failed", e)
                _state.update {
                    it.copy(phase = Phase.Idle, message = e.message ?: "The rename failed.")
                }
            }
        }
    }

    /** Folds successful renames back into the row list so the UI reflects what is on disk. */
    private fun applyRenameReport(report: RenameReport) {
        report.results.forEach { result ->
            val romId = result.operation.uri.toString()
            val position = rowIndex[romId] ?: return@forEach
            val rom = workingRoms[position]
            when (val outcome = result.outcome) {
                is RenameOutcome.Renamed -> {
                    val newName = result.finalName ?: result.operation.targetName
                    workingRoms[position] = rom.copy(
                        file = rom.file.copy(name = newName, uri = result.newUri ?: rom.file.uri),
                        selected = false,
                    )
                    // The document URI can change, so the lookup key has to follow it.
                    if (result.newUri != null && result.newUri.toString() != romId) {
                        rowIndex.remove(romId)
                        rowIndex[result.newUri.toString()] = position
                    }
                }

                is RenameOutcome.Blocked ->
                    workingRoms[position] = rom.copy(
                        status = MatchStatus.Failed(outcome.reason),
                        selected = false,
                    )

                is RenameOutcome.Failed ->
                    workingRoms[position] = rom.copy(
                        status = MatchStatus.Failed(outcome.reason),
                        selected = false,
                    )

                RenameOutcome.AlreadyCorrect ->
                    workingRoms[position] = rom.copy(selected = false)
            }
        }
    }

    // ---------------------------------------------------------------- housekeeping

    private fun clearResults() {
        workingRoms.clear()
        rowIndex.clear()
        _state.update {
            it.copy(roms = emptyList(), summary = ScanSummary(), scanCompleted = false)
        }
    }

    /** Reuses last session's folder and DAT when their permission grants survived. */
    private fun restoreSavedSelections() {
        prefs.getString(KEY_NAMING_POLICY, null)
            ?.let { name -> NamingPolicy.entries.firstOrNull { it.name == name } }
            ?.let { policy -> _state.update { it.copy(namingPolicy = policy) } }
        _state.update { it.copy(inspectArchives = prefs.getBoolean(KEY_INSPECT_ARCHIVES, true)) }

        prefs.getString(KEY_TREE_URI, null)?.let { saved ->
            val uri = Uri.parse(saved)
            if (safHandler.hasPersistedPermission(uri)) {
                treeUri = uri
                _state.update { it.copy(folderName = safHandler.treeDisplayName(uri)) }
            } else {
                prefs.edit().remove(KEY_TREE_URI).apply()
            }
        }

        prefs.getString(KEY_DAT_URI, null)?.let { saved ->
            val uri = Uri.parse(saved)
            val stillGranted = getApplication<Application>().contentResolver
                .persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            if (stillGranted) {
                viewModelScope.launch { loadDat(uri, persist = false) }
            } else {
                prefs.edit().remove(KEY_DAT_URI).apply()
            }
        }
    }

    private companion object {
        const val TAG = "MainViewModel"
        const val PREFS = "rom_renamer"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_DAT_URI = "dat_uri"
        const val KEY_NAMING_POLICY = "naming_policy"
        const val KEY_INSPECT_ARCHIVES = "inspect_archives"
        const val EMIT_INTERVAL_MS = 120L
    }
}
