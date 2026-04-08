#!/usr/bin/env bash
# spec-resolve.sh — deterministic context bundle builder
# Usage: spec-resolve.sh "<feature description>" [token-budget]
# Optional env: OVERRIDE_DOMAINS="storage,compaction"
# Output: markdown bundle on stdout | diagnostics on stderr

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/spec-lib.sh"

spec_require_deps

FEATURE_DESC="${1:-}"
TOKEN_BUDGET="${2:-8000}"
[[ -z "$FEATURE_DESC" ]] && {
  echo "Usage: spec-resolve.sh '<feature description>' [token-budget]" >&2
  exit 1
}

SPEC_DIR="$(spec_find_root)" || exit 1
PROJECT_ROOT="$(dirname "$SPEC_DIR")"
MANIFEST="$SPEC_DIR/registry/manifest.json"

[[ ! -f "$MANIFEST" ]] && {
  echo "ERROR: manifest not found at $MANIFEST — run /spec-init first" >&2
  exit 1
}

# ── Step 1: Determine matched domains ────────────────────────────────────────
if [[ -n "${OVERRIDE_DOMAINS:-}" ]]; then
  IFS=',' read -ra MATCHED_DOMAINS <<< "$OVERRIDE_DOMAINS"
  echo "[resolve] Domain override: ${MATCHED_DOMAINS[*]}" >&2
else
  mapfile -t ALL_DOMAINS < <(jq -r '.domains | keys[]' "$MANIFEST")
  desc_lower=$(echo "$FEATURE_DESC" | tr '[:upper:]' '[:lower:]')
  MATCHED_DOMAINS=()
  for domain in "${ALL_DOMAINS[@]}"; do
    domain_desc=$(jq -r --arg d "$domain" '.domains[$d].description // ""' "$MANIFEST" \
      | tr '[:upper:]' '[:lower:]')
    # Match if feature desc contains domain name OR any keyword in domain desc
    if echo "$desc_lower" | grep -qw "$domain"; then
      MATCHED_DOMAINS+=("$domain")
    else
      for word in $domain_desc; do
        if [[ ${#word} -gt 4 ]] && echo "$desc_lower" | grep -qw "$word"; then
          MATCHED_DOMAINS+=("$domain")
          break
        fi
      done
    fi
  done
fi

# Emit partial bundle for domain inference if no match
if [[ ${#MATCHED_DOMAINS[@]} -eq 0 ]]; then
  echo "NEEDS_DOMAIN_INFERENCE=true" >&2
  echo "# Partial Bundle — Domain Inference Required"
  echo "Feature request: $FEATURE_DESC"
  echo ""
  echo "## Available Domains"
  jq -r '.domains | to_entries[] | "- \(.key): \(.value.description)"' "$MANIFEST"
  exit 0
fi

echo "[resolve] Domains matched: ${MATCHED_DOMAINS[*]}" >&2

# ── Step 2: Collect candidate spec files via registry ────────────────────────
CANDIDATE_FILES=()
CONFLICT_OMITTED=()
mapfile -t ALL_FEATURE_IDS < <(jq -r '.features | keys[]' "$MANIFEST")

for fid in "${ALL_FEATURE_IDS[@]}"; do
  # Check if this feature belongs to any matched domain
  feature_domains=$(jq -r --arg id "$fid" '.features[$id].domains // [] | .[]' "$MANIFEST")
  matched=false
  for fd in $feature_domains; do
    for md in "${MATCHED_DOMAINS[@]}"; do
      [[ "$fd" == "$md" ]] && matched=true && break 2
    done
  done
  [[ "$matched" != "true" ]] && continue

  # Resolve file path via registry
  spec_file=$(spec_file_for_id "$MANIFEST" "$fid")
  [[ -z "$spec_file" || ! -f "$spec_file" ]] && {
    echo "[resolve] Warning: $fid has no resolvable file, skipping" >&2
    continue
  }

  spec_check_crlf "$spec_file" 2>/dev/null || continue

  # Only include APPROVED or ACTIVE states; DRAFT only if no unresolved conflicts
  state=$(fm "$spec_file" '.state // "UNKNOWN"')
  status=$(fm "$spec_file" '.status // "UNKNOWN"')
  if [[ "$state" == "APPROVED" || "$status" == "ACTIVE" ]]; then
    CANDIDATE_FILES+=("$spec_file")
  elif [[ "$state" == "DRAFT" ]]; then
    # Check for unresolved conflict markers before including a DRAFT spec
    has_conflicts=false
    if grep -qE '\[UNRESOLVED\]' "$spec_file" 2>/dev/null; then
      has_conflicts=true
    elif grep -qE '\[CONFLICT\]' "$spec_file" 2>/dev/null; then
      has_conflicts=true
    elif [[ "$(fm "$spec_file" '.open_obligations | length // 0')" != "0" ]]; then
      has_conflicts=true
    fi
    if [[ "$has_conflicts" == "true" ]]; then
      CONFLICT_OMITTED+=("$fid")
      echo "[resolve] Excluding $fid — DRAFT with unresolved conflicts" >&2
    else
      CANDIDATE_FILES+=("$spec_file")
    fi
  fi
done

echo "[resolve] Candidates: ${#CANDIDATE_FILES[@]} files" >&2

# ── Step 3: Sort by direct domain match first ────────────────────────────────
# No relevance_weight — direct matches before transitive requires
if [[ ${#CANDIDATE_FILES[@]} -gt 0 ]]; then
    SORTED_FILES=("${CANDIDATE_FILES[@]}")
else
    SORTED_FILES=()
fi

# ── Step 4: Expand transitive requires[], deduplicate ────────────────────────
declare -A SEEN_FILES
ALL_FILES=()

add_file() {
  local f="$1"
  [[ -n "${SEEN_FILES[$f]+x}" ]] && return
  SEEN_FILES["$f"]=1
  ALL_FILES+=("$f")
}

for f in "${SORTED_FILES[@]+"${SORTED_FILES[@]}"}"; do
  add_file "$f"
  while IFS= read -r req_id; do
    [[ -z "$req_id" ]] && continue
    req_file=$(spec_file_for_id "$MANIFEST" "$req_id")
    if [[ -n "$req_file" && -f "$req_file" ]]; then
      spec_check_crlf "$req_file" 2>/dev/null && add_file "$req_file"
    else
      echo "[resolve] Warning: requires $req_id has no resolvable file" >&2
    fi
  done < <(fm "$f" '.requires // [] | .[]')
done

# ── Step 5: Build bundle with token budget ───────────────────────────────────
BUNDLE_PARTS=()
OMITTED=()
RUNNING_TOKENS=0
HEADER_RESERVE=300

for f in "${ALL_FILES[@]+"${ALL_FILES[@]}"}"; do
  section=$(machine_section "$f")
  spec_id=$(fm "$f" '.id')

  section_tokens=$(count_tokens "$section")
  if (( RUNNING_TOKENS + section_tokens + HEADER_RESERVE > TOKEN_BUDGET )); then
    OMITTED+=("$spec_id")
    echo "[resolve] Over budget — omitting $spec_id (~$section_tokens tokens)" >&2
    continue
  fi
  BUNDLE_PARTS+=("$section")
  RUNNING_TOKENS=$(( RUNNING_TOKENS + section_tokens ))
done

# ── Step 6: Load open obligations — domain-filtered ──────────────────────────
OBLIGATIONS_FILE="$SPEC_DIR/registry/_obligations.json"
MATCHED_DOMAINS_JSON=$(printf '%s\n' "${MATCHED_DOMAINS[@]}" | jq -R . | jq -sc .)
OBLIGATIONS=$(jq -r --argjson domains "$MATCHED_DOMAINS_JSON" '
  .obligations[]
  | select(.status == "open")
  | select(.domains[] as $d | $domains | index($d) != null)
  | "- [" + (.domains | join(", ")) + "] " + .description
    + " (target: " + .target_feature + ")"
' "$OBLIGATIONS_FILE" 2>/dev/null || echo "none")

# ── Step 7: Collect cross-references (decision_refs + kb_refs) ───────────────
CROSS_REFS=""
for f in "${ALL_FILES[@]+"${ALL_FILES[@]}"}"; do
  while IFS= read -r ref; do
    [[ -z "$ref" ]] && continue
    CROSS_REFS+="- ADR: .decisions/$ref/adr.md"$'\n'
  done < <(fm "$f" '.decision_refs // [] | .[]')
  while IFS= read -r ref; do
    [[ -z "$ref" ]] && continue
    CROSS_REFS+="- KB: .kb/$ref.md"$'\n'
  done < <(fm "$f" '.kb_refs // [] | .[]')
done

# ── Step 7b: Conflict detection ─────────────────────────────────────────────
# Check for invalidates cross-references and overlapping requirement subjects
# between included specs. Structural checks only — no semantic analysis.

CONFLICTS=""

# Build a map of included spec IDs for fast lookup
declare -A INCLUDED_IDS
for f in "${ALL_FILES[@]+"${ALL_FILES[@]}"}"; do
  sid=$(fm "$f" '.id')
  [[ -n "$sid" ]] && INCLUDED_IDS["$sid"]=1
done

# Check 1: invalidates cross-references within the bundle
for f in "${ALL_FILES[@]+"${ALL_FILES[@]}"}"; do
  src_id=$(fm "$f" '.id')
  while IFS= read -r inv_ref; do
    [[ -z "$inv_ref" ]] && continue
    # Extract the feature ID from FXX.RN format
    target_fid=$(echo "$inv_ref" | grep -oE '^F[0-9]+' || true)
    [[ -z "$target_fid" ]] && continue
    if [[ -n "${INCLUDED_IDS[$target_fid]+x}" ]]; then
      CONFLICTS+="INVALIDATES: $src_id invalidates $inv_ref, but $target_fid is also in this bundle"$'\n'
    fi
  done < <(fm "$f" '.invalidates // [] | .[]')
done

# Check 2: overlapping requirement subjects with contradictory language
# Extract requirement lines from each spec and look for same-subject contradictions
declare -A REQ_SUBJECTS  # key=subject_token, value="SPEC_ID.REQ_LINE"

for f in "${ALL_FILES[@]+"${ALL_FILES[@]}"}"; do
  src_id=$(fm "$f" '.id')
  while IFS= read -r req_line; do
    [[ -z "$req_line" ]] && continue
    # Extract requirement ID (e.g., R1, R56)
    req_id=$(echo "$req_line" | grep -oE '^R[0-9]+' || true)
    [[ -z "$req_id" ]] && continue
    req_lower=$(echo "$req_line" | tr '[:upper:]' '[:lower:]')

    # Extract subject tokens: words that look like type/construct names (CamelCase or snake_case)
    subject_tokens=$(echo "$req_line" | grep -oE '[A-Z][a-zA-Z0-9]+|[a-z_][a-z_0-9]+' | sort -u)
    for token in $subject_tokens; do
      # Skip common English words and short tokens
      [[ ${#token} -lt 4 ]] && continue
      case "$token" in
        must|should|shall|will|when|then|that|this|with|from|into|each|have|does|been|also|only) continue ;;
      esac

      if [[ -n "${REQ_SUBJECTS[$token]+x}" ]]; then
        prev="${REQ_SUBJECTS[$token]}"
        prev_id="${prev%%|*}"
        prev_line="${prev#*|}"
        # Only flag if from different specs
        prev_spec=$(echo "$prev_id" | grep -oE '^F[0-9]+' || true)
        [[ "$prev_spec" == "$src_id" ]] && continue

        prev_lower=$(echo "$prev_line" | tr '[:upper:]' '[:lower:]')
        # Check for contradictory language patterns
        contradiction=false
        for pair in "must reject:must accept" "must accept:must reject" \
                    "must be null:must not be null" "must not be null:must be null" \
                    "must throw:must not throw" "must not throw:must throw" \
                    "must fail:must succeed" "must succeed:must fail" \
                    "must ignore:must require" "must require:must ignore" \
                    "is immutable:is mutable" "is mutable:is immutable" \
                    "must return null:must not return null" "must not return null:must return null" \
                    "must be empty:must not be empty" "must not be empty:must be empty"; do
          pat_a="${pair%%:*}"
          pat_b="${pair#*:}"
          if echo "$req_lower" | grep -q "$pat_a" && echo "$prev_lower" | grep -q "$pat_b"; then
            contradiction=true
            break
          fi
        done

        if [[ "$contradiction" == "true" ]]; then
          CONFLICTS+="CONFLICT: $prev_id references $token; $src_id.$req_id also references $token with different semantics"$'\n'
        fi
      else
        REQ_SUBJECTS["$token"]="$src_id.$req_id|$req_line"
      fi
    done
  done < <(machine_section "$f" | grep -E '^R[0-9]+\.')
done

# ── Step 8: Emit bundle ─────────────────────────────────────────────────────
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
if [[ ${#OMITTED[@]} -gt 0 ]]; then
    OMITTED_STR="${OMITTED[*]}"
else
    OMITTED_STR="none"
fi
if [[ ${#CONFLICT_OMITTED[@]} -gt 0 ]]; then
    CONFLICT_OMITTED_STR="${CONFLICT_OMITTED[*]}"
else
    CONFLICT_OMITTED_STR="none"
fi

cat <<EOF
# Resolved Context Bundle
Generated: $TIMESTAMP
Feature request: $FEATURE_DESC
Domains matched: ${MATCHED_DOMAINS[*]}
Token budget: $TOKEN_BUDGET | Tokens used: ~$RUNNING_TOKENS
Omitted (budget): $OMITTED_STR
Omitted (DRAFT with unresolved conflicts): $CONFLICT_OMITTED_STR

## Open Obligations (must be addressed in this feature)
${OBLIGATIONS:-none}

## Feature Requirements
$(if [[ ${#BUNDLE_PARTS[@]} -gt 0 ]]; then printf '%s\n\n---\n\n' "${BUNDLE_PARTS[@]}" | head -c -6; else echo "none"; fi)

## Cross-References
${CROSS_REFS:-none}
EOF

# Emit conflicts section only if conflicts were found
if [[ -n "$CONFLICTS" ]]; then
  echo ""
  echo "## Conflicts"
  echo ""
  echo -n "$CONFLICTS"
  conflict_count=$(echo -n "$CONFLICTS" | grep -c '.')
  echo "[resolve] WARNING: $conflict_count conflict(s) detected in bundle" >&2
fi

echo "[resolve] Done. ~$RUNNING_TOKENS tokens across ${#BUNDLE_PARTS[@]} specs." >&2
