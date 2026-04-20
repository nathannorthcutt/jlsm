#!/usr/bin/env bash
# vallorcine reconcile-cards wrapper
# Detects available runtimes and delegates to the best implementation.
# Falls back silently on errors — always delegates to bash as last resort.
#
# Priority: python3 → node → bash
# All implementations produce identical side effects (analysis-cards.yaml).

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if command -v python3 &>/dev/null; then
    exec python3 "$DIR/reconcile-cards.py" "$@"
elif command -v node &>/dev/null; then
    exec node "$DIR/reconcile-cards.js" "$@"
else
    exec bash "$DIR/reconcile-cards.sh" "$@"
fi
