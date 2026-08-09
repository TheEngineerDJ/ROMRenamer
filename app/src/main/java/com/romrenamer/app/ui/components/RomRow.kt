package com.romrenamer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.romrenamer.app.core.match.MatchStatus
import com.romrenamer.app.core.match.ScannedRom
import com.romrenamer.app.ui.theme.LocalStatusColors

/**
 * One file in the preview list: what it is called now, what it would be called, and why.
 *
 * The checkbox is only meaningful for rows that would actually change, so it is hidden
 * everywhere else rather than shown disabled — a screen of dead checkboxes reads as broken.
 */
@Composable
fun RomRow(
    rom: ScannedRom,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectable = rom.needsRename
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (selectable) Modifier.clickable { onToggle(rom.id) } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (selectable) {
            Checkbox(
                checked = rom.selected,
                onCheckedChange = { onToggle(rom.id) },
                modifier = Modifier.padding(end = 4.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = rom.file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            rom.targetName?.takeIf { it != rom.file.name }?.let { target ->
                Text(
                    text = "→ $target",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            SecondaryLine(rom)
        }

        StatusBadge(rom.status, Modifier.padding(start = 8.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SecondaryLine(rom: ScannedRom) {
    val detail = when (val status = rom.status) {
        is MatchStatus.Matched -> buildString {
            append(status.entry.game.name)
            if (status.fromArchive && rom.archiveEntryName != null) {
                append(" · in archive: ${rom.archiveEntryName}")
            }
            rom.hashes.crc32?.let { append(" · CRC32 $it") }
        }

        is MatchStatus.FuzzyMatched -> buildString {
            append(status.entry.game.name)
            append(" · name only, ${status.confidencePercent}% similar")
            append(if (status.sizeMismatch) " · size not in DAT" else " · no hash match")
        }

        is MatchStatus.Ambiguous ->
            "${status.candidates.size} possible matches: " +
                status.candidates.take(3).joinToString(", ") { it.game.name }

        is MatchStatus.FuzzyAmbiguous ->
            "Name fits ${status.candidates.size} titles equally: " +
                status.candidates.take(3).joinToString(", ") { it.game.name }

        is MatchStatus.Failed -> status.message

        MatchStatus.Unmatched ->
            rom.hashes.crc32?.let { "Not in DAT · CRC32 $it" } ?: "Not in DAT"

        MatchStatus.SizeExcluded -> "Skipped — no DAT entry has this file size"

        MatchStatus.Hashing -> "Hashing…"

        MatchStatus.Pending -> rom.file.relativePath.ifEmpty { "Waiting…" }
    }

    Text(
        text = detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun StatusBadge(status: MatchStatus, modifier: Modifier = Modifier) {
    val statusColors = LocalStatusColors.current
    val (label, color) = when (status) {
        is MatchStatus.Matched -> "Hash match" to statusColors.success
        // Amber, not green: the name fits but nothing about the bytes was verified.
        is MatchStatus.FuzzyMatched -> "Text matched" to statusColors.warning
        is MatchStatus.Ambiguous -> "Ambiguous" to statusColors.warning
        is MatchStatus.FuzzyAmbiguous -> "Ambiguous" to statusColors.warning
        is MatchStatus.Failed -> "Error" to MaterialTheme.colorScheme.error
        MatchStatus.Unmatched -> "No match" to MaterialTheme.colorScheme.onSurfaceVariant
        MatchStatus.SizeExcluded -> "Skipped" to MaterialTheme.colorScheme.onSurfaceVariant
        MatchStatus.Hashing -> "Hashing" to MaterialTheme.colorScheme.primary
        MatchStatus.Pending -> "Pending" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .background(color.copy(alpha = BADGE_ALPHA), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            // The badge repeats information already in the row text below it.
            .clearAndSetSemantics {},
    )
}

private const val BADGE_ALPHA = 0.14f
