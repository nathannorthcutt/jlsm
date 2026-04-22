#!/usr/bin/env bash
# audit-state-gate.sh — refuse to proceed when /audit targets a spec that is
# not APPROVED. A DRAFT or INVALIDATED spec has no authoritative contract, so
# conformance/implementation findings (Lens A SPEC-REQ) have nothing to check
# against. Only adversarial exploration would work — a misleading audit.
#
# Usage: audit-state-gate.sh <spec-id>
# Exit 0  → spec is APPROVED, audit may proceed.
# Exit 1  → spec is not APPROVED, manifest missing, or registry lookup failed.
#           A human-readable message is printed to stderr.
#
# Called by /audit for "spec:" entry points before the pipeline starts.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -z "${1:-}" ]]; then
  echo "Usage: audit-state-gate.sh <spec-id>" >&2
  exit 1
fi

SPEC_ID="$1"

if [[ ! -f "$SCRIPT_DIR/spec-lib.sh" ]]; then
  echo "ERROR: spec-lib.sh not found alongside audit-state-gate.sh" >&2
  exit 1
fi
source "$SCRIPT_DIR/spec-lib.sh"

# Locate project root by walking up to find .spec/
PROJECT_ROOT="$PWD"
while [[ "$PROJECT_ROOT" != "/" ]]; do
  [[ -d "$PROJECT_ROOT/.spec" ]] && break
  PROJECT_ROOT="$(dirname "$PROJECT_ROOT")"
done

MANIFEST="$PROJECT_ROOT/.spec/registry/manifest.json"
if [[ ! -f "$MANIFEST" ]]; then
  echo "ERROR: no .spec/registry/manifest.json — cannot verify spec '$SPEC_ID'" >&2
  echo "  /audit spec:<id> requires a registered APPROVED spec." >&2
  exit 1
fi

TARGET="$(spec_file_for_id "$MANIFEST" "$SPEC_ID")"
if [[ -z "$TARGET" || ! -f "$TARGET" ]]; then
  echo "ERROR: spec '$SPEC_ID' not found in registry" >&2
  echo "  Check .spec/registry/manifest.json or try /spec-resolve to confirm the ID." >&2
  exit 1
fi

STATE="$(fm "$TARGET" '.state // ""')"
if [[ -z "$STATE" ]]; then
  echo "ERROR: spec '$SPEC_ID' has no state field in front matter ($TARGET)" >&2
  exit 1
fi

if [[ "$STATE" != "APPROVED" ]]; then
  echo "ERROR: spec '$SPEC_ID' is $STATE — /audit requires APPROVED" >&2
  echo "" >&2
  echo "  A $STATE spec has no authoritative contract for conformance checking." >&2
  echo "  /audit on $STATE specs can only do adversarial exploration, which" >&2
  echo "  produces misleading findings (nothing to prove against)." >&2
  echo "" >&2
  echo "  Next step:" >&2
  if [[ "$STATE" == "DRAFT" ]]; then
    echo "    /spec-verify $SPEC_ID    # promote DRAFT → APPROVED via verify-and-repair" >&2
  elif [[ "$STATE" == "INVALIDATED" ]]; then
    echo "    Point /audit at the replacement spec instead." >&2
  else
    echo "    /spec-author $SPEC_ID    # author/re-verify the spec" >&2
  fi
  exit 1
fi

echo "OK: spec '$SPEC_ID' is APPROVED"
exit 0
