# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| 0.9.x | Yes — current development line |
| < 0.9 | No |

Only builds published on the [Releases](https://github.com/thetechgeekko/Spektrafilm-android/releases)
page are supported. CI artifacts are development evidence, not production-signed releases, and are
not covered by this policy.

## Reporting a vulnerability

Use GitHub's private reporting: **Security → Advisories → Report a vulnerability** on this
repository. That keeps the report private until a fix ships.

Please do not open a public issue for a vulnerability.

Include what you have: affected version and `versionCode`, device and Android version, the steps
that reproduce it, and what an attacker gains. A crash is not automatically a vulnerability — say
what the security impact is.

Expect an acknowledgement within a week. This is a single-maintainer project, so please allow
reasonable time before disclosing publicly.

## Scope

This is an offline photo editor. It requests no network permission, so there is no server side and
no account to compromise. Realistic reports concern:

- **Malformed input files.** RAW, DNG, JPEG, PNG, TIFF and preset JSON all cross a trust boundary
  into native code. Memory-safety bugs in the decoders are in scope; LibRaw is vendored and patched,
  and the patches are listed in [`docs/dependencies/LIBRAW.md`](docs/dependencies/LIBRAW.md).
- **Storage and URI handling.** The app takes persistable URI permissions and writes sidecars. Path
  traversal, writing outside granted trees, or leaking another app's data is in scope. The contract
  is in [`docs/TRANSACTIONAL_STORAGE.md`](docs/TRANSACTIONAL_STORAGE.md).
- **The JNI boundary.** Buffer lifetime, cancellation, and use-after-free across the native seam are
  in scope. The contract is in [`docs/JNI_LIFETIME_SAFETY.md`](docs/JNI_LIFETIME_SAFETY.md).
- **Release integrity.** Signing, the update advisory path
  ([`docs/UPDATER_SECURITY.md`](docs/UPDATER_SECURITY.md)), and anything that would let an
  unofficial build present as official.

Out of scope: rendering that you disagree with aesthetically, performance, and reports produced by
scanners without a demonstrated impact on this app.

## What the project already does

Release builds are signed only inside a protected CI environment that requires maintainer approval;
the workflow refuses to fall back to the debug key. Candidate identity is bound to source, version,
run ID, artifact digests, both SPDX documents, and the runtime classpath, and installed-byte
identity is proved on a device before publication. Sanitizer jobs (ASan, UBSan, TSan) and a fuzz
job run over the native decoders in CI.
