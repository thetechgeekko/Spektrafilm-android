#!/usr/bin/env bash
# First job of release.yml. Fails in seconds, before anything is built, unless:
#   - the run was dispatched on main,
#   - VERSION is a stable vMAJOR.MINOR.PATCH with no leading zeros,
#   - no tag of that name exists yet (Publish on the draft creates it),
#   - this exact commit has a successful full ci.yml run.
# Env: GH_TOKEN, GH_REPO, EVENT_REF, RELEASE_SHA, VERSION. Writes version, sha,
# ci_run_id to $GITHUB_OUTPUT.
set -euo pipefail

if [ "$EVENT_REF" != "refs/heads/main" ]; then
  echo "::error::Run the Release workflow on main (got $EVENT_REF)"
  exit 1
fi
if [[ ! "$VERSION" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "::error::Version must be a stable vMAJOR.MINOR.PATCH value without leading zeros"
  exit 1
fi
if [[ ! "$RELEASE_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "::error::Workflow commit is not one SHA"
  exit 1
fi
if [ -n "$(git ls-remote --tags origin "refs/tags/${VERSION}")" ]; then
  echo "::error::Tag ${VERSION} already exists. Bump the version; published tags cannot move."
  exit 1
fi

ci_run_id="$(gh api \
  "repos/${GH_REPO}/actions/workflows/ci.yml/runs?head_sha=${RELEASE_SHA}&status=success&per_page=1" \
  --jq '.workflow_runs[0].id // empty')"
if [[ ! "$ci_run_id" =~ ^[0-9]+$ ]]; then
  echo "::error::No successful full CI (ci.yml) run for ${RELEASE_SHA}. Run Actions -> CI -> Run workflow on main, wait for it to pass, then start the release again."
  exit 1
fi
echo "Full CI passed on this commit: https://github.com/${GH_REPO}/actions/runs/${ci_run_id}"

{
  echo "version=${VERSION}"
  echo "sha=${RELEASE_SHA}"
  echo "ci_run_id=${ci_run_id}"
} >> "$GITHUB_OUTPUT"
