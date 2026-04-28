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

  # ── Check 4b: kind is valid enum (optional field)
  KIND=$(fm "$FILE" '.kind // ""')
  if [[ -n "$KIND" && ! "$KIND" =~ ^(interface-contract)$ ]]; then
    ERRORS+=("Invalid kind: '$KIND' — must be interface-contract (or omit field)")
  fi

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

  # ── Spec ID format patterns (legacy FXX or domain.slug, optionally nested) ──
  # Spec ID alone:           F01  OR  schema.field-access  OR
  #                          encryption.primitives-lifecycle.key-rotation
  # Spec requirement ref:    F01.R3  OR  schema.field-access.R3  OR
  #                          encryption.primitives-lifecycle.key-rotation.R3
  #                          (letter-suffix RNs like R51a are supported;
  #                           multi-dot IDs are nested specs.)
  spec_id_re='^(F[0-9]+|[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+)$'
  spec_ref_re='^(F[0-9]+|[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+)\.R[0-9]+[a-z]?$'

  # ── Check 7: invalidates[] format AND target existence
  while IFS= read -r inv; do
    [[ -z "$inv" ]] && continue
    if ! echo "$inv" | grep -qE "$spec_ref_re"; then
      ERRORS+=("Invalid invalidates format: '$inv' (expected FXX.RN or domain.slug.RN, e.g. F01.R3 or schema.field-access.R3)")
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
    if ! echo "$dby" | grep -qE "$spec_id_re"; then
      ERRORS+=("Invalid displaced_by format: '$dby' (expected FXX or domain.slug, e.g. F05 or schema.field-access)")
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
    if ! echo "$rev" | grep -qE "$spec_id_re"; then
      ERRORS+=("Invalid revives format: '$rev' (expected FXX or domain.slug)")
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
    if ! echo "$rby" | grep -qE "$spec_id_re"; then
      ERRORS+=("Invalid revived_by format: '$rby' (expected FXX or domain.slug)")
    elif [[ -f "$MANIFEST" ]]; then
      rby_file=$(spec_file_for_id "$MANIFEST" "$rby")
      if [[ -z "$rby_file" || ! -f "$rby_file" ]]; then
        ERRORS+=("Unresolvable revived_by ID: $rby (not in registry)")
      fi
    fi
  done < <(fm "$FILE" '.revived_by // [] | .[]')

  # ── Check 7f: parent_spec resolves + ID-prefix consistency + acyclic ────────
  # parent_spec is a single-string field. When set:
  #   1. The parent must exist in the manifest.
  #   2. The child's ID must be the parent's ID + "." + a single hierarchical
  #      segment (so encryption.primitives-lifecycle.key-rotation may declare
  #      encryption.primitives-lifecycle as parent, but NOT encryption.foo).
  #   3. The chain walked via parent_spec MUST be acyclic. spec_walk_parent_chain
  #      surfaces any cycle on stderr; we verify by counting hops vs depth cap.
  PARENT_SPEC=$(fm "$FILE" '.parent_spec // ""')
  if [[ -n "$PARENT_SPEC" && "$PARENT_SPEC" != "null" ]]; then
    # Format check
    if ! echo "$PARENT_SPEC" | grep -qE "$spec_id_re"; then
      ERRORS+=("Invalid parent_spec format: '$PARENT_SPEC' (expected FXX or domain.slug[.subdomain...])")
    elif [[ -f "$MANIFEST" ]]; then
      # Existence check
      par_file=$(spec_file_for_id "$MANIFEST" "$PARENT_SPEC")
      if [[ -z "$par_file" || ! -f "$par_file" ]]; then
        ERRORS+=("Unresolvable parent_spec: $PARENT_SPEC (not in registry)")
      else
        # ID-prefix check: child ID must be parent ID + "." + one segment
        SELF_ID=$(fm "$FILE" '.id // ""')
        if [[ -n "$SELF_ID" ]]; then
          expected_prefix="${PARENT_SPEC}."
          if [[ "$SELF_ID" != ${expected_prefix}* ]]; then
            ERRORS+=("ID prefix mismatch: '$SELF_ID' must start with '$expected_prefix' to declare parent_spec '$PARENT_SPEC'")
          else
            # Trailing portion after the prefix must be a single hierarchical
            # segment (one or more lowercase-hyphenated words separated by dots
            # is valid for grandchildren — actually, wait, exactly one segment
            # for an immediate parent). We enforce ONE segment because
            # parent_spec is the *immediate* parent.
            tail_part="${SELF_ID#${expected_prefix}}"
            if [[ "$tail_part" == *.* ]]; then
              ERRORS+=("ID-segment mismatch: '$SELF_ID' has more than one segment beyond parent_spec '$PARENT_SPEC' — parent_spec must point to the IMMEDIATE parent (e.g. for a.b.c.d, parent_spec must be a.b.c, not a.b)")
            elif [[ -z "$tail_part" ]]; then
              ERRORS+=("ID equals parent_spec — '$SELF_ID' cannot declare itself as its own parent")
            fi
          fi
        fi
        # Acyclic check: walk the chain; spec_walk_parent_chain emits an
        # error to stderr if it detects a cycle or exceeds depth.
        chain_err=$(spec_walk_parent_chain "$MANIFEST" "$SELF_ID" 2>&1 >/dev/null || true)
        if echo "$chain_err" | grep -q "ERROR:"; then
          ERRORS+=("parent_spec chain invalid for '$SELF_ID': $(echo "$chain_err" | head -1 | sed 's/^\[spec-lib\] //')")
        fi
      fi
    fi
  fi

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
  # Use here-strings (not `echo "$var" | grep`) so SIGPIPE under pipefail
  # cannot flip the result on very large machine sections — observed at
  # 134 KB on encryption.primitives-lifecycle in jlsm.
  if [[ "$STATE" == "APPROVED" ]]; then
    spec_body=$(machine_section "$FILE")
    if grep -qE '\[UNRESOLVED\]' <<< "$spec_body" 2>/dev/null; then
      ERRORS+=("APPROVED spec has [UNRESOLVED] markers — should be DRAFT")
    fi
    if grep -qE '\[CONFLICT\]' <<< "$spec_body" 2>/dev/null; then
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
# Here-string instead of pipe — see 9b above for SIGPIPE rationale.
machine=$(machine_section "$FILE")
if ! grep -qE '^R[0-9]+\.' <<< "$machine"; then
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
