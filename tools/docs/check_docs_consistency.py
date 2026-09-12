#!/usr/bin/env python3
"""Offline consistency checks for Spektrafilm's current authority documents."""

from __future__ import annotations

import json
import re
import string
import sys
import unicodedata
from pathlib import Path
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[2]
CURRENT_DOCS = (
    ROOT / "README.md",
    ROOT / "CLAUDE.md",
    # Root-level docs a contributor lands on first. These were added later and
    # were never link-checked, so a dead relative link in CONTRIBUTING.md would
    # have shipped silently (#185).
    ROOT / "CONTRIBUTING.md",
    ROOT / "SECURITY.md",
    ROOT / "CHANGELOG.md",
    ROOT / "NOTICE.md",
    ROOT / "docs" / "EXECUTION_INDEX.md",
    ROOT / "docs" / "ARCHITECTURE.md",
    ROOT / "docs" / "PRODUCTION_READINESS_PLAN.md",
    ROOT / "docs" / "BIT_IDENTICAL_EXPORT_ROADMAP.md",
    ROOT / "docs" / "RELEASE_CHECKLIST.md",
    ROOT / "docs" / "MOBILE_STRATEGY.md",
    ROOT / "docs" / "PRESETS.md",
    ROOT / "docs" / "LICENSING.md",
    ROOT / "docs" / "JNI_LIFETIME_SAFETY.md",
    ROOT / "docs" / "TRANSACTIONAL_STORAGE.md",
    ROOT / "docs" / "RAW_DNG.md",
    ROOT / "docs" / "MASK_JSON_SCHEMA.md",
    ROOT / "engine" / "spektra-core" / "README.md",
    ROOT / "lib" / "libraw" / "README.md",
)

_COMMONMARK_ESCAPABLE = frozenset(string.punctuation)


def _extract(pattern: str, text: str, source: Path) -> str:
    match = re.search(pattern, text, flags=re.MULTILINE)
    if not match:
        raise ValueError(f"could not derive {pattern!r} from {source.relative_to(ROOT)}")
    return match.group(1)


def _without_fenced_code(text: str) -> str:
    output: list[str] = []
    fence_char = ""
    fence_length = 0
    for line in text.splitlines(keepends=True):
        marker = re.match(r"^\s*(`{3,}|~{3,})", line)
        if marker:
            token = marker.group(1)
            if not fence_char:
                fence_char, fence_length = token[0], len(token)
            elif token[0] == fence_char and len(token) >= fence_length:
                fence_char, fence_length = "", 0
            output.append("\n" if line.endswith(("\n", "\r")) else "")
        elif fence_char:
            output.append("\n" if line.endswith(("\n", "\r")) else "")
        else:
            output.append(line)
    return "".join(output)


def _markdown_link_targets(text: str) -> list[str]:
    """Return common inline/image/reference Markdown targets outside fenced code."""
    text = _without_fenced_code(text)
    targets: list[str] = []
    cursor = 0
    while True:
        marker = text.find("](", cursor)
        if marker < 0:
            break
        start = marker + 2
        if start < len(text) and text[start] == "<":
            end = text.find(">", start + 1)
            if end >= 0 and end + 1 < len(text) and text[end + 1] == ")":
                targets.append(text[start : end + 1])
                cursor = end + 2
                continue
        depth = 1
        index = start
        while index < len(text) and depth:
            if text[index] == "\\":
                index += 2
                continue
            if text[index] == "(":
                depth += 1
            elif text[index] == ")":
                depth -= 1
            index += 1
        if depth == 0:
            targets.append(text[start : index - 1])
            cursor = index
        else:
            cursor = start
    targets.extend(
        match.group(1)
        for match in re.finditer(
            r"^\s*\[[^\]]+\]:\s*(<[^>\r\n]+>|[^\s]+)",
            text,
            flags=re.MULTILINE,
        )
    )
    return targets


def _unescape_markdown_destination(target: str) -> str:
    """Apply CommonMark backslash escapes used inside link destinations."""
    output: list[str] = []
    index = 0
    while index < len(target):
        if (
            target[index] == "\\"
            and index + 1 < len(target)
            and target[index + 1] in _COMMONMARK_ESCAPABLE
        ):
            output.append(target[index + 1])
            index += 2
        else:
            output.append(target[index])
            index += 1
    return "".join(output)


def _exact_local_path_error(base: Path, portable_path: str) -> str | None:
    """Return an error when a local path component is absent or mis-cased."""
    current = base
    for component in portable_path.split("/"):
        if not component or component == ".":
            continue
        if component == "..":
            current = current.parent
            continue
        try:
            children = tuple(current.iterdir())
        except OSError:
            return "missing local link target"
        exact = next((child for child in children if child.name == component), None)
        if exact is not None:
            current = exact
            continue
        folded = next(
            (child for child in children if child.name.casefold() == component.casefold()),
            None,
        )
        if folded is not None:
            return (
                "on-disk spelling mismatch for component "
                f"{component!r}; found {folded.name!r}"
            )
        return "missing local link target"
    return None


def _heading_slug(title: str) -> str:
    """GitHub's heading slug: strip inline markup, lowercase, spaces to hyphens.

    Deliberately conservative -- it keeps letters, digits, hyphens, underscores
    and any non-ASCII (so emoji-bearing headings still resolve), and drops the
    rest. A slug this function gets wrong produces a false failure, so anything
    it cannot model is handled by _explicit_anchors below instead.
    """
    text = re.sub(r"`([^`]*)`", r"\1", title)                 # code spans
    text = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", text)    # links / images
    text = re.sub(r"[*_~]+", "", text)                        # emphasis
    text = text.strip().lower()
    out = []
    for char in text:
        if char.isalnum() or char in "-_" or ord(char) > 127:
            out.append(char)
        elif char.isspace():
            out.append("-")
    return "".join(out)


def _anchors(text: str) -> set[str]:
    """Every fragment a link in this repo may target within one document."""
    body = _without_fenced_code(text)
    found: set[str] = set()
    seen: dict[str, int] = {}
    for line in body.splitlines():
        match = re.match(r"\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$", line)
        if not match:
            continue
        slug = _heading_slug(match.group(2))
        if not slug:
            continue
        count = seen.get(slug, 0)
        seen[slug] = count + 1
        found.add(slug if count == 0 else f"{slug}-{count}")
    # Explicit HTML anchors, which headings cannot express.
    for match in re.finditer(r"<a\s[^>]*\b(?:id|name)=[\"']([^\"']+)[\"']", body):
        found.add(match.group(1))
    return found


def _check_anchor(path: Path, target_path: Path, fragment: str,
                  cache: dict[Path, set[str] | None]) -> str | None:
    """None if the fragment resolves, else the error text."""
    if target_path not in cache:
        try:
            cache[target_path] = _anchors(
                target_path.read_text(encoding="utf-8")
            )
        except OSError:
            cache[target_path] = None
    anchors = cache[target_path]
    if anchors is None:          # unreadable: the path check already reported it
        return None
    decoded = unquote(fragment)
    if decoded in anchors or _heading_slug(decoded) in anchors:
        return None
    where = "this file" if target_path == path else target_path.name
    return f"link fragment #{fragment} has no matching heading in {where}"


def _check_local_links(path: Path, text: str,
                       anchor_cache: dict[Path, set[str] | None] | None = None
                       ) -> list[str]:
    errors: list[str] = []
    if anchor_cache is None:
        anchor_cache = {}
    for raw_target in _markdown_link_targets(text):
        stripped = raw_target.strip()
        if stripped.startswith("<") and ">" in stripped:
            target = stripped[1 : stripped.index(">")]
        else:
            target = stripped.split(maxsplit=1)[0]
        if not target:
            continue
        if target.startswith("#"):
            anchor_error = _check_anchor(path, path, target[1:], anchor_cache)
            if anchor_error:
                errors.append(f"{path.relative_to(ROOT)}: {anchor_error}")
            continue
        target = _unescape_markdown_destination(target)
        parsed = urlsplit(target)
        scheme = parsed.scheme.lower()
        if scheme in {"http", "https", "mailto"}:
            continue
        if scheme:
            errors.append(
                f"{path.relative_to(ROOT)}: unsupported link scheme {scheme!r}: {target}"
            )
            continue
        if parsed.netloc:
            continue
        decoded_path = unquote(parsed.path)
        if any(unicodedata.category(char) == "Cc" for char in decoded_path):
            errors.append(
                f"{path.relative_to(ROOT)}: decoded link target contains a control character: {target}"
            )
            continue
        decoded = urlsplit(decoded_path)
        decoded_scheme = decoded.scheme.lower()
        if decoded.netloc or decoded_scheme in {"http", "https", "mailto"}:
            errors.append(
                f"{path.relative_to(ROOT)}: encoded external link target is not allowed: {target}"
            )
            continue
        if decoded_scheme:
            errors.append(
                f"{path.relative_to(ROOT)}: encoded link scheme {decoded_scheme!r}: {target}"
            )
            continue
        portable_path = decoded_path.replace("\\", "/")
        if portable_path.startswith("//"):
            errors.append(f"{path.relative_to(ROOT)}: unsafe network link target: {target}")
            continue
        components = portable_path.split("/")
        if any(
            component not in {"", ".", ".."}
            and component.endswith((".", " "))
            for component in components
        ):
            errors.append(
                f"{path.relative_to(ROOT)}: link target has a trailing dot or space: {target}"
            )
            continue
        resolved = (path.parent / portable_path).resolve()
        root_resolved = ROOT.resolve()
        try:
            resolved.relative_to(root_resolved)
        except ValueError:
            errors.append(f"{path.relative_to(ROOT)}: link escapes repository: {target}")
            continue
        local_error = _exact_local_path_error(path.parent, portable_path)
        if local_error:
            errors.append(f"{path.relative_to(ROOT)}: {local_error}: {target}")
            continue
        # The path resolves; if the link also names a section, that section has
        # to exist. A heading that gets renamed is the most common way a
        # cross-reference goes stale without any link going dead (#185).
        if parsed.fragment and resolved.suffix.lower() == ".md":
            anchor_error = _check_anchor(path, resolved, parsed.fragment, anchor_cache)
            if anchor_error:
                errors.append(f"{path.relative_to(ROOT)}: {anchor_error}: {target}")
    return errors


def _user_facing_claim_errors(strings_xml: str) -> list[str]:
    """The Settings strings are where a user actually reads our claims.

    docs/MOBILE_STRATEGY.md is already guarded against "export always uses the
    CPU engine", because that stopped being true when the Fast GPU export route
    landed. The Android string resources were never covered by that rule, and
    they carried the same claim in two toggles until #185. Since owner decision
    #180 the Fast GPU route also re-draws grain and viewing glare from a cheaper
    generator, so an unconditional promise about export in ANY string is wrong.
    """
    errors: list[str] = []
    banned = (
        "Export is always the exact CPU engine",
        "Export always uses the exact CPU engine",
        "Export always uses the CPU engine",
    )
    for phrase in banned:
        if phrase in strings_xml:
            errors.append(
                "app/src/main/res/values/strings_screens.xml: stale export claim "
                f"{phrase!r} - a preview toggle may only promise what it controls "
                "(see #180, #185)"
            )
    # The GPU export toggle must disclose the different noise realisation.
    if "screen_settings_gpu_export_note" in strings_xml:
        note_start = strings_xml.index("screen_settings_gpu_export_note")
        note = strings_xml[note_start : note_start + 1200].lower()
        if "grain" not in note or "will not match" not in note:
            errors.append(
                "app/src/main/res/values/strings_screens.xml: "
                "screen_settings_gpu_export_note must disclose that grain and glare "
                "will not match the default engine (#180 migration disclosure)"
            )
    return errors


def _preset_set_errors(asset_ids: list[str], documented_ids: list[str]) -> list[str]:
    errors: list[str] = []
    if len(asset_ids) != len(set(asset_ids)):
        errors.append("presets.json: duplicate preset ID")
    if len(documented_ids) != len(set(documented_ids)):
        errors.append("docs/PRESETS.md: duplicate documented preset ID")
    missing = sorted(set(asset_ids) - set(documented_ids))
    extra = sorted(set(documented_ids) - set(asset_ids))
    if missing:
        errors.append(f"docs/PRESETS.md: missing preset IDs: {', '.join(missing)}")
    if extra:
        errors.append(f"docs/PRESETS.md: unknown preset IDs: {', '.join(extra)}")
    return errors


# docs/PRESETS.md quotes concrete parameter values as `key value` in backticks. Nothing
# compared them to the asset, so they drifted silently: a sweep found 43 stale numbers and
# 10 wrong preset names, including a `grain.blur 0.5` documented against a shipped 1.0, and
# four citations of `halationStrength` -- a knob presets.json's own _comment says the engine
# bakes from the profile and IGNORES from a preset. ID matching alone could not see any of it.
_PRESET_DOC_KEYS = {
    "halation": ("filmRender", "halation", "halationAmount"),
    "halationAmount": ("filmRender", "halation", "halationAmount"),
    "scatterAmount": ("filmRender", "halation", "scatterAmount"),
    "boostEv": ("filmRender", "halation", "boostEv"),
    "blur": ("filmRender", "grain", "blur"),
    "grain.blur": ("filmRender", "grain", "blur"),
    "agxParticleScale": ("filmRender", "grain", "agxParticleScale"),
    "densityCurveGamma": ("filmRender", "densityCurveGamma"),
    "dirCouplers.amount": ("filmRender", "dirCouplers", "amount"),
    "exposureCompensationEv": ("camera", "exposureCompensationEv"),
    "scanner.unsharpMask": ("scanner", "unsharpMask"),
}

# Baked from each profile's info.use / info.antihalation tags; a preset value is discarded.
_PRESET_IGNORED_KEYS = ("halationStrength", "halationFirstSigmaUm")


def _preset_doc_value_errors(preset_asset: dict, doc_text: str) -> list[str]:
    by_id = {str(p["id"]): p for p in preset_asset["presets"]}
    errors: list[str] = []
    sections = re.split(r"(?m)^### ", doc_text)[1:]
    for section in sections:
        head = re.match(r"(.+?)\s*\(`([^`]+)`\)", section)
        if not head:
            continue
        title, preset_id = head.group(1).strip(), head.group(2)
        preset = by_id.get(preset_id)
        if preset is None:
            continue  # _preset_set_errors already reports unknown IDs
        if title != str(preset.get("name", "")):
            errors.append(
                f"docs/PRESETS.md: {preset_id}: heading {title!r} != asset name "
                f"{preset.get('name')!r}"
            )
        for token in sorted(set(re.findall(r"`([^`]+)`", section))):
            match = re.match(
                r"^([A-Za-z][A-Za-z0-9_.]*)\s+(\[[^\]]*\]|-?[0-9.]+)$", token.strip()
            )
            if not match:
                continue
            key, raw = match.group(1), match.group(2)
            if key in _PRESET_IGNORED_KEYS:
                errors.append(
                    f"docs/PRESETS.md: {preset_id}: documents `{token}`, but the engine bakes "
                    f"{key} from the profile and ignores any preset value"
                )
                continue
            path = _PRESET_DOC_KEYS.get(key)
            if path is None:
                continue
            node = preset.get("params", {})
            for part in path:
                if not isinstance(node, dict) or part not in node:
                    node = None
                    break
                node = node[part]
            if node is None:
                errors.append(
                    f"docs/PRESETS.md: {preset_id}: documents `{token}`, but the asset does "
                    f"not author {'.'.join(path)}"
                )
                continue
            try:
                documented = json.loads(raw)
            except ValueError:
                continue
            if isinstance(documented, list) != isinstance(node, list):
                errors.append(f"docs/PRESETS.md: {preset_id}: `{token}` shape != asset {node!r}")
            elif isinstance(documented, list):
                if [round(float(x), 6) for x in documented] != [round(float(x), 6) for x in node]:
                    errors.append(f"docs/PRESETS.md: {preset_id}: `{token}` != asset {node!r}")
            elif abs(float(documented) - float(node)) > 1e-9:
                errors.append(f"docs/PRESETS.md: {preset_id}: `{token}` != asset {node!r}")
    return errors


def _sdk_summary(min_sdk: str, target_sdk: str, compile_sdk: str) -> str:
    return f"min {min_sdk}, target {target_sdk}, compile {compile_sdk}"


def _included_module_gradles(settings: str) -> list[Path]:
    modules: list[str] = []
    for arguments in re.findall(r"^\s*include\(([^)]*)\)", settings, flags=re.MULTILINE):
        modules.extend(re.findall(r'["\'](:[^"\']+)["\']', arguments))
    return [
        ROOT.joinpath(*module.removeprefix(":").split(":")) / "build.gradle.kts"
        for module in modules
    ]


def _workflow_pin_errors(
    workflows: dict[Path, str], expected: dict[str, str]
) -> list[str]:
    errors: list[str] = []
    for path, text in workflows.items():
        for label, version in expected.items():
            found = set(
                re.findall(
                    rf"{re.escape(label)}[;/]([0-9.]+)",
                    text,
                    flags=re.IGNORECASE,
                )
            )
            if found != {version}:
                rendered = ", ".join(sorted(found)) or "none"
                errors.append(
                    f"{path.relative_to(ROOT)}: expected {label} {version}, found {rendered}"
                )
    return errors


def _parity_flag_errors(ci: str, release: str, shipping_flags: str) -> list[str]:
    errors: list[str] = []
    ci_flags = set(re.findall(r'^\s+opt:\s*"([^"]+)"', ci, flags=re.MULTILINE))
    required_ci_flags = {"-O2", shipping_flags}
    if not required_ci_flags.issubset(ci_flags):
        errors.append(
            "ci.yml: parity matrix must contain -O2 and the CMake shipping flags; "
            f"found {sorted(ci_flags)}"
        )
    release_flags = set(
        re.findall(
            r"^\s+SPK_PARITY_EXTRA_FLAGS:\s*([^\r\n]+?)\s*$",
            release,
            flags=re.MULTILINE,
        )
    )
    if release_flags != {shipping_flags}:
        errors.append(
            "release.yml: SPK_PARITY_EXTRA_FLAGS must equal the CMake shipping flags; "
            f"found {sorted(release_flags)}"
        )
    return errors


def main() -> int:
    errors: list[str] = []
    try:
        app_gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        workflow_paths = (
            ROOT / ".github" / "workflows" / "ci.yml",
            ROOT / ".github" / "workflows" / "release.yml",
            ROOT / ".github" / "workflows" / "r8-smoke.yml",
        )
        workflows = {path: path.read_text(encoding="utf-8") for path in workflow_paths}
        ci = workflows[workflow_paths[0]]
        engine_cmake = (ROOT / "engine" / "spektra-core" / "src" / "main" / "cpp" / "CMakeLists.txt").read_text(encoding="utf-8")
        version_catalog = (ROOT / "gradle" / "libs.versions.toml").read_text(encoding="utf-8")
        wrapper = (ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text(encoding="utf-8")
        settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
        libraw_vendor = (ROOT / "lib" / "libraw" / "cmake" / "LibRawVendor.cmake").read_text(encoding="utf-8")
        version_name = _extract(r'^\s*versionName\s*=\s*"([^"]+)"', app_gradle, ROOT / "app/build.gradle.kts")
        version_code = _extract(r"^\s*versionCode\s*=\s*(\d+)", app_gradle, ROOT / "app/build.gradle.kts")
        min_sdk = _extract(r"^\s*minSdk\s*=\s*(\d+)", app_gradle, ROOT / "app/build.gradle.kts")
        target_sdk = _extract(r"^\s*targetSdk\s*=\s*(\d+)", app_gradle, ROOT / "app/build.gradle.kts")
        compile_sdk = _extract(r"^\s*compileSdk\s*=\s*(\d+)", app_gradle, ROOT / "app/build.gradle.kts")
        build_tools = _extract(r'^\s*buildToolsVersion\s*=\s*"([^"]+)"', app_gradle, ROOT / "app/build.gradle.kts")
        native_gradles = []
        for gradle_path in _included_module_gradles(settings):
            gradle_text = gradle_path.read_text(encoding="utf-8")
            if "externalNativeBuild" in gradle_text:
                native_gradles.append((gradle_path, gradle_text))
        if not native_gradles:
            raise ValueError("no Gradle native modules found")
        ndk_versions = {
            _extract(r'^\s*ndkVersion\s*=\s*"([^"]+)"', text, path)
            for path, text in native_gradles
        }
        cmake_versions = {
            _extract(r'^\s*version\s*=\s*"([^"]+)"', text, path)
            for path, text in native_gradles
        }
        if len(ndk_versions) != 1:
            raise ValueError(f"native modules disagree on NDK pins: {sorted(ndk_versions)}")
        if len(cmake_versions) != 1:
            raise ValueError(f"native modules disagree on CMake pins: {sorted(cmake_versions)}")
        ndk_version = next(iter(ndk_versions))
        cmake_version = next(iter(cmake_versions))
        shipping_flags = _extract(r'^set\(CMAKE_CXX_FLAGS_RELEASE\s+"([^"]+)"\)', engine_cmake, ROOT / "engine/spektra-core/src/main/cpp/CMakeLists.txt")
        agp_version = _extract(r'^agp\s*=\s*"([^"]+)"', version_catalog, ROOT / "gradle/libs.versions.toml")
        kotlin_version = _extract(r'^kotlin\s*=\s*"([^"]+)"', version_catalog, ROOT / "gradle/libs.versions.toml")
        gradle_version = _extract(r"gradle-([0-9.]+)-bin\.zip", wrapper, ROOT / "gradle/wrapper/gradle-wrapper.properties")
        libraw_version = _extract(r'^set\(SFRAW_PINNED_LIBRAW_VERSION\s+"([^"]+)"\)', libraw_vendor, ROOT / "lib/libraw/cmake/LibRawVendor.cmake")
        parity_count = len(re.findall(r"^\s+build_run\s+test_", ci, flags=re.MULTILINE))
        preset_asset = json.loads(
            (ROOT / "engine" / "spektra-core" / "src" / "main" / "assets" / "spektra" / "presets.json").read_text(
                encoding="utf-8"
            )
        )
        asset_preset_ids = [str(item["id"]) for item in preset_asset["presets"]]
        preset_doc_text = (ROOT / "docs" / "PRESETS.md").read_text(encoding="utf-8")
        documented_preset_ids = re.findall(
            r"^### .+?\(`([^`]+)`\)\s*$", preset_doc_text, flags=re.MULTILINE
        )
    except (OSError, ValueError) as exc:
        print(f"docs-consistency: ERROR: {exc}", file=sys.stderr)
        return 1

    required_fragments = {
        ROOT / "README.md": (
            "docs/EXECUTION_INDEX.md",
            "oracle tolerance",
            "Earlier owner status note",
        ),
        ROOT / "CLAUDE.md": ("docs/EXECUTION_INDEX.md", f"{parity_count} tests"),
        ROOT / "docs" / "EXECUTION_INDEX.md": (
            f"`{version_name}` / versionCode `{version_code}`",
            _sdk_summary(min_sdk, target_sdk, compile_sdk),
            f"`{build_tools}`",
            f"AGP `{agp_version}`, Kotlin `{kotlin_version}`, Gradle `{gradle_version}`",
            f"NDK `{ndk_version}`, CMake `{cmake_version}`",
            f"LibRaw `{libraw_version}`",
            shipping_flags,
            f"{parity_count} cases",
        ),
        ROOT / "docs" / "PRODUCTION_READINESS_PLAN.md": ("EXECUTION_INDEX.md",),
        ROOT / "docs" / "BIT_IDENTICAL_EXPORT_ROADMAP.md": ("EXECUTION_INDEX.md",),
        ROOT / "docs" / "RELEASE_CHECKLIST.md": ("EXECUTION_INDEX.md",),
    }
    forbidden_fragments = {
        ROOT / "README.md": ("checked bit-for-bit",),
        ROOT / "docs" / "MOBILE_STRATEGY.md": (
            "GPU is an **optional accelerator for the preview path only**",
            "export always uses the CPU engine",
        ),
    }

    strings_screens = (
        ROOT / "app" / "src" / "main" / "res" / "values" / "strings_screens.xml"
    )
    try:
        errors.extend(_user_facing_claim_errors(strings_screens.read_text(encoding="utf-8")))
    except OSError as exc:
        errors.append(f"{strings_screens.relative_to(ROOT)}: {exc}")

    errors.extend(_preset_set_errors(asset_preset_ids, documented_preset_ids))
    errors.extend(_preset_doc_value_errors(preset_asset, preset_doc_text))
    errors.extend(
        _workflow_pin_errors(
            workflows,
            {
                "ndk": ndk_version,
                "cmake": cmake_version,
                "build-tools": build_tools,
            },
        )
    )
    errors.extend(
        _parity_flag_errors(
            ci,
            workflows[ROOT / ".github" / "workflows" / "release.yml"],
            shipping_flags,
        )
    )

    anchor_cache: dict[Path, set[str] | None] = {}
    for path in CURRENT_DOCS:
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as exc:
            errors.append(f"{path.relative_to(ROOT)}: {exc}")
            continue
        errors.extend(_check_local_links(path, text, anchor_cache))
        for fragment in required_fragments.get(path, ()):
            if fragment not in text:
                errors.append(f"{path.relative_to(ROOT)}: missing required text {fragment!r}")
        for fragment in forbidden_fragments.get(path, ()):
            if fragment in text:
                errors.append(f"{path.relative_to(ROOT)}: stale policy text {fragment!r}")

    if errors:
        print(f"docs-consistency: FAILED ({len(errors)} error(s))")
        for error in errors:
            print(f"  {error}")
        return 1

    print(
        "docs-consistency: OK "
        f"(v{version_name}/{version_code}, SDK {min_sdk}/{target_sdk}/{compile_sdk}, "
        f"AGP/Kotlin/Gradle {agp_version}/{kotlin_version}/{gradle_version}, "
        f"NDK/CMake {ndk_version}/{cmake_version}, build-tools {build_tools}, "
        f"LibRaw {libraw_version}, flags {shipping_flags!r}, parity {parity_count}, "
        f"presets {len(asset_preset_ids)})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
