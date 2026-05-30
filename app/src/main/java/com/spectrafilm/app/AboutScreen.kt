/*
 * Spektrafilm for Android — About section. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The dedication, credits/thanks, author links, version, license and source. Rendered
 * either as its own scrollable screen (AboutScreen) or as a card embedded in Settings
 * (AboutCard). Links open via Links.open(). Self-contained Material3, no new dependency.
 */
package com.spectrafilm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Full-screen About, used when reached from the top-bar / Settings. */
@Composable
fun AboutScreen() {
    // Show the How-To guide over this screen when the user taps the button.
    // Local state: no MainActivity or NavController dependency.
    var showHowTo by remember { mutableStateOf(false) }

    if (showHowTo) {
        HowToUseScreen(onBack = { showHowTo = false })
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Prominent "How to use this app" entry point at the top of About.
        Button(
            onClick = { showHowTo = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("How to use this app")
        }
        AboutContent()
    }
}

/** About rendered as a collapsible card (embedded in Settings). */
@Composable
fun AboutCard() {
    var expanded by remember { mutableStateOf(false) }
    SectionCard("About", expanded, { expanded = it }) {
        AboutContent()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutContent() {
    val ctx = LocalContext.current

    Text("Spektrafilm", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(
        "Version ${appVersionName(ctx)} (${appVersionCode(ctx)})",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(
        "Spectral film simulation on your phone — film modeling powered by spektrafilm.",
        style = MaterialTheme.typography.bodyMedium,
    )

    Text("Dedication", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text(
        "Dedicated to the pixls.us community.",
        style = MaterialTheme.typography.bodyMedium,
    )

    Text("Thanks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text(
        "Film modeling powered by spektrafilm (Andrea Volpato). UI inspired by Image " +
            "Toolbox (T8RIN). Colour math from colour-science. RAW decoding via LibRaw.",
        style = MaterialTheme.typography.bodyMedium,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { Links.open(ctx, Links.SPEKTRAFILM) }, label = { Text("spektrafilm") })
        AssistChip(onClick = { Links.open(ctx, Links.IMAGE_TOOLBOX) }, label = { Text("Image Toolbox") })
        AssistChip(onClick = { Links.open(ctx, Links.COLOUR_SCIENCE) }, label = { Text("colour-science") })
        AssistChip(onClick = { Links.open(ctx, Links.LIBRAW) }, label = { Text("LibRaw") })
        AssistChip(onClick = { Links.open(ctx, Links.PIXLS) }, label = { Text("pixls.us") })
    }

    Text("Author", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text("Akshay", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { Links.open(ctx, Links.AUTHOR_INSTAGRAM) }, label = { Text("Instagram") })
        AssistChip(onClick = { Links.open(ctx, Links.AUTHOR_YOUTUBE) }, label = { Text("YouTube") })
    }

    Text("License & source", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text(
        "Free software released under the GNU GPLv3.",
        style = MaterialTheme.typography.bodyMedium,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { Links.open(ctx, Links.SOURCE) }, label = { Text("Source code") })
        AssistChip(onClick = { Links.open(ctx, Links.GPLV3) }, label = { Text("GPLv3") })
        AssistChip(onClick = { Links.open(ctx, Links.NEW_ISSUE) }, label = { Text("Report an issue") })
    }
}
