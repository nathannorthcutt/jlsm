#!/usr/bin/env bash
# spec-resolve.sh — deterministic context bundle builder
# Usage: spec-resolve.sh "<feature description>" [token-budget]
# Optional env: OVERRIDE_DOMAINS="storage,compaction"
# Optional env: EXPLICIT_SPEC_IDS="id1,id2" — bypass fuzzy match; use these IDs directly
# Optional env: NEW_SPEC_FILES="path1:path2" — draft specs to check for displacement
# Optional env: INCLUDE_INVALIDATED=true — include INVALIDATED specs in separate section
# Optional env: FILTER_KIND="interface-contract" — only include specs with this kind field
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
EXPLICIT_ID_MODE=false
CANDIDATE_FILES=()
CONFLICT_OMITTED=()

if [[ -n "${EXPLICIT_SPEC_IDS:-}" ]]; then
  # Caller passed spec IDs directly — bypass fuzzy match and domain inference.
  # Used by skills (e.g. /feature-test --specs flag, brief.md explicit list)
  # when the spec set is known and fuzzy matching would be unreliable.
  EXPLICIT_ID_MODE=true
  IFS=',' read -ra EXPLICIT_IDS <<< "$EXPLICIT_SPEC_IDS"
  echo "[resolve] Explicit spec IDs: ${EXPLICIT_IDS[*]}" >&2

  declare -A DOMAIN_SET
  for fid in "${EXPLICIT_IDS[@]}"; do
    fid="$(echo "$fid" | tr -d ' ')"
    [[ -z "$fid" ]] && continue
    spec_file=$(spec_file_for_id "$MANIFEST" "$fid")
    if [[ -z "$spec_file" || ! -f "$spec_file" ]]; then
      echo "[resolve] WARN: explicit spec '$fid' not in registry — skipping" >&2
      continue
    fi
    spec_check_crlf "$spec_file" 2>/dev/null || continue
    CANDIDATE_FILES+=("$spec_file")
    while IFS= read -r dom; do
      [[ -n "$dom" ]] && DOMAIN_SET["$dom"]=1
    done < <(fm "$spec_file" '.domains // [] | .[]')
  done
  MATCHED_DOMAINS=("${!DOMAIN_SET[@]}")
  [[ ${#MATCHED_DOMAINS[@]} -eq 0 ]] && MATCHED_DOMAINS=("unknown")
elif [[ -n "${OVERRIDE_DOMAINS:-}" ]]; then
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
# Skipped when EXPLICIT_SPEC_IDS populated CANDIDATE_FILES above.
mapfile -t ALL_FEATURE_IDS < <(jq -r '.features | keys[]' "$MANIFEST")

if [[ "$EXPLICIT_ID_MODE" == "true" ]]; then
  : # CANDIDATE_FILES already populated from explicit IDs; skip domain filter loop
else
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

  # Filter by kind if FILTER_KIND is set
  if [[ -n "${FILTER_KIND:-}" ]]; then
    spec_kind=$(fm "$spec_file" '.kind // ""')
    if [[ "$spec_kind" != "$FILTER_KIND" ]]; then
      continue
    fi
  fi

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
fi  # end: if EXPLICIT_ID_MODE else

# Collect INVALIDATED specs separately when requested (for revival detection)
INVALIDATED_FILES=()
if [[ "${INCLUDE_INVALIDATED:-}" == "true" ]]; then
  for fid in "${ALL_FEATURE_IDS[@]}"; do
    feature_domains=$(jq -r --arg id "$fid" '.features[$id].domains // [] | .[]' "$MANIFEST")
    matched=false
    for fd in $feature_domains; do
      for md in "${MATCHED_DOMAINS[@]}"; do
        [[ "$fd" == "$md" ]] && matched=true && break 2
      done
    done
    [[ "$matched" != "true" ]] && continue
    spec_file=$(spec_file_for_id "$MANIFEST" "$fid")
    [[ -z "$spec_file" || ! -f "$spec_file" ]] && continue
    spec_check_crlf "$spec_file" 2>/dev/null || continue
    state=$(fm "$spec_file" '.state // "UNKNOWN"')
    if [[ "$state" == "INVALIDATED" ]]; then
      INVALIDATED_FILES+=("$spec_file")
    fi
  done
  echo "[resolve] INVALIDATED specs found: ${#INVALIDATED_FILES[@]}" >&2
fi

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
    # Strip trailing .RN to get the target spec ID. Works for both
    # FXX.RN (→ FXX) and domain.slug.RN (→ domain.slug).
    target_id=$(echo "$inv_ref" | sed -E 's/\.R[0-9]+[a-z]?$//')
    [[ -z "$target_id" ]] && continue
    if [[ -n "${INCLUDED_IDS[$target_id]+x}" ]]; then
      CONFLICTS+="INVALIDATES: $src_id invalidates $inv_ref, but $target_id is also in this bundle"$'\n'
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
        # Only flag if from different specs (compare full spec IDs directly —
        # works for both FXX and domain.slug forms)
        [[ "$prev_id" == "$src_id" ]] && continue

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

# ── Step 7c: Displacement detection ─────────────────────────────────────────
# When NEW_SPEC_FILES is set, check if new specs' requirements contradict
# existing APPROVED specs. Detection is mechanical: subject-token overlap
# combined with antonym pairs or displacement signal keywords.

DISPLACEMENTS=""

if [[ -n "${NEW_SPEC_FILES:-}" ]]; then
  IFS=':' read -ra NEW_FILES <<< "$NEW_SPEC_FILES"

  # Displacement signal keywords (lowercased, checked in new spec requirements)
  DISPLACEMENT_KEYWORDS=(
    "only support" "replace" "remove" "eliminate" "drop support"
    "no longer" "prohibit" "must not support" "exclusive"
  )

  for new_file in "${NEW_FILES[@]}"; do
    [[ ! -f "$new_file" ]] && {
      echo "[resolve] Warning: NEW_SPEC_FILES entry not found: $new_file" >&2
      continue
    }
    new_id=$(fm "$new_file" '.id')
    [[ -z "$new_id" ]] && continue

    # Extract requirements from the new spec
    while IFS= read -r new_req_line; do
      [[ -z "$new_req_line" ]] && continue
      new_req_id=$(echo "$new_req_line" | grep -oE '^R[0-9]+' || true)
      [[ -z "$new_req_id" ]] && continue
      new_lower=$(echo "$new_req_line" | tr '[:upper:]' '[:lower:]')

      # Extract subject tokens from new requirement
      new_tokens=$(echo "$new_req_line" | grep -oE '[A-Z][a-zA-Z0-9]+|[a-z_][a-z_0-9]+' | sort -u)

      for existing_file in "${ALL_FILES[@]+"${ALL_FILES[@]}"}"; do
        existing_id=$(fm "$existing_file" '.id')
        [[ "$existing_id" == "$new_id" ]] && continue  # skip self

        while IFS= read -r existing_req_line; do
          [[ -z "$existing_req_line" ]] && continue
          existing_req_id=$(echo "$existing_req_line" | grep -oE '^R[0-9]+' || true)
          [[ -z "$existing_req_id" ]] && continue
          existing_lower=$(echo "$existing_req_line" | tr '[:upper:]' '[:lower:]')

          # Check for subject token overlap
          existing_tokens=$(echo "$existing_req_line" | grep -oE '[A-Z][a-zA-Z0-9]+|[a-z_][a-z_0-9]+' | sort -u)
          shared_token=""
          for nt in $new_tokens; do
            [[ ${#nt} -lt 4 ]] && continue
            case "$nt" in
              must|should|shall|will|when|then|that|this|with|from|into|each|have|does|been|also|only) continue ;;
            esac
            for et in $existing_tokens; do
              if [[ "$nt" == "$et" ]]; then
                shared_token="$nt"
                break 2
              fi
            done
          done
          [[ -z "$shared_token" ]] && continue

          # Check 1: antonym-pair contradictions (same 12 pairs as Step 7b)
          signal=""
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
            if echo "$new_lower" | grep -q "$pat_a" && echo "$existing_lower" | grep -q "$pat_b"; then
              signal="antonym: $pat_a vs $pat_b"
              break
            fi
          done

          # Check 2: displacement signal keywords in new spec
          if [[ -z "$signal" ]]; then
            for kw in "${DISPLACEMENT_KEYWORDS[@]}"; do
              if echo "$new_lower" | grep -q "$kw"; then
                signal="keyword: $kw"
                break
              fi
            done
          fi

          if [[ -n "$signal" ]]; then
            DISPLACEMENTS+="DISPLACED: ${new_id}.${new_req_id} → ${existing_id}.${existing_req_id} | subject: ${shared_token} | signal: ${signal}"$'\n'
          fi
        done < <(machine_section "$existing_file" | grep -E '^R[0-9]+\.')
      done
    done < <(machine_section "$new_file" | grep -E '^R[0-9]+\.')
  done

  if [[ -n "$DISPLACEMENTS" ]]; then
    disp_count=$(echo -n "$DISPLACEMENTS" | grep -c '.')
    echo "[resolve] WARNING: $disp_count displacement(s) detected" >&2
  fi
fi

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

# Emit displacement section if detected
if [[ -n "$DISPLACEMENTS" ]]; then
  echo ""
  echo "## Displacement"
  echo ""
  echo -n "$DISPLACEMENTS"
  disp_count=$(echo -n "$DISPLACEMENTS" | grep -c '.')
  echo "[resolve] WARNING: $disp_count displacement(s) in bundle output" >&2
fi

# Emit INVALIDATED specs section if requested and found
if [[ "${INCLUDE_INVALIDATED:-}" == "true" && ${#INVALIDATED_FILES[@]} -gt 0 ]]; then
  echo ""
  echo "## INVALIDATED Specs (historical reference)"
  echo ""
  for f in "${INVALIDATED_FILES[@]}"; do
    inv_id=$(fm "$f" '.id')
    inv_reason=$(fm "$f" '.displacement_reason // "no reason recorded"')
    inv_displaced_by=$(fm "$f" '.displaced_by // [] | join(", ")')
    echo "- $inv_id — displaced by: ${inv_displaced_by:-unknown} — reason: $inv_reason"
  done
fi

echo "[resolve] Done. ~$RUNNING_TOKENS tokens across ${#BUNDLE_PARTS[@]} specs." >&2
