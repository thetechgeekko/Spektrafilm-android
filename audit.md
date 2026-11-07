# Spektrafilm for Android — MAD audit (2026-11-07)

Standard: the **MAD for Google** skill only (its audit procedure, the §23 audit checklist in
`quality-performance-tooling.md`, the B11 "Photo and video editing" playbook in
`ux-flows-by-category.md`, and its 2026-11-07 version snapshot). No other source was used.

Audited commit: `main` at `55c4522` (after the UI redesign #433–#443, Navigation 3, wider RAW
support #437/#439). PR #444 (upstream items) was not merged at audit time and is not covered.

Method: code reading only. The app was **not run on a device** for this pass, so anything
that needs runtime evidence (startup time, jank, memory, Accessibility Scanner, TalkBack) is
marked "not measured". Versions are compared against the skill's snapshot dated 2026-11-07
(today); they were not re-fetched live, so verify at
https://developer.android.com/jetpack/androidx/versions before bumping.

---

## Scorecard (0–10)

| Area | Score | One-line reason |
|---|---|---|
| UX & flows | **7.0** | Today's redesign follows the B11 photo-editor playbook closely (picker, compare, export sheet, share), but errors reach users as Toasts carrying raw exception text, and there is no splash/first-frame polish. |
| Visual design (M3) | **7.5** | `MaterialExpressiveTheme` + `MotionScheme.expressive()` + dynamic color + reduced-motion scheme is excellent; the non-dynamic fallback is the stock baseline scheme (no brand), and there are zero `@Preview`s to keep it honest. |
| Accessibility | **6.5** | Semantics are thoughtful (labels, state descriptions, RTL and pseudolocale tests), but some clickables are forced to 40 dp and nothing tests the largest font scale. Not measured with Accessibility Scanner/TalkBack. |
| Adaptive & surfaces | **7.0** | Resizable, no orientation lock, fold posture, keyboard shortcuts, drag-and-drop: genuinely good. Breakpoints are hand-rolled instead of window size classes / adaptive scaffolds, and no surfaces beyond the app (shortcuts, widget). |
| Performance | **5.5** | Native + GPU engine is fast by design, but the baseline profile is a "compile everything" wildcard, there is no Macrobenchmark, no `reportFullyDrawn`, no StrictMode, no JankStats, and `largeHeap=true`. Startup/jank not measured. |
| Stability | **7.0** | Export transaction journal, recovery, cancellation and foreground priority are unusually rigorous. Two `runBlocking` calls can block the main thread (one in `onStop`). Vitals not assessed (no Play data seen). |
| Architecture | **4.0** | The weakest area. The editor is one 2,807-line composable written as an extension function on `MainActivity`, its core state (`ParamsState`, 139 mutable fields) lives in `remember`, not a ViewModel, and every ViewModel is Activity-scoped. |
| Data & backend | **8.0** | Local-first, DataStore settings, atomic JSON stores, verified document writes, ADR-documented storage boundaries. The C++ engine with 48 parity gates is the strongest part of the codebase (9+ on its own). |
| Security & privacy | **7.5** | Photo picker (no media permission), explicit `exported`, immutable PendingIntents, no cleartext, tight backup rules. `REQUEST_INSTALL_PACKAGES` ships in the only build (no Play flavor), and R8 runs with `-dontobfuscate` and package-wide keeps. |
| Build & release | **6.5** | Release provenance, 16 KB gate, lockfiles and R8 dex check are best-in-class; dependencies are 1–2 releases behind, no resource shrinking, a 1,800-line test probe ships in `main`, and no screenshot tests or benchmark module. |
| **Overall** | **6.5 / 10** | A world-class engine and release pipeline wrapped in a UI layer whose architecture has not caught up with its new design. |

Brutal summary: the parts users never see (engine, export transactions, release hash-binding)
are 9/10. The part every future feature has to touch, `EditorScreen.kt`, is the thing holding
the app at 6.5. Fix the editor's shape and most other scores move with it.

---

## Top 5 fixes (highest impact ÷ effort first)

1. **Take `runBlocking` off the main thread** (P1, ~1 hour). `MainActivity.onStop()` calls
   `appContainer.settings.flush()` (`MainActivity.kt:139-142`), which does
   `runBlocking { withTimeoutOrNull(2000) … }` (`SettingsDataStore.kt:96`); the first settings
   read is also `runBlocking { dataStore.data.first() }` (`SettingsDataStore.kt:140`). Skill
   checklist #30: no main-thread I/O or blocking. Fix: flush from
   `ProcessLifecycleOwner`/an application-scoped coroutine, or accept DataStore's own durability
   and drop the wait; load settings asynchronously and gate the first frame on it with the
   splash screen (fix 4).

2. **One dependency refresh PR** (P2, ~half a day plus lockfile regen with `write-locks`).
   Against the 2026-11-07 snapshot: Compose BOM `2026.06.01` → **2026.09.00**; lifecycle
   `2.10.0` → **2.11.0** (adds scoped ViewModels in Compose and stable
   `lifecycle-viewmodel-navigation3`, which fix 6 needs); DataStore `1.1.1` → **1.3.0**;
   core-ktx `1.17.0` → **1.19.1**; window `1.5.0` → **1.5.1**; AGP `9.3.2` → **9.5.0**; Kotlin
   `2.2.10` → **2.4.21**. Material3 `1.5.0-rc01` and navigation3 `1.2.1` are already current.
   exifinterface `1.3.7` is not in the snapshot: verify at
   https://developer.android.com/jetpack/androidx/releases/exifinterface.

3. **Replace Toasts with Snackbars and human copy** (P2, ~half a day). Six `Toast.makeText`
   call sites (`EditorExportEffects.kt:210,218`, `EditorExportAction.kt:99`,
   `EditorToolOverlays.kt:117`, `EditorScreen.kt:1004`, `EditorPresetPanel.kt:295`). Two of
   them show `outcome.cause.message` (a raw exception string) to the user. The B11 playbook's
   flow ends in `Snackbar "Saved · Share"`; errors should say what happened and what to do,
   with a Retry action where one exists. The app already has `SnackbarHost`s in 21 places, so
   this is consistency, not new plumbing. Also: `"Share diagnostics"` is hard-coded English
   (`Diagnostics.kt:408`).

4. **SplashScreen API, a dark-aware window theme, and TTFD** (P2, ~half a day). The manifest
   theme is `@android:style/Theme.Material.Light.NoActionBar` (`AndroidManifest.xml:58`), so a
   dark-mode user gets a light window before Compose draws; there is no `installSplashScreen()`
   and no `reportFullyDrawn`/`ReportDrawnWhen` anywhere (checklist #7, #21). Add
   `core-splashscreen`, a `Theme.SplashScreen` parent, keep the splash until settings and the
   session restore are read (this also replaces the blocking read in fix 1), and call
   `ReportDrawnWhen { previewReady }` in the editor so startup can be measured as TTFD.

5. **Previews and screenshot tests for the design system** (P2, ~1–2 days). There are **zero**
   `@Preview` functions in 160 main source files, and no screenshot-test source set. The
   skill's speed rule is "stateless screens get free previews (light/dark/font-scale/size
   multipreviews) and trivially testable UI". Start with the shared pieces the redesign just
   created (`EditorStageChrome.kt`, `Sliders.kt`, `Widgets.kt`, `FilmStockStrip.kt`,
   `ExportSheet.kt`) using `@PreviewLightDark`, `@PreviewFontScale`, `@PreviewScreenSizes`,
   then put them under screenshot tests. This is the safety net fix 6 needs.

**The one big thing (do it right after the five above):** fix 6 below, the editor
ViewModel/UiState split. It is not top-5 only because it is expensive; it has the largest
impact of anything in this report.

---

## Findings by area

### Architecture

**A1. The editor is one composable bolted onto the Activity — P1.**
Evidence: `internal fun MainActivity.EditorScreen(...)` (`EditorScreen.kt:83`), 2,807 lines,
84 `remember {}` blocks, 56 `mutableStateOf`. It reaches Activity members directly (the
ViewModels in `MainActivity.kt:37-80`, `heldDropPermissions`), and helpers like
`EditorRecipeEffects.kt`, `EditorExportEffects.kt` and `EditorBatchExport.kt` take
`activity: MainActivity` or extend it.
Why it matters to users: every new feature lands in this file, so every feature costs more
and regresses more; it also cannot be previewed or UI-tested in isolation.
Fix (skill architecture shape): `EditorRoute` (wires ViewModels, effects, launchers) +
stateless `EditorScreen(uiState: EditorUiState, onEvent: (EditorEvent) -> Unit)`. Move each
panel to take state + lambdas only. No composable should take `MainActivity`.

**A2. The core editing state is not in a ViewModel — P1.**
Evidence: `val state = remember { ParamsState() }` (`EditorScreen.kt:118`); `ParamsState` is a
mutable class with 139 `mutableStateOf` fields (`ParamsState.kt:67`). It survives rotation only
because the Activity opts out of recreation (`configChanges`), and survives process death only
through the disk checkpoint.
Why: the skill's state rule is `ViewModel + StateFlow<UiState>`, data down / events up. Today a
`uiMode`, locale, density or font-scale change (deliberately not in `configChanges`) recreates
the Activity and throws this object away, leaning entirely on checkpoint restore.
Fix: an `EditorViewModel` owning an immutable `EditorParams` (data class, or a few grouped
ones) exposed as `StateFlow`, with `SavedStateHandle` for the small "where was I" keys; keep
the disk checkpoint as the process-death source of truth. Mark the state classes `@Immutable`
(there are no `@Stable`/`@Immutable` annotations in the app today).

**A3. All ViewModels are Activity-scoped — P2.**
Evidence: ten ViewModels created with `ViewModelProvider(this, …)` in `MainActivity.kt`,
including Library and Settings state that only matters on those Navigation 3 entries.
Fix: after the lifecycle 2.11 bump (fix 2), add the ViewModel store entry decorator to
`NavDisplay` (`AppRoot.kt:458`) so Settings/Library/Curves get per-entry ViewModels that are
cleared when popped. Editor-wide ones can stay at the root entry.

**A4. Backwards write in composition — P2.**
Evidence: `SideEffect { paneWide = wide }` (`EditorScreen.kt:2256`) writes state that the top
bar, composed *above* it, reads. Checklist #26: this costs an extra recomposition and a
one-frame mismatch every time the pane mode flips.
Fix: compute the pane mode once at the top (from window size, see D1) and pass it down to both.

**A5. One `collectAsState()` without lifecycle — P3.** `EditorScreen.kt:591`
(`ExportWorkRuntime.state.collectAsState()`); the other 22 collectors already use
`collectAsStateWithLifecycle`. Switch it for consistency (checklist #37).

**A6. Single Kotlin module and manual DI — accepted, not a finding.** ADR 0004 (no feature
modules yet) and ADR 0001 (AppContainer instead of Hilt) both weigh the skill's defaults
against this repo's hash-bound release identity and give a reasoned answer. The skill's
default is Hilt + feature modules, but for one Activity and ~160 files the ADRs are a fair
call. Revisit when A1/A2 are done; the split becomes cheap once screens are stateless.

### UX & flows (B11 photo-editor playbook)

What already matches the playbook: photo picker with no storage permission; Sharesheet in and
out (VIEW/SEND filters, `Intent.createChooser` on export); press-and-hold / slider compare;
non-destructive edits with versions and recipes; export in a foreground service with a Cancel
action; Ultra HDR gain-map handling (`HdrGainMap.kt`); haptics in `Sliders.kt` and
`ToneCurve.kt`; drag-and-drop and keyboard shortcuts; undo/redo in the app bar.

**U1. Error and status feedback — P2.** See top fix 3 (Toasts, raw exception text).

**U2. Indeterminate export progress — P3.** The notification uses `setProgress(0, 0, true)`
(`ExportForegroundService.kt:218`) with an honest comment that the engine has no monotonic
fraction. The playbook asks for a determinate indicator for anything over 5 s. The engine
already knows its stage list (filming → printing → scanning → encode); a stage-weighted
fraction is honest enough and much better for a 30–60 s export.

**U3. No app shortcuts — P3.** B11 lists "App shortcuts (New project, Camera)". A static
`shortcuts.xml` with "Open photo" and "Library" is a one-file change.

**U4. First run** — `WelcomeFlow` exists (`AppRoot.kt:588`). Not evaluated on device this pass.

### Visual design (M3)

**V1. Fallback color scheme is the stock baseline — P3.** `spektraLightScheme() =
lightColorScheme()` / `darkColorScheme()` (`Theme.kt:130-132`). Users on API < 31, or who turn
dynamic color off, get Material's default purple, not a Spektrafilm identity. The skill:
"dynamic color + brand fallback scheme". Generate a scheme from a brand seed (film-amber
suits the product) and keep dynamic color as the default.

**V2. Stale comment** — `Theme.kt:11` still says `material3:1.5.0-alpha28`; the catalog pins
`1.5.0-rc01`. Same in `app/build.gradle.kts` (compileSdk comment).

**V3. No previews** — see top fix 5.

### Accessibility

**X1. 40 dp touch targets — P1 (verify).** `CircleScrimButton` forces `.size(40.dp)` on the
box that hosts an `IconButton` (`EditorChrome.kt:193`); `Viewer.kt:557` does the same but
adds `minimumInteractiveComponentSize()` first. Checklist #9 requires 48 dp. Compose may extend
the touch bounds of a constrained `IconButton`, so confirm with Accessibility Scanner; if it
reports < 48 dp, apply the same `minimumInteractiveComponentSize()` pattern used in
`Widgets.kt:335`.

**X2. Largest font scale is not tested — P2.** No `fontScale = 2f` previews or tests
(checklist #12). `LocalizedLayoutTest` covers expansion and RTL, which is good; add font scale.

**X3. `contentDescription = null` audit — mostly correct.** 37 occurrences; the ones checked
are decorative or sit beside a text label (menu leading icons, images described by a parent
node). No action beyond a TalkBack pass.

### Adaptive & surfaces

**D1. Hand-rolled breakpoints instead of window size classes — P2.** The layout is decided
from `BoxWithConstraints` and `editorPaneMode(widthDp, heightDp)` (`EditorLayout.kt:47`);
there is no `currentWindowAdaptiveInfo`, `NavigationSuiteScaffold` or
`SupportingPaneScaffold` in the app (checklist #14). B11 names the editor "the canonical
supporting pane". The custom logic works and handles posture, but moving to
`material3-adaptive` (stable 1.3.0 in the snapshot) gives the standard breakpoints, pane
motion and predictive back between panes for free, and fixes A4 as a side effect.

**D2. Strong already** — resizable, no orientation lock, `configChanges` for window shape,
fold posture (`FoldPosture.kt`), Meta+/ shortcut helper, wheel zoom, drag-and-drop, touch not
required. This is above most apps on the store.

**D3. Export foreground service type — P2.** The service declares `dataSync`
(`AndroidManifest.xml`, `ExportForegroundService.kt:150`). The skill's FGS table says
`dataSync` is for upload/download, and its photo-editing guidance says long exports belong in
a **`mediaProcessing`** foreground service (6 h / 24 h budget, `onTimeout`). Declare
`mediaProcessing` on API 35+ and keep `dataSync` below it. Note Play asks you to declare FGS
types in the Console; the declaration should match. ADR 0003 (no WorkManager) stays valid.

### Performance

**P1. Baseline profile is a wildcard — P2.** `baseline-prof.txt` is
`HSPLcom/spectrafilm/app/**->**(**)**` plus all of engine and coroutines. It AOT-compiles the
whole app, which grows install-time compile and on-disk size and gives no startup
DEX-layout benefit. Skill: generate it from a recorded journey with a `:baselineprofile`
module (`BaselineProfileRule`) and add a Startup Profile.

**P2. No Macrobenchmark module, no TTFD — P2.** Checklist #20/#21. Today there is no way to
say whether a change made cold start or editor scroll faster. Add a `com.android.test`
module with `StartupTimingMetric` and `FrameTimingMetric` for "open app → preview shown" and
"drag a slider". (`Ticket177BenchmarkChecks.kt` measures the engine, not the UI.)

**P3. `largeHeap="true"` — P2.** (`AndroidManifest.xml:51`.) It raises the ceiling on some
devices but makes GC pauses longer and hides leaks; the skill flags memory against vitals
RAM-tier thresholds. The tiled-export work (#300) is the real fix; once it is in, try removing
`largeHeap` behind a measured A/B.

**P4. No StrictMode in debug, no JankStats — P3.** Checklist #27/#28. StrictMode in debug would
have caught the `runBlocking` in fix 1.

**P5. Composition work** — the grep for sorting/filtering inside composables found only
`FullScreen.kt:136` (small list). Lazy lists use keys except `StockBrowser.kt:180`
(`items(rows)` without `key`/`contentType`, P3).

### Stability

**S1. Main-thread blocking — P1.** See top fix 1.

**S2. Excellent transaction design** — the export journal, recovery report, generation gates,
`onTimeout` handling and cancellation are more careful than most production apps. Keep it.

**S3. No crash/vitals visibility — not assessed.** There is a custom crash handler and a
Diagnostics screen, but no field telemetry. That is a legitimate choice for a GPL app
distributed on GitHub; if you go to Play, Android vitals becomes your telemetry for free.

### Data & backend

This app has no server; its "backend" is the native engine, local storage and the GitHub
updater.

- **Engine (9+/10):** 48 host parity gates at two flag sets, thread-count byte identity, GPU
  gates under lavapipe, and an R8 dex check for JNI-resolved members. Nothing in the skill
  asks for more.
- **Storage (8/10):** DataStore for settings, atomic JSON stores, verified document writes,
  ADR 0002 for what stays on SharedPreferences. Room is not needed for this data shape.
- **Updater:** one HTTPS call, signer verification before PackageInstaller, user always
  confirms. Good. See H1 for the Play side.

### Security & privacy

**H1. `REQUEST_INSTALL_PACKAGES` in the only build — P0 for Play, n/a for GitHub.**
(`AndroidManifest.xml:13`.) The manifest comment already says a Play build must remove it, but
there is no product flavor that does so. Play treats it as a restricted permission. Add a
`play` flavor with `tools:node="remove"` and the updater compiled out, before any Play upload.

**H2. R8 keep rules are broad — P2.** `-dontobfuscate`, `-keep class com.spectrafilm.engine.**
{ *; }` and ~40 class-wide keeps (`proguard-rules.pro`); checklist #59. Context matters here:
the `kotlin.Triple` incident in CLAUDE.md shows why the team chose safety, and
`check_release_dex.sh` now guards JNI members. Narrowing to `-keepclassmembers` for exactly the
JNI-touched members is still the target; obfuscation is optional for a GPL app.

**H3. Good already:** photo picker, no `READ_MEDIA_*`; `ACCESS_MEDIA_LOCATION` requested only
when the user turns GPS export on; every component's `exported` is explicit; `FLAG_IMMUTABLE`
PendingIntents; `usesCleartextTraffic=false` with a network security config;
`allowBackup=false` plus data extraction rules that include only settings.

### Build & release

**R1. No resource shrinking — P2.** `isMinifyEnabled = true` but no `isShrinkResources = true`
(`app/build.gradle.kts:84`); checklist #58.

**R2. Test-only probe ships in `main` — P2.** `Ticket139EditorProbe.kt` (1,802 lines) and
`Ticket139EditorTestBridge.kt` live in `src/main`, are called from `EditorScreen.kt` (lines 103,
468, 725, 741, 1124, 1141–1154) and are kept by R8. Checklist #62 (no debug code in release).
It is default-off, but it is shipped code and shipped surface. Move it behind a `debug`/
`androidTest` source set or a no-op interface with a test implementation.

**R3. Dependencies behind** — see top fix 2.

**R4. Test pyramid — partly there (P2).** 141 JVM test files with Robolectric and Compose UI
tests, instrumentation on an API 35 emulator, and the engine parity suite: strong. Missing:
screenshot tests and a macrobenchmark (checklist #65). Compose tests still use
`createComposeRule` from `ui-test-junit4`; the skill says plan the move to the v2 APIs
(checklist #66, P3).

**R5. Good already:** targetSdk 36 meets the Play rule in force (API 36 since 2026-08-31);
16 KB page alignment gated in CI; NDK r28c; lockfiles, SPDX and hash-bound release identity;
protected signing environment.

---

## Modernization opportunities (status as of the 2026-11-07 snapshot)

| Idea | Why it fits this app | Status |
|---|---|---|
| `SupportingPaneScaffold` / `SupportingPaneSceneStrategy` for the editor | B11 calls the editor "the canonical supporting pane" | **Stable** (material3-adaptive 1.3.0, navigation3 1.2.1) |
| Per-entry ViewModels via `lifecycle-viewmodel-navigation3` | Fixes A3 | **Stable** (lifecycle 2.11.0) |
| `MaterialShapes` + `PolygonShape`/`MorphPolygonShape` for the export "done" hero | Expressive moment at the emotional peak | Foundation shapes **RC** (Compose 1.13.0-rc01); MaterialShapes promotion was **reverted** in M3 1.5 alpha19, still experimental |
| `Modifier.blur { }` progressive blur behind the floating stage toolbar over the photo | Keeps the photo visible under chrome | **RC** (Compose 1.13.0-rc01) |
| Live Update style progress notification for export (`ProgressStyle`) | Shows stage progress on the lock screen during a 60 s export | Platform, API 36+/37 semantic colors; requires a determinate fraction (U2) |
| AppFunctions: "apply Portra 400 look to this photo" for on-device assistants | Agentic entry into the editor | **RC** (appfunctions 1.0.0-rc01); Gemini integration is a **private preview** |
| Gemini Nano (ML Kit GenAI) image description → suggest a film stock | "Pick a look for me" without network | Status not stated on the skill's source page — **verify** at https://developer.android.com/ai |
| Glance widget: last edited photo, tap to resume | B11 lists widgets (recent project) | **Stable** (glance 1.2.0) |
| Android 17 Handoff: continue an edit on a tablet | Phone-to-tablet editing | Android 17 platform API; app targets 36, so needs target 37 first |

Which parts are synthesis: the specific feature ideas (film-stock suggestion, export hero
shape, widget content) are my synthesis; the APIs and their status are from the skill's
dated snapshot.

---

## Path to 10/10

**Quick wins (this week, each a small PR):**
1. Async settings, no `runBlocking` on main (fix 1).
2. Dependency refresh (fix 2).
3. Snackbars + human error copy, localize "Share diagnostics" (fix 3).
4. SplashScreen + dark window theme + `ReportDrawnWhen` (fix 4).
5. `isShrinkResources = true`; `key` on `StockBrowser` rows; `collectAsStateWithLifecycle` at
   `EditorScreen.kt:591`; stale alpha28 comments.
6. StrictMode in debug builds.

**Next sprint:**
7. Previews + screenshot tests for shared components (fix 5).
8. `EditorViewModel` + immutable `EditorUiState`; `EditorRoute` / stateless `EditorScreen`;
   no composable takes `MainActivity` (A1, A2, A4).
9. Per-entry ViewModels on Navigation 3 (A3).
10. `mediaProcessing` FGS type on API 35+ and a stage-weighted determinate progress (D3, U2).
11. Move the Ticket139 probe out of `main` (R2).

**Strategic:**
12. `material3-adaptive` scaffolds for the editor and Settings (D1).
13. `:baselineprofile` + Macrobenchmark module; recorded Baseline and Startup Profiles; then
    try dropping `largeHeap` with numbers (P1–P3).
14. `play` flavor without `REQUEST_INSTALL_PACKAGES` before any Play upload (H1).
15. Brand fallback color scheme (V1), app shortcuts (U3), then one "beyond the app" surface
    (widget or Live Update export progress).

Expected scores after each phase (estimate, not measured): quick wins → ~7.2; next sprint →
~8.3 (architecture 4 → 7.5); strategic → ~9.

---

## Not inspected / assumptions

- **No device run.** Startup time, jank, memory, Accessibility Scanner, TalkBack, contrast
  ratios and the first-run flow were not measured; scores in those areas come from code only.
- **Android vitals / Play Console** not seen (the app ships through GitHub releases).
- **PR #444** (upstream items) was open, not merged, at audit time.
- The C++ engine was assessed from its test gates and CLAUDE.md, not line by line.
- Versions come from the skill's snapshot dated 2026-11-07, not a live fetch today.
- Touch-target finding X1 needs confirmation with Accessibility Scanner.
