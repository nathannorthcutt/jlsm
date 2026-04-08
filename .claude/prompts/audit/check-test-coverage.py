#!/usr/bin/env python3
"""
Pre-prove test coverage check for the audit pipeline.

Usage:
  python3 check-test-coverage.py <run-dir> <project-root>

Reads:
  <run-dir>/finding-list.txt       — pipe-delimited finding list
  <project-root>/**/*AdversarialTest.java — adversarial tests with intent comments

Writes:
  <run-dir>/coverage-check.md      — coverage mapping for review
  <run-dir>/prove-fix-<id>.md      — stub files for COVERED findings
    (the prove-fix orchestrator skips findings with existing output files)

Adversarial tests written by prior audit runs have structured intent comments:
  // Finding: F-R1.cb.1.1
  // Bug: register() does not call evictIfNeeded — limits not enforced
  // Correct behavior: ...
  // Fix location: HandleTracker.register()

This script matches suspect findings against these existing tests by
construct name + keyword overlap. A covered finding already has a test
proving the bug was fixed — no need to re-prove it.

NEVER overwrites existing prove-fix files.
"""

import sys
import os
import re
import glob
from collections import defaultdict


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
    """Extract construct name from finding-list construct field."""
    stripped = re.sub(r'\s*\(.*', '', raw).strip()
    if ' / ' in stripped:
        return [s.strip() for s in stripped.split(' / ')]
    return [stripped]


def finding_short_id(finding_id):
    """Convert finding ID to filename suffix."""
    return finding_id.replace('.', '-')


def scan_adversarial_tests(project_root):
    """
    Scan all *AdversarialTest.java files for structured intent comments.

    Returns index: construct_name_lower → list of {
        'finding_id', 'bug', 'fix_location', 'test_method', 'test_file', 'tokens'
    }
    """
    index = defaultdict(list)

    patterns = [
        os.path.join(project_root, '**', '*AdversarialTest.java'),
    ]

    test_files = set()
    for pattern in patterns:
        test_files.update(glob.glob(pattern, recursive=True))

    # Exclude worktree copies
    test_files = [f for f in test_files if '/worktrees/' not in f and '/.claude/' not in f]

    for test_file in sorted(test_files):
        try:
            with open(test_file) as f:
                content = f.read()
        except OSError:
            continue

        # Find all intent comment blocks followed by @Test + method name
        # Pattern: lines of // comments, then @Test, then void methodName
        block_pattern = re.compile(
            r'((?:\s*//[^\n]*\n)+)'    # comment block
            r'\s*@Test[^\n]*\n'         # @Test annotation
            r'(?:\s*@\w+[^\n]*\n)*'    # optional other annotations
            r'\s*(?:void\s+)?(\w+)',    # method name
            re.MULTILINE
        )

        for match in block_pattern.finditer(content):
            comment_block = match.group(1)
            method_name = match.group(2)

            # Extract structured fields
            finding_id = ''
            bug = ''
            fix_location = ''
            correct_behavior = ''

            for line in comment_block.split('\n'):
                line = line.strip().lstrip('/')
                line = line.strip()
                if line.startswith('Finding:'):
                    finding_id = line[len('Finding:'):].strip()
                elif line.startswith('Bug:'):
                    bug = line[len('Bug:'):].strip()
                elif line.startswith('Fix location:'):
                    fix_location = line[len('Fix location:'):].strip()
                elif line.startswith('Correct behavior:'):
                    correct_behavior = line[len('Correct behavior:'):].strip()

            if not bug:
                continue

            # Extract construct from fix location or method name
            constructs = set()
            if fix_location:
                # "HandleTracker.register() — ..." → "HandleTracker"
                loc_match = re.match(r'(\w+(?:\.\w+)?)', fix_location)
                if loc_match:
                    constructs.add(loc_match.group(1).split('.')[0].lower())
            # Also extract from method name: test_HandleTracker_register_...
            method_parts = method_name.split('_')
            if len(method_parts) >= 2:
                constructs.add(method_parts[1].lower())

            bug_tokens = tokenize(bug)
            if correct_behavior:
                bug_tokens |= tokenize(correct_behavior)

            rel_path = os.path.relpath(test_file, project_root)

            entry = {
                'finding_id': finding_id,
                'bug': bug,
                'fix_location': fix_location,
                'test_method': method_name,
                'test_file': rel_path,
                'tokens': bug_tokens,
            }

            for c in constructs:
                index[c].append(entry)

    return index


def check_coverage(findings, test_index):
    """
    Match findings against test index.

    Returns list of (finding, matching_test) tuples for covered findings,
    and list of uncovered findings.
    """
    covered = []
    uncovered = []

    for f in findings:
        best_match = None
        best_overlap = 0

        for construct_name in f['constructs']:
            key = construct_name.split('.')[0].lower()
            if key not in test_index:
                continue

            for test in test_index[key]:
                overlap = len(f['tokens'] & test['tokens'])
                if overlap >= 2 and overlap > best_overlap:
                    best_match = test
                    best_overlap = overlap

        if best_match:
            covered.append((f, best_match))
        else:
            uncovered.append(f)

    return covered, uncovered


def write_stub(run_dir, finding, test):
    """Write a prove-fix stub for a covered finding. Never overwrites."""
    short_id = finding_short_id(finding['id'])
    path = os.path.join(run_dir, f'prove-fix-{short_id}.md')
    if os.path.exists(path):
        return False
    tmp = path + '.tmp'
    try:
        with open(tmp, 'w') as f:
            f.write(f"# Prove-Fix — {finding['id']}\n\n")
            f.write(f"## Result: COVERED_BY_EXISTING_TEST\n\n")
            f.write(f"An existing adversarial test already covers this finding.\n\n")
            f.write(f"### Existing test\n")
            f.write(f"- **Test method:** {test['test_method']}\n")
            f.write(f"- **Test file:** {test['test_file']}\n")
            f.write(f"- **Bug description:** {test['bug']}\n")
            if test['finding_id']:
                f.write(f"- **Original finding:** {test['finding_id']}\n")
            f.write(f"\n### This finding\n")
            f.write(f"- **ID:** {finding['id']}\n")
            f.write(f"- **Title:** {finding['title']}\n")
            f.write(f"- **Construct:** {finding['construct_raw']}\n")
            f.write(f"- **Lens:** {finding['lens']}\n")
        os.rename(tmp, path)
        return True
    except OSError as e:
        print(f"Error writing {path}: {e}", file=sys.stderr)
        return False


def write_report(run_dir, covered, uncovered, total):
    """Write coverage-check.md."""
    path = os.path.join(run_dir, 'coverage-check.md')
    tmp = path + '.tmp'
    try:
        with open(tmp, 'w') as f:
            f.write("# Pre-Prove Test Coverage Check\n\n")
            f.write(f"Total findings: {total}\n")
            f.write(f"Covered by existing tests: {len(covered)}\n")
            f.write(f"Uncovered (need prove-fix): {len(uncovered)}\n\n")
            f.write("---\n\n")

            if covered:
                f.write("## Covered Findings\n\n")
                for finding, test in covered:
                    f.write(f"### {finding['id']}: {finding['title'][:80]}\n")
                    f.write(f"- **Matched test:** `{test['test_method']}` in {test['test_file']}\n")
                    f.write(f"- **Test bug:** {test['bug']}\n")
                    if test['finding_id']:
                        f.write(f"- **Original finding:** {test['finding_id']}\n")
                    f.write("\n")

            if not covered:
                f.write("No findings covered by existing tests.\n")

        os.rename(tmp, path)
    except OSError as e:
        print(f"Error writing {path}: {e}", file=sys.stderr)


def main():
    if len(sys.argv) != 3:
        print("Usage: python3 check-test-coverage.py <run-dir> <project-root>",
              file=sys.stderr)
        sys.exit(1)

    run_dir = sys.argv[1]
    project_root = sys.argv[2]

    if not os.path.isdir(run_dir):
        print(f"Error: {run_dir} is not a directory", file=sys.stderr)
        sys.exit(1)
    if not os.path.isdir(project_root):
        print(f"Error: {project_root} is not a directory", file=sys.stderr)
        sys.exit(1)

    # Parse findings
    findings = []
    finding_path = os.path.join(run_dir, 'finding-list.txt')
    try:
        with open(finding_path) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                parts = line.split('|')
                if len(parts) < 6:
                    continue
                fid, title, construct_raw, lens, cluster, suspect_file = parts[:6]
                findings.append({
                    'id': fid.strip(),
                    'title': title.strip(),
                    'construct_raw': construct_raw.strip(),
                    'constructs': normalize_construct(construct_raw),
                    'lens': lens.strip(),
                    'tokens': tokenize(title),
                })
    except OSError:
        print("Coverage — no finding-list.txt found")
        return

    if not findings:
        print("Coverage — 0 findings, nothing to check")
        write_report(run_dir, [], [], 0)
        return

    # Scan tests
    test_index = scan_adversarial_tests(project_root)
    total_tests = sum(len(v) for v in test_index.values())

    # Match
    covered, uncovered = check_coverage(findings, test_index)

    # Write stubs for covered findings
    stubs_written = 0
    stubs_skipped = 0
    for finding, test in covered:
        if write_stub(run_dir, finding, test):
            stubs_written += 1
        else:
            stubs_skipped += 1

    write_report(run_dir, covered, uncovered, len(findings))

    print(f"Coverage — {len(covered)} covered, {len(uncovered)} uncovered "
          f"of {len(findings)} findings ({total_tests} test intents scanned)")
    if stubs_written:
        print(f"  {stubs_written} prove-fix stubs written, {stubs_skipped} skipped (existing)")


if __name__ == '__main__':
    main()
