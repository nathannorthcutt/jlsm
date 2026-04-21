#!/usr/bin/env python3
"""
generate-split-maps.py — Generate .map files for the 7 split features.

Reads .spec/_migration/split-rules.json, which defines for each split feature:
  - source_path
  - section_rules:  '### Header' text → destination spec
  - requirement_overrides:  per-req destination (for cross-section judgment calls)
  - destinations:  output spec paths and domain tags

For each split:
  1. Parse source spec, walking requirements section-by-section
  2. Assign each R-ID to destination via override-or-section-rule
  3. Renumber new_rn consecutively from R1 within each destination
  4. Emit .map file validating that every R-ID was assigned

Exit 0 on success, 1 if any requirement couldn't be classified (missing rule).

Usage:  python3 .spec/_migration/scripts/generate-split-maps.py [FEATURE_ID ...]
        (no args = generate all splits with rules)
"""

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
MIGRATION_DIR = REPO_ROOT / ".spec" / "_migration"
SPEC_DOMAINS = REPO_ROOT / ".spec" / "domains"
RULES_FILE = MIGRATION_DIR / "split-rules.json"


def read_source_state(source_path):
    spec_file = SPEC_DOMAINS / source_path
    text = spec_file.read_text()
    m = re.search(r"---\s*\n(.*?)\n---", text, re.DOTALL)
    if not m:
        return "UNKNOWN"
    try:
        return json.loads(m.group(1)).get("state", "UNKNOWN")
    except json.JSONDecodeError:
        return "UNKNOWN"


def walk_requirements(spec_text):
    """Yield (requirement_id, section_header) pairs in document order.

    Section header is the text of the most recent `### Name` before the requirement.
    Scans only the `## Requirements` top-level block; stops at next `## ` boundary.
    Skips prose references and orphan stubs (same rules as mechanical generator).
    """
    in_requirements = False
    current_section = None
    seen = set()
    for line in spec_text.splitlines():
        # Track top-level section boundaries
        if line.startswith("## "):
            in_requirements = line.startswith("## Requirements")
            current_section = None
            continue
        if not in_requirements:
            continue
        # Sub-section headers within Requirements
        if line.startswith("### "):
            current_section = line[4:].strip()
            continue
        # Requirement definition: `^Rn. <content>` with content on same line
        m = re.match(r"^(R\d+(?:[a-z])?)\.[ \t]+\S", line)
        if m:
            rn = m.group(1)
            if rn in seen:
                continue  # duplicates handled same as mechanical generator
            seen.add(rn)
            yield rn, current_section


def generate_split_map(feature_id, rules):
    source_path = rules["source_path"]
    section_rules = rules.get("section_rules", {})
    overrides = rules.get("requirement_overrides", {})
    destinations_config = rules["destinations"]
    dropped = rules.get("dropped_requirements", {})  # {R-ID: reason}

    spec_file = SPEC_DOMAINS / source_path
    if not spec_file.exists():
        raise FileNotFoundError(f"source spec not found: {spec_file}")

    spec_text = spec_file.read_text()
    state = read_source_state(source_path)

    # Classify each requirement
    per_dest_reqs = {dest: [] for dest in destinations_config}
    unassigned = []
    dropped_seen = []
    total = 0

    for rn, section in walk_requirements(spec_text):
        if rn in dropped:
            dropped_seen.append(rn)
            continue
        total += 1
        # Override takes precedence over section rule
        if rn in overrides:
            dest = overrides[rn]["destination"]
        elif section in section_rules:
            dest = section_rules[section]
        else:
            unassigned.append((rn, section))
            continue

        if dest not in per_dest_reqs:
            raise ValueError(f"{feature_id}: rule assigns {rn} to {dest!r}, not in destinations")
        per_dest_reqs[dest].append(rn)

    # Sanity check: every declared drop should have appeared in the spec
    missing_drops = set(dropped.keys()) - set(dropped_seen)
    if missing_drops:
        print(f"  WARN: {feature_id} declares drop for {sorted(missing_drops)} but those R-IDs not found in spec", file=sys.stderr)

    if unassigned:
        print(f"ERROR: {feature_id} has {len(unassigned)} unassigned requirement(s):", file=sys.stderr)
        for rn, section in unassigned:
            print(f"  {rn} (section: {section!r})", file=sys.stderr)
        raise SystemExit(1)

    # Build requirements dict. For real splits the new_rn renumbers consecutively
    # from R1; for features that move cross-domain but keep their ID sequence
    # (e.g., F42 moving encryption→wal with multi-domain tag), preserve_rns flag
    # keeps source RNs intact. Letter suffixes (R51a) are preserved in both modes.
    preserve_rns = rules.get("preserve_rns", False)
    requirements = {}
    destinations_out = []
    for dest, rns in per_dest_reqs.items():
        dconfig = destinations_config[dest]
        new_path = dconfig["new_path"]
        for i, rn in enumerate(rns, start=1):
            new_rn = rn if preserve_rns else f"R{i}"
            requirements[rn] = {
                "new_spec": dest,
                "new_rn": new_rn,
                "new_path": new_path,
            }
        destinations_out.append(
            {
                "new_spec": dest,
                "new_path": new_path,
                "domains": dconfig.get("domains", [dest.split(".")[0]]),
                "source_requirements": rns,
            }
        )

    map_data = {
        "source_spec": feature_id,
        "source_path": source_path,
        "source_state": state,
        "total_requirements": total,
        "requirements": requirements,
        "destinations": destinations_out,
    }
    return map_data


def main():
    if not RULES_FILE.exists():
        print(f"ERROR: {RULES_FILE} not found", file=sys.stderr)
        sys.exit(1)

    raw = json.loads(RULES_FILE.read_text())
    # Strip $-prefixed doc/comment keys
    all_rules = {k: v for k, v in raw.items() if not k.startswith("$")}

    requested = sys.argv[1:] if len(sys.argv) > 1 else list(all_rules.keys())
    unknown = [r for r in requested if r not in all_rules]
    if unknown:
        print(f"ERROR: no rules for {unknown}", file=sys.stderr)
        sys.exit(1)

    for fid in requested:
        rules = all_rules[fid]
        try:
            map_data = generate_split_map(fid, rules)
        except SystemExit:
            raise
        except Exception as e:
            print(f"ERROR: {fid}: {e}", file=sys.stderr)
            sys.exit(1)

        map_path = MIGRATION_DIR / f"{fid}.map"
        map_path.write_text(json.dumps(map_data, indent=2) + "\n")
        dest_count = len(map_data["destinations"])
        total = map_data["total_requirements"]
        print(f"  {fid} → {dest_count} destinations, {total} reqs")


if __name__ == "__main__":
    main()
