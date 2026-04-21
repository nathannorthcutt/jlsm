#!/usr/bin/env bash
# spec-trace.sh — find @spec annotations for a spec across the codebase
# Usage: spec-trace.sh <spec-id> [project-root]
#   spec-id: legacy FXX (e.g., F13) OR domain.slug (e.g., schema.field-access)
# Optional env: SPEC_TRACE_DIRS="src,lib,test" — comma-separated dirs to scan (default: auto-detect)
# Optional env: SPEC_TRACE_FORMAT="summary|detail|json" — output format (default: summary)
# Output: grouped annotation report on stdout | diagnostics on stderr
#
# Scans implementation and test files for @spec annotations matching the given
# spec ID. Groups results by file, distinguishes implementation vs test
# locations, and reports per-requirement coverage.

set -euo pipefail

SPEC_ID="${1:-}"
# Backwards-compatible alias used internally by older code paths
PROJECT_ROOT="${2:-}"
FORMAT="${SPEC_TRACE_FORMAT:-summary}"

[[ -z "$SPEC_ID" ]] && {
  echo "Usage: spec-trace.sh <spec-id> [project-root]" >&2
  echo "  Examples:" >&2
  echo "    spec-trace.sh F13                      # legacy FXX format" >&2
  echo "    spec-trace.sh schema.field-access      # domain.slug format" >&2
  echo "    spec-trace.sh F13 /path/to/project" >&2
  echo "" >&2
  echo "  Env: SPEC_TRACE_DIRS='src,lib,test'  — dirs to scan" >&2
  echo "  Env: SPEC_TRACE_FORMAT='summary'      — summary|detail|json" >&2
  exit 1
}

# Validate spec ID format: legacy FXX (zero-padded 2+ digits) OR domain.slug
# (lowercase letter + hyphenated segments separated by a single dot).
if ! echo "$SPEC_ID" | grep -qE '^(F[0-9]{2,}|[a-z][a-z0-9-]*\.[a-z][a-z0-9-]*)$'; then
  echo "ERROR: Invalid spec ID '$SPEC_ID'." >&2
  echo "  Expected: legacy FXX (e.g. F01, F13) OR domain.slug (e.g. schema.field-access)" >&2
  exit 1
fi

# Escape regex metachars in the spec ID so dots in domain.slug match literally
SPEC_ID_RE=$(printf '%s' "$SPEC_ID" | sed 's/[.[\*^$()+?{|]/\\&/g')

# ── Locate project root ────────────────────────────────────────────────────────
if [[ -z "$PROJECT_ROOT" ]]; then
  dir="$PWD"
  while [[ "$dir" != "/" ]]; do
    if [[ -d "$dir/.spec" ]] || [[ -d "$dir/.git" ]]; then
      PROJECT_ROOT="$dir"
      break
    fi
    dir="$(dirname "$dir")"
  done
  [[ -z "$PROJECT_ROOT" ]] && {
    echo "ERROR: Could not find project root (no .spec/ or .git/ found)." >&2
    exit 1
  }
fi

# ── Determine directories to scan ──────────────────────────────────────────────
SCAN_DIRS=()
if [[ -n "${SPEC_TRACE_DIRS:-}" ]]; then
  IFS=',' read -ra USER_DIRS <<< "$SPEC_TRACE_DIRS"
  for d in "${USER_DIRS[@]}"; do
    d=$(echo "$d" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    [[ -d "$PROJECT_ROOT/$d" ]] && SCAN_DIRS+=("$PROJECT_ROOT/$d")
  done
else
  # Auto-detect: scan common source/test directory names. `modules` is included
  # for multi-module Java/Kotlin projects (e.g., jlsm); `examples` and
  # `benchmarks` cover sample/perf code that may also carry @spec annotations.
  for candidate in src lib app main test tests spec modules examples benchmarks; do
    [[ -d "$PROJECT_ROOT/$candidate" ]] && SCAN_DIRS+=("$PROJECT_ROOT/$candidate")
  done
fi

if [[ ${#SCAN_DIRS[@]} -eq 0 ]]; then
  echo "ERROR: No source directories found to scan in $PROJECT_ROOT" >&2
  echo "  Set SPEC_TRACE_DIRS='src,test' to specify directories." >&2
  exit 1
fi

echo "[trace] Scanning for @spec $SPEC_ID in: ${SCAN_DIRS[*]}" >&2

# ── Grep pattern ────────────────────────────────────────────────────────────────
# Match @spec annotations containing this spec ID. The spec ID is regex-escaped
# (SPEC_ID_RE) so dots in domain.slug ids match literally.
GREP_PATTERN="@spec\s+.*${SPEC_ID_RE}\."

# Collect all matching lines: file:line_number:content
MATCHES_FILE=$(mktemp)
trap 'rm -f "$MATCHES_FILE"' EXIT

grep -rnE "$GREP_PATTERN" "${SCAN_DIRS[@]}" \
  --include='*.java' --include='*.py' --include='*.js' --include='*.ts' \
  --include='*.go' --include='*.rs' --include='*.kt' --include='*.scala' \
  --include='*.c' --include='*.cpp' --include='*.h' --include='*.hpp' \
  --include='*.cs' --include='*.rb' --include='*.swift' --include='*.m' \
  --include='*.sh' \
  > "$MATCHES_FILE" 2>/dev/null || true

MATCH_COUNT=$(wc -l < "$MATCHES_FILE" | tr -d ' ')

if [[ "$MATCH_COUNT" -eq 0 ]]; then
  echo "No @spec annotations found for $SPEC_ID" >&2
  echo "## $SPEC_ID — Trace Report"
  echo ""
  echo "**No annotations found.**"
  echo ""
  echo "Scanned: ${SCAN_DIRS[*]}"
  exit 0
fi

echo "[trace] Found $MATCH_COUNT annotation(s)" >&2

# ── Parse matches and extract requirement references ────────────────────────────
# For each match line, extract all <spec-id>.RN references belonging to our spec.
# Track: file, line number, requirement IDs, whether it's a test file.

declare -A FILE_LINES       # file -> "line1:R1,R3|line2:R7"
declare -A REQ_FILES        # requirement -> "file1:line|file2:line"
declare -A REQ_TEST_FILES   # requirement -> "testfile1:line|testfile2:line"
ALL_REQS=()

is_test_file() {
  local f="$1"
  # Common test file patterns across languages
  case "$f" in
    */test/*|*/tests/*|*/spec/*|*/__tests__/*) return 0 ;;
    *Test.java|*Tests.java|*_test.go|*_test.py|*.test.js|*.test.ts|*.spec.js|*.spec.ts) return 0 ;;
    *test_*.py|*_test.rb|*Tests.cs|*Test.kt|*Test.scala) return 0 ;;
  esac
  return 1
}

while IFS= read -r line; do
  # Format: filepath:linenumber:content
  file=$(echo "$line" | cut -d: -f1)
  lineno=$(echo "$line" | cut -d: -f2)
  content=$(echo "$line" | cut -d: -f3-)

  # Make path relative to project root for display
  rel_file="${file#"$PROJECT_ROOT"/}"

  # Extract all <spec>.RN references for our spec from the content.
  # Handle both "<spec>.R1,R3,R7" (multi-req same spec) and "<spec>.R1 <other>.R4" (multi-spec).
  # Letter-suffixed RNs (R51a, R39h) are supported.

  # First, extract the @spec portion (everything after @spec until end of line or --)
  spec_portion=$(echo "$content" | sed -E 's/.*@spec\s+//; s/\s*--.*//; s/\s*—.*//')

  # Extract refs for our spec: <SPEC_ID>.RN[,RN,RN]
  # SPEC_ID_RE has dots regex-escaped so domain.slug ids match literally.
  our_refs=$(echo "$spec_portion" | grep -oE "${SPEC_ID_RE}\\.R[0-9]+[a-z]?(,R[0-9]+[a-z]?)*" || true)

  for ref_group in $our_refs; do
    # ref_group is like "<spec>.R1,R3,R7" or "<spec>.R1"
    # Strip the spec id prefix, split on comma
    req_part="${ref_group#"${SPEC_ID}".}"
    IFS=',' read -ra req_ids <<< "$req_part"
    for rid in "${req_ids[@]}"; do
      # Normalize: ensure it looks like Rn or Rn<letter>
      if echo "$rid" | grep -qE '^R[0-9]+[a-z]?$'; then
        full_ref="${SPEC_ID}.${rid}"

        # Track unique requirements
        if [[ -z "${REQ_FILES[$full_ref]+x}" ]]; then
          ALL_REQS+=("$full_ref")
          REQ_FILES[$full_ref]=""
          REQ_TEST_FILES[$full_ref]=""
        fi

        # Classify as test or implementation
        if is_test_file "$file"; then
          REQ_TEST_FILES[$full_ref]="${REQ_TEST_FILES[$full_ref]:+${REQ_TEST_FILES[$full_ref]}|}${rel_file}:${lineno}"
        else
          REQ_FILES[$full_ref]="${REQ_FILES[$full_ref]:+${REQ_FILES[$full_ref]}|}${rel_file}:${lineno}"
        fi
      fi
    done
  done
done < "$MATCHES_FILE"

# ── Sort requirements naturally by trailing R-number (R1, R2, ..., R10, R51a, ...) ──
# Strip the spec ID prefix to get just the RN, sort version-aware on that, then re-attach.
IFS=$'\n' SORTED_REQS=($(
  for r in "${ALL_REQS[@]}"; do
    rn="${r##*.}"   # drop everything up to and including the last "."
    printf "%s\t%s\n" "$rn" "$r"
  done | sort -V | cut -f2-
))
unset IFS

# ── Output ──────────────────────────────────────────────────────────────────────

if [[ "$FORMAT" == "json" ]]; then
  # JSON output for machine consumption
  echo "{"
  echo "  \"spec\": \"$SPEC_ID\","
  echo "  \"total_annotations\": $MATCH_COUNT,"
  echo "  \"requirements\": {"
  first=true
  for req in "${SORTED_REQS[@]}"; do
    $first || echo ","
    first=false
    impl_locations="${REQ_FILES[$req]}"
    test_locations="${REQ_TEST_FILES[$req]}"

    echo -n "    \"$req\": { \"implementation\": ["
    if [[ -n "$impl_locations" ]]; then
      ifirst=true
      IFS='|' read -ra locs <<< "$impl_locations"
      for loc in "${locs[@]}"; do
        $ifirst || echo -n ", "
        ifirst=false
        echo -n "\"$loc\""
      done
    fi
    echo -n "], \"test\": ["
    if [[ -n "$test_locations" ]]; then
      tfirst=true
      IFS='|' read -ra locs <<< "$test_locations"
      for loc in "${locs[@]}"; do
        $tfirst || echo -n ", "
        tfirst=false
        echo -n "\"$loc\""
      done
    fi
    echo -n "] }"
  done
  echo ""
  echo "  }"
  echo "}"

elif [[ "$FORMAT" == "detail" ]]; then
  # Detailed output: per-requirement with all locations
  echo "## $SPEC_ID — Trace Report (detail)"
  echo ""
  echo "**Annotations:** $MATCH_COUNT | **Requirements traced:** ${#SORTED_REQS[@]}"
  echo ""

  for req in "${SORTED_REQS[@]}"; do
    impl_locations="${REQ_FILES[$req]}"
    test_locations="${REQ_TEST_FILES[$req]}"
    impl_count=0
    test_count=0
    [[ -n "$impl_locations" ]] && impl_count=$(echo "$impl_locations" | tr '|' '\n' | wc -l | tr -d ' ')
    [[ -n "$test_locations" ]] && test_count=$(echo "$test_locations" | tr '|' '\n' | wc -l | tr -d ' ')

    echo "### $req"
    echo ""
    if [[ -n "$impl_locations" ]]; then
      echo "**Implementation** ($impl_count):"
      IFS='|' read -ra locs <<< "$impl_locations"
      for loc in "${locs[@]}"; do
        echo "- \`$loc\`"
      done
    else
      echo "**Implementation:** none"
    fi
    if [[ -n "$test_locations" ]]; then
      echo "**Test** ($test_count):"
      IFS='|' read -ra locs <<< "$test_locations"
      for loc in "${locs[@]}"; do
        echo "- \`$loc\`"
      done
    else
      echo "**Test:** none"
    fi
    echo ""
  done

else
  # Summary output (default): compact table
  echo "## $SPEC_ID — Trace Report"
  echo ""
  echo "**Annotations:** $MATCH_COUNT | **Requirements traced:** ${#SORTED_REQS[@]}"
  echo ""
  echo "| Requirement | Impl | Test | Locations |"
  echo "|-------------|------|------|-----------|"

  for req in "${SORTED_REQS[@]}"; do
    impl_locations="${REQ_FILES[$req]}"
    test_locations="${REQ_TEST_FILES[$req]}"
    impl_count=0
    test_count=0
    [[ -n "$impl_locations" ]] && impl_count=$(echo "$impl_locations" | tr '|' '\n' | wc -l | tr -d ' ')
    [[ -n "$test_locations" ]] && test_count=$(echo "$test_locations" | tr '|' '\n' | wc -l | tr -d ' ')

    # Show first impl + first test location as summary
    first_impl=""
    first_test=""
    if [[ -n "$impl_locations" ]]; then
      first_impl=$(echo "$impl_locations" | cut -d'|' -f1)
    fi
    if [[ -n "$test_locations" ]]; then
      first_test=$(echo "$test_locations" | cut -d'|' -f1)
    fi

    locations=""
    [[ -n "$first_impl" ]] && locations="$first_impl"
    [[ -n "$first_test" ]] && locations="${locations:+$locations, }$first_test"

    echo "| $req | $impl_count | $test_count | \`$locations\` |"
  done

  # Summary: requirements with no implementation or no tests
  no_impl=()
  no_test=()
  for req in "${SORTED_REQS[@]}"; do
    [[ -z "${REQ_FILES[$req]}" ]] && no_impl+=("$req")
    [[ -z "${REQ_TEST_FILES[$req]}" ]] && no_test+=("$req")
  done

  echo ""
  if [[ ${#no_impl[@]} -gt 0 ]]; then
    echo "**No implementation annotations:** ${no_impl[*]}"
  fi
  if [[ ${#no_test[@]} -gt 0 ]]; then
    echo "**No test annotations:** ${no_test[*]}"
  fi
  if [[ ${#no_impl[@]} -eq 0 && ${#no_test[@]} -eq 0 ]]; then
    echo "**All traced requirements have both implementation and test annotations.**"
  fi
fi
