#!/usr/bin/env bash
# spec-validate.sh — structural validation for spec files
# Usage: spec-validate.sh <spec-file>
# Exit 0 = PASS | Exit 1 = FAIL (prints all errors before exiting)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/spec-lib.sh"

spec_require_deps

FILE="${1:-}"
[[ -z "$FILE" ]] && { echo "Usage: spec-validate.sh <spec-file>" >&2; exit 1; }
[[ ! -f "$FILE" ]] && { echo "ERROR: File not found: $FILE" >&2; exit 1; }

# Locate .spec/ and project root
SPEC_DIR="$(spec_find_root)" || exit 1
PROJECT_ROOT="$(dirname "$SPEC_DIR")"
MANIFEST="$SPEC_DIR/registry/manifest.json"
ERRORS=()

# ── CRLF check first — all other checks are meaningless if CRLF present
if ! spec_check_crlf "$FILE"; then
  echo "FAIL: $FILE"
  echo "  CRLF line endings corrupt all parsing — fix before re-running"
  exit 1
fi

# ── Check 1: Front matter is valid JSON
raw_fm=$(awk '/^---$/{n++; next} n==1{print} n>=2{exit}' "$FILE")
if [[ -z "$raw_fm" ]]; then
  ERRORS+=("No front matter found between --- delimiters")
elif ! echo "$raw_fm" | jq . >/dev/null 2>&1; then
  ERRORS+=("Front matter is not valid JSON")
fi

# Only continue field checks if JSON is valid
if [[ ${#ERRORS[@]} -eq 0 ]]; then

  # ── Check 2: Required fields present
  for field in id version status state domains; do
    val=$(fm "$FILE" ".${field} // \"__missing__\"")
    [[ "$val" == "__missing__" ]] && ERRORS+=("Missing required field: $field")
  done

  # ── Check 3: state is valid enum
  STATE=$(fm "$FILE" '.state // ""')
  [[ ! "$STATE" =~ ^(DRAFT|APPROVED|INVALIDATED)$ ]] && \
    ERRORS+=("Invalid state: '$STATE' — must be DRAFT|APPROVED|INVALIDATED")

  # ── Check 4: status is valid enum
  STATUS=$(fm "$FILE" '.status // ""')
  [[ ! "$STATUS" =~ ^(DRAFT|ACTIVE|STABLE|DEPRECATED)$ ]] && \
    ERRORS+=("Invalid status: '$STATUS' — must be DRAFT|ACTIVE|STABLE|DEPRECATED")

  # ── Check 5: domains is a non-empty array
  DOMAIN_COUNT=$(fm "$FILE" '.domains | length // 0')
  [[ "$DOMAIN_COUNT" == "0" ]] && ERRORS+=("domains array is empty — at least one domain required")

  # ── Check 6: requires[] IDs resolve via registry
  if [[ -f "$MANIFEST" ]]; then
    while IFS= read -r req_id; do
      [[ -z "$req_id" ]] && continue
      req_file=$(spec_file_for_id "$MANIFEST" "$req_id")
      if [[ -z "$req_file" || ! -f "$req_file" ]]; then
        ERRORS+=("Unresolvable requires ID: $req_id (not in registry)")
      fi
    done < <(fm "$FILE" '.requires // [] | .[]')
  else
    echo "[validate] Warning: manifest not found, skipping requires check" >&2
  fi

  # ── Check 7: invalidates[] format AND target existence
  while IFS= read -r inv; do
    [[ -z "$inv" ]] && continue
    if ! echo "$inv" | grep -qE '^F[0-9]+\.R[0-9]+$'; then
      ERRORS+=("Invalid invalidates format: '$inv' (expected FXX.RN e.g. F01.R3)")
    elif [[ -f "$MANIFEST" ]]; then
      inv_err=$(spec_invalidates_check "$MANIFEST" "$inv" 2>&1 || true)
      if [[ -n "$inv_err" ]]; then
        ERRORS+=("$inv_err")
      fi
    fi
  done < <(fm "$FILE" '.invalidates // [] | .[]')

  # ── Check 7b: displaced_by[] IDs resolve via registry (optional field)
  while IFS= read -r dby; do
    [[ -z "$dby" ]] && continue
    if ! echo "$dby" | grep -qE '^F[0-9]+$'; then
      ERRORS+=("Invalid displaced_by format: '$dby' (expected FXX e.g. F05)")
    elif [[ -f "$MANIFEST" ]]; then
      dby_file=$(spec_file_for_id "$MANIFEST" "$dby")
      if [[ -z "$dby_file" || ! -f "$dby_file" ]]; then
        ERRORS+=("Unresolvable displaced_by ID: $dby (not in registry)")
      fi
    fi
  done < <(fm "$FILE" '.displaced_by // [] | .[]')

  # ── Check 7c: revives[] IDs must be INVALIDATED specs
  while IFS= read -r rev; do
    [[ -z "$rev" ]] && continue
    if ! echo "$rev" | grep -qE '^F[0-9]+$'; then
      ERRORS+=("Invalid revives format: '$rev' (expected FXX e.g. F05)")
    elif [[ -f "$MANIFEST" ]]; then
      rev_file=$(spec_file_for_id "$MANIFEST" "$rev")
      if [[ -z "$rev_file" || ! -f "$rev_file" ]]; then
        ERRORS+=("Unresolvable revives ID: $rev (not in registry)")
      else
        rev_state=$(fm "$rev_file" '.state // ""')
        if [[ "$rev_state" != "INVALIDATED" ]]; then
          ERRORS+=("revives '$rev' has state '$rev_state' — must be INVALIDATED")
        fi
      fi
    fi
  done < <(fm "$FILE" '.revives // [] | .[]')

  # ── Check 7d: revived_by[] IDs resolve via registry (optional field)
  while IFS= read -r rby; do
    [[ -z "$rby" ]] && continue
    if ! echo "$rby" | grep -qE '^F[0-9]+$'; then
      ERRORS+=("Invalid revived_by format: '$rby' (expected FXX e.g. F05)")
    elif [[ -f "$MANIFEST" ]]; then
      rby_file=$(spec_file_for_id "$MANIFEST" "$rby")
      if [[ -z "$rby_file" || ! -f "$rby_file" ]]; then
        ERRORS+=("Unresolvable revived_by ID: $rby (not in registry)")
      fi
    fi
  done < <(fm "$FILE" '.revived_by // [] | .[]')

  # ── Check 7e: displacement_reason present when displaced_by is non-empty (warning)
  DISPLACED_BY_COUNT=$(fm "$FILE" '.displaced_by // [] | length')
  if [[ "$DISPLACED_BY_COUNT" != "0" && "$DISPLACED_BY_COUNT" != "null" ]]; then
    DISP_REASON=$(fm "$FILE" '.displacement_reason // ""')
    if [[ -z "$DISP_REASON" ]]; then
      echo "  WARN: displaced_by is non-empty but displacement_reason is missing" >&2
    fi
  fi

  # ── Check 8: decision_refs resolve (warning, not error)
  while IFS= read -r ref; do
    [[ -z "$ref" ]] && continue
    spec_decision_ref_check "$PROJECT_ROOT" "$ref" 2>&1 || true
  done < <(fm "$FILE" '.decision_refs // [] | .[]')

  # ── Check 9: kb_refs resolve (warning, not error)
  while IFS= read -r ref; do
    [[ -z "$ref" ]] && continue
    spec_kb_ref_check "$PROJECT_ROOT" "$ref" 2>&1 || true
  done < <(fm "$FILE" '.kb_refs // [] | .[]')

  # ── Check 9b: APPROVED specs must not have unresolved conflict markers
  if [[ "$STATE" == "APPROVED" ]]; then
    spec_body=$(machine_section "$FILE")
    if echo "$spec_body" | grep -qE '\[UNRESOLVED\]' 2>/dev/null; then
      ERRORS+=("APPROVED spec has [UNRESOLVED] markers — should be DRAFT")
    fi
    if echo "$spec_body" | grep -qE '\[CONFLICT\]' 2>/dev/null; then
      ERRORS+=("APPROVED spec has [CONFLICT] markers — should be DRAFT")
    fi
  fi

fi  # end JSON-valid block

# ── Check 10: human narrative separator exists after requirements
# Count --- occurrences — need at least 3 (open FM, close FM, narrative separator)
delim_count=$(grep -c '^---$' "$FILE" || true)
if (( delim_count < 3 )); then
  ERRORS+=("Missing human narrative separator — need 3x '---' lines, found $delim_count")
fi

# ── Check 11: numbered requirements exist in machine section
machine=$(machine_section "$FILE")
if ! echo "$machine" | grep -qE '^R[0-9]+\.'; then
  ERRORS+=("No numbered requirements found in machine section (expected R1. R2. format)")
fi

# ── Report
if [[ ${#ERRORS[@]} -gt 0 ]]; then
  echo "FAIL: $FILE"
  for err in "${ERRORS[@]}"; do
    echo "  $err"
  done
  exit 1
else
  echo "PASS: $FILE"
  exit 0
fi
