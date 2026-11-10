# Films and papers

Spektrafilm simulates 25 films and 9 papers and print stocks. Each profile comes from measured
spectral data: how the film's layers respond to each wavelength, how its dyes develop, and how
dense they get. Profiles come from the upstream
[spektrafilm](https://github.com/andreavolpato/spektrafilm) project, except the black-and-white
films and Multigrade paper, which were added for this app.

Every film has a usual paper, which the app picks for you. A colour film prints on any paper you
choose. Black-and-white films print on Multigrade, because silver film on colour paper turns
orange.

## Color negative

| Film | Light | Usual paper | Known for |
|---|---|---|---|
| Kodak Portra 160 | Daylight | Portra Endura | Low contrast, fine grain, natural skin |
| Kodak Portra 400 | Daylight | Portra Endura | The all-round portrait film, soft and forgiving |
| Kodak Portra 800 | Daylight | Portra Endura | Low light, more grain |
| Kodak Portra 800 (Push 1 / Push 2) | Daylight | Portra Endura | Portra 800 shot one or two stops under and pushed in development: more contrast and grain |
| Kodak Ektar 100 | Daylight | Portra Endura | Saturated colour and very fine grain, for landscapes |
| Kodak Gold 200 | Daylight | Portra Endura | Warm, nostalgic everyday film |
| Kodak UltraMax 400 | Daylight | Portra Endura | Punchy consumer film |
| Fujifilm Pro 400H | Daylight | Crystal Archive Type II | Cool, airy pastels |
| Fujifilm C200 | Daylight | Crystal Archive Type II | Fuji consumer colour, greens and blues |
| Fujifilm X-Tra 400 | Daylight | Crystal Archive Type II | Fuji consumer 400 |
| CineStill 800T | Tungsten | Portra Endura | Cinema film for stills, night scenes and glowing highlights |

## Cinema

Motion-picture negatives, printed on cinema print film by default.

| Film | Light | Usual print | Known for |
|---|---|---|---|
| Kodak Vision3 50D | Daylight | Vision 2383 | Finest grain, bright daylight |
| Kodak Vision3 250D | Daylight | Vision 2383 | Daylight all-rounder |
| Kodak Vision3 200T | Tungsten | Vision 2383 | Indoor and mixed light |
| Kodak Vision3 500T | Tungsten | Vision 2383 | Night and low light, the classic cinema look |
| Kodak Verita 200D | Daylight | Vision 2383 | Daylight cinema stock |

## Slide

Slide films are positives: the film itself is the picture. When you pick one, the app offers
**Slide mode**, which shows the film without printing it.

| Film | Known for |
|---|---|
| Fujifilm Velvia 100 | Very saturated colour and deep contrast |
| Fujifilm Provia 100F | Accurate, neutral colour |
| Kodak Ektachrome 100 | Clean colour with cool blues |
| Kodak Kodachrome 64 | Rich reds and the look of decades of magazines |

## Black and white

These print on Ilford Multigrade IV RC, where **Paper grade** (Edit > Print) sets contrast from
00 (softest) to 5 (hardest) while mid-grey stays put.

| Film | Known for |
|---|---|
| Kodak Tri-X 400 | Classic grain and contrast, street and documentary |
| Ilford HP5 Plus 400 | Wide latitude, pushes well |
| Kodak T-Max 100 | Very fine grain, smooth tones |
| Fujifilm Neopan 100 Acros II | Fine grain, deep blacks |

## Papers and print stocks

| Paper | Notes |
|---|---|
| Kodak Professional Portra Endura | The usual pairing for Kodak colour negatives, gentle contrast |
| Kodak Professional Endura Premier | Kodak's newer colour paper |
| Kodak Professional Supra Endura | Slightly more contrast and saturation |
| Kodak Professional Ultra Endura | The most saturated Kodak paper |
| Kodak Ektacolor Edge | Consumer lab paper |
| Fujifilm Crystal Archive Type II | The usual pairing for Fuji films |
| Ilford Multigrade IV RC | Black-and-white paper with grades 00 to 5 |
| Kodak Vision 2383 | Cinema print film, the theatrical look |
| Kodak Vision Premier 2393 | Cinema print film with deeper blacks and more saturation |

The "Known for" notes describe each stock's usual reputation, not a measurement. The best way to
choose is the film strip in **Looks**, which shows your own photo through every film.

## The six built-in looks

| Look | Film on paper | For |
|---|---|---|
| Portra 400 | Portra 400 on Portra Endura | Portraits |
| Gold 200 | Gold 200 on Ektacolor Edge | Everyday |
| Ektar 100 | Ektar 100 on Supra Endura | Landscapes |
| CineStill 800T | CineStill 800T on Vision 2383 | Night |
| Kodachrome 64 | Kodachrome 64 in Slide mode | Slide |
| Tri-X 400 | Tri-X 400 on Multigrade, grade 3 | Black and white |

They are the films photographers most often buy and emulate. Why these six, and their exact
settings, are in [PRESETS.md](PRESETS.md).
