# Wiring `feature:film-emulation` into the host (do at M1)

> **⚠️ OBSOLETE — dead module.** `feature/film-emulation/` is uncompiled (not in
> `settings.gradle.kts`) and the ImageToolbox host (`core/`, `build-logic/`) it targets was never
> built. The live UI is the standalone `:app` module. This file documents a module that cannot run
> in this repo as-is; kept only for reference.

This module is self-contained but inert until the ImageToolbox host is seeded.
Three host edits activate it. Apply them once the host (`app/`, `core/`,
`build-logic/`, `gradle/libs.versions.toml`, `settings.gradle.kts`) is present.

## 1. `settings.gradle.kts` — include the module

Add alongside the other Spektrafilm additions:

```kotlin
include(":engine:spektra-core")
include(":lib:libraw")
include(":feature:film-emulation")
```

## 2. `core/ui/.../navigation/Screen.kt` — register the screen

The highest screen id currently in use is **67** (`Screen.kt`, the
`AudioCoverExtractor`-era tail). Use the next free id, **68**. Add a
`@Serializable data class` in the `Screen` sealed hierarchy, next to the other
single-image tools (e.g. after `PickColorFromImage`):

```kotlin
@Serializable
data class FilmEmulation(
    val uri: Uri? = null
) : Screen(
    id = 68,
    title = R.string.film_emulation,
    subtitle = R.string.film_emulation_sub
)
```

(`Uri` and `R` are already imported in `Screen.kt`.)

## 3. `core/resources` — add the two string resources

In `core/resources/src/main/res/values/strings.xml`:

```xml
<string name="film_emulation">Film Emulation</string>
<string name="film_emulation_sub">Simulate analog film and darkroom printing on a RAW or image</string>
```

## 4. Navigation host (auto-wired, verify only)

ImageToolbox routes screens via Decompose + KSP — there is no manual route table.
Once the `FilmEmulation` `Screen` entry and the module include exist, the host's
generated navigation will discover `FilmEmulationContent(component)` and the
`FilmEmulationComponent.Factory` assisted-inject factory. Confirm the host's
`ChildProvider` / generated `*ComponentFactory` picks up the new component (same
mechanism that wires `PickColorFromImageComponent`). If the host uses an explicit
`when (screen)` mapping in `app/`, add:

```kotlin
is Screen.FilmEmulation -> FilmEmulationContent(
    component = filmEmulationComponentFactory(
        componentContext = ...,
        initialUri = screen.uri,
        onGoBack = ::navigateBack,
    )
)
```

## 5. Home grid (optional)

To surface it on the main grid, add `Screen.FilmEmulation()` to the relevant
group list in the host's screen catalog (the same list that orders the existing
~55 tools), and provide an icon override in `Screen.icon()`.
