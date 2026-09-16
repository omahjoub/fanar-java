#!/usr/bin/env python3
"""Documentation integrity checks.

Run it locally exactly as CI does:

    python3 .github/scripts/check-docs.py

What it checks, and why each one is here rather than left to review:

1. **Required files exist.** A handful of paths that other tooling and the README assume.
2. **Every relative link resolves** — not only `](….md)`. The wire-observations ledger links to
   the live test classes that pin each observation, and the ADRs link to source files; those are
   the links whose breakage matters most and the ones a Markdown-only check never saw.
3. **Every `#fragment` resolves** to a heading in the target document. Six of these rotted
   silently before anything looked for them.
4. **Every `<img src=…>` resolves.** The module diagram is embedded as raw HTML in two documents,
   so no Markdown link checker would ever have looked at it.
5. **The README's `<version>` snippets match the root POM.** They track `main`'s snapshot version
   by hand and have gone stale after past releases; `docs/RELEASING.md` carries a step for it, and
   a step in a runbook is not a check.

**Targets are resolved against tracked files only** (`git ls-files`). That is deliberate: a link to
a file that exists on your disk but was never `git add`-ed is broken for everyone else, and this is
the check that catches it. Running locally before staging a new file will report it as missing —
that is the intended answer, not a false positive.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import unicodedata

REPO = subprocess.run(
    ["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True
).stdout.strip()

REQUIRED_FILES = [
    "LICENSE",
    "README.md",
    "api-spec/openapi.json",
    "api-spec/openapi.yaml",
    ".github/pull_request_template.md",
    ".github/SECURITY.md",
    "docs/adr/INDEX.md",
]

MD_LINK = re.compile(r"\[(?P<label>[^\]]*)\]\((?P<target>[^)\s]+)(?:\s+\"[^\"]*\")?\)")
HTML_IMG = re.compile(r"<img[^>]*\ssrc=\"(?P<target>[^\"]+)\"")
HEADING = re.compile(r"^(?P<hashes>#{1,6})\s+(?P<text>.*?)\s*#*\s*$")
EXPLICIT_ANCHOR = re.compile(r"<a\s+(?:name|id)=\"([^\"]+)\"")
POM_VERSION = re.compile(r"<artifactId>fanar-java</artifactId>\s*\n\s*<version>([^<]+)</version>")
README_VERSION = re.compile(r"<version>([^<]+)</version>")


def tracked_files() -> set[str]:
    out = subprocess.run(
        ["git", "ls-files"], cwd=REPO, capture_output=True, text=True, check=True
    ).stdout.split("\n")
    return {p for p in out if p}


def slugify(text: str) -> str:
    """Approximate GitHub's heading slugger.

    Strip inline markup first, lowercase, drop punctuation, spaces to hyphens. GitHub keeps the
    doubled hyphen that an em-dash surrounded by spaces produces, so no run-collapsing here.
    """
    t = re.sub(r"<[^>]+>", "", text)
    t = re.sub(r"`([^`]*)`", r"\1", t)
    t = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", t)
    t = re.sub(r"\*\*|__|\*|_|~~", "", t)
    t = t.lower()
    kept = []
    for ch in t:
        if ch.isalnum() or ch in ("-", "_", " "):
            kept.append(ch)
        elif unicodedata.category(ch).startswith("M"):
            kept.append(ch)
    return "".join(kept).replace(" ", "-")


def anchors_of(path: str) -> set[str]:
    """Every fragment the document offers: heading slugs plus explicit HTML anchors."""
    text = open(os.path.join(REPO, path), encoding="utf-8").read()
    found: set[str] = set()
    seen: dict[str, int] = {}
    in_fence = False
    for line in text.split("\n"):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        m = HEADING.match(line)
        if not m:
            continue
        base = slugify(m.group("text"))
        if base in seen:
            seen[base] += 1
            found.add(f"{base}-{seen[base]}")
        else:
            seen[base] = 0
            found.add(base)
    found.update(EXPLICIT_ANCHOR.findall(text))
    return found


def link_targets(path: str):
    """Yield (line_no, target, label) for every relative link and image in a document.

    Fenced blocks and inline code spans are skipped: both render literally, so `[a](b.com)` inside
    backticks is prose about Markdown, not a link.
    """
    lines = open(os.path.join(REPO, path), encoding="utf-8").read().split("\n")
    in_fence = False
    for n, raw in enumerate(lines, start=1):
        if raw.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        line = re.sub(r"`[^`]*`", "", raw)
        for m in MD_LINK.finditer(line):
            yield n, m.group("target"), m.group("label")[:48]
        for m in HTML_IMG.finditer(raw):
            yield n, m.group("target"), "<img>"


def main() -> int:
    tracked = tracked_files()
    dirs = {os.path.dirname(p) for p in tracked}
    dirs |= {d for p in tracked for d in _ancestors(p)}
    docs = sorted(p for p in tracked if p.endswith(".md"))
    problems: list[str] = []

    for required in REQUIRED_FILES:
        if required not in tracked:
            problems.append(f"{required}: required file is missing or untracked")

    anchor_cache: dict[str, set[str]] = {}
    checked = 0
    for doc in docs:
        for line_no, target, label in link_targets(doc):
            if target.startswith(("http://", "https://", "mailto:", "#")):
                continue
            checked += 1
            path_part, _, fragment = target.partition("#")
            resolved = doc if not path_part else os.path.normpath(
                os.path.join(os.path.dirname(doc), path_part)
            )
            if resolved not in tracked and resolved not in dirs:
                problems.append(
                    f"{doc}:{line_no}: links to '{path_part}' — no such tracked file "
                    f"or directory  [{label}]"
                )
                continue
            if fragment and resolved.endswith(".md"):
                if resolved not in anchor_cache:
                    anchor_cache[resolved] = anchors_of(resolved)
                if fragment not in anchor_cache[resolved]:
                    problems.append(
                        f"{doc}:{line_no}: '{path_part}#{fragment}' — no such heading "
                        f"in {resolved}  [{label}]"
                    )

    problems.extend(check_readme_versions(tracked))

    print(f"checked {len(docs)} documents, {checked} relative links")
    if problems:
        print(f"\n{len(problems)} problem(s):\n")
        for p in problems:
            print(f"  {p}")
        return 1
    print("all documentation references resolve")
    return 0


def _ancestors(path: str):
    parts = path.split("/")[:-1]
    for i in range(1, len(parts) + 1):
        yield "/".join(parts[:i])


def check_readme_versions(tracked: set[str]) -> list[str]:
    """The README's quick-start snippets must name the version the reactor is actually on."""
    pom = open(os.path.join(REPO, "pom.xml"), encoding="utf-8").read()
    m = POM_VERSION.search(pom)
    if not m:
        return ["pom.xml: could not read the reactor version — check-docs needs updating"]
    expected = m.group(1).strip()

    readme = open(os.path.join(REPO, "README.md"), encoding="utf-8").read()
    problems = []
    for n, line in enumerate(readme.split("\n"), start=1):
        for found in README_VERSION.findall(line):
            if found.strip() != expected:
                problems.append(
                    f"README.md:{n}: quick-start shows <version>{found}</version> but the "
                    f"reactor is on {expected} (docs/RELEASING.md step 7)"
                )
    return problems


if __name__ == "__main__":
    sys.exit(main())
