# M1 — Bootstrapping the host (ImageToolbox) into this repo

This repo currently holds the **plan + engine contract** (M0). M1 seeds the **host
application** by importing the ImageToolbox source, then wiring in our three new modules.
Do this on the development branch (`claude/sharp-allen-I7wQK`).

## 1. Import the ImageToolbox tree

Copy the ImageToolbox working tree (without its `.git`) into the repo root:

```bash
# from the parent dir that contains both checkouts
rsync -a --exclude='.git' ImageToolbox/ Spectrafilmandroid/
```

This brings in `app/`, `core/`, `feature/`, `lib/`, `build-logic/`, `gradle/`,
`settings.gradle.kts`, `gradlew`, etc. Keep our existing `engine/`, `docs/`, `tools/`,
`README.md`, `NOTICE.md`, `LICENSE` (GPLv3 — do **not** overwrite with ImageToolbox's Apache
`LICENSE`; preserve ImageToolbox's license text as `LICENSE-ImageToolbox` and keep its per-file
headers). Update `NOTICE.md` if needed.

## 2. Register the new Gradle modules

Append to `settings.gradle.kts`:

```kotlin
include(":engine:spektra-core")
include(":lib:libraw")
include(":feature:film-emulation")
```

(`engine/spektra-core/build.gradle.kts` already exists and applies the convention plugins.)

## 3. Add the version-catalog entries we need

In `gradle/libs.versions.toml`, confirm/add the plugin aliases the engine uses
(`image.toolbox.library`, `image.toolbox.hilt`) — they already exist for ImageToolbox modules.
No new third-party libs are required for M0/M1.

## 4. Create the feature + libraw module skeletons

`feature/film-emulation/` — minimal `build.gradle.kts` (4 convention plugins, see
`docs/maps/IMAGETOOLBOX_MAP.md` §"Adding a feature module"), `AndroidManifest.xml`, a
`presentation/FilmEmulationContent.kt` Composable, and a
`presentation/screenLogic/FilmEmulationComponent.kt` (Decompose). Depend on
`projects.engine.spektraCore` and the usual `core:*`.

`lib/libraw/` — Android library with a CMake build for LibRaw (see `docs/RAW_DNG.md`) and a
JNI `RawDecoder` returning a linear `LinearImage`/`ByteBuffer`.

## 5. Register the screen

In `core/ui/.../navigation/Screen.kt`, add a `FilmEmulation` screen (next free `id`, title +
subtitle string resources) and surface it on the home grid, following the existing entries.

## 6. Move the assets

Copy spektrafilm's data into the engine module:

```bash
mkdir -p engine/spektra-core/src/main/assets/spektra
cp -r spektrafilm/src/spektrafilm/data/profiles engine/spektra-core/src/main/assets/spektra/
cp -r spektrafilm/src/spektrafilm/data/luts     engine/spektra-core/src/main/assets/spektra/
cp -r spektrafilm/src/spektrafilm/data/filters  engine/spektra-core/src/main/assets/spektra/
cp -r spektrafilm/src/spektrafilm/data/icc       engine/spektra-core/src/main/assets/spektra/
```

(~17 MB; see `docs/ASSETS.md` for the size-management options if APK size becomes a concern.)

## 7. Build gate

`./gradlew :app:assembleDebug` builds; the SpectraFilm screen opens (empty). The engine + JNI
link (returning "not implemented") and LibRaw compiles for all ABIs. That closes M1; M2 brings
RAW decode online and M3 starts the engine port (`docs/ROADMAP.md`).

## Golden-vector harness (set up alongside M3)

Use spektrafilm's `DebugParams` taps to dump intermediates from the Python engine on fixed
inputs, and assert the C++ port matches via `spk_simulate_tap`. Drive it from spektrafilm's
existing `tests/baselines/*.npz` + `scripts/regenerate_test_baselines.py`.
```
