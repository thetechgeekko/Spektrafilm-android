/*
 * Spektrafilm for Android — "What's new" / update dialog. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Shown over the whole app whenever [UpdateUiState.offer] is set: by the launch-time auto
 * check or by Settings' "Check for updates". Lists the release notes and offers Update
 * (download + system install confirmation), Later, and Skip this version.
 */
package com.spectrafilm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun UpdateDialog(offer: UpdateOffer, onAction: (UpdateAction) -> Unit) {
    val stage = offer.stage
    val busy = stage is UpdateStage.Downloading || stage is UpdateStage.Verifying
    AlertDialog(
        // While bytes are moving, an outside tap must not silently cancel the download.
        onDismissRequest = { if (!busy) onAction(UpdateAction.Later) },
        properties = DialogProperties(dismissOnClickOutside = !busy),
        title = {
            Text(
                stringResource(R.string.update_dialog_title, offer.info.latestTag),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.update_dialog_current, offer.info.currentVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ReleaseNotesView(
                    offer.info.notes,
                    Modifier.heightIn(max = 360.dp).fillMaxWidth(),
                )
                StageLine(stage)
            }
        },
        confirmButton = {
            when {
                stage is UpdateStage.Downloading || stage is UpdateStage.Verifying -> Unit
                stage is UpdateStage.Installing -> Unit
                stage is UpdateStage.Failed && stage.problem == UpdateProblem.DOWNLOAD_FAILED ->
                    Button(onClick = { onAction(UpdateAction.Install) }) {
                        Text(stringResource(R.string.update_try_again))
                    }
                stage is UpdateStage.Failed || !offer.canInstallInApp ->
                    Button(onClick = { onAction(UpdateAction.OpenReleasePage) }) {
                        Text(stringResource(R.string.update_open_release_page))
                    }
                else -> Button(onClick = { onAction(UpdateAction.Install) }) {
                    Text(stringResource(R.string.update_now))
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!busy && stage !is UpdateStage.Installing) {
                    TextButton(onClick = { onAction(UpdateAction.SkipVersion) }) {
                        Text(stringResource(R.string.update_skip_version))
                    }
                }
                TextButton(onClick = { onAction(UpdateAction.Later) }) {
                    Text(stringResource(if (busy) R.string.update_cancel else R.string.update_later))
                }
            }
        },
    )
}

@Composable
private fun StageLine(stage: UpdateStage) {
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (stage) {
        UpdateStage.Offer -> Unit
        is UpdateStage.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(progress = { stage.fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.update_downloading, (stage.fraction * 100).toInt()),
                style = MaterialTheme.typography.bodySmall,
                modifier = live,
            )
        }
        UpdateStage.Verifying -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.update_verifying), style = MaterialTheme.typography.bodySmall, modifier = live)
        }
        UpdateStage.Installing ->
            Text(stringResource(R.string.update_installing), style = MaterialTheme.typography.bodySmall, modifier = live)
        is UpdateStage.Failed -> Text(
            stringResource(
                when (stage.problem) {
                    UpdateProblem.DOWNLOAD_FAILED -> R.string.update_failed_download
                    UpdateProblem.SIGNER_MISMATCH -> R.string.update_failed_signer
                    UpdateProblem.VERIFY_FAILED -> R.string.update_failed_verify
                    UpdateProblem.INSTALL_FAILED -> R.string.update_failed_install
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = live,
        )
    }
}

@Composable
private fun ReleaseNotesView(notes: String, modifier: Modifier = Modifier) {
    val blocks = remember(notes) { ReleaseNotes.parse(notes) }
    if (blocks.isEmpty()) {
        Text(stringResource(R.string.update_no_notes), style = MaterialTheme.typography.bodyMedium, modifier = modifier)
        return
    }
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (block in blocks) {
            when (block) {
                is NoteBlock.Heading -> Text(
                    annotated(block.spans, codeBackground),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp).semantics { heading() },
                )
                is NoteBlock.Bullet -> Row(Modifier.padding(start = (block.level * 12).dp)) {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                    Text(annotated(block.spans, codeBackground), style = MaterialTheme.typography.bodyMedium)
                }
                is NoteBlock.Paragraph ->
                    Text(annotated(block.spans, codeBackground), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun annotated(spans: List<NoteSpan>, codeBackground: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        for (span in spans) {
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.SemiBold else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) codeBackground else androidx.compose.ui.graphics.Color.Unspecified,
            )
            withStyle(style) { append(span.text) }
        }
    }
