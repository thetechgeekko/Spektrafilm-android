# Attribution & Third-Party Notices

SpectraFilm for Android is licensed under the **GNU General Public License v3.0** (see `LICENSE`).

## Film modeling powered by spektrafilm

The film-simulation engine in this project is a port of **spektrafilm** by Andrea Volpato.

- Upstream: https://github.com/andreavolpato/spektrafilm
- License: GPLv3
- Citation: see `CITATION.cff` upstream / the project's Zenodo DOI.

Per the upstream request: *film modeling powered by `spektrafilm`*. Any academic use should
cite the upstream repository.

## Host application: Image Toolbox

The Android host application is derived from **Image Toolbox** by T8RIN (Malik Mukhametzyanov).

- Upstream: https://github.com/T8RIN/ImageToolbox
- License: Apache License 2.0

Apache-2.0 source incorporated into this GPLv3 work retains its original copyright notices.
Apache-2.0 is one-way compatible with GPLv3, so the combined work is distributed under GPLv3.

## RAW decoding: LibRaw

Full-resolution RAW/DNG decoding uses **LibRaw**.

- Upstream: https://www.libraw.org / https://github.com/LibRaw/LibRaw
- License: LibRaw is dual-licensed (LGPL-2.1 / CDDL-1.0). Both are GPLv3-compatible when
  used as documented; see `docs/LICENSING.md` for the chosen configuration.

## Film & paper profiles

The 28 film/paper JSON profiles and the spectral-upsampling LUT binaries originate from
the spektrafilm project and are redistributed under GPLv3. See `docs/ASSETS.md`.
