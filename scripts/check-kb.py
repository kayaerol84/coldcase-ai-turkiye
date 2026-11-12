#!/usr/bin/env python3
"""Fails if the knowledge base references repository paths that don't exist.

Checks backticked tokens in docs/kb/**/*.md, CLAUDE.md and .claude/skills/**/SKILL.md that look like
repository paths (start with a top-level directory of this repo). Keeps the KB from silently rotting
when files move. Run from anywhere: python3 scripts/check-kb.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TOP = ("services/", "apps/", "docs/", "infra/", "evals/", "scripts/", ".github/", ".claude/", "packages/", "workers/")
TOKEN = re.compile(r"`([^`\s]+)`")

files = sorted(
    list((ROOT / "docs/kb").rglob("*.md"))
    + [ROOT / "CLAUDE.md", ROOT / "AGENTS.md"]
    + list((ROOT / ".claude/skills").rglob("SKILL.md"))
)
errors = []
checked = 0
for f in files:
    if not f.exists():
        errors.append(f"{f.relative_to(ROOT)}: file is missing")
        continue
    in_fence = False
    for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
        if line.strip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        for tok in TOKEN.findall(line):
            path = tok.rstrip(".,:;)")
            if not path.startswith(TOP) or any(c in path for c in "*<>{}$"):
                continue
            checked += 1
            if not (ROOT / path).exists():
                errors.append(f"{f.relative_to(ROOT)}:{n}: `{path}` does not exist")

if errors:
    print("Knowledge base references broken paths:\n" + "\n".join("  " + e for e in errors))
    sys.exit(1)
print(f"KB OK: {checked} path references in {len(files)} files")
