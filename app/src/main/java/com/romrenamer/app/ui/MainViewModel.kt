package com.romrenamer.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.romrenamer.app.core.dat.DatIndex
import com.romrenamer.app.core.dat.DatLoadResult
import com.romrenamer.app.core.dat.DatLoader
import com.romrenamer.app.core.dat.DatSource
import com.romrenamer.app.core.match.FuzzyTitleMatcher
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
import com.romrenamer.app.core.storage.RomFileFilter
import com.romrenamer.app.core.storage.SafHandler
import java.io.FileNotFoundException
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
 * Owns the app's state machine: hold the folder grant, load the DAT database, run a scan,
 * apply renames.
 *
 * All long-running work lives in [viewModelScope] and is cancellable, so rotating the
 * device keeps a scan alive while leaving the screen kills it cleanly.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val safHandler = SafHandler(application)
    private val hashEngine = HashEngine(application.contentResolver)
    private val scanner = RomScanner(safHandler, hashEngine)
    private val renamer = BatchRenamer(safHandler)
    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val datLoader = DatLoader(open = ::openDatSource)

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var datIndex: DatIndex = DatIndex.EMPTY
    private var treeUri: Uri? = null
    private var datJob: Job? = null
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

    /** Individually picked DAT files, from `ACTION_OPEN_DOCUMENT` with multi-select. */
    fun onDatFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        // A provider that refuses a persistable grant still allows reads for this process,
        // so a failure here only costs the selection on next launch.
        uris.forEach { safHandler.persistReadPermission(it) }

        datJob?.cancel()
        datJob = viewModelScope.launch {
            val sources = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    safHandler.documentInfo(uri)?.let { info ->
                        DatSource(uri = info.uri, name = info.name, sizeBytes = info.sizeBytes)
                    }
                }
            }
            if (sources.isEmpty()) {
                _state.update { it.copy(message = "Those files could not be read.") }
                return@launch
            }
            rememberSelection(fileUris = sources.map { it.uri })
            loadDats(sources, DatSelection.Files(sources.size))
        }
    }

    /**
     * A whole folder of DAT files, from `ACTION_OPEN_DOCUMENT_TREE`.
     *
     * Every `.dat` and `.xml` under the folder is parsed and merged, however deeply nested,
     * so a user who keeps a No-Intro download folder can point at it once and match a mixed
     * ROM library without picking the right file per system.
     */
    fun onDatFolderPicked(uri: Uri?) {
        if (uri == null) return
        safHandler.persistReadPermission(uri)
        datJob?.cancel()
        datJob = viewModelScope.launch {
            rememberSelection(treeUri = uri)
            loadDatFolder(uri)
        }
    }

    private suspend fun loadDatFolder(uri: Uri) {
        val folderName = safHandler.treeDisplayName(uri)
        _state.update {
            it.copy(phase = Phase.LoadingDats(0, 0, folderName, 0, null), message = null)
        }

        val files = try {
            safHandler.listFilesRecursively(uri, RomFileFilter.DAT_FILES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not list DAT folder", e)
            _state.update {
                it.copy(phase = Phase.Idle, message = e.message ?: "Could not read that folder.")
            }
            return
        }

        if (files.isEmpty()) {
            _state.update {
                it.copy(
                    phase = Phase.Idle,
                    message = "No .dat or .xml files found in \"$folderName\".",
                )
            }
            return
        }

        val sources = files.map { file ->
            DatSource(
                uri = file.uri,
                name = file.name,
                sizeBytes = file.size.takeIf { size -> size > 0 },
                relativePath = file.relativePath,
            )
        }
        loadDats(sources, DatSelection.Folder(folderName))
    }

    /** Parses every source into one merged index and publishes the result. */
    private suspend fun loadDats(sources: List<DatSource>, selection: DatSelection) {
        _state.update {
            it.copy(
                phase = Phase.LoadingDats(1, sources.size, sources.first().name, 0, null),
                message = null,
            )
        }

        val result: DatLoadResult = try {
            withContext(Dispatchers.IO) {
                datLoader.load(sources) { progress ->
                    _state.update {
                        it.copy(
                            phase = Phase.LoadingDats(
                                fileNumber = progress.fileNumber,
                                fileCount = progress.fileCount,
                                fileName = progress.fileName,
                                gamesParsed = progress.gamesParsed,
                                fraction = progress.overallFraction,
                            ),
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(phase = Phase.Idle) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "DAT load failed", e)
            _state.update {
                it.copy(phase = Phase.Idle, message = e.message ?: "Could not read those DAT files.")
            }
            return
        }

        datIndex = result.index
        val library = DatLibrary(
            selection = selection,
            outcomes = result.outcomes,
            gameCount = datIndex.gameCount,
            romCount = datIndex.romCount,
            truncated = result.truncated,
        )

        _state.update {
            it.copy(phase = Phase.Idle, datLibrary = library, message = loadMessage(library))
        }
        // Row targets were computed against the previous database, so they no longer apply.
        if (workingRoms.isNotEmpty()) clearResults()
    }

    private fun loadMessage(library: DatLibrary): String = when {
        library.loadedCount == 0 ->
            "No readable DAT files in that selection."
        library.truncated ->
            "Loaded ${library.gameCount} games, then stopped at the size limit. " +
                "Select fewer DATs for a complete database."
        library.rejectedCount > 0 ->
            "Merged ${library.loadedCount} DATs (${library.gameCount} games); " +
                "${library.rejectedCount} files were not DATs."
        else ->
            "Merged ${library.loadedCount} DATs — ${library.gameCount} games."
    }

    /** Opens a DAT for the loader, which is deliberately unaware of `ContentResolver`. */
    private fun openDatSource(source: DatSource) =
        getApplication<Application>().contentResolver.openInputStream(source.uri)
            ?: throw FileNotFoundException("The storage provider returned no data.")

    // ---------------------------------------------------------------- scanning

    fun startScan() {
        val tree = treeUri ?: run {
            _state.update { it.copy(message = "Choose a ROM folder first.") }
            return
        }
        if (datIndex.isEmpty) {
            _state.update { it.copy(message = "Load one or more DAT files first.") }
            return
        }
        if (scanJob?.isActive == true) return

        clearResults()
        val options = ScanOptions(
            namingPolicy = _state.value.namingPolicy,
            inspectArchives = _state.value.inspectArchives,
            fuzzyMatching = _state.value.fuzzyMatching,
            fuzzyThreshold = _state.value.fuzzyThreshold,
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
        invalidateResults("Naming changed — run the scan again.")
    }

    fun setInspectArchives(enabled: Boolean) {
        if (enabled == _state.value.inspectArchives) return
        _state.update { it.copy(inspectArchives = enabled) }
        prefs.edit().putBoolean(KEY_INSPECT_ARCHIVES, enabled).apply()
        invalidateResults("Archive handling changed — run the scan again.")
    }

    fun setFuzzyMatching(enabled: Boolean) {
        if (enabled == _state.value.fuzzyMatching) return
        _state.update { it.copy(fuzzyMatching = enabled) }
        prefs.edit().putBoolean(KEY_FUZZY_MATCHING, enabled).apply()
        invalidateResults("Text matching changed — run the scan again.")
    }

    fun setFuzzyThreshold(threshold: Float) {
        if (threshold == _state.value.fuzzyThreshold) return
        _state.update { it.copy(fuzzyThreshold = threshold) }
        prefs.edit().putFloat(KEY_FUZZY_THRESHOLD, threshold).apply()
        invalidateResults("Match confidence changed — run the scan again.")
    }

    /** Drops stale rows after a setting that would change what a scan produces. */
    private fun invalidateResults(reason: String) {
        if (workingRoms.isEmpty()) return
        clearResults()
        _state.update { it.copy(message = reason) }
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

    /**
     * Records the DAT selection so the next launch can rebuild the same database.
     *
     * The two kinds are mutually exclusive — picking a folder replaces a file selection and
     * vice versa — so the unused key is always cleared.
     */
    private fun rememberSelection(treeUri: Uri? = null, fileUris: List<Uri>? = null) {
        prefs.edit().apply {
            if (treeUri != null) {
                putString(KEY_DAT_TREE_URI, treeUri.toString())
                remove(KEY_DAT_FILE_URIS)
            } else {
                remove(KEY_DAT_TREE_URI)
                putStringSet(KEY_DAT_FILE_URIS, fileUris.orEmpty().mapTo(mutableSetOf()) { it.toString() })
            }
        }.apply()
    }

    /** Reuses last session's ROM folder and DAT selection when their grants survived. */
    private fun restoreSavedSelections() {
        prefs.getString(KEY_NAMING_POLICY, null)
            ?.let { name -> NamingPolicy.entries.firstOrNull { it.name == name } }
            ?.let { policy -> _state.update { it.copy(namingPolicy = policy) } }
        _state.update {
            it.copy(
                inspectArchives = prefs.getBoolean(KEY_INSPECT_ARCHIVES, true),
                fuzzyMatching = prefs.getBoolean(KEY_FUZZY_MATCHING, true),
                fuzzyThreshold = prefs.getFloat(
                    KEY_FUZZY_THRESHOLD,
                    FuzzyTitleMatcher.DEFAULT_THRESHOLD,
                ),
            )
        }

        prefs.getString(KEY_TREE_URI, null)?.let { saved ->
            val uri = Uri.parse(saved)
            if (safHandler.hasPersistedPermission(uri)) {
                treeUri = uri
                _state.update { it.copy(folderName = safHandler.treeDisplayName(uri)) }
            } else {
                prefs.edit().remove(KEY_TREE_URI).apply()
            }
        }

        restoreDatSelection()
    }

    private fun restoreDatSelection() {
        prefs.getString(KEY_DAT_TREE_URI, null)?.let { saved ->
            val uri = Uri.parse(saved)
            if (safHandler.hasPersistedReadPermission(uri)) {
                datJob = viewModelScope.launch { loadDatFolder(uri) }
            } else {
                prefs.edit().remove(KEY_DAT_TREE_URI).apply()
            }
            return
        }

        val saved = prefs.getStringSet(KEY_DAT_FILE_URIS, null).orEmpty()
        if (saved.isEmpty()) return

        // Individual grants are revoked independently, so a partly-surviving selection is
        // reloaded from whatever is left rather than discarded wholesale.
        val stillGranted = saved.map(Uri::parse).filter(safHandler::hasPersistedReadPermission)
        if (stillGranted.isEmpty()) {
            prefs.edit().remove(KEY_DAT_FILE_URIS).apply()
            return
        }

        datJob = viewModelScope.launch {
            val sources = withContext(Dispatchers.IO) {
                stillGranted.mapNotNull { uri ->
                    safHandler.documentInfo(uri)?.let { info ->
                        DatSource(uri = info.uri, name = info.name, sizeBytes = info.sizeBytes)
                    }
                }
            }
            if (sources.isNotEmpty()) loadDats(sources, DatSelection.Files(sources.size))
        }
    }

    private companion object {
        const val TAG = "MainViewModel"
        const val PREFS = "rom_renamer"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_DAT_TREE_URI = "dat_tree_uri"
        const val KEY_DAT_FILE_URIS = "dat_file_uris"
        const val KEY_NAMING_POLICY = "naming_policy"
        const val KEY_INSPECT_ARCHIVES = "inspect_archives"
        const val KEY_FUZZY_MATCHING = "fuzzy_matching"
        const val KEY_FUZZY_THRESHOLD = "fuzzy_threshold"
        const val EMIT_INTERVAL_MS = 120L
    }
}
