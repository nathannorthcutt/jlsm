#!/usr/bin/env bash
# req-inventory.sh — Extract requirement inventory from each spec file.
# Output: TSV to stdout, one row per spec.
# Columns: spec_id | state | domains | decision_refs | total_reqs | audit_sourced | original | open_obligations | invalidates | requires | file
#
# Usage: bash tools/spec-audit/req-inventory.sh
# Requires: python3 (for JSON frontmatter parsing)

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

MANIFEST=".spec/registry/manifest.json"
if [[ ! -f "$MANIFEST" ]]; then
  echo "ERROR: $MANIFEST not found" >&2
  exit 1
fi

# Header
printf "spec_id\tstate\tdomains\tdecision_refs\ttotal_reqs\taudit_sourced\toriginal\topen_obligations\tinvalidates\trequires\tamends\tamended_by\tfile\n"

# Extract feature list from manifest
python3 -c "
import json, sys
with open('$MANIFEST') as f:
    data = json.load(f)
for fid in sorted(data.get('features', {}), key=lambda x: int(x[1:])):
    info = data['features'][fid]
    print(fid + '\t' + info.get('latest_file', '') + '\t' + info.get('state', ''))
" | while IFS=$'\t' read -r spec_id spec_file manifest_state; do
  if [[ ! -f ".spec/$spec_file" ]]; then
    printf "%s\tMISSING_FILE\t\t\t0\t0\t0\t0\t0\t0\t0\t0\t%s\n" "$spec_id" "$spec_file"
    continue
  fi

  # Extract JSON frontmatter and parse
  meta=$(python3 -c "
import json, sys

lines = []
in_front = False
with open('.spec/$spec_file') as f:
    for line in f:
        line = line.rstrip()
        if line == '---':
            if in_front:
                break
            in_front = True
            continue
        if in_front:
            lines.append(line)

try:
    data = json.loads('\n'.join(lines))
except:
    data = {}

state = data.get('state', 'UNKNOWN')
domains = ','.join(data.get('domains', []))
drefs = ','.join(data.get('decision_refs', []))
dref_count = len(data.get('decision_refs', []))
obligs = len(data.get('open_obligations', []))
invals = len(data.get('invalidates', []))
reqs_list = data.get('requires', [])
req_count = len(reqs_list) if isinstance(reqs_list, list) else 0
amends = len(data.get('amends', []))
amended_by = len(data.get('amended_by', []))

print(f'{state}\t{domains}\t{drefs}\t{dref_count}\t{obligs}\t{invals}\t{req_count}\t{amends}\t{amended_by}')
" 2>/dev/null) || meta="ERROR\t\t\t0\t0\t0\t0\t0\t0"

  IFS=$'\t' read -r state domains drefs dref_count obligs invals req_deps amends amended_by <<< "$meta"

  # Count R-numbered requirements in the spec file
  total_reqs=$(grep -cE '^R[0-9]+[a-z]?\.' ".spec/$spec_file" 2>/dev/null || true)
  total_reqs=${total_reqs:-0}
  total_reqs=$(echo "$total_reqs" | tr -d '[:space:]')

  # Count audit-sourced requirements (have <!-- source: audit ... --> comment)
  audit_sourced=$(grep -cE '^R[0-9]+.*<!--\s*source:\s*audit' ".spec/$spec_file" 2>/dev/null || true)
  audit_sourced=${audit_sourced:-0}
  audit_sourced=$(echo "$audit_sourced" | tr -d '[:space:]')

  original=$(( ${total_reqs:-0} - ${audit_sourced:-0} ))

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "$spec_id" "$state" "$domains" "$drefs" \
    "$total_reqs" "$audit_sourced" "$original" \
    "$obligs" "$invals" "$req_deps" "$amends" "$amended_by" \
    "$spec_file"
done
