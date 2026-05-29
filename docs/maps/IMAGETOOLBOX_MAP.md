# Technical map — Image Toolbox (the host app)

Source: `T8RIN/ImageToolbox`, Kotlin/Jetpack Compose, Apache-2.0. Produced by surveying the
actual source + `ARCHITECTURE.md`. This is the reference for hosting the SpectraFilm port.

## Stack

- **Build:** Gradle KTS + version catalog (`gradle/libs.versions.toml`); convention plugins in
  `build-logic/convention/` (`image.toolbox.library / feature / hilt / compose`).
- **SDK:** min 24, target 37. **Kotlin** 2.3.x, **Compose** 1.12-alpha.
- **DI:** Hilt (Dagger). **Navigation:** Decompose 3.x. **Image decode:** Coil 3.
- **NDK:** ABIs `armeabi-v7a, arm64-v8a, x86_64`; already ships native `.so` codecs
  (AVIF/JXL/JP2/WebP/QOI/DjVu/PSD). No first-party C/C++ yet, but NDK build is configured.

## Modules

- **`app/`** — entry, navigation host, quick tiles, media picker.
- **`core/`** (10): `data` (image load/save/transform/metadata), `ui` (Compose components +
  theme + navigation/`Screen.kt`), `domain`, `filters` (filter domain + registry), `resources`,
  `settings`, `di`, `ksp` (codegen), `crash`, `utils`.
- **`feature/`** (~55): `single-edit`, `filters`, `crop`, `draw`, `erase-background`, `compare`,
  `pick-color`, `resize-convert`, `format-conversion`, `gif/jxl/webp/pdf-tools`,
  `image-stacking/splitting/stitch`, `watermarking`, `gradient-maker`, `recognize-text`,
  `document-scanner`, `settings`, `root`, … (one screen per module).
- **`lib/`** (16): `opencv-tools` (OpenCV 4.13), `neural-tools` (ML Kit), `curves`, `ascii`,
  `collages`, `palette`, `cropper`, `zoomable`, `gesture`, `dynamic-theme`, …

## Image pipeline

- **Decode:** Coil `ImageLoader` with a custom `ComponentRegistry`
  (`core/data/.../ImageLoaderModule.kt`): APNG, animated, SVG, **HEIF**, **JXL**, **JP2**,
  **NEF** (Nikon RAW *preview* via `NefDecoder.kt`), **TIFF**, QOI, PSD, DjVu, PDF, Base64.
- **Load/save/transform:** `core/data/.../Android{ImageGetter,ImageTransformer,ImageCompressor,
  ImageScaler,Metadata}.kt`. Handles 16-bit, EXIF, ICC.
- **Filters:** **295** filters. UI models in
  `core/filters/.../presentation/model/Ui*.kt`; domain `Filter<T>` in
  `core/filters/domain/model/Filter.kt`; provider
  `feature/filters/data/AndroidFilterProvider.kt` maps `Filter<*>` → `Transformation<Bitmap>`
  via KSP-generated `mapFilter()`. Backends: JHLabs, GPUImage, OpenCV, AIRE (CPU + GPU
  wrappers; no direct GL/Vulkan). Categories include color/tone, blur, dithering, artistic,
  distortion, edge, **vintage/film-look presets**, pixelation, neural, special.

## Filter injection (KSP)

- Annotations: `core/ksp/.../annotations/FilterInject.kt`, `UiFilterInject.kt`.
- Processors: `core/ksp/.../processor/FilterInjectProcessor.kt`, `UiFilterInjectProcessor.kt` →
  generate `com.t8rin.imagetoolbox.generated.mapFilter()`.
- `AndroidFilterProvider`: `filter.ifVisible(::mapFilter) ?: EmptyTransformation()`.

## RAW/DNG today

Only **NEF preview extraction** (`core/data/.../coil/NefDecoder.kt`, ~462 LOC): parses TIFF
IFDs, pulls the embedded JPEG preview + orientation. **No full sensor decode, no DNG, no
LibRaw/dcraw.** Extension point: add a `Decoder.Factory` in `core/data/.../coil/` and register
it in `ImageLoaderModule.provideComponentRegistry()` — exactly where we slot a LibRaw-backed
`RawDecoder`.

## Adding a feature module (the pattern we follow)

Minimal module = `build.gradle.kts` applying 4 convention plugins + one namespace line:

```kotlin
plugins {
    alias(libs.plugins.image.toolbox.library)
    alias(libs.plugins.image.toolbox.feature)
    alias(libs.plugins.image.toolbox.hilt)
    alias(libs.plugins.image.toolbox.compose)
}
android.namespace = "com.t8rin.imagetoolbox.feature.<name>"
```

Files: `src/main/AndroidManifest.xml` (minimal) + `.../presentation/<Name>Content.kt`
(Composable), `.../presentation/screenLogic/<Name>Component.kt` (Decompose logic),
optional `components/`, `domain/`, `data/`, `di/`. Register the screen in
`core/ui/.../navigation/Screen.kt` (`@Serializable data class … : Screen(id, title, subtitle)`)
and add `include(":feature:<name>")` to `settings.gradle.kts`. No manual routing — Decompose +
KSP wire it.

## What we reuse vs add

- **Reuse:** Coil decode, image getter/saver/scaler, EXIF/ICC, zoomable canvas, gesture, sliders
  & sheets, compare view, media/file picker, settings, crash, theme, libraries-info screen.
- **Add:** `engine:spektra-core` (NDK engine), `lib:libraw` (NDK RAW), `feature:film-emulation`
  (Compose screen), a `RawDecoder` in `core/data/coil`, and a `FilmEmulation` entry in
  `Screen.kt`.
