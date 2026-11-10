# FAQ

## Is it free?

Yes. Spektrafilm is free and open source under the GPLv3. There are no ads, no account and no
in-app purchases.

## Why isn't it on Google Play?

It is distributed as an APK on [GitHub Releases](https://github.com/thetechgeekko/Spektrafilm-android/releases),
and updates itself from there. Only download it from that page.

## Which phones does it run on?

Android 7.0 or later, on 64-bit ARM (most phones), 32-bit ARM and x86_64. It renders on the GPU
through Vulkan where the phone supports it and falls back to the CPU otherwise. A phone with more
memory handles larger photos and bigger previews; the app picks a preview size for your phone
automatically.

## Which files can I open?

JPEG, HEIC and most camera RAW and DNG files. RAW files are decoded with
[LibRaw](https://www.libraw.org/). If a DNG can't be read as RAW, the app opens it through
Android's own decoder and tells you so. Video and MotionCam `.mcraw` clips are not supported yet.

## Why does my RAW look different from my camera's JPEG?

Your camera's JPEG already has the camera maker's colour, contrast and sharpening baked in. A RAW
file has none of that, so Spektrafilm starts from the scene itself and the film does the work.
That is why RAW files give the most faithful film looks. If the white balance is off, use the
eyedropper in **Edit > Color**, or set the camera white balance there.

## Why does my photo look blue with CineStill 800T or Vision3 500T?

Those are tungsten films, balanced for indoor light. In daylight real tungsten film turns blue
too. Turn on **Balance to film stock** in **Edit > Color** to correct it, the way an 85 filter
would.

## Why does the grain change between previews?

Grain is simulated as random silver or dye particles, so every render is a little different, just
like two prints from two frames. The export renders grain at full resolution.

## Does the app change my original photo?

No. The app only reads your photo. Edits are stored as a small recipe file keyed to that photo,
and the export is saved as a new file in `Pictures/Spektrafilm`.

## Privacy: does anything leave my phone?

Your photos never leave your phone unless you share or export them yourself. There is no
analytics, no advertising SDK, no account and no crash uploader. The app only goes online for:

- **Update checks.** At most once a day it asks GitHub whether a new release exists. You can turn
  this off in Settings. An update is only downloaded when you tap **Update**.
- **The skin mask model.** The first time you use **Skin (AI)**, the app downloads the
  segmentation model (about 16 MB) from Google's model storage. The app checks the file against a
  fixed fingerprint before using it. Your photo is not sent anywhere; the model runs on the phone.

GPS location is removed from exports unless you turn on **Preserve location (GPS)**. Crash
reports stay on the phone in **Diagnostics** (editor menu) until you choose to copy or share them.

## Which languages does the app speak?

English, German, French, Spanish, Brazilian Portuguese, Japanese, Korean and Simplified Chinese.
The app follows your phone's language. To pick another one for Spektrafilm only, use
**Settings > App language**.

## Can I use the looks in other apps?

Yes, as a 3D LUT. **Looks > Presets** can bake the current look into a `.cube` or `.clf` file.
A LUT carries colour only, not grain, halation or other effects. See [Exporting](exporting.md#3d-luts).

## How do I report a bug or ask for a feature?

Open an issue on [GitHub Issues](https://github.com/thetechgeekko/Spektrafilm-android/issues). It
helps to include your phone model, the app version (in **About**), and what you did. If the app
crashed, **Diagnostics** in the editor menu can copy a report with file names and locations removed.
For a security problem, follow [SECURITY.md](../SECURITY.md) instead of opening a public issue.
