## What changed

<!-- One or two sentences. What does this do that the tree did not do before? -->

## Why

<!-- The problem, not the patch. Link the issue if there is one. -->

Closes #

## Evidence

<!-- Numbers, not adjectives. Paste what you measured and how. -->

- [ ] `./gradlew :app:testDebugUnitTest :app:lint`
- [ ] Engine parity, `-O2` leg
- [ ] Engine parity, shipping flags (`-O3 -ffast-math -fno-finite-math-only`)
- [ ] Device measurement, if this claims a performance change (state build type and device)

<!--
If this touches engine/spektra-core/src/main/cpp/**, parity is not optional and
"it looked the same" is not evidence. If it claims a speed-up, a debug-build
timing does not count.
-->

## Risk

<!--
What could this break that the gates would not catch? If nothing, say so and
why. If it changes the rendered image at all, say that plainly here.
-->
