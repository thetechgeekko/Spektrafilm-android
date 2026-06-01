/*
 * Spektrafilm for Android — editor coach marks. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A one-time, dismissible overlay shown the first time the editor is opened (after
 * onboarding). It surfaces the non-obvious gestures — tap-a-category, before/after
 * compare, tap-to-inspect-at-100%, pinch-zoom — the way Lightroom's coach marks do,
 * so the gesture-driven editor is discoverable without a manual. Gated by
 * AppSettings.seenEditorCoach; shown exactly once.
 */
package com.spectrafilm.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Full-screen one-time coach-mark overlay. [onDismiss] persists the "seen" flag and
 * removes the overlay. Tapping the scrim or the button both dismiss.
 */
@Composable
fun EditorCoachOverlay(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            // Tap anywhere on the scrim to dismiss (no ripple — it's a backdrop).
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(28.dp),
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Quick tips",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                CoachTip("Tap a category", "Pick a section in the bottom bar to reveal its sliders.")
                CoachTip("Before / after", "Toggle the compare split to judge an edit against the original.")
                CoachTip("Inspect at 100%", "Tap the preview to check grain and detail at full resolution.")
                CoachTip("Pinch to zoom", "Pinch and drag the preview to inspect any area.")
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) { Text("Got it") }
            }
        }
    }
}

@Composable
private fun CoachTip(title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 6.dp, end = 12.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
