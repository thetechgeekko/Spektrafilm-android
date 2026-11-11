#!/usr/bin/env bash
# release.yml creates no tag; the maintainer's Publish on the draft does. Between
# jobs, refuse to continue if someone created VERSION's tag on another commit.
# Env: VERSION, RELEASE_SHA.
set -euo pipefail

line="$(git ls-remote --tags origin "refs/tags/${VERSION}^{}" "refs/tags/${VERSION}")"
if [ -z "$line" ]; then
  echo "tag ${VERSION} not created yet (expected)"
  exit 0
fi
peeled="$(awk -v ref="refs/tags/${VERSION}^{}" '$2 == ref {print $1}' <<<"$line")"
direct="$(awk -v ref="refs/tags/${VERSION}" '$2 == ref {print $1}' <<<"$line")"
target="${peeled:-$direct}"
if [ "$target" != "$RELEASE_SHA" ]; then
  echo "::error::Tag ${VERSION} was created on ${target}, not the built commit ${RELEASE_SHA}"
  exit 1
fi
echo "tag ${VERSION} already points to the built commit"
