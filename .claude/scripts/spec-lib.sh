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

# ── Resolve feature ID to file path via manifest registry ────────────────────
# Never uses grep on file contents. Returns absolute path or empty string.
spec_file_for_id() {
  local manifest="$1"
  local fid="$2"
  local spec_dir
  spec_dir="$(dirname "$(dirname "$manifest")")"
  local rel
  rel=$(jq -r --arg id "$fid" '.features[$id].latest_file // ""' "$manifest")
  [[ -z "$rel" ]] && echo "" && return 0
  echo "$spec_dir/$rel"
}

# ── Update manifest registry entry atomically via temp file ──────────────────
# latest_file is relative to .spec/ directory.
spec_registry_update() {
  local manifest="$1" fid="$2" latest_file="$3" state="$4"
  local domains_json="${5:-[]}"
  local tmp
  tmp=$(mktemp)
  jq --arg id "$fid" --arg lf "$latest_file" --arg st "$state" \
    --argjson doms "$domains_json" \
    '.features[$id] = {"latest_file": $lf, "state": $st, "domains": $doms}' \
    "$manifest" > "$tmp" && mv "$tmp" "$manifest"
  echo "[registry] $fid -> $latest_file ($state)" >&2
}

# ── Verify FXX.RN reference resolves to a real requirement ───────────────────
# Returns 0 if valid, 1 with error message if not.
spec_invalidates_check() {
  local manifest="$1" ref="$2"
  local fid req_num
  fid=$(echo "$ref" | grep -oE '^F[0-9]+' || true)
  req_num=$(echo "$ref" | grep -oE 'R[0-9]+$' || true)
  if [[ -z "$fid" || -z "$req_num" ]]; then
    echo "  FAIL invalidates '$ref': cannot parse as FXX.RN" >&2
    return 1
  fi
  local target_file
  target_file=$(spec_file_for_id "$manifest" "$fid")
  if [[ -z "$target_file" || ! -f "$target_file" ]]; then
    echo "  FAIL invalidates '$ref': $fid not found in registry" >&2
    return 1
  fi
  if ! machine_section "$target_file" | grep -qE "^${req_num}\."; then
    echo "  FAIL invalidates '$ref': $req_num not found in $fid" >&2
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
