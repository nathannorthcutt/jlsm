#!/usr/bin/env bash
# spec-lib.sh — shared functions sourced by all spec scripts
# Source this file at the top of every script:
#   SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
#   source "$SCRIPT_DIR/spec-lib.sh"

# ── Dependency preflight ─────────────────────────────────────────────────────
spec_require_deps() {
  local missing=()
  for cmd in jq awk grep find sed; do
    command -v "$cmd" >/dev/null 2>&1 || missing+=("$cmd")
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    echo "ERROR: Missing required tools: ${missing[*]}" >&2
    echo "  Install jq:  brew install jq   (macOS)" >&2
    echo "               apt install jq    (Debian/Ubuntu)" >&2
    echo "               dnf install jq    (Fedora/RHEL)" >&2
    exit 1
  fi
}

# ── Locate .spec/ directory ──────────────────────────────────────────────────
# Walks up from CWD to find .spec/. Returns path or exits.
spec_find_root() {
  local dir="$PWD"
  while [[ "$dir" != "/" ]]; do
    if [[ -d "$dir/.spec" ]]; then
      echo "$dir/.spec"
      return 0
    fi
    dir="$(dirname "$dir")"
  done
  echo "ERROR: .spec/ directory not found. Run /spec-init first." >&2
  return 1
}

# ── CRLF detection ───────────────────────────────────────────────────────────
spec_check_crlf() {
  local file="$1"
  if awk '/\r/{found=1; exit} END{exit !found}' "$file" 2>/dev/null; then
    echo "ERROR: $file has Windows line endings (CRLF)." >&2
    echo "  Fix: sed -i 's/\\r//' \"$file\"" >&2
    return 1
  fi
  return 0
}

spec_normalize_crlf() {
  sed -i 's/\r//' "$1"
}

# ── Extract JSON front matter and query with jq ─────────────────────────────
# Lines strictly between first and second --- delimiters.
# Returns empty string on missing key — never errors.
fm() {
  local file="$1"
  local filter="${2:-.}"
  awk '/^---$/{n++; next} n==1{print} n>=2{exit}' "$file" \
    | jq -r "$filter" 2>/dev/null || true
}

# ── Extract machine section only (body between 2nd and 3rd ---) ──────────────
# Excludes the JSON front matter block — only the requirements body.
machine_section() {
  local file="$1"
  awk '
    /^---$/ {
      delim++
      if (delim == 3) { exit }
      next
    }
    delim == 2 { print }
  ' "$file"
}

# ── Token estimator: 1 token ~ 4 chars ──────────────────────────────────────
count_tokens() {
  echo $(( ${#1} / 4 ))
}

# ── Resolve a spec ID to file path via manifest registry ─────────────────────
# Supports both manifest schema versions:
#   v1 (legacy): {"features": {"F01": {"latest_file": "...", ...}}}
#   v2 (post-migration): {"schema_version": 2, "specs": [{"id": "...", "path": "..."}]}
#
# Resolution order:
#   1. Manifest lookup (exact ID match) — source of truth for any spec the
#      project has explicitly registered. Most specs are registered.
#   2. ID-computed path fallback — for nested specs whose parent is registered
#      but whose entry has not been added to the manifest yet (e.g. during a
#      /spec-split pilot). The path is deterministic from the ID:
#          a.b.c.d  →  domains/a/b/c/d.md
#      First component is the top-level domain dir; intermediate components
#      become subdirectories; last component is the filename.
#
# Returns absolute path or empty string.
spec_file_for_id() {
  local manifest="$1"
  local fid="$2"
  local spec_dir
  spec_dir="$(dirname "$(dirname "$manifest")")"
  local rel
  # Try v2 first (specs[] array with id field), fall back to v1 (features{} object).
  rel=$(jq -r --arg id "$fid" '
    if .specs then
      ((.specs[] | select(.id == $id) | .path) // "")
    else
      (.features[$id].latest_file // "")
    end
  ' "$manifest")
  if [[ -n "$rel" ]]; then
    # v2 manifest stores paths relative to the repo root (e.g., ".spec/domains/...").
    # v1 stores paths relative to the .spec/ directory. Detect which form by checking
    # if the path already starts with ".spec/" — if so, resolve against repo root.
    if [[ "$rel" == .spec/* ]]; then
      echo "$spec_dir/${rel#.spec/}"
    else
      echo "$spec_dir/$rel"
    fi
    return 0
  fi
  # ── Fallback: compute path from a hierarchical ID (a.b[.c[.d...]] → file).
  # Only applies to domain.slug-shaped IDs with at least one dot. Legacy FXX
  # IDs and bare names without dots cannot be resolved this way and return
  # empty (preserves existing behavior on misses).
  if [[ "$fid" == *.* && "$fid" != F[0-9]* ]]; then
    local computed
    # First component is the top-level domain (under domains/).
    # Remaining components map dots to slashes; last component gets `.md`.
    local top="${fid%%.*}"
    local rest="${fid#*.}"
    rest="${rest//./\/}"
    computed="$spec_dir/domains/$top/${rest}.md"
    if [[ -f "$computed" ]]; then
      echo "$computed"
      return 0
    fi
  fi
  echo ""
}

# ── Walk the parent_spec chain for a spec, emit each ancestor ID ─────────────
# Reads parent_spec from each spec's frontmatter (single string or null).
# Emits IDs from immediate parent up to the root. Stops at:
#   - empty/null parent_spec (root reached)
#   - cycle detection (an ID seen twice — emits a single error to stderr,
#     stops emitting further IDs but does NOT exit; callers can ignore or
#     surface as they wish)
#
# Args: <manifest> <spec-id>
# Output: parent IDs on stdout, one per line (closest first)
spec_walk_parent_chain() {
  local manifest="$1"
  local fid="$2"
  local seen=":$fid:"  # ":id1:id2:" — colon-bookended for safe substring match
  local depth=0
  while (( depth < 32 )); do  # hard depth cap as cycle backstop
    local file parent
    file=$(spec_file_for_id "$manifest" "$fid")
    [[ -z "$file" || ! -f "$file" ]] && return 0
    parent=$(fm "$file" '.parent_spec // ""')
    [[ -z "$parent" || "$parent" == "null" ]] && return 0
    # Cycle detection
    if [[ "$seen" == *":$parent:"* ]]; then
      echo "[spec-lib] ERROR: parent_spec cycle detected at $parent (chain: $seen)" >&2
      return 0
    fi
    echo "$parent"
    seen="$seen$parent:"
    fid="$parent"
    depth=$((depth + 1))
  done
  echo "[spec-lib] ERROR: parent_spec chain exceeded depth 32 (chain: $seen)" >&2
}

# ── List children of a spec by scanning the manifest ────────────────────────
# Computed at scan time (we don't store children_specs to avoid bidirectional
# sync bugs — see decisions-scan.sh:43-50 for the same precedent).
#
# Args: <manifest> <parent-id>
# Output: child IDs on stdout, one per line
spec_children_for() {
  local manifest="$1"
  local pid="$2"
  jq -r --arg pid "$pid" '
    if .specs then
      ((.specs[] | select(.parent_spec == $pid) | .id))
    else
      empty
    end
  ' "$manifest" 2>/dev/null
}

# ── Manifest query helpers (v1/v2 schema-agnostic) ───────────────────────────
# v1 schema: {"features": {"F01": {...}}, "domains": {"compression": {...}}}
# v2 schema: {"schema_version": 2, "specs": [{"id": "...", "domains": [...]}]}
# Every helper below detects the schema by probing for the `.specs` array.

# Emit every spec ID in the manifest, one per line.
spec_manifest_ids() {
  local manifest="$1"
  jq -r '
    if .specs then .specs[].id
    else (.features // {}) | keys[]
    end
  ' "$manifest" 2>/dev/null
}

# Emit the state for a single spec ID.
spec_manifest_state() {
  local manifest="$1" fid="$2"
  jq -r --arg id "$fid" '
    if .specs then ((.specs[] | select(.id == $id) | .state) // "")
    else (.features[$id].state // "")
    end
  ' "$manifest" 2>/dev/null
}

# Emit the domains for a single spec ID, one per line.
spec_manifest_domains_for() {
  local manifest="$1" fid="$2"
  jq -r --arg id "$fid" '
    if .specs then ((.specs[] | select(.id == $id) | .domains // []) | .[])
    else (.features[$id].domains // [] | .[])
    end
  ' "$manifest" 2>/dev/null
}

# Emit the unique set of all domains across the manifest, one per line.
spec_manifest_all_domains() {
  local manifest="$1"
  jq -r '
    if .specs then ([.specs[].domains[]] | unique | .[])
    else ((.domains // {}) | keys[])
    end
  ' "$manifest" 2>/dev/null
}

# Report whether this is a v2 manifest. Returns 0 (v2) or 1 (v1/unknown).
spec_manifest_is_v2() {
  local manifest="$1"
  [[ "$(jq -r 'has("specs")' "$manifest" 2>/dev/null)" == "true" ]]
}

# ── Update manifest registry entry atomically via temp file ──────────────────
# v1: latest_file relative to .spec/; schema is {"features": {fid: {...}}}.
# v2: path relative to repo root (".spec/domains/..."); schema is
#     {"schema_version": 2, "specs": [{"id": ..., "path": ..., ...}]}.
# Callers pass the v1-style latest_file; this function normalizes for v2.
spec_registry_update() {
  local manifest="$1" fid="$2" latest_file="$3" state="$4"
  local domains_json="${5:-[]}"
  local tmp
  tmp=$(mktemp)
  if spec_manifest_is_v2 "$manifest"; then
    # v2 stores paths relative to the repo root. Prepend .spec/ only if the
    # caller hasn't already included it (callers historically pass paths like
    # "domains/foo/F01-x.md").
    local v2_path="$latest_file"
    [[ "$v2_path" != .spec/* ]] && v2_path=".spec/$v2_path"
    jq --arg id "$fid" --arg p "$v2_path" --arg st "$state" \
      --argjson doms "$domains_json" \
      '
        .specs = (
          (.specs // [])
          | map(select(.id != $id))
          | . + [{
              "id": $id,
              "path": $p,
              "state": $st,
              "version": 1,
              "domains": $doms,
              "requires": [],
              "invalidates": [],
              "decision_refs": [],
              "kb_refs": []
            }]
        )
        | .spec_count = (.specs | length)
        | .generated_at = (now | todate)
      ' "$manifest" > "$tmp" && mv "$tmp" "$manifest"
  else
    jq --arg id "$fid" --arg lf "$latest_file" --arg st "$state" \
      --argjson doms "$domains_json" \
      '.features[$id] = {"latest_file": $lf, "state": $st, "domains": $doms}' \
      "$manifest" > "$tmp" && mv "$tmp" "$manifest"
  fi
  echo "[registry] $fid -> $latest_file ($state)" >&2
}

# ── Verify a spec requirement reference resolves to a real requirement ────────
# Accepts both legacy FXX.RN and new domain.slug.RN formats. Returns 0 if valid,
# 1 with error message if not.
spec_invalidates_check() {
  local manifest="$1" ref="$2"
  local sid req_num
  # Strip the trailing .RN[a] to get the spec ID (works for both forms).
  sid=$(echo "$ref" | sed -E 's/\.R[0-9]+[a-z]?$//')
  # Capture the trailing R-number (with optional letter suffix).
  req_num=$(echo "$ref" | grep -oE 'R[0-9]+[a-z]?$' || true)
  if [[ -z "$sid" || -z "$req_num" || "$sid" == "$ref" ]]; then
    echo "  FAIL invalidates '$ref': cannot parse as <spec-id>.RN (e.g. F01.R3 or schema.field-access.R3)" >&2
    return 1
  fi
  local target_file
  target_file=$(spec_file_for_id "$manifest" "$sid")
  if [[ -z "$target_file" || ! -f "$target_file" ]]; then
    echo "  FAIL invalidates '$ref': $sid not found in registry" >&2
    return 1
  fi
  if ! machine_section "$target_file" | grep -qE "^${req_num}\."; then
    echo "  FAIL invalidates '$ref': $req_num not found in $sid" >&2
    return 1
  fi
  return 0
}

# ── Validate decision_refs resolve to .decisions/ ADRs ───────────────────────
spec_decision_ref_check() {
  local project_root="$1" ref="$2"
  if [[ ! -f "$project_root/.decisions/$ref/adr.md" ]]; then
    echo "  WARN decision_ref '$ref': .decisions/$ref/adr.md not found" >&2
    return 1
  fi
  return 0
}

# ── Validate kb_refs resolve to .kb/ entries ─────────────────────────────────
spec_kb_ref_check() {
  local project_root="$1" ref="$2"
  if [[ ! -f "$project_root/.kb/$ref.md" ]]; then
    echo "  WARN kb_ref '$ref': .kb/$ref.md not found" >&2
    return 1
  fi
  return 0
}
