#!/usr/bin/env python3
"""
06-rewrite-work-layer.py — Rewrite FXX references in .work/ files.

Walks .work/**/*.md and replaces any FXX or FXX.RN tokens with their
domain.slug or domain.slug.RN equivalents using final-rn-table.json.

Targets: artifact_deps refs, brief.md acceptance criteria text, WD frontmatter,
manifest tables.

Idempotent.
"""

import json
import re
import sys
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
MIGRATION_DIR = REPO_ROOT / ".spec" / "_migration"
WORK_DIR = REPO_ROOT / ".work"
FINAL_RN_TABLE_PATH = MIGRATION_DIR / "final-rn-table.json"


def load_table():
    if not FINAL_RN_TABLE_PATH.exists():
        print(f"ERROR: {FINAL_RN_TABLE_PATH} not found", file=sys.stderr)
        sys.exit(1)
    return json.loads(FINAL_RN_TABLE_PATH.read_text())


def build_feature_dest_map(table):
    by_feature = defaultdict(list)
    for key, entry in table.items():
        feature = key.split(".")[0]
        if entry["destination"] not in by_feature[feature]:
            by_feature[feature].append(entry["destination"])
    return by_feature


def rewrite_text(text, table, feature_dests, warnings):
    """Replace FXX.RN tokens (preceded/followed by word boundaries) in arbitrary text."""

    def repl_full(m):
        feature, rn = m.group(1), m.group(2)
        key = f"{feature}.{rn}"
        entry = table.get(key)
        if not entry:
            warnings.append(f"unresolved: {key}")
            return m.group(0)
        return f"{entry['destination']}.{entry['final_rn']}"

    text = re.sub(r"\b(F\d+)\.(R\d+[a-z]?)\b", repl_full, text)

    def repl_bare(m):
        feature = m.group(1)
        # Don't rewrite if followed by .R (already handled above)
        dests = feature_dests.get(feature, [])
        if not dests:
            warnings.append(f"unresolved bare: {feature}")
            return feature
        if len(dests) > 1:
            warnings.append(f"{feature} ambiguous (multi-dest); using {dests[0]}")
        return dests[0]

    text = re.sub(r"\b(F\d+)\b", repl_bare, text)
    return text


def main():
    if not WORK_DIR.exists():
        print(".work/ not present — nothing to rewrite")
        return

    table = load_table()
    feature_dests = build_feature_dest_map(table)

    files_scanned = 0
    files_changed = 0
    all_warnings = []

    for path in WORK_DIR.rglob("*.md"):
        if path.name == "CLAUDE.md":
            continue
        files_scanned += 1
        try:
            text = path.read_text()
        except UnicodeDecodeError:
            continue
        warnings = []
        new_text = rewrite_text(text, table, feature_dests, warnings)
        if new_text != text:
            path.write_text(new_text)
            files_changed += 1
            print(f"  rewrote {path.relative_to(REPO_ROOT)}")
        all_warnings.extend((path.relative_to(REPO_ROOT), w) for w in warnings)

    print(f"\nScanned {files_scanned} work files, rewrote {files_changed}")
    if all_warnings:
        print(f"\n{len(all_warnings)} warning(s):")
        for src, w in all_warnings[:20]:
            print(f"  {src}: {w}")


if __name__ == "__main__":
    main()
