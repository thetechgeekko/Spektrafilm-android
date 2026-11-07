# ADR 0003: Export stays on the custom foreground service, not WorkManager

- Status: accepted
- Date: 2026-11-07
- Ticket: #279 (roadmap #267)

## Context

Export runs as a process-owned coroutine in `ExportWorkRuntime`. While it runs,
`ExportForegroundService` (type `dataSync`) holds foreground priority, so Samsung's
device-health manager does not kill a backgrounded export (#153: `oom_score_adj` 50 with the
service, 700 without). Publication goes through `ExportTransaction`: a pending MediaStore row,
a journalled token, verified bytes, one publish, and recovery of leftover rows on the next
start (`docs/TRANSACTIONAL_STORAGE.md`). Cancellation reaches native code through the
`isCancelled` hook that the engine and encoders poll.

#279 asked whether a WorkManager `CoroutineWorker` with `setForeground` should replace this,
and said to adopt it only if exactly-once publication, journal recovery and native
cancellation are kept, and otherwise to record why the service stays.

## What WorkManager would and would not change

- **Throughput: no change.** perf-lab §15.1 measured the backgrounded slowdown at ~3.9x
  even with the foreground service running. The cause is the cpuset a backgrounded process
  lands in, which no foreground service controls. A worker that calls `setForeground` runs
  under WorkManager's own `dataSync` foreground service, so it ends up in the same cpuset.
- **Kill resistance: no change.** It is the same foreground-service mechanism, with the same
  API 35 `dataSync` daily budget and `onTimeout` handling.
- **Native cancellation: preserved either way.** Stopping a worker cancels its coroutine,
  which reaches the same `isCancelled` hook.
- **Restart after process death: new, and it is the only real gain.** Today, a process death
  mid-export loses the export, and recovery deletes its pending row. WorkManager would run it
  again. But that needs the export as a serializable job, and today it is not:
  the export is a closure inside `EditorScreen` that captures the live engine, the
  `ParamsState` snapshot, the decode request (with a `Context`), the export cache and a
  preview bitmap for the result UI.
- **Exactly-once: at risk on a rerun.** If the process dies after MediaStore publication
  but before the worker reports success, WorkManager runs the export again and publishes a
  second copy. Preventing that needs an idempotency key per job, checked against the
  journal before reserving a new row. The current design never reruns, so it never needed one.
- **Cost:** `androidx.work` brings its Room/SQLite store and a startup initializer. That adds
  lockfile, SPDX and R8 keep-rule surface to every release candidate. `release.yml`
  hash-binds all of these, and a missing keep rule has shipped silently wrong renders before
  (see CLAUDE.md).

## Decision

Keep `ExportForegroundService` + `ExportWorkRuntime`. Do not add WorkManager for export.

## Revisit when

- Export has been extracted from `EditorScreen` into a context-free job
  (`ExportJob(spec)`: source URI and kind, params JSON, output descriptor, options), which
  is worth doing for testability regardless, **and**
- surviving process death becomes a goal. In that case, add a per-job idempotency key to the
  pending-export journal before moving the job onto a `CoroutineWorker`.
