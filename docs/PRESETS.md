# Spektrafilm Built-in Looks

Spektrafilm ships six built-in looks. Each pairs a film stock with a print medium (or scans a
slide directly) and adds a small set of engine tweaks so the result reads as that film. The
machine-readable definitions live in `engine/spektra-core/src/main/assets/spektra/presets.json`;
this document records why these six were chosen and what each one sets.

Until November 2026 the app shipped 27 looks in nine groups. Many were near-copies (four Portras
on the same paper, five cinema negatives on the same print film), and none was black and white.
The set was cut to the six films photographers most often buy, name and emulate. The film stocks
and papers those older looks used are all still in the Film and Paper pickers; only the one-tap
looks were removed. The visual comparison used for the decision rendered every old and new look
through the engine on the same sample photos.

## Why these six

No film manufacturer publishes sales by stock, so there is no single popularity figure. The
choice rests on four independent signals of different kinds, each with its own bias:

| Look | KEH sales rank, 35mm | Film Dating picks (2017) | Fuji X Weekly recipe views (2024) | Other |
| --- | --- | --- | --- | --- |
| Portra 400 | top 3 in 120 | #2 | #2, #3, #6, #7 | |
| Gold 200 | #6 | not ranked | named among the leaders | most-named film in a 2024 r/AnalogCommunity thread (hand count, approximate) |
| Ektar 100 | #2 | #3 | not in top 10 | |
| Tri-X 400 | #1 | #1 | #10 | the app's only black-and-white look |
| Kodachrome 64 | discontinued | slide films excluded | #1 | demand exists only as emulation |
| CineStill 800T | not ranked | not ranked | not ranked | #3 by rolls logged, Analog.Cafe 2025 Film Log report |

Sources:

- KEH Camera, "What Film Stocks Sell The Most? The KEH Tilt-Shift Report":
  <https://www.keh.com/blog/what-film-stocks-sell-the-most-the-keh-tilt-shift-report>. Three years
  of KEH's own sales; ranks only, no unit counts; the page is undated (it references 2021 events).
- PetaPixel, "The Top 10 Camera Films Preferred by Photographers" (2017):
  <https://petapixel.com/2017/07/22/top-10-camera-films-preferred-photographers>. Results of a
  "Film Dating" quiz used by about 38,000 people. Measures preference, not sales; slide films
  were excluded.
- Fuji X Weekly, "Top 25 Most Popular Film Simulation Recipes of 2024":
  <https://fujixweekly.com/2025/01/02/top-25-most-popular-film-simulation-recipes-of-2024/>, ranked
  by page views; and "Why Do Fujifilm Photographers Love Kodak Film Simulation Recipes So Much?"
  (October 2026): <https://fujixweekly.com/2026/10/08/why-do-fujifilm-photographers-love-kodak-film-simulation-recipes-so-much/>,
  which says Kodachrome, Portra, Gold and Tri-X recipes consistently dominate.
- Analog.Cafe, "Top 10 Film Stocks According to 293 Photographers" (December 2025):
  <https://www.analog.cafe/r/top-10-film-stocks-according-to-293-photographers-wlzp>. Rolls logged
  in the Film Log app. Ranks 1, 2 and 4 to 6 are behind a paywall, so only CineStill 800T at #3 is
  cited here.
- r/AnalogCommunity, "What is y'all's most used film?" (June 2024):
  <https://www.reddit.com/r/AnalogCommunity/comments/1d960ge/what_is_yalls_most_used_film/>.
  Counted by hand; treat as anecdotal.

Close runners-up were Ilford HP5 Plus (KEH #4), Portra 800 (KEH #3), Fujifilm Pro 400H (KEH #10,
Film Dating #5, discontinued) and UltraMax 400. They were left out so the six cover six different
jobs (portrait, everyday, landscape, night, slide, black and white) instead of several similar
400-speed colour negatives.

## How looks work

A built-in look is a sparse overlay: it changes only the fields authored in `presets.json`, and
`BuiltInPresets.apply` leaves every omitted field at its current editor value. The editor rewinds
to the look a browsing run started from before applying the next one, so tapping through the strip
never stacks looks. Every look authors `enlarger.yFilterShift` and `enlarger.mFilterShift`, so one
look's print colour cannot leak into the next.

Applying a look copies its settings into the photo's edit. Recipes, versions and user presets store
the full settings, never a look's id, so a photo edited with a look that was later removed keeps
rendering exactly as before.

Key knobs used here:

- **`camera.exposureCompensationEv`**: overall exposure. Negatives flatter slight overexposure.
- **`filmRender.densityCurveGamma`**: film contrast. Above 1 is snappier, below 1 softer.
- **`filmRender.grain.*`**: `blur` sets softness (higher is smoother); `agxParticleScale` (R, G, B)
  enlarges the grain when it should be visible.
- **`filmRender.halation.*`**: the glow bright lights scatter into the emulsion.
  `halationAmount` is a multiplier on the stock's own measured strength. A look cannot set
  `halationStrength` or `halationFirstSigmaUm`; the engine bakes both from each profile's
  anti-halation tags. `boostEv` reconstructs clipped highlights so the glow blooms harder.
- **`filmRender.dirCouplers.amount`**: interlayer coupler strength, for edge contrast and colour
  separation.
- **`io.scanFilm`**: scan the developed film directly and skip the print stage. Used for the slide
  film.
- **`enlarger.yFilterShift` / `mFilterShift`**: the enlarger's colour filtration, in CC units, on
  top of the neutral pack for that film and paper. A negative yellow shift warms the print.
- **`enlarger.paperGrade`**: the Multigrade contrast grade, read only on silver paper.
- **`scanner.unsharpMask`**: capture sharpening (sigma, amount).

---

## Portrait

### Portra 400  (`portra_400`)
**Kodak Portra 400 → Kodak Portra Endura.** The film most often meant by "the film look": warm,
forgiving skin and a low-contrast curve that holds highlights and shadows. These are the same
settings the former "Portra 400 — Wedding Warm" look used, which the export baseline corpus is
pinned to.
Tuning: `exposureCompensationEv 0.3` with centre-weighted auto-exposure, `densityCurveGamma 0.96`,
`grain.blur 0.7`, `halation 1.0`, DIR couplers on at `dirCouplers.amount 1.0`,
`scanner.unsharpMask [0.5, 0.7]`.

## Everyday

### Gold 200  (`gold_200`)
**Kodak Gold 200 → Kodak Ektacolor Edge.** Kodak's consumer film: sunny yellows, warm golds and a
friendly grain, on a consumer RA-4 paper.
Tuning: `exposureCompensationEv 0.3`, `densityCurveGamma 1.0`, `grain.blur 0.68` with
`agxParticleScale [0.96, 1.2, 2.3]` for a more visible consumer grain, `halation 1.0`,
`dirCouplers.amount 1.05`, a small yellow filtration shift (`yFilterShift` 2).

## Landscape

### Ektar 100  (`ektar_100`)
**Kodak Ektar 100 → Kodak Supra Endura.** The finest-grain colour negative, known for saturated
skies and foliage. Printed on Supra Endura rather than the contrastier Ultra Endura: in side-by-side
renders the Ultra pairing pushed reds and skin toward magenta.
Tuning: `exposureCompensationEv 0.0`, `densityCurveGamma 1.05`, `grain.blur 0.5` with fine
`agxParticleScale [0.6, 0.8, 1.6]`, `halation 0.7`, `dirCouplers.amount 1.1`,
`scanner.unsharpMask [0.7, 0.7]`.

## Night

### CineStill 800T  (`cinestill_800t`)
**CineStill 800T → Kodak 2383.** Kodak Vision3 500T with its rem-jet backing removed. Without the
backing, light reflects off the film base, so bright lights bloom into the red halos the stock is
known for; the profile's anti-halation tag already models that, and `boostEv 0.6` lets clipped
practicals and street lights drive it harder. It is tungsten-balanced, so in daylight or mixed light
an uncorrected print goes strongly blue; the look applies the correction a lab scan would
(`yFilterShift` −20, `mFilterShift` −10), leaving the cool, cinematic cast it is liked for.
Tuning: `exposureCompensationEv 0.3` (it is commonly rated slower than box speed),
`densityCurveGamma 1.0`, `grain.blur 0.68`, `halation 1.0`, `dirCouplers.amount 1.0`.

## Slide

### Kodachrome 64  (`kodachrome_64`)
**Kodak Kodachrome 64, scanned as a slide.** The K-14 reversal film: rich reds, deep blues and
punchy contrast. Kodak discontinued it in 2009 and processing ended in 2010, so emulation
is the only way to get the look. Reversal film is already a positive, so `io.scanFilm` skips the print.
Tuning: `exposureCompensationEv 0.0`, `densityCurveGamma 1.04`, `grain.blur 0.55`, `halation 0.6`,
`dirCouplers.amount 1.05`, `scanner.unsharpMask [0.8, 0.7]`.

## Black & white

### Tri-X 400  (`tri_x_400`)
**Kodak Tri-X 400 → Ilford Multigrade IV RC, grade 3.** The classic high-speed black-and-white
negative: gritty grain, deep blacks and bright midtones. It prints on Multigrade because a silver
film on colour paper renders orange (see `SilverPrint.kt`). Grade 3 is one step harder than the
neutral grade 2, for the punchy darkroom print Tri-X is associated with.
Tuning: `exposureCompensationEv 0.0`, `densityCurveGamma 1.0`, `grain.blur 0.6` with
`agxParticleScale [1.3, 1.3, 1.3]` (silver grain is the same in every channel), `halation 0.8`,
DIR couplers off, `scanner.unsharpMask [0.7, 0.8]`.
