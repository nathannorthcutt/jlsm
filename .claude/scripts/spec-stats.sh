#!/usr/bin/env bash
# spec-stats.sh — corpus health summary (~200 tokens)
# Usage: spec-stats.sh
# Exit 0 always — safe for preflight checks

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/spec-lib.sh"

spec_require_deps

SPEC_DIR="$(spec_find_root 2>/dev/null)" || {
  echo "# Spec Corpus Health"
  echo "Not initialized. Run /spec-init to bootstrap."
  exit 0
}

MANIFEST="$SPEC_DIR/registry/manifest.json"
OBLIGATIONS="$SPEC_DIR/registry/_obligations.json"

TOTAL=0; APPROVED=0; DRAFT=0; INVALIDATED=0

while IFS= read -r -d '' f; do
  TOTAL=$(( TOTAL + 1 ))
  state=$(fm "$f" '.state // "UNKNOWN"')
  case "$state" in
    APPROVED)    APPROVED=$(( APPROVED + 1 )) ;;
    DRAFT)       DRAFT=$(( DRAFT + 1 )) ;;
    INVALIDATED) INVALIDATED=$(( INVALIDATED + 1 )) ;;
  esac
done < <(find "$SPEC_DIR/domains" -name "*.md" ! -name "INDEX.md" -print0 2>/dev/null)

OPEN_OBLIGATIONS=$(jq '[.obligations[] | select(.status=="open")] | length' \
  "$OBLIGATIONS" 2>/dev/null || echo 0)
DOMAINS=$(spec_manifest_all_domains "$MANIFEST" 2>/dev/null | grep -c . || echo 0)

cat <<EOF
# Spec Corpus Health
Specs: $TOTAL total | $APPROVED approved | $DRAFT draft | $INVALIDATED invalidated
Domains: $DOMAINS | Open obligations: $OPEN_OBLIGATIONS
EOF

exit 0
