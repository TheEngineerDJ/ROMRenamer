package com.romrenamer.app.ui

import com.romrenamer.app.core.dat.DatSourceOutcome
import com.romrenamer.app.core.match.FuzzyTitleMatcher
import com.romrenamer.app.core.match.MatchStatus
import com.romrenamer.app.core.match.ScanSummary
import com.romrenamer.app.core.match.ScannedRom
import com.romrenamer.app.core.rename.NamingPolicy
import com.romrenamer.app.core.rename.RenameReport

/** What the app is busy with; drives progress indicators and button enablement. */
sealed interface Phase {
    data object Idle : Phase

    /** Reading the selected DAT files, one after another, into a single index. */
    data class LoadingDats(
        val fileNumber: Int,
        val fileCount: Int,
        val fileName: String,
        val gamesParsed: Int,
        val fraction: Float?,
    ) : Phase

    data class Listing(val filesFound: Int, val currentPath: String) : Phase
    data class Scanning(val completed: Int, val total: Int) : Phase
    data class Renaming(val completed: Int, val total: Int) : Phase

    val isBusy: Boolean get() = this !is Idle
}

/** Which rows the list shows. */
enum class RowFilter(val label: String) {
    ALL("All"),
    TO_RENAME("To rename"),
    MATCHED("Hash matched"),
    TEXT_MATCHED("Text matched"),
    UNMATCHED("Unmatched"),
    PROBLEMS("Problems"),
    ;

    fun accepts(rom: ScannedRom): Boolean = when (this) {
        ALL -> true
        TO_RENAME -> rom.needsRename
        MATCHED -> rom.status is MatchStatus.Matched
        TEXT_MATCHED -> rom.status is MatchStatus.FuzzyMatched
        UNMATCHED -> rom.status is MatchStatus.Unmatched || rom.status is MatchStatus.SizeExcluded
        PROBLEMS -> rom.status is MatchStatus.Ambiguous ||
            rom.status is MatchStatus.FuzzyAmbiguous ||
            rom.status is MatchStatus.Failed
    }
}

/** Modal the screen is currently showing, if any. */
sealed interface UiDialog {
    data object ConfirmRename : UiDialog
    data class Report(val report: RenameReport) : UiDialog
    data object Options : UiDialog

    /** Per-file breakdown of the merged DAT database. */
    data object DatSources : UiDialog
}

/** How the DAT selection was made, which is what gets restored on next launch. */
sealed interface DatSelection {
    /** Every DAT found under one granted folder. */
    data class Folder(val name: String) : DatSelection

    /** Individually picked files. */
    data class Files(val count: Int) : DatSelection
}

/** The merged DAT database currently loaded. */
data class DatLibrary(
    val selection: DatSelection,
    val outcomes: List<DatSourceOutcome> = emptyList(),
    val gameCount: Int = 0,
    val romCount: Int = 0,
    /** `true` when the merge stopped early at the entry ceiling. */
    val truncated: Boolean = false,
) {
    val loadedCount: Int get() = outcomes.count { it is DatSourceOutcome.Loaded }
    val rejectedCount: Int get() = outcomes.count { it is DatSourceOutcome.Rejected }

    /** Where the DATs came from, e.g. "No-Intro folder" or "4 files". */
    val sourceLabel: String
        get() = when (selection) {
            is DatSelection.Folder -> selection.name
            is DatSelection.Files -> if (selection.count == 1) "1 file" else "${selection.count} files"
        }

    /** One-line status for the setup card. */
    val summaryLine: String
        get() = buildString {
            append("$sourceLabel · ")
            append(if (loadedCount == 1) "1 DAT" else "$loadedCount DATs")
            append(" · ")
            append("%,d".format(gameCount))
            append(" games")
            if (rejectedCount > 0) append(" · $rejectedCount skipped")
        }
}

data class MainUiState(
    val folderName: String? = null,
    val datLibrary: DatLibrary? = null,
    val phase: Phase = Phase.Idle,
    val roms: List<ScannedRom> = emptyList(),
    val summary: ScanSummary = ScanSummary(),
    val rowFilter: RowFilter = RowFilter.ALL,
    val namingPolicy: NamingPolicy = NamingPolicy.DAT_ROM_NAME,
    val inspectArchives: Boolean = true,
    val fuzzyMatching: Boolean = true,
    val fuzzyThreshold: Float = FuzzyTitleMatcher.DEFAULT_THRESHOLD,
    val dialog: UiDialog? = null,
    /** One-shot text for the snackbar. */
    val message: String? = null,
    val scanCompleted: Boolean = false,
) {
    val hasFolder: Boolean get() = folderName != null
    val hasDat: Boolean get() = (datLibrary?.gameCount ?: 0) > 0

    val canScan: Boolean get() = hasFolder && hasDat && !phase.isBusy

    /** Rows the user has ticked that would actually change a file name. */
    val selectedForRename: List<ScannedRom>
        get() = roms.filter { it.selected && it.needsRename }

    val canRename: Boolean get() = !phase.isBusy && selectedForRename.isNotEmpty()
}
