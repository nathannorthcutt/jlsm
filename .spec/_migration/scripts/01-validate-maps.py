#!/usr/bin/env python3
"""
01-validate-maps.py — Structural validator for .spec/_migration/*.map files.

Enforces the five invariants documented in .spec/_migration/README.md:
  1. Every R* key from the source spec appears exactly once in requirements.
  2. destinations[].source_requirements arrays collectively partition the
     requirements key set (no overlap, no missing).
  3. Within each destination, new_rn values form a consecutive sequence
     starting at R1 (no gaps, no duplicates).
  4. Every requirements[Rn].new_spec matches some destinations[i].new_spec.
  5. source_state matches the state field of the source spec's frontmatter.

Also cross-checks that every required source spec has a .map file (given the
expected set of 48 features).

Exit codes:
  0 — all maps valid
  1 — at least one invariant violated
"""

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
MIGRATION_DIR = REPO_ROOT / ".spec" / "_migration"
SPEC_DOMAINS = REPO_ROOT / ".spec" / "domains"

EXPECTED_FEATURES = {f"F{n:02d}" for n in range(1, 49)}


def die(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def read_source_state(source_path):
    """Extract the `state` field from the source spec's JSON frontmatter."""
    spec_file = SPEC_DOMAINS / source_path
    if not spec_file.exists():
        return None
    text = spec_file.read_text()
    m = re.search(r"---\s*\n(.*?)\n---", text, re.DOTALL)
    if not m:
        return None
    try:
        return json.loads(m.group(1)).get("state")
    except json.JSONDecodeError:
        return None


def validate_map(map_path):
    """Return list of violation strings for a single .map file (empty = valid)."""
    violations = []
    try:
        data = json.loads(map_path.read_text())
    except json.JSONDecodeError as e:
        return [f"{map_path.name}: malformed JSON — {e}"]

    source = data.get("source_spec", "?")
    requirements = data.get("requirements", {})
    destinations = data.get("destinations", [])

    # Invariant 1 — total_requirements matches len(requirements)
    total = data.get("total_requirements", 0)
    if total != len(requirements):
        violations.append(
            f"{source}: total_requirements={total} but requirements dict has {len(requirements)} entries"
        )

    # Invariant 2 — destinations partition requirements keys
    all_req_keys = set(requirements.keys())
    union = set()
    for dest in destinations:
        srs = dest.get("source_requirements", [])
        for rn in srs:
            if rn in union:
                violations.append(
                    f"{source}: R-ID {rn} appears in multiple destinations.source_requirements"
                )
            union.add(rn)
    missing = all_req_keys - union
    extra = union - all_req_keys
    if missing:
        violations.append(
            f"{source}: requirements present but not assigned to any destination: {sorted(missing)}"
        )
    if extra:
        violations.append(
            f"{source}: destination.source_requirements contains unknown R-IDs: {sorted(extra)}"
        )

    # Invariant 3 — within each destination, new_rn values are unique. For splits (new_rn
    # differs from source rn), the new_rn sequence must be consecutive R1.. without gaps.
    # For mechanical moves (new_rn == source rn), gaps in source numbering are preserved
    # faithfully — a spec that already has R18/R21/R29 missing keeps those gaps because
    # @spec annotations in code reference specific RNs that we promised to preserve.
    for dest in destinations:
        spec_name = dest.get("new_spec", "?")
        srs = dest.get("source_requirements", [])

        # Detect mechanical move vs split: mechanical iff every requirement's new_rn
        # equals its source rn (the dict key) within this destination.
        is_split = any(
            rn in requirements and requirements[rn]["new_rn"] != rn
            for rn in srs
        )

        new_rns = [requirements[rn]["new_rn"] for rn in srs if rn in requirements]
        seen_numbers = []
        for rn in new_rns:
            m = re.match(r"^R(\d+)([a-z]?)$", rn)
            if not m:
                violations.append(f"{source}→{spec_name}: malformed new_rn {rn!r}")
                continue
            seen_numbers.append((int(m.group(1)), m.group(2)))

        # Gap check applies to splits only (splits renumber from 1)
        if is_split:
            numeric_prefixes = sorted({n for n, _ in seen_numbers})
            if numeric_prefixes:
                expected = list(range(1, max(numeric_prefixes) + 1))
                if numeric_prefixes != expected:
                    gaps = set(expected) - set(numeric_prefixes)
                    violations.append(
                        f"{source}→{spec_name}: split renumbering has gaps — missing {sorted(gaps)}"
                    )

        # Duplicate (n, suffix) check applies to both mechanical and split
        dup_check = {}
        for n, suf in seen_numbers:
            key = (n, suf)
            if key in dup_check:
                violations.append(f"{source}→{spec_name}: duplicate new_rn R{n}{suf}")
            dup_check[key] = True

    # Invariant 4 — every requirement's new_spec matches some destination
    dest_specs = {d.get("new_spec") for d in destinations}
    for rn, info in requirements.items():
        ns = info.get("new_spec")
        if ns not in dest_specs:
            violations.append(
                f"{source}: requirement {rn} new_spec={ns!r} has no matching destinations entry"
            )

    # Invariant 5 — source_state matches live frontmatter
    recorded = data.get("source_state")
    actual = read_source_state(data.get("source_path", ""))
    if actual is None:
        violations.append(
            f"{source}: could not read source spec at {data.get('source_path')}"
        )
    elif recorded != actual:
        violations.append(
            f"{source}: source_state={recorded!r} in .map but spec has state={actual!r}"
        )

    return violations


def main():
    if not MIGRATION_DIR.exists():
        die(f"{MIGRATION_DIR} not found")

    map_files = sorted(MIGRATION_DIR.glob("F*.map"))
    if not map_files:
        die("no .map files to validate — run generate-mechanical-maps.py first")

    all_violations = []
    seen_features = set()
    for mp in map_files:
        data = json.loads(mp.read_text())
        seen_features.add(data.get("source_spec"))
        all_violations.extend(validate_map(mp))

    # Completeness — every expected feature should have a map
    missing_features = EXPECTED_FEATURES - seen_features
    extra_features = seen_features - EXPECTED_FEATURES
    if missing_features:
        all_violations.append(
            f"completeness: .map files missing for {sorted(missing_features)} — hand-craft or regenerate"
        )
    if extra_features:
        all_violations.append(
            f"completeness: unexpected .map files for {sorted(extra_features)} — remove or check F-ID range"
        )

    if all_violations:
        for v in all_violations:
            print(f"  FAIL  {v}")
        print(f"\n{len(all_violations)} violation(s) across {len(map_files)} map file(s)")
        sys.exit(1)

    print(f"OK  {len(map_files)} map file(s) valid, {len(seen_features)}/48 features covered")


if __name__ == "__main__":
    main()
