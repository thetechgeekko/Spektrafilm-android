/*
 * Spektrafilm for Android — small app-wide helpers. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Moved out of MainActivity.kt (#273).
 */
package com.spectrafilm.app

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun localConfigurationHeightDp(): Int =
    androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp

internal fun Modifier.clickableNoRipple(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this.then(
    Modifier.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
)

/**
 * #162: the one accepted inbound shape — a content:// image from VIEW (data) or SEND
 * (EXTRA_STREAM). Anything else (file:// paths, missing streams, foreign actions) is null
 * and never adopted; the decode route itself stays content-sniffed fail-closed (#190).
 */
internal fun incomingImageUri(intent: Intent?): Uri? = when (intent?.action) {
    Intent.ACTION_VIEW -> intent.data
    Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
    else -> null
}?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
