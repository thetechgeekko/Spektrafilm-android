# Attribution & Third-Party Notices

SpectraFilm for Android is licensed under the **GNU General Public License v3.0** (see `LICENSE`).

> **Dedicated to the [pixls.us](https://pixls.us) community** — for keeping open photography and
> open color science alive. Thank you. 💛

Authored and directed by **Akshay** —
[Instagram @akshay.pool](https://www.instagram.com/akshay.pool/) ·
[YouTube @Akshayishere](https://www.youtube.com/@Akshayishere/videos).

## Film modeling powered by spektrafilm

The film-simulation engine in this project is a port of **spektrafilm** by Andrea Volpato.

- Upstream: https://github.com/andreavolpato/spektrafilm
- License: GPLv3
- Citation: see `CITATION.cff` upstream / the project's Zenodo DOI.

Per the upstream request: *film modeling powered by `spektrafilm`*. Any academic use should
cite the upstream repository.

## Color science: colour-science

Color-matching functions, illuminants, chromatic adaptation, and color-space transforms follow
**colour-science** — the reference used by spektrafilm and the ground truth for this port.

- Upstream: https://www.colour-science.org / https://github.com/colour-science/colour
- License: BSD-3-Clause

## Android architecture reference: Image Toolbox

The Android app design is informed by **Image Toolbox** by T8RIN (Malik Mukhametzyanov) — its
modular Compose/Hilt architecture guided this project, and it remains the intended richer host
for future versions.

- Upstream: https://github.com/T8RIN/ImageToolbox
- License: Apache License 2.0 (one-way compatible with GPLv3)

## RAW decoding: LibRaw

Full-resolution RAW/DNG decoding uses **LibRaw**.

- Upstream: https://www.libraw.org / https://github.com/LibRaw/LibRaw
- License: LibRaw is dual-licensed (LGPL-2.1 / CDDL-1.0). Both are GPLv3-compatible when
  used as documented; see `docs/LICENSING.md` for the chosen configuration.

## Film & paper profiles

The 28 film/paper JSON profiles and the spectral-upsampling LUT binaries originate from
the spektrafilm project and are redistributed under GPLv3. See `docs/ASSETS.md`.
