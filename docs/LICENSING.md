# Licensing

<!-- libraw-license-route: LGPL-2.1-only -->

LibRaw Android distribution route: LGPL-2.1-only.

> **Route elected (2026-09-14):** LibRaw is statically compiled into the native
> RAW module and is distributed under **LGPL-2.1-only**. The election, its
> rationale, the approving human owner, and the local-patch contribution
> authorization are recorded in `lib/libraw/compliance/license-decision.json`; see
> [Resolve LibRaw static-link compliance and publish a complete license/source bundle](https://github.com/thetechgeekko/Spektrafilm-android/issues/166).
> A green automated verifier is technical evidence, not legal advice.

## Summary

The combined application is distributed under **GPL-3.0-only**. No repository
grant of the GPL "or any later version" option has been identified.

| Component | Upstream license | Compatibility |
|-----------|------------------|---------------|
| spektrafilm (engine we port) | **GPL-3.0** | Defines the floor: any derivative must be GPLv3. |
| spektrafilm OFX selective Vulkan adaptations | **GPL-3.0-only** | Directly compatible with this GPL-3.0-only application; preserve provenance and mark modifications. |
| ImageToolbox (originally planned host; never vendored — row kept in case its code is ever incorporated) | **Apache-2.0** | Apache-2.0 → GPLv3 is **one-way compatible**; Apache code may be incorporated into a GPLv3 work. |
| LibRaw (RAW decode) | Offered under a choice of **LGPL-2.1-only** or **CDDL-1.0**; **LGPL-2.1-only** elected | LGPL-2.1 section 3 permits the combination with GPLv3. CDDL-1.0 is not generally treated as GPL-compatible and was therefore declined. Section 6 relink materials, corresponding source, notices and SBOM ship with each release. |

Result: **GPLv3** is the only license that satisfies all constraints. `LICENSE` is the GPLv3
text; `NOTICE.md` carries attributions.

## Why GPLv3 (not a choice)

spektrafilm is GPLv3 and its README is explicit: *"any derivative work must also be open source
under the same license. Derivative work includes any software, plugin, or tool that incorporates
spektrafilm code or is directly inspired by its methods."* Since `spektra-core` is a direct port
of spektrafilm, the engine — and therefore the app that links it — must be GPLv3.

## Apache-2.0 → GPLv3 direction

The Apache Software Foundation and FSF agree Apache-2.0 is compatible with GPLv3 (but **not**
GPLv2), so Apache-2.0 code may be incorporated into this GPLv3 work; none is today (the
ImageToolbox host was never vendored). The combined/derived whole is offered under GPLv3.

## spektrafilm OFX boundary

Only selected source-level Vulkan orchestration from the public GPL-3.0-only repository may be
adapted. Each adapted file must identify the exact upstream commit and source path and describe
the Android changes; `docs/research/spektrafilm-ofx-port.md` is the mapping record. Official
binary-only resources, exported OFX LUTs, licensed standards data, icons/branding, Metal and
OpenFX host glue are outside the port and must not be copied. The Android engine's locally
validated shaders and parity corpus remain the numeric authority.

## LibRaw

LibRaw is compiled as a static native library and included in the RAW JNI module. The project
distributes it under **LGPL-2.1-only**, recorded in
`lib/libraw/compliance/license-route.txt`; the release workflow rejects any other
value. CI constructs and verifies the source/relink bundle so packaging
regressions are caught independently of the election. The bundle includes both
upstream license texts for provenance. The paired canonical
`license-decision.json` carries the owner, decision date, rationale, HTTPS approval
reference, and local-patch contribution authorization, all of which release-mode
audit requires; a marker-only change still fails.
`spdx-created-at.txt` is the checked-in SPDX document creation time and must be
canonical UTC, not future-dated, and no earlier than the decision's `recorded_at`.
If we later enable the Adobe **DNG SDK**
add-on for non-baseline DNGs, we will separately review and record its exact terms.

## Practical obligations

- Ship `LICENSE` (GPLv3) and `NOTICE.md` in the repo and in-app (`AboutScreen.kt` renders
  `assets/legal/spektrafilm/NOTICE.md`).
- Publish the exact release source, patches, notices, SBOM, and reproducible
  source/relink materials required by the human-selected LibRaw route. A public
  repository alone is not recorded here as satisfying that route.
- Preserve upstream copyright/license notices in inherited files.
- Credit: *"film modeling powered by spektrafilm"* in app About/credits, per upstream request.
