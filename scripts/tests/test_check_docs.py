"""Black-box regressions for repository link checking; no network or packages."""

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


CHECKER = Path(__file__).resolve().parents[1] / "check-docs.py"


class DocumentationLinksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="blesk-docs-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "repo"
        (self.root / "scripts").mkdir(parents=True)
        shutil.copy2(CHECKER, self.root / "scripts/check-docs.py")
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)

    def write(self, name, text):
        target = self.root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def check(self, expected):
        result = subprocess.run([sys.executable, "scripts/check-docs.py"], cwd=self.root,
                                capture_output=True, text=True, check=False)
        self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
        return result

    def test_valid_relative_links_unicode_duplicate_anchors_html_and_encoded_spaces(self):
        self.write("README.md", "[doc](docs/My%20Guide.md#prehľad-1)\n[html](docs/page.html#content)\n")
        self.write("docs/My Guide.md", "# Prehľad\n# Prehľad\n")
        self.write("docs/page.html", '<h1 id="content">Content</h1><a href="../README.md">Back</a>')
        subprocess.run(["git", "add", "README.md"], cwd=self.root, check=True)
        self.check(0)  # Both tracked README and untracked new documents are checked.

    def test_missing_local_file_fails(self):
        self.write("README.md", "[bad](docs/missing.md)\n")
        self.assertIn("missing local target", self.check(1).stderr)

    def test_missing_fragment_fails_even_when_the_file_exists(self):
        self.write("README.md", "[bad](docs/guide.md#missing)\n")
        self.write("docs/guide.md", "# Present\n")
        self.assertIn("missing fragment", self.check(1).stderr)

    def test_existing_file_outside_repository_is_rejected(self):
        (Path(self.temp.name) / "outside.md").write_text("external")
        self.write("README.md", "[escape](../outside.md)\n")
        self.assertIn("missing local target", self.check(1).stderr)

    def test_external_urls_examples_and_ignored_generated_docs_are_not_fetched(self):
        self.write("README.md", "[web](https://example.invalid/not-fetched)\n```md\n[example](missing.md)\n```\n")
        self.write(".gitignore", "node_modules/\n")
        self.write("node_modules/generated.md", "[ignored](missing.md)\n")
        self.check(0)


if __name__ == "__main__":
    unittest.main()
