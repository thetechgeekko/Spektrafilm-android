/*
 * Spektrafilm for Android. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * The Versions section of the Presets panel: save the current look of THIS photo under a name,
 * and put any saved look back. State and storage live in [VersionsViewModel]; this file is
 * presentation only.
 */
package com.spectrafilm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
internal fun VersionsSection(
    ui: VersionsUiState,
    onSave: (name: String) -> Unit,
    onApply: (PhotoVersion) -> Unit,
    onReplaceWithCurrent: (PhotoVersion) -> Unit,
    onRename: (PhotoVersion, String) -> Unit,
    onDelete: (PhotoVersion) -> Unit,
) {
    Text(
        stringResource(R.string.editor_versions_heading),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() },
    )
    if (!ui.available) {
        Text(
            stringResource(R.string.editor_versions_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text(
        stringResource(R.string.editor_versions_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val prefix = stringResource(R.string.editor_versions_default_prefix)
    var naming by remember { mutableStateOf<String?>(null) }
    val full = ui.versions.size >= PhotoVersionCodec.MAX_VERSIONS
    // Tonal: this sits in the Presets panel under its filled "Save preset".
    FilledTonalButton(
        onClick = { naming = PhotoVersionCodec.defaultName(ui.versions, prefix) },
        enabled = !full && !ui.loading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.editor_versions_save))
    }
    if (full) {
        Text(
            stringResource(R.string.editor_versions_full, PhotoVersionCodec.MAX_VERSIONS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    // One grouped list, the same segmented rows as Settings and Photo info.
    Column(verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP)) {
    ui.versions.forEachIndexed { i, version ->
        VersionRow(
            version = version,
            shape = segmentShape(i, ui.versions.size),
            onApply = { onApply(version) },
            onReplaceWithCurrent = { onReplaceWithCurrent(version) },
            onRename = { name -> onRename(version, name) },
            onDelete = { onDelete(version) },
        )
    }
    }

    naming?.let { initial ->
        NameDialog(
            title = stringResource(R.string.editor_versions_save_title),
            initial = initial,
            confirm = stringResource(R.string.editor_versions_save_confirm),
            onDismiss = { naming = null },
            onConfirm = { name -> naming = null; onSave(name) },
        )
    }
}

@Composable
private fun VersionRow(
    version: PhotoVersion,
    shape: androidx.compose.ui.graphics.Shape,
    onApply: () -> Unit,
    onReplaceWithCurrent: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val when_ = remember(version.updatedAtMillis) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(version.updatedAtMillis))
    }
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                version.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when_,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onApply) {
            Text(stringResource(R.string.editor_versions_apply))
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(
                    SpectraIcons.MoreVert,
                    contentDescription = stringResource(R.string.editor_versions_more, version.name),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_versions_update)) },
                    onClick = { menu = false; onReplaceWithCurrent() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_versions_rename)) },
                    onClick = { menu = false; renaming = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_versions_delete)) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
    }
    if (renaming) {
        NameDialog(
            title = stringResource(R.string.editor_versions_rename_title),
            initial = version.name,
            confirm = stringResource(R.string.editor_versions_rename_confirm),
            onDismiss = { renaming = false },
            onConfirm = { name -> renaming = false; onRename(name) },
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val cleaned = PhotoVersionCodec.cleanName(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= PhotoVersionCodec.MAX_NAME_CHARS) text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.editor_versions_name_label)) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(cleaned) }, enabled = cleaned.isNotEmpty()) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
