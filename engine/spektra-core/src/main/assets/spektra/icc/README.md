# ICC profiles

The output-colour-space ICC profiles the app embeds on 16-bit TIFF and PNG export
(`lib:tiffwriter`, `lib:pngwriter`) so that ICC-aware viewers display the pixels with the
correct colour interpretation. The mapping from output space to file is
`app/src/main/java/com/spectrafilm/app/OutputDescriptor.kt`; only the six files below ship.

Files are kept under their **original upstream names** so they stay traceable to the source
repositories.

## Active mapping

| Output space      | File                                   | Source        |
| ----------------- | -------------------------------------- | ------------- |
| sRGB              | `saucecontrol/sRGB-v4.icc`             | saucecontrol  |
| Adobe RGB (1998)  | `saucecontrol/AdobeCompat-v4.icc`      | saucecontrol  |
| ProPhoto RGB      | `saucecontrol/ProPhoto-v4.icc`         | saucecontrol  |
| ITU-R BT.2020     | `saucecontrol/Rec2020-v4.icc`          | saucecontrol  |
| ACES2065-1        | `ellelstone/ACES-elle-V4-g10.icc`      | ellelstone    |
| Linear sRGB       | `ellelstone/sRGB-elle-V4-g10.icc`      | ellelstone    |

The rest of both upstream profile sets was carried in this directory until 2026-09-14 and was
dropped from the APK then (nothing enumerated the directory; the app only ever opened these six).
Git history keeps them.

## Sources & licenses

### `ellelstone/` — Elle Stone's well-behaved ICC profiles

- Repository: <https://github.com/ellelstone/elles_icc_profiles>
- License: Creative Commons Attribution-ShareAlike 3.0 Unported (CC BY-SA 3.0)
- Attribution required. See `ellelstone/LICENSE-CC-BY-SA-3.0`.
- Naming convention: `<colorspace>-elle-V<2|4>-<trc>.icc`; `g10` is a linear TRC, V4 is ICC v4.
- ACES2065-1 uses AP0 primaries and is always scene-linear, which is why its profile is a `g10`
  file.

### `saucecontrol/` — Compact ICC Profiles

- Repository: <https://github.com/saucecontrol/Compact-ICC-Profiles>
- License: MIT
- Attribution required. See `saucecontrol/LICENSE-MIT`.
- Byte-minimal ICC v4 profiles (~400 B – 2 KB) for the standard display spaces.
