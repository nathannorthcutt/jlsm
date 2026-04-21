#!/usr/bin/env python3
"""
07-regenerate-manifest.py — Rebuild .spec/registry/manifest.json from current
.spec/domains/ directory contents.

Walks every spec file, extracts frontmatter (id, state, domains, requires,
invalidates, decision_refs, kb_refs), and writes a fresh manifest. Old FXX-named
files in .spec/domains/ are excluded — only the new domain.slug specs are listed.

Idempotent — overwrites manifest.json each run.
"""

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
SPEC_DOMAINS = REPO_ROOT / ".spec" / "domains"
MANIFEST_PATH = REPO_ROOT / ".spec" / "registry" / "manifest.json"


def parse_frontmatter(text):
    m = re.match(r"^(---\s*\n)(.*?)(\n---\s*\n)(.*)$", text, re.DOTALL)
    if not m:
        return None
    try:
        return json.loads(m.group(2))
    except json.JSONDecodeError:
        return None


def main():
    if not SPEC_DOMAINS.exists():
        print(f"ERROR: {SPEC_DOMAINS} not found", file=sys.stderr)
        sys.exit(1)

    entries = []
    for path in sorted(SPEC_DOMAINS.rglob("*.md")):
        if path.name in ("INDEX.md", "CLAUDE.md"):
            continue
        # Skip old FXX-named files (will be removed by 09-cleanup)
        if re.match(r"^F\d+-", path.name):
            continue
        fm = parse_frontmatter(path.read_text())
        if fm is None:
            print(f"  WARN: no frontmatter in {path.relative_to(REPO_ROOT)}", file=sys.stderr)
            continue
        entries.append({
            "id": fm.get("id"),
            "path": str(path.relative_to(REPO_ROOT)),
            "state": fm.get("state"),
            "version": fm.get("version", 1),
            "domains": fm.get("domains", []),
            "requires": fm.get("requires", []),
            "invalidates": fm.get("invalidates", []),
            "decision_refs": fm.get("decision_refs", []),
            "kb_refs": fm.get("kb_refs", []),
        })

    manifest = {
        "schema_version": 2,
        "generated_at": "2026-04-20",
        "spec_count": len(entries),
        "specs": entries,
    }

    MANIFEST_PATH.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST_PATH.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Wrote {len(entries)} spec entries to {MANIFEST_PATH.relative_to(REPO_ROOT)}")


if __name__ == "__main__":
    main()
