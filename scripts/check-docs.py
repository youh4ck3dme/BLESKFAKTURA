#!/usr/bin/env python3
"""Check local Markdown/HTML documentation links without network or dependencies."""

from collections import Counter
from html import unescape
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]


def without_fenced_code(text):
    lines = []
    fence = None
    for line in text.splitlines():
        marker = re.match(r"^\s*(`{3,}|~{3,})", line)
        if marker:
            if fence is None:
                fence = marker.group(1)[0]
            elif marker.group(1)[0] == fence:
                fence = None
            lines.append("")
        else:
            lines.append(line if fence is None else "")
    return "\n".join(lines)


def anchors(path):
    text = path.read_text(encoding="utf-8")
    found = set(re.findall(r'\bid=["\']([^"\']+)["\']', text))
    if path.suffix.lower() == ".md":
        counts = Counter()
        for heading in re.findall(r"^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$", without_fenced_code(text), re.M):
            heading = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", heading)
            slug = re.sub(r"[^\w\- ]", "", unescape(heading).lower()).replace(" ", "-")
            duplicate = counts[slug]
            counts[slug] += 1
            found.add(f"{slug}-{duplicate}" if duplicate else slug)
    return found


def local_links(path):
    text = path.read_text(encoding="utf-8")
    if path.suffix.lower() == ".md":
        text = without_fenced_code(text)
        yield from re.findall(r"!?\[[^\]\n]*\]\(\s*<?([^\s)>]+)>?(?:\s+[^)]*)?\)", text)
        yield from re.findall(r"^\s*\[[^\]]+\]:\s*<?([^\s>]+)", text, re.M)
    yield from re.findall(r'\b(?:href|src)=["\']([^"\']+)["\']', text)


def main():
    # Include newly created documents during local review, as well as tracked files in CI.
    names = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT
    ).decode().split("\0")
    documents = sorted({ROOT / n for n in names if n.endswith(".md") or n.startswith("docs/") and n.endswith(".html")})
    checked = 0
    errors = []
    for document in documents:
        for raw_link in local_links(document):
            url = urlsplit(unescape(raw_link))
            if url.scheme or url.netloc:
                continue
            target = (ROOT / unquote(url.path).lstrip("/") if url.path.startswith("/")
                      else document.parent / unquote(url.path)) if url.path else document
            target = target.resolve()
            checked += 1
            if not target.is_relative_to(ROOT) or not target.exists():
                errors.append(f"{document.relative_to(ROOT)}: missing local target {raw_link}")
            elif url.fragment and target.suffix.lower() in {".md", ".html"}:
                if unquote(url.fragment) not in anchors(target):
                    errors.append(f"{document.relative_to(ROOT)}: missing fragment {raw_link}")
    for error in errors:
        print(error, file=sys.stderr)
    print(f"Documentation: {len(documents)} files, {checked} local links, {len(errors)} errors")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
