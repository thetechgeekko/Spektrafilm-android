/*
 * Spektrafilm for Android — Diagnostics screen. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Shows the last persisted crash (if any) and an on-demand logcat snapshot of this
 * process, with copy + share. No network, no permission; for self-service bug reports.
 */
package com.spectrafilm.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun DiagnosticsScreen() {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var crash by remember { mutableStateOf(Diagnostics.lastCrash(ctx)) }
    var log by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("App: ${Diagnostics.appVersion(ctx)}", style = MaterialTheme.typography.bodyMedium)

        // --- last crash ---
        Text("Last crash", style = MaterialTheme.typography.titleMedium)
        if (crash == null) {
            Text("No crash recorded.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            MonoBlock(crash!!)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(crash!!)) }) { Text("Copy") }
                OutlinedButton(onClick = {
                    Diagnostics.clearLastCrash(ctx); crash = null
                }) { Text("Clear") }
            }
        }

        // --- logcat snapshot ---
        Text("Logcat snapshot", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { log = Diagnostics.captureLogcat() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (log == null) "Capture logcat" else "Re-capture logcat")
        }
        log?.let { MonoBlock(it) }

        // --- share full report ---
        Button(
            onClick = { Diagnostics.share(ctx, Diagnostics.buildReport(ctx)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Share diagnostics report") }
    }
}

@Composable
private fun MonoBlock(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        )
    }
}
