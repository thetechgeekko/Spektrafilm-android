# ADR 0001: Manual dependency injection through an `AppContainer`

- Status: accepted
- Date: 2026-11-07
- Ticket: #275 (roadmap #267)

## Context

Shared services are global `object` singletons reached directly from composables and
ViewModel factories: `EngineHolder`, `ExportWorkRuntime`, `Diagnostics`,
`EditorSessionCheckpointRuntime`, `AppSettings.from(ctx)`. Dispatchers are hard-coded
(`Dispatchers.IO` / `Dispatchers.Default` literals, 52 of them in `app/`). ViewModels that
need the engine or storage cannot be tested with fakes.

Two options were on the table:

1. **Hilt** (the Modern Android Development default): annotation processing via KSP,
   `@HiltAndroidApp`, `@AndroidEntryPoint`, `hiltViewModel()`.
2. **Manual container**: one `AppContainer` object graph owned by the `Application`,
   plus one `ViewModelProvider.Factory` that builds every ViewModel from it.

## Decision

Use a manual `AppContainer` (option 2).

- **No new dependencies.** This repo hash-binds its release candidates to all six Gradle
  lockfiles, two SPDX documents and the runtime classpath (`release.yml`). Hilt adds a
  Gradle plugin, KSP, the Dagger runtime and generated code to every one of those, plus
  new R8 keep-rule surface; `tools/r8_check/check_release_dex.sh` exists because a
  missing keep rule once shipped silently wrong renders.
- **Small graph.** One activity, a handful of ViewModels, and process singletons whose
  lifetimes are already documented (`docs/JNI_LIFETIME_SAFETY.md`). A container is a page
  of plain Kotlin; codegen buys little here.
- **Testable either way.** ViewModels take their collaborators as constructor parameters;
  JVM tests pass fakes directly, with no DI framework in the test classpath.

The container *wraps* the existing singletons; it does not change their ownership or
lifetime. In particular `EngineHolder` keeps its one-immutable-engine-per-process rule and
is never closed.

## Consequences

- `SpektraApplication` (manifest `android:name`) owns `AppContainer`; code reaches it with
  `Context.appContainer`.
- `AppContainer` provides `AppDispatchers` (io / default / main). New code takes a
  dispatcher from there instead of writing a `Dispatchers.*` literal; #277 migrates the
  existing literals.
- ViewModels are created through `AppContainer.viewModelFactory`. A new ViewModel is one
  more branch in that factory.
- Revisit Hilt if the graph grows to several activities or feature modules with their own
  scopes (#278), where generated wiring starts to pay for itself.
