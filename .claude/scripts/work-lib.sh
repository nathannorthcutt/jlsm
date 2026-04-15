#!/usr/bin/env bash
# work-lib.sh — shared functions sourced by all work layer scripts
# Source this file at the top of every script:
#   SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
#   source "$SCRIPT_DIR/work-lib.sh"

# ── Dependency preflight ─────────────────────────────────────────────────────
work_require_deps() {
  local missing=()
  for cmd in awk grep find sed; do
    command -v "$cmd" >/dev/null 2>&1 || missing+=("$cmd")
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    echo "ERROR: Missing required tools: ${missing[*]}" >&2
    exit 1
  fi
}

# ── Locate .work/ directory ──────────────────────────────────────────────────
# Walks up from CWD to find .work/. Returns path or exits.
work_find_root() {
  local dir="$PWD"
  while [[ "$dir" != "/" ]]; do
    if [[ -d "$dir/.work" ]]; then
      echo "$dir/.work"
      return 0
    fi
    dir="$(dirname "$dir")"
  done
  echo "ERROR: .work/ directory not found. Run /work first." >&2
  return 1
}

# ── Locate project root (parent of .work/) ──────────────────────────────────
work_project_root() {
  local work_dir
  work_dir="$(work_find_root)" || return 1
  dirname "$work_dir"
}

# ── Extract YAML front matter value ─────────────────────────────────────────
# Reads lines between first and second --- delimiters.
# Extracts a simple key: value pair (single-line scalars only).
# Usage: work_fm <file> <key>
work_fm() {
  local file="$1"
  local key="$2"
  awk -v k="$key" '
    /^---$/ { n++; next }
    n == 1 && $0 ~ "^" k ":" {
      sub("^" k ":[ ]*", "")
      # Strip surrounding quotes
      gsub(/^["'"'"']|["'"'"']$/, "")
      print
      exit
    }
    n >= 2 { exit }
  ' "$file"
}

# ── Extract YAML front matter array values ───────────────────────────────────
# Handles both inline [a, b] and multi-line - item formats.
# Usage: work_fm_array <file> <key>
# Output: one value per line
work_fm_array() {
  local file="$1"
  local key="$2"
  awk -v k="$key" '
    /^---$/ { n++; next }
    n >= 2 { exit }
    n == 1 {
      # Match the key line
      if ($0 ~ "^" k ":") {
        line = $0
        sub("^" k ":[ ]*", "", line)
        # Inline array: [a, b, c]
        if (line ~ /^\[/) {
          gsub(/[\[\]]/, "", line)
          split(line, items, ",")
          for (i in items) {
            gsub(/^[ ]+|[ ]+$/, "", items[i])
            gsub(/^["'"'"']|["'"'"']$/, "", items[i])
            if (items[i] != "") print items[i]
          }
          next
        }
        # Non-empty scalar on same line — single-element
        if (line != "") {
          gsub(/^["'"'"']|["'"'"']$/, "", line)
          print line
          next
        }
        # Empty — look for multi-line items
        in_array = 1
        next
      }
      # Multi-line array items
      if (in_array) {
        if ($0 ~ /^  - /) {
          item = $0
          sub(/^  - [ ]*/, "", item)
          gsub(/^["'"'"']|["'"'"']$/, "", item)
          print item
        } else {
          in_array = 0
        }
      }
    }
  ' "$file"
}

# ── Extract artifact_deps as structured records ──────────────────────────────
# Handles the YAML list of {type, path/slug, ...} objects.
# Output: one line per dep in format: type|path_or_slug|required_state|kind
# Usage: work_fm_artifact_deps <file>
work_fm_artifact_deps() {
  local file="$1"
  awk '
    /^---$/ { n++; next }
    n >= 2 { exit }
    n == 1 {
      if ($0 ~ /^artifact_deps:/) { in_deps = 1; next }
      if (in_deps) {
        # New list item starts with "  - {"
        if ($0 ~ /^  - \{/) {
          line = $0
          sub(/^  - \{[ ]*/, "", line)
          sub(/\}[ ]*$/, "", line)
          # Parse key: value pairs
          dep_type = ""; dep_path = ""; dep_slug = ""; req_state = ""; kind = ""
          n_fields = split(line, fields, ",")
          for (i = 1; i <= n_fields; i++) {
            gsub(/^[ ]+|[ ]+$/, "", fields[i])
            split(fields[i], kv, ":")
            gsub(/^[ ]+|[ ]+$/, "", kv[1])
            val = ""
            for (j = 2; j <= length(kv); j++) {
              if (j > 2) val = val ":"
              val = val kv[j]
            }
            gsub(/^[ ]+|[ ]+$/, "", val)
            gsub(/^["'"'"']|["'"'"']$/, "", val)
            if (kv[1] == "type") dep_type = val
            else if (kv[1] == "path") dep_path = val
            else if (kv[1] == "slug") dep_slug = val
            else if (kv[1] == "required_state") req_state = val
            else if (kv[1] == "required_status") req_state = val
            else if (kv[1] == "kind") kind = val
          }
          ref = (dep_path != "") ? dep_path : dep_slug
          print dep_type "|" ref "|" req_state "|" kind
        } else if ($0 !~ /^  /) {
          in_deps = 0
        }
      }
    }
  ' "$file"
}

# ── Extract produces as structured records ───────────────────────────────────
# Same format as artifact_deps output: type|path|state|kind
work_fm_produces() {
  local file="$1"
  awk '
    /^---$/ { n++; next }
    n >= 2 { exit }
    n == 1 {
      if ($0 ~ /^produces:/) { in_prod = 1; next }
      if (in_prod) {
        if ($0 ~ /^  - \{/) {
          line = $0
          sub(/^  - \{[ ]*/, "", line)
          sub(/\}[ ]*$/, "", line)
          dep_type = ""; dep_path = ""; kind = ""
          n_fields = split(line, fields, ",")
          for (i = 1; i <= n_fields; i++) {
            gsub(/^[ ]+|[ ]+$/, "", fields[i])
            split(fields[i], kv, ":")
            gsub(/^[ ]+|[ ]+$/, "", kv[1])
            val = ""
            for (j = 2; j <= length(kv); j++) {
              if (j > 2) val = val ":"
              val = val kv[j]
            }
            gsub(/^[ ]+|[ ]+$/, "", val)
            gsub(/^["'"'"']|["'"'"']$/, "", val)
            if (kv[1] == "type") dep_type = val
            else if (kv[1] == "path") dep_path = val
            else if (kv[1] == "kind") kind = val
          }
          print dep_type "|" dep_path "||" kind
        } else if ($0 !~ /^  /) {
          in_prod = 0
        }
      }
    }
  ' "$file"
}

# ── List all work definition files in a group ────────────────────────────────
# Returns absolute paths, one per line.
work_list_wds() {
  local group_dir="$1"
  find "$group_dir" -maxdepth 1 -name 'WD-*.md' -type f | sort
}

# ── List all group directories ───────────────────────────────────────────────
work_list_groups() {
  local work_dir="$1"
  find "$work_dir" -mindepth 1 -maxdepth 1 -type d \
    ! -name '_archive' ! -name '_refs' | sort
}

# ── Check if a spec artifact exists and is in required state ─────────────────
# Returns 0 if met, 1 if not. Writes reason to stdout on failure.
work_check_spec_dep() {
  local project_root="$1"
  local spec_path="$2"       # e.g., "auth/jwt-token-contract"
  local required_state="$3"  # e.g., "APPROVED"
  local manifest="$project_root/.spec/registry/manifest.json"

  if [[ ! -f "$manifest" ]]; then
    echo "spec manifest not found"
    return 1
  fi

  # Search by multiple matching strategies against manifest features
  local found_file=""
  local found_state=""

  # Extract domain and name from spec_path (e.g., "auth/jwt-token-contract")
  local spec_domain="${spec_path%%/*}"
  local spec_name="${spec_path##*/}"

  while IFS= read -r fid; do
    local latest
    latest=$(jq -r --arg id "$fid" '.features[$id].latest_file // ""' "$manifest")
    [[ -z "$latest" ]] && continue

    local match=false

    # Strategy 1: exact substring match
    [[ "$latest" == *"$spec_path"* ]] && match=true

    # Strategy 2: domain directory match + filename contains spec_name
    if [[ "$match" != "true" ]]; then
      local latest_basename="${latest##*/}"
      latest_basename="${latest_basename%.md}"  # strip .md
      if [[ "$latest" == *"/$spec_domain/"* && "$latest_basename" == *"$spec_name"* ]]; then
        match=true
      fi
    fi

    # Strategy 3: spec_name is a suffix of the filename (handles F01- prefix)
    if [[ "$match" != "true" ]]; then
      local latest_basename="${latest##*/}"
      latest_basename="${latest_basename%.md}"
      if [[ "$latest_basename" == *"-$spec_name" || "$latest_basename" == "$spec_name" ]]; then
        match=true
      fi
    fi

    if [[ "$match" == "true" ]]; then
      found_file="$project_root/.spec/$latest"
      if [[ -f "$found_file" ]]; then
        found_state=$(awk '/^---$/{n++; next} n==1{print} n>=2{exit}' "$found_file" \
          | jq -r '.state // ""' 2>/dev/null || true)
      fi
      break
    fi
  done < <(jq -r '.features | keys[]' "$manifest" 2>/dev/null)

  if [[ -z "$found_file" || ! -f "$found_file" ]]; then
    echo "spec not found: $spec_path"
    return 1
  fi

  if [[ -n "$required_state" && "$found_state" != "$required_state" ]]; then
    echo "spec $spec_path is $found_state (need $required_state)"
    return 1
  fi

  return 0
}

# ── Check if an ADR artifact exists and is in required status ────────────────
work_check_adr_dep() {
  local project_root="$1"
  local slug="$2"
  local required_status="$3"
  local adr_file="$project_root/.decisions/$slug/adr.md"

  if [[ ! -f "$adr_file" ]]; then
    echo "ADR not found: $slug"
    return 1
  fi

  if [[ -n "$required_status" ]]; then
    local actual_status
    actual_status=$(awk '/^---$/{n++; next} n==1 && /^status:/{sub(/^status:[ ]*/, ""); print; exit} n>=2{exit}' "$adr_file")
    if [[ "$actual_status" != "$required_status" ]]; then
      echo "ADR $slug is $actual_status (need $required_status)"
      return 1
    fi
  fi

  return 0
}

# ── Check if a KB entry exists ───────────────────────────────────────────────
work_check_kb_dep() {
  local project_root="$1"
  local kb_path="$2"

  if [[ ! -f "$project_root/.kb/$kb_path.md" ]]; then
    echo "KB entry not found: $kb_path"
    return 1
  fi

  return 0
}
