/*
 * SpectraFilm for Android — Settings screen. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * App-level settings persisted via AppSettings (SharedPreferences): default output
 * color space, preview resolution, default film/print profiles, export format +
 * quality, theme, a "Show onboarding again" action, a "Report an issue" entry (and a
 * "View issues" link), and an embedded About card. Edits write straight back to the
 * passed AppSettings and mirror into local Compose state so the controls update live.
 * The host (MainActivity) reads AppSettings on the next launch / immediately re-applies
 * relevant values (theme, preview size, default profiles, output space).
 */
package com.spectrafilm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.spectrafilm.engine.ColorSpace

/**
 * The Settings UI. [settings] is the live store; [onThemeChanged] lets the host re-apply
 * the theme immediately. [filmGroups]/[printGroups] are the catalog-grouped profile
 * options (may be empty before the engine is ready); [onShowOnboarding] re-launches the
 * welcome flow.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    filmGroups: List<DropdownGroup>,
    printGroups: List<DropdownGroup>,
    onThemeChanged: (ThemeMode) -> Unit,
    onShowOnboarding: () -> Unit,
) {
    val ctx = LocalContext.current

    // Local mirrors of the persisted values so controls update without a recomposition key.
    var theme by remember { mutableStateOf(settings.theme) }
    var outputCs by remember { mutableStateOf(settings.defaultOutputColorSpace) }
    var previewSize by remember { mutableIntStateOf(settings.previewMaxSize) }
    var film by remember { mutableStateOf(settings.defaultFilmProfile) }
    var print by remember { mutableStateOf(settings.defaultPrintProfile) }
    var format by remember { mutableStateOf(settings.exportFormat) }
    var quality by remember { mutableIntStateOf(settings.exportQuality) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        // --- Appearance ---
        SettingsCard("Appearance") {
            Dropdown(
                label = "Theme",
                selected = theme,
                options = ThemeMode.entries.toList(),
                display = { it.display },
                onSelect = { theme = it; settings.theme = it; onThemeChanged(it) },
            )
        }

        // --- Defaults ---
        SettingsCard("Render defaults") {
            Text(
                "Applied when the app starts. Per-image edits and presets still override these.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Dropdown(
                label = "Default output color space",
                selected = outputCs,
                options = ColorSpace.entries.toList(),
                display = { it.name },
                onSelect = { outputCs = it; settings.defaultOutputColorSpace = it },
            )
            IntSlider(
                label = "Preview max size",
                value = previewSize,
                range = 128..1024,
                onValueChange = { previewSize = it; settings.previewMaxSize = it },
                tooltip = "Long edge of the interactive preview, in pixels.",
            )
            if (filmGroups.isNotEmpty()) {
                GroupedDropdown(
                    label = "Default film profile",
                    selectedId = film.ifEmpty { filmGroups.firstOrNull()?.options?.firstOrNull()?.id ?: "" },
                    groups = filmGroups,
                    onSelect = { film = it; settings.defaultFilmProfile = it },
                )
            }
            if (printGroups.isNotEmpty()) {
                GroupedDropdown(
                    label = "Default print profile",
                    selectedId = print.ifEmpty { printGroups.firstOrNull()?.options?.firstOrNull()?.id ?: "" },
                    groups = printGroups,
                    onSelect = { print = it; settings.defaultPrintProfile = it },
                )
            }
        }

        // --- Export ---
        SettingsCard("Export") {
            Dropdown(
                label = "Export format",
                selected = format,
                options = ExportFormat.entries.toList(),
                display = { it.display },
                onSelect = { format = it; settings.exportFormat = it },
            )
            if (format == ExportFormat.JPEG) {
                IntSlider(
                    label = "JPEG quality",
                    value = quality,
                    range = 1..100,
                    onValueChange = { quality = it; settings.exportQuality = it },
                )
            }
        }

        // --- Help / feedback ---
        SettingsCard("Help & feedback") {
            Button(
                onClick = { Links.open(ctx, Links.NEW_ISSUE) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Report an issue") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { Links.open(ctx, Links.ISSUES) },
                    modifier = Modifier.weight(1f),
                ) { Text("View issues") }
                OutlinedButton(
                    onClick = { Links.open(ctx, Links.SOURCE) },
                    modifier = Modifier.weight(1f),
                ) { Text("Source") }
            }
            OutlinedButton(
                onClick = onShowOnboarding,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Show onboarding again") }
        }

        // --- About ---
        AboutCard()
    }
}

/** A simple always-expanded settings card (reuses the editor SectionCard look). */
@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    SectionCard(title, expanded, { expanded = it }) { content() }
}
