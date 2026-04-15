#!/usr/bin/env bash
# work-context.sh — lightweight work group context builder
# Usage: work-context.sh [--group <slug>] [--feature <feature-slug>] [--domains <d1,d2>]
#
# Builds a bounded markdown snippet of work group context relevant to the
# current operation. Designed to be called by skills (architect, spec-author,
# domain analysis, work planner) to inject work awareness.
#
# Detection priority:
#   1. --group <slug>           explicit work group
#   2. --feature <feature-slug> auto-detect from .feature/<slug>/status.md
#   3. --domains <d1,d2>        find work groups with WDs in these domains
#
# Output: markdown snippet on stdout (empty if no relevant context)
# Exit: always 0 (never fails — missing context is not an error)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/work-lib.sh"

# ── Argument parsing ─────────────────────────────────────────────────────────

GROUP_SLUG=""
FEATURE_SLUG=""
DOMAIN_LIST=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --group)   GROUP_SLUG="$2"; shift 2 ;;
    --feature) FEATURE_SLUG="$2"; shift 2 ;;
    --domains) DOMAIN_LIST="$2"; shift 2 ;;
    *) shift ;;
  esac
done

# ── Locate .work/ — silent exit if not found ─────────────────────────────────

WORK_DIR=""
dir="$PWD"
while [[ "$dir" != "/" ]]; do
  if [[ -d "$dir/.work" ]]; then
    WORK_DIR="$dir/.work"
    break
  fi
  dir="$(dirname "$dir")"
done

# No .work/ directory — no context to provide
[[ -z "$WORK_DIR" ]] && exit 0

PROJECT_ROOT="$(dirname "$WORK_DIR")"

# ── Step 1: Determine which work group(s) to report on ──────────────────────

MATCHED_GROUPS=()

if [[ -n "$GROUP_SLUG" ]]; then
  # Explicit group
  if [[ -d "$WORK_DIR/$GROUP_SLUG" ]]; then
    MATCHED_GROUPS+=("$GROUP_SLUG")
  fi

elif [[ -n "$FEATURE_SLUG" ]]; then
  # Auto-detect from feature status.md
  status_file="$PROJECT_ROOT/.feature/$FEATURE_SLUG/status.md"
  if [[ -f "$status_file" ]]; then
    # Check for work_group field in status.md
    wg=$(grep -m1 '^work_group:' "$status_file" 2>/dev/null | sed 's/^work_group:[[:space:]]*//' || true)
    if [[ -n "$wg" && -d "$WORK_DIR/$wg" ]]; then
      MATCHED_GROUPS+=("$wg")
    fi
  fi
  # Also try double-dash convention: feature slug = "group--wd"
  if [[ ${#MATCHED_GROUPS[@]} -eq 0 && "$FEATURE_SLUG" == *"--"* ]]; then
    group_part="${FEATURE_SLUG%%--*}"
    if [[ -d "$WORK_DIR/$group_part" ]]; then
      MATCHED_GROUPS+=("$group_part")
    fi
  fi

elif [[ -n "$DOMAIN_LIST" ]]; then
  # Find work groups with WDs in matching domains
  IFS=',' read -ra SEARCH_DOMAINS <<< "$DOMAIN_LIST"
  while IFS= read -r group_dir; do
    [[ -z "$group_dir" ]] && continue
    group_name="$(basename "$group_dir")"
    # Check each WD in this group for domain overlap
    matched=false
    while IFS= read -r wd_file; do
      [[ -z "$wd_file" ]] && continue
      while IFS= read -r wd_domain; do
        [[ -z "$wd_domain" ]] && continue
        for search_domain in "${SEARCH_DOMAINS[@]}"; do
          if [[ "$wd_domain" == "$search_domain" ]]; then
            matched=true
            break 3
          fi
        done
      done < <(work_fm_array "$wd_file" "domains")
    done < <(work_list_wds "$group_dir")
    [[ "$matched" == "true" ]] && MATCHED_GROUPS+=("$group_name")
  done < <(work_list_groups "$WORK_DIR")
fi

# No matching work groups — no context
[[ ${#MATCHED_GROUPS[@]} -eq 0 ]] && exit 0

# ── Step 2: Build context snippet ────────────────────────────────────────────

echo "## Work Group Context"
echo ""

for group_slug in "${MATCHED_GROUPS[@]}"; do
  group_dir="$WORK_DIR/$group_slug"
  work_file="$group_dir/work.md"

  # Read goal from work.md
  goal=""
  if [[ -f "$work_file" ]]; then
    goal=$(work_fm "$work_file" "goal")
  fi

  echo "### $group_slug"
  [[ -n "$goal" ]] && echo "**Goal:** $goal"
  echo ""

  # Collect WD summaries
  echo "| WD | Title | Status | Domains | Deps | Produces |"
  echo "|----|-------|--------|---------|------|----------|"

  while IFS= read -r wd_file; do
    [[ -z "$wd_file" ]] && continue
    wd_id=$(work_fm "$wd_file" "id")
    wd_title=$(work_fm "$wd_file" "title")
    wd_status=$(work_fm "$wd_file" "status")
    wd_domains=$(work_fm_array "$wd_file" "domains" | tr '\n' ',' | sed 's/,$//')

    # Count deps and produces
    dep_count=0
    while IFS= read -r _; do
      ((dep_count++)) || true
    done < <(work_fm_artifact_deps "$wd_file")

    prod_summary=""
    while IFS='|' read -r prod_type prod_path _ prod_kind; do
      [[ -z "$prod_type" ]] && continue
      label="$prod_type:$prod_path"
      [[ -n "$prod_kind" ]] && label+=" ($prod_kind)"
      if [[ -z "$prod_summary" ]]; then
        prod_summary="$label"
      else
        prod_summary="$prod_summary, $label"
      fi
    done < <(work_fm_produces "$wd_file")

    echo "| $wd_id | ${wd_title:-—} | ${wd_status:-—} | ${wd_domains:-—} | $dep_count | ${prod_summary:-—} |"
  done < <(work_list_wds "$group_dir")

  echo ""

  # Surface shared interface contracts
  has_interfaces=false
  while IFS= read -r wd_file; do
    [[ -z "$wd_file" ]] && continue
    while IFS='|' read -r prod_type prod_path _ prod_kind; do
      [[ -z "$prod_type" ]] && continue
      if [[ "$prod_kind" == "interface-contract" ]]; then
        if [[ "$has_interfaces" != "true" ]]; then
          echo "**Shared interfaces:**"
          has_interfaces=true
        fi
        producer_id=$(work_fm "$wd_file" "id")
        # Find consumers
        consumers=""
        while IFS= read -r other_wd; do
          [[ -z "$other_wd" ]] && continue
          other_id=$(work_fm "$other_wd" "id")
          [[ "$other_id" == "$producer_id" ]] && continue
          while IFS='|' read -r dep_type dep_ref _ _; do
            if [[ "$dep_type" == "$prod_type" && "$dep_ref" == "$prod_path" ]]; then
              consumers+="${consumers:+, }$other_id"
            fi
          done < <(work_fm_artifact_deps "$other_wd")
        done < <(work_list_wds "$group_dir")
        echo "- $prod_path — produced by $producer_id, consumed by ${consumers:-none}"
      fi
    done < <(work_fm_produces "$wd_file")
  done < <(work_list_wds "$group_dir")

  [[ "$has_interfaces" == "true" ]] && echo ""

  # Surface ADR dependencies across WDs
  # Collect ADR deps into a temp file to avoid associative array lifecycle issues
  adr_tmp=$(mktemp)
  while IFS= read -r wd_file; do
    [[ -z "$wd_file" ]] && continue
    wd_id=$(work_fm "$wd_file" "id")
    while IFS='|' read -r dep_type dep_ref _ _; do
      [[ "$dep_type" != "adr" ]] && continue
      echo "$dep_ref|$wd_id" >> "$adr_tmp"
    done < <(work_fm_artifact_deps "$wd_file")
  done < <(work_list_wds "$group_dir")

  if [[ -s "$adr_tmp" ]]; then
    echo "**ADR dependencies:**"
    # Group consumers by ADR slug
    for adr_slug in $(cut -d'|' -f1 "$adr_tmp" | sort -u); do
      consumers=$(grep "^${adr_slug}|" "$adr_tmp" | cut -d'|' -f2 | tr '\n' ',' | sed 's/,$//' | sed 's/,/, /g')
      echo "- $adr_slug — needed by $consumers"
    done
    echo ""
  fi
  rm -f "$adr_tmp"
done
