# ADR 0004: Keep the Kotlin app in one Gradle module; structure it by package

- Status: accepted
- Date: 2026-11-07
- Ticket: #278 (roadmap #267)

## Context

`settings.gradle.kts` builds five modules: `:app`, `:engine:spektra-core`, `:lib:libraw`,
`:lib:tiffwriter` and `:lib:pngwriter`. Everything native (the engine, LibRaw and the two
writers) is already its own module, and native code is what dominates a clean build.
All Kotlin UI and data code is in `:app`: 115 files and about 37k lines in one package.

#278 proposed splitting `:app` into `:core:ui`, `:core:data` and `:core:model`, plus
`:feature:editor`, `:feature:export`, `:feature:settings`, `:feature:presets` and
`:feature:curves`, with convention plugins in `build-logic/`.

Android's modularization guide (<https://developer.android.com/topic/modularization>) says
modules are a necessity only for reusability, strict visibility control or Play Feature
Delivery. For scalability, ownership, encapsulation and build time they are "worth
considering". It also warns: "It doesn't always make sense to modularize your project. A
dominating factor is the size of the codebase," and "every module brings a certain amount
of overhead in the form of increased build complexity and boilerplate code."

## What a split would cost here

- **Release identity.** `release.yml` hash-binds every `gradle.lockfile` (six today), both
  SPDX documents and the runtime classpath into the candidate's identity. Each new module
  adds a lockfile, which has to be listed in `release.yml`, regenerated with
  `--update-locks` and kept in sync on every dependency bump. Eight new modules would more
  than double that surface.
- **R8.** Moving types across module boundaries changes what R8 sees as entry points. A
  missing keep rule has already shipped silently wrong renders once (CLAUDE.md, the
  `kotlin.Triple` getters). `tools/r8_check/check_release_dex.sh` covers the JNI-resolved
  members, but not app-level reflection or serialization that a split would newly expose.
- **Visibility churn.** About 500 `internal` declarations rely on everything sharing one
  module. A feature split turns most of them `public`, which is the opposite of the
  "strict visibility control" benefit the guide gives as a reason to split.
- **compileSdk.** Only `:app` compiles against android-37 (material3 alpha). Every new
  Compose module would need the same exception.

## What a split would buy here

- **Reuse:** none. There is one app and no second target such as Wear, TV or a library
  consumer.
- **Play Feature Delivery:** not used. Releases ship as one signed APK.
- **Ownership:** one owner.
- **Build time:** the Kotlin compile of `:app` is a minority of a clean build next to the
  four native modules, which are already separate and cached. This is an estimate and has
  not been measured as a split.

## Decision

Do not split `:app` into Gradle feature or core modules now. Get the separation-of-concerns
benefit from packages and file boundaries instead, which costs nothing at release:

1. Finish #273, so that no file is over about 800 lines and every screen and panel is a
   stateless composable taking `UiState` plus callbacks.
2. Group files by feature into sub-packages (`editor`, `export`, `settings`, `presets`,
   `curves`, `diagnostics`) with shared code in `ui` and `data`, mirroring the module layout
   #278 proposed. If the trigger below fires later, each package can become a module with a
   mostly mechanical move.

## Revisit when any of these happens

- A second consumer of the app code appears: another app target, a widget or
  companion process, or a published library.
- Play Feature Delivery or on-demand modules are wanted, for example to keep the bundled
  film profiles out of the base APK.
- More than one regular contributor, so that per-area code ownership matters.
- Incremental Kotlin compile of `:app` becomes the main thing slowing the edit-build loop.
  That needs a measurement, not an impression; state the build type and machine.
