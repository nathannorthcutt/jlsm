#!/usr/bin/env bash
# work-validate.sh — structural validation for work definition files
# Usage: work-validate.sh <wd-file>
#        work-validate.sh --group <group-slug>   (validates all WDs + circular dep check)
# Exit 0 = PASS | Exit 1 = FAIL (prints all errors before exiting)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/work-lib.sh"

work_require_deps

# ── Argument parsing ─────────────────────────────────────────────────────────

GROUP_MODE=false
GROUP_SLUG=""
FILE=""

if [[ "${1:-}" == "--group" ]]; then
  GROUP_MODE=true
  GROUP_SLUG="${2:-}"
  [[ -z "$GROUP_SLUG" ]] && { echo "Usage: work-validate.sh --group <group-slug>" >&2; exit 1; }
else
  FILE="${1:-}"
  [[ -z "$FILE" ]] && { echo "Usage: work-validate.sh <wd-file>" >&2; exit 1; }
  [[ ! -f "$FILE" ]] && { echo "ERROR: File not found: $FILE" >&2; exit 1; }
fi

SCOPE_THRESHOLD="${WORK_SCOPE_THRESHOLD:-5}"

# ── Single file validation ───────────────────────────────────────────────────

validate_wd_file() {
  local file="$1"
  local errors=()

  # Check 1: Front matter exists (YAML between --- delimiters)
  local raw_fm
  raw_fm=$(awk '/^---$/{n++; next} n==1{print} n>=2{exit}' "$file")
  if [[ -z "$raw_fm" ]]; then
    errors+=("No front matter found between --- delimiters")
  fi

  # Only continue field checks if front matter exists
  if [[ ${#errors[@]} -eq 0 ]]; then

    # Check 2: Required fields present
    for field in id title group status domains; do
      val=$(work_fm "$file" "$field")
      [[ -z "$val" ]] && errors+=("Missing required field: $field")
    done

    # Check 3: status is valid enum
    local status
    status=$(work_fm "$file" "status")
    if [[ -n "$status" && ! "$status" =~ ^(DRAFT|SPECIFIED|READY|IN_PROGRESS|COMPLETE|BLOCKED)$ ]]; then
      errors+=("Invalid status: '$status' — must be DRAFT|SPECIFIED|READY|IN_PROGRESS|COMPLETE|BLOCKED")
    fi

    # Check 4: domains is non-empty
    local domain_count
    domain_count=$(work_fm_array "$file" "domains" | wc -l)
    (( domain_count == 0 )) && errors+=("domains is empty — at least one domain required")

    # Check 5: artifact_deps entries are well-formed
    local dep_count=0
    while IFS='|' read -r dep_type dep_ref dep_req_state dep_kind; do
      [[ -z "$dep_type" ]] && continue
      ((dep_count++)) || true

      # Type must be spec, adr, or kb
      if [[ ! "$dep_type" =~ ^(spec|adr|kb)$ ]]; then
        errors+=("artifact_deps: invalid type '$dep_type' (must be spec|adr|kb)")
      fi

      # Reference must be non-empty
      if [[ -z "$dep_ref" ]]; then
        errors+=("artifact_deps: missing path/slug for $dep_type dependency")
      fi

      # spec and adr must have required_state/required_status
      if [[ "$dep_type" =~ ^(spec|adr)$ && -z "$dep_req_state" ]]; then
        errors+=("artifact_deps: $dep_type:$dep_ref missing required_state/required_status")
      fi
    done < <(work_fm_artifact_deps "$file")

    # Check 6: produces entries are well-formed
    while IFS='|' read -r prod_type prod_path _ prod_kind; do
      [[ -z "$prod_type" ]] && continue

      if [[ ! "$prod_type" =~ ^(spec|adr|kb)$ ]]; then
        errors+=("produces: invalid type '$prod_type' (must be spec|adr|kb)")
      fi

      if [[ -z "$prod_path" ]]; then
        errors+=("produces: missing path for $prod_type artifact")
      fi
    done < <(work_fm_produces "$file")

    # Check 7: Scope signal (warning, not error)
    if (( dep_count > SCOPE_THRESHOLD )); then
      echo "  WARN: $dep_count artifact dependencies (threshold: $SCOPE_THRESHOLD) — consider decomposing" >&2
    fi
  fi

  # Check 8: Narrative sections exist
  local has_summary
  has_summary=$(grep -c '^## Summary' "$file" 2>/dev/null || true)
  (( has_summary == 0 )) && errors+=("Missing ## Summary section")

  local has_criteria
  has_criteria=$(grep -c '^## Acceptance Criteria' "$file" 2>/dev/null || true)
  (( has_criteria == 0 )) && errors+=("Missing ## Acceptance Criteria section")

  # Report
  if [[ ${#errors[@]} -gt 0 ]]; then
    echo "FAIL: $file"
    for err in "${errors[@]}"; do
      echo "  $err"
    done
    return 1
  else
    echo "PASS: $file"
    return 0
  fi
}

# ── Circular dependency detection ────────────────────────────────────────────
# Build a dependency graph from produces/artifact_deps relationships between
# WDs in the same group, then check for cycles via DFS.

check_circular_deps() {
  local group_dir="$1"

  # Build maps: WD id -> what it produces, WD id -> what it depends on
  declare -A PRODUCES_MAP   # "type|path" -> wd_id
  declare -A DEP_EDGES      # wd_id -> space-separated list of wd_ids it depends on

  while IFS= read -r wd_file; do
    [[ -z "$wd_file" ]] && continue
    local wd_id
    wd_id=$(work_fm "$wd_file" "id")
    [[ -z "$wd_id" ]] && continue

    # Register what this WD produces
    while IFS='|' read -r prod_type prod_path _ prod_kind; do
      [[ -z "$prod_type" ]] && continue
      PRODUCES_MAP["$prod_type|$prod_path"]="$wd_id"
    done < <(work_fm_produces "$wd_file")
  done < <(work_list_wds "$group_dir")

  # Build edges: if WD-A depends on an artifact that WD-B produces, A depends on B
  while IFS= read -r wd_file; do
    [[ -z "$wd_file" ]] && continue
    local wd_id
    wd_id=$(work_fm "$wd_file" "id")
    [[ -z "$wd_id" ]] && continue

    DEP_EDGES["$wd_id"]=""
    while IFS='|' read -r dep_type dep_ref dep_req_state dep_kind; do
      [[ -z "$dep_type" ]] && continue
      local key="$dep_type|$dep_ref"
      if [[ -n "${PRODUCES_MAP[$key]+x}" ]]; then
        local producer="${PRODUCES_MAP[$key]}"
        [[ "$producer" == "$wd_id" ]] && continue  # self-reference is fine
        DEP_EDGES["$wd_id"]="${DEP_EDGES[$wd_id]} $producer"
      fi
    done < <(work_fm_artifact_deps "$wd_file")
  done < <(work_list_wds "$group_dir")

  # DFS cycle detection
  declare -A VISITED  # 0=unvisited, 1=in-stack, 2=done
  local cycles_found=0

  dfs() {
    local node="$1"
    local path="$2"
    VISITED["$node"]=1

    for neighbor in ${DEP_EDGES[$node]:-}; do
      [[ -z "$neighbor" ]] && continue
      if [[ "${VISITED[$neighbor]:-0}" == "1" ]]; then
        echo "  CIRCULAR: $path -> $neighbor"
        ((cycles_found++)) || true
        return 1
      elif [[ "${VISITED[$neighbor]:-0}" == "0" ]]; then
        dfs "$neighbor" "$path -> $neighbor" || return 1
      fi
    done

    VISITED["$node"]=2
    return 0
  }

  for wd_id in "${!DEP_EDGES[@]}"; do
    if [[ "${VISITED[$wd_id]:-0}" == "0" ]]; then
      dfs "$wd_id" "$wd_id" || true
    fi
  done

  return $cycles_found
}

# ── Execution ────────────────────────────────────────────────────────────────

if [[ "$GROUP_MODE" == "true" ]]; then
  WORK_DIR="$(work_find_root)" || exit 1
  GROUP_DIR="$WORK_DIR/$GROUP_SLUG"

  [[ ! -d "$GROUP_DIR" ]] && {
    echo "ERROR: Work group not found: $GROUP_SLUG" >&2
    exit 1
  }

  echo ""
  echo "validate: work group $GROUP_SLUG"
  echo "────────────────────────────────────────────────"

  total_errors=0

  # Validate each WD file
  while IFS= read -r wd_file; do
    [[ -z "$wd_file" ]] && continue
    validate_wd_file "$wd_file" || ((total_errors++)) || true
  done < <(work_list_wds "$GROUP_DIR")

  # Check for circular dependencies
  echo ""
  echo "── Circular dependency check"
  if check_circular_deps "$GROUP_DIR"; then
    echo "  PASS  No circular dependencies"
  else
    echo "  FAIL  Circular dependencies detected"
    ((total_errors++)) || true
  fi

  echo ""
  echo "────────────────────────────────────────────────"
  if (( total_errors > 0 )); then
    echo "FAILED  $total_errors error(s)"
    exit 1
  else
    echo "ALL PASSED"
    exit 0
  fi
else
  validate_wd_file "$FILE"
  exit $?
fi
