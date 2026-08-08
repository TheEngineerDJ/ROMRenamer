package com.romrenamer.app.ui

import com.romrenamer.app.core.match.MatchStatus
import com.romrenamer.app.core.match.ScanSummary
import com.romrenamer.app.core.match.ScannedRom
import com.romrenamer.app.core.rename.NamingPolicy
import com.romrenamer.app.core.rename.RenameReport

/** What the app is busy with; drives progress indicators and button enablement. */
sealed interface Phase {
    data object Idle : Phase
    data class LoadingDat(val fileName: String, val gamesParsed: Int, val fraction: Float?) : Phase
    data class Listing(val filesFound: Int, val currentPath: String) : Phase
    data class Scanning(val completed: Int, val total: Int) : Phase
    data class Renaming(val completed: Int, val total: Int) : Phase

    val isBusy: Boolean get() = this !is Idle
}

/** Which rows the list shows. */
enum class RowFilter(val label: String) {
    ALL("All"),
    TO_RENAME("To rename"),
    MATCHED("Matched"),
    UNMATCHED("Unmatched"),
    PROBLEMS("Problems"),
    ;

    fun accepts(rom: ScannedRom): Boolean = when (this) {
        ALL -> true
        TO_RENAME -> rom.needsRename
        MATCHED -> rom.status is MatchStatus.Matched
        UNMATCHED -> rom.status is MatchStatus.Unmatched || rom.status is MatchStatus.SizeExcluded
        PROBLEMS -> rom.status is MatchStatus.Ambiguous || rom.status is MatchStatus.Failed
    }
}

/** Modal the screen is currently showing, if any. */
sealed interface UiDialog {
    data object ConfirmRename : UiDialog
    data class Report(val report: RenameReport) : UiDialog
    data object Options : UiDialog
}

data class MainUiState(
    val folderName: String? = null,
    val datName: String? = null,
    val datGameCount: Int = 0,
    val datRomCount: Int = 0,
    val phase: Phase = Phase.Idle,
    val roms: List<ScannedRom> = emptyList(),
    val summary: ScanSummary = ScanSummary(),
    val rowFilter: RowFilter = RowFilter.ALL,
    val namingPolicy: NamingPolicy = NamingPolicy.DAT_ROM_NAME,
    val inspectArchives: Boolean = true,
    val dialog: UiDialog? = null,
    /** One-shot text for the snackbar. */
    val message: String? = null,
    val scanCompleted: Boolean = false,
) {
    val hasFolder: Boolean get() = folderName != null
    val hasDat: Boolean get() = datGameCount > 0

    val canScan: Boolean get() = hasFolder && hasDat && !phase.isBusy

    /** Rows the user has ticked that would actually change a file name. */
    val selectedForRename: List<ScannedRom>
        get() = roms.filter { it.selected && it.needsRename }

    val canRename: Boolean get() = !phase.isBusy && selectedForRename.isNotEmpty()
}
