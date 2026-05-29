/*
 * SpectraFilm for Android — app entry. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.SpektraEngine
import com.spectrafilm.engine.SpektraParams
import com.spectrafilm.engine.IoParams
import com.spectrafilm.engine.CameraParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        val ctx = applicationContext
        var engine by remember { mutableStateOf<SpektraEngine?>(null) }
        var profiles by remember { mutableStateOf<List<String>>(emptyList()) }
        var film by remember { mutableStateOf("kodak_portra_400") }
        var print by remember { mutableStateOf("kodak_portra_endura") }
        var scanFilm by remember { mutableStateOf(true) }
        var exposureEv by remember { mutableFloatStateOf(0f) }
        var status by remember { mutableStateOf("initializing…") }
        var rendered by remember { mutableStateOf<Bitmap?>(null) }
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

        Column(
            Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("SpectraFilm", style = MaterialTheme.typography.headlineMedium)
            Text(status, style = MaterialTheme.typography.bodySmall)

            ProfileDropdown("Film", film, profiles.ifEmpty { listOf(film) }) { film = it }
            ProfileDropdown("Print paper", print, profiles.ifEmpty { listOf(print) }) { print = it }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = scanFilm, onCheckedChange = { scanFilm = it })
                Spacer(Modifier.width(8.dp))
                Text(if (scanFilm) "Scan negative (skip print)" else "Full negative → print → scan")
            }

            Text("Exposure: %+.1f EV".format(exposureEv))
            Slider(value = exposureEv, onValueChange = { exposureEv = it }, valueRange = -3f..3f)

            Button(
                onClick = {
                    val e = engine ?: return@Button
                    busy = true; status = "rendering…"
                    lifecycleScope.launch {
                        val bmp = withContext(Dispatchers.Default) {
                            val (buf, w, h) = syntheticLinearImage(256)
                            val params = SpektraParams(
                                filmProfile = film,
                                printProfile = print,
                                io = IoParams(scanFilm = scanFilm),
                                camera = CameraParams(exposureCompensationEv = exposureEv, autoExposure = false),
                            )
                            val res = e.simulate(LinearImage(buf, w, h), params)
                            simResultToBitmap(res.data, res.width, res.height)
                        }
                        rendered = bmp; busy = false; status = "done"
                    }
                },
                enabled = engine != null && !busy
            ) { Text(if (busy) "Rendering…" else "Render") }

            rendered?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "result",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
            }
            Text(
                "Film modeling powered by spektrafilm (GPLv3). " +
                    "Demo uses a synthetic scene-linear test image; RAW/DNG import is the next step.",
                style = MaterialTheme.typography.bodySmall
            )
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
}

/** Recursively copy the bundled assets/spektra tree to filesDir/spektra; returns that dir. */
private fun extractAssets(ctx: Context): File {
    val out = File(ctx.filesDir, "spektra")
    val am = ctx.assets
    fun copyDir(rel: String) {
        val entries = am.list(rel) ?: emptyArray()
        if (entries.isEmpty()) { // it's a file
            File(out.parentFile, rel).apply { parentFile?.mkdirs() }
            am.open(rel).use { input -> File(ctx.filesDir, rel).outputStream().use { input.copyTo(it) } }
            return
        }
        File(ctx.filesDir, rel).mkdirs()
        for (e in entries) copyDir("$rel/$e")
    }
    if (!out.exists()) copyDir("spektra")
    return out
}

/** A deterministic scene-linear ProPhoto-ish test image: horizontal exposure ramp + RGB bands. */
private fun syntheticLinearImage(size: Int): Triple<ByteBuffer, Int, Int> {
    val buf = ByteBuffer.allocateDirect(size * size * 3 * 4).order(ByteOrder.nativeOrder())
    val f = buf.asFloatBuffer()
    for (y in 0 until size) {
        val band = (y * 4 / size) // 0..3
        for (x in 0 until size) {
            val t = x.toFloat() / (size - 1)        // 0..1 exposure ramp
            val v = 0.02f + t * t * 0.9f            // perceptually spread linear values
            val (r, g, b) = when (band) {
                0 -> Triple(v, v, v)                // neutral
                1 -> Triple(v, v * 0.25f, v * 0.25f)// reds
                2 -> Triple(v * 0.25f, v, v * 0.25f)// greens
                else -> Triple(v * 0.25f, v * 0.25f, v) // blues
            }
            val i = (y * size + x) * 3
            f.put(i, r); f.put(i + 1, g); f.put(i + 2, b)
        }
    }
    return Triple(buf, size, size)
}

/** Display-referred float RGB (0..1, already CCTF-encoded by the engine) → ARGB_8888 bitmap. */
private fun simResultToBitmap(data: ByteBuffer, w: Int, h: Int): Bitmap {
    val f = data.order(ByteOrder.nativeOrder()).asFloatBuffer()
    val px = IntArray(w * h)
    for (p in 0 until w * h) {
        val i = p * 3
        val r = (min(1f, maxOf(0f, f.get(i))) * 255f + 0.5f).toInt()
        val g = (min(1f, maxOf(0f, f.get(i + 1))) * 255f + 0.5f).toInt()
        val b = (min(1f, maxOf(0f, f.get(i + 2))) * 255f + 0.5f).toInt()
        px[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}
