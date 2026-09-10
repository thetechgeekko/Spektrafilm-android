from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).resolve().parents[1] / "check_docs_consistency.py"
SPEC = importlib.util.spec_from_file_location("check_docs_consistency", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
checker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checker)


class DocsConsistencyTest(unittest.TestCase):
    def test_extract_returns_first_capture_and_rejects_missing(self) -> None:
        source = checker.ROOT / "sample.txt"
        self.assertEqual(checker._extract(r"value=(\d+)", "value=39", source), "39")
        with self.assertRaises(ValueError):
            checker._extract(r"missing=(\d+)", "value=39", source)

    def test_local_link_checker_handles_present_missing_external_and_escape(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docs = root / "docs"
            docs.mkdir()
            page = docs / "page.md"
            (docs / "exists.md").write_text("ok", encoding="utf-8")
            text = "[ok](exists.md) [missing](no.md) [web](https://example.com) [escape](../../x)"
            with mock.patch.object(checker, "ROOT", root):
                errors = checker._check_local_links(page, text)
        self.assertEqual(len(errors), 2)
        self.assertTrue(any("missing local link" in error for error in errors))
        self.assertTrue(any("escapes repository" in error for error in errors))

    def test_local_link_checker_accepts_balanced_parentheses_and_angle_spaces(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docs = root / "docs"
            docs.mkdir()
            page = docs / "page.md"
            (docs / "file_(v2).md").write_text("ok", encoding="utf-8")
            spaced = docs / "folder with spaces"
            spaced.mkdir()
            (spaced / "file.md").write_text("ok", encoding="utf-8")
            text = (
                "[balanced](file_(v2).md) "
                "[spaced](<folder with spaces/file.md> \"optional title\")"
            )
            with mock.patch.object(checker, "ROOT", root):
                errors = checker._check_local_links(page, text)
        self.assertEqual(errors, [])

    def test_commonmark_destination_escapes_are_unescaped_before_classification(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docs = root / "docs"
            nested = docs / "nested"
            nested.mkdir(parents=True)
            page = docs / "page.md"
            (nested / "file_(v2).md").write_text("ok", encoding="utf-8")
            text = (
                r"[parentheses](nested/file_\(v2\).md) "
                r"[backslash](nested\\file_\(v2\).md) "
                r"[external](HTTPS\://example.invalid/path)"
            )
            with mock.patch.object(checker, "ROOT", root):
                errors = checker._check_local_links(page, text)
        self.assertEqual(errors, [])

    def test_local_link_checker_enforces_exact_nested_component_spelling(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docs = root / "docs"
            nested = docs / "Parent" / "Child"
            nested.mkdir(parents=True)
            page = docs / "page.md"
            (nested / "Target.md").write_text("ok", encoding="utf-8")
            text = (
                "[exact](Parent/Child/Target.md) "
                "[parent](parent/Child/Target.md) "
                "[child](Parent/child/Target.md) "
                "[file](Parent/Child/target.md)"
            )
            with mock.patch.object(checker, "ROOT", root):
                errors = checker._check_local_links(page, text)
        self.assertEqual(len(errors), 3)
        self.assertTrue(all("on-disk spelling mismatch" in error for error in errors))

    def test_local_link_checker_rejects_trailing_dot_or_space_in_any_component(self) -> None:
        page = checker.ROOT / "docs" / "page.md"
        text = (
            "[dot](existing.md.) "
            "[space](<existing.md >) "
            "[nested-dot](folder./file.md) "
            "[nested-space](folder%20/file.md)"
        )
        with mock.patch.object(Path, "resolve", side_effect=AssertionError("must not resolve")):
            errors = checker._check_local_links(page, text)
        self.assertEqual(len(errors), 4)
        self.assertTrue(all("trailing dot or space" in error for error in errors))

    def test_local_link_checker_rejects_percent_decoded_control_characters(self) -> None:
        page = checker.ROOT / "docs" / "page.md"
        text = "[nul](exists%00.md) [tab](exists%09.md) [del](exists%7F.md)"
        with mock.patch.object(Path, "resolve", side_effect=AssertionError("must not resolve")):
            errors = checker._check_local_links(page, text)
        self.assertEqual(len(errors), 3)
        self.assertTrue(all("control character" in error for error in errors))

    def test_reference_links_and_images_are_checked_but_fenced_examples_are_not(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docs = root / "docs"
            docs.mkdir()
            page = docs / "page.md"
            (docs / "exists.png").write_bytes(b"image")
            text = (
                "![image](exists.png) [reference][ok]\n"
                "[ok]: exists.png\n"
                "```markdown\n[example](missing-example.md)\n```\n"
            )
            with mock.patch.object(checker, "ROOT", root):
                errors = checker._check_local_links(page, text)
        self.assertEqual(errors, [])

    def test_external_and_scheme_relative_links_never_resolve_as_paths(self) -> None:
        page = checker.ROOT / "docs" / "page.md"
        text = (
            "[upper](HTTPS://example.invalid/path) "
            "[network](//example.invalid/share) [mail](MAILTO:test@example.invalid)"
        )
        with mock.patch.object(Path, "resolve", side_effect=AssertionError("must not resolve")):
            self.assertEqual(checker._check_local_links(page, text), [])

    def test_file_scheme_is_rejected_without_path_resolution(self) -> None:
        page = checker.ROOT / "docs" / "page.md"
        with mock.patch.object(Path, "resolve", side_effect=AssertionError("must not resolve")):
            errors = checker._check_local_links(page, "[local](file:///etc/passwd)")
        self.assertEqual(len(errors), 1)
        self.assertIn("unsupported link scheme 'file'", errors[0])

    def test_unsupported_authority_schemes_are_rejected_after_unescaping(self) -> None:
        page = checker.ROOT / "docs" / "page.md"
        text = r"[file](FILE\://server/share) [ssh](SSH\://host/path)"
        with mock.patch.object(Path, "resolve", side_effect=AssertionError("must not resolve")):
            errors = checker._check_local_links(page, text)
        self.assertEqual(len(errors), 2)
        self.assertIn("unsupported link scheme 'file'", errors[0])
        self.assertIn("unsupported link scheme 'ssh'", errors[1])

    def test_percent_encoded_network_paths_never_reach_pathlib(self) -> None:
        page = checker.ROOT / "docs" / "page.md"
        text = "[forward](%2F%2Fevil.invalid/share) [back](%5C%5Cevil%5Cshare)"
        with mock.patch.object(Path, "resolve", side_effect=AssertionError("must not resolve")):
            errors = checker._check_local_links(page, text)
        self.assertEqual(len(errors), 2)
        self.assertTrue(all("link target" in error for error in errors))

    def test_heading_slug_matches_github_for_markup_and_punctuation(self) -> None:
        self.assertEqual(checker._heading_slug("Module layout"), "module-layout")
        self.assertEqual(checker._heading_slug("A note on accuracy"), "a-note-on-accuracy")
        self.assertEqual(
            checker._heading_slug("Engine architecture (C++, `engine/src/`)"),
            "engine-architecture-c-enginesrc",
        )
        self.assertEqual(checker._heading_slug("**Bold** and _em_"), "bold-and-em")
        self.assertEqual(checker._heading_slug("[linked](x.md) title"), "linked-title")

    def test_anchors_collects_headings_duplicates_and_explicit_ids(self) -> None:
        text = (
            "# Top\n"
            "## Repeat\n"
            "## Repeat\n"
            "<a id=\"explicit\"></a>\n"
            "```\n"
            "## Fenced heading\n"
            "```\n"
        )
        anchors = checker._anchors(text)
        self.assertIn("top", anchors)
        self.assertIn("repeat", anchors)
        self.assertIn("repeat-1", anchors)      # GitHub's duplicate suffix
        self.assertIn("explicit", anchors)
        self.assertNotIn("fenced-heading", anchors)  # fenced code is not a heading

    def test_same_document_fragment_must_resolve(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            page = root / "page.md"
            text = "# Real Heading\n[ok](#real-heading) [bad](#no-such-heading)\n"
            page.write_text(text, encoding="utf-8")
            with mock.patch.object(checker, "ROOT", root):
                errors = checker._check_local_links(page, text)
        self.assertEqual(len(errors), 1)
        self.assertIn("#no-such-heading", errors[0])

    def test_cross_document_fragment_must_resolve(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "other.md").write_text("# Known Section\n", encoding="utf-8")
            page = root / "page.md"
            text = "[ok](other.md#known-section) [bad](other.md#gone)\n"
            page.write_text(text, encoding="utf-8")
            with mock.patch.object(checker, "ROOT", root):
                errors = checker._check_local_links(page, text)
        self.assertEqual(len(errors), 1)
        self.assertIn("#gone", errors[0])
        self.assertIn("other.md", errors[0])

    def test_fragment_on_a_missing_file_reports_the_path_not_the_anchor(self) -> None:
        """One error, not two -- a dead path must not also claim a dead anchor."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            page = root / "page.md"
            text = "[bad](absent.md#whatever)\n"
            page.write_text(text, encoding="utf-8")
            with mock.patch.object(checker, "ROOT", root):
                errors = checker._check_local_links(page, text)
        self.assertEqual(len(errors), 1)
        self.assertIn("missing local link", errors[0])

    def test_percent_encoded_fragment_resolves(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            page = root / "page.md"
            text = "# Real Heading\n[ok](#real%2Dheading)\n"
            page.write_text(text, encoding="utf-8")
            with mock.patch.object(checker, "ROOT", root):
                errors = checker._check_local_links(page, text)
        self.assertEqual(errors, [])

    def test_user_facing_claim_gate_rejects_unconditional_export_promises(self) -> None:
        good = (
            '<string name="screen_settings_gpu_preview_note">This toggle never affects '
            'export.</string>'
            '<string name="screen_settings_gpu_export_note">Grain and viewing glare are '
            'different: the noise pattern will not match the default engine.</string>'
        )
        self.assertEqual(checker._user_facing_claim_errors(good), [])

        stale = good.replace(
            "This toggle never affects export.",
            "Export is always the exact CPU engine.",
        )
        errors = checker._user_facing_claim_errors(stale)
        self.assertEqual(len(errors), 1)
        self.assertIn("stale export claim", errors[0])

    def test_user_facing_claim_gate_requires_the_180_disclosure(self) -> None:
        missing = (
            '<string name="screen_settings_gpu_export_note">Run more of the export on '
            'the GPU. Everything is checked against the CPU engine.</string>'
        )
        errors = checker._user_facing_claim_errors(missing)
        self.assertEqual(len(errors), 1)
        self.assertIn("migration disclosure", errors[0])

    def test_preset_set_comparison_detects_missing_extra_and_duplicates(self) -> None:
        errors = checker._preset_set_errors(["a", "a", "b"], ["a", "c", "c"])
        joined = "\n".join(errors)
        self.assertIn("duplicate preset ID", joined)
        self.assertIn("duplicate documented preset ID", joined)
        self.assertIn("missing preset IDs: b", joined)
        self.assertIn("unknown preset IDs: c", joined)

    def test_preset_set_comparison_accepts_equal_sets_in_different_order(self) -> None:
        self.assertEqual(checker._preset_set_errors(["a", "b"], ["b", "a"]), [])

    def test_sdk_summary_keeps_target_and_compile_independent(self) -> None:
        self.assertEqual(
            checker._sdk_summary("24", "34", "35"),
            "min 24, target 34, compile 35",
        )

    def test_included_gradles_ignore_dormant_decoy_modules(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            included = root / "app" / "build.gradle.kts"
            decoy = root / "dormant" / "build.gradle.kts"
            included.parent.mkdir()
            decoy.parent.mkdir()
            included.write_text("android {}", encoding="utf-8")
            decoy.write_text("externalNativeBuild { stale() }", encoding="utf-8")
            with mock.patch.object(checker, "ROOT", root):
                paths = checker._included_module_gradles('include(":app")')
        self.assertEqual(paths, [included])

    def test_workflow_pins_detect_drift(self) -> None:
        workflow = checker.ROOT / ".github" / "workflows" / "sample.yml"
        errors = checker._workflow_pin_errors(
            {
                workflow: (
                    'sdkmanager "ndk;27.0" "cmake;3.22.1" '
                    '"build-tools;36.0.0"'
                )
            },
            {"ndk": "27.0", "cmake": "3.22.1", "build-tools": "35.0.0"},
        )
        self.assertEqual(len(errors), 1)
        self.assertIn("expected build-tools 35.0.0, found 36.0.0", errors[0])

    def test_parity_flags_detect_ci_and_release_drift(self) -> None:
        shipping = "-O3 -ffast-math -fno-finite-math-only"
        good_ci = f'          opt: "-O2"\n          opt: "{shipping}"\n'
        good_release = f"          SPK_PARITY_EXTRA_FLAGS: {shipping}\n"
        self.assertEqual(checker._parity_flag_errors(good_ci, good_release, shipping), [])
        errors = checker._parity_flag_errors(
            '          opt: "-O2"\n',
            "          SPK_PARITY_EXTRA_FLAGS: -O3\n",
            shipping,
        )
        self.assertEqual(len(errors), 2)


if __name__ == "__main__":
    unittest.main()
