package com.romrenamer.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.romrenamer.app.core.dat.DatSourceOutcome
import com.romrenamer.app.core.match.ScanSummary
import com.romrenamer.app.core.match.ScannedRom
import com.romrenamer.app.core.rename.NamingPolicy
import com.romrenamer.app.core.rename.RenameOutcome
import com.romrenamer.app.core.rename.RenameReport
import com.romrenamer.app.ui.components.RomRow
import com.romrenamer.app.ui.theme.LocalStatusColors

/**
 * The single screen: pick a folder, pick a DAT, scan, review, rename.
 *
 * Nothing on disk changes until the user works through the confirmation dialog, so the
 * whole list is a preview right up to that point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> viewModel.onFolderPicked(uri) }

    val datFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.onDatFilesPicked(uris) }

    val datFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> viewModel.onDatFolderPicked(uri) }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("ROMRenamer") },
                actions = {
                    TextButton(onClick = { viewModel.showDialog(UiDialog.Options) }) {
                        Text("Options")
                    }
                },
            )
        },
        bottomBar = {
            BottomActionBar(
                state = state,
                onRename = viewModel::requestRename,
                onCancelScan = viewModel::cancelScan,
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SetupCard(
                state = state,
                onPickFolder = { folderPicker.launch(null) },
                onPickDatFiles = { datFilePicker.launch(DAT_MIME_TYPES) },
                onPickDatFolder = { datFolderPicker.launch(null) },
                onShowDatSources = { viewModel.showDialog(UiDialog.DatSources) },
                onScan = viewModel::startScan,
            )

            ProgressBanner(state.phase)

            if (state.roms.isNotEmpty()) {
                FilterRow(
                    state = state,
                    onFilterChange = viewModel::setRowFilter,
                    onSelectAll = { viewModel.setAllSelected(true) },
                    onSelectNone = { viewModel.setAllSelected(false) },
                )
                HorizontalDivider()
            }

            RomList(
                state = state,
                onToggle = viewModel::toggleSelection,
                modifier = Modifier.weight(1f),
            )
        }
    }

    when (val dialog = state.dialog) {
        UiDialog.ConfirmRename -> ConfirmRenameDialog(
            candidates = state.selectedForRename,
            onConfirm = viewModel::confirmRename,
            onDismiss = viewModel::dismissDialog,
        )

        is UiDialog.Report -> ReportDialog(dialog.report, viewModel::dismissDialog)

        UiDialog.DatSources -> state.datLibrary?.let { library ->
            DatSourcesDialog(library, viewModel::dismissDialog)
        }

        UiDialog.Options -> OptionsDialog(
            state = state,
            onNamingPolicy = viewModel::setNamingPolicy,
            onInspectArchives = viewModel::setInspectArchives,
            onDismiss = viewModel::dismissDialog,
        )

        null -> Unit
    }
}

@Composable
private fun SetupCard(
    state: MainUiState,
    onPickFolder: () -> Unit,
    onPickDatFiles: () -> Unit,
    onPickDatFolder: () -> Unit,
    onShowDatSources: () -> Unit,
    onScan: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SetupLine(
                label = "ROM folder",
                value = state.folderName ?: "Not selected",
                enabled = !state.phase.isBusy,
            ) {
                OutlinedButton(onClick = onPickFolder, enabled = !state.phase.isBusy) {
                    Text(if (state.hasFolder) "Change" else "Choose")
                }
            }

            DatSetupLine(
                state = state,
                onPickFiles = onPickDatFiles,
                onPickFolder = onPickDatFolder,
                onShowSources = onShowDatSources,
            )

            Button(
                onClick = onScan,
                enabled = state.canScan,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.scanCompleted) "Scan again" else "Scan and match")
            }
        }
    }
}

/**
 * The DAT row offers both selection shapes side by side: several individual files, or a
 * whole folder that is walked for DATs. Either way they merge into one database, so the
 * user never has to work out which DAT covers which ROM.
 */
@Composable
private fun DatSetupLine(
    state: MainUiState,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onShowSources: () -> Unit,
) {
    val library = state.datLibrary
    val enabled = !state.phase.isBusy

    Column {
        SetupLine(
            label = "DAT database",
            value = library?.summaryLine ?: "Not loaded",
            enabled = enabled,
            onValueClick = if (library != null) onShowSources else null,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onPickFiles, enabled = enabled) { Text("Files") }
                OutlinedButton(onClick = onPickFolder, enabled = enabled) { Text("Folder") }
            }
        }

        if (library != null && (library.rejectedCount > 0 || library.truncated)) {
            Text(
                text = if (library.truncated) {
                    "Size limit reached — tap for details"
                } else {
                    "${library.rejectedCount} files skipped — tap for details"
                },
                style = MaterialTheme.typography.bodySmall,
                color = LocalStatusColors.current.warning,
                modifier = Modifier.clickable(onClick = onShowSources).padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SetupLine(
    label: String,
    value: String,
    enabled: Boolean,
    onValueClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onValueClick != null && enabled) {
                        Modifier.clickable(onClick = onValueClick)
                    } else {
                        Modifier
                    },
                ),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

@Composable
private fun ProgressBanner(phase: Phase) {
    if (phase is Phase.Idle) return

    val (text, fraction) = when (phase) {
        is Phase.LoadingDats ->
            buildString {
                if (phase.fileCount > 1) append("DAT ${phase.fileNumber}/${phase.fileCount} · ")
                append(phase.fileName)
                if (phase.gamesParsed > 0) append(" — ${phase.gamesParsed} games")
            } to phase.fraction

        is Phase.Listing ->
            buildString {
                append("Listing files — ${phase.filesFound} found")
                if (phase.currentPath.isNotEmpty()) append(" · ${phase.currentPath}")
            } to null

        is Phase.Scanning ->
            "Hashing ${phase.completed} of ${phase.total}" to
                progressOf(phase.completed, phase.total)

        is Phase.Renaming ->
            "Renaming ${phase.completed} of ${phase.total}" to
                progressOf(phase.completed, phase.total)

        Phase.Idle -> return
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

@Composable
private fun FilterRow(
    state: MainUiState,
    onFilterChange: (RowFilter) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.rowFilter == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text("${filter.label} (${countFor(state.summary, filter)})") },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSelectAll, enabled = state.summary.needsRename > 0) {
                Text("Select all")
            }
            TextButton(onClick = onSelectNone, enabled = state.selectedForRename.isNotEmpty()) {
                Text("Select none")
            }
        }
    }
}

@Composable
private fun RomList(
    state: MainUiState,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = remember(state.roms, state.rowFilter) {
        state.roms.filter { state.rowFilter.accepts(it) }
    }

    if (visible.isEmpty()) {
        EmptyState(state, modifier)
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize(), state = rememberLazyListState()) {
        items(items = visible, key = { it.id }) { rom ->
            RomRow(rom = rom, onToggle = onToggle)
        }
    }
}

@Composable
private fun EmptyState(state: MainUiState, modifier: Modifier = Modifier) {
    val message = when {
        !state.hasFolder -> "Choose the folder holding your ROMs. ROMRenamer only ever touches " +
            "the folder you grant it."
        !state.hasDat -> "Load your No-Intro or Redump DATs — pick individual files, or a " +
            "folder to merge every DAT inside it into one database."
        state.roms.isEmpty() && state.phase.isBusy -> "Working…"
        state.roms.isEmpty() -> "Run a scan to hash your ROMs and match them against the DAT."
        else -> "Nothing matches the \"${state.rowFilter.label}\" filter."
    }

    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BottomActionBar(
    state: MainUiState,
    onRename: () -> Unit,
    onCancelScan: () -> Unit,
) {
    if (state.roms.isEmpty() && !state.phase.isBusy) return

    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summaryLine(state.summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${state.selectedForRename.size} selected",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (state.phase is Phase.Scanning || state.phase is Phase.Listing) {
                OutlinedButton(onClick = onCancelScan) { Text("Stop") }
            }

            Button(onClick = onRename, enabled = state.canRename) {
                Text("Rename ${state.selectedForRename.size}")
            }
        }
    }
}

@Composable
private fun ConfirmRenameDialog(
    candidates: List<ScannedRom>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename ${candidates.size} files?") },
        text = {
            Column {
                Text(
                    "Files are renamed in place. Nothing is moved, copied, or deleted, " +
                        "but renaming cannot be undone from inside the app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(modifier = Modifier.height(220.dp).padding(top = 12.dp)) {
                    LazyColumn {
                        items(items = candidates.take(PREVIEW_LIMIT), key = { it.id }) { rom ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    rom.file.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "→ ${rom.targetName}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (candidates.size > PREVIEW_LIMIT) {
                            item {
                                Text(
                                    "…and ${candidates.size - PREVIEW_LIMIT} more",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReportDialog(report: RenameReport, onDismiss: () -> Unit) {
    val problems = report.results.filter {
        it.outcome is RenameOutcome.Blocked || it.outcome is RenameOutcome.Failed
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renamed ${report.renamed} files") },
        text = {
            Column {
                Text(
                    buildString {
                        append("${report.renamed} renamed")
                        if (report.alreadyCorrect > 0) append(", ${report.alreadyCorrect} already correct")
                        if (report.blocked > 0) append(", ${report.blocked} skipped")
                        if (report.failed > 0) append(", ${report.failed} failed")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (problems.isNotEmpty()) {
                    Column(modifier = Modifier.height(200.dp).padding(top = 12.dp)) {
                        LazyColumn {
                            items(items = problems, key = { it.operation.uri.toString() }) { result ->
                                val reason = when (val outcome = result.outcome) {
                                    is RenameOutcome.Blocked -> outcome.reason
                                    is RenameOutcome.Failed -> outcome.reason
                                    else -> ""
                                }
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        result.operation.displayPath,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * Per-file breakdown of the merged database.
 *
 * A folder selection routinely contains files that are not DATs, so the app needs to be
 * able to say exactly which ones contributed and which were passed over — otherwise a
 * silently-ignored DAT looks identical to a ROM that genuinely is not catalogued.
 */
@Composable
private fun DatSourcesDialog(library: DatLibrary, onDismiss: () -> Unit) {
    val loaded = library.outcomes.filterIsInstance<DatSourceOutcome.Loaded>()
    val rejected = library.outcomes.filterIsInstance<DatSourceOutcome.Rejected>()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DAT database") },
        text = {
            Column {
                Text(library.summaryLine, style = MaterialTheme.typography.bodyMedium)
                if (library.truncated) {
                    Text(
                        text = "The merge stopped at the entry limit, so the last files were " +
                            "not read. Select fewer DATs for a complete database.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalStatusColors.current.warning,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                LazyColumn(modifier = Modifier.height(280.dp).padding(top = 12.dp)) {
                    if (loaded.isNotEmpty()) {
                        item {
                            SectionLabel("Loaded (${loaded.size})")
                        }
                        items(items = loaded, key = { it.source.uri.toString() }) { outcome ->
                            Column(modifier = Modifier.padding(vertical = 3.dp)) {
                                Text(
                                    outcome.header.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "${outcome.source.displayPath} · ${outcome.games} games",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (rejected.isNotEmpty()) {
                        item {
                            SectionLabel("Skipped (${rejected.size})")
                        }
                        items(items = rejected, key = { it.source.uri.toString() }) { outcome ->
                            Column(modifier = Modifier.padding(vertical = 3.dp)) {
                                Text(
                                    outcome.source.displayPath,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    outcome.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun OptionsDialog(
    state: MainUiState,
    onNamingPolicy: (NamingPolicy) -> Unit,
    onInspectArchives: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Options") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Naming", style = MaterialTheme.typography.labelLarge)
                NamingPolicy.entries.forEach { policy ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = state.namingPolicy == policy,
                            onClick = { onNamingPolicy(policy) },
                            label = {
                                Text(
                                    when (policy) {
                                        NamingPolicy.DAT_ROM_NAME -> "Official DAT file name"
                                        NamingPolicy.GAME_TITLE_KEEP_EXTENSION -> "Title, keep extension"
                                    },
                                )
                            },
                        )
                    }
                }

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Look inside .zip files", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Hash the ROM inside the archive, which is what DATs record.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.inspectArchives, onCheckedChange = onInspectArchives)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun summaryLine(summary: ScanSummary): String = buildString {
    append("${summary.matched} matched")
    append(" · ${summary.needsRename} to rename")
    if (summary.alreadyCorrect > 0) append(" · ${summary.alreadyCorrect} ok")
    if (summary.ambiguous > 0) append(" · ${summary.ambiguous} ambiguous")
    if (summary.unmatched > 0) append(" · ${summary.unmatched} unmatched")
    if (summary.failed > 0) append(" · ${summary.failed} failed")
}

private fun countFor(summary: ScanSummary, filter: RowFilter): Int = when (filter) {
    RowFilter.ALL -> summary.total
    RowFilter.TO_RENAME -> summary.needsRename
    RowFilter.MATCHED -> summary.matched
    RowFilter.UNMATCHED -> summary.unmatched
    RowFilter.PROBLEMS -> summary.ambiguous + summary.failed
}

private fun progressOf(completed: Int, total: Int): Float? =
    if (total <= 0) null else (completed.toFloat() / total).coerceIn(0f, 1f)

private const val PREVIEW_LIMIT = 200

/**
 * DATs are published as `.dat` or `.xml`; providers report them inconsistently, so the
 * generic type is included to keep them selectable.
 */
private val DAT_MIME_TYPES = arrayOf("text/xml", "application/xml", "application/octet-stream", "*/*")
