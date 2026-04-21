#!/usr/bin/env python3
"""
02-generate-splits.py — Compose new spec files from .map files.

Reads all .map files, collates requirements by destination spec (handling cross-feature
merges where multiple features contribute to one destination — e.g.,
compression.codec-contract receives reqs from both F02 and F17), and writes the resulting
new spec files into .spec/domains/.

Algorithm:
  1. For each .map file, extract per-requirement body text from its source spec
  2. Group reqs by destination (collating across features in feature-id order)
  3. For each destination spec:
     a. Compose frontmatter (union of requires/invalidates/decision_refs/kb_refs;
        most-conservative state)
     b. Compose markdown body: title, ## Requirements with ### sections preserved
        from source, requirements renumbered per .map's new_rn
     c. Write to destination path

Frontmatter cross-references (requires, invalidates, etc.) keep their FXX form here;
they get rewritten to domain.slug form by 05-rewrite-refs.py later.

Idempotent — overwrites destination files on re-run.
"""

import json
import re
import sys
from collections import OrderedDict, defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
MIGRATION_DIR = REPO_ROOT / ".spec" / "_migration"
SPEC_DOMAINS = REPO_ROOT / ".spec" / "domains"


def parse_frontmatter(text):
    m = re.match(r"^(---\s*\n)(.*?)(\n---\s*\n)(.*)$", text, re.DOTALL)
    if not m:
        return None, text
    try:
        return json.loads(m.group(2)), m.group(4)
    except json.JSONDecodeError:
        return None, text


def extract_requirement_bodies(source_text):
    """Return dict {rn: {"section": str, "body": str}} for every R-ID found in
    the ## Requirements top-level section. body includes the original `Rn. ...`
    prefix and all continuation lines up to the next R-ID, ### header, or ## boundary.
    """
    in_requirements = False
    current_section = None
    current_rn = None
    current_lines = []
    bodies = {}

    def flush():
        nonlocal current_rn, current_lines
        if current_rn is not None:
            bodies[current_rn] = {
                "section": current_section,
                "body": "\n".join(current_lines).rstrip(),
            }
            current_rn = None
            current_lines = []

    for line in source_text.splitlines():
        if line.startswith("## "):
            flush()
            in_requirements = line.startswith("## Requirements")
            current_section = None
            continue
        if not in_requirements:
            continue
        if line.startswith("### "):
            flush()
            current_section = line[4:].strip()
            continue
        m = re.match(r"^(R\d+(?:[a-z])?)\.[ \t]+\S", line)
        if m:
            flush()
            current_rn = m.group(1)
            current_lines = [line]
        elif current_rn is not None:
            current_lines.append(line)
    flush()
    return bodies


def title_from_slug(slug):
    """`sstable.striped-block-cache` → 'Striped Block Cache'."""
    last = slug.split(".")[-1]
    return " ".join(w.capitalize() for w in last.split("-"))


def merge_state(states):
    """Most conservative wins: INVALIDATED > DRAFT > APPROVED."""
    order = {"INVALIDATED": 3, "DRAFT": 2, "APPROVED": 1}
    return max(states, key=lambda s: order.get(s, 0))


def union_lists(lists):
    """Return de-duplicated union of all input lists, preserving first-seen order."""
    seen = set()
    out = []
    for lst in lists:
        for item in lst or []:
            if item not in seen:
                seen.add(item)
                out.append(item)
    return out


def main():
    map_files = sorted(MIGRATION_DIR.glob("F*.map"))
    if not map_files:
        print("ERROR: no .map files found", file=sys.stderr)
        sys.exit(1)

    # Parse all maps; gather per-feature data
    feature_data = {}
    for mp in map_files:
        data = json.loads(mp.read_text())
        fid = data["source_spec"]
        source_path = SPEC_DOMAINS / data["source_path"]
        if not source_path.exists():
            print(f"ERROR: source spec missing for {fid}: {source_path}", file=sys.stderr)
            sys.exit(1)
        source_text = source_path.read_text()
        fm, _ = parse_frontmatter(source_text)
        bodies = extract_requirement_bodies(source_text)
        feature_data[fid] = {
            "map": data,
            "frontmatter": fm,
            "bodies": bodies,
            "source_path": source_path,
        }

    # Collate by destination: dest -> ordered list of (feature_id, source_rn, new_rn, body, section)
    dest_contributions = defaultdict(list)
    dest_domains = {}

    # Process features in F-ID numeric order so cross-feature merges have stable ordering
    def fid_key(fid):
        return int(fid[1:]) if fid.startswith("F") else 9999

    for fid in sorted(feature_data.keys(), key=fid_key):
        fdata = feature_data[fid]
        map_data = fdata["map"]
        bodies = fdata["bodies"]

        # For each destination in this map, append its requirements in source order
        for dest in map_data["destinations"]:
            spec_id = dest["new_spec"]
            dest_domains[spec_id] = dest["domains"]  # last writer wins; should match across maps
            for source_rn in dest["source_requirements"]:
                if source_rn not in bodies:
                    print(f"WARN: {fid}.{source_rn} listed in map but no body extracted from source", file=sys.stderr)
                    continue
                req_info = map_data["requirements"][source_rn]
                body_info = bodies[source_rn]
                dest_contributions[spec_id].append({
                    "feature_id": fid,
                    "source_rn": source_rn,
                    "new_rn": req_info["new_rn"],
                    "body": body_info["body"],
                    "section": body_info["section"] or "(no section)",
                })

    # Track destination paths from any contributing map
    dest_paths = {}
    for fdata in feature_data.values():
        for d in fdata["map"]["destinations"]:
            dest_paths[d["new_spec"]] = d["new_path"]

    # Compose and write each destination spec.
    # Final RN table maps (source_feature, source_rn) → (destination_spec, final_rn)
    # so the annotation rewriter knows the authoritative final RN for each source pair.
    final_rn_table = {}
    written = 0

    # Identify "pure move" destinations: single-feature contribution where every req's
    # new_rn equals its source rn. For these we preserve the full source body
    # (Design Narrative, Verification Notes, etc.) and only update frontmatter+title.
    # Splits and cross-feature merges compose from sections.
    def is_pure_move(spec_id, contributions, contributing_features):
        if len(contributing_features) != 1:
            return False
        return all(c["new_rn"] == c["source_rn"] for c in contributions)

    for spec_id, contributions in dest_contributions.items():
        new_path = REPO_ROOT / dest_paths[spec_id]

        # Aggregate frontmatter fields across contributing features
        contributing_features = list({c["feature_id"] for c in contributions})
        contributing_features.sort(key=fid_key)
        frontmatters = [feature_data[fid]["frontmatter"] for fid in contributing_features]
        states = [feature_data[fid]["map"]["source_state"] for fid in contributing_features]

        # Decide RN assignment strategy:
        #   - Single-feature destination: trust the .map's new_rn (handles preserve_rns
        #     for F42-style 1:1 moves and within-feature renumbering for splits)
        #   - Multi-feature destination: globally renumber R1..N across all contributions
        #     in feature-id order so RNs are unique within the spec
        is_multi_feature = len(contributing_features) > 1
        pure_move = is_pure_move(spec_id, contributions, contributing_features)

        if is_multi_feature:
            counter = 1
            for c in contributions:
                final_rn = f"R{counter}"
                counter += 1
                c["final_rn"] = final_rn
                final_rn_table[f"{c['feature_id']}.{c['source_rn']}"] = {
                    "destination": spec_id,
                    "final_rn": final_rn,
                }
        else:
            for c in contributions:
                c["final_rn"] = c["new_rn"]
                final_rn_table[f"{c['feature_id']}.{c['source_rn']}"] = {
                    "destination": spec_id,
                    "final_rn": c["new_rn"],
                }

        # Pure move: preserve full source body (Design Narrative, Verification Notes, etc.)
        # Only update frontmatter id+domains and title heading.
        if pure_move:
            fid = contributing_features[0]
            fdata = feature_data[fid]
            source_text = fdata["source_path"].read_text()
            source_fm, source_body = parse_frontmatter(source_text)
            new_fm = dict(source_fm)
            new_fm["id"] = spec_id
            new_fm["domains"] = dest_domains[spec_id]
            new_fm["_migrated_from"] = [fid]
            # Update title heading "# F22 — ..." → "# spec_id — ..."
            new_body = re.sub(r"^# F\d+\s*—\s*", f"# {spec_id} — ", source_body, count=1, flags=re.MULTILINE)
            new_path.parent.mkdir(parents=True, exist_ok=True)
            rendered = "---\n" + json.dumps(new_fm, indent=2) + "\n---\n" + new_body
            new_path.write_text(rendered)
            written += 1
            print(f"  {spec_id}  (pure move from {fid}, {len(contributions)} reqs preserved) → {new_path.relative_to(REPO_ROOT)}")
            continue

        fm = {
            "id": spec_id,
            "version": 1,
            "status": "ACTIVE",
            "state": merge_state(states),
            "domains": dest_domains[spec_id],
            "requires": union_lists(f.get("requires", []) for f in frontmatters),
            "invalidates": union_lists(f.get("invalidates", []) for f in frontmatters),
            "amends": None,
            "amended_by": None,
            "decision_refs": union_lists(f.get("decision_refs", []) for f in frontmatters),
            "kb_refs": union_lists(f.get("kb_refs", []) for f in frontmatters),
            "open_obligations": union_lists(f.get("open_obligations", []) for f in frontmatters),
            "_migrated_from": sorted({fid for fid in contributing_features}),
        }

        title = title_from_slug(spec_id)
        body_lines = [f"# {spec_id} — {title}\n", "## Requirements\n"]

        # Within each contributing feature, group by section (preserve source order)
        for fid in contributing_features:
            feature_contribs = [c for c in contributions if c["feature_id"] == fid]
            sections = OrderedDict()
            for c in feature_contribs:
                sections.setdefault(c["section"], []).append(c)

            # Multi-feature merge: prefix sections with source feature
            section_prefix = f" *(from {fid})*" if is_multi_feature else ""

            for section_name, reqs in sections.items():
                body_lines.append(f"### {section_name}{section_prefix}\n")
                for req in reqs:
                    # Replace leading `Rn.` with `final_rn.` (only the first occurrence)
                    rewritten = re.sub(r"^R\d+[a-z]?\.", f"{req['final_rn']}.", req["body"], count=1)
                    body_lines.append(rewritten + "\n")

        body = "\n".join(body_lines)

        # Render
        new_path.parent.mkdir(parents=True, exist_ok=True)
        rendered = "---\n" + json.dumps(fm, indent=2) + "\n---\n\n" + body
        new_path.write_text(rendered)
        written += 1
        feature_summary = "+".join(contributing_features) if is_multi_feature else contributing_features[0]
        print(f"  {spec_id}  ({len(contributions)} reqs from {feature_summary}) → {new_path.relative_to(REPO_ROOT)}")

    # Emit final-rn-table.json for the annotation rewriter
    final_rn_path = MIGRATION_DIR / "final-rn-table.json"
    final_rn_path.write_text(json.dumps(final_rn_table, indent=2, sort_keys=True) + "\n")
    print(f"\nWrote {written} destination spec files")
    print(f"Wrote final-rn-table.json with {len(final_rn_table)} (source_feature.rn → destination.rn) mappings")


if __name__ == "__main__":
    main()
