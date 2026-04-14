#!/usr/bin/env bash
# work-resolve.sh — readiness computation for work definitions
# Usage: work-resolve.sh <group-slug> [--curate]
# Output: markdown readiness report on stdout | diagnostics on stderr
#
# Walks all WDs in a work group, cross-references their artifact_deps against
# actual artifact states, and reports which are READY, BLOCKED, SPECIFYING,
# SPECIFIED, IMPLEMENTING, or COMPLETE.
# Legacy: IN_PROGRESS is treated as IMPLEMENTING for backwards compatibility.
#
# Readiness is computed each invocation — never cached.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/work-lib.sh"

work_require_deps

GROUP_SLUG="${1:-}"
CURATE_MODE=false
for arg in "$@"; do
  [[ "$arg" == "--curate" ]] && CURATE_MODE=true
done

[[ -z "$GROUP_SLUG" ]] && {
  echo "Usage: work-resolve.sh <group-slug> [--curate]" >&2
  exit 1
}

WORK_DIR="$(work_find_root)" || exit 1
PROJECT_ROOT="$(dirname "$WORK_DIR")"
GROUP_DIR="$WORK_DIR/$GROUP_SLUG"

[[ ! -d "$GROUP_DIR" ]] && {
  echo "ERROR: Work group not found: $GROUP_SLUG" >&2
  echo "  Expected directory: $GROUP_DIR" >&2
  exit 1
}

# ── Collect all work definitions ─────────────────────────────────────────────

declare -A WD_STATUS    # id -> status
declare -A WD_TITLE     # id -> title
declare -A WD_FILE      # id -> file path
declare -A WD_BLOCKERS  # id -> blocker descriptions (newline-separated)
declare -A WD_DEP_COUNT # id -> number of artifact deps

READY_COUNT=0
BLOCKED_COUNT=0
SPECIFYING_COUNT=0
SPECIFIED_COUNT=0
IMPLEMENTING_COUNT=0
COMPLETE_COUNT=0
TOTAL_COUNT=0

while IFS= read -r wd_file; do
  [[ -z "$wd_file" ]] && continue

  wd_id=$(work_fm "$wd_file" "id")
  wd_title=$(work_fm "$wd_file" "title")
  wd_status=$(work_fm "$wd_file" "status")

  [[ -z "$wd_id" ]] && {
    echo "[resolve] Warning: skipping $wd_file — no id field" >&2
    continue
  }

  WD_FILE["$wd_id"]="$wd_file"
  WD_TITLE["$wd_id"]="${wd_title:-$wd_id}"
  WD_DEP_COUNT["$wd_id"]=0
  WD_BLOCKERS["$wd_id"]=""
  ((TOTAL_COUNT++)) || true

  # Respect declared phase statuses — no recomputation needed
  if [[ "$wd_status" == "COMPLETE" ]]; then
    WD_STATUS["$wd_id"]="COMPLETE"
    ((COMPLETE_COUNT++)) || true
    continue
  fi

  if [[ "$wd_status" == "IMPLEMENTING" || "$wd_status" == "IN_PROGRESS" ]]; then
    WD_STATUS["$wd_id"]="IMPLEMENTING"
    ((IMPLEMENTING_COUNT++)) || true
    continue
  fi

  if [[ "$wd_status" == "SPECIFYING" ]]; then
    WD_STATUS["$wd_id"]="SPECIFYING"
    ((SPECIFYING_COUNT++)) || true
    continue
  fi

  if [[ "$wd_status" == "SPECIFIED" ]]; then
    WD_STATUS["$wd_id"]="SPECIFIED"
    ((SPECIFIED_COUNT++)) || true
    continue
  fi

  # For DRAFT: compute readiness from deps
  blockers=""
  dep_count=0

  while IFS='|' read -r dep_type dep_ref dep_req_state dep_kind; do
    [[ -z "$dep_type" ]] && continue
    ((dep_count++)) || true

    reason=""
    case "$dep_type" in
      spec)
        reason=$(work_check_spec_dep "$PROJECT_ROOT" "$dep_ref" "$dep_req_state") || true
        ;;
      adr)
        reason=$(work_check_adr_dep "$PROJECT_ROOT" "$dep_ref" "$dep_req_state") || true
        ;;
      kb)
        reason=$(work_check_kb_dep "$PROJECT_ROOT" "$dep_ref") || true
        ;;
      *)
        reason="unknown dependency type: $dep_type"
        ;;
    esac

    if [[ -n "$reason" ]]; then
      blockers+="$dep_type:$dep_ref — $reason"$'\n'
    fi
  done < <(work_fm_artifact_deps "$wd_file")

  WD_DEP_COUNT["$wd_id"]=$dep_count

  if [[ -z "$blockers" ]]; then
    WD_STATUS["$wd_id"]="READY"
    ((READY_COUNT++)) || true
  else
    WD_STATUS["$wd_id"]="BLOCKED"
    WD_BLOCKERS["$wd_id"]="$blockers"
    ((BLOCKED_COUNT++)) || true
  fi

done < <(work_list_wds "$GROUP_DIR")

echo "[resolve] Group: $GROUP_SLUG — $TOTAL_COUNT WDs ($READY_COUNT ready, $BLOCKED_COUNT blocked, $SPECIFYING_COUNT specifying, $SPECIFIED_COUNT specified, $IMPLEMENTING_COUNT implementing, $COMPLETE_COUNT complete)" >&2

# ── Build dependency graph ───────────────────────────────────────────────────
# For each WD, check if its produces match another WD's artifact_deps.
# This tells us which WDs unblock others.

declare -A WD_UNBLOCKS  # id -> list of WD ids it unblocks (comma-separated)

for wd_id in "${!WD_FILE[@]}"; do
  while IFS='|' read -r prod_type prod_path _ prod_kind; do
    [[ -z "$prod_type" ]] && continue
    # Check if any other WD depends on this artifact
    for other_id in "${!WD_FILE[@]}"; do
      [[ "$other_id" == "$wd_id" ]] && continue
      while IFS='|' read -r dep_type dep_ref dep_req _ ; do
        [[ -z "$dep_type" ]] && continue
        if [[ "$dep_type" == "$prod_type" && "$dep_ref" == "$prod_path" ]]; then
          if [[ -z "${WD_UNBLOCKS[$wd_id]+x}" ]]; then
            WD_UNBLOCKS["$wd_id"]="$other_id"
          else
            WD_UNBLOCKS["$wd_id"]="${WD_UNBLOCKS[$wd_id]},$other_id"
          fi
        fi
      done < <(work_fm_artifact_deps "${WD_FILE[$other_id]}")
    done
  done < <(work_fm_produces "${WD_FILE[$wd_id]}")
done

# ── Emit readiness report ────────────────────────────────────────────────────

TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

cat <<EOF
# Work Group Readiness: $GROUP_SLUG
Generated: $TIMESTAMP
Total: $TOTAL_COUNT | Ready: $READY_COUNT | Blocked: $BLOCKED_COUNT | Specifying: $SPECIFYING_COUNT | Specified: $SPECIFIED_COUNT | Implementing: $IMPLEMENTING_COUNT | Complete: $COMPLETE_COUNT

## Status

| WD | Title | Status | Deps | Unblocks |
|----|-------|--------|------|----------|
EOF

# Sort by status priority: actionable first
for status_phase in READY BLOCKED SPECIFYING SPECIFIED IMPLEMENTING COMPLETE; do
  for wd_id in $(echo "${!WD_STATUS[@]}" | tr ' ' '\n' | sort); do
    [[ "${WD_STATUS[$wd_id]}" != "$status_phase" ]] && continue
    unblocks="${WD_UNBLOCKS[$wd_id]:-none}"
    echo "| $wd_id | ${WD_TITLE[$wd_id]} | ${WD_STATUS[$wd_id]} | ${WD_DEP_COUNT[$wd_id]} | $unblocks |"
  done
done

# Emit blockers detail for BLOCKED WDs
has_blockers=false
for wd_id in $(echo "${!WD_STATUS[@]}" | tr ' ' '\n' | sort); do
  [[ "${WD_STATUS[$wd_id]}" != "BLOCKED" ]] && continue
  if [[ "$has_blockers" != "true" ]]; then
    echo ""
    echo "## Blockers"
    has_blockers=true
  fi
  echo ""
  echo "### $wd_id — ${WD_TITLE[$wd_id]}"
  echo "${WD_BLOCKERS[$wd_id]}" | while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    echo "- $line"
  done
done

# Scope signal: flag high-dependency WDs
has_scope_signal=false
SCOPE_THRESHOLD="${WORK_SCOPE_THRESHOLD:-5}"
for wd_id in $(echo "${!WD_DEP_COUNT[@]}" | tr ' ' '\n' | sort); do
  if (( WD_DEP_COUNT[$wd_id] > SCOPE_THRESHOLD )); then
    if [[ "$has_scope_signal" != "true" ]]; then
      echo ""
      echo "## Scope Signal"
      echo "Work definitions with >${SCOPE_THRESHOLD} artifact dependencies may benefit from decomposition."
      echo ""
      has_scope_signal=true
    fi
    echo "- **$wd_id** (${WD_TITLE[$wd_id]}): ${WD_DEP_COUNT[$wd_id]} dependencies"
  fi
done

# Curate mode: emit extra correlation data
if [[ "$CURATE_MODE" == "true" ]]; then
  echo ""
  echo "## Curate Data"
  echo ""

  # List all artifact deps across all WDs for cross-referencing
  echo "### All Artifact Dependencies"
  echo "| WD | Type | Reference | Required State |"
  echo "|----|------|-----------|----------------|"
  for wd_id in $(echo "${!WD_FILE[@]}" | tr ' ' '\n' | sort); do
    while IFS='|' read -r dep_type dep_ref dep_req_state dep_kind; do
      [[ -z "$dep_type" ]] && continue
      echo "| $wd_id | $dep_type | $dep_ref | $dep_req_state |"
    done < <(work_fm_artifact_deps "${WD_FILE[$wd_id]}")
  done

  # List all produces for displacement cross-referencing
  echo ""
  echo "### All Produced Artifacts"
  echo "| WD | Type | Path | Kind |"
  echo "|----|------|------|------|"
  for wd_id in $(echo "${!WD_FILE[@]}" | tr ' ' '\n' | sort); do
    while IFS='|' read -r prod_type prod_path _ prod_kind; do
      [[ -z "$prod_type" ]] && continue
      echo "| $wd_id | $prod_type | $prod_path | ${prod_kind:-—} |"
    done < <(work_fm_produces "${WD_FILE[$wd_id]}")
  done
fi

echo ""
echo "[resolve] Done." >&2
