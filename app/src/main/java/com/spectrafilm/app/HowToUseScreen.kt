/*
 * Spektrafilm for Android — in-app "How to use this app" guide. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A self-contained, scrollable full-screen composable that walks a photographer
 * through every part of the app: what Spektrafilm is, loading a photo, the editing
 * layout, choosing a look, key tools, RAW white balance, non-destructive editing,
 * exporting, tips, and credits. Edge-to-edge safe via windowInsetsPadding(systemBars).
 *
 * Shown from two places:
 *   1. AboutScreen — via local `showHowTo` state inside that composable.
 *   2. WelcomeFlow — via local `showHowTo` state on the last onboarding page.
 * Neither caller needs a parameter change — no MainActivity dependency.
 */
package com.spectrafilm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Full-screen "How to use this app" guide.
 *
 * Self-contained: no NavController, no MainActivity internals. The back affordance is
 * provided by the [onBack] lambda; callers (AboutScreen, WelcomeFlow) manage
 * show/hide via a local [androidx.compose.runtime.mutableStateOf] boolean.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowToUseScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            TopAppBar(
                title = { Text("How to use Spektrafilm") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HowToContent(ctx)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Main guide content (extracted so it can be tested in isolation if needed)
// ---------------------------------------------------------------------------

@Composable
private fun HowToContent(ctx: android.content.Context) {

    // --- 1. What Spektrafilm is ---
    GuideSection(
        icon = SpectraIcons.Simulation,
        title = "What Spektrafilm is",
    ) {
        GuideBody(
            "Spektrafilm is a physically-based spectral film simulator. Unlike a simple filter " +
                "or a look-up table, it models the real analog pipeline: a virtual negative is " +
                "exposed to your photo's light, developed in a chemical bath, optionally enlarged " +
                "onto print paper in a darkroom, and scanned back to a digital file — all driven " +
                "by measured spectral data from real film stocks."
        )
        GuideBody(
            "The result is a faithful reproduction of color, grain, halation, and dye chemistry " +
                "that matches what you would get in a real darkroom — not an approximation."
        )
        AttributionNote("Film modeling powered by spektrafilm (Andrea Volpato).")
    }

    // --- 2. Getting a photo in ---
    GuideSection(
        icon = SpectraIcons.Input,
        title = "Getting a photo in",
    ) {
        GuideStep(1, "Tap the open button (top-left of the editor) to pick an image.")
        GuideStep(2, "Choose a JPEG or HEIC from your gallery for quick results.")
        GuideStep(3,
            "For the truest film rendering, open a RAW or DNG file. " +
                "The app decodes RAW files through LibRaw to a linear, scene-referred image — " +
                "the same starting point real film would have."
        )
        GuideStep(4,
            "If you don't have a photo handy, tap \"Demo image\" to load the built-in test image."
        )
        GuideBody(
            "EXIF orientation is read automatically: portrait shots and rotated RAW files " +
                "will appear upright in the preview without you doing anything."
        )
    }

    // --- 3. Editing screen layout ---
    GuideSection(
        icon = SpectraIcons.SourceImage,
        title = "The editing screen layout",
    ) {
        GuideBody(
            "The editor is designed like a professional photo editing app. " +
                "From top to bottom:"
        )
        GuideStep(1,
            "Pinned preview — your photo, rendered live with the current settings, " +
                "fills the top portion of the screen. A status pill in the corner shows " +
                "when a render is in progress and how long the last render took."
        )
        GuideStep(2,
            "Histogram — displayed over the preview so you can judge exposure and clipping."
        )
        GuideStep(3,
            "Scrollable category bar — a row of icons at the bottom. Swipe left/right to " +
                "reach all 13 categories. Tap any icon to open that category's adjustment panel."
        )
        GuideBody("The categories are:")
        CategoryList()
        GuideBody(
            "Tap a category icon to open its panel below the preview. " +
                "Swipe the panel downward to dismiss it and return to the full preview."
        )
    }

    // --- 4. Choosing a look ---
    GuideSection(
        icon = SpectraIcons.Presets,
        title = "Choosing a look",
    ) {
        GuideBody("Start here to find the right film character for your photo:")
        GuideStep(1,
            "Open the Simulation category. Choose a film stock from the Film picker — " +
                "28 profiles are available, grouped as color negative, slide, motion-picture, " +
                "and print film. Each profile shows the film's ISO, color balance, and era."
        )
        GuideStep(2,
            "If you want a printed look (negative→enlarger→paper), also select a print paper " +
                "in the Print picker within Simulation. Leave it at its default for a direct " +
                "scan-of-the-negative result."
        )
        GuideStep(3,
            "Adjust the exposure slider to brighten or darken the simulated exposure. " +
                "Alternatively, tap Auto to let the app meter the scene for you. Auto-exposure " +
                "meters to a mid-grey target and offers 7 metering patterns (center-weighted, " +
                "matrix, spot, and more) in a pop-up list."
        )
        GuideStep(4,
            "For a fast starting point, open the Presets category. 20 built-in, researched " +
                "film-to-print looks are available. Tap a preset to load its full parameter set, " +
                "then fine-tune from there."
        )
    }

    // --- 5. Key tools ---
    GuideSection(
        icon = SpectraIcons.Rotate,
        title = "Key tools",
    ) {
        GuideBody("Several tools are available directly in the editor:")
        GuideStep(1,
            "90° rotate — the rotate button (top bar) rotates your photo 90° clockwise. " +
                "The rotation applies to the preview, the export, and the grain magnifier."
        )
        GuideStep(2,
            "Crop — the Input category contains an interactive crop overlay. " +
                "Drag the corner and edge handles to crop freely, or tap one of the aspect " +
                "ratio presets (1:1, 4:3, 3:2, 16:9, etc.) to lock the ratio."
        )
        GuideStep(3,
            "Before / after compare — available in the Source category. Lets you flick " +
                "between the original and the simulated result to judge the effect."
        )
        GuideStep(4,
            "100% grain magnifier — accessible from the Grain category. Shows a 1:1 crop " +
                "of the grain texture so you can check the grain scale and character before " +
                "exporting at full resolution."
        )
        GuideStep(5,
            "Profile curve browser — in the Simulation category, tap the film or paper name " +
                "to open the curve browser. It shows that stock's spectral sensitivity, " +
                "characteristic (H-D) density curves, and dye density spectra."
        )
    }

    // --- 6. RAW white balance ---
    GuideSection(
        icon = SpectraIcons.ImportRaw,
        title = "RAW white balance",
    ) {
        GuideBody(
            "When a RAW or DNG file is loaded, the RAW WB category becomes active. " +
                "It gives you control over how LibRaw interprets the raw sensor data:"
        )
        GuideStep(1,
            "Mode — choose As-shot (uses the camera's recorded white balance), Daylight, " +
                "or Tungsten for a preset. Select Custom to enter your own values."
        )
        GuideStep(2,
            "Temperature and Tint sliders — available in Custom mode to dial in a precise " +
                "white balance. Changing either slider re-decodes the RAW preview automatically."
        )
        GuideStep(3, "Reset — returns the white balance to the camera's as-shot setting.")
        GuideBody(
            "For JPEG or HEIC sources the RAW WB category is hidden, since the white " +
                "balance is already baked into those files."
        )
    }

    // --- 7. Non-destructive editing ---
    GuideSection(
        icon = SpectraIcons.Settings,
        title = "Non-destructive editing",
    ) {
        GuideBody(
            "All edits are non-destructive. Here is what that means in practice:"
        )
        GuideStep(1,
            "Your original file is never changed. The app only reads it."
        )
        GuideStep(2,
            "Every adjustment is stored as a recipe (a small JSON sidecar) keyed to that " +
                "specific source image."
        )
        GuideStep(3,
            "When you reopen the same image later, the recipe is loaded automatically and " +
                "the simulation re-renders exactly where you left off."
        )
        GuideStep(4,
            "The recipe is re-applied at full resolution when you export, so your preview " +
                "at a smaller size and the exported file at full resolution are the same look."
        )
    }

    // --- 8. Exporting ---
    GuideSection(
        icon = SpectraIcons.Display,
        title = "Exporting",
    ) {
        GuideBody(
            "When you are happy with the look, tap the export button (top bar) to save the result."
        )
        GuideBody("Export settings (in the Settings screen):")
        GuideStep(1,
            "Format — choose from 8-bit JPEG, 8-bit PNG, 16-bit TIFF, 16-bit PNG, or " +
                "Google Ultra HDR (gain-map JPEG, on supported devices). " +
                "16-bit TIFF and 16-bit PNG preserve the full dynamic range of the simulation."
        )
        GuideStep(2,
            "JPEG quality — a 0–100 slider for JPEG exports."
        )
        GuideStep(3,
            "Keep GPS / location — OFF by default for privacy. Turn it on if you want GPS " +
                "coordinates copied from the source EXIF into the exported file."
        )
        GuideBody(
            "The exported file is saved to Pictures/Spektrafilm on your device. " +
                "Camera metadata (make, model, lens, exposure settings) from the source image " +
                "is copied into the exported file's EXIF automatically."
        )
    }

    // --- 9. Tips ---
    GuideSection(
        icon = SpectraIcons.Halation,
        title = "Tips for best results",
    ) {
        GuideStep(1,
            "Judge film looks in a darkened room. Film character is subtle and ambient " +
                "light on the screen will flatten your perception of it."
        )
        GuideStep(2,
            "Use RAW input for the most accurate result. JPEG files have already been " +
                "processed by the camera's tone curve, white balance, and sharpening, which " +
                "changes how the simulation perceives the scene."
        )
        GuideStep(3,
            "Export to 16-bit TIFF or 16-bit PNG when you plan to do further editing. " +
                "This keeps the full precision of the simulation for later adjustments."
        )
        GuideStep(4,
            "Auto-exposure meters to mid-grey. If your subject is lighter or darker than " +
                "mid-grey you may want to nudge the exposure slider manually afterward."
        )
        GuideStep(5,
            "Grain is stochastic (random). The preview may look slightly different each " +
                "time you render; the export is always the definitive full-resolution version."
        )
    }

    // --- 10. Credits / links ---
    GuideSection(
        icon = SpectraIcons.Glare,
        title = "Credits",
    ) {
        GuideBody(
            "Film modeling powered by spektrafilm (Andrea Volpato). The science, the film-stock " +
                "profiles, and the spectral LUTs are Andrea's work — Spektrafilm is a " +
                "native Android port of that engine, verified bit-exact against the original."
        )
        GuideBody("Dedicated to the pixls.us community.")
        GuideBody(
            "UI inspired by Image Toolbox (T8RIN). Colour math from colour-science. " +
                "RAW decoding via LibRaw. Free software under the GPLv3."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { Links.open(ctx, Links.SPEKTRAFILM) }) {
                Text("spektrafilm")
            }
            TextButton(onClick = { Links.open(ctx, Links.PIXLS) }) {
                Text("pixls.us")
            }
            TextButton(onClick = { Links.open(ctx, Links.SOURCE) }) {
                Text("Source code")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Category list helper (inline in section 3)
// ---------------------------------------------------------------------------

@Composable
private fun CategoryList() {
    val categories = listOf(
        "Source" to "open / close a photo, demo image, before/after compare",
        "Presets" to "load a built-in or saved parameter set",
        "Simulation" to "film stock, print paper, exposure, Auto-exposure",
        "Input" to "crop, resize, upscale factor",
        "RAW WB" to "temperature, tint, white-balance mode (RAW/DNG only)",
        "Grain" to "grain amount, scale, sublayer mixing, 100% magnifier",
        "Halation" to "halation glow radius and strength",
        "Glare" to "lens flare / glare (print path, off by default)",
        "Couplers" to "DIR dye-coupler diffusion",
        "Preflash" to "base-fog preflash amount",
        "Experimental" to "developer options and advanced engine flags",
        "Display" to "output color space (sRGB, Adobe RGB, ProPhoto, Rec.2020, etc.)",
        "Settings / About" to "app settings and this guide",
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        categories.forEach { (name, description) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Text(
                    " — $description",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Layout primitives
// ---------------------------------------------------------------------------

/** A guide section: a card with an icon + heading and a [content] column. */
@Composable
private fun GuideSection(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

/** A numbered step inside a section. */
@Composable
private fun GuideStep(number: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            modifier = Modifier.padding(top = 1.dp),
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A paragraph of body text. */
@Composable
private fun GuideBody(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** A small attribution / note chip. */
@Composable
private fun AttributionNote(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
