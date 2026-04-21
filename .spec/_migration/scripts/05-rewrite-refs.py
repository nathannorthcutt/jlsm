#!/usr/bin/env python3
"""
05-rewrite-refs.py — Rewrite cross-spec FXX references in spec frontmatter.

Walks the new spec files in .spec/domains/ and rewrites the following frontmatter
fields, replacing FXX or FXX.RN tokens with their domain.slug or domain.slug.RN
equivalents from final-rn-table.json:
  - requires
  - invalidates
  - displaced_by, displacement_reason
  - revives, revived_by
  - amends, amended_by

Also updates .spec/registry/_obligations.json: spec_id field and affects[] entries.

Bare FXX references (no .RN) are mapped to the feature's primary destination.
For split features with multiple destinations, the first listed destination is
used; a warning is printed for manual review if needed.

Idempotent — running twice on already-rewritten frontmatter is a no-op.
"""

import json
import re
import sys
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
MIGRATION_DIR = REPO_ROOT / ".spec" / "_migration"
SPEC_DOMAINS = REPO_ROOT / ".spec" / "domains"
REGISTRY_OBLIGATIONS = REPO_ROOT / ".spec" / "registry" / "_obligations.json"
FINAL_RN_TABLE_PATH = MIGRATION_DIR / "final-rn-table.json"
SPLIT_RULES_PATH = MIGRATION_DIR / "split-rules.json"

REF_FIELDS = ["requires", "invalidates", "displaced_by", "revives", "revived_by", "amends", "amended_by"]


def load_table():
    if not FINAL_RN_TABLE_PATH.exists():
        print(f"ERROR: {FINAL_RN_TABLE_PATH} not found", file=sys.stderr)
        sys.exit(1)
    return json.loads(FINAL_RN_TABLE_PATH.read_text())


def load_dropped():
    if not SPLIT_RULES_PATH.exists():
        return set()
    rules = json.loads(SPLIT_RULES_PATH.read_text())
    dropped = set()
    for fid, frules in rules.items():
        if fid.startswith("$"):
            continue
        for rn in frules.get("dropped_requirements", {}):
            dropped.add(f"{fid}.{rn}")
    return dropped


def build_feature_dest_map(table):
    """Return {feature_id: [destinations]} (deduplicated, in first-seen order)."""
    by_feature = defaultdict(list)
    for key, entry in table.items():
        feature = key.split(".")[0]
        dest = entry["destination"]
        if dest not in by_feature[feature]:
            by_feature[feature].append(dest)
    return by_feature


def rewrite_token(token, table, feature_dests, dropped, warnings):
    """Rewrite a single FXX or FXX.RN token. Returns list of rewritten tokens.
    For dropped reqs, returns [] (filtered out — the requirement no longer exists).
    For bare FXX with multiple destinations, returns ALL destinations (let downstream
    consumers see the full dependency surface)."""
    m_full = re.match(r"^(F\d+)\.(R\d+[a-z]?)$", token)
    if m_full:
        feature, rn = m_full.groups()
        key = f"{feature}.{rn}"
        if key in dropped:
            warnings.append(f"dropped {token} — removed from refs (req no longer exists)")
            return []
        entry = table.get(key)
        if not entry:
            warnings.append(f"unresolved RN reference: {token}")
            return [token]
        return [f"{entry['destination']}.{entry['final_rn']}"]
    m_bare = re.match(r"^(F\d+)$", token)
    if m_bare:
        feature = m_bare.group(1)
        dests = feature_dests.get(feature, [])
        if not dests:
            warnings.append(f"unresolved bare feature reference: {token}")
            return [token]
        if len(dests) > 1:
            warnings.append(f"{token} expanded to {len(dests)} destinations: {dests}")
        return list(dests)  # emit all destinations
    return [token]


def parse_frontmatter(text):
    m = re.match(r"^(---\s*\n)(.*?)(\n---\s*\n)(.*)$", text, re.DOTALL)
    if not m:
        return None, text
    try:
        return json.loads(m.group(2)), m.group(4)
    except json.JSONDecodeError:
        return None, text


def rewrite_field_value(value, table, feature_dests, dropped, warnings):
    """Rewrite a single field value (string or list of strings)."""
    if value is None:
        return value
    if isinstance(value, str):
        rewritten = rewrite_token(value, table, feature_dests, dropped, warnings)
        if not rewritten:
            return None
        return rewritten[0] if len(rewritten) == 1 else rewritten
    if isinstance(value, list):
        out = []
        for item in value:
            if isinstance(item, str):
                out.extend(rewrite_token(item, table, feature_dests, dropped, warnings))
            else:
                out.append(item)
        # dedupe preserving order
        seen = set()
        deduped = []
        for x in out:
            key = json.dumps(x, sort_keys=True) if not isinstance(x, str) else x
            if key not in seen:
                seen.add(key)
                deduped.append(x)
        return deduped
    return value


def rewrite_spec_file(path, table, feature_dests, dropped):
    text = path.read_text()
    fm, body = parse_frontmatter(text)
    if fm is None:
        return False, []

    warnings = []
    changed = False
    for field in REF_FIELDS:
        if field not in fm:
            continue
        old = fm[field]
        new = rewrite_field_value(old, table, feature_dests, dropped, warnings)
        if new != old:
            fm[field] = new
            changed = True

    if changed:
        rendered = "---\n" + json.dumps(fm, indent=2) + "\n---\n" + body
        path.write_text(rendered)
    return changed, [(path.relative_to(REPO_ROOT), w) for w in warnings]


def rewrite_obligations(table, feature_dests, dropped):
    if not REGISTRY_OBLIGATIONS.exists():
        return False, []
    data = json.loads(REGISTRY_OBLIGATIONS.read_text())
    warnings = []
    changed = False
    obligations = data.get("obligations") if isinstance(data, dict) else data
    if obligations is None:
        return False, []

    for obl in obligations:
        if "spec_id" in obl:
            old_id = obl["spec_id"]
            new_id = rewrite_token(old_id, table, feature_dests, dropped, warnings)
            if new_id and new_id[0] != old_id:
                obl["spec_id"] = new_id[0]
                changed = True
        for affects_key in ("affects", "blocked_by"):
            if affects_key in obl and isinstance(obl[affects_key], list):
                new_list = []
                for ref in obl[affects_key]:
                    new_list.extend(rewrite_token(ref, table, feature_dests, dropped, warnings))
                if new_list != obl[affects_key]:
                    obl[affects_key] = new_list
                    changed = True

    if changed:
        REGISTRY_OBLIGATIONS.write_text(json.dumps(data, indent=2) + "\n")
    return changed, [("_obligations.json", w) for w in warnings]


def main():
    table = load_table()
    dropped = load_dropped()
    feature_dests = build_feature_dest_map(table)

    files_scanned = 0
    files_changed = 0
    all_warnings = []

    for path in SPEC_DOMAINS.rglob("*.md"):
        if path.name == "INDEX.md" or path.name == "CLAUDE.md":
            continue
        # Skip old FXX-named files; only rewrite the new domain.slug specs
        if re.match(r"^F\d+-", path.name):
            continue
        files_scanned += 1
        try:
            changed, warnings = rewrite_spec_file(path, table, feature_dests, dropped)
        except (json.JSONDecodeError, UnicodeDecodeError) as e:
            print(f"  WARN: could not process {path.relative_to(REPO_ROOT)}: {e}", file=sys.stderr)
            continue
        if changed:
            files_changed += 1
            print(f"  rewrote refs in {path.relative_to(REPO_ROOT)}")
        all_warnings.extend(warnings)

    obl_changed, obl_warnings = rewrite_obligations(table, feature_dests, dropped)
    if obl_changed:
        print(f"  rewrote refs in .spec/registry/_obligations.json")
    all_warnings.extend(obl_warnings)

    print(f"\nScanned {files_scanned} new spec files, rewrote refs in {files_changed}")
    if obl_changed:
        print(f"Updated _obligations.json")
    if all_warnings:
        print(f"\n{len(all_warnings)} warning(s):")
        for source, w in all_warnings[:30]:
            print(f"  {source}: {w}")
        if len(all_warnings) > 30:
            print(f"  ... and {len(all_warnings) - 30} more")


if __name__ == "__main__":
    main()
