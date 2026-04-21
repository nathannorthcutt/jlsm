#!/usr/bin/env python3
"""
generate-mechanical-maps.py — Generate .map files for the 41 single-destination features.

Reads .spec/MIGRATION.md §5 table, identifies features that move 1:1 (all requirements
to a single destination spec with preserved RNs), and emits one .map JSON file per
feature into .spec/_migration/.

Split features (listed in SPLITS) are skipped — they require hand-crafted maps.

Usage:  python3 .spec/_migration/scripts/generate-mechanical-maps.py
Must be run from the jlsm repo root.
"""

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
MIGRATION_MD = REPO_ROOT / ".spec" / "MIGRATION.md"
MIGRATION_DIR = REPO_ROOT / ".spec" / "_migration"

# Features that distribute across multiple destinations — handled by hand-crafted .map files
SPLITS = {"F02", "F03", "F10", "F13", "F14", "F17", "F42"}

# §5 table row regex — pipe-delimited with backtick-wrapped paths
ROW_RE = re.compile(
    r"^\|\s*`([^`]+)`\s*\|\s*`([^`]+)`\s*\|\s*`([^`]+)`\s*\|"
)


def parse_section_5(md_text):
    """Return list of (source_path, new_path, new_slug) tuples from §5."""
    lines = md_text.splitlines()
    in_section = False
    rows = []
    for line in lines:
        if line.startswith("## 5. Default destinations"):
            in_section = True
            continue
        if in_section and line.startswith("## 6."):
            break
        if not in_section:
            continue
        m = ROW_RE.match(line)
        if not m:
            continue
        source_path, new_path, new_slug = m.groups()
        if not source_path.startswith(".spec/") and not source_path.startswith("cluster-") and not source_path.startswith(("engine/", "encryption/", "partitioning/", "query/", "serialization/", "storage/", "vector-indexing/")):
            # Skip the header row
            continue
        rows.append((source_path, new_path, new_slug))
    return rows


def extract_feature_id(source_path):
    """F13 from 'engine/F13-jlsm-schema.md'."""
    m = re.search(r"/(F\d+)-", source_path)
    if not m:
        m = re.search(r"^(F\d+)-", source_path)
    return m.group(1) if m else None


def extract_state(spec_file):
    """Parse frontmatter JSON, return state field."""
    text = spec_file.read_text()
    m = re.search(r"---\s*\n(.*?)\n---", text, re.DOTALL)
    if not m:
        return "UNKNOWN"
    try:
        data = json.loads(m.group(1))
        return data.get("state", "UNKNOWN")
    except json.JSONDecodeError:
        return "UNKNOWN"


def extract_requirements(spec_file):
    """Return ordered list of unique requirement IDs (R1, R2, ...) in first-appearance order.

    A "requirement definition" is a line matching `^Rn. <non-whitespace content>` — the
    content check filters out prose references like "chunked per R43." and orphan stubs
    like a bare "R24a." on its own line. Duplicates (e.g., requirement redefined in an
    amendment section) keep the first occurrence and a warning is emitted.
    """
    text = spec_file.read_text()
    # `^Rn. [spaces]<content>` — R-ID + period + same-line content.
    # Restrict whitespace to spaces/tabs (not \n) so orphan stubs like "R43.\n" are skipped
    # even when the next requirement starts on a later line.
    matches = re.findall(r"^(R\d+(?:[a-z])?)\.[ \t]+\S", text, re.MULTILINE)
    seen = set()
    ordered = []
    duplicates = []
    for rn in matches:
        if rn in seen:
            duplicates.append(rn)
            continue
        seen.add(rn)
        ordered.append(rn)
    if duplicates:
        print(f"  WARN: {spec_file.name} has duplicate R-ID definitions: {duplicates} (keeping first)", file=sys.stderr)
    return ordered


def build_mechanical_map(feature_id, source_path, new_path, new_slug, spec_file):
    """Produce the .map JSON dict for a 1:1 feature."""
    reqs = extract_requirements(spec_file)
    if not reqs:
        print(f"  WARN: {feature_id} has no requirements matched in {source_path}", file=sys.stderr)

    # Infer primary domain from new_slug (e.g., "membership.continuous-rediscovery" -> "membership")
    primary_domain = new_slug.split(".")[0]
    new_path_full = f".spec/domains/{new_path}" if not new_path.startswith(".spec/") else new_path

    requirements = {}
    for rn in reqs:
        requirements[rn] = {
            "new_spec": new_slug,
            "new_rn": rn,
            "new_path": new_path_full,
        }

    destinations = [
        {
            "new_spec": new_slug,
            "new_path": new_path_full,
            "domains": [primary_domain],
            "source_requirements": reqs,
        }
    ]

    return {
        "source_spec": feature_id,
        "source_path": str(source_path),
        "source_state": extract_state(spec_file),
        "total_requirements": len(reqs),
        "requirements": requirements,
        "destinations": destinations,
    }


def main():
    if not MIGRATION_MD.exists():
        print(f"ERROR: {MIGRATION_MD} not found", file=sys.stderr)
        sys.exit(1)

    MIGRATION_DIR.mkdir(exist_ok=True)

    md_text = MIGRATION_MD.read_text()
    rows = parse_section_5(md_text)
    print(f"Parsed {len(rows)} rows from §5 table")

    generated = 0
    skipped_splits = 0
    skipped_missing = 0

    for source_path, new_path, new_slug in rows:
        feature_id = extract_feature_id(source_path)
        if not feature_id:
            continue

        if feature_id in SPLITS:
            skipped_splits += 1
            continue

        # Belt-and-suspenders — catches splits not in the hardcoded list.
        # Use exact match on new_slug == "multiple" to avoid false positives like
        # "transport.multiplexed-framing" (substring match caught F19).
        if "*" in new_path or "split" in new_path.lower() or new_slug.strip().lower() == "multiple":
            skipped_splits += 1
            continue

        spec_file = REPO_ROOT / ".spec" / "domains" / source_path
        if not spec_file.exists():
            print(f"  WARN: source file missing for {feature_id}: {spec_file}", file=sys.stderr)
            skipped_missing += 1
            continue

        map_data = build_mechanical_map(feature_id, source_path, new_path, new_slug, spec_file)
        map_path = MIGRATION_DIR / f"{feature_id}.map"
        map_path.write_text(json.dumps(map_data, indent=2) + "\n")
        generated += 1
        print(f"  {feature_id} → {new_slug} ({map_data['total_requirements']} reqs)")

    print(f"\nGenerated {generated} mechanical maps")
    print(f"Skipped {skipped_splits} splits (hand-crafted)")
    if skipped_missing:
        print(f"Skipped {skipped_missing} missing sources — investigate")


if __name__ == "__main__":
    main()
