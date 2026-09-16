#!/usr/bin/env python3
"""Build-consistency checks that nothing else enforces.

Run it locally exactly as CI does:

    python3 .github/scripts/check-build-invariants.py

Three invariants, each guarding a failure that a normal build reports as success. All three hold
today; they are regression guards, not fixes.

1. **The BOM manages exactly the published library modules.** This one has already failed:
   `fanar-spring-ai-starter` shipped from April 2026 and was missing from `bom/pom.xml` until
   September, so a consumer following the README — declaring the artifact with no version, letting
   the BOM supply it — got a resolution failure for five months. ADR-010 states the rule; until now
   nothing checked it. Equality, not containment, so a stale entry left behind by a removed module
   is caught too.

2. **Every class named as a string resolves to a real source file.** `META-INF/services`
   descriptors and the GraalVM reachability metadata name classes as text, so a rename that the
   compiler is perfectly happy with turns into "no FanarJsonCodec found on the classpath" in a
   consumer's application, or a native-image failure — at runtime, in someone else's process. The
   GraalVM self-test covers only what it exercises, and only when its path-filtered job runs at all.
   This matters most around a package rename (ADR-029): these are the two categories that fail
   silently.

3. **A module may skip the coverage gate only if it is never published.** The realistic way this
   breaks is not malice but a template: copying a sample module's POM to start a new one and not
   noticing the inherited `jacoco.skip`, which ships an ungated module by accident.

Everything is compared build-fact to build-fact — POMs and metadata parsed as data. Deliberately no
check reads prose: a rule that depends on the wording of a sentence breaks when someone rewords it,
and a check that cries wolf gets deleted.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
QNAME = "{http://maven.apache.org/POM/4.0.0}"

REPO = subprocess.run(
    ["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True
).stdout.strip()


def tracked() -> list[str]:
    out = subprocess.run(
        ["git", "ls-files"], cwd=REPO, capture_output=True, text=True, check=True
    ).stdout.split("\n")
    return [p for p in out if p]


def _text(node, path: str) -> str | None:
    e = node.find(path, NS)
    return e.text.strip() if e is not None and e.text else None


class Module:
    def __init__(self, pom_path: str):
        self.pom = pom_path
        self.dir = os.path.dirname(pom_path) or "."
        root = ET.parse(os.path.join(REPO, pom_path)).getroot()
        self.root = root
        self.artifact_id = _text(root, "m:artifactId")
        self.packaging = _text(root, "m:packaging") or "jar"
        props = root.find("m:properties", NS)
        self.props = {}
        if props is not None:
            for child in props:
                tag = child.tag.replace(QNAME, "")
                self.props[tag] = (child.text or "").strip()

    def flag(self, name: str) -> bool:
        return self.props.get(name) == "true"

    @property
    def published(self) -> bool:
        return not self.flag("maven.deploy.skip")


def check_bom(modules: list[Module]) -> list[str]:
    """The BOM manages exactly the published library jars — no more, no less."""
    expected = {m.artifact_id for m in modules if m.published and m.packaging != "pom"}

    bom = next((m for m in modules if m.artifact_id == "fanar-java-bom"), None)
    if bom is None:
        return ["bom/pom.xml: the BOM module is missing entirely"]

    managed: dict[str, str | None] = {}
    for dep in bom.root.iter(f"{QNAME}dependency"):
        managed[_text(dep, "m:artifactId")] = _text(dep, "m:version")

    problems = []
    for missing in sorted(expected - managed.keys()):
        problems.append(
            f"bom/pom.xml: '{missing}' is published but not managed by the BOM — a consumer "
            f"declaring it without a version gets a resolution failure "
            f"(ADR-010: the BOM manages every published library module)"
        )
    for stale in sorted(managed.keys() - expected):
        problems.append(
            f"bom/pom.xml: manages '{stale}', which is not a published library module — "
            f"remove the entry or publish the module"
        )
    for artifact, version in sorted(managed.items()):
        if artifact in expected and version != "${project.version}":
            problems.append(
                f"bom/pom.xml: '{artifact}' is pinned to '{version}' instead of "
                f"${{project.version}} — the BOM exists to keep every module on one version"
            )
    return problems


def _source_index(files: list[str]) -> set[str]:
    """Every class the repository defines, as a fully-qualified name."""
    classes = set()
    for path in files:
        marker = "/src/main/java/"
        if marker not in path or not path.endswith(".java"):
            continue
        rel = path.split(marker, 1)[1]
        if rel == "module-info.java":
            continue
        classes.add(rel[: -len(".java")].replace("/", "."))
    return classes


def _named_classes(path: str) -> list[str]:
    """Class names a metadata file asserts, restricted to ones we could have renamed."""
    full = os.path.join(REPO, path)
    names: list[str] = []
    if "/META-INF/services/" in path:
        for line in open(full, encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#"):
                names.append(line)
    elif path.endswith(".json") and "/META-INF/native-image/" in path:
        data = json.load(open(full, encoding="utf-8"))
        entries = data if isinstance(data, list) else data.get("reflection", [])
        for entry in entries:
            if isinstance(entry, dict):
                name = entry.get("name") or entry.get("type")
                if isinstance(name, str):
                    names.append(name)
    # Only our own classes: a JDK or third-party name is not ours to keep in step.
    return [n for n in names if n.startswith("qa.fanar")]


def check_string_named_classes(files: list[str]) -> tuple[list[str], int]:
    """Every qa.fanar class named as a string in shipped metadata must exist."""
    defined = _source_index(files)
    problems: list[str] = []
    counted = 0
    for path in sorted(files):
        if "/META-INF/services/" not in path and "/META-INF/native-image/" not in path:
            continue
        for name in _named_classes(path):
            counted += 1
            # Nested classes live in their outermost class's file.
            if name.split("$")[0] not in defined:
                problems.append(
                    f"{path}: names '{name}', which resolves to no source file — a class named "
                    f"as a string fails at runtime, not at compile time"
                )
    return problems, counted


def check_coverage_optout(modules: list[Module]) -> list[str]:
    """A module may skip the coverage gate only if it is never published."""
    return [
        f"{m.pom}: sets jacoco.skip but is published — a shipping module cannot opt out of the "
        f"coverage gate (CONTRIBUTING → Quality gates). Did this POM start as a copy of a sample?"
        for m in modules
        if m.flag("jacoco.skip") and m.published
    ]


def main() -> int:
    files = tracked()
    modules = [Module(p) for p in files if p.endswith("pom.xml")]

    problems = check_bom(modules)
    named, counted = check_string_named_classes(files)
    problems += named
    problems += check_coverage_optout(modules)

    published = sum(1 for m in modules if m.published and m.packaging != "pom")
    print(f"checked {len(modules)} modules ({published} published), {counted} string-named classes")

    if problems:
        print(f"\n{len(problems)} problem(s):\n")
        for p in problems:
            print(f"  {p}")
        return 1
    print("build invariants hold")
    return 0


if __name__ == "__main__":
    sys.exit(main())
