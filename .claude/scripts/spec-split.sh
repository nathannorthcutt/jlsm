#!/usr/bin/env bash
# spec-split.sh — execute a planned spec subdivision
#
# Usage:
#   spec-split.sh --plan <plan.json> [--scan-dirs <csv>] [--dry-run]
#   spec-split.sh --rollback <log.json>
#
# Modes:
#   default — read a split plan, execute it, write a rollback log.
#             On any post-write failure (validation, etc.), automatically
#             replays the rollback log and exits non-zero.
#   --rollback — replay a rollback log to undo a previously-executed split.
#                Useful if a manual revert is needed after the fact.
#   --dry-run — validate the plan and report what would happen, without
#               touching any file. Always exits 0 if plan is sane.
#
# Plan format (JSON):
#   {
#     "parent_id": "encryption.primitives-lifecycle",
#     "children": [
#       {
#         "id": "encryption.primitives-lifecycle.key-rotation",
#         "title": "Key Rotation",
#         "domains": ["encryption"],
#         "requirements": ["R12", "R13", "R14", ...]
#       },
#       ...
#     ]
#   }
#
# Cross-cutting requirements are implicit — every R-number in the source
# spec NOT listed in any child's `requirements` array stays at parent.
# R-numbers are preserved across the split (no renumbering).
#
# What this script does (in order):
#   1. Validate plan: parent exists, every child's requirements list is
#      a subset of parent's R-numbers, no overlaps between children, no
#      missing required JSON fields.
#   2. Snapshot: capture parent file content + manifest content into
#      a rollback log JSON written under .spec/.split-log/.
#   3. Generate per-child file content (carved from parent's body).
#   4. Generate new parent content (only cross-cutting reqs retained).
#   5. Write child files, overwrite parent.
#   6. Update manifest: insert child entries with parent_spec set.
#   7. Sweep @spec annotations: for each requirement that moved to a
#      child, rewrite `@spec parent.Rxx` → `@spec parent.child.Rxx` in
#      project source dirs (auto-detected: src/ lib/ app/ main/ modules/
#      examples/ benchmarks/ — NOT test/ unless --scan-dirs override).
#   8. Run spec-validate on parent + every child. On any failure,
#      replay the rollback log and exit 1.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=spec-lib.sh
source "$SCRIPT_DIR/spec-lib.sh"

spec_require_deps

# ── Argument parsing ─────────────────────────────────────────────────────────

PLAN_FILE=""
ROLLBACK_FILE=""
DRY_RUN=false
SCAN_DIRS_OVERRIDE=""

usage() {
  cat <<EOF
Usage:
  spec-split.sh --plan <plan.json> [--scan-dirs <csv>] [--dry-run]
  spec-split.sh --rollback <log.json>

Plan format:
  {
    "parent_id": "<spec-id>",
    "children": [
      {"id": "<parent-id>.<slug>", "title": "...", "domains": [...],
       "requirements": ["R12", "R13", ...]}
    ]
  }
EOF
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --plan) PLAN_FILE="${2:-}"; shift 2 ;;
    --rollback) ROLLBACK_FILE="${2:-}"; shift 2 ;;
    --scan-dirs) SCAN_DIRS_OVERRIDE="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h|--help) usage ;;
    *) echo "ERROR: unknown arg: $1" >&2; usage ;;
  esac
done

# ── Project tree resolution ──────────────────────────────────────────────────

SPEC_DIR="$(spec_find_root)" || exit 1
PROJECT_ROOT="$(dirname "$SPEC_DIR")"
MANIFEST="$SPEC_DIR/registry/manifest.json"
[[ ! -f "$MANIFEST" ]] && { echo "ERROR: manifest not found at $MANIFEST" >&2; exit 1; }

SPLIT_LOG_DIR="$SPEC_DIR/.split-log"

# ── Rollback mode ────────────────────────────────────────────────────────────

replay_rollback() {
  local log="$1"
  echo "[split] Replaying rollback log: $log" >&2
  [[ ! -f "$log" ]] && { echo "ERROR: rollback log not found: $log" >&2; return 1; }

  # Restore parent file content (always present in log).
  local parent_path parent_content
  parent_path="$(jq -r '.parent_path' "$log")"
  parent_content="$(jq -r '.parent_snapshot' "$log")"
  if [[ -n "$parent_path" && -n "$parent_content" ]]; then
    printf '%s\n' "$parent_content" > "$parent_path"
    echo "  restored: $parent_path" >&2
  fi

  # Restore manifest.
  local manifest_path manifest_content
  manifest_path="$(jq -r '.manifest_path' "$log")"
  manifest_content="$(jq -r '.manifest_snapshot' "$log")"
  if [[ -n "$manifest_path" && -n "$manifest_content" ]]; then
    printf '%s\n' "$manifest_content" > "$manifest_path"
    echo "  restored: $manifest_path" >&2
  fi

  # Delete created child files (in reverse order — innermost first).
  while IFS= read -r child_path; do
    [[ -z "$child_path" || "$child_path" == "null" ]] && continue
    if [[ -f "$child_path" ]]; then
      rm -f "$child_path"
      echo "  deleted: $child_path" >&2
    fi
  done < <(jq -r '.children_created[]' "$log")

  # Reverse @spec annotation rewrites.
  while IFS= read -r rewrite; do
    [[ -z "$rewrite" ]] && continue
    local file from_text to_text
    file="$(jq -r '.file' <<< "$rewrite")"
    from_text="$(jq -r '.to' <<< "$rewrite")"   # "to" was applied; reverse to "from"
    to_text="$(jq -r '.from' <<< "$rewrite")"
    if [[ -f "$file" ]]; then
      # Use a delimiter unlikely to appear in identifiers: |
      sed -i "s|${from_text}|${to_text}|g" "$file"
      echo "  unrewrote: $file (${from_text} → ${to_text})" >&2
    fi
  done < <(jq -c '.annotation_rewrites[]?' "$log")

  echo "[split] Rollback complete." >&2
  return 0
}

if [[ -n "$ROLLBACK_FILE" ]]; then
  replay_rollback "$ROLLBACK_FILE"
  exit $?
fi

# ── Default: plan execution ──────────────────────────────────────────────────

[[ -z "$PLAN_FILE" ]] && usage
[[ ! -f "$PLAN_FILE" ]] && { echo "ERROR: plan file not found: $PLAN_FILE" >&2; exit 1; }
jq . "$PLAN_FILE" >/dev/null 2>&1 || { echo "ERROR: plan is not valid JSON: $PLAN_FILE" >&2; exit 1; }

PARENT_ID="$(jq -r '.parent_id // ""' "$PLAN_FILE")"
[[ -z "$PARENT_ID" ]] && { echo "ERROR: plan missing parent_id" >&2; exit 1; }

PARENT_FILE="$(spec_file_for_id "$MANIFEST" "$PARENT_ID")"
[[ -z "$PARENT_FILE" || ! -f "$PARENT_FILE" ]] && {
  echo "ERROR: parent spec not found in registry: $PARENT_ID" >&2
  exit 1
}

# ── Parse parent's R-numbers from machine section ────────────────────────────

mapfile -t PARENT_R_NUMBERS < <(machine_section "$PARENT_FILE" \
  | grep -oE '^R[0-9]+[a-z]?\.' \
  | sed 's/\.$//' \
  | sort -u)

declare -A PARENT_R_SET
for r in "${PARENT_R_NUMBERS[@]}"; do
  PARENT_R_SET["$r"]=1
done

if [[ ${#PARENT_R_NUMBERS[@]} -eq 0 ]]; then
  echo "ERROR: parent spec has no R-requirements: $PARENT_FILE" >&2
  exit 1
fi

echo "[split] Parent: $PARENT_ID  ($PARENT_FILE)" >&2
echo "[split] Source R-numbers: ${#PARENT_R_NUMBERS[@]}  (R$(echo "${PARENT_R_NUMBERS[0]}" | tr -d R) … R$(echo "${PARENT_R_NUMBERS[-1]}" | tr -d R))" >&2

# ── Validate plan against parent ─────────────────────────────────────────────

CHILD_COUNT="$(jq '.children | length' "$PLAN_FILE")"
if (( CHILD_COUNT < 1 )); then
  echo "ERROR: plan has no children" >&2
  exit 1
fi

declare -A CLAIMED_R
declare -A CHILD_IDS_SEEN

# Each child entry validation pass.
for ((i=0; i<CHILD_COUNT; i++)); do
  child_id="$(jq -r ".children[$i].id // \"\"" "$PLAN_FILE")"
  child_title="$(jq -r ".children[$i].title // \"\"" "$PLAN_FILE")"
  child_doms="$(jq -c ".children[$i].domains // []" "$PLAN_FILE")"
  mapfile -t child_reqs < <(jq -r ".children[$i].requirements[]?" "$PLAN_FILE")

  [[ -z "$child_id" ]] && { echo "ERROR: child $i missing 'id'" >&2; exit 1; }
  [[ -z "$child_title" ]] && { echo "ERROR: child $i ($child_id) missing 'title'" >&2; exit 1; }

  # Child ID must extend parent ID by exactly one segment.
  expected_prefix="${PARENT_ID}."
  if [[ "$child_id" != ${expected_prefix}* ]]; then
    echo "ERROR: child id '$child_id' must start with '$expected_prefix'" >&2
    exit 1
  fi
  tail_part="${child_id#$expected_prefix}"
  if [[ "$tail_part" == *.* ]]; then
    echo "ERROR: child id '$child_id' must add exactly one segment to parent" >&2
    exit 1
  fi
  if [[ -z "$tail_part" ]]; then
    echo "ERROR: child id '$child_id' equals parent id" >&2
    exit 1
  fi

  # Duplicate child IDs in the plan
  if [[ -n "${CHILD_IDS_SEEN[$child_id]+x}" ]]; then
    echo "ERROR: duplicate child id in plan: $child_id" >&2
    exit 1
  fi
  CHILD_IDS_SEEN["$child_id"]=1

  # Each requirement must exist in parent + not already be claimed.
  if (( ${#child_reqs[@]} == 0 )); then
    echo "ERROR: child '$child_id' has no requirements (empty children are not allowed)" >&2
    exit 1
  fi
  for r in "${child_reqs[@]}"; do
    if [[ -z "${PARENT_R_SET[$r]+x}" ]]; then
      echo "ERROR: child '$child_id' requires '$r' which is not in parent's R-numbers" >&2
      exit 1
    fi
    if [[ -n "${CLAIMED_R[$r]+x}" ]]; then
      echo "ERROR: requirement '$r' claimed by both '${CLAIMED_R[$r]}' and '$child_id'" >&2
      exit 1
    fi
    CLAIMED_R["$r"]="$child_id"
  done

  echo "[split] Child plan: $child_id ($child_title) — ${#child_reqs[@]} reqs" >&2
done

# Cross-cutting set = parent reqs not claimed by any child.
CROSS_CUTTING=()
for r in "${PARENT_R_NUMBERS[@]}"; do
  [[ -z "${CLAIMED_R[$r]+x}" ]] && CROSS_CUTTING+=("$r")
done
echo "[split] Cross-cutting (stays at parent): ${#CROSS_CUTTING[@]}  ${CROSS_CUTTING[*]:-(none)}" >&2

if (( ${#CROSS_CUTTING[@]} == 0 )); then
  echo "ERROR: no cross-cutting requirements would remain at parent — every R-number is claimed by a child." >&2
  echo "       A parent must retain at least one cross-cutting requirement after a split." >&2
  echo "       Re-examine the plan; consider keeping at least one umbrella invariant at parent." >&2
  exit 1
fi

# ── Dry-run exit ─────────────────────────────────────────────────────────────

if [[ "$DRY_RUN" == "true" ]]; then
  echo "[split] DRY RUN — plan validates. ${#CHILD_COUNT[@]:-} children, ${#CROSS_CUTTING[@]} cross-cutting reqs." >&2
  exit 0
fi

# ── Build rollback log first (before any change) ─────────────────────────────

mkdir -p "$SPLIT_LOG_DIR"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
ROLLBACK_LOG="$SPLIT_LOG_DIR/${PARENT_ID//./-}-${TIMESTAMP}.json"

PARENT_SNAPSHOT="$(cat "$PARENT_FILE")"
MANIFEST_SNAPSHOT="$(cat "$MANIFEST")"

# Track files we'll create (for rollback delete) and annotation rewrites.
CHILDREN_CREATED=()
declare -A ANNOTATION_REWRITES_FROM=()  # key=index, val=from
declare -A ANNOTATION_REWRITES_TO=()
declare -A ANNOTATION_REWRITES_FILE=()
REWRITE_INDEX=0

# Write initial rollback log (we'll rewrite at the end with full state).
emit_rollback_log() {
  jq -n \
    --arg parent_id "$PARENT_ID" \
    --arg parent_path "$PARENT_FILE" \
    --arg parent_snapshot "$PARENT_SNAPSHOT" \
    --arg manifest_path "$MANIFEST" \
    --arg manifest_snapshot "$MANIFEST_SNAPSHOT" \
    --arg timestamp "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --argjson children_created "$(printf '%s\n' "${CHILDREN_CREATED[@]+"${CHILDREN_CREATED[@]}"}" | jq -R . | jq -s .)" \
    --argjson rewrites "$(
      if (( REWRITE_INDEX > 0 )); then
        for ((i=0; i<REWRITE_INDEX; i++)); do
          jq -n \
            --arg file "${ANNOTATION_REWRITES_FILE[$i]}" \
            --arg from "${ANNOTATION_REWRITES_FROM[$i]}" \
            --arg to "${ANNOTATION_REWRITES_TO[$i]}" \
            '{file: $file, from: $from, to: $to}'
        done | jq -s .
      else
        echo '[]'
      fi
    )" \
    '{
      parent_id: $parent_id,
      parent_path: $parent_path,
      parent_snapshot: $parent_snapshot,
      manifest_path: $manifest_path,
      manifest_snapshot: $manifest_snapshot,
      timestamp: $timestamp,
      children_created: $children_created,
      annotation_rewrites: $rewrites
    }' > "$ROLLBACK_LOG"
}

emit_rollback_log
echo "[split] Rollback log: $ROLLBACK_LOG" >&2

# ── Helper: extract a requirement's full block from parent body ──────────────
# A requirement block is everything from "RN. " up to (but not including) the
# next "RN. " line at the same indentation. Empty lines and continuation lines
# are part of the requirement.
extract_requirement_block() {
  local body="$1" rn="$2"
  awk -v target="$rn" '
    BEGIN { in_target = 0 }
    /^R[0-9]+[a-z]?\./ {
      if (in_target) exit
      # Match "Rxx." prefix exactly (Rxx followed by .)
      if (substr($0, 1, length(target) + 1) == target".") {
        in_target = 1
      }
    }
    in_target { print }
  ' <<< "$body"
}

# ── Pull parent metadata for child frontmatter ───────────────────────────────

PARENT_FM="$(awk '/^---$/{n++; next} n==1{print} n>=2{exit}' "$PARENT_FILE")"
PARENT_VERSION="$(jq -r '.version // 1' <<< "$PARENT_FM")"
PARENT_DOMAINS="$(jq -c '.domains // []' <<< "$PARENT_FM")"
PARENT_BODY="$(machine_section "$PARENT_FILE")"
PARENT_NARRATIVE="$(awk '/^---$/{n++; next} n==2{found=1; next} n>=3{print}' "$PARENT_FILE")"
PARENT_PRENARRATIVE="$(awk '
  /^---$/ { delim++; print; next }
  delim == 2 { exit }
  { print }
' "$PARENT_FILE" | awk '
  BEGIN { d = 0 }
  /^---$/ { d++; if (d == 2) { exit }; next }
  d == 1 { next }   # skip frontmatter
  d == 2 { print }
')"

# Actually — simpler: pre-narrative is whatever lives between FM close and
# machine section open. Let me re-extract.
# Spec layout:
#   ---           <- d=1
#   { JSON }
#   ---           <- d=2
#   <pre-narrative>
#                 (often "# Title" + a paragraph)
#   R1. ...
#   R2. ...
#   ---           <- d=3
#   <post-narrative>
# Wait — machine_section is delim==2 content, which INCLUDES pre-narrative
# and R-lines. The pre-narrative is the prose before the first R-line.

# Recompute pre/R/post split from machine_section content.
PARENT_PRENARRATIVE="$(awk '
  /^R[0-9]+[a-z]?\./ { exit }
  { print }
' <<< "$PARENT_BODY")"

# ── Build child files ────────────────────────────────────────────────────────

new_child_file_for_id() {
  # ID a.b.c.d → SPEC_DIR/domains/a/b/c/d.md
  local fid="$1"
  local top="${fid%%.*}"
  local rest="${fid#*.}"
  rest="${rest//./\/}"
  echo "$SPEC_DIR/domains/$top/${rest}.md"
}

build_child_file() {
  local child_id="$1" child_title="$2" child_domains="$3"
  shift 3
  local -a reqs=("$@")

  # Body: assemble per-requirement blocks in their order in PARENT_BODY.
  # Walk parent's R-numbers in source order; if R is in this child's req list,
  # emit it.
  declare -A IS_MINE
  for r in "${reqs[@]}"; do IS_MINE["$r"]=1; done

  local body=""
  for r in "${PARENT_R_NUMBERS[@]}"; do
    if [[ -n "${IS_MINE[$r]+x}" ]]; then
      body+=$(extract_requirement_block "$PARENT_BODY" "$r")
      body+=$'\n\n'
    fi
  done

  # Frontmatter for child.
  local child_fm
  child_fm="$(jq -n \
    --arg id "$child_id" \
    --argjson version 1 \
    --arg parent_spec "$PARENT_ID" \
    --argjson domains "$child_domains" \
    '{
      id: $id,
      version: $version,
      status: "ACTIVE",
      state: "DRAFT",
      domains: $domains,
      requires: [],
      invalidates: [],
      decision_refs: [],
      kb_refs: [],
      parent_spec: $parent_spec,
      _split_from: $parent_spec
    }')"

  # Compose: FM + title + body + post-narrative stub.
  cat <<EOF
---
$child_fm
---

# $child_id — $child_title

This spec was carved from \`$PARENT_ID\` during a domain subdivision. The
parent retains cross-cutting requirements that span multiple sub-domains;
the requirements below are specific to this concern.

${body%$'\n\n'}

---

## Notes
EOF
}

execute_split() {
  # Step 1: build child files in memory; only write after all are built.
  local -A CHILD_CONTENT=()
  local -A CHILD_PATH=()

  for ((i=0; i<CHILD_COUNT; i++)); do
    local child_id child_title child_doms
    child_id="$(jq -r ".children[$i].id" "$PLAN_FILE")"
    child_title="$(jq -r ".children[$i].title" "$PLAN_FILE")"
    child_doms="$(jq -c ".children[$i].domains" "$PLAN_FILE")"
    mapfile -t child_reqs < <(jq -r ".children[$i].requirements[]" "$PLAN_FILE")

    local child_path
    child_path="$(new_child_file_for_id "$child_id")"
    if [[ -f "$child_path" ]]; then
      echo "ERROR: child file already exists: $child_path" >&2
      return 1
    fi

    CHILD_CONTENT["$child_id"]="$(build_child_file "$child_id" "$child_title" "$child_doms" "${child_reqs[@]}")"
    CHILD_PATH["$child_id"]="$child_path"
  done

  # Step 2: build new parent body — only cross-cutting R-numbers retained.
  local new_parent_body=""
  if [[ -n "$PARENT_PRENARRATIVE" ]]; then
    # Trim trailing blank lines but preserve content.
    new_parent_body+="$PARENT_PRENARRATIVE"
    new_parent_body+=$'\n'
  fi
  for r in "${CROSS_CUTTING[@]}"; do
    new_parent_body+=$(extract_requirement_block "$PARENT_BODY" "$r")
    new_parent_body+=$'\n\n'
  done

  # Step 3: build new parent file (FM unchanged + new body).
  local new_parent_fm
  new_parent_fm="$(jq --argjson v "$((PARENT_VERSION + 1))" \
    '. + {version: $v}' <<< "$PARENT_FM")"

  local post_narrative
  post_narrative="$(awk '
    BEGIN { d = 0 }
    /^---$/ { d++; if (d == 3) { p = 1; next } }
    p { print }
  ' "$PARENT_FILE")"

  local new_parent_content
  new_parent_content="---
$new_parent_fm
---

${new_parent_body%$'\n\n'}

---

$post_narrative"

  # Step 4: write children + parent atomically (write to .tmp then mv).
  for child_id in "${!CHILD_PATH[@]}"; do
    local child_path="${CHILD_PATH[$child_id]}"
    mkdir -p "$(dirname "$child_path")"
    local tmp="$child_path.tmp"
    printf '%s\n' "${CHILD_CONTENT[$child_id]}" > "$tmp"
    mv "$tmp" "$child_path"
    CHILDREN_CREATED+=("$child_path")
    echo "  created: $child_path" >&2
  done

  # Overwrite parent.
  local parent_tmp="$PARENT_FILE.tmp"
  printf '%s\n' "$new_parent_content" > "$parent_tmp"
  mv "$parent_tmp" "$PARENT_FILE"
  echo "  rewrote: $PARENT_FILE (cross-cutting only, ${#CROSS_CUTTING[@]} reqs)" >&2

  # Step 5: update manifest with child entries.
  local manifest_tmp="$MANIFEST.tmp"
  cp "$MANIFEST" "$manifest_tmp"
  for ((i=0; i<CHILD_COUNT; i++)); do
    local child_id child_doms child_path child_rel
    child_id="$(jq -r ".children[$i].id" "$PLAN_FILE")"
    child_doms="$(jq -c ".children[$i].domains" "$PLAN_FILE")"
    child_path="${CHILD_PATH[$child_id]}"
    child_rel=".spec/${child_path#$SPEC_DIR/}"

    local next_tmp
    next_tmp="$(mktemp)"
    jq --arg id "$child_id" \
       --arg path "$child_rel" \
       --arg parent "$PARENT_ID" \
       --argjson doms "$child_doms" \
       '
        .specs = ((.specs // []) | map(select(.id != $id)) + [{
          id: $id,
          path: $path,
          state: "DRAFT",
          version: 1,
          domains: $doms,
          parent_spec: $parent,
          requires: [],
          invalidates: [],
          decision_refs: [],
          kb_refs: []
        }])
        | .spec_count = (.specs | length)
        | .generated_at = (now | todate)
       ' "$manifest_tmp" > "$next_tmp" && mv "$next_tmp" "$manifest_tmp"
  done
  mv "$manifest_tmp" "$MANIFEST"
  echo "  manifest: added $CHILD_COUNT child entries" >&2

  # Step 6: sweep @spec annotations for moved requirements.
  rewrite_annotations
}

# ── Annotation rewrite sweep ─────────────────────────────────────────────────

rewrite_annotations() {
  # Resolve scan dirs.
  local scan_dirs=()
  if [[ -n "$SCAN_DIRS_OVERRIDE" ]]; then
    IFS=',' read -ra user_dirs <<< "$SCAN_DIRS_OVERRIDE"
    for d in "${user_dirs[@]}"; do
      [[ -d "$PROJECT_ROOT/$d" ]] && scan_dirs+=("$PROJECT_ROOT/$d")
    done
  else
    for cand in src lib app main modules examples benchmarks; do
      [[ -d "$PROJECT_ROOT/$cand" ]] && scan_dirs+=("$PROJECT_ROOT/$cand")
    done
  fi
  if (( ${#scan_dirs[@]} == 0 )); then
    echo "[split] No source dirs to scan for @spec annotations — skipping rewrite." >&2
    return 0
  fi
  echo "[split] @spec rewrite scan: ${scan_dirs[*]}" >&2

  # For each child, for each moved requirement, rewrite annotations.
  for ((i=0; i<CHILD_COUNT; i++)); do
    local child_id
    child_id="$(jq -r ".children[$i].id" "$PLAN_FILE")"
    mapfile -t reqs < <(jq -r ".children[$i].requirements[]" "$PLAN_FILE")

    for r in "${reqs[@]}"; do
      local from_anno="@spec ${PARENT_ID}.${r}"
      local to_anno="@spec ${child_id}.${r}"

      # Find files containing the from_anno literal. Skip conventional test
      # directories — annotations live in production code; test annotations
      # mirror them but are not part of the kit's rewrite mandate (the
      # rewrite scope guarantee is "production source dirs"). Override via
      # --scan-dirs if a project keeps annotated tests under test/.
      mapfile -t hits < <(
        grep -rln --binary-files=without-match -F "$from_anno" "${scan_dirs[@]}" \
          --include='*.java' --include='*.py' --include='*.js' \
          --include='*.ts' --include='*.go' --include='*.rs' \
          --include='*.kt' --include='*.scala' --include='*.c' \
          --include='*.cpp' --include='*.h' --include='*.hpp' \
          --include='*.cs' --include='*.rb' --include='*.swift' \
          --include='*.m' --include='*.sh' \
          --exclude-dir='test' --exclude-dir='tests' \
          --exclude-dir='__tests__' --exclude-dir='spec' \
          --exclude-dir='node_modules' --exclude-dir='vendor' \
          --exclude-dir='target' --exclude-dir='build' --exclude-dir='dist' \
          2>/dev/null || true
      )

      for hit in "${hits[@]}"; do
        [[ -z "$hit" ]] && continue
        # Use sed with | delimiter (file paths may contain /).
        # Anchor: from_anno followed by a non-identifier character or EOL,
        # so we don't accidentally match `parent.R1` when intending `parent.R12`.
        sed -i -E "s|${from_anno}([^A-Za-z0-9])|${to_anno}\1|g; s|${from_anno}\$|${to_anno}|g" "$hit"
        ANNOTATION_REWRITES_FILE[$REWRITE_INDEX]="$hit"
        ANNOTATION_REWRITES_FROM[$REWRITE_INDEX]="$from_anno"
        ANNOTATION_REWRITES_TO[$REWRITE_INDEX]="$to_anno"
        REWRITE_INDEX=$((REWRITE_INDEX + 1))
        echo "  @spec rewrite: $hit  ($from_anno → $to_anno)" >&2
      done
    done
  done

  # Update rollback log with the rewrite list.
  emit_rollback_log
}

# ── Validation gate ──────────────────────────────────────────────────────────

validate_after_split() {
  echo "[split] Validating parent + children…" >&2
  local fail=0

  if ! bash "$SCRIPT_DIR/spec-validate.sh" "$PARENT_FILE" >/dev/null 2>&1; then
    bash "$SCRIPT_DIR/spec-validate.sh" "$PARENT_FILE" >&2 || true
    fail=1
  fi

  for ((i=0; i<CHILD_COUNT; i++)); do
    local child_id child_path
    child_id="$(jq -r ".children[$i].id" "$PLAN_FILE")"
    child_path="$(spec_file_for_id "$MANIFEST" "$child_id")"
    if [[ -z "$child_path" || ! -f "$child_path" ]]; then
      echo "FAIL: child $child_id not resolvable post-split" >&2
      fail=1
      continue
    fi
    if ! bash "$SCRIPT_DIR/spec-validate.sh" "$child_path" >/dev/null 2>&1; then
      bash "$SCRIPT_DIR/spec-validate.sh" "$child_path" >&2 || true
      fail=1
    fi
  done

  return $fail
}

# ── Main ─────────────────────────────────────────────────────────────────────

if ! execute_split; then
  echo "[split] Execution failed — replaying rollback…" >&2
  replay_rollback "$ROLLBACK_LOG" || true
  exit 1
fi

# Re-emit final rollback log with annotation rewrites included.
emit_rollback_log

if ! validate_after_split; then
  echo "[split] Post-split validation failed — replaying rollback…" >&2
  replay_rollback "$ROLLBACK_LOG"
  echo "[split] Rolled back; rollback log preserved at $ROLLBACK_LOG for inspection." >&2
  exit 1
fi

echo "[split] Done. Parent: $PARENT_ID  ·  Children: $CHILD_COUNT  ·  Cross-cutting reqs: ${#CROSS_CUTTING[@]}" >&2
echo "[split] Rollback log: $ROLLBACK_LOG (preserved for one release; safe to delete after)" >&2
