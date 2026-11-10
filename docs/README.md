# Spektrafilm documentation

Spektrafilm turns your phone photos and RAW files into film. These guides cover the app as it is
in version 1.0. Start with **Getting started** if you are new, or jump to the tab you are using.

| Guide | What's in it |
|---|---|
| [Getting started](getting-started.md) | Install, the Library, your first edit in a minute, updates |
| [Editing guide](editing.md) | Looks, Edit, Crop and Masks, tab by tab, plus history and versions |
| [Films and papers](films-and-papers.md) | Every stock in the app, what it's good for and how to pair it |
| [Exporting](exporting.md) | Formats, color spaces, frames and borders, LUTs, large images |
| [How it works](how-it-works.md) | The negative, enlarger, print and scan, and how accuracy is checked |
| [FAQ](faq.md) | Common questions, privacy, and how to report a bug |

## For maintainers

These files are for people building the app or changing the engine. Release and test tooling
reads several of them, so they keep their names and places.

- [MAINTAINERS.md](MAINTAINERS.md): architecture, build, the parity gates and the engineering
  contracts in one page
- [EXECUTION_INDEX.md](EXECUTION_INDEX.md): which document owns what, and the toolchain pins
- [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) and
  [PRODUCTION_READINESS_PLAN.md](PRODUCTION_READINESS_PLAN.md): how a release is qualified,
  signed and published
- [LICENSING.md](LICENSING.md) and [dependencies/LIBRAW.md](dependencies/LIBRAW.md): licence
  obligations, shipped inside every release bundle
- [PRESETS.md](PRESETS.md): the six built-in looks and their exact values
- [UPSTREAM_PARITY.md](UPSTREAM_PARITY.md): coverage of the upstream spektrafilm parameters
  (generated)
- [MASK_JSON_SCHEMA.md](MASK_JSON_SCHEMA.md) and [schema/](schema/): the mask file format
- [../CONTRIBUTING.md](../CONTRIBUTING.md), [../SECURITY.md](../SECURITY.md),
  [../CHANGELOG.md](../CHANGELOG.md)

Older research notes, audits and plans were removed for 1.0. They are still in git history, for
example `git show 37d475e:docs/research/perf-lab.md`.
