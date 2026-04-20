#!/usr/bin/env bash
# ref-map.sh — Find where each spec ID is referenced across the codebase.
# Output: TSV to stdout, one row per (spec_id, ref_type, file).
# Columns: spec_id | ref_type | file_path | line_count
#
# ref_type: source (src/main/), test (src/test/), audit (.feature/*/audit/),
#           cyclelog (.feature/*/cycle-log.md), commit (git log)
#
# Usage: bash tools/spec-audit/ref-map.sh [F01 F02 ...]
# If no args, scans all specs F01-F48.

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

# Build spec ID list
if [[ $# -gt 0 ]]; then
  SPECS=("$@")
else
  SPECS=()
  for i in $(seq 1 48); do
    SPECS+=("$(printf 'F%02d' "$i")")
  done
fi

# Header
printf "spec_id\tref_type\tfile_path\tline_count\n"

for spec_id in "${SPECS[@]}"; do
  # Pattern: match spec ID as a word boundary (F01 not F012)
  # Use \bF01\b for grep, but also catch F01. F01- F01/ F01, F01) etc.
  pattern="\\b${spec_id}\\b"

  # Source files (src/main/)
  while IFS= read -r file; do
    count=$(grep -cE "$pattern" "$file" 2>/dev/null || echo 0)
    if [[ "$count" -gt 0 ]]; then
      printf "%s\tsource\t%s\t%s\n" "$spec_id" "$file" "$count"
    fi
  done < <(find modules/ -path '*/src/main/*.java' -type f 2>/dev/null)

  # Test files (src/test/)
  while IFS= read -r file; do
    count=$(grep -cE "$pattern" "$file" 2>/dev/null || echo 0)
    if [[ "$count" -gt 0 ]]; then
      printf "%s\ttest\t%s\t%s\n" "$spec_id" "$file" "$count"
    fi
  done < <(find modules/ -path '*/src/test/*.java' -type f 2>/dev/null)

  # Audit artifacts
  while IFS= read -r file; do
    count=$(grep -cE "$pattern" "$file" 2>/dev/null || echo 0)
    if [[ "$count" -gt 0 ]]; then
      printf "%s\taudit\t%s\t%s\n" "$spec_id" "$file" "$count"
    fi
  done < <(find .feature/ -path '*/audit/*' -type f 2>/dev/null)

  # Cycle logs
  while IFS= read -r file; do
    count=$(grep -cE "$pattern" "$file" 2>/dev/null || echo 0)
    if [[ "$count" -gt 0 ]]; then
      printf "%s\tcyclelog\t%s\t%s\n" "$spec_id" "$file" "$count"
    fi
  done < <(find .feature/ -name 'cycle-log.md' -type f 2>/dev/null)

  # Other spec files (cross-references)
  while IFS= read -r file; do
    count=$(grep -cE "$pattern" "$file" 2>/dev/null || echo 0)
    if [[ "$count" -gt 0 ]]; then
      printf "%s\tspec_xref\t%s\t%s\n" "$spec_id" "$file" "$count"
    fi
  done < <(find .spec/domains/ -name '*.md' -type f 2>/dev/null | grep -v "INDEX.md")

done
