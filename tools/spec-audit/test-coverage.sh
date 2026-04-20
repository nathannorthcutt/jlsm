#!/usr/bin/env bash
# test-coverage.sh — For each spec, find test files referencing it and check
# which R-requirements have test signals.
# Output: TSV to stdout, one row per spec.
# Columns: spec_id | total_reqs | reqs_with_test_signal | coverage_pct | test_file_count | test_files
#
# A requirement has a "test signal" if its R-number appears in a test file
# that also references the spec ID.
#
# Usage: bash tools/spec-audit/test-coverage.sh [F01 F02 ...]

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

MANIFEST=".spec/registry/manifest.json"

# Build spec list
if [[ $# -gt 0 ]]; then
  SPECS=("$@")
else
  SPECS=($(python3 -c "
import json
with open('$MANIFEST') as f:
    data = json.load(f)
for fid in sorted(data.get('features', {}), key=lambda x: int(x[1:])):
    print(fid)
"))
fi

# Index all test files once
TEST_FILES=()
while IFS= read -r f; do
  TEST_FILES+=("$f")
done < <(find modules/ -path '*/src/test/*.java' -type f 2>/dev/null)

# Also include audit artifacts as test signal sources
AUDIT_FILES=()
while IFS= read -r f; do
  AUDIT_FILES+=("$f")
done < <(find .feature/ -path '*/audit/*' -type f -name '*.md' 2>/dev/null)

# Header
printf "spec_id\ttotal_reqs\treqs_with_test_signal\tcoverage_pct\ttest_file_count\ttest_files\n"

for spec_id in "${SPECS[@]}"; do
  # Find the spec file
  spec_file=$(python3 -c "
import json
with open('$MANIFEST') as f:
    data = json.load(f)
info = data.get('features', {}).get('$spec_id', {})
print(info.get('latest_file', ''))
" 2>/dev/null)

  if [[ -z "$spec_file" || ! -f ".spec/$spec_file" ]]; then
    printf "%s\t0\t0\t0\t0\t\n" "$spec_id"
    continue
  fi

  # Extract all R-requirement IDs from the spec
  req_ids=()
  while IFS= read -r rid; do
    req_ids+=("$rid")
  done < <(grep -oE '^R[0-9]+[a-z]?' ".spec/$spec_file" 2>/dev/null | sort -u)
  total_reqs=${#req_ids[@]}

  if [[ "$total_reqs" -eq 0 ]]; then
    printf "%s\t0\t0\t0\t0\t\n" "$spec_id"
    continue
  fi

  # Find test files that reference this spec
  matching_test_files=()
  pattern="\\b${spec_id}\\b"
  for tf in "${TEST_FILES[@]}"; do
    if grep -qE "$pattern" "$tf" 2>/dev/null; then
      matching_test_files+=("$tf")
    fi
  done

  # Also check audit reports (they document which requirements were tested)
  matching_audit_files=()
  for af in "${AUDIT_FILES[@]}"; do
    if grep -qE "$pattern" "$af" 2>/dev/null; then
      matching_audit_files+=("$af")
    fi
  done

  # Combine all signal files
  all_signal_files=("${matching_test_files[@]}" "${matching_audit_files[@]}")
  test_file_count=${#matching_test_files[@]}

  if [[ ${#all_signal_files[@]} -eq 0 ]]; then
    printf "%s\t%s\t0\t0\t0\t\n" "$spec_id" "$total_reqs"
    continue
  fi

  # Concatenate all signal files
  all_content=$(cat "${all_signal_files[@]}" 2>/dev/null)

  # Check which requirements appear in the signal files
  covered=0
  for rid in "${req_ids[@]}"; do
    # Match R1, R1., R1-, R1: R1) etc. but not R10 when checking R1
    if echo "$all_content" | grep -qE "\\b${rid}\\b" 2>/dev/null; then
      covered=$((covered + 1))
    fi
  done

  if [[ "$total_reqs" -gt 0 ]]; then
    pct=$((covered * 100 / total_reqs))
  else
    pct=0
  fi

  # Truncate file list for display
  files_display=$(printf '%s\n' "${matching_test_files[@]}" | head -5 | tr '\n' ',' | sed 's/,$//')
  if [[ ${#matching_test_files[@]} -gt 5 ]]; then
    files_display="${files_display},+$((test_file_count - 5))more"
  fi

  printf "%s\t%s\t%s\t%s\t%s\t%s\n" \
    "$spec_id" "$total_reqs" "$covered" "$pct" "$test_file_count" "$files_display"
done
