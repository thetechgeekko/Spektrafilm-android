/*
 * Spektrafilm for Android — editor messages. GPLv3.
 * Film modeling powered by spektrafilm (GPLv3).
 *
 * One way to tell the user something happened in the editor: a Snackbar on the editor's
 * SnackbarHost, never a Toast (MAD audit fix 3). Messages say what happened and what to do;
 * a raw exception message goes to the diagnostics log, not to the screen.
 */
package com.spectrafilm.app

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shows [message] on [host], replacing whatever snackbar is up. With [actionLabel] the
 * snackbar carries that action (for example Retry) and [onAction] runs when it is tapped.
 * Launched in [this] scope, which should be the screen's scope rather than an effect's, so
 * the message is not cancelled when the effect that raised it restarts.
 */
internal fun CoroutineScope.showEditorMessage(
    host: SnackbarHostState,
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    launch {
        host.currentSnackbarData?.dismiss()
        val result = host.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            withDismissAction = actionLabel == null,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) onAction()
    }
}
