#!/usr/bin/env bash
# spec-ambiguity-score.sh — quantitative ambiguity gate for specs.
#
# Computes a numeric ambiguity score for a spec file based on red-flag markers
# that indicate the spec is not yet ready for downstream consumers. Replaces
# the implicit "any [UNVERIFIED] marker -> DRAFT" rule with a transparent
# score + configurable threshold so authors can see *why* a spec is DRAFT.
#
# Inspired by adversarial-authoring critique (2026-04-21): binary gates hide
# signal — a spec with 1/30 unresolved claims shouldn't be classed the same
# as one with 15/30.
#
# Usage:
#   spec-ambiguity-score.sh <spec-file> [--threshold <n>] [--json]
#
# Exit 0 → score <= threshold (spec is ready)
# Exit 1 → score > threshold (spec needs more work) OR file unreadable
#
# Output (stdout):
#   Human-readable breakdown (default)
#   JSON with --json (for tooling pipelines)
#
# Score = (unverified + unresolved + conflict) / max(1, total_requirements)
# Default threshold: 0.20

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Argument parsing ─────────────────────────────────────────────────────────

FILE=""
THRESHOLD="0.20"
JSON_OUTPUT=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --threshold)
      THRESHOLD="${2:-}"
      [[ -z "$THRESHOLD" ]] && { echo "ERROR: --threshold requires a value" >&2; exit 1; }
      shift 2
      ;;
    --json)
      JSON_OUTPUT=true
      shift
      ;;
    -h|--help)
      sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    -*)
      echo "ERROR: unknown flag '$1'" >&2
      exit 1
      ;;
    *)
      if [[ -z "$FILE" ]]; then
        FILE="$1"
      else
        echo "ERROR: only one file argument allowed" >&2
        exit 1
      fi
      shift
      ;;
  esac
done

if [[ -z "$FILE" ]]; then
  echo "Usage: spec-ambiguity-score.sh <spec-file> [--threshold <n>] [--json]" >&2
  exit 1
fi

if [[ ! -f "$FILE" ]]; then
  echo "ERROR: file not found: $FILE" >&2
  exit 1
fi

# ── Machine section extraction ───────────────────────────────────────────────
# The spec format is: front matter (---...---) + machine section + Design
# Narrative. Ambiguity scoring applies to the machine section only —
# Design Narrative may legitimately contain "unresolved" in natural English.

machine_section() {
  awk '
    /^---$/ {
      delim++
      if (delim == 3) { exit }
      next
    }
    delim == 2 { print }
  ' "$FILE"
}

# If there are only two --- delimiters (no Design Narrative separator), the
# machine section is everything after the second delimiter. This handles
# specs that haven't yet been through spec-author's narrative phase.
SECTION_CONTENT="$(machine_section)"
if [[ -z "$SECTION_CONTENT" ]]; then
  SECTION_CONTENT="$(awk '/^---$/{n++; next} n>=2{print}' "$FILE")"
fi

# ── Counters ─────────────────────────────────────────────────────────────────

# Requirement count: lines starting with R<digits>. (allowing optional letter
# suffix like R3a)
TOTAL_REQS=$(echo "$SECTION_CONTENT" | grep -cE '^R[0-9]+[a-z]?\.' || true)

# Red-flag markers (case-sensitive by convention).
UNVERIFIED=$(echo "$SECTION_CONTENT" | grep -cE '\[UNVERIFIED' || true)
UNRESOLVED=$(echo "$SECTION_CONTENT" | grep -cE '\[UNRESOLVED' || true)
CONFLICT=$(echo "$SECTION_CONTENT" | grep -cE '\[CONFLICT' || true)

# Advisory signal (not in score): requirements that name class/method/file
# paths — structural leaks, per spec rules "behavioral, not structural".
# This is a heuristic and will have false positives.
CODE_REF=$(echo "$SECTION_CONTENT" \
  | grep -cE '(class|method|function)[[:space:]]+[A-Z][A-Za-z0-9_]+|[A-Z][A-Za-z0-9_]+\.(java|py|ts|js|go|rs):|src/[a-z]' \
  || true)

TOTAL_REDFLAGS=$(( UNVERIFIED + UNRESOLVED + CONFLICT ))

# Divide-by-zero guard: if the spec has no parseable requirements, score is
# 0 if there are also no red flags (empty spec), or 1.0 if there are flags
# with no requirements (broken spec).
if (( TOTAL_REQS == 0 )); then
  if (( TOTAL_REDFLAGS == 0 )); then
    SCORE="0.00"
  else
    SCORE="1.00"
  fi
else
  # Use awk for floating-point division; bash doesn't do floats.
  SCORE=$(awk -v n="$TOTAL_REDFLAGS" -v d="$TOTAL_REQS" \
    'BEGIN { printf "%.2f", n / d }')
fi

# ── Compare against threshold ────────────────────────────────────────────────

PASS=$(awk -v s="$SCORE" -v t="$THRESHOLD" \
  'BEGIN { print (s + 0 <= t + 0) ? "true" : "false" }')

# ── Output ───────────────────────────────────────────────────────────────────

if [[ "$JSON_OUTPUT" == "true" ]]; then
  cat <<EOF
{
  "file": "$FILE",
  "total_requirements": $TOTAL_REQS,
  "unverified": $UNVERIFIED,
  "unresolved": $UNRESOLVED,
  "conflict": $CONFLICT,
  "code_refs_advisory": $CODE_REF,
  "score": $SCORE,
  "threshold": $THRESHOLD,
  "pass": $PASS
}
EOF
else
  verdict="PASS"
  [[ "$PASS" == "false" ]] && verdict="FAIL"
  cat <<EOF
── Spec ambiguity score ─────────────────────────
  File             : $FILE
  Requirements     : $TOTAL_REQS
  Unverified       : $UNVERIFIED
  Unresolved       : $UNRESOLVED
  Conflict         : $CONFLICT
  Code-ref (advisory): $CODE_REF
  ─────────────────────────────────────────────
  Score            : $SCORE
  Threshold        : $THRESHOLD
  Verdict          : $verdict
──────────────────────────────────────────────────
EOF
  if [[ "$PASS" != "true" ]]; then
    cat >&2 <<EOF
Spec is above the readiness threshold. Common paths:
  - Resolve [UNVERIFIED] claims by reading code or running /research
  - Resolve [UNRESOLVED]/[CONFLICT] claims via /spec-author pass 2
  - If any remain, set state: DRAFT and add to open_obligations
EOF
  fi
fi

if [[ "$PASS" == "true" ]]; then
  exit 0
else
  exit 1
fi
