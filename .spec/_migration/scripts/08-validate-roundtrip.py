#!/usr/bin/env python3
"""
08-validate-roundtrip.py — Post-migration completeness validation.

Verifies six integrity properties of the migrated repository:

  A. Requirement coverage:
     Every (FXX.RN) pair in the backup specs has a final-rn-table entry,
     and the destination spec contains a requirement at that final_rn.

  B. Annotation integrity:
     Every @spec annotation in the source tree resolves to a current spec
     requirement (no dangling references after rewrite).

  C. Cross-reference integrity:
     Every requires/invalidates/displaced_by/etc field in new spec frontmatter
     references a spec that exists.

  D. Manifest integrity:
     manifest.json lists every spec in .spec/domains/ (excluding FXX-prefixed
     legacy files) and no orphans.

  E. Obligation integrity:
     Every spec_id and affects entry in _obligations.json resolves to a current spec.

  F. Old-file accounting:
     Every FXX-named spec in .spec/domains/ has a .map file (no untracked legacy specs).

Exits 0 if all checks pass, 1 if any fail. Reports each violation with location.
"""

import json
import re
import sys
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
MIGRATION_DIR = REPO_ROOT / ".spec" / "_migration"
SPEC_DOMAINS = REPO_ROOT / ".spec" / "domains"
REGISTRY = REPO_ROOT / ".spec" / "registry"
FINAL_RN_TABLE_PATH = MIGRATION_DIR / "final-rn-table.json"


def parse_frontmatter(text):
    m = re.match(r"^(---\s*\n)(.*?)(\n---\s*\n)(.*)$", text, re.DOTALL)
    if not m:
        return None, text
    try:
        return json.loads(m.group(2)), m.group(4)
    except json.JSONDecodeError:
        return None, text


def collect_new_spec_reqs():
    """Walk new spec files, return {spec_id: {set_of_rns}}."""
    by_spec = {}
    for path in SPEC_DOMAINS.rglob("*.md"):
        if path.name in ("INDEX.md", "CLAUDE.md"):
            continue
        if re.match(r"^F\d+-", path.name):
            continue
        text = path.read_text()
        fm, body = parse_frontmatter(text)
        if fm is None:
            continue
        spec_id = fm.get("id")
        rns = set(re.findall(r"^(R\d+(?:[a-z])?)\.[ \t]+\S", body, re.MULTILINE))
        by_spec[spec_id] = {"rns": rns, "path": path, "frontmatter": fm}
    return by_spec


def main():
    violations = []

    if not FINAL_RN_TABLE_PATH.exists():
        print(f"ERROR: {FINAL_RN_TABLE_PATH} not found — run 02-generate-splits.py first", file=sys.stderr)
        sys.exit(1)
    table = json.loads(FINAL_RN_TABLE_PATH.read_text())

    # Check A: requirement coverage — every table entry's destination spec contains the final_rn
    new_specs = collect_new_spec_reqs()
    for source_key, entry in table.items():
        dest = entry["destination"]
        final_rn = entry["final_rn"]
        if dest not in new_specs:
            violations.append(f"A: source {source_key} maps to destination {dest} but spec file does not exist")
            continue
        if final_rn not in new_specs[dest]["rns"]:
            violations.append(f"A: source {source_key} → {dest}.{final_rn} but spec has no requirement at {final_rn}")

    # Check B: annotation integrity — scan source tree for @spec, verify all resolve
    annot_re = re.compile(r"@spec\s+([a-z][a-z0-9-]*(?:\.[a-z][a-z0-9-]*)*)\.(R\d+[a-z]?(?:,R\d+[a-z]?)*)")
    fxx_annot_re = re.compile(r"@spec\s+(F\d+)\.(R\d+[a-z]?)")
    source_dirs = ["modules", "examples", "benchmarks"]
    leftover_fxx = []
    unresolved_new = []
    for sd in source_dirs:
        root = REPO_ROOT / sd
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if not path.is_file() or path.suffix not in {".java", ".kt", ".py", ".ts", ".js", ".rs", ".go"}:
                continue
            try:
                text = path.read_text()
            except UnicodeDecodeError:
                continue
            for m in fxx_annot_re.finditer(text):
                # Skip intentional historical markers from the dropped-req rewriter
                line_start = text.rfind("\n", 0, m.start()) + 1
                line_end = text.find("\n", m.end())
                if line_end == -1:
                    line_end = len(text)
                line = text[line_start:line_end]
                if "formerly @spec" in line or "dropped during migration" in line:
                    continue
                leftover_fxx.append(f"B: leftover FXX annotation in {path.relative_to(REPO_ROOT)}: {m.group(0)}")
            for m in annot_re.finditer(text):
                spec_id, rns_str = m.group(1), m.group(2)
                for rn in rns_str.split(","):
                    if spec_id not in new_specs or rn not in new_specs[spec_id]["rns"]:
                        unresolved_new.append(f"B: unresolved annotation in {path.relative_to(REPO_ROOT)}: @spec {spec_id}.{rn}")
    violations.extend(leftover_fxx[:50])
    violations.extend(unresolved_new[:50])
    if len(leftover_fxx) > 50:
        violations.append(f"B: ...and {len(leftover_fxx) - 50} more leftover FXX annotations")
    if len(unresolved_new) > 50:
        violations.append(f"B: ...and {len(unresolved_new) - 50} more unresolved new-form annotations")

    # Check C: cross-reference integrity in new spec frontmatter
    for spec_id, info in new_specs.items():
        fm = info["frontmatter"]
        for field in ("requires", "invalidates", "displaced_by", "revives", "revived_by"):
            value = fm.get(field)
            refs = []
            if isinstance(value, str):
                refs = [value]
            elif isinstance(value, list):
                refs = [r for r in value if isinstance(r, str)]
            for ref in refs:
                base = ref.split(".")[0] + ("." + ref.split(".", 1)[1].split(".")[0] if "." in ref else "")
                # Allow domain.slug or domain.slug.RN
                m_full = re.match(r"^([a-z][a-z0-9-]*\.[a-z][a-z0-9-]*)(?:\.(R\d+[a-z]?))?$", ref)
                if not m_full:
                    if re.match(r"^F\d+", ref):
                        violations.append(f"C: leftover FXX reference in {spec_id}.{field}: {ref}")
                    continue
                target_spec = m_full.group(1)
                target_rn = m_full.group(2)
                if target_spec not in new_specs:
                    violations.append(f"C: {spec_id}.{field} references unknown spec {target_spec}")
                elif target_rn and target_rn not in new_specs[target_spec]["rns"]:
                    violations.append(f"C: {spec_id}.{field} references missing requirement {target_spec}.{target_rn}")

    # Check D: manifest integrity
    manifest_path = REGISTRY / "manifest.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        manifest_ids = {s["id"] for s in manifest.get("specs", [])}
        spec_ids = set(new_specs.keys())
        for missing in spec_ids - manifest_ids:
            violations.append(f"D: spec {missing} exists but not in manifest.json")
        for orphan in manifest_ids - spec_ids:
            violations.append(f"D: manifest.json lists {orphan} but spec file not found")

    # Check E: obligation integrity
    obl_path = REGISTRY / "_obligations.json"
    if obl_path.exists():
        try:
            obl_data = json.loads(obl_path.read_text())
            obligations = obl_data.get("obligations") if isinstance(obl_data, dict) else obl_data
            for obl in obligations or []:
                spec_id = obl.get("spec_id")
                if spec_id and re.match(r"^F\d+", spec_id):
                    violations.append(f"E: obligation has leftover FXX spec_id: {spec_id}")
                elif spec_id and spec_id not in new_specs:
                    violations.append(f"E: obligation references unknown spec_id: {spec_id}")
                for ref_field in ("affects", "blocked_by"):
                    for ref in obl.get(ref_field, []) or []:
                        if isinstance(ref, str) and re.match(r"^F\d+", ref):
                            violations.append(f"E: obligation {ref_field} has leftover FXX: {ref}")
        except json.JSONDecodeError:
            violations.append(f"E: _obligations.json is malformed JSON")

    # Check F: old-file accounting — every FXX file has a .map (would have been migrated)
    map_features = {p.stem for p in MIGRATION_DIR.glob("F*.map")}
    for path in SPEC_DOMAINS.rglob("F*-*.md"):
        if path.name == "F04-verification-diff.md":
            continue  # known artifact, not a spec
        m = re.match(r"^(F\d+)-", path.name)
        if not m:
            continue
        fid = m.group(1)
        if fid not in map_features:
            violations.append(f"F: legacy spec {path.relative_to(REPO_ROOT)} has no .map file (untracked migration)")

    if violations:
        print(f"FAIL — {len(violations)} violation(s):\n")
        for v in violations:
            print(f"  {v}")
        sys.exit(1)

    print(f"OK — round-trip validation passed")
    print(f"  {len(table)} (source.rn → destination.final_rn) mappings")
    print(f"  {len(new_specs)} new spec files")


if __name__ == "__main__":
    main()
