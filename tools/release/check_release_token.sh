#!/usr/bin/env bash
# Exercise RELEASE_GITHUB_TOKEN with the calls tools/release/github_release.py
# depends on, in under a minute, against a draft that is deleted again (drafts
# create no tag and are not immutable):
#   GET    /user, /immutable-releases -> token valid + Administration (read)
#   POST   /releases (draft)          -> Contents (write) + Workflows (write)
#   GET    /releases listing          -> drafts visible to this token
#   DELETE /releases/{id}             -> Contents (write)
#
# Used by release-token-check.yml (manual) and by release.yml's first protected
# job, so an expired token fails before the hour-long candidate build instead of
# at the final upload (v1.0.0 run 42975413550: HTTP 401 "Bad credentials" after
# signing and device smoke had passed).
#
# Env: GH_TOKEN, GH_REPO (owner/name), TARGET_SHA (commit for the draft),
#      CHECK_ID (unique suffix for the draft tag name).
set -euo pipefail

: "${GH_REPO:?}" "${TARGET_SHA:?}" "${CHECK_ID:?}"
if [ -z "${GH_TOKEN:-}" ]; then
  echo "::error::RELEASE_GITHUB_TOKEN is empty in environment release-signing"
  exit 1
fi

if ! gh api user -q '"token identity: \(.login)"'; then
  echo "::error::RELEASE_GITHUB_TOKEN was rejected (expired or revoked?). Create a new fine-grained token with Administration read, Contents write and Workflows write on this repository, replace the release-signing secret, and run this check again."
  exit 1
fi

immutable="$(gh api "repos/$GH_REPO/immutable-releases")"
printf '%s\n' "$immutable"
if ! grep -q '"enabled":true' <<<"$immutable"; then
  echo "::error::immutable releases are not enabled"
  exit 1
fi

tag="token-check-${CHECK_ID}"
id="$(gh api -X POST "repos/$GH_REPO/releases" \
  -f tag_name="$tag" \
  -f target_commitish="$TARGET_SHA" \
  -f name="token check ${CHECK_ID}" \
  -F draft=true -q .id)"
echo "draft release id: $id"
cleanup() { gh api -X DELETE "repos/$GH_REPO/releases/$id" || true; }
trap cleanup EXIT

# github_release.py re-discovers the draft it just created through
# GET /releases (drafts are listed only for tokens with push access). Run
# 34919864173 created the draft, read it back by id, then found no entry for its
# tag in the listing. Prove the listing, immediately and after a short wait.
n=0
for attempt in 1 2 3; do
  n="$(gh api "repos/$GH_REPO/releases?per_page=30" \
    -q "[.[] | select(.tag_name == \"$tag\")] | length")"
  echo "listing attempt $attempt: $n release(s) carry tag $tag"
  [ "$n" = "1" ] && break
  sleep 5
done
gh api "repos/$GH_REPO/releases/$id" -q '"by id: \(.id) draft=\(.draft) tag=\(.tag_name)"'
trap - EXIT
gh api -X DELETE "repos/$GH_REPO/releases/$id"
if [ "$n" != "1" ]; then
  echo "::error::GET /releases never listed the draft this token created; github_release.py cannot publish with it"
  exit 1
fi
echo "RELEASE_GITHUB_TOKEN: PASS (valid, Administration read, Contents write, Workflows write, draft listing)"
