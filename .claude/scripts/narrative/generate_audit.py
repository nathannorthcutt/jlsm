#!/usr/bin/env python3
"""Generate HTML narrative from audit pipeline data.

Reads audit-report.md and prove-fix-*.md files from a run directory
and produces a standalone HTML visualization of the audit results.

Can optionally merge with the feature's TDD narrative to show the
full lifecycle: scoping -> planning -> testing -> implementation -> audit.

Usage:
    # Single audit run
    python generate_audit.py .feature/<slug>/audit/run-001 -o audit.html

    # Feature + audit combined
    python generate_audit.py .feature/<slug> --include-feature -o combined.html

    # All audited features
    python generate_audit.py --all -o output-dir/
"""

import argparse
import glob
import os
import re
import sys
from pathlib import Path

# Add script directory to path for imports
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from model import Node, NodeType, Story, TokenUsage


# ---------------------------------------------------------------------------
# Audit report parsing
# ---------------------------------------------------------------------------

def _parse_table(lines: list[str], start: int) -> list[dict]:
    """Parse a markdown table starting at the given line index.

    Expects a header row, a separator row (|---|...), then data rows.
    Returns list of dicts keyed by header column names.
    """
    if start >= len(lines):
        return []

    # Find header row (first line with pipes)
    header_idx = start
    while header_idx < len(lines) and "|" not in lines[header_idx]:
        header_idx += 1
    if header_idx >= len(lines):
        return []

    headers = [h.strip() for h in lines[header_idx].split("|") if h.strip()]

    # Skip separator row
    data_start = header_idx + 2
    rows = []
    for i in range(data_start, len(lines)):
        line = lines[i].strip()
        if not line or not line.startswith("|"):
            break
        cells = [c.strip() for c in line.split("|") if c.strip()]
        if len(cells) >= len(headers):
            rows.append(dict(zip(headers, cells)))
        elif cells:
            # Pad missing columns with empty strings
            padded = cells + [""] * (len(headers) - len(cells))
            rows.append(dict(zip(headers, padded)))

    return rows


def _find_section(lines: list[str], heading: str, level: int = 2,
                   start: int = 0, end: int = -1) -> int:
    """Find the line index of a markdown heading. Returns -1 if not found.

    Matches the heading text after the # prefix. Uses word-boundary matching
    to avoid substring false positives (e.g., "Health" matching "Healthy").
    Optionally scoped to a line range [start, end).
    """
    prefix = "#" * level + " "
    search_end = end if end >= 0 else len(lines)
    heading_lower = heading.lower()
    for i in range(start, search_end):
        line = lines[i]
        if line.startswith(prefix):
            # Extract the heading text after the # prefix
            heading_text = line[len(prefix):].strip().lower()
            if heading_text == heading_lower or heading_text.startswith(heading_lower):
                return i
    return -1


def _section_end(lines: list[str], start: int, level: int = 2) -> int:
    """Find where a section ends (next heading of same or higher level, or EOF)."""
    prefix_len = level
    for i in range(start + 1, len(lines)):
        stripped = lines[i].lstrip()
        if stripped.startswith("#"):
            hashes = len(stripped) - len(stripped.lstrip("#"))
            if hashes <= prefix_len:
                return i
    return len(lines)


def _extract_field(lines: list[str], start: int, end: int, field: str) -> str:
    """Extract a '- **Field:** value' or '**Field:** value' from a line range."""
    pattern = re.compile(r"[-*]*\s*\*\*" + re.escape(field) + r":\*\*\s*(.*)", re.I)
    for i in range(start, min(end, len(lines))):
        m = pattern.search(lines[i])
        if m:
            return m.group(1).strip()
    return ""


def _extract_metadata_field(lines: list[str], field: str) -> str:
    """Extract a top-level '**Field:** value' metadata line."""
    pattern = re.compile(r"\*\*" + re.escape(field) + r":\*\*\s*(.*)", re.I)
    for line in lines:
        m = pattern.search(line)
        if m:
            return m.group(1).strip()
    return ""


def parse_audit_report(report_path: str) -> dict:
    """Parse audit-report.md into structured data.

    Returns dict with keys:
        title, date, round, scope,
        pipeline_summary (list of stage dicts),
        bugs_fixed (list of finding dicts),
        removed_tests_summary (str),
        cross_domain (list of dicts),
        spec_coverage (list of dicts),
        pipeline_health (list of dicts),
        health_notes (str),
    """
    try:
        text = Path(report_path).read_text(encoding="utf-8")
    except OSError:
        return {}

    lines = text.splitlines()
    result = {
        "title": "",
        "date": "",
        "round": "",
        "scope": "",
        "pipeline_summary": [],
        "bugs_fixed": [],
        "removed_tests_summary": "",
        "cross_domain": [],
        "spec_coverage": [],
        "pipeline_health": [],
        "health_notes": "",
    }

    # Title — first H1
    for line in lines:
        if line.startswith("# "):
            m = re.match(r"#\s+Audit Report\s*[—–-]\s*(.*)", line)
            if m:
                result["title"] = m.group(1).strip()
            else:
                result["title"] = line[2:].strip()
            break

    # Metadata fields
    result["date"] = _extract_metadata_field(lines, "Date")
    result["round"] = _extract_metadata_field(lines, "Round")
    result["scope"] = _extract_metadata_field(lines, "Scope")

    # Pipeline Summary table
    ps_idx = _find_section(lines, "Pipeline Summary")
    if ps_idx >= 0:
        ps_end = _section_end(lines, ps_idx)
        result["pipeline_summary"] = _parse_table(lines, ps_idx + 1)

    # Bugs Fixed — each ### heading is a finding
    bf_idx = _find_section(lines, "Bugs Fixed")
    if bf_idx >= 0:
        bf_end = _section_end(lines, bf_idx)
        result["bugs_fixed"] = _parse_finding_subsections(lines, bf_idx, bf_end)

    # Cross-Domain Findings
    xd_idx = _find_section(lines, "Cross-Domain")
    if xd_idx >= 0:
        xd_end = _section_end(lines, xd_idx)
        result["cross_domain"] = _parse_cross_domain(lines, xd_idx, xd_end)

    # Removed Tests
    rt_idx = _find_section(lines, "Removed Tests")
    if rt_idx >= 0:
        rt_end = _section_end(lines, rt_idx)
        result["removed_tests_summary"] = "\n".join(
            lines[rt_idx + 1:rt_end]
        ).strip()

    # Spec Coverage
    sc_idx = _find_section(lines, "Spec Coverage")
    if sc_idx >= 0:
        sc_end = _section_end(lines, sc_idx)
        result["spec_coverage"] = _parse_spec_coverage(lines, sc_idx, sc_end)

    # Pipeline Health
    ph_idx = _find_section(lines, "Pipeline Health")
    if ph_idx >= 0:
        ph_end = _section_end(lines, ph_idx)
        result["pipeline_health"] = _parse_table(lines, ph_idx + 1)
        # Health notes — text after the table within the section
        hn_idx = _find_section(lines, "Health notes", level=3)
        if hn_idx >= 0:
            hn_end = _section_end(lines, hn_idx, level=3)
            result["health_notes"] = "\n".join(lines[hn_idx + 1:hn_end]).strip()

    return result


def _parse_finding_subsections(lines: list[str], start: int, end: int) -> list[dict]:
    """Parse ### finding subsections within a ## section."""
    findings = []
    i = start + 1
    while i < end:
        line = lines[i]
        if line.startswith("### "):
            m = re.match(r"###\s+(F-R\S+):\s*(.*)", line)
            finding_id = m.group(1) if m else ""
            title = m.group(2).strip() if m else line[4:].strip()

            # Find the end of this subsection
            sub_end = i + 1
            while sub_end < end and not lines[sub_end].startswith("### "):
                sub_end += 1

            if not finding_id:
                # Non-finding subsection (e.g., ### Summary) — skip
                i = sub_end
                continue

            finding = {
                "finding_id": finding_id,
                "title": title,
                "construct": _extract_field(lines, i, sub_end, "Construct"),
                "concern": _extract_field(lines, i, sub_end, "Concern"),
                "fix": _extract_field(lines, i, sub_end, "Fix"),
                "spec": _extract_field(lines, i, sub_end, "Spec"),
            }
            findings.append(finding)
            i = sub_end
            continue
        i += 1
    return findings


def _parse_cross_domain(lines: list[str], start: int, end: int) -> list[dict]:
    """Parse ### cross-domain finding subsections."""
    findings = []
    i = start + 1
    while i < end:
        line = lines[i]
        if line.startswith("### "):
            m = re.match(r"###\s+(XD-R\S+):\s*(.*)", line)
            finding_id = m.group(1) if m else ""
            title = m.group(2).strip() if m else line[4:].strip()

            sub_end = i + 1
            while sub_end < end and not lines[sub_end].startswith("### "):
                sub_end += 1

            finding = {
                "finding_id": finding_id,
                "title": title,
                "component_findings": _extract_field(lines, i, sub_end, "Component findings"),
                "causal_chain": _extract_field(lines, i, sub_end, "Causal chain"),
                "severity": _extract_field(lines, i, sub_end, "Severity"),
                "constructs_involved": _extract_field(lines, i, sub_end, "Constructs involved"),
            }
            findings.append(finding)
            i = sub_end
            continue
        i += 1
    return findings


def _parse_spec_coverage(lines: list[str], start: int, end: int) -> list[dict]:
    """Parse spec coverage subsections (### Fxx — Name, then table)."""
    specs = []
    i = start + 1
    while i < end:
        line = lines[i]
        if line.startswith("### "):
            # e.g. "### F02 — Block-Level SSTable Compression"
            spec_title = line[4:].strip()

            sub_end = i + 1
            while sub_end < end and not lines[sub_end].startswith("### "):
                sub_end += 1

            # Extract coverage summary line (e.g. "**Coverage: 30/43 requirements (70%)**")
            coverage_line = ""
            for j in range(i + 1, sub_end):
                if "Coverage:" in lines[j]:
                    coverage_line = lines[j].strip().strip("*")
                    break

            # Parse the requirements table
            reqs = _parse_table(lines, i + 1)

            specs.append({
                "title": spec_title,
                "coverage": coverage_line,
                "requirements": reqs,
            })
            i = sub_end
            continue
        i += 1
    return specs


# ---------------------------------------------------------------------------
# Prove-fix parsing
# ---------------------------------------------------------------------------

def parse_prove_fix(pf_path: str) -> dict:
    """Parse a single prove-fix-*.md file.

    Returns dict with keys:
        finding_id, result,
        phase0 (dict or None),
        phase1 (dict with test_method, test_class, result, detail),
        phase2 (dict with change, file, result, detail),
        impossibility (dict or None),
    """
    try:
        text = Path(pf_path).read_text(encoding="utf-8")
    except OSError:
        return {}

    lines = text.splitlines()
    result = {
        "finding_id": "",
        "result": "",
        "phase0": None,
        "phase1": None,
        "phase2": None,
        "impossibility": None,
    }

    # Title line: "# Prove-Fix — <finding ID>" or "# Prove-Fix -- <finding ID>"
    for line in lines:
        if line.startswith("# "):
            m = re.match(r"#\s+Prove-Fix\s*(?:—|–|-+)\s*(.*)", line)
            if m:
                result["finding_id"] = m.group(1).strip()
            break

    # Result line: "## Result: <RESULT>"
    for line in lines:
        if line.startswith("## Result:"):
            result["result"] = line.split(":", 1)[1].strip()
            break

    # Phase 0: Already-fixed check (may or may not exist)
    p0_idx = _find_section(lines, "Phase 0", level=3)
    if p0_idx >= 0:
        p0_end = _section_end(lines, p0_idx, level=3)
        result["phase0"] = {
            "result": _extract_field(lines, p0_idx, p0_end, "Result"),
            "detail": _extract_field(lines, p0_idx, p0_end, "Detail"),
        }

    # Phase 1: Verify
    p1_idx = _find_section(lines, "Phase 1", level=3)
    if p1_idx >= 0:
        p1_end = _section_end(lines, p1_idx, level=3)
        result["phase1"] = {
            "test_method": _extract_field(lines, p1_idx, p1_end, "Test method"),
            "test_class": _extract_field(lines, p1_idx, p1_end, "Test class"),
            "result": _extract_field(lines, p1_idx, p1_end, "Result"),
            "detail": _extract_field(lines, p1_idx, p1_end, "Detail"),
        }

    # Phase 2: Fix
    p2_idx = _find_section(lines, "Phase 2", level=3)
    if p2_idx >= 0:
        p2_end = _section_end(lines, p2_idx, level=3)
        result["phase2"] = {
            "change": _extract_field(lines, p2_idx, p2_end, "Change"),
            "file": _extract_field(lines, p2_idx, p2_end, "File"),
            "result": _extract_field(lines, p2_idx, p2_end, "Result"),
            "detail": _extract_field(lines, p2_idx, p2_end, "Detail"),
        }

    # Impossibility proof
    imp_idx = _find_section(lines, "Impossibility", level=3)
    if imp_idx >= 0:
        imp_end = _section_end(lines, imp_idx, level=3)

        # Approaches tried — may be multi-line numbered list
        approaches = _extract_field(lines, imp_idx, imp_end, "Approaches tried")
        # Collect continuation lines (numbered items)
        if not approaches:
            # Look for the field header then gather numbered lines
            for j in range(imp_idx, imp_end):
                if "**Approaches tried:**" in lines[j]:
                    approach_lines = []
                    for k in range(j + 1, imp_end):
                        stripped = lines[k].strip()
                        if stripped.startswith(("- **", "### ")):
                            break
                        if stripped:
                            approach_lines.append(stripped)
                    approaches = " ".join(approach_lines)
                    break

        result["impossibility"] = {
            "approaches": approaches,
            "structural_reason": _extract_field(lines, imp_idx, imp_end, "Structural reason"),
            "relaxation": _extract_field(lines, imp_idx, imp_end, "Relaxation request"),
        }

    return result


# ---------------------------------------------------------------------------
# Story building
# ---------------------------------------------------------------------------

def _extract_slug_from_path(path: str) -> str:
    """Extract feature slug from a path like .feature/<slug>/audit/run-NNN."""
    parts = Path(path).resolve().parts
    for i, part in enumerate(parts):
        if part == ".feature" and i + 1 < len(parts):
            return parts[i + 1]
        if part == "audit" and i >= 1:
            return parts[i - 1]
    return ""


def _find_run_dirs(feature_dir: str) -> list[str]:
    """Find all audit/run-NNN directories under a feature directory."""
    audit_dir = Path(feature_dir) / "audit"
    if not audit_dir.is_dir():
        return []
    runs = sorted(
        d for d in audit_dir.iterdir()
        if d.is_dir() and d.name.startswith("run-")
    )
    return [str(r) for r in runs]


def _find_all_audited_features(base_dir: str = ".") -> list[str]:
    """Find all .feature/<slug> directories that have audit/run-* subdirs."""
    feature_base = Path(base_dir) / ".feature"
    if not feature_base.is_dir():
        return []
    features = []
    for slug_dir in sorted(feature_base.iterdir()):
        if slug_dir.is_dir():
            audit_dir = slug_dir / "audit"
            if audit_dir.is_dir():
                runs = [d for d in audit_dir.iterdir()
                        if d.is_dir() and d.name.startswith("run-")]
                if runs:
                    features.append(str(slug_dir))
    return features


def _estimate_cost_from_sessions(run_dir: str, feature_slug: str) -> float:
    """Estimate total API cost from JSONL session subagent logs.

    Finds the audit session by looking for the session whose subagents
    reference prove-fix files for this specific feature. Uses the feature
    slug in prove-fix file paths as the discriminator.
    Returns cost in dollars, or 0.0 if no session data found.
    """
    try:
        import json as _json

        project_dir = os.getcwd()
        project_id = project_dir.lstrip("/").replace("/", "-")
        session_base = Path.home() / ".claude" / "projects" / f"-{project_id}"
        if not session_base.is_dir():
            return 0.0

        # The prove-fix files reference the feature slug in their paths
        # e.g., ".feature/block-compression/audit/run-001/prove-fix-F-R1-cb-2-1.md"
        # Find the session whose subagents write to this feature's audit dir
        pf_path_marker = f".feature/{feature_slug}/audit/"

        best_session = None
        best_count = 0

        for session_dir in session_base.iterdir():
            subagent_dir = session_dir / "subagents"
            if not subagent_dir.is_dir():
                continue
            subagent_count = len(list(subagent_dir.glob("*.jsonl")))
            if subagent_count < 5:  # audit sessions have many subagents
                continue

            # Check first few subagent files for our feature's prove-fix paths
            matches = 0
            for jsonl_file in list(subagent_dir.glob("*.jsonl"))[:3]:
                try:
                    with open(jsonl_file) as f:
                        chunk = f.read(5000)
                    if pf_path_marker in chunk:
                        matches += 1
                except OSError:
                    continue

            if matches > 0 and subagent_count > best_count:
                best_session = subagent_dir
                best_count = subagent_count

        if not best_session:
            return 0.0

        # Sum costs across all subagents in the matched session
        total_cost = 0.0
        for jsonl_file in best_session.glob("*.jsonl"):
            try:
                with open(jsonl_file) as f:
                    for line in f:
                        if '"usage"' not in line:
                            continue
                        try:
                            obj = _json.loads(line)
                            usage = obj.get("message", {}).get("usage", {})
                            if usage:
                                total_cost += usage.get("input_tokens", 0) * 15 / 1e6
                                total_cost += usage.get("cache_creation_input_tokens", 0) * 18.75 / 1e6
                                total_cost += usage.get("cache_read_input_tokens", 0) * 1.5 / 1e6
                                total_cost += usage.get("output_tokens", 0) * 75 / 1e6
                        except (_json.JSONDecodeError, KeyError):
                            pass
            except OSError:
                continue
        return total_cost
    except Exception:
        return 0.0


def build_audit_story(run_dir: str, feature_slug: str = "") -> Story:
    """Build a Story AST from audit run data.

    Reads audit-report.md and all prove-fix-*.md files from the run directory.
    Creates a Story with story_type "audit" containing one PHASE node
    (stage="audit") with an AUDIT_CYCLE child that holds AUDIT_FINDING children.
    """
    run_path = Path(run_dir)
    if not feature_slug:
        feature_slug = _extract_slug_from_path(run_dir)

    # Parse audit report
    report_path = run_path / "audit-report.md"
    report = parse_audit_report(str(report_path))
    if not report:
        print(f"narrative-audit: no audit-report.md in {run_dir}", file=sys.stderr)
        return Story(story_type="audit")

    # Parse all prove-fix files
    pf_files = sorted(run_path.glob("prove-fix-*.md"))
    prove_fixes = {}
    for pf in pf_files:
        pf_data = parse_prove_fix(str(pf))
        if pf_data and pf_data.get("finding_id"):
            prove_fixes[pf_data["finding_id"]] = pf_data

    # Lens abbreviation map (finding ID contains abbreviated lens name)
    lens_map = {
        "dt": "data_transformation",
        "ss": "shared_state",
        "ct": "contracts",
        "cb": "contract_boundaries",
        "lc": "lifecycle",
        "rl": "resource_lifecycle",
        "rs": "resource",
        "cc": "concurrency",
        "conc": "concurrency",
        "er": "error_handling",
        "bd": "boundary",
    }

    def _extract_lens(finding_id: str) -> str:
        """Extract full lens name from finding ID like F-R1.cb.2.1."""
        parts = finding_id.replace("F-R", "").split(".")
        if len(parts) >= 2:
            abbrev = parts[1]
            return lens_map.get(abbrev, abbrev)
        return ""

    # Build finding nodes for confirmed/fixed bugs.
    # The report's bugs_fixed section is the authoritative list of fixed bugs,
    # even when a prove-fix has a non-standard result (e.g. FIX_IMPOSSIBLE
    # with spec conflict resolved via alternative approach).
    finding_nodes = []
    bugs_fixed_ids = set()
    all_lenses = set()
    for bug in report.get("bugs_fixed", []):
        fid = bug.get("finding_id", "")
        bugs_fixed_ids.add(fid)
        pf = prove_fixes.get(fid, {})
        lens = _extract_lens(fid)
        if lens:
            all_lenses.add(lens)
        node = Node(
            node_type=NodeType.AUDIT_FINDING,
            data={
                "finding_id": fid,
                "title": bug.get("title", ""),
                "status": "CONFIRMED_AND_FIXED",
                "construct": bug.get("construct", ""),
                "concern": bug.get("concern", ""),
                "lens": lens,
                "fix_summary": bug.get("fix", ""),
                "fix_description": bug.get("fix", ""),
                "fix": bug.get("fix", ""),
                "spec": bug.get("spec", ""),
                "result": pf.get("result", "CONFIRMED_AND_FIXED"),
                "report_section": "bugs_fixed",
                "phase0": pf.get("phase0", {}).get("result") == "ALREADY_FIXED" if isinstance(pf.get("phase0"), dict) else False,
                "phase1": pf.get("phase1"),
                "phase2": pf.get("phase2"),
            },
            interesting=True,
        )
        finding_nodes.append(node)

    # Build finding nodes for impossible/removed tests
    for fid, pf in prove_fixes.items():
        if pf.get("result") in ("IMPOSSIBLE", "FIX_IMPOSSIBLE"):
            if any(f.data.get("finding_id") == fid for f in finding_nodes):
                continue
            lens = _extract_lens(fid)
            if lens:
                all_lenses.add(lens)
            # Try to get title from removed_tests in report
            title = ""
            reason = ""
            for rt in report.get("removed_tests", []):
                if rt.get("finding_id") == fid:
                    title = rt.get("title", "")
                    reason = rt.get("reasoning", "")
                    break
            node = Node(
                node_type=NodeType.AUDIT_FINDING,
                data={
                    "finding_id": fid,
                    "title": title,
                    "status": pf["result"],
                    "result": pf["result"],
                    "lens": lens,
                    "report_section": "removed_tests",
                    "phase0": pf.get("phase0", {}).get("result") == "ALREADY_FIXED" if isinstance(pf.get("phase0"), dict) else False,
                    "phase1": pf.get("phase1"),
                    "impossibility": pf.get("impossibility"),
                    "impossibility_reason": reason or (pf.get("impossibility", {}).get("structural_reason", "") if isinstance(pf.get("impossibility"), dict) else ""),
                },
                interesting=False,
            )
            finding_nodes.append(node)

    # Sort findings by ID for stable ordering
    finding_nodes.sort(key=lambda n: n.data.get("finding_id", ""))

    # Build cross-domain findings as separate nodes
    xd_nodes = []
    for xd in report.get("cross_domain", []):
        xd_nodes.append(Node(
            node_type=NodeType.AUDIT_FINDING,
            data={
                "finding_id": xd.get("finding_id", ""),
                "title": xd.get("title", ""),
                "component_findings": xd.get("component_findings", ""),
                "causal_chain": xd.get("causal_chain", ""),
                "severity": xd.get("severity", ""),
                "constructs_involved": xd.get("constructs_involved", ""),
                "cross_domain": True,
            },
            interesting=True,
        ))

    # Build the AUDIT_CYCLE node
    # Count by report section membership, not prove-fix result, because
    # the report is the authoritative source (some prove-fix results like
    # FIX_IMPOSSIBLE can still appear in bugs_fixed when resolved differently)
    confirmed_count = sum(
        1 for n in finding_nodes
        if n.data.get("report_section") == "bugs_fixed"
    )
    impossible_count = sum(
        1 for n in finding_nodes
        if n.data.get("report_section") != "bugs_fixed"
    )

    # Extract constructs/clusters/lenses from scope line
    scope_text = report.get("scope", "")
    constructs_count = 0
    clusters_count = 0
    scope_match = re.search(r"(\d+)\s*constructs?", scope_text)
    if scope_match:
        constructs_count = int(scope_match.group(1))
    cluster_match = re.search(r"(\d+)\s*clusters?", scope_text)
    if cluster_match:
        clusters_count = int(cluster_match.group(1))

    # Try to get cost from JSONL session data
    cost_estimate = _estimate_cost_from_sessions(run_dir, feature_slug)

    cycle_data = {
        "round": report.get("round", "1"),
        "scope": scope_text,
        "confirmed_count": confirmed_count,
        "impossible_count": impossible_count,
        "total_findings": confirmed_count + impossible_count,
        "cross_domain_count": len(xd_nodes),
        "lenses": sorted(all_lenses),
        "constructs": constructs_count,
        "clusters": clusters_count,
        "cost_estimate": f"{cost_estimate:.2f}" if cost_estimate else "unknown",
    }
    if confirmed_count > 0 and cost_estimate:
        cycle_data["cost_per_bug"] = f"{cost_estimate / confirmed_count:.2f}"

    # Add pipeline summary if present
    if report.get("pipeline_summary"):
        cycle_data["pipeline_summary"] = report["pipeline_summary"]

    # Add pipeline health metrics if present
    if report.get("pipeline_health"):
        cycle_data["pipeline_health"] = report["pipeline_health"]
    if report.get("health_notes"):
        cycle_data["health_notes"] = report["health_notes"]

    # Add spec coverage if present
    if report.get("spec_coverage"):
        cycle_data["spec_coverage"] = report["spec_coverage"]

    # Add removed tests summary
    if report.get("removed_tests_summary"):
        cycle_data["removed_tests_summary"] = report["removed_tests_summary"]

    audit_cycle = Node(
        node_type=NodeType.AUDIT_CYCLE,
        data=cycle_data,
        children=finding_nodes + xd_nodes,
        interesting=confirmed_count > 0,
    )

    # Build the PHASE node
    run_name = run_path.name  # e.g. "run-001"
    phase_title = f"Audit {report.get('title', feature_slug)}"
    if run_name != "run-001":
        phase_title += f" ({run_name})"

    phase = Node(
        node_type=NodeType.PHASE,
        data={
            "stage": "audit",
            "command": "/audit",
            "title": phase_title,
            "date": report.get("date", ""),
        },
        children=[audit_cycle],
    )

    # Build Story
    story = Story(
        story_type="audit",
        title=report.get("title", feature_slug),
    )
    story.phases = [phase]
    story.started = report.get("date", "")

    return story


def _find_feature_ast(feature_dir: str) -> "Story | None":
    """Search for a feature story AST in multiple locations.

    Checks: the feature dir itself, the archive dir, and attempts
    on-demand generation from JSONL sessions if no AST exists.
    """
    feature_path = Path(feature_dir)
    feature_slug = feature_path.name

    # Search paths: active feature dir, archive dir
    search_dirs = [feature_path]
    archive_path = feature_path.parent / "_archive" / feature_slug
    if archive_path.is_dir():
        search_dirs.append(archive_path)

    for search_dir in search_dirs:
        for ast_name in (".narrative-ast.json", "story-ast.json"):
            ast_path = search_dir / ast_name
            if ast_path.is_file():
                try:
                    return Story.load(str(ast_path))
                except (OSError, KeyError, ValueError) as e:
                    print(f"narrative-audit: failed to load {ast_path}: {e}",
                          file=sys.stderr)

    # No pre-built AST — try to generate one from JSONL sessions
    try:
        script_dir = str(Path(__file__).resolve().parent)
        if script_dir not in sys.path:
            sys.path.insert(0, script_dir)
        from generate import generate, _find_projects_dir

        projects_dir = _find_projects_dir()
        if projects_dir:
            # Try active feature dir first, then archive
            for target_dir in search_dirs:
                print(f"narrative-audit: generating feature AST for {feature_slug}...",
                      file=sys.stderr)
                success = generate(feature_slug, str(target_dir))
                if success:
                    # generate() now preserves .narrative-ast.json
                    ast_path = target_dir / ".narrative-ast.json"
                    if ast_path.is_file():
                        return Story.load(str(ast_path))
    except Exception as e:
        print(f"narrative-audit: on-demand feature generation failed: {e}",
              file=sys.stderr)

    return None


def merge_feature_audit(feature_dir: str) -> Story:
    """Build a combined Story from feature data + audit data.

    Searches for a feature AST (active dir, archive, or generates on-demand),
    then appends audit phases. Falls back to audit-only if no feature data.
    """
    feature_path = Path(feature_dir)
    feature_slug = feature_path.name

    # Try to find or generate feature story
    feature_story = _find_feature_ast(feature_dir)

    # Build audit stories from all runs
    run_dirs = _find_run_dirs(feature_dir)
    audit_phases = []
    for run_dir in run_dirs:
        audit_story = build_audit_story(run_dir, feature_slug)
        audit_phases.extend(audit_story.phases)

    if feature_story:
        # Append audit phases to existing feature story
        feature_story.phases.extend(audit_phases)
        # Keep story_type as "feature" — audit is a phase within it
        return feature_story

    # No feature story — build audit-only with all runs
    story = Story(
        story_type="audit",
        title=feature_slug,
    )
    story.phases = audit_phases
    if audit_phases:
        story.started = audit_phases[0].data.get("date", "")

    return story


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

def _try_render_html(story: Story) -> str:
    """Try to use render_html module if available, fall back to built-in."""
    try:
        from render_html import render_story
        return render_story(story)
    except ImportError:
        pass
    # Built-in minimal HTML rendering for audit stories
    return _render_audit_html(story)


def _render_audit_html(story: Story) -> str:
    """Render an audit Story to standalone HTML.

    Produces a self-contained HTML document with embedded CSS.
    Focuses on the audit-specific node types: AUDIT_CYCLE and AUDIT_FINDING.
    """
    parts = []
    parts.append("<!DOCTYPE html>")
    parts.append('<html lang="en">')
    parts.append("<head>")
    parts.append('<meta charset="utf-8">')
    parts.append('<meta name="viewport" content="width=device-width, initial-scale=1">')
    parts.append(f"<title>Audit: {_esc(story.title)}</title>")
    parts.append("<style>")
    parts.append(_audit_css())
    parts.append("</style>")
    parts.append("</head>")
    parts.append("<body>")
    parts.append(f'<header><h1>Audit: {_esc(story.title)}</h1>')
    if story.started:
        parts.append(f'<p class="meta">Date: {_esc(story.started)}</p>')
    parts.append("</header>")
    parts.append('<main>')

    for phase in story.phases:
        parts.append(_render_phase_html(phase))

    parts.append("</main>")
    parts.append("</body>")
    parts.append("</html>")
    return "\n".join(parts)


def _render_phase_html(phase: Node) -> str:
    """Render a single PHASE node to HTML."""
    parts = []
    title = phase.data.get("title", "Audit")
    parts.append(f'<section class="phase">')
    parts.append(f'<h2>{_esc(title)}</h2>')

    for child in phase.children:
        if child.node_type == NodeType.AUDIT_CYCLE:
            parts.append(_render_audit_cycle_html(child))
        else:
            # Generic child — render as prose
            if child.content:
                parts.append(f'<div class="prose">{_esc(child.content)}</div>')

    parts.append("</section>")
    return "\n".join(parts)


def _render_audit_cycle_html(cycle: Node) -> str:
    """Render an AUDIT_CYCLE node to HTML.

    Layout priority (front-loaded):
    1. Executive summary: cost, fix rate, critical findings, follow-ups
    2. Domain lenses and timing breakdown
    3. Cluster visualization with bug counts
    4. Detailed findings (collapsed by default)
    """
    parts = []
    data = cycle.data
    parts.append('<div class="audit-cycle">')

    # Classify findings
    confirmed = [c for c in cycle.children
                 if c.node_type == NodeType.AUDIT_FINDING
                 and not c.data.get("cross_domain")
                 and c.data.get("report_section") == "bugs_fixed"]
    impossible = [c for c in cycle.children
                  if c.node_type == NodeType.AUDIT_FINDING
                  and not c.data.get("cross_domain")
                  and c.data.get("report_section") != "bugs_fixed"]
    xd_findings = [c for c in cycle.children
                   if c.data.get("cross_domain")]

    total = data.get("total_findings", len(confirmed) + len(impossible))
    fix_rate = f"{len(confirmed) / total * 100:.0f}%" if total else "N/A"

    # ── Section 1: Executive Summary ─────────────────────────────────
    parts.append('<div class="executive-summary">')
    parts.append('<h3>Executive Summary</h3>')

    # Cost + metrics cards row
    parts.append('<div class="summary-cards">')
    parts.append(_summary_card(
        "Cost",
        f"${data.get('cost_estimate', 'unknown')}",
        "cost",
    ))
    parts.append(_summary_card(
        "Bugs Fixed",
        f"{len(confirmed)} of {total} findings ({fix_rate})",
        "findings",
    ))
    parts.append(_summary_card(
        "Scope",
        f"{data.get('constructs', '?')} constructs, "
        f"{data.get('clusters', '?')} clusters",
        "scope",
    ))
    if xd_findings:
        parts.append(_summary_card(
            "Cross-Domain",
            f'{len(xd_findings)} compositions',
            "cross-domain",
        ))
    parts.append("</div>")

    # Critical findings brief (top 5 most important fixes, one line each)
    if confirmed:
        parts.append('<div class="critical-brief">')
        parts.append(f'<h4>Key Fixes ({len(confirmed)} total)</h4>')
        parts.append('<ul class="brief-list">')
        for finding in confirmed[:8]:
            d = finding.data
            fid = d.get("finding_id", "")
            title = d.get("title", "")
            lens = d.get("lens", "")
            lens_html = f' <span class="lens-tag lens-{_esc(lens)}">{_esc(lens)}</span>' if lens else ""
            parts.append(f'<li><strong>{_esc(fid)}</strong>: {_esc(title)}{lens_html}</li>')
        if len(confirmed) > 8:
            parts.append(f'<li class="more">...and {len(confirmed) - 8} more (expand below)</li>')
        parts.append('</ul>')
        parts.append('</div>')

    # Follow-up items
    follow_ups = []
    if data.get("spec_updates_count"):
        follow_ups.append(f'{data["spec_updates_count"]} spec updates suggested')
    if data.get("kb_patterns_count"):
        follow_ups.append(f'{data["kb_patterns_count"]} KB patterns found')
    if data.get("fix_impossible_count"):
        follow_ups.append(f'{data["fix_impossible_count"]} fixes need test relaxation')
    if follow_ups:
        parts.append('<div class="follow-ups">')
        parts.append('<h4>Follow-up Items</h4>')
        parts.append('<ul class="brief-list">')
        for item in follow_ups:
            parts.append(f'<li>{_esc(item)}</li>')
        parts.append('</ul>')
        parts.append('</div>')

    parts.append('</div>')  # executive-summary

    # ── Section 2: Domain Lenses + Timing ────────────────────────────
    lenses = data.get("lenses", [])
    if lenses:
        parts.append('<div class="domain-timing">')
        parts.append('<h3>Domain Coverage</h3>')

        # Lens tags with finding counts
        parts.append('<div class="lens-tags">')
        # Count findings per lens
        lens_counts = {}
        lens_fixed = {}
        for c in cycle.children:
            if c.node_type == NodeType.AUDIT_FINDING:
                l = c.data.get("lens", "unknown")
                lens_counts[l] = lens_counts.get(l, 0) + 1
                if c.data.get("report_section") == "bugs_fixed":
                    lens_fixed[l] = lens_fixed.get(l, 0) + 1
        for lens in lenses:
            count = lens_counts.get(lens, 0)
            fixed = lens_fixed.get(lens, 0)
            parts.append(
                f'<span class="lens-tag lens-{_esc(lens)}">'
                f'{_esc(lens)} '
                f'<span class="lens-count">{fixed}/{count}</span>'
                f'</span>'
            )
        parts.append('</div>')

        # Timing breakdown from pipeline summary
        pipeline = data.get("pipeline_summary", [])
        if pipeline:
            parts.append('<div class="timing-bar">')
            for stage in pipeline:
                stage_name = stage.get("Stage", stage.get("stage", ""))
                if stage_name:
                    parts.append(
                        f'<span class="timing-stage">'
                        f'{_esc(stage_name)}'
                        f'</span>'
                    )
            parts.append('</div>')

        parts.append('</div>')

    # ── Section 3: Cluster Bug Counts ────────────────────────────────
    # Group findings by cluster
    cluster_bugs = {}
    cluster_total = {}
    for c in cycle.children:
        if c.node_type != NodeType.AUDIT_FINDING:
            continue
        fid = c.data.get("finding_id", "")
        # Extract cluster from finding ID: F-R1.lens.cluster.seq
        id_parts = fid.replace("F-R", "").split(".")
        if len(id_parts) >= 3:
            cluster_key = f"{id_parts[1]}.{id_parts[2]}"
        else:
            cluster_key = "other"
        cluster_total[cluster_key] = cluster_total.get(cluster_key, 0) + 1
        if c.data.get("report_section") == "bugs_fixed":
            cluster_bugs[cluster_key] = cluster_bugs.get(cluster_key, 0) + 1

    if cluster_total:
        parts.append('<div class="cluster-summary">')
        parts.append('<h3>Clusters</h3>')
        parts.append('<div class="cluster-grid">')
        for cluster in sorted(cluster_total.keys()):
            total_c = cluster_total[cluster]
            fixed_c = cluster_bugs.get(cluster, 0)
            pct = f"{fixed_c / total_c * 100:.0f}%" if total_c else "0%"
            bar_width = fixed_c / total_c * 100 if total_c else 0
            parts.append(
                f'<div class="cluster-card">'
                f'<div class="cluster-name">{_esc(cluster)}</div>'
                f'<div class="cluster-bar">'
                f'<div class="cluster-fill" style="width:{bar_width:.0f}%"></div>'
                f'</div>'
                f'<div class="cluster-stat">{fixed_c} fixed / {total_c} total ({pct})</div>'
                f'</div>'
            )
        parts.append('</div>')
        parts.append('</div>')

    # ── Section 4: Detailed Findings (collapsed) ─────────────────────
    if confirmed:
        parts.append(f'<details class="findings-section">')
        parts.append(f'<summary>Bugs Fixed &mdash; {len(confirmed)} findings (click to expand)</summary>')
        for finding in confirmed:
            parts.append(_render_finding_html(finding))
        parts.append("</details>")

    if xd_findings:
        parts.append(f'<details class="findings-section">')
        parts.append(f'<summary>Cross-Domain Findings &mdash; {len(xd_findings)} (click to expand)</summary>')
        for finding in xd_findings:
            parts.append(_render_xd_finding_html(finding))
        parts.append("</details>")

    if impossible:
        parts.append(f'<details class="findings-section">')
        parts.append(f'<summary>Removed Tests &mdash; {len(impossible)} impossible (click to expand)</summary>')
        for finding in impossible:
            parts.append(_render_impossible_html(finding))
        parts.append("</details>")

    # ── Section 5: Pipeline Details (collapsed) ──────────────────────
    pipeline = data.get("pipeline_summary", [])
    if pipeline:
        parts.append('<details class="pipeline-section">')
        parts.append('<summary>Pipeline Summary (click to expand)</summary>')
        parts.append(_render_table_html(pipeline))
        parts.append('</details>')

    # Spec coverage (collapsed)
    spec_cov = data.get("spec_coverage", [])
    if spec_cov:
        parts.append('<details class="spec-section">')
        parts.append('<summary>Spec Coverage (click to expand)</summary>')
        for spec in spec_cov:
            parts.append(f'<h4>{_esc(spec.get("title", ""))}</h4>')
            if spec.get("coverage"):
                parts.append(f'<p class="coverage-line">{_esc(spec["coverage"])}</p>')
            reqs = spec.get("requirements", [])
            if reqs:
                parts.append(_render_table_html(reqs))
        parts.append('</details>')

    # Pipeline health (collapsed)
    health = data.get("pipeline_health", [])
    if health:
        parts.append('<details class="health-section">')
        parts.append('<summary>Pipeline Health (click to expand)</summary>')
        parts.append(_render_table_html(health))
        if data.get("health_notes"):
            parts.append(f'<div class="health-notes">{_esc(data["health_notes"])}</div>')
        parts.append('</details>')

    parts.append("</div>")
    return "\n".join(parts)


def _render_finding_html(finding: Node) -> str:
    """Render a confirmed AUDIT_FINDING to HTML."""
    d = finding.data
    fid = d.get("finding_id", "")
    title = d.get("title", "")

    parts = []
    parts.append(f'<details class="finding confirmed" open>')
    parts.append(f'<summary>')
    parts.append(f'<span class="badge confirmed">FIXED</span> ')
    parts.append(f'<strong>{_esc(fid)}</strong>: {_esc(title)}')
    parts.append(f'</summary>')

    parts.append('<div class="finding-body">')

    if d.get("construct"):
        parts.append(f'<p><strong>Construct:</strong> <code>{_esc(d["construct"])}</code></p>')
    if d.get("concern"):
        parts.append(f'<p><strong>Concern:</strong> {_esc(d["concern"])}</p>')
    if d.get("fix_summary"):
        parts.append(f'<p><strong>Fix:</strong> {_esc(d["fix_summary"])}</p>')

    # Phase 1 detail
    p1 = d.get("phase1")
    if p1 and isinstance(p1, dict):
        parts.append('<div class="phase-detail">')
        parts.append('<h5>Phase 1: Verify</h5>')
        if p1.get("test_method"):
            parts.append(f'<p><strong>Test:</strong> <code>{_esc(p1["test_method"])}</code></p>')
        if p1.get("test_class"):
            parts.append(f'<p><strong>Class:</strong> <code>{_esc(p1["test_class"])}</code></p>')
        if p1.get("detail"):
            parts.append(f'<p class="detail">{_esc(p1["detail"])}</p>')
        parts.append('</div>')

    # Phase 2 detail
    p2 = d.get("phase2")
    if p2 and isinstance(p2, dict):
        parts.append('<div class="phase-detail">')
        parts.append('<h5>Phase 2: Fix</h5>')
        if p2.get("change"):
            parts.append(f'<p><strong>Change:</strong> {_esc(p2["change"])}</p>')
        if p2.get("file"):
            parts.append(f'<p><strong>File:</strong> <code>{_esc(p2["file"])}</code></p>')
        if p2.get("detail"):
            parts.append(f'<p class="detail">{_esc(p2["detail"])}</p>')
        parts.append('</div>')

    parts.append('</div>')
    parts.append('</details>')
    return "\n".join(parts)


def _render_xd_finding_html(finding: Node) -> str:
    """Render a cross-domain AUDIT_FINDING to HTML."""
    d = finding.data
    fid = d.get("finding_id", "")
    title = d.get("title", "")
    severity = d.get("severity", "medium")

    sev_class = "high" if "high" in severity.lower() else "medium"

    parts = []
    parts.append(f'<details class="finding cross-domain" open>')
    parts.append(f'<summary>')
    parts.append(f'<span class="badge {sev_class}">{_esc(severity.upper())}</span> ')
    parts.append(f'<strong>{_esc(fid)}</strong>: {_esc(title)}')
    parts.append(f'</summary>')

    parts.append('<div class="finding-body">')
    if d.get("component_findings"):
        parts.append(f'<p><strong>Component findings:</strong> {_esc(d["component_findings"])}</p>')
    if d.get("causal_chain"):
        parts.append(f'<p><strong>Causal chain:</strong> {_esc(d["causal_chain"])}</p>')
    if d.get("constructs_involved"):
        parts.append(f'<p><strong>Constructs:</strong> <code>{_esc(d["constructs_involved"])}</code></p>')
    parts.append('</div>')
    parts.append('</details>')
    return "\n".join(parts)


def _render_impossible_html(finding: Node) -> str:
    """Render an impossible AUDIT_FINDING to HTML."""
    d = finding.data
    fid = d.get("finding_id", "")

    parts = []
    parts.append(f'<details class="finding impossible">')
    parts.append(f'<summary>')
    parts.append(f'<span class="badge impossible">IMPOSSIBLE</span> ')
    parts.append(f'<strong>{_esc(fid)}</strong>')
    parts.append(f'</summary>')

    parts.append('<div class="finding-body">')

    p1 = d.get("phase1")
    if p1 and isinstance(p1, dict) and p1.get("detail"):
        parts.append(f'<p>{_esc(p1["detail"])}</p>')

    imp = d.get("impossibility")
    if imp and isinstance(imp, dict):
        if imp.get("structural_reason"):
            parts.append(f'<p><strong>Structural reason:</strong> {_esc(imp["structural_reason"])}</p>')
        if imp.get("approaches"):
            parts.append(f'<p><strong>Approaches tried:</strong> {_esc(imp["approaches"])}</p>')

    parts.append('</div>')
    parts.append('</details>')
    return "\n".join(parts)


def _render_table_html(rows: list[dict]) -> str:
    """Render a list of dicts as an HTML table."""
    if not rows:
        return ""
    keys = list(rows[0].keys())
    parts = ['<table>', '<thead><tr>']
    for k in keys:
        parts.append(f'<th>{_esc(k)}</th>')
    parts.append('</tr></thead>')
    parts.append('<tbody>')
    for row in rows:
        parts.append('<tr>')
        for k in keys:
            val = row.get(k, "")
            parts.append(f'<td>{_esc(str(val))}</td>')
        parts.append('</tr>')
    parts.append('</tbody></table>')
    return "\n".join(parts)


def _summary_card(title: str, value: str, css_class: str) -> str:
    """Render a summary metric card."""
    return (
        f'<div class="card {css_class}">'
        f'<div class="card-title">{_esc(title)}</div>'
        f'<div class="card-value">{_esc(value)}</div>'
        f'</div>'
    )


def _esc(text: str) -> str:
    """HTML-escape text."""
    return (
        text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def _audit_css() -> str:
    """Embedded CSS for audit narrative HTML."""
    return """
:root {
    --bg: #0d1117;
    --surface: #161b22;
    --border: #30363d;
    --text: #e6edf3;
    --text-muted: #8b949e;
    --green: #3fb950;
    --red: #f85149;
    --amber: #d29922;
    --blue: #58a6ff;
}
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
    background: var(--bg);
    color: var(--text);
    line-height: 1.6;
    max-width: 960px;
    margin: 0 auto;
    padding: 2rem 1rem;
}
header h1 {
    font-size: 1.8rem;
    margin-bottom: 0.5rem;
}
.meta { color: var(--text-muted); font-size: 0.9rem; }
h2 { margin-top: 2rem; font-size: 1.4rem; border-bottom: 1px solid var(--border); padding-bottom: 0.5rem; }
h3 { margin-top: 1.5rem; font-size: 1.15rem; color: var(--blue); }
h4 { margin-top: 1rem; font-size: 1rem; }
h5 { margin-top: 0.8rem; font-size: 0.9rem; color: var(--text-muted); }
.summary-cards {
    display: flex;
    gap: 1rem;
    flex-wrap: wrap;
    margin: 1rem 0;
}
.card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 1rem;
    flex: 1;
    min-width: 200px;
}
.card-title { font-size: 0.8rem; color: var(--text-muted); text-transform: uppercase; }
.card-value { font-size: 1.1rem; margin-top: 0.3rem; }
table {
    width: 100%;
    border-collapse: collapse;
    margin: 0.8rem 0;
    font-size: 0.9rem;
}
th, td {
    border: 1px solid var(--border);
    padding: 0.5rem 0.8rem;
    text-align: left;
}
th { background: var(--surface); color: var(--text-muted); font-weight: 600; }
details { margin: 0.5rem 0; }
summary {
    cursor: pointer;
    padding: 0.5rem;
    border-radius: 4px;
}
summary:hover { background: var(--surface); }
.finding { border-left: 3px solid var(--border); margin: 0.5rem 0; }
.finding.confirmed { border-left-color: var(--green); }
.finding.cross-domain { border-left-color: var(--amber); }
.finding.impossible { border-left-color: var(--text-muted); }
.finding-body { padding: 0.5rem 1rem; }
.finding-body p { margin: 0.3rem 0; }
.badge {
    display: inline-block;
    font-size: 0.7rem;
    font-weight: 700;
    padding: 0.15rem 0.5rem;
    border-radius: 3px;
    text-transform: uppercase;
    vertical-align: middle;
}
.badge.confirmed { background: var(--green); color: #000; }
.badge.high { background: var(--red); color: #fff; }
.badge.medium { background: var(--amber); color: #000; }
.badge.impossible { background: var(--border); color: var(--text-muted); }
.phase-detail { margin: 0.5rem 0 0.5rem 1rem; padding-left: 0.8rem; border-left: 1px solid var(--border); }
.detail { color: var(--text-muted); font-size: 0.9rem; }
.prose { white-space: pre-wrap; font-size: 0.9rem; color: var(--text-muted); margin: 0.5rem 0; }
.coverage-line { font-weight: 600; margin: 0.5rem 0; }
.health-notes { margin-top: 0.5rem; font-size: 0.9rem; color: var(--text-muted); }
.impossible-section, .removed-summary { margin-top: 1rem; }
code {
    background: var(--surface);
    padding: 0.15rem 0.4rem;
    border-radius: 3px;
    font-size: 0.85em;
}
/* Executive summary */
.executive-summary {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 1.5rem;
    margin: 1rem 0;
}
.brief-list { list-style: none; padding: 0; margin: 0.5rem 0; }
.brief-list li {
    padding: 0.3rem 0;
    border-bottom: 1px solid var(--border);
    font-size: 0.9rem;
}
.brief-list li:last-child { border-bottom: none; }
.brief-list .more { color: var(--text-muted); font-style: italic; }
.follow-ups { margin-top: 1rem; }
.follow-ups h4 { color: var(--amber); }
.critical-brief h4 { color: var(--green); }
/* Domain lens tags */
.lens-tags { display: flex; gap: 0.5rem; flex-wrap: wrap; margin: 0.8rem 0; }
.lens-tag {
    display: inline-block;
    font-size: 0.75rem;
    font-weight: 600;
    padding: 0.25rem 0.6rem;
    border-radius: 12px;
    background: var(--surface);
    border: 1px solid var(--border);
}
.lens-count {
    font-weight: 400;
    opacity: 0.7;
    margin-left: 0.3rem;
}
.lens-concurrency { border-color: #f97583; color: #f97583; }
.lens-contract_boundaries { border-color: #79c0ff; color: #79c0ff; }
.lens-data_transformation { border-color: #d2a8ff; color: #d2a8ff; }
.lens-resource_lifecycle { border-color: #56d364; color: #56d364; }
.lens-shared_state { border-color: #e3b341; color: #e3b341; }
/* Timing bar */
.timing-bar {
    display: flex;
    gap: 0.3rem;
    margin: 0.5rem 0;
    flex-wrap: wrap;
}
.timing-stage {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 0.2rem 0.6rem;
    font-size: 0.75rem;
    color: var(--text-muted);
}
/* Cluster grid */
.cluster-summary { margin: 1.5rem 0; }
.cluster-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: 0.8rem;
    margin: 0.8rem 0;
}
.cluster-card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 0.8rem;
}
.cluster-name {
    font-weight: 600;
    font-size: 0.85rem;
    margin-bottom: 0.4rem;
    font-family: 'SF Mono', 'Cascadia Code', monospace;
}
.cluster-bar {
    height: 6px;
    background: var(--border);
    border-radius: 3px;
    margin: 0.3rem 0;
    overflow: hidden;
}
.cluster-fill {
    height: 100%;
    background: var(--green);
    border-radius: 3px;
    transition: width 0.3s;
}
.cluster-stat {
    font-size: 0.75rem;
    color: var(--text-muted);
}
/* Findings sections (collapsed by default) */
.findings-section, .pipeline-section, .spec-section, .health-section {
    margin-top: 1rem;
}
.findings-section > summary, .pipeline-section > summary,
.spec-section > summary, .health-section > summary {
    font-size: 1.05rem;
    font-weight: 600;
    padding: 0.6rem;
}
"""


# ---------------------------------------------------------------------------
# Markdown rendering (fallback)
# ---------------------------------------------------------------------------

def _render_audit_markdown(story: Story) -> str:
    """Render an audit Story to markdown (for --format markdown)."""
    parts = []
    parts.append(f"# Audit: {story.title}\n")
    if story.started:
        parts.append(f"**Date:** {story.started}\n")

    for phase in story.phases:
        parts.append(f"\n## {phase.data.get('title', 'Audit')}\n")
        for child in phase.children:
            if child.node_type == NodeType.AUDIT_CYCLE:
                parts.append(_render_cycle_markdown(child))

    return "\n".join(parts)


def _render_cycle_markdown(cycle: Node) -> str:
    """Render an AUDIT_CYCLE to markdown."""
    data = cycle.data
    parts = []

    parts.append(f"**Scope:** {data.get('scope', '')}\n")
    parts.append(
        f"**Results:** {data.get('confirmed_count', 0)} fixed, "
        f"{data.get('impossible_count', 0)} impossible, "
        f"{data.get('total_findings', 0)} total\n"
    )

    confirmed = [c for c in cycle.children
                 if c.node_type == NodeType.AUDIT_FINDING
                 and not c.data.get("cross_domain")
                 and c.data.get("report_section") == "bugs_fixed"]
    if confirmed:
        parts.append(f"\n### Bugs Fixed ({len(confirmed)})\n")
        for f in confirmed:
            d = f.data
            parts.append(f"- **{d.get('finding_id', '')}**: {d.get('title', '')}")
            if d.get("construct"):
                parts.append(f"  - Construct: `{d['construct']}`")
            if d.get("fix_summary"):
                parts.append(f"  - Fix: {d['fix_summary']}")

    xd = [c for c in cycle.children if c.data.get("cross_domain")]
    if xd:
        parts.append(f"\n### Cross-Domain Findings ({len(xd)})\n")
        for f in xd:
            d = f.data
            parts.append(
                f"- **{d.get('finding_id', '')}** [{d.get('severity', '')}]: "
                f"{d.get('title', '')}"
            )

    impossible = [c for c in cycle.children
                  if c.node_type == NodeType.AUDIT_FINDING
                  and not c.data.get("cross_domain")
                  and c.data.get("report_section") != "bugs_fixed"]
    if impossible:
        parts.append(f"\n### Removed Tests ({len(impossible)} impossible)\n")
        for f in impossible:
            parts.append(f"- {f.data.get('finding_id', '')}")

    return "\n".join(parts)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _write_output(content: str, output_path: str):
    """Write content to file, creating parent directories as needed."""
    out = Path(output_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(content, encoding="utf-8")
    print(f"narrative-audit: wrote {out}", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(
        description="Generate audit narrative HTML",
        epilog=(
            "Examples:\n"
            "  %(prog)s .feature/block-compression/audit/run-001 -o audit.html\n"
            "  %(prog)s .feature/block-compression --include-feature -o combined.html\n"
            "  %(prog)s --all -o output-dir/\n"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("path", nargs="?",
                        help="Run dir, feature dir, or omit with --all")
    parser.add_argument("-o", "--output",
                        help="Output HTML file or directory")
    parser.add_argument("--include-feature", action="store_true",
                        help="Include feature TDD narrative before audit")
    parser.add_argument("--all", action="store_true",
                        help="Generate for all audited features")
    parser.add_argument("--format", choices=["html", "markdown", "both"],
                        default="html", help="Output format (default: html)")
    parser.add_argument("--save-ast", action="store_true",
                        help="Save intermediate story-ast.json alongside output")
    args = parser.parse_args()

    if not args.path and not args.all:
        parser.error("either provide a path or use --all")

    try:
        if args.all:
            # Generate for all audited features
            features = _find_all_audited_features()
            if not features:
                print("narrative-audit: no audited features found", file=sys.stderr)
                sys.exit(0)

            output_dir = args.output or "audit-narratives"
            for feature_dir in features:
                slug = Path(feature_dir).name
                if args.include_feature:
                    story = merge_feature_audit(feature_dir)
                else:
                    run_dirs = _find_run_dirs(feature_dir)
                    if not run_dirs:
                        continue
                    # Build from most recent run
                    story = build_audit_story(run_dirs[-1], slug)

                if not story.phases:
                    continue

                if args.save_ast:
                    ast_path = str(Path(output_dir) / f"{slug}-ast.json")
                    story.save(ast_path)

                if args.format in ("html", "both"):
                    html = _try_render_html(story)
                    _write_output(html, str(Path(output_dir) / f"{slug}.html"))

                if args.format in ("markdown", "both"):
                    md = _render_audit_markdown(story)
                    _write_output(md, str(Path(output_dir) / f"{slug}.md"))

        elif args.include_feature:
            # Feature + audit merged
            feature_dir = args.path
            if not Path(feature_dir).is_dir():
                print(f"narrative-audit: not a directory: {feature_dir}",
                      file=sys.stderr)
                sys.exit(1)

            story = merge_feature_audit(feature_dir)
            if not story.phases:
                print("narrative-audit: no phases found", file=sys.stderr)
                sys.exit(0)

            slug = Path(feature_dir).name
            if args.save_ast:
                ast_path = os.path.splitext(args.output)[0] + "-ast.json" if args.output else f"{slug}-ast.json"
                story.save(ast_path)

            if args.format in ("html", "both"):
                html = _try_render_html(story)
                out = args.output or f"{slug}-audit.html"
                _write_output(html, out)

            if args.format in ("markdown", "both"):
                md = _render_audit_markdown(story)
                md_out = os.path.splitext(args.output)[0] + ".md" if args.output else f"{slug}-audit.md"
                _write_output(md, md_out)

        else:
            # Single path — could be a run dir or feature dir
            path = Path(args.path)
            if not path.is_dir():
                print(f"narrative-audit: not a directory: {args.path}",
                      file=sys.stderr)
                sys.exit(1)

            # Detect whether this is a run dir or feature dir
            if (path / "audit-report.md").is_file():
                # It's a run directory
                slug = _extract_slug_from_path(str(path))
                story = build_audit_story(str(path), slug)
            elif (path / "audit").is_dir():
                # It's a feature directory — build from all runs
                slug = path.name
                run_dirs = _find_run_dirs(str(path))
                if not run_dirs:
                    print("narrative-audit: no run directories found",
                          file=sys.stderr)
                    sys.exit(0)
                # Use the most recent run
                story = build_audit_story(run_dirs[-1], slug)
            else:
                print(f"narrative-audit: no audit data found in {args.path}",
                      file=sys.stderr)
                sys.exit(1)

            if not story.phases:
                print("narrative-audit: no phases found", file=sys.stderr)
                sys.exit(0)

            slug = slug or "audit"
            if args.save_ast:
                ast_path = os.path.splitext(args.output)[0] + "-ast.json" if args.output else f"{slug}-ast.json"
                story.save(ast_path)

            if args.format in ("html", "both"):
                html = _try_render_html(story)
                out = args.output or f"{slug}-audit.html"
                _write_output(html, out)

            if args.format in ("markdown", "both"):
                md = _render_audit_markdown(story)
                md_out = os.path.splitext(args.output)[0] + ".md" if args.output else f"{slug}-audit.md"
                _write_output(md, md_out)

    except Exception as e:
        # Narrative generation is optional — never crash the caller
        print(f"narrative-audit: pipeline failed: {e}", file=sys.stderr)
        import traceback
        print("narrative-audit: traceback follows (report at "
              "https://github.com/telefrek/vallorcine/issues):", file=sys.stderr)
        traceback.print_exc(file=sys.stderr)

    # Always exit 0 — this is an optional enhancement
    sys.exit(0)


if __name__ == "__main__":
    main()
