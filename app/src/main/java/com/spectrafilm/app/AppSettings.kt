/*
 * Spektrafilm for Android — persisted app settings. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A thin Preferences DataStore store (#276; migrated from SharedPreferences on first use)
 * for app-level preferences that are NOT part of the per-image SpektraParams tree: the default output color space, preview resolution,
 * default film/print profiles, export format + quality, theme, and the onboarding
 * "seen" flag. ParamsState owns the render parameters; this owns the app's defaults and
 * chrome. Reads come from an in-memory snapshot; writes go to it at once and to disk in order
 * (see [SettingsDataStore]).
 *
 * applyDefaultsTo() seeds a fresh ParamsState with the user's saved defaults on launch.
 */
package com.spectrafilm.app

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.spectrafilm.engine.ColorSpace
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** App theme preference. [display] is the stable English name (logs); the UI shows [labelRes]. */
enum class ThemeMode(val display: String, @StringRes val labelRes: Int) {
    SYSTEM("System default", R.string.theme_mode_system),
    LIGHT("Light", R.string.theme_mode_light),
    DARK("Dark", R.string.theme_mode_dark),
}

/**
 * Persisted application settings. Construct lazily via [AppSettings.from] (which caches
 * a single instance per process). All setters write through to [SettingsDataStore].
 */
class AppSettings private constructor(
    private val store: SettingsDataStore,
    /** Only the dev-only big-cores switch and its legacy key; see [bigCores]. */
    private val experimentPrefs: SharedPreferences,
    private val appContext: Context,
) : SettingsStore {

    var seenOnboarding: Boolean
        get() = store[KEY_SEEN_ONBOARDING] ?: false
        set(v) { store.edit { it[KEY_SEEN_ONBOARDING] = v } }

    /**
     * Whether the app has already asked for POST_NOTIFICATIONS (at the first Export tap). It is
     * asked once; after that the system setting is the user's to change, and the app never
     * prompts again on its own.
     */
    var askedNotificationPermission: Boolean
        get() = store[KEY_ASKED_NOTIFICATIONS] ?: false
        set(v) { store.edit { it[KEY_ASKED_NOTIFICATIONS] = v } }

    /** Whether the one-time editor coach-mark overlay (tap-to-edit / compare / 100%) was shown. */
    var seenEditorCoach: Boolean
        get() = store[KEY_SEEN_EDITOR_COACH] ?: false
        set(v) { store.edit { it[KEY_SEEN_EDITOR_COACH] = v } }

    /**
     * GPU LUT preview (default OFF — opt-in). When on, the FIT view renders the current
     * look instantly by GPU-sampling the engine's baked 3D LUT instead of a ~1s CPU render
     * per edit. This loupe is a separate approximation from the Vulkan engine route (how the
     * app renders and exports since 294d3dc); it never affects export.
     *
     * DEFAULT OFF: the current GLSurfaceView-based surface churns (continuous buffer
     * re-allocation / dropped frames) when the editor's preview area resizes during panel
     * animations, and can grow over the bottom controls — observed on SM-S948W as a frame
     * hang plus a hidden export button. Being reimplemented on a resize-friendly surface
     * (TextureView) before it can be safe to default on. Enable here to try it meanwhile.
     */
    //
    // NOT part of "the app runs on the GPU". This switches the preview SURFACE
    // (LutGpuPreview's GLSurfaceView), not any compute: the spectral engine's
    // GPU route is gpuEngine / gpuExportEngine above. It stays OFF and its
    // toggle was removed with theirs, because defaulting it on would ship the
    // documented frame hang AND the hidden export button below -- which would
    // reach users as "I cannot export", the very report this release is meant
    // to answer. Restore the toggle when the TextureView rewrite lands.
    var gpuPreview: Boolean
        get() = false
        set(v) { store.edit { it[KEY_GPU_PREVIEW] = v } }

    /**
     * Pin the render pool to the device's performance cores.
     *
     * MEASURED A REGRESSION ON A WHOLE RENDER. Kept as a pref-only experiment
     * switch; there is deliberately no Settings UI for it.
     *
     * The microbenchmark said 1.51x (SM-S948W, halation path, 1024x768: 71.86 ms
     * unpinned vs 47.58 ms pinned), and capping the pool without pinning did NOT
     * reproduce it, so core placement really was the mechanism THERE. It did not
     * survive the end-to-end pipeline. A 12.5 MP export on the same device:
     *
     * ```
     *   OFF  median 14476 ms          ON  median 20458 ms      1.41x SLOWER
     *   grain          3620 -> 8612 ms  (2.38x) = 83% of the regression
     *   filming_expose  177 ->  391 ms  (2.20x)
     *   dir_couplers   1349 -> 1466 ms  (1.09x) — essentially immune
     *   halation       2055 -> 2159 ms  (1.05x) — essentially immune
     * ```
     *
     * Why: pinning also caps the pool to the big-core count (parallel.cpp), so ON is
     * 2 workers on 2 prime cores against OFF's 8 workers across all 8 — 9.48 GHz of
     * aggregate clock against 31.26 GHz. A 1.31x per-core clock edge cannot cover a
     * 3.3x compute deficit. The microbenchmark was one spatial filter on a small
     * fixture, where thread-spawn overhead dominates and 2 workers legitimately beat
     * 8; a full-resolution render is nothing like that.
     *
     * Output is unaffected either way — affinity moves only WHERE a chunk runs, and
     * every worker count is byte-identical under the parity gate's thread-invariance
     * contract. That half held up. Default OFF, and it should stay off.
     */
    var bigCores: Boolean
        get() = experimentPrefs.getBoolean(KEY_BIG_CORES, false)
        set(v) { experimentPrefs.edit().putBoolean(KEY_BIG_CORES, v).apply() }

    /**
     * One-time cleanup of the ORIGINAL `big_cores` key.
     *
     * When the Settings row was removed, nothing wrote that key any more but
     * EngineHolder still read and honoured it — so anyone who had ever flipped the
     * switch was left permanently 1.41x slow, with no UI to discover it and no way
     * to turn it off. The key above is deliberately a NEW name, so a stale value can
     * never be honoured again; this drops the old one so it does not linger.
     */
    fun clearLegacyBigCores() {
        if (experimentPrefs.contains(LEGACY_KEY_BIG_CORES)) {
            experimentPrefs.edit().remove(LEGACY_KEY_BIG_CORES).apply()
            Diag.i("cleared legacy big_cores pref (feature was measured 1.41x slower)")
        }
    }

    /**
     * GPU ENGINE (Vulkan, GPU M1 #146) — distinct from [gpuPreview] (the GLES LUT
     * loupe overlay above): the film simulation itself runs on the GPU for
     * interactive previews (~2e-6 from the CPU chain, tighter than the preview's
     * 3D LUT at ~5e-5 — see PR #145). ON for every render since 294d3dc and the
     * user-facing toggle is gone. The route is gated by a one-time on-device
     * self-check and falls back to the CPU engine automatically, so "GPU" means
     * "GPU where this device proves it agrees with the CPU", not "GPU
     * unconditionally". The pref key is kept and written so an existing install
     * that had it OFF is not stuck there; the getter ignores it.
     */
    var gpuEngine: Boolean
        get() = true
        set(v) { store.edit { it[KEY_GPU_ENGINE] = v } }

    /**
     * GPU export (Vulkan, #154 / #149): the film simulation runs on the GPU for
     * FULL-RESOLUTION exports. ON for every export since 294d3dc and the
     * user-facing toggle is gone; the same on-device self-check gates it and the
     * CPU engine stays the ground truth and automatic fallback. Two things this
     * changes for every user, both deliberate:
     *   - Grain and viewing glare are re-drawn with the faster generator (#180),
     *     so the speckle pattern differs from the CPU engine. Same amount and
     *     character; repeat exports on one device stay identical.
     *   - A default export is therefore no longer byte-identical to the CPU
     *     path. It stays inside the Fast GPU tolerance, and every colour stage
     *     is self-checked against the CPU engine on the device with automatic
     *     fallback, but the strict exact contract belongs to the CPU engine
     *     alone rather than to what ships by default.
     * The pref key is kept and written; the getter ignores it.
     */
    var gpuExportEngine: Boolean
        get() = true
        set(v) { store.edit { it[KEY_GPU_EXPORT] = v } }

    /**
     * PROGRESSIVE LADDER rung (perf lab). Longest edge of the live DRAFT render that
     * runs while a slider is being dragged, before the crisp settle pass lands.
     *
     * Was the fixed constant [DRAFT_RENDER_MAX_PX]; made a setting so the coarse/fine
     * trade can be swept on a real device instead of guessed. Lower tracks the finger
     * more closely; higher makes the live frame a better preview of the settle result.
     * Clamped to a band where the step-down is still meaningful — below 128 the frame
     * is too coarse to judge a colour edit by, and above 512 the draft costs enough
     * that it stops being a draft.
     *
     * Defaults to [DRAFT_RENDER_MAX_PX], so an untouched install renders as before.
     */
    override var draftRenderMaxPx: Int
        get() = (store[KEY_DRAFT_MAX_PX] ?: DRAFT_RENDER_MAX_PX).coerceIn(128, 512)
        set(v) { store.edit { it[KEY_DRAFT_MAX_PX] = v.coerceIn(128, 512) } }

    /**
     * Auto draft size (#299): start from the device tier and let [DraftEdgeCalibrator]
     * move it from measured draft times. Defaults ON for installs that never chose a
     * draft size; an install that already set one keeps it (auto off) until the user
     * turns Auto on.
     */
    override var draftRenderAuto: Boolean
        get() = store[KEY_DRAFT_AUTO] ?: (KEY_DRAFT_MAX_PX !in store)
        set(v) { store.edit { it[KEY_DRAFT_AUTO] = v } }

    /** Draft edge to render at right now: calibrated when Auto, else the manual value. */
    fun effectiveDraftEdge(): Int =
        if (draftRenderAuto) AdaptivePreview.calibrator(appContext).edge else draftRenderMaxPx

    /** Feed a completed draft's timing to the calibrator (no-op when Auto is off). */
    fun recordDraftTiming(edge: Int, elapsedMs: Long) {
        Diag.i("render mode=draft edge=$edge ${elapsedMs}ms auto=$draftRenderAuto")
        if (!draftRenderAuto) return
        val before = AdaptivePreview.calibrator(appContext).edge
        val after = AdaptivePreview.calibrator(appContext).record(edge, elapsedMs)
        if (after != before) Diag.i("auto draft edge $before -> $after (draft ${elapsedMs}ms at $edge)")
    }

    /**
     * The folder the Library shows (an OpenDocumentTree URI), or null when none was chosen.
     * Its persisted read grant is kept by [SourceAccessCoordinator]'s reconciliation.
     */
    var libraryTreeUri: String?
        get() = store[KEY_LIBRARY_TREE]
        set(v) {
            store.edit { if (v == null) it.remove(KEY_LIBRARY_TREE) else it[KEY_LIBRARY_TREE] = v }
        }

    /** The Library's sort and filter, as [LibraryQuery.encode]; null = newest first, all frames. */
    var libraryQuery: String?
        get() = store[KEY_LIBRARY_QUERY]
        set(v) {
            store.edit { if (v == null) it.remove(KEY_LIBRARY_QUERY) else it[KEY_LIBRARY_QUERY] = v }
        }

    override var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(store[KEY_THEME] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
        set(v) { store.edit { it[KEY_THEME] = v.name } }

    /**
     * Material You: derive the palette from the user's wallpaper (API 31+). Defaults ON, which is
     * the platform's own default behaviour; the stored value is still honoured on devices that
     * cannot do it, so toggling it on an older phone is remembered rather than silently discarded.
     */
    override var dynamicColor: Boolean
        get() = store[KEY_DYNAMIC_COLOR] ?: true
        set(v) { store.edit { it[KEY_DYNAMIC_COLOR] = v } }

    /**
     * Full screen editing: the editor hides the status and navigation bars so the photo gets
     * the whole display; a swipe in from an edge shows them for a moment. Defaults ON.
     */
    override var fullScreenEditing: Boolean
        get() = store[KEY_FULL_SCREEN] ?: true
        set(v) { store.edit { it[KEY_FULL_SCREEN] = v } }

    override var defaultOutputColorSpace: ColorSpace
        get() = runCatching { ColorSpace.valueOf(store[KEY_OUTPUT_CS] ?: ColorSpace.SRGB.name) }
            .getOrDefault(ColorSpace.SRGB)
        set(v) { store.edit { it[KEY_OUTPUT_CS] = v.name } }

    override var previewMaxSize: Int
        get() = store[KEY_PREVIEW_MAX] ?: 640
        set(v) { store.edit { it[KEY_PREVIEW_MAX] = v } }

    /**
     * Auto preview size (#299): pick the settle-preview edge from device RAM, CPU and
     * screen ([autoPreviewEdges]). Defaults ON for installs that never chose a size; an
     * install that already set one keeps it (auto off) until the user turns Auto on.
     */
    override var previewMaxSizeAuto: Boolean
        get() = store[KEY_PREVIEW_AUTO] ?: (KEY_PREVIEW_MAX !in store)
        set(v) { store.edit { it[KEY_PREVIEW_AUTO] = v } }

    /** The device's auto edges (cached per process). */
    internal fun autoPreviewEdges(): AutoPreviewEdges = AdaptivePreview.edges(appContext)

    /** Preview edge a new editor session starts with. */
    fun effectivePreviewMaxSize(): Int =
        if (previewMaxSizeAuto) autoPreviewEdges().previewEdge else previewMaxSize

    override var defaultFilmProfile: String
        get() = store[KEY_FILM] ?: ""
        set(v) { store.edit { it[KEY_FILM] = v } }

    override var defaultPrintProfile: String
        get() = store[KEY_PRINT] ?: ""
        set(v) { store.edit { it[KEY_PRINT] = v } }

    override var exportFormat: ExportFormat
        get() = runCatching { ExportFormat.valueOf(store[KEY_EXPORT_FMT] ?: ExportFormat.PNG.name) }
            .getOrDefault(ExportFormat.PNG)
        set(v) { store.edit { it[KEY_EXPORT_FMT] = v.name } }

    override var exportQuality: Int
        get() = (store[KEY_EXPORT_Q] ?: 95).coerceIn(1, 100)
        set(v) { store.edit { it[KEY_EXPORT_Q] = v.coerceIn(1, 100) } }

    /**
     * Whether to preserve GPS/location EXIF tags when copying source metadata into
     * exported images. Defaults to FALSE (strip location) — the privacy-safe default
     * for a shareable photo app (security review F3). All other EXIF (camera/lens/
     * exposure/date) is always copied; this toggle only governs the GPS block.
     */
    override var exportKeepGps: Boolean
        get() = store[KEY_EXPORT_KEEP_GPS] ?: false
        set(v) { store.edit { it[KEY_EXPORT_KEEP_GPS] = v } }

    override var exportBorder: FilmBorder
        get() = FilmBorder.parse(store[KEY_EXPORT_BORDER])
        set(v) { store.edit { it[KEY_EXPORT_BORDER] = v.name } }

    override var exportFrame: FrameLook
        get() = runCatching { FrameLook.fromJson(store[KEY_EXPORT_FRAME]?.let(::JSONObject)) }
            .getOrDefault(FrameLook())
        set(v) { store.edit { it[KEY_EXPORT_FRAME] = v.toJson().toString() } }

    /** Whether saved settings have been read; see [SettingsDataStore.awaitLoaded]. */
    val isLoaded: Boolean get() = store.isLoaded

    /** Suspends until saved settings have been read (the splash screen waits on this). */
    suspend fun awaitLoaded() = store.awaitLoaded()

    /** Waits up to [timeoutMs] for queued writes to reach disk; see [SettingsDataStore.flush]. */
    fun flush(timeoutMs: Long): Boolean = store.flush(timeoutMs)

    /**
     * Seed a freshly constructed [state] with the saved app defaults. Profile defaults
     * are only applied when they appear in [availableProfiles] (so a stale id can't make
     * the picker show a profile the engine doesn't know).
     */
    fun applyDefaultsTo(state: ParamsState, availableProfiles: List<String>) {
        state.outputColorSpace = defaultOutputColorSpace
        state.previewMaxSize = effectivePreviewMaxSize()
        state.gpuEngine = gpuEngine
        state.gpuExport = gpuExportEngine
        if (defaultFilmProfile.isNotBlank() && defaultFilmProfile in availableProfiles) {
            state.filmProfile = defaultFilmProfile
        }
        if (defaultPrintProfile.isNotBlank() && defaultPrintProfile in availableProfiles) {
            state.printProfile = defaultPrintProfile
        }
        // A black-and-white default film with a colour default paper would render orange.
        if (SilverPrint.isMismatched(state.filmProfile, state.printProfile) &&
            SilverPrint.MULTIGRADE in availableProfiles
        ) {
            state.printProfile = SilverPrint.MULTIGRADE
        }
    }

    companion object {
        private const val PREFS_NAME = "spectrafilm_settings"
        private val KEY_SEEN_ONBOARDING = booleanPreferencesKey("seen_onboarding")
        private val KEY_SEEN_EDITOR_COACH = booleanPreferencesKey("seen_editor_coach")
        private val KEY_GPU_PREVIEW = booleanPreferencesKey("gpu_preview")
        private val KEY_GPU_ENGINE = booleanPreferencesKey("gpu_engine_preview")
        private val KEY_GPU_EXPORT = booleanPreferencesKey("gpu_engine_export")
        private val KEY_DRAFT_MAX_PX = intPreferencesKey("draft_render_max_px")
        private val KEY_DRAFT_AUTO = booleanPreferencesKey("draft_render_auto")
        // Renamed from "big_cores". The old key must never be read again — see
        // clearLegacyBigCores(). This one exists only so an A/B can still be driven
        // from a shell without a UI; it is not a product setting. Both stay in the
        // SharedPreferences file (not migrated to DataStore) so a shell can still set it.
        private const val KEY_BIG_CORES = "big_cores_experiment"
        private const val LEGACY_KEY_BIG_CORES = "big_cores"
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_LIBRARY_TREE = stringPreferencesKey("library_tree_uri")
        private val KEY_LIBRARY_QUERY = stringPreferencesKey("library_query")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_OUTPUT_CS = stringPreferencesKey("output_color_space")
        private val KEY_PREVIEW_MAX = intPreferencesKey("preview_max_size")
        private val KEY_PREVIEW_AUTO = booleanPreferencesKey("preview_max_size_auto")
        private val KEY_FILM = stringPreferencesKey("default_film_profile")
        private val KEY_PRINT = stringPreferencesKey("default_print_profile")
        private val KEY_EXPORT_FMT = stringPreferencesKey("export_format")
        private val KEY_EXPORT_Q = intPreferencesKey("export_quality")
        private val KEY_EXPORT_KEEP_GPS = booleanPreferencesKey("export_keep_gps")
        // New in DataStore only; never lived in SharedPreferences, so not in MIGRATED_KEYS.
        private val KEY_EXPORT_BORDER = stringPreferencesKey("export_border")
        // New in DataStore only, like KEY_EXPORT_BORDER: not in MIGRATED_KEYS.
        private val KEY_EXPORT_FRAME = stringPreferencesKey("export_frame")
        private val KEY_FULL_SCREEN = booleanPreferencesKey("full_screen_editing")
        // New in DataStore only, like KEY_EXPORT_BORDER: not in MIGRATED_KEYS.
        private val KEY_ASKED_NOTIFICATIONS = booleanPreferencesKey("asked_notification_permission")

        /** Every key that moves from SharedPreferences to DataStore (all but big cores). */
        private val MIGRATED_KEYS: Set<String> = setOf(
            KEY_SEEN_ONBOARDING, KEY_SEEN_EDITOR_COACH, KEY_GPU_PREVIEW, KEY_GPU_ENGINE,
            KEY_GPU_EXPORT, KEY_DRAFT_MAX_PX, KEY_DRAFT_AUTO, KEY_THEME, KEY_DYNAMIC_COLOR,
            KEY_OUTPUT_CS, KEY_PREVIEW_MAX, KEY_PREVIEW_AUTO, KEY_FILM, KEY_PRINT,
            KEY_EXPORT_FMT, KEY_EXPORT_Q, KEY_EXPORT_KEEP_GPS,
        ).mapTo(HashSet()) { it.name }

        @Volatile private var instance: AppSettings? = null

        fun from(ctx: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(
                    SettingsDataStore.create(
                        ctx,
                        PREFS_NAME,
                        MIGRATED_KEYS,
                        CoroutineScope(SupervisorJob() + ProcessDispatchers.io),
                    ),
                    ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                    ctx.applicationContext,
                ).also { instance = it }
            }
    }
}
