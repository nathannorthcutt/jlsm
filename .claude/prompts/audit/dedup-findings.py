#!/usr/bin/env python3
"""
Cross-finding duplicate detection for the audit pipeline.

Usage:
  python3 dedup-findings.py <run-dir>

Reads:
  <run-dir>/finding-list.txt  — pipe-delimited finding list from extract-findings.sh

Writes:
  <run-dir>/dedup-report.md       — groupings and recommended dispatch order
  <run-dir>/dispatch-order.txt    — reordered finding IDs for the orchestrator

When domain-lens clustering is active, the same construct can appear in
findings from multiple lenses. These often describe the same bug from
different perspectives, producing expensive IMPOSSIBLE results in prove-fix.

STRATEGY: Reorder, don't skip. This script identifies behavioral duplicate
groups and reorders the dispatch queue so the "primary" finding in each
group runs first. After it confirms+fixes, Phase 0 catches the duplicates
in ~3 turns instead of ~35. No findings are ever skipped — correctness
is preserved while saving ~90% of duplicate prove cost.
"""

import sys
import os
import re
from collections import defaultdict


# ── Stopwords for keyword matching ──────────────────────────────────────

STOPWORDS = frozenset({
    'a', 'an', 'the', 'is', 'are', 'was', 'were', 'be', 'been', 'being',
    'have', 'has', 'had', 'do', 'does', 'did', 'will', 'would', 'could',
    'should', 'may', 'might', 'shall', 'can', 'to', 'of', 'in', 'for',
    'on', 'with', 'at', 'by', 'from', 'as', 'into', 'through', 'during',
    'before', 'after', 'above', 'below', 'between', 'out', 'off', 'over',
    'under', 'again', 'further', 'then', 'once', 'and', 'but', 'or', 'nor',
    'not', 'no', 'so', 'if', 'that', 'this', 'it', 'its', 'up', 'all',
    'each', 'every', 'both', 'any', 'few', 'more', 'most', 'other', 'some',
    'such', 'than', 'too', 'very', 'just', 'also', 'only', 'own', 'same',
    'new', 'when', 'while',
})


def tokenize(text):
    """Split text into lowercase keyword tokens, removing stopwords and short tokens."""
    tokens = re.findall(r'[a-zA-Z][a-zA-Z0-9_]*', text.lower())
    return {t for t in tokens if t not in STOPWORDS and len(t) > 2}


def normalize_construct(raw):
    """Extract construct name, stripping file references and line numbers."""
    stripped = re.sub(r'\s*\(.*', '', raw).strip()
    if ' / ' in stripped:
        return [s.strip() for s in stripped.split(' / ')]
    return [stripped]


def keyword_overlap(tokens_a, tokens_b):
    """Count substantive keyword overlap between two token sets."""
    return len(tokens_a & tokens_b)


def parse_finding_list(run_dir):
    """Parse finding-list.txt into structured findings."""
    findings = []
    path = os.path.join(run_dir, 'finding-list.txt')
    try:
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                parts = line.split('|')
                if len(parts) < 6:
                    continue
                fid, title, construct_raw, lens, cluster, suspect_file = parts[:6]
                constructs = normalize_construct(construct_raw)
                tokens = tokenize(title)
                findings.append({
                    'id': fid.strip(),
                    'title': title.strip(),
                    'construct_raw': construct_raw.strip(),
                    'constructs': constructs,
                    'lens': lens.strip(),
                    'cluster': cluster.strip(),
                    'suspect_file': suspect_file.strip(),
                    'tokens': tokens,
                    'raw_line': line,
                })
    except OSError:
        pass
    return findings


def find_behavioral_duplicates(findings):
    """
    Group findings by construct, then cluster by behavioral similarity.

    Returns list of (primary_finding, duplicate_findings) tuples.
    Only groups where 2+ findings from different lenses match on behavior.
    """
    by_construct = defaultdict(list)
    for f in findings:
        for c in f['constructs']:
            by_construct[c].append(f)

    lens_counts = defaultdict(int)
    for f in findings:
        lens_counts[f['lens']] += 1

    dedup_groups = []
    seen = set()

    for construct, construct_findings in sorted(by_construct.items()):
        lenses = set(f['lens'] for f in construct_findings)
        if len(lenses) < 2:
            continue

        # Greedy clustering by keyword overlap
        unclustered = list(construct_findings)
        while unclustered:
            seed = unclustered.pop(0)
            cluster = [seed]
            remaining = []
            for candidate in unclustered:
                if keyword_overlap(seed['tokens'], candidate['tokens']) >= 3:
                    cluster.append(candidate)
                else:
                    remaining.append(candidate)
            unclustered = remaining

            cluster_lenses = set(f['lens'] for f in cluster)
            if len(cluster) >= 2 and len(cluster_lenses) >= 2:
                # Select primary: prefer lens with most findings
                cluster.sort(key=lambda f: (
                    -lens_counts[f['lens']],
                    -int(f['cluster']),
                    f['id'],
                ))
                primary = cluster[0]
                duplicates = [f for f in cluster[1:] if f['id'] not in seen]
                if duplicates:
                    dedup_groups.append((primary, duplicates))
                    for f in duplicates:
                        seen.add(f['id'])

    return dedup_groups


def compute_dispatch_order(findings, dedup_groups):
    """
    Produce an optimized dispatch order.

    Primary findings run before their duplicates. Non-grouped findings
    maintain their original order. Duplicates are placed immediately
    after their primary.
    """
    # Build lookup: finding ID → group info
    primary_ids = set()
    duplicate_of = {}  # duplicate_id → primary_id
    group_duplicates = defaultdict(list)  # primary_id → [duplicate_ids]

    for primary, duplicates in dedup_groups:
        primary_ids.add(primary['id'])
        for d in duplicates:
            duplicate_of[d['id']] = primary['id']
            group_duplicates[primary['id']].append(d['id'])

    # Build order: walk original finding list, emit primary + duplicates together
    ordered = []
    emitted = set()

    for f in findings:
        fid = f['id']
        if fid in emitted:
            continue

        if fid in duplicate_of:
            # This is a duplicate — skip it here, it will be emitted after its primary
            continue

        # Emit this finding
        ordered.append(fid)
        emitted.add(fid)

        # If it's a primary, emit its duplicates right after
        if fid in group_duplicates:
            for dup_id in group_duplicates[fid]:
                if dup_id not in emitted:
                    ordered.append(dup_id)
                    emitted.add(dup_id)

    # Catch any remaining duplicates whose primary wasn't in the finding list
    for f in findings:
        if f['id'] not in emitted:
            ordered.append(f['id'])
            emitted.add(f['id'])

    return ordered


def write_report(run_dir, dedup_groups, total_findings, dispatch_order):
    """Write dedup-report.md with groupings and recommended order."""
    path = os.path.join(run_dir, 'dedup-report.md')
    tmp = path + '.tmp'
    total_duplicates = sum(len(dups) for _, dups in dedup_groups)

    try:
        with open(tmp, 'w') as f:
            f.write("# Cross-Finding Duplicate Detection Report\n\n")
            f.write(f"Total findings: {total_findings}\n")
            f.write(f"Behavioral duplicate groups: {len(dedup_groups)}\n")
            f.write(f"Duplicates identified: {total_duplicates}\n")
            f.write(f"Expected savings: ~{total_duplicates * 32} turns "
                    f"(~${total_duplicates * 5:.0f} at $5/finding)\n\n")
            f.write("**Strategy:** Reorder dispatch so primaries run before duplicates. "
                    "Phase 0 catches duplicates in ~3 turns after the primary's fix lands. "
                    "No findings are skipped.\n\n")
            f.write("---\n\n")

            if not dedup_groups:
                f.write("No cross-lens behavioral duplicates detected.\n")
            else:
                for i, (primary, duplicates) in enumerate(dedup_groups, 1):
                    f.write(f"## Group {i}: {primary['constructs'][0]}\n\n")
                    f.write(f"**Primary:** {primary['id']} [{primary['lens']}]\n")
                    f.write(f"  {primary['title']}\n\n")
                    f.write(f"**Duplicates ({len(duplicates)}):**\n")
                    for d in duplicates:
                        f.write(f"- {d['id']} [{d['lens']}]: {d['title']}\n")
                    shared = primary['tokens'] & duplicates[0]['tokens']
                    f.write(f"\n**Shared keywords:** {', '.join(sorted(shared))}\n\n")

        os.rename(tmp, path)
    except OSError as e:
        print(f"Error writing {path}: {e}", file=sys.stderr)


def write_dispatch_order(run_dir, dispatch_order):
    """Write dispatch-order.txt — one finding ID per line."""
    path = os.path.join(run_dir, 'dispatch-order.txt')
    tmp = path + '.tmp'
    try:
        with open(tmp, 'w') as f:
            for fid in dispatch_order:
                f.write(fid + '\n')
        os.rename(tmp, path)
    except OSError as e:
        print(f"Error writing {path}: {e}", file=sys.stderr)


def main():
    if len(sys.argv) != 2:
        print("Usage: python3 dedup-findings.py <run-dir>", file=sys.stderr)
        sys.exit(1)

    run_dir = sys.argv[1]
    if not os.path.isdir(run_dir):
        print(f"Error: {run_dir} is not a directory", file=sys.stderr)
        sys.exit(1)

    findings = parse_finding_list(run_dir)
    if not findings:
        print("Dedup — 0 findings, nothing to deduplicate")
        write_report(run_dir, [], 0, [])
        write_dispatch_order(run_dir, [])
        return

    dedup_groups = find_behavioral_duplicates(findings)
    dispatch_order = compute_dispatch_order(findings, dedup_groups)

    write_report(run_dir, dedup_groups, len(findings), dispatch_order)
    write_dispatch_order(run_dir, dispatch_order)

    # Summary
    total_duplicates = sum(len(dups) for _, dups in dedup_groups)
    print(f"Dedup — {total_duplicates} duplicates in {len(dedup_groups)} groups, "
          f"reordered {len(dispatch_order)} findings for optimal Phase 0 cascade")


if __name__ == '__main__':
    main()
