#!/usr/bin/env bash
# spec-obligations-gc.sh — resolve obligations when target reaches APPROVED
# Usage: spec-obligations-gc.sh
# Exit 0 always

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/spec-lib.sh"

spec_require_deps

SPEC_DIR="$(spec_find_root)" || exit 0
MANIFEST="$SPEC_DIR/registry/manifest.json"
OBLIGATIONS_FILE="$SPEC_DIR/registry/_obligations.json"

[[ ! -f "$MANIFEST" ]] && { echo "ERROR: manifest not found" >&2; exit 0; }
[[ ! -f "$OBLIGATIONS_FILE" ]] && { echo "ERROR: obligations file not found" >&2; exit 0; }

# ── Build state map: {id: state} for all specs
STATES_FILE=$(mktemp)
trap 'rm -f "$STATES_FILE"' EXIT

echo '{}' > "$STATES_FILE"

while IFS= read -r -d '' f; do
  spec_check_crlf "$f" 2>/dev/null || continue
  fid=$(fm "$f" '.id // ""')
  state=$(fm "$f" '.state // ""')
  [[ -z "$fid" || -z "$state" ]] && continue

  tmp=$(mktemp)
  jq --arg id "$fid" --arg st "$state" '. + {($id): $st}' "$STATES_FILE" > "$tmp" \
    && mv "$tmp" "$STATES_FILE"
done < <(find "$SPEC_DIR/domains" -name "*.md" ! -name "INDEX.md" -print0 2>/dev/null)

echo "[gc] State map built for $(jq 'keys | length' "$STATES_FILE") specs" >&2

# ── Resolve obligations whose target feature is now APPROVED
OPEN_BEFORE=$(jq '[.obligations[] | select(.status=="open")] | length' "$OBLIGATIONS_FILE" 2>/dev/null || echo 0)

tmp=$(mktemp)
jq --slurpfile states "$STATES_FILE" '
  .obligations |= map(
    if .status == "open" and ($states[0][.target_feature] == "APPROVED")
    then .status = "resolved"
    else .
    end
  )
' "$OBLIGATIONS_FILE" > "$tmp" && mv "$tmp" "$OBLIGATIONS_FILE"

OPEN_AFTER=$(jq '[.obligations[] | select(.status=="open")] | length' "$OBLIGATIONS_FILE" 2>/dev/null || echo 0)
RESOLVED=$(( OPEN_BEFORE - OPEN_AFTER ))

if (( RESOLVED > 0 )); then
  echo "[gc] Resolved $RESOLVED obligation(s). Open: $OPEN_AFTER remaining."
else
  echo "[gc] No obligations resolved. Open: $OPEN_AFTER."
fi

exit 0
