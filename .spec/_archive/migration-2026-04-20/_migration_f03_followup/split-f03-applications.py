#!/usr/bin/env python3
"""
split-f03-applications.py — F03 application-layer follow-up split.

The main migration filed F03's application-layer requirements (Index registry
validation, SSE encrypted index, Positional posting codec, Serializer integration)
into encryption.primitives-* specs as a temporary measure. This script extracts
them to their proper functional domains:

  encryption.primitives-variants R30-R35  → query.encrypted-index-compatibility (new)
  encryption.primitives-variants R36-R41  → query.encrypted-sse-index (new)
  encryption.primitives-variants R42-R45  → query.encrypted-positional-posting (new)
  encryption.primitives-dispatch  R9-R14   → serialization.encrypted-field-serialization (new)

Within each new spec the requirements renumber consecutively from R1.
The source specs lose the moved requirements (gaps left in numbering) and
have their section headers removed.

After the script:
  - 4 new spec files exist
  - encryption.primitives-variants and encryption.primitives-dispatch have
    fewer requirements but preserved survivor RNs (no renumber, no annotation
    breakage for survivors)
  - All @spec annotations in code referencing the moved reqs are rewritten

Idempotent? No — running twice leaves the source specs in a malformed state
(sections already removed). Single-shot script.
"""

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SPEC_DOMAINS = REPO_ROOT / ".spec" / "domains"
MANIFEST_PATH = REPO_ROOT / ".spec" / "registry" / "manifest.json"

# Move plan: source_spec → list of (section_header, source_first_rn, source_last_rn,
# new_spec_id, new_spec_path, domains)
MOVE_PLAN = [
    {
        "source_spec": "encryption.primitives-variants",
        "source_path": SPEC_DOMAINS / "encryption" / "primitives-variants.md",
        "section": "Index registry validation",
        "source_rns": ["R30", "R31", "R32", "R33", "R34", "R35"],
        "new_spec_id": "query.encrypted-index-compatibility",
        "new_spec_path": SPEC_DOMAINS / "query" / "encrypted-index-compatibility.md",
        "domains": ["query", "encryption"],
        "title": "Encrypted Index Compatibility",
        "requires": ["encryption.primitives-variants"],
    },
    {
        "source_spec": "encryption.primitives-variants",
        "source_path": SPEC_DOMAINS / "encryption" / "primitives-variants.md",
        "section": "SSE encrypted index",
        "source_rns": ["R36", "R37", "R38", "R39", "R40", "R41"],
        "new_spec_id": "query.encrypted-sse-index",
        "new_spec_path": SPEC_DOMAINS / "query" / "encrypted-sse-index.md",
        "domains": ["query", "encryption"],
        "title": "Encrypted SSE Index",
        "requires": ["encryption.primitives-variants", "encryption.primitives-key-holder"],
    },
    {
        "source_spec": "encryption.primitives-variants",
        "source_path": SPEC_DOMAINS / "encryption" / "primitives-variants.md",
        "section": "Positional posting codec",
        "source_rns": ["R42", "R43", "R44", "R45"],
        "new_spec_id": "query.encrypted-positional-posting",
        "new_spec_path": SPEC_DOMAINS / "query" / "encrypted-positional-posting.md",
        "domains": ["query", "encryption"],
        "title": "Encrypted Positional Posting Codec",
        "requires": ["encryption.primitives-variants"],
    },
    {
        "source_spec": "encryption.primitives-dispatch",
        "source_path": SPEC_DOMAINS / "encryption" / "primitives-dispatch.md",
        "section": "Serializer integration",
        "source_rns": ["R9", "R10", "R11", "R12", "R13", "R14"],
        "new_spec_id": "serialization.encrypted-field-serialization",
        "new_spec_path": SPEC_DOMAINS / "serialization" / "encrypted-field-serialization.md",
        "domains": ["serialization", "encryption"],
        "title": "Encrypted Field Serialization",
        "requires": ["encryption.primitives-variants", "encryption.primitives-dispatch"],
    },
]


def parse_frontmatter(text):
    m = re.match(r"^(---\s*\n)(.*?)(\n---\s*\n)(.*)$", text, re.DOTALL)
    if not m:
        return None, text
    try:
        return json.loads(m.group(2)), m.group(4)
    except json.JSONDecodeError:
        return None, text


def write_with_frontmatter(path, fm, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    rendered = "---\n" + json.dumps(fm, indent=2) + "\n---\n" + body
    path.write_text(rendered)


def extract_section(body, section_name, source_rns):
    """Return the section's full text (header + body up to next ### or ##) and
    the (source_rn → req_body) mapping for reqs in source_rns."""
    lines = body.splitlines(keepends=True)
    start = None
    end = len(lines)
    for i, line in enumerate(lines):
        if line.strip().startswith(f"### {section_name}"):
            start = i
            break
    if start is None:
        raise RuntimeError(f"section not found: {section_name}")
    for j in range(start + 1, len(lines)):
        s = lines[j].strip()
        if s.startswith("### ") or s.startswith("## "):
            end = j
            break
    section_text = "".join(lines[start:end])

    # Extract per-req bodies
    req_bodies = {}
    current_rn = None
    current_buf = []
    section_lines = section_text.splitlines(keepends=True)
    rn_re = re.compile(r"^(R\d+(?:[a-z])?)\.[ \t]+\S")
    def flush():
        if current_rn:
            req_bodies[current_rn] = "".join(current_buf).rstrip("\n")
    for line in section_lines:
        m = rn_re.match(line)
        if m:
            flush()
            current_rn = m.group(1)
            current_buf = [line]
        elif current_rn:
            current_buf.append(line)
    flush()

    missing = [rn for rn in source_rns if rn not in req_bodies]
    if missing:
        raise RuntimeError(f"section {section_name} missing reqs {missing}")

    return start, end, req_bodies


def main():
    rewrite_map = {}  # (old_spec, old_rn) → (new_spec, new_rn)

    # Phase 1: extract from sources, write new specs
    # Group by source so we can edit each source spec once after all extractions
    by_source = {}
    for plan in MOVE_PLAN:
        by_source.setdefault(plan["source_path"], []).append(plan)

    # First pass: extract requirement bodies from each source (before any edits)
    extractions = []
    for source_path, plans in by_source.items():
        text = source_path.read_text()
        fm, body = parse_frontmatter(text)
        if fm is None:
            print(f"ERROR: no frontmatter in {source_path}", file=sys.stderr)
            sys.exit(1)
        for plan in plans:
            start, end, req_bodies = extract_section(body, plan["section"], plan["source_rns"])
            extractions.append({
                "plan": plan,
                "source_fm": fm,
                "section_start": start,
                "section_end": end,
                "req_bodies": req_bodies,
            })

    # Second pass: write each new spec
    for ext in extractions:
        plan = ext["plan"]
        new_fm = {
            "id": plan["new_spec_id"],
            "version": 1,
            "status": "ACTIVE",
            "state": ext["source_fm"].get("state", "DRAFT"),
            "domains": plan["domains"],
            "requires": plan["requires"],
            "invalidates": [],
            "amends": None,
            "amended_by": None,
            "decision_refs": [],
            "kb_refs": [],
            "open_obligations": [],
            "_extracted_from": f"{plan['source_spec']} ({','.join(plan['source_rns'])})",
        }
        body_lines = [f"# {plan['new_spec_id']} — {plan['title']}\n", "## Requirements\n"]
        for new_idx, source_rn in enumerate(plan["source_rns"], start=1):
            new_rn = f"R{new_idx}"
            req_text = ext["req_bodies"][source_rn]
            # Replace the leading "Rn." with new_rn
            rewritten = re.sub(r"^R\d+[a-z]?\.", f"{new_rn}.", req_text, count=1)
            body_lines.append(rewritten + "\n")
            rewrite_map[(plan["source_spec"], source_rn)] = (plan["new_spec_id"], new_rn)
        new_body = "\n".join(body_lines)
        write_with_frontmatter(plan["new_spec_path"], new_fm, new_body)
        print(f"  wrote {plan['new_spec_path'].relative_to(REPO_ROOT)} ({len(plan['source_rns'])} reqs)")

    # Third pass: edit each source spec to remove the moved sections (in reverse line order
    # so earlier indices remain valid as we delete later sections)
    for source_path, plans in by_source.items():
        text = source_path.read_text()
        fm, body = parse_frontmatter(text)
        # Re-extract section bounds since they may have shifted... actually they
        # haven't shifted because we haven't edited yet. Use stored bounds.
        relevant = [e for e in extractions if e["plan"]["source_path"] == source_path]
        # Sort by section_start descending so deleting later sections doesn't shift earlier ones
        relevant.sort(key=lambda e: e["section_start"], reverse=True)
        body_lines = body.splitlines(keepends=True)
        for ext in relevant:
            del body_lines[ext["section_start"]:ext["section_end"]]
            print(f"  removed section '{ext['plan']['section']}' from {source_path.relative_to(REPO_ROOT)}")
        new_body = "".join(body_lines)
        write_with_frontmatter(source_path, fm, new_body)

    # Phase 2: rewrite annotations in source code
    rewrites_applied = 0
    files_touched = 0
    for source_dir in ["modules", "examples", "benchmarks"]:
        root = REPO_ROOT / source_dir
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if not path.is_file() or path.suffix not in {".java", ".kt", ".py", ".ts", ".js", ".rs", ".go"}:
                continue
            try:
                text = path.read_text()
            except UnicodeDecodeError:
                continue
            new_text = text
            for (old_spec, old_rn), (new_spec, new_rn) in rewrite_map.items():
                # Match @spec old_spec.old_rn (also in comma-list contexts, but our moves are
                # contiguous within sections so we can do simple substitution; multi-rn lists
                # spanning move boundaries are rare and would need special handling)
                pattern = rf"@spec\s+{re.escape(old_spec)}\.{old_rn}\b"
                replacement = f"@spec {new_spec}.{new_rn}"
                count_before = len(re.findall(pattern, new_text))
                new_text = re.sub(pattern, replacement, new_text)
                rewrites_applied += count_before
            if new_text != text:
                path.write_text(new_text)
                files_touched += 1

    print(f"\nRewrote {rewrites_applied} annotation(s) across {files_touched} source file(s)")
    print(f"Move plan: 22 reqs → 4 new specs (encryption/ application reqs → query/ + serialization/)")


if __name__ == "__main__":
    main()
