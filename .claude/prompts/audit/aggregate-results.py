#!/usr/bin/env python3
"""
Pre-aggregate prove-fix and suspect outputs for the report subagent.

Usage:
  python3 aggregate-results.py <run-dir>

Reads:
  <run-dir>/prove-fix-*.md       — individual finding results
  <run-dir>/suspect-*.md         — suspect cluster outputs (findings + boundaries)

Writes:
  <run-dir>/prove-fix-summary.md — all findings in one file, structured for report
  <run-dir>/boundary-summary.md  — all boundary observations in one file

This eliminates ~80 individual file reads from the report subagent,
reducing its context from ~140K to ~30K tokens and its cost by ~75%.
"""

import sys
import os
import re
import glob


def parse_prove_fix(path):
    """Extract structured fields from a prove-fix file."""
    try:
        with open(path, 'r') as f:
            content = f.read()
    except OSError:
        return None

    finding = {}

    # Finding ID from header
    m = re.search(r'^# Prove-Fix \S+ (.+)$', content, re.MULTILINE)
    if m:
        finding['id'] = m.group(1)

    # Result
    m = re.search(r'^## Result: (.+)$', content, re.MULTILINE)
    if m:
        finding['result'] = m.group(1).strip()

    # Phase 0
    m = re.search(r'### Phase 0:.*?\n- \*\*Result:\*\* (.+)', content)
    if m:
        finding['phase0'] = m.group(1).strip()

    # Phase 0 detail
    m = re.search(r'### Phase 0:.*?\n- \*\*Result:\*\*.*?\n- \*\*Detail:\*\* (.+)', content)
    if m:
        finding['phase0_detail'] = m.group(1).strip()

    # Phase 1 result
    m = re.search(r'### Phase 1:.*?\n(?:.*?\n)*?- \*\*Result:\*\* (.+)', content)
    if m:
        finding['phase1'] = m.group(1).strip()

    # Phase 2 change
    m = re.search(r'### Phase 2:.*?\n- \*\*Change:\*\* (.+)', content)
    if m:
        finding['fix_change'] = m.group(1).strip()

    # Phase 2 file
    m = re.search(r'### Phase 2:.*?\n(?:.*?\n)*?- \*\*File:\*\* (.+)', content)
    if m:
        finding['fix_file'] = m.group(1).strip()

    # Phase 2 result
    m = re.search(r'### Phase 2:.*?\n(?:.*?\n)*?- \*\*Result:\*\* (.+)', content)
    if m:
        finding['fix_result'] = m.group(1).strip()

    # Phase 2 detail
    m = re.search(r'### Phase 2:.*?\n(?:.*?\n)*?- \*\*Detail:\*\* (.+)', content)
    if m:
        finding['fix_detail'] = m.group(1).strip()

    # Test method + class from Phase 1
    m = re.search(r'- \*\*Test method:\*\* (.+)', content)
    if m:
        finding['test_method'] = m.group(1).strip()
    m = re.search(r'- \*\*Test class:\*\* (.+)', content)
    if m:
        finding['test_class'] = m.group(1).strip()

    # Impossibility proof block — only present for FIX_IMPOSSIBLE.
    # Captures the structured request the user needs to act on.
    m = re.search(
        r'### Impossibility proof.*?\n- \*\*Approaches tried:\*\* (.+)',
        content
    )
    if m:
        finding['approaches_tried'] = m.group(1).strip()
    m = re.search(
        r'### Impossibility proof.*?\n(?:.*?\n)*?- \*\*Structural reason:\*\* (.+)',
        content
    )
    if m:
        finding['structural_reason'] = m.group(1).strip()
    m = re.search(
        r'### Impossibility proof.*?\n(?:.*?\n)*?- \*\*Relaxation request:\*\* (.+)',
        content
    )
    if m:
        finding['relaxation_request'] = m.group(1).strip()

    return finding


def parse_suspect_boundaries(path):
    """Extract boundary observations from a suspect cluster file."""
    try:
        with open(path, 'r') as f:
            content = f.read()
    except OSError:
        return None, None

    # Extract cluster identity from header
    m = re.search(r'^# Suspect Results \S+ (.+)$', content, re.MULTILINE)
    cluster_id = m.group(1).strip() if m else os.path.basename(path)

    # Extract boundary section
    boundary_match = re.search(
        r'^## Boundary Observations\n(.*?)(?=^## |\Z)',
        content, re.MULTILINE | re.DOTALL
    )
    if not boundary_match:
        return cluster_id, []

    boundary_text = boundary_match.group(1).strip()
    if not boundary_text:
        return cluster_id, []

    # Parse individual boundary entries
    boundaries = []
    entries = re.split(r'^### ', boundary_text, flags=re.MULTILINE)
    for entry in entries:
        entry = entry.strip()
        if not entry:
            continue
        lines = entry.split('\n')
        header = lines[0].strip()
        fields = {}
        for line in lines[1:]:
            m = re.match(r'- \*\*(.+?):\*\* (.+)', line)
            if m:
                fields[m.group(1)] = m.group(2).strip()
        boundaries.append({'edge': header, **fields})

    return cluster_id, boundaries


def parse_suspect_summary(path):
    """Extract summary line from a suspect cluster file."""
    try:
        with open(path, 'r') as f:
            content = f.read()
    except OSError:
        return None

    m = re.search(r'^## Summary\n(.*?)(?=^## |\Z)', content, re.MULTILINE | re.DOTALL)
    if m:
        return m.group(1).strip()
    return None


def format_finding(f):
    """Format a single finding for the summary file."""
    lines = []
    fid = f.get('id', 'unknown')
    result = f.get('result', 'unknown')
    lines.append(f"### {fid}: {result}")

    if f.get('phase0'):
        lines.append(f"- **Phase 0:** {f['phase0']}")
        if f.get('phase0') == 'ALREADY_FIXED' and f.get('phase0_detail'):
            lines.append(f"  - {f['phase0_detail']}")

    if result == 'CONFIRMED_AND_FIXED':
        if f.get('test_method'):
            lines.append(f"- **Test:** {f['test_method']}")
        if f.get('test_class'):
            lines.append(f"  - Class: {f['test_class']}")
        if f.get('fix_change'):
            lines.append(f"- **Fix:** {f['fix_change']}")
        if f.get('fix_file'):
            lines.append(f"  - File: {f['fix_file']}")
        if f.get('fix_detail'):
            lines.append(f"  - Detail: {f['fix_detail']}")
    elif result == 'IMPOSSIBLE':
        if f.get('phase0') == 'ALREADY_FIXED':
            lines.append(f"- **Reason:** Already fixed by prior finding")
        elif f.get('phase0_detail'):
            lines.append(f"- **Detail:** {f['phase0_detail']}")
    elif result == 'FIX_IMPOSSIBLE':
        if f.get('test_method'):
            lines.append(f"- **Test:** {f['test_method']}")
        if f.get('test_class'):
            lines.append(f"  - Class: {f['test_class']}")
        if f.get('fix_change'):
            lines.append(f"- **Attempted fix:** {f['fix_change']}")
        if f.get('approaches_tried'):
            lines.append(f"- **Approaches tried:** {f['approaches_tried']}")
        if f.get('structural_reason'):
            lines.append(f"- **Structural reason:** {f['structural_reason']}")
        if f.get('relaxation_request'):
            lines.append(f"- **Relaxation request:** {f['relaxation_request']}")
        if f.get('fix_detail'):
            lines.append(f"- **Detail:** {f['fix_detail']}")

    return '\n'.join(lines)


def natural_sort_key(path):
    """Sort key that handles embedded numbers naturally."""
    base = os.path.basename(path)
    parts = re.split(r'(\d+)', base)
    return [int(p) if p.isdigit() else p.lower() for p in parts]


def main():
    if len(sys.argv) != 2:
        print("Usage: python3 aggregate-results.py <run-dir>", file=sys.stderr)
        sys.exit(1)

    run_dir = sys.argv[1]
    if not os.path.isdir(run_dir):
        print(f"Error: {run_dir} is not a directory", file=sys.stderr)
        sys.exit(1)

    # ── Aggregate prove-fix results ─────────────────────────────────

    pf_files = sorted(
        glob.glob(os.path.join(run_dir, 'prove-fix-*.md')),
        key=natural_sort_key
    )

    findings = []
    for path in pf_files:
        f = parse_prove_fix(path)
        if f:
            findings.append(f)

    # Tally
    tally = {}
    for f in findings:
        r = f.get('result', 'unknown')
        tally[r] = tally.get(r, 0) + 1

    # Count Phase 0 short-circuits
    phase0_fixed = sum(1 for f in findings if f.get('phase0') == 'ALREADY_FIXED')

    # Write summary
    summary_path = os.path.join(run_dir, 'prove-fix-summary.md')
    tmp_path = summary_path + '.tmp'
    try:
        with open(tmp_path, 'w') as out:
            out.write("# Prove-Fix Summary\n\n")
            out.write("## Tally\n\n")
            out.write(f"| Result | Count |\n")
            out.write(f"|--------|-------|\n")
            for result, count in sorted(tally.items()):
                out.write(f"| {result} | {count} |\n")
            out.write(f"| **Total** | **{len(findings)}** |\n")
            out.write(f"\nPhase 0 short-circuits (ALREADY_FIXED): {phase0_fixed}\n")
            out.write("\n---\n\n")

            # Group by result for easier report consumption.
            # FIX_IMPOSSIBLE is split out separately because it carries a
            # relaxation request the orchestrator needs to escalate to the
            # user — distinct from IMPOSSIBLE (Phase 1 found no bug).
            confirmed = [f for f in findings if f.get('result') == 'CONFIRMED_AND_FIXED']
            impossible = [f for f in findings if f.get('result') == 'IMPOSSIBLE']
            fix_impossible = [f for f in findings if f.get('result') == 'FIX_IMPOSSIBLE']
            other = [f for f in findings if f.get('result') not in
                     ('CONFIRMED_AND_FIXED', 'IMPOSSIBLE', 'FIX_IMPOSSIBLE')]

            if confirmed:
                out.write(f"## Confirmed and Fixed ({len(confirmed)})\n\n")
                for f in confirmed:
                    out.write(format_finding(f) + "\n\n")

            if impossible:
                out.write(f"## Impossible ({len(impossible)})\n\n")
                for f in impossible:
                    out.write(format_finding(f) + "\n\n")

            if fix_impossible:
                out.write(f"## Fix Impossible ({len(fix_impossible)})\n\n")
                out.write(
                    "These findings have a confirmed bug but the fix conflicts with\n"
                    "an existing test or contract. Each carries a relaxation request\n"
                    "that needs explicit user resolution — see the audit report's\n"
                    "*Fix Impossible — needs resolution* section.\n\n"
                )
                for f in fix_impossible:
                    out.write(format_finding(f) + "\n\n")

            if other:
                out.write(f"## Other ({len(other)})\n\n")
                for f in other:
                    out.write(format_finding(f) + "\n\n")

        os.rename(tmp_path, summary_path)
    except OSError as e:
        print(f"Error writing {summary_path}: {e}", file=sys.stderr)
        sys.exit(1)

    # ── Aggregate boundary observations ─────────────────────────────

    suspect_files = sorted(
        glob.glob(os.path.join(run_dir, 'suspect-*.md')),
        key=natural_sort_key
    )

    all_boundaries = []
    cluster_summaries = []
    for path in suspect_files:
        cluster_id, boundaries = parse_suspect_boundaries(path)
        if boundaries:
            all_boundaries.append((cluster_id, boundaries))
        summary = parse_suspect_summary(path)
        if summary:
            cluster_summaries.append((cluster_id, summary))

    boundary_path = os.path.join(run_dir, 'boundary-summary.md')
    tmp_path = boundary_path + '.tmp'
    try:
        with open(tmp_path, 'w') as out:
            out.write("# Boundary Observations Summary\n\n")

            if not all_boundaries:
                out.write("No boundary observations recorded.\n")
            else:
                total = sum(len(b) for _, b in all_boundaries)
                out.write(f"Total boundary observations: {total} across {len(all_boundaries)} clusters\n\n")

                for cluster_id, boundaries in all_boundaries:
                    out.write(f"## {cluster_id}\n\n")
                    for b in boundaries:
                        edge = b.get('edge', 'unknown')
                        out.write(f"### {edge}\n")
                        for k, v in b.items():
                            if k != 'edge':
                                out.write(f"- **{k}:** {v}\n")
                        out.write("\n")

            if cluster_summaries:
                out.write("---\n\n## Cluster Summaries\n\n")
                for cluster_id, summary in cluster_summaries:
                    out.write(f"### {cluster_id}\n{summary}\n\n")

        os.rename(tmp_path, boundary_path)
    except OSError as e:
        print(f"Error writing {boundary_path}: {e}", file=sys.stderr)
        sys.exit(1)

    # ── Report ──────────────────────────────────────────────────────

    print(f"Aggregated {len(findings)} findings → prove-fix-summary.md")
    for result, count in sorted(tally.items()):
        print(f"  {result}: {count}")
    print(f"  Phase 0 short-circuits: {phase0_fixed}")
    print(f"Aggregated {sum(len(b) for _, b in all_boundaries)} boundary observations → boundary-summary.md")


if __name__ == '__main__':
    main()
