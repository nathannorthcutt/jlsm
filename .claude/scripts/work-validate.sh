#!/usr/bin/env bash
# work-validate.sh — structural validation for work definition files
# Usage: work-validate.sh <wd-file>
#        work-validate.sh --group <group-slug>              (structural + circular dep check)
#        work-validate.sh --group <group-slug> --decompose  (structural + decompose invariant)
# Exit 0 = PASS | Exit 1 = FAIL (prints all errors before exiting)
#
# The --decompose flag adds the Phase C invariant check: every artifact_dep
# reference must resolve to either (a) an existing artifact in the repo,
# (b) an artifact listed in another WD's produces:, or (c) an artifact
# explicitly declared out-of-scope in work.md's frontmatter. This is what
# prevents parallel /work-plan runs from diverging — if decompose hasn't
# settled every cross-WD coordination surface, this invariant fails.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/work-lib.sh"
# spec-lib.sh is required for spec_file_for_id + fm (used by artifact_dep resolution).
# Ship together in the kit; if missing, validation below fails loud.
[[ -f "$SCRIPT_DIR/spec-lib.sh" ]] && source "$SCRIPT_DIR/spec-lib.sh"

work_require_deps

# ── Argument parsing ─────────────────────────────────────────────────────────

GROUP_MODE=false
DECOMPOSE_MODE=false
GROUP_SLUG=""
FILE=""

# First pass: identify --group + slug, or <wd-file>. --decompose can appear
# anywhere after --group.
if [[ "${1:-}" == "--group" ]]; then
  GROUP_MODE=true
  GROUP_SLUG="${2:-}"
  [[ -z "$GROUP_SLUG" ]] && { echo "Usage: work-validate.sh --group <group-slug> [--decompose]" >&2; exit 1; }
  shift 2
  for arg in "$@"; do
    [[ "$arg" == "--decompose" ]] && DECOMPOSE_MODE=true
  done
else
  FILE="${1:-}"
  [[ -z "$FILE" ]] && { echo "Usage: work-validate.sh <wd-file> | --group <group-slug> [--decompose]" >&2; exit 1; }
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
    local project_root
    project_root="$(cd "$(dirname "$file")/../.." && pwd)"
    while IFS='|' read -r dep_type dep_ref dep_req_state dep_kind; do
      [[ -z "$dep_type" ]] && continue
      ((dep_count++)) || true

      # Type must be spec, adr, kb, or wd
      if [[ ! "$dep_type" =~ ^(spec|adr|kb|wd)$ ]]; then
        errors+=("artifact_deps: invalid type '$dep_type' (must be spec|adr|kb|wd)")
      fi

      # Reference must be non-empty
      if [[ -z "$dep_ref" ]]; then
        errors+=("artifact_deps: missing path/slug for $dep_type dependency")
      fi

      # spec, adr, and wd must have required_state/required_status
      if [[ "$dep_type" =~ ^(spec|adr|wd)$ && -z "$dep_req_state" ]]; then
        errors+=("artifact_deps: $dep_type:$dep_ref missing required_state/required_status")
      fi

      # Check 5b: Resolve the reference. Distinguish "can't verify because the
      # target directory/manifest doesn't exist" (WARN — project may not use
      # that layer yet) from "verified and wrong" (ERROR — dead reference or
      # state mismatch). Catches dead wiring and stale state declarations
      # before the WD is picked up by /work-plan or /work-start.
      if [[ -n "$dep_type" && -n "$dep_ref" ]] && [[ "$dep_type" =~ ^(spec|adr|kb|wd)$ ]]; then
        case "$dep_type" in
          spec)
            local manifest="$project_root/.spec/registry/manifest.json"
            if [[ ! -f "$manifest" ]]; then
              echo "  WARN: cannot verify spec '$dep_ref' — no .spec/registry/manifest.json" >&2
            else
              # Delegate to work_check_spec_dep so validate and resolve agree
              # on what counts as a resolvable reference. Accepts both spec ID
              # (period-style) and slash-path forms.
              local spec_reason
              spec_reason="$(work_check_spec_dep "$project_root" "$dep_ref" "$dep_req_state")" || true
              if [[ -n "$spec_reason" ]]; then
                if [[ "$spec_reason" == "spec not found"* ]]; then
                  errors+=("artifact_deps: spec '$dep_ref' not found in registry (dead reference)")
                elif [[ "$spec_reason" == *"need "* ]]; then
                  # Re-format as "has state 'X', required_state is 'Y'" for the
                  # established test contract.
                  local actual_state
                  actual_state="$(echo "$spec_reason" | sed -n "s/^spec [^ ]* is \(.*\) (need .*)$/\1/p")"
                  if [[ -n "$actual_state" ]]; then
                    errors+=("artifact_deps: spec '$dep_ref' has state '$actual_state', required_state is '$dep_req_state'")
                  else
                    errors+=("artifact_deps: spec '$dep_ref' state mismatch: $spec_reason")
                  fi
                else
                  errors+=("artifact_deps: spec '$dep_ref': $spec_reason")
                fi
              fi
            fi
            ;;
          adr)
            if [[ ! -d "$project_root/.decisions" ]]; then
              echo "  WARN: cannot verify adr '$dep_ref' — no .decisions/ directory" >&2
            elif [[ ! -f "$project_root/.decisions/$dep_ref/adr.md" ]]; then
              errors+=("artifact_deps: adr '$dep_ref' not found (.decisions/$dep_ref/adr.md missing)")
            fi
            # ADR state lives in a markdown table, not front matter; state-value
            # check deferred until ADR schema is revisited.
            ;;
          wd)
            # wd: deps are scoped to the WD's own group. Cross-group
            # coordination belongs in work.md's external_deps:. A wd: ref
            # to another group validates OK today but is unreachable at
            # runtime (scripts/work-resolve.sh populates its WD table from
            # the current group only), so catch the mismatch here.
            #
            # State mismatch (target WD's current status != required_state) is
            # NOT a validation FAIL. wd: deps describe the eventual ordering;
            # at any moment, some siblings are upstream and some are downstream
            # of one another. scripts/work-resolve.sh surfaces this as BLOCKED
            # with a clear reason. Double-reporting it as FAIL here was noisy
            # for active groups and blocked legitimate fresh decompositions.
            if [[ ! -d "$project_root/.work" ]]; then
              echo "  WARN: cannot verify wd '$dep_ref' — no .work/ directory" >&2
            else
              local current_group_dir
              current_group_dir="$(dirname "$file")"
              local wd_found=0
              local wd_f
              while IFS= read -r wd_f; do
                [[ -z "$wd_f" ]] && continue
                local wd_id
                wd_id="$(work_fm "$wd_f" "id")"
                if [[ "$wd_id" == "$dep_ref" ]]; then
                  wd_found=1
                  break
                fi
              done < <(find "$current_group_dir" -maxdepth 1 -name "WD-*.md" 2>/dev/null)
              if [[ $wd_found -eq 0 ]]; then
                local cross_group=0
                local other_f
                while IFS= read -r other_f; do
                  [[ -z "$other_f" ]] && continue
                  local other_id
                  other_id="$(work_fm "$other_f" "id" 2>/dev/null)"
                  if [[ "$other_id" == "$dep_ref" ]]; then
                    cross_group=1
                    break
                  fi
                done < <(find "$project_root/.work" -name "WD-*.md" 2>/dev/null)
                if (( cross_group == 1 )); then
                  errors+=("artifact_deps: wd '$dep_ref' is in a different group — use external_deps: on work.md for cross-group coordination")
                else
                  errors+=("artifact_deps: wd '$dep_ref' not found in group")
                fi
              fi
            fi
            ;;
          kb)
            if [[ ! -d "$project_root/.kb" ]]; then
              echo "  WARN: cannot verify kb '$dep_ref' — no .kb/ directory" >&2
            else
              local kb_file="$project_root/.kb/${dep_ref%.md}.md"
              if [[ ! -f "$kb_file" ]]; then
                errors+=("artifact_deps: kb '$dep_ref' not found ($kb_file missing)")
              fi
            fi
            ;;
        esac
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
      # wd-type deps are direct WD-to-WD edges
      if [[ "$dep_type" == "wd" ]]; then
        [[ "$dep_ref" == "$wd_id" ]] && continue  # self-reference
        DEP_EDGES["$wd_id"]="${DEP_EDGES[$wd_id]} $dep_ref"
        continue
      fi
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

# ── External deps: group-level cross-group coordination gate ────────────────
#
# work.md may declare external_deps: — a list of references to sibling work
# groups that must reach a required_state before this group's WDs are ready.
# Validates shape and that referenced groups exist. Only type=group with
# required_state=COMPLETE is currently supported; scripts/work-lib.sh's
# work_check_group_dep enforces the same restriction at runtime.

validate_external_deps() {
  local group_dir="$1"
  local work_md="$group_dir/work.md"

  # work.md is optional — some groups predate it. Nothing to validate.
  [[ ! -f "$work_md" ]] && return 0

  local work_dir
  work_dir="$(dirname "$group_dir")"

  local errors=()
  local ent_count=0
  local ext_type ext_ref ext_req_state
  while IFS='|' read -r ext_type ext_ref ext_req_state; do
    [[ -z "$ext_type" ]] && continue
    ((ent_count++)) || true

    if [[ "$ext_type" != "group" ]]; then
      errors+=("external_deps: invalid type '$ext_type' (only 'group' supported)")
      continue
    fi

    if [[ -z "$ext_ref" ]]; then
      errors+=("external_deps: missing ref for group entry")
      continue
    fi

    if [[ -z "$ext_req_state" ]]; then
      errors+=("external_deps: group:$ext_ref missing required_state")
      continue
    fi

    if [[ "$ext_req_state" != "COMPLETE" ]]; then
      errors+=("external_deps: group:$ext_ref required_state '$ext_req_state' unsupported (only COMPLETE)")
      continue
    fi

    if [[ ! -d "$work_dir/$ext_ref" ]]; then
      errors+=("external_deps: referenced group '$ext_ref' does not exist")
    fi

    local self_slug
    self_slug="$(basename "$group_dir")"
    if [[ "$ext_ref" == "$self_slug" ]]; then
      errors+=("external_deps: group '$ext_ref' references itself")
    fi
  done < <(work_fm_external_deps "$work_md")

  if (( ${#errors[@]} > 0 )); then
    echo "  FAIL  external_deps in $work_md"
    for err in "${errors[@]}"; do
      echo "    $err"
    done
    return 1
  fi

  if (( ent_count > 0 )); then
    echo "  PASS  $ent_count external_deps entry(ies) valid"
  else
    echo "  PASS  no external_deps declared"
  fi
  return 0
}

# ── Decompose invariant: every cross-WD reference has a group-level artifact ─
#
# For each artifact_deps entry across all WDs in the group, the referenced
# artifact must resolve via one of:
#   (a) exists in the repo (spec in .spec/registry, ADR in .decisions/, KB file)
#   (b) listed in another WD's produces: array (i.e. produced within this group)
#   (c) declared explicitly out_of_scope in work.md frontmatter
#
# wd: deps are always internal-only and don't require a backing artifact.

check_decompose_invariant() {
  local group_dir="$1"
  local project_root
  project_root="$(dirname "$(work_find_root)")"

  local work_md="$group_dir/work.md"
  local unsettled=()
  local settled_count=0

  # Build the union of all produces: entries across all WDs in the group.
  # Format in the set: "<type>:<ref>" (e.g. "spec:encryption/primitives").
  local -A PRODUCED
  while IFS= read -r wd_file; do
    [[ -z "$wd_file" ]] && continue
    while IFS='|' read -r p_type p_ref _; do
      [[ -z "$p_type" ]] && continue
      PRODUCED["$p_type:$p_ref"]=1
    done < <(work_fm_produces "$wd_file" 2>/dev/null)
  done < <(work_list_wds "$group_dir")

  # Read out_of_scope list from work.md (optional).
  # Format in frontmatter: out_of_scope: ["spec:foo/bar", "adr:some-slug"]
  local -A OUT_OF_SCOPE
  if [[ -f "$work_md" ]]; then
    while IFS= read -r entry; do
      [[ -z "$entry" ]] && continue
      OUT_OF_SCOPE["$entry"]=1
    done < <(work_fm_array "$work_md" "out_of_scope" 2>/dev/null)
  fi

  # Walk each WD's artifact_deps and classify each reference.
  while IFS= read -r wd_file; do
    [[ -z "$wd_file" ]] && continue
    local wd_id
    wd_id=$(work_fm "$wd_file" "id")

    while IFS='|' read -r dep_type dep_ref dep_req_state _; do
      [[ -z "$dep_type" ]] && continue

      # wd: deps are internal and don't require a backing artifact.
      [[ "$dep_type" == "wd" ]] && continue

      local key="$dep_type:$dep_ref"
      local reason=""

      case "$dep_type" in
        spec)
          reason=$(work_check_spec_dep "$project_root" "$dep_ref" "$dep_req_state") || true
          ;;
        adr)
          reason=$(work_check_adr_dep "$project_root" "$dep_ref" "$dep_req_state") || true
          ;;
        kb)
          reason=$(work_check_kb_dep "$project_root" "$dep_ref") || true
          ;;
      esac

      # Settled if the dep resolves cleanly (exists + in required state).
      if [[ -z "$reason" ]]; then
        ((settled_count++)) || true
        continue
      fi

      # Unsettled in repo — check if produced by another WD in the group.
      if [[ -n "${PRODUCED[$key]:-}" ]]; then
        ((settled_count++)) || true
        continue
      fi

      # Unsettled + not produced — check if declared out of scope.
      if [[ -n "${OUT_OF_SCOPE[$key]:-}" ]]; then
        ((settled_count++)) || true
        continue
      fi

      # Unsettled reference.
      unsettled+=("$wd_id → $dep_type:$dep_ref ($reason)")
    done < <(work_fm_artifact_deps "$wd_file")
  done < <(work_list_wds "$group_dir")

  if [[ ${#unsettled[@]} -eq 0 ]]; then
    echo "  PASS  $settled_count cross-WD reference(s), all settled"
    return 0
  fi

  echo "  FAIL  ${#unsettled[@]} unsettled cross-WD reference(s):"
  for entry in "${unsettled[@]}"; do
    echo "    $entry"
  done
  echo ""
  echo "  Resolve by either:"
  echo "    - Authoring the artifact in Phase B (/architect or /spec-author), OR"
  echo "    - Adding it to another WD's produces: if a WD in this group will"
  echo "      author it during implementation, OR"
  echo "    - Declaring it explicitly out-of-scope in work.md's"
  echo "      out_of_scope: [\"spec:foo/bar\", ...] frontmatter list."
  return 1
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

  # Check external_deps shape + referenced group existence
  echo ""
  echo "── External deps check"
  if ! validate_external_deps "$GROUP_DIR"; then
    ((total_errors++)) || true
  fi

  # Phase C decompose invariant (--decompose flag only).
  if [[ "$DECOMPOSE_MODE" == "true" ]]; then
    echo ""
    echo "── Decompose invariant: every cross-WD reference settled"
    if ! check_decompose_invariant "$GROUP_DIR"; then
      ((total_errors++)) || true
    fi
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
