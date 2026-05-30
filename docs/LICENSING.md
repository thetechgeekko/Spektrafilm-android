# Licensing

## Summary

The combined application is distributed under **GPL-3.0-or-later**.

| Component | Upstream license | Compatibility |
|-----------|------------------|---------------|
| spektrafilm (engine we port) | **GPL-3.0** | Defines the floor: any derivative must be GPLv3. |
| ImageToolbox (host app) | **Apache-2.0** | Apache-2.0 → GPLv3 is **one-way compatible**; Apache code may be incorporated into a GPLv3 work. |
| LibRaw (RAW decode) | **LGPL-2.1 / CDDL-1.0** (dual) | We use it under **LGPL-2.1**, which is GPLv3-compatible. |

Result: **GPLv3** is the only license that satisfies all constraints. `LICENSE` is the GPLv3
text; `NOTICE.md` carries attributions.

## Why GPLv3 (not a choice)

spektrafilm is GPLv3 and its README is explicit: *"any derivative work must also be open source
under the same license. Derivative work includes any software, plugin, or tool that incorporates
spektrafilm code or is directly inspired by its methods."* Since `spektra-core` is a direct port
of spektrafilm, the engine — and therefore the app that links it — must be GPLv3.

## Apache-2.0 → GPLv3 direction

The Apache Software Foundation and FSF agree Apache-2.0 is compatible with GPLv3 (but **not**
GPLv2). Incorporating ImageToolbox (Apache-2.0) into this GPLv3 work is allowed; we retain
ImageToolbox's copyright headers and `LICENSE`/`NOTICE` references in the files that originate
from it. The combined/derived whole is offered under GPLv3.

## LibRaw

LibRaw's default LGPL-2.1 is compatible with GPLv3. Dynamic linking via JNI keeps the LGPL
boundary clean. If we later enable the Adobe **DNG SDK** add-on for non-baseline DNGs, we will
re-check its (BSD-style) terms — expected compatible — and record it here.

## Practical obligations

- Ship `LICENSE` (GPLv3) and `NOTICE.md` in the repo and in-app (the host already has a
  "libraries info" screen we extend).
- Make complete corresponding source available (this is a public repo — satisfied).
- Preserve upstream copyright/license notices in inherited files.
- Credit: *"film modeling powered by spektrafilm"* in app About/credits, per upstream request.
