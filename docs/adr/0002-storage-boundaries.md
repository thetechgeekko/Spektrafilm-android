# ADR 0002: Storage boundaries after the DataStore migration

- Status: accepted
- Date: 2026-11-07
- Ticket: #276 (roadmap #267)

## Context

#276 asked for repositories over DataStore for settings, presets, sources and the export
journal, with one condition: the export journal keeps the crash-recovery and exactly-once
guarantees in `docs/TRANSACTIONAL_STORAGE.md`, on DataStore only if that preserves them,
otherwise by wrapping the existing store.

Before this ticket, app storage was:

| Data | Store |
| --- | --- |
| Settings | `SharedPreferences` (`AppSettings`) |
| Presets, recipes | `AtomicFile` JSON (`Presets.kt`, `Recipes.kt`) |
| Source reference | `SharedPreferences.commit()` (`SharedPreferencesSourceRefStore`) |
| Pending-export journal | `SharedPreferences.commit()` (`SharedPreferencesPendingExportJournal`) |
| Update check state | `SharedPreferences` (`SharedUpdatePrefs`) |

## Decision

- **Settings move to Preferences DataStore** (`SettingsDataStore`, #329), migrated with
  `SharedPreferencesMigration`. Reads stay synchronous through an in-memory snapshot;
  `onStop` flushes queued writes. `big_cores_experiment` stays in SharedPreferences
  so the A/B can still be driven from a shell; it is not a product setting.
- **Presets and recipes stay on `AtomicFile`** behind `PresetRepository` (#330) and
  `RecipeRepository` (#332). They are documents, not key/value settings.
- **The source reference and the export journal stay on `SharedPreferences.commit()`**,
  reached through their existing ports (`SourceRefStore`, `PendingExportJournal`) and,
  for sources, `AppContainer.sources`.
- **Update check state stays in its own SharedPreferences file**, built by
  `SharedUpdatePrefs.create`, because it is deliberately outside the backup allowlist.

## Why the two transactional stores were not moved

Both stores need a write to be durable before the next step runs: the source coordinator
saves the new reference before releasing the old URI grant, and the export transaction
journals the token of a reserved pending MediaStore item before it continues, deleting the
item if that write fails, so recovery after a crash can find every pending item. `commit()` gives that
synchronously, on the single ordered worker that already serializes these mutations, and
`docs/TRANSACTIONAL_STORAGE.md` and its tests are written against it.

DataStore would need `runBlocking` around each edit on that same worker to give the same
ordering. That adds a migration path for crash-recovery state, and a new failure mode if
the migration itself is interrupted, for no change the user can see. Both stores already sit
behind interfaces with JVM fakes, so a later move remains a local change.

## Consequences

- `getSharedPreferences` appears only inside store classes: `SharedPreferencesSourceRefStore`,
  `SharedPreferencesPendingExportJournal`, `SharedUpdatePrefs.create`, and `AppSettings` for
  the experiment flag.
- UI code reaches storage through `AppContainer` (`settings`, `presets`, `recipes`,
  `sources`).
