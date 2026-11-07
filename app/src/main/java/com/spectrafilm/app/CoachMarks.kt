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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
            .background(SpektraBrand.scrim.copy(alpha = 0.72f))
            // Tap anywhere on the scrim to dismiss (no ripple — it's a backdrop).
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = stringResource(R.string.tool_coach_dismiss),
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Drawn as an M3 basic dialog: extra-large (28dp) corners on surfaceContainerHigh, 24dp
        // padding, headline-small title and a text-button action at the end
        // (m3-design-language, components-communication "Dialogs").
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .widthIn(min = 280.dp, max = 560.dp)
                .padding(24.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.tool_coach_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                CoachTip(
                    stringResource(R.string.tool_coach_category_title),
                    stringResource(R.string.tool_coach_category_body),
                )
                CoachTip(
                    stringResource(R.string.tool_coach_compare_title),
                    stringResource(R.string.tool_coach_compare_body),
                )
                CoachTip(
                    stringResource(R.string.tool_coach_inspect_title),
                    stringResource(R.string.tool_coach_inspect_body),
                )
                CoachTip(
                    stringResource(R.string.tool_coach_zoom_title),
                    stringResource(R.string.tool_coach_zoom_body),
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
                ) { Text(stringResource(R.string.tool_coach_got_it)) }
            }
        }
    }
}

@Composable
private fun CoachTip(title: String, body: String) {
    // One TalkBack node per tip (title + body); the bullet is decorative.
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Box(
            Modifier
                .padding(top = 6.dp, end = 12.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
