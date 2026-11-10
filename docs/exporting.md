# Exporting

Tap the save button (the arrow pointing down) at the top of the editor. The **Export** sheet opens
with the format, quality, size and border; tap **Export**. Saved photos go to
`Pictures/Spektrafilm`. The share button next to it sends the photo straight to another app.

Export always renders the whole photo at full resolution with every effect, so it takes longer
than the preview. A progress bar shows each stage, and the export keeps running if you switch
apps. When it finishes, a bar offers **Share** and **Open** until your next edit.

## Formats

| Format | Use it for |
|---|---|
| **JPEG** | Sharing and social media. Set the quality from 0 to 100. |
| **PNG (8-bit)** | Lossless, for the web. |
| **16-bit PNG** | Lossless with full tonal depth, for further editing. |
| **16-bit TIFF** | Further editing in a desktop app or for print. |
| **32-bit float TIFF** | The rendered image with no rounding, for grading. |
| **Scene-linear input (32-bit TIFF)** | The decoded photo before the film simulation, in linear light, for grading elsewhere. It is not tagged with a colour profile on purpose. |
| **Ultra HDR (JPEG)** | Experimental. A JPEG with a gain map for HDR screens. |

In the sheet you can also set the size by its long edge in pixels; the photo is never enlarged.
**Settings > Export** sets the default format and JPEG quality.

## Color spaces

The output color space is in **Edit > Output**: sRGB, Adobe RGB, ProPhoto RGB, Rec. 2020,
ACES 2065-1 or linear sRGB. Use sRGB unless you know you need another.

16-bit PNG and the TIFF formats carry the matching ICC profile. JPEG and 8-bit PNG are tagged on
Android 8.0 and later; on Android 7 they are tagged as plain sRGB.

**Gamut compression** (also in Output) decides what happens to colours too saturated for the
color space: a simple clip, or a gentler perceptual squeeze (ACES, Oklch, Oklrab, Jzazbz or
CAM16).

## Frames and borders

**Edit > Frame**, or the border section of the Export sheet, adds a border around the photo on export. The photo is never cropped.

- **Style**: white print, black edge, 35 mm film strip, medium-format roll, instant print,
  gallery mat, half-frame strip or rough filed edge.
- **Border width** and **Colour**: paper white, warm cream, grey, black, or a colour taken from
  the photo.
- **Fit to aspect** pads the frame to 1:1, 4:5 or 9:16, handy for social media.
- **Caption**: shot details, the date, the film stock or your own text, in sans, serif or mono.
- **Edge markings** on film strips, with a custom label and frame number.

JPEG, Ultra HDR and PNG carry the frame. 16-bit and 32-bit formats export the bare photo. The
frame is an export setting, so it stays the same from photo to photo and Undo does not change it.

## Location and metadata

Camera details (make, model, lens, exposure, date) are copied into JPEG exports. GPS location is
removed by default. Turn on **Settings > Preserve location (GPS)** to keep it.

## Exporting many photos

In the Library, select several photos and choose export. Each photo keeps its own edit: film,
print, crop and masks. You can stop the batch at any time.

## Large photos

Photos above about 16 megapixels are rendered in overlapping horizontal bands so they fit in your
phone's memory. If a photo is still too large for the free memory, the app says so and suggests
a smaller size. Closing other apps first helps.

## 3D LUTs

**Looks > Presets** can bake the current film and print look into a 3D LUT, at 17³, 33³ or 65³:

- `.cube` imports into most photo and video apps.
- `.clf` (Common LUT Format) imports into DaVinci Resolve and other ACES tools.

A LUT maps one colour to another, so it cannot carry anything that depends on where a pixel is
or on chance: grain, halation, diffusion, blur, masks and crop are left out. Use it to match the
colour of a look in another app.

Next: [How it works](how-it-works.md).
