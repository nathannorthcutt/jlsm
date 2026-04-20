#!/usr/bin/env bash
# work-finalize.sh — finalize work group state after feature refactor
# Usage: work-finalize.sh <feature-slug>
#
# Called by feature-refactor Step 6b. For work-group-sourced features:
# 1. Sets WD status to COMPLETE
# 2. Resolves obligations referenced in brief.md
# 3. Removes resolved IDs from spec open_obligations frontmatter
#
# Idempotent — safe to run multiple times.

set -euo pipefail

SLUG="${1:-}"
[[ -z "$SLUG" ]] && { echo "Usage: work-finalize.sh <feature-slug>" >&2; exit 0; }

# Find .feature directory (check both active and archive)
FEATURE_DIR=""
if [[ -d ".feature/$SLUG" ]]; then
    FEATURE_DIR=".feature/$SLUG"
elif [[ -d ".feature/_archive/$SLUG" ]]; then
    FEATURE_DIR=".feature/_archive/$SLUG"
fi

[[ -z "$FEATURE_DIR" ]] && { echo "[finalize] Feature directory not found for $SLUG" >&2; exit 0; }

STATUS_FILE="$FEATURE_DIR/status.md"
BRIEF_FILE="$FEATURE_DIR/brief.md"

[[ ! -f "$STATUS_FILE" ]] && { echo "[finalize] No status.md — skipping" >&2; exit 0; }

# ── Detect work group ──────────────────────────────────────────────────────

WORK_GROUP=""
WORK_DEF=""

# Try status.md fields first
if [[ -f "$STATUS_FILE" ]]; then
    WORK_GROUP="$(grep -m1 '^work_group:' "$STATUS_FILE" 2>/dev/null | sed 's/^work_group:[[:space:]]*//' || true)"
    WORK_DEF="$(grep -m1 '^work_definition:' "$STATUS_FILE" 2>/dev/null | sed 's/^work_definition:[[:space:]]*//' || true)"
fi

# Fallback: derive from slug convention (group--wd)
if [[ -z "$WORK_GROUP" && "$SLUG" == *"--"* ]]; then
    WORK_GROUP="${SLUG%%--*}"
    wd_suffix="${SLUG##*--}"
    WORK_DEF="$(echo "$wd_suffix" | tr '[:lower:]' '[:upper:]' | sed 's/-/\-/g')"
fi

[[ -z "$WORK_GROUP" ]] && { echo "[finalize] Not a work-group feature — skipping" >&2; exit 0; }

echo "[finalize] Work group: $WORK_GROUP, WD: $WORK_DEF"

# ── 1. Set WD status to COMPLETE ──────────────────────────────────────────

WORK_DIR=".work/$WORK_GROUP"
[[ ! -d "$WORK_DIR" ]] && { echo "[finalize] Work group directory not found: $WORK_DIR" >&2; exit 0; }

# Find the WD file
WD_FILE=""
for f in "$WORK_DIR"/WD-*.md; do
    [[ ! -f "$f" ]] && continue
    wd_id="$(awk '/^---$/{n++; next} n==1 && /^id:/{sub(/^id:[[:space:]]*/, ""); gsub(/["'"'"']/, ""); print; exit} n>=2{exit}' "$f")"
    if [[ "$wd_id" == "$WORK_DEF" ]]; then
        WD_FILE="$f"
        break
    fi
done

if [[ -n "$WD_FILE" ]]; then
    current_status="$(awk '/^---$/{n++; next} n==1 && /^status:/{sub(/^status:[[:space:]]*/, ""); gsub(/["'"'"']/, ""); print; exit} n>=2{exit}' "$WD_FILE")"
    if [[ "$current_status" == "COMPLETE" ]]; then
        echo "[finalize] WD already COMPLETE — skipping status update"
    else
        sed -i "s/^status:.*$/status: COMPLETE/" "$WD_FILE"
        echo "[finalize] WD status → COMPLETE"
    fi
else
    echo "[finalize] WD file not found for $WORK_DEF in $WORK_DIR" >&2
fi

# ── 2. Resolve obligations ────────────────────────────────────────────────

OB_REGISTRY=".spec/registry/_obligations.json"
[[ ! -f "$OB_REGISTRY" ]] && { echo "[finalize] No _obligations.json — skipping obligation resolution" >&2; exit 0; }
command -v jq >/dev/null 2>&1 || { echo "[finalize] jq not available — skipping obligation resolution" >&2; exit 0; }

# Extract obligation IDs from brief.md and WD file, then validate against registry
CANDIDATE_IDS=()
for src_file in "$BRIEF_FILE" "$WD_FILE"; do
    [[ -z "$src_file" || ! -f "$src_file" ]] && continue
    while IFS= read -r ob_id; do
        [[ -n "$ob_id" ]] && CANDIDATE_IDS+=("$ob_id")
    done < <(grep -oE 'OBL-[A-Za-z0-9_-]+' "$src_file" 2>/dev/null | sort -u)
done

# Validate candidates against actual IDs in the registry — drop partial matches
KNOWN_IDS="$(jq -r '.obligations[].id' "$OB_REGISTRY" 2>/dev/null)"
OB_IDS=()
for candidate in $(printf '%s\n' "${CANDIDATE_IDS[@]}" | sort -u); do
    if echo "$KNOWN_IDS" | grep -qx "$candidate" 2>/dev/null; then
        OB_IDS+=("$candidate")
    fi
done

if [[ ${#OB_IDS[@]} -eq 0 ]]; then
    echo "[finalize] No obligation IDs found in brief or WD — skipping"
    exit 0
fi

echo "[finalize] Found obligation IDs: ${OB_IDS[*]}"

TODAY="$(date -u +%Y-%m-%d)"
RESOLVED_IDS=()
SPEC_IDS=()

for ob_id in "${OB_IDS[@]}"; do
    # Check if this obligation exists and is open
    ob_status="$(jq -r --arg id "$ob_id" '.obligations[] | select(.id == $id) | .status' "$OB_REGISTRY" 2>/dev/null || true)"

    if [[ "$ob_status" == "open" ]]; then
        # Build resolved_by from the feature slug
        resolved_by="$SLUG"

        # Resolve the obligation
        jq --arg id "$ob_id" \
           --arg resolved_by "$resolved_by" \
           --arg date "$TODAY" \
           '(.obligations[] | select(.id == $id)) |= . + {status: "resolved", resolved_by: $resolved_by, resolved_date: $date}' \
           "$OB_REGISTRY" > "$OB_REGISTRY.tmp" && mv "$OB_REGISTRY.tmp" "$OB_REGISTRY"

        echo "[finalize] Resolved: $ob_id"
        RESOLVED_IDS+=("$ob_id")

        # Track which specs need frontmatter update
        spec_id="$(jq -r --arg id "$ob_id" '.obligations[] | select(.id == $id) | .spec' "$OB_REGISTRY" 2>/dev/null || true)"
        [[ -n "$spec_id" ]] && SPEC_IDS+=("$spec_id")
    elif [[ "$ob_status" == "resolved" ]]; then
        echo "[finalize] Already resolved: $ob_id"
    else
        echo "[finalize] Obligation $ob_id not found or status=$ob_status — skipping"
    fi
done

# ── 3. Update spec open_obligations frontmatter ────────────────────────────

# Deduplicate spec IDs
SPEC_IDS=($(printf '%s\n' "${SPEC_IDS[@]}" 2>/dev/null | sort -u))

MANIFEST=".spec/registry/manifest.json"
for spec_id in "${SPEC_IDS[@]}"; do
    # Find the spec file via manifest
    spec_file=""
    if [[ -f "$MANIFEST" ]]; then
        rel_path="$(jq -r --arg id "$spec_id" '.features[$id].latest_file // ""' "$MANIFEST" 2>/dev/null || true)"
        [[ -n "$rel_path" ]] && spec_file=".spec/$rel_path"
    fi

    # Fallback: search .spec/domains/
    if [[ -z "$spec_file" || ! -f "$spec_file" ]]; then
        spec_file="$(find .spec/domains -name "${spec_id}-*.md" -o -name "${spec_id}.md" 2>/dev/null | head -1 || true)"
    fi

    [[ -z "$spec_file" || ! -f "$spec_file" ]] && continue

    # Remove resolved obligation IDs from open_obligations array
    for ob_id in "${RESOLVED_IDS[@]}"; do
        # Check if this obligation belongs to this spec
        ob_spec="$(jq -r --arg id "$ob_id" '.obligations[] | select(.id == $id) | .spec' "$OB_REGISTRY" 2>/dev/null || true)"
        [[ "$ob_spec" != "$spec_id" ]] && continue

        # Remove from the JSON frontmatter's open_obligations array
        if grep -q "$ob_id" "$spec_file" 2>/dev/null; then
            # Handle the JSON frontmatter between --- delimiters
            # Remove the obligation ID from the open_obligations array
            sed -i "s/\"$ob_id\",\?//g" "$spec_file"
            # Clean up trailing commas in array
            sed -i 's/,\]/]/g' "$spec_file"
            # Clean up leading commas after [
            sed -i 's/\[,/[/g' "$spec_file"
            # Clean up double commas
            sed -i 's/,,/,/g' "$spec_file"
            echo "[finalize] Removed $ob_id from $spec_file open_obligations"
        fi
    done
done

echo "[finalize] Done. Resolved ${#RESOLVED_IDS[@]} obligations."
