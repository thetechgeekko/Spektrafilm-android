# Updater security contract

Spektrafilm checks GitHub for a newer stable release, shows its release notes ("What's new"), and,
when the user taps **Update**, downloads the release APK and hands it to Android's installer. Before
2026-11 the updater was browser-only (it opened the release page and never touched an APK); that
design is in git history. This document is the contract for the in-app design that replaced it.

## The integrity gate: signing-certificate continuity

The repository does not ship a separately signed update-metadata format, and this design does not
pretend to. The APK is already a signed artifact: only the holder of the production key can produce
an APK that Android will install over the production-signed app. So the gate is:

1. **Before the installer** (`UpdateVerifier`, `UpdateInstaller.verify`): the downloaded file's own
   manifest and signature block must say it is `com.spectrafilm.app`, its `versionCode` is strictly
   greater than the installed one (no downgrade or replay of an older release), its `versionName`
   equals the release tag without `v`, and its signers include **every** certificate that signs the
   installed app right now (a newer key is accepted only through a proven APK Signature Scheme v3
   rotation lineage that contains the installed key). Anything else is deleted and the dialog offers
   the release page instead.
2. **At install** Android's package manager enforces the same signing-certificate continuity again,
   and the user confirms the update in the system installer UI. The app never installs silently.

Consequences, by design:

- Whatever served the bytes (GitHub, a CDN, a TLS-intercepting proxy, a compromised release asset),
  an APK not signed with the production key cannot get past step 1 or step 2.
- A debug or self-built install (different key) is never updated in place; it gets a clear message
  and the release page.
- The SHA-256 GitHub reports for an asset is checked when present, but it comes from the same
  response as the URL, so it only catches truncation and accidents. It is not an integrity control.
- A compromised production key is out of scope for any client-side check; it is protected by the
  release workflow's environment rules (`docs/RELEASE_CHECKLIST.md`).

## Accepted metadata request and response

The only metadata request is:

```text
GET https://api.github.com/repos/thetechgeekko/Spektrafilm-android/releases/latest
```

The request contract is fail closed:

- exact HTTPS scheme, `api.github.com` host, default port, repository path, and no user-info, query, or
  fragment;
- platform system trust anchors and cleartext disabled;
- connection and read timeouts of 5 seconds each;
- automatic redirects disabled; any 3xx is failure rather than a second request;
- HTTP 200 only, with `application/json` or `application/vnd.github+json` media type;
- declared and streamed response size limited to 256 KiB (a real release with 14 assets and long notes
  is about 33 KiB);
- strict UTF-8 decoding and fail-closed handling for a non-progressing response stream.

Accepted metadata must contain exact boolean `draft: false` and `prerelease: false`, a canonical
`vMAJOR.MINOR.PATCH` tag without leading-zero components, and this exact browser destination:

```text
https://github.com/thetechgeekko/Spektrafilm-android/releases/tag/<same-tag>
```

From an accepted response the app also takes:

- **Release notes**: the `body` string, HTML comments removed, at most 20,000 characters. The dialog
  renders a small Markdown subset as text. Links show their label only; no URL from the notes is ever
  opened.
- **The APK asset**, only if exactly one asset is named `Spektrafilm-<tag>.apk`, has `state:
  "uploaded"`, a size of 1 byte to 256 MiB, and exactly this download URL:

  ```text
  https://github.com/thetechgeekko/Spektrafilm-android/releases/download/<tag>/Spektrafilm-<tag>.apk
  ```

  Otherwise there is no in-app install and the dialog offers the release page.

The browser URL is validated again immediately before creating the browser Intent, so even
caller-constructed `UpdateInfo` cannot select another destination.

## Download

`UpdateDownloader` runs only after the user taps **Update**:

- the first request must be the exact asset URL above;
- redirects are followed by hand, at most three, and only to `https` on
  `objects.githubusercontent.com` or `release-assets.githubusercontent.com`, default port, no
  user-info or fragment (the pre-signed object-store query string is allowed);
- connection timeout 10 s, read timeout 20 s;
- HTTP 200 only; a declared `Content-Length` must equal the asset size, and the body must be exactly
  that many bytes (never more is read);
- when the API reported `digest: sha256:...` the bytes must match it;
- the file is written to `noBackupFilesDir/updater/` as `.part`, renamed only when complete, and the
  directory is cleared on dismissal, after a failed verification, and on the next app start.

## Install

`UpdateInstaller` streams the verified file into a `PackageInstaller` session and commits it with an
explicit, mutable `PendingIntent` to `UpdateInstallReceiver`, which is **not exported**, so only the
system installer can deliver a status (and the confirmation `EXTRA_INTENT` it launches) to it. The
user confirms in the system UI (including, the first time, Android's "allow installs from this
source" step). Success replaces the process; a cancel returns the dialog to its offer; a failure
offers the release page.

## When the check runs

- Automatically, at most once every 24 hours when the app opens, if Settings > "Check for updates
  automatically" is on (the default). A version the user chose to **Skip** is not offered again by
  the automatic check; a manual check always shows a newer release.
- When the user taps Settings > "Check for updates".
- **Never automatically on a copy installed by Google Play**, and a Play copy is never self-updated:
  Play policy forbids self-update. A Play build of this app must also remove
  `REQUEST_INSTALL_PACKAGES` from its manifest (`tools:node="remove"`) and, if it wants in-app
  updates, use Play In-App Updates instead. That is not implemented yet (#200).

## Verification

- `AppUpdaterTest`: valid stable metadata; redirect, media-type, declared/streamed oversize,
  scheme/host/user-info/repository/path/tag/query, draft/prerelease, malformed tag, malformed UTF-8 and
  non-progressing streams; release-notes cleanup and bound; APK asset acceptance and every rejection
  (wrong host/scheme/tag/query/name, zero or oversize, not uploaded, duplicated).
- `UpdateDownloadPolicyTest`: start-URL pinning, redirect host/scheme/port/user-info/fragment
  rejection, exact-length copy with digest, short/long/stalled/cancelled streams.
- `UpdateVerifierTest`: package, downgrade/replay, version name, signer mismatch (including a debug
  install and an empty signer set), rotation lineage.
- `UpdateControllerTest`: daily gate, auto-check switch, skip version, Play installs, download →
  verify → install, cancel and failure paths, release-page fallback.
- `BackupSecurityConfigTest`: cleartext denial, system trust, the exact documented domains, the
  permission, the non-exported receiver with no intent filter, and updater state outside backup.

Android's declarative TLS/cleartext controls are documented in
[Network Security Configuration](https://developer.android.com/privacy-and-security/security-config).
