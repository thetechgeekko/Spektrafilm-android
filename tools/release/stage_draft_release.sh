#!/usr/bin/env bash
# Last step of release.yml: put the exact signed, device-verified files on a
# DRAFT GitHub Release with the built-in GITHUB_TOKEN (contents: write). The
# maintainer edits the notes and presses Publish, which creates the tag at
# RELEASE_SHA; repository immutable releases then lock tag and assets.
#
# A draft creates no tag and is invisible to users and to the in-app updater,
# so a bad run costs nothing: delete the draft and run again.
#
# Usage: stage_draft_release.sh <assets-dir>
# Env:   GH_TOKEN, GH_REPO, VERSION, RELEASE_SHA, GITHUB_RUN_ID
set -euo pipefail

ASSETS_DIR="${1:?assets dir}"
: "${GH_REPO:?}" "${VERSION:?}" "${RELEASE_SHA:?}" "${GITHUB_RUN_ID:?}"

mapfile -t FILES < <(find "$ASSETS_DIR" -maxdepth 1 -type f | LC_ALL=C sort)
if [ "${#FILES[@]}" -eq 0 ]; then
  echo "::error::No release assets in $ASSETS_DIR"
  exit 1
fi

# A published release for this tag means the version is already out.
if gh api "repos/${GH_REPO}/releases/tags/${VERSION}" --jq .id >/dev/null 2>&1; then
  echo "::error::A published release for ${VERSION} already exists"
  exit 1
fi

RUN_URL="https://github.com/${GH_REPO}/actions/runs/${GITHUB_RUN_ID}"
BODY="Built and signed from ${RELEASE_SHA} by ${RUN_URL}. Replace this text with the release notes, then press Publish (it creates tag ${VERSION} on that commit)."

release_id="$(gh api -X POST "repos/${GH_REPO}/releases" \
  -f tag_name="$VERSION" \
  -f target_commitish="$RELEASE_SHA" \
  -f name="Spektrafilm ${VERSION}" \
  -f body="$BODY" \
  -F draft=true \
  -F prerelease=false \
  -F generate_release_notes=false \
  --jq .id)"
if [[ ! "$release_id" =~ ^[0-9]+$ ]]; then
  echo "::error::Draft creation did not return a release ID"
  exit 1
fi
cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then
    echo "Deleting incomplete draft ${release_id}"
    gh api -X DELETE "repos/${GH_REPO}/releases/${release_id}" || true
  fi
}
trap cleanup EXIT

declare -A EXPECTED
for file in "${FILES[@]}"; do
  name="$(basename "$file")"
  if [[ ! "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
    echo "::error::Unsafe asset name: $name"
    exit 1
  fi
  EXPECTED["$name"]="sha256:$(sha256sum "$file" | cut -d' ' -f1)"
  gh api -X POST -H "Content-Type: application/octet-stream" \
    "https://uploads.github.com/repos/${GH_REPO}/releases/${release_id}/assets?name=${name}" \
    --input "$file" --jq .id >/dev/null
done

# Read back by numeric ID: exactly our names, each with GitHub's digest of the
# stored bytes equal to the local signed file. Still a draft, still on our SHA.
release_json="$(gh api "repos/${GH_REPO}/releases/${release_id}")"
python3 - "$release_json" "$VERSION" "$RELEASE_SHA" "${#FILES[@]}" <<'PY' "${!EXPECTED[@]}" "${EXPECTED[@]}"
import json, sys
release = json.loads(sys.argv[1])
version, sha, count = sys.argv[2], sys.argv[3], int(sys.argv[4])
rest = sys.argv[5:]
expected = dict(zip(rest[:count], rest[count:]))
if release.get("draft") is not True:
    raise SystemExit("::error::release is no longer a draft")
if release.get("tag_name") != version or release.get("target_commitish") != sha:
    raise SystemExit("::error::draft tag or target changed")
actual = {a["name"]: a.get("digest") for a in release.get("assets", [])}
if actual != expected:
    raise SystemExit(f"::error::draft assets differ: expected={expected} actual={actual}")
for name, digest in sorted(actual.items()):
    print(f"{name}  {digest}")
PY

trap - EXIT
html_url="$(jq -r .html_url <<<"$release_json")"
echo "Draft ready: ${html_url}"
echo "### Draft release ready: [${VERSION}](${html_url})" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
echo "Add the notes and press **Publish** to create tag \`${VERSION}\` on \`${RELEASE_SHA}\`." >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
