#!/usr/bin/env bash
# run-audit.sh — Run all spec audit scripts and produce a combined dashboard.
# Output: tools/spec-audit/output/ directory with individual TSVs + summary.md
#
# Usage: bash tools/spec-audit/run-audit.sh

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

SCRIPT_DIR="tools/spec-audit"
OUT_DIR="$SCRIPT_DIR/output"
mkdir -p "$OUT_DIR"

echo "=== Spec Audit Dashboard ==="
echo "Running from: $(pwd)"
echo "Output: $OUT_DIR/"
echo ""

# Run scripts in parallel
echo "[1/4] req-inventory..."
bash "$SCRIPT_DIR/req-inventory.sh" > "$OUT_DIR/req-inventory.tsv" 2>"$OUT_DIR/req-inventory.err" &
pid1=$!

echo "[2/4] adr-linkage..."
bash "$SCRIPT_DIR/adr-linkage.sh" > "$OUT_DIR/adr-linkage.tsv" 2>"$OUT_DIR/adr-linkage.err" &
pid2=$!

echo "[3/4] test-coverage..."
bash "$SCRIPT_DIR/test-coverage.sh" > "$OUT_DIR/test-coverage.tsv" 2>"$OUT_DIR/test-coverage.err" &
pid3=$!

echo "[4/4] ref-map..."
bash "$SCRIPT_DIR/ref-map.sh" > "$OUT_DIR/ref-map.tsv" 2>"$OUT_DIR/ref-map.err" &
pid4=$!

# Wait for all
wait $pid1 && echo "  req-inventory: done" || echo "  req-inventory: FAILED (see $OUT_DIR/req-inventory.err)"
wait $pid2 && echo "  adr-linkage: done" || echo "  adr-linkage: FAILED (see $OUT_DIR/adr-linkage.err)"
wait $pid3 && echo "  test-coverage: done" || echo "  test-coverage: FAILED (see $OUT_DIR/test-coverage.err)"
wait $pid4 && echo "  ref-map: done" || echo "  ref-map: FAILED (see $OUT_DIR/ref-map.err)"

echo ""
echo "=== Generating summary ==="

# Generate summary.md from the TSVs
python3 - "$OUT_DIR" <<'PYEOF'
import sys, os, csv
from collections import defaultdict

out_dir = sys.argv[1]

def read_tsv(name):
    path = os.path.join(out_dir, name)
    if not os.path.exists(path):
        return []
    with open(path) as f:
        reader = csv.DictReader(f, delimiter='\t')
        return list(reader)

inventory = read_tsv('req-inventory.tsv')
adr_linkage = read_tsv('adr-linkage.tsv')
test_coverage = read_tsv('test-coverage.tsv')
ref_map = read_tsv('ref-map.tsv')

# Build ref summary per spec
ref_summary = defaultdict(lambda: defaultdict(int))
for row in ref_map:
    sid = row.get('spec_id', '')
    rtype = row.get('ref_type', '')
    count = int(row.get('line_count', '0'))
    ref_summary[sid][rtype] += count

# Build ADR summary per spec
adr_summary = defaultdict(list)
for row in adr_linkage:
    sid = row.get('spec_id', '')
    slug = row.get('adr_slug', '')
    status = row.get('adr_status', '')
    wd = row.get('wd_id', 'none')
    adr_summary[sid].append((slug, status, wd))

# Build test summary per spec
test_summary = {}
for row in test_coverage:
    sid = row.get('spec_id', '')
    test_summary[sid] = row

# Write summary
with open(os.path.join(out_dir, 'summary.md'), 'w') as f:
    f.write('# Spec Audit Dashboard\n\n')
    f.write(f'Generated: {os.popen("date -Iseconds").read().strip()}\n\n')

    # Overview table
    f.write('## Inventory Overview\n\n')
    f.write('| Spec | State | Domains | Reqs | Audit-sourced | Original | Test Coverage | Source Refs | Test Refs | Audit Refs | ADRs | Open Obligs |\n')
    f.write('|------|-------|---------|------|---------------|----------|---------------|-------------|-----------|------------|------|-------------|\n')

    for row in inventory:
        sid = row.get('spec_id', '')
        state = row.get('state', '')
        domains = row.get('domains', '')
        total = row.get('total_reqs', '0')
        audit = row.get('audit_sourced', '0')
        orig = row.get('original', '0')
        obligs = row.get('open_obligations', '0')
        invals = row.get('invalidates', '0')

        tc = test_summary.get(sid, {})
        cov_pct = tc.get('coverage_pct', '0')
        cov_count = tc.get('reqs_with_test_signal', '0')
        test_files = tc.get('test_file_count', '0')

        refs = ref_summary.get(sid, {})
        src_refs = refs.get('source', 0)
        test_refs = refs.get('test', 0)
        audit_refs = refs.get('audit', 0)

        adr_count = len(adr_summary.get(sid, []))

        f.write(f'| {sid} | {state} | {domains} | {total} | {audit} | {orig} | {cov_count}/{total} ({cov_pct}%) | {src_refs} | {test_refs} | {audit_refs} | {adr_count} | {obligs} |\n')

    # Triage buckets
    f.write('\n## Triage Buckets\n\n')

    # High confidence: DRAFT with high test coverage + source refs
    f.write('### Likely ready for APPROVED (high test coverage + code refs)\n\n')
    for row in inventory:
        sid = row.get('spec_id', '')
        state = row.get('state', '')
        if state != 'DRAFT':
            continue
        tc = test_summary.get(sid, {})
        pct = int(tc.get('coverage_pct', '0'))
        refs = ref_summary.get(sid, {})
        src = refs.get('source', 0)
        test = refs.get('test', 0)
        if pct >= 50 or (test > 0 and src > 0):
            total = row.get('total_reqs', '0')
            cov = tc.get('reqs_with_test_signal', '0')
            f.write(f'- **{sid}** — {pct}% coverage ({cov}/{total}), {src} source refs, {test} test refs\n')

    f.write('\n### Needs investigation (DRAFT with low/no test coverage)\n\n')
    for row in inventory:
        sid = row.get('spec_id', '')
        state = row.get('state', '')
        if state != 'DRAFT':
            continue
        tc = test_summary.get(sid, {})
        pct = int(tc.get('coverage_pct', '0'))
        refs = ref_summary.get(sid, {})
        src = refs.get('source', 0)
        test = refs.get('test', 0)
        if pct < 50 and not (test > 0 and src > 0):
            total = row.get('total_reqs', '0')
            cov = tc.get('reqs_with_test_signal', '0')
            f.write(f'- **{sid}** — {pct}% coverage ({cov}/{total}), {src} source refs, {test} test refs\n')

    f.write('\n### Already APPROVED\n\n')
    for row in inventory:
        sid = row.get('spec_id', '')
        state = row.get('state', '')
        if state == 'APPROVED':
            total = row.get('total_reqs', '0')
            tc = test_summary.get(sid, {})
            pct = int(tc.get('coverage_pct', '0'))
            f.write(f'- **{sid}** — {total} reqs, {pct}% test coverage\n')

    # ADR linkage gaps
    f.write('\n## ADR Linkage\n\n')
    f.write('### Specs with ADR references\n\n')
    f.write('| Spec | ADR Slug | ADR Status | WD | WD Status |\n')
    f.write('|------|----------|------------|----|-----------|\n')
    for row in adr_linkage:
        f.write(f'| {row.get("spec_id","")} | {row.get("adr_slug","")} | {row.get("adr_status","")} | {row.get("wd_id","none")} | {row.get("wd_status","none")} |\n')

    orphan_adrs = [r for r in adr_linkage if r.get('wd_id', 'none') == 'none']
    if orphan_adrs:
        f.write(f'\n**{len(orphan_adrs)} ADRs referenced by specs but NOT tracked by any WD.**\n')

    # Cross-reference summary
    f.write('\n## Cross-References Between Specs\n\n')
    xrefs = defaultdict(list)
    for row in ref_map:
        if row.get('ref_type') == 'spec_xref':
            xrefs[row['spec_id']].append(row['file_path'])
    for sid, files in sorted(xrefs.items()):
        f.write(f'- **{sid}** referenced in: {", ".join(os.path.basename(f) for f in files[:5])}\n')

print(f"Summary written to {os.path.join(out_dir, 'summary.md')}")
PYEOF

echo ""
echo "=== Complete ==="
echo "Files:"
ls -la "$OUT_DIR/"
echo ""
echo "View dashboard: cat $OUT_DIR/summary.md"
