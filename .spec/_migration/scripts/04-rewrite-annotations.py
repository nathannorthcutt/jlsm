#!/usr/bin/env python3
"""
04-rewrite-annotations.py — Rewrite @spec FXX.RN annotations in source code.

Reads .spec/_migration/final-rn-table.json (produced by 02-generate-splits.py)
and walks the source tree, replacing every @spec FXX.RN with its new
@spec domain.slug.RN form.

Handles:
  - Single annotation:  // @spec F13.R5  → // @spec schema.schema-construction.R5
  - Multi-RN comma list: // @spec F12.R24,R25,R26
      If all RNs map to the same destination → single rewritten annotation
      Otherwise → split into multiple lines (each with its own destination)
  - Sub-lettered RNs:  R51a, R39h, etc.

Walks modules/ by default (configurable via SOURCE_DIRS env var).
Reports per-file changes; exits non-zero if any annotation is unresolvable.

Idempotent — running twice on already-rewritten code is a no-op (already-rewritten
annotations don't match the FXX pattern).
"""

import json
import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
MIGRATION_DIR = REPO_ROOT / ".spec" / "_migration"
FINAL_RN_TABLE_PATH = MIGRATION_DIR / "final-rn-table.json"

# Source directories to scan (override with SOURCE_DIRS env var: comma-separated)
SOURCE_DIRS = os.environ.get("SOURCE_DIRS", "modules,examples,benchmarks").split(",")

# File extensions whose @spec comments we rewrite. Anything text-y with line comments works.
SOURCE_EXTS = {".java", ".kt", ".py", ".ts", ".tsx", ".js", ".rs", ".go"}

# @spec line pattern (anchored to start-of-comment but tolerant of indentation)
# Captures: leading whitespace+comment marker, then "@spec ", then F<id>.R<rn>[,<rn>]*
ANNOT_RE = re.compile(
    r"^(?P<lead>\s*(?://|#|--)\s*@spec\s+)(?P<feature>F\d+)\.(?P<rns>R\d+[a-z]?(?:,R\d+[a-z]?)*)(?P<trailer>.*)$",
    re.MULTILINE,
)


def load_final_rn_table():
    if not FINAL_RN_TABLE_PATH.exists():
        print(f"ERROR: {FINAL_RN_TABLE_PATH} not found — run 02-generate-splits.py first", file=sys.stderr)
        sys.exit(1)
    return json.loads(FINAL_RN_TABLE_PATH.read_text())


def resolve_annotation(feature, rns_str, table):
    """Resolve a single @spec FXX.R1[,R2,...] occurrence.

    Returns: list of (destination, [final_rns]) tuples — one tuple per destination
    that any of the input RNs map to. Multi-destination splits the annotation.
    """
    rns = rns_str.split(",")
    by_dest = {}
    unresolved = []
    for rn in rns:
        key = f"{feature}.{rn}"
        entry = table.get(key)
        if not entry:
            unresolved.append(key)
            continue
        dest = entry["destination"]
        by_dest.setdefault(dest, []).append(entry["final_rn"])
    return by_dest, unresolved


def rewrite_file(path, table):
    text = path.read_text()
    new_lines = []
    changed = False
    unresolved_in_file = []

    for line in text.splitlines(keepends=True):
        # Strip trailing newline for matching, re-add when emitting
        stripped = line.rstrip("\n")
        eol = line[len(stripped):]
        m = ANNOT_RE.match(stripped)
        if not m:
            new_lines.append(line)
            continue

        feature = m.group("feature")
        rns_str = m.group("rns")
        lead = m.group("lead")
        trailer = m.group("trailer")

        by_dest, unresolved = resolve_annotation(feature, rns_str, table)
        if unresolved:
            unresolved_in_file.extend(unresolved)
            new_lines.append(line)  # leave unchanged so reviewer can see
            continue

        if len(by_dest) == 1:
            # Single destination — one rewritten line
            dest, final_rns = next(iter(by_dest.items()))
            new_annot = f"{lead}{dest}.{','.join(final_rns)}{trailer}"
            new_lines.append(new_annot + eol)
            changed = True
        else:
            # Multi-destination — emit one line per destination
            for dest, final_rns in by_dest.items():
                new_lines.append(f"{lead}{dest}.{','.join(final_rns)}{trailer}{eol}")
            changed = True

    if changed:
        path.write_text("".join(new_lines))
    return changed, unresolved_in_file


def main():
    table = load_final_rn_table()
    print(f"Loaded {len(table)} (source.rn → destination.final_rn) mappings")

    files_scanned = 0
    files_changed = 0
    all_unresolved = []

    for source_dir in SOURCE_DIRS:
        root = REPO_ROOT / source_dir.strip()
        if not root.exists():
            print(f"  (skip {source_dir}/ — not found)")
            continue

        for path in root.rglob("*"):
            if not path.is_file():
                continue
            if path.suffix not in SOURCE_EXTS:
                continue
            files_scanned += 1
            try:
                changed, unresolved = rewrite_file(path, table)
            except UnicodeDecodeError:
                continue  # binary file with extension match; skip
            if changed:
                files_changed += 1
            if unresolved:
                rel = path.relative_to(REPO_ROOT)
                for u in unresolved:
                    all_unresolved.append(f"{rel}: {u}")

    print(f"\nScanned {files_scanned} source files, modified {files_changed}")
    if all_unresolved:
        print(f"\nERROR: {len(all_unresolved)} unresolved annotation(s) — leftover @spec FXX.RN with no final-rn mapping:")
        for u in all_unresolved[:30]:
            print(f"  {u}")
        if len(all_unresolved) > 30:
            print(f"  ... and {len(all_unresolved) - 30} more")
        sys.exit(1)


if __name__ == "__main__":
    main()
