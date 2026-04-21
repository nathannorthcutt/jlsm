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
SPLIT_RULES_PATH = MIGRATION_DIR / "split-rules.json"

# Source directories to scan (override with SOURCE_DIRS env var: comma-separated)
SOURCE_DIRS = os.environ.get("SOURCE_DIRS", "modules,examples,benchmarks").split(",")

# File extensions whose @spec comments we rewrite. Anything text-y with line comments works.
SOURCE_EXTS = {".java", ".kt", ".py", ".ts", ".tsx", ".js", ".rs", ".go"}

# @spec line pattern. Matches all common comment styles:
#   //  — Java/JS/Rust/Go/TS line comments
#   *   — Javadoc/JSDoc continuation lines (within /** ... */ blocks)
#   #   — Python/shell
#   --  — SQL/Lua/Haskell
# Captures: leading whitespace+marker, then "@spec ", then F<id>.R<rn>[,R<rn>]*
ANNOT_RE = re.compile(
    r"^(?P<lead>\s*(?://|#|--|\*)\s*@spec\s+)(?P<feature>F\d+)\.(?P<rns>R\d+[a-z]?(?:,R\d+[a-z]?)*)(?P<trailer>.*)$",
    re.MULTILINE,
)


def load_final_rn_table():
    if not FINAL_RN_TABLE_PATH.exists():
        print(f"ERROR: {FINAL_RN_TABLE_PATH} not found — run 02-generate-splits.py first", file=sys.stderr)
        sys.exit(1)
    return json.loads(FINAL_RN_TABLE_PATH.read_text())


def load_dropped_requirements():
    """Return dict {feature.rn: reason} for every requirement explicitly dropped
    during migration (e.g., F02 reqs invalidated by F17, F14 YAML reqs)."""
    if not SPLIT_RULES_PATH.exists():
        return {}
    rules = json.loads(SPLIT_RULES_PATH.read_text())
    dropped = {}
    for fid, frules in rules.items():
        if fid.startswith("$"):
            continue
        for rn, reason in frules.get("dropped_requirements", {}).items():
            dropped[f"{fid}.{rn}"] = reason
    return dropped


def resolve_annotation(feature, rns_str, table, dropped):
    """Resolve a single @spec FXX.R1[,R2,...] occurrence.

    Returns: (by_dest, unresolved, dropped_rns)
      - by_dest: {destination: [final_rns]} for resolvable RNs
      - unresolved: list of FXX.RN with no mapping (real errors)
      - dropped_rns: list of FXX.RN that were intentionally dropped during migration
    """
    rns = rns_str.split(",")
    by_dest = {}
    unresolved = []
    dropped_rns = []
    for rn in rns:
        key = f"{feature}.{rn}"
        if key in dropped:
            dropped_rns.append(key)
            continue
        entry = table.get(key)
        if not entry:
            unresolved.append(key)
            continue
        dest = entry["destination"]
        by_dest.setdefault(dest, []).append(entry["final_rn"])
    return by_dest, unresolved, dropped_rns


def rewrite_file(path, table, dropped):
    text = path.read_text()
    new_lines = []
    changed = False
    unresolved_in_file = []
    dropped_in_file = []

    for line in text.splitlines(keepends=True):
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
        # Extract the comment marker prefix (everything before "@spec")
        # so we can re-emit a plain comment for dropped reqs.
        comment_prefix_m = re.match(r"^(\s*(?://|#|--|\*))\s*@spec\s+", lead)
        comment_prefix = comment_prefix_m.group(1) if comment_prefix_m else "//"

        by_dest, unresolved, dropped_rns = resolve_annotation(feature, rns_str, table, dropped)

        # If ALL RNs in the annotation were dropped, convert to a historical comment
        # (preserves the trailing text but loses the @spec trace so spec-trace ignores it)
        if dropped_rns and not by_dest and not unresolved:
            dropped_in_file.extend(dropped_rns)
            historical = f"{comment_prefix} (formerly @spec {feature}.{rns_str} — dropped during migration){trailer}"
            new_lines.append(historical + eol)
            changed = True
            continue

        if unresolved:
            unresolved_in_file.extend(unresolved)
            new_lines.append(line)  # leave unchanged so reviewer can see
            continue

        if len(by_dest) == 1:
            dest, final_rns = next(iter(by_dest.items()))
            new_annot = f"{lead}{dest}.{','.join(final_rns)}{trailer}"
            if dropped_rns:
                # Some RNs resolved, some dropped — annotate the dropped ones in trailer
                new_annot += f"  /* dropped: {','.join(dropped_rns)} */"
            new_lines.append(new_annot + eol)
            changed = True
        else:
            for dest, final_rns in by_dest.items():
                new_lines.append(f"{lead}{dest}.{','.join(final_rns)}{trailer}{eol}")
            if dropped_rns:
                new_lines.append(f"{comment_prefix} (formerly {','.join(dropped_rns)} — dropped during migration){eol}")
            changed = True

    # Second pass: handle inline @spec FXX.RN references that aren't at start-of-comment-line
    # (e.g., mid-Javadoc-prose like "the write-path gate (@spec F04.R41).").
    # These are word-level substitutions — no line restructuring.
    text_after_pass1 = "".join(new_lines)
    inline_re = re.compile(r"@spec\s+(F\d+)\.(R\d+[a-z]?(?:,R\d+[a-z]?)*)")

    def inline_repl(m):
        nonlocal changed
        feature, rns_str = m.group(1), m.group(2)
        by_dest, unresolved, dropped_rns = resolve_annotation(feature, rns_str, table, dropped)
        if dropped_rns and not by_dest:
            # Leave mid-prose dropped references untouched (they read fine as historical refs)
            dropped_in_file.extend(dropped_rns)
            return m.group(0)
        if unresolved:
            unresolved_in_file.extend(unresolved)
            return m.group(0)
        if len(by_dest) == 1:
            dest, final_rns = next(iter(by_dest.items()))
            changed = True
            return f"@spec {dest}.{','.join(final_rns)}"
        # Multi-destination inline — emit as space-separated list (keeps readability)
        parts = [f"@spec {dest}.{','.join(rns)}" for dest, rns in by_dest.items()]
        changed = True
        return " ".join(parts)

    new_text = inline_re.sub(inline_repl, text_after_pass1)
    if new_text != text_after_pass1:
        changed = True

    if changed:
        path.write_text(new_text)
    return changed, unresolved_in_file, dropped_in_file


def main():
    table = load_final_rn_table()
    dropped = load_dropped_requirements()
    print(f"Loaded {len(table)} (source.rn → destination.final_rn) mappings")
    print(f"Loaded {len(dropped)} explicitly-dropped requirements")

    files_scanned = 0
    files_changed = 0
    all_unresolved = []
    all_dropped_seen = []

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
                changed, unresolved, dropped_seen = rewrite_file(path, table, dropped)
            except UnicodeDecodeError:
                continue
            if changed:
                files_changed += 1
            if unresolved:
                rel = path.relative_to(REPO_ROOT)
                for u in unresolved:
                    all_unresolved.append(f"{rel}: {u}")
            if dropped_seen:
                rel = path.relative_to(REPO_ROOT)
                for d in dropped_seen:
                    all_dropped_seen.append(f"{rel}: {d}")

    print(f"\nScanned {files_scanned} source files, modified {files_changed}")
    if all_dropped_seen:
        print(f"\nConverted {len(all_dropped_seen)} dropped-requirement annotation(s) to historical comments:")
        for d in all_dropped_seen[:20]:
            print(f"  {d}")
        if len(all_dropped_seen) > 20:
            print(f"  ... and {len(all_dropped_seen) - 20} more")
    if all_unresolved:
        print(f"\nERROR: {len(all_unresolved)} unresolved annotation(s) — leftover @spec FXX.RN with no final-rn mapping:")
        for u in all_unresolved[:30]:
            print(f"  {u}")
        if len(all_unresolved) > 30:
            print(f"  ... and {len(all_unresolved) - 30} more")
        sys.exit(1)


if __name__ == "__main__":
    main()
