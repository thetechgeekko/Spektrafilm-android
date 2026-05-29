/*
 * SpectraFilm for Android — app entry. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * UI: pick a photo (or use the synthetic demo image), choose film/print profiles
 * and rendering options, render off the main thread via the native engine, and
 * export the result to the gallery.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.spectrafilm.engine.CameraParams
import com.spectrafilm.engine.ColorSpace
import com.spectrafilm.engine.DirCouplersParams
import com.spectrafilm.engine.FilmRenderingParams
import com.spectrafilm.engine.GlareParams
import com.spectrafilm.engine.GrainParams
import com.spectrafilm.engine.HalationParams
import com.spectrafilm.engine.IoParams
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.SpektraEngine
import com.spectrafilm.engine.SpektraParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        val ctx = LocalContext.current.applicationContext
        val scope = lifecycleScope

        var engine by remember { mutableStateOf<SpektraEngine?>(null) }
        var profiles by remember { mutableStateOf<List<String>>(emptyList()) }

        // --- parameter state ---
        var film by remember { mutableStateOf("kodak_portra_400") }
        var print by remember { mutableStateOf("kodak_portra_endura") }
        var scanFilm by remember { mutableStateOf(true) }
        var exposureEv by remember { mutableFloatStateOf(0f) }
        var outputCs by remember { mutableStateOf(ColorSpace.SRGB) }
        var grainOn by remember { mutableStateOf(true) }
        var halationOn by remember { mutableStateOf(true) }
        var dirCouplersOn by remember { mutableStateOf(true) }
        var glareOn by remember { mutableStateOf(true) }
        var exportFormat by remember { mutableStateOf(ExportFormat.PNG) }

        // --- image / result state ---
        var pickedUri by remember { mutableStateOf<Uri?>(null) }
        var rendered by remember { mutableStateOf<Bitmap?>(null) }
        var status by remember { mutableStateOf("initializing…") }
        var busy by remember { mutableStateOf(false) }

        // One-time engine init: extract bundled assets, open the engine.
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val dir = extractAssets(ctx)
                val e = SpektraEngine(dir.absolutePath)
                val list = runCatching { e.listProfiles() }.getOrDefault(emptyList())
                withContext(Dispatchers.Main) {
                    engine = e
                    profiles = list
                    status = "ready · ${list.size} profiles"
                }
            }
        }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                pickedUri = uri
                status = "image selected"
            }
        }

        // Film/print profile lists default to the current selection when assets aren't loaded yet.
        val filmOptions = profiles.ifEmpty { listOf(film) }
        val printOptions = profiles.ifEmpty { listOf(print) }

        Column(
            Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("SpectraFilm", style = MaterialTheme.typography.headlineMedium)
            Text(status, style = MaterialTheme.typography.bodySmall)

            // --- source image ---
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) { Text("Pick photo") }
                OutlinedButton(onClick = {
                    pickedUri = null
                    status = "using demo image"
                }) { Text("Use demo image") }
            }
            Text(
                if (pickedUri != null) "Source: picked photo" else "Source: synthetic demo image",
                style = MaterialTheme.typography.bodySmall
            )

            HorizontalDivider()

            // --- profiles ---
            ProfileDropdown("Film", film, filmOptions) { film = it }
            ProfileDropdown("Print paper", print, printOptions) { print = it }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = scanFilm, onCheckedChange = { scanFilm = it })
                Spacer(Modifier.width(8.dp))
                Text(if (scanFilm) "Scan negative (skip print)" else "Full negative → print → scan")
            }

            // --- output color space ---
            EnumDropdown(
                label = "Output color space",
                selected = outputCs,
                options = ColorSpace.entries,
                display = { it.name },
                onSelect = { outputCs = it }
            )

            // --- exposure ---
            Text("Exposure: %+.1f EV".format(exposureEv))
            Slider(value = exposureEv, onValueChange = { exposureEv = it }, valueRange = -3f..3f)

            HorizontalDivider()

            // --- film rendering toggles ---
            Text("Film rendering", style = MaterialTheme.typography.titleSmall)
            ToggleRow("Grain", grainOn) { grainOn = it }
            ToggleRow("Halation", halationOn) { halationOn = it }
            ToggleRow("DIR couplers", dirCouplersOn) { dirCouplersOn = it }
            ToggleRow("Glare", glareOn) { glareOn = it }

            HorizontalDivider()

            // --- render ---
            Button(
                onClick = {
                    val e = engine ?: return@Button
                    busy = true; status = "rendering…"
                    val uri = pickedUri
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.Default) {
                                val image: LinearImage = if (uri != null) {
                                    decodeToLinearProPhoto(ctx, uri)
                                } else {
                                    syntheticLinearImage(256)
                                }
                                val params = SpektraParams(
                                    filmProfile = film,
                                    printProfile = print,
                                    io = IoParams(
                                        scanFilm = scanFilm,
                                        outputColorSpace = outputCs,
                                    ),
                                    camera = CameraParams(
                                        exposureCompensationEv = exposureEv,
                                        autoExposure = false,
                                    ),
                                    filmRender = FilmRenderingParams(
                                        grain = GrainParams(active = grainOn),
                                        halation = HalationParams(active = halationOn),
                                        dirCouplers = DirCouplersParams(active = dirCouplersOn),
                                        glare = GlareParams(active = glareOn),
                                    ),
                                )
                                val res = e.simulate(image, params)
                                simResultToBitmap(res.data, res.width, res.height)
                            }
                        }
                        result.onSuccess { bmp ->
                            rendered = bmp; busy = false; status = "done"
                        }.onFailure { t ->
                            busy = false; status = "error: ${t.message}"
                        }
                    }
                },
                enabled = engine != null && !busy
            ) { Text(if (busy) "Rendering…" else "Render") }

            rendered?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "result",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth()
                )

                // --- export ---
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EnumDropdown(
                        label = "Format",
                        selected = exportFormat,
                        options = ExportFormat.entries.toList(),
                        display = { it.display },
                        onSelect = { exportFormat = it },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                val saved = runCatching {
                                    withContext(Dispatchers.IO) {
                                        saveToGallery(ctx, bmp, exportFormat)
                                    }
                                }
                                val msg = if (saved.isSuccess) {
                                    "Saved to Pictures/SpectraFilm"
                                } else {
                                    "Export failed: ${saved.exceptionOrNull()?.message}"
                                }
                                status = msg
                                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    ) { Text("Save to gallery") }
                }
            }

            Text(
                "Film modeling powered by spektrafilm (GPLv3). Pick a photo or render the " +
                    "synthetic demo image; RAW/DNG import is a separate workstream.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    @Composable
    private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ProfileDropdown(
        label: String, selected: String, options: List<String>, onSelect: (String) -> Unit
    ) {
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selected, onValueChange = {}, readOnly = true, label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(opt); expanded = false })
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun <T> EnumDropdown(
        label: String,
        selected: T,
        options: List<T>,
        display: (T) -> String,
        onSelect: (T) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = modifier,
        ) {
            OutlinedTextField(
                value = display(selected), onValueChange = {}, readOnly = true, label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(display(opt)) },
                        onClick = { onSelect(opt); expanded = false }
                    )
                }
            }
        }
    }
}
