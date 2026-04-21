#!/usr/bin/env python3
"""
03-move-nonsplit.py — Move features whose .map has exactly one destination
                       and preserves source RNs (mechanical 1:1 + F42-style).

For each .map file:
  1. If destinations == 1 and (preserve_rns OR new_rn == source_rn for all reqs):
     - Read source spec
     - Update frontmatter: id → new_slug, domains updated
     - Rewrite section headers and body for new context (mostly cosmetic)
     - Write to new path
     - Mark source as moved (will be removed by 09-cleanup.sh)
  2. Otherwise: skip — handled by 02-generate-splits.py

Idempotent — overwrites destinations on re-run.
"""

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
MIGRATION_DIR = REPO_ROOT / ".spec" / "_migration"
SPEC_DOMAINS = REPO_ROOT / ".spec" / "domains"


def is_mechanical_or_preserved(map_data):
    """True when the map represents a single-destination move with preserved RNs."""
    if len(map_data["destinations"]) != 1:
        return False
    requirements = map_data["requirements"]
    if not requirements:
        return False
    return all(info["new_rn"] == rn for rn, info in requirements.items())


def parse_frontmatter(text):
    m = re.match(r"^(---\s*\n)(.*?)(\n---\s*\n)(.*)$", text, re.DOTALL)
    if not m:
        return None, text
    try:
        fm = json.loads(m.group(2))
        body = m.group(4)
        return fm, body
    except json.JSONDecodeError:
        return None, text


def write_with_frontmatter(path, frontmatter, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    rendered = "---\n" + json.dumps(frontmatter, indent=2) + "\n---\n" + body
    path.write_text(rendered)


def move_one(map_data):
    source_path = SPEC_DOMAINS / map_data["source_path"]
    dest = map_data["destinations"][0]
    new_path_str = dest["new_path"]
    # new_path is given relative to repo root (.spec/domains/...)
    new_path = REPO_ROOT / new_path_str

    text = source_path.read_text()
    fm, body = parse_frontmatter(text)
    if fm is None:
        raise RuntimeError(f"could not parse frontmatter of {source_path}")

    # Update id and domains
    fm["id"] = dest["new_spec"]
    fm["domains"] = dest["domains"]

    # Update title heading: "# F22 — Continuous Rediscovery" → "# membership.continuous-rediscovery — Continuous Rediscovery"
    # Match `# F<digits> — <title>` and replace prefix
    title_re = re.compile(r"^# F\d+\s*—\s*", re.MULTILINE)
    body = title_re.sub(f"# {dest['new_spec']} — ", body, count=1)

    write_with_frontmatter(new_path, fm, body)
    return source_path, new_path


def main():
    map_files = sorted(MIGRATION_DIR.glob("F*.map"))
    moved = 0
    skipped = 0
    for mp in map_files:
        data = json.loads(mp.read_text())
        if not is_mechanical_or_preserved(data):
            skipped += 1
            continue
        source, dest = move_one(data)
        moved += 1
        print(f"  {data['source_spec']} → {dest.relative_to(REPO_ROOT)}")

    print(f"\nMoved {moved} specs (mechanical + preserve_rns), skipped {skipped} (handled by 02-generate-splits)")


if __name__ == "__main__":
    main()
