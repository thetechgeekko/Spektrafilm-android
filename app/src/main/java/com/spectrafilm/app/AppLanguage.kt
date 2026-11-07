/*
 * Spektrafilm for Android — per-app language. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The app's language is owned by the platform, not by us. On Android 13+ the system keeps a
 * per-app language (Settings > Apps > Spektrafilm > Language) and lists the locales from the
 * LocaleConfig that AGP generates from our values-* folders (`generateLocaleConfig` in
 * app/build.gradle.kts). We therefore store nothing and need no AppCompat: Settings shows the
 * current language and opens the system page. Older Android simply follows the phone language.
 *
 * The row only appears once the app actually ships more than one language; offering a picker
 * with a single entry is a dead end. Idea credit: two community forks (q8987308-design and
 * hwx959) each translated the whole UI into Simplified Chinese, which is what showed the need.
 */
package com.spectrafilm.app

import android.app.LocaleConfig
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import java.util.Locale

/** Whether to offer a language choice: needs the platform page (API 33+) and 2+ shipped locales. */
internal fun shouldOfferLanguageChoice(sdkInt: Int, shippedLocaleCount: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && shippedLocaleCount > 1

/** True when Settings should show the "App language" row on this device. */
internal fun appLanguageChoiceAvailable(context: Context): Boolean {
    // Inline SDK check (not only inside shouldOfferLanguageChoice) so lint's NewApi sees it.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val shipped = runCatching { LocaleConfig(context).supportedLocales?.size() ?: 0 }.getOrDefault(0)
    return shouldOfferLanguageChoice(Build.VERSION.SDK_INT, shipped)
}

/** The language the app is showing now, in that language's own name (e.g. "中文 (中国)"). */
internal fun currentAppLanguageLabel(context: Context): String {
    val locale: Locale = context.resources.configuration.locales.get(0) ?: Locale.getDefault()
    return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}

/** The system per-app language page for this app. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun appLanguageSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
