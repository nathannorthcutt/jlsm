#!/usr/bin/env bash
# vallorcine token Stop hook wrapper
# Detects available runtimes and delegates to the best implementation.
# Falls back silently on errors — always delegates to bash as last resort.
#
# Priority: python3 → node → bash
# All implementations produce identical side effects (state files, token-log.md).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
input=$(cat)

# Try Python (best: native JSON/JSONL parsing, no jq dependency)
if command -v python3 &>/dev/null; then
    if echo "$input" | python3 "$SCRIPT_DIR/token-stop-hook.py" 2>/dev/null; then
        exit 0
    fi
fi

# Try Node.js
if command -v node &>/dev/null; then
    if echo "$input" | node "$SCRIPT_DIR/token-stop-hook.js" 2>/dev/null; then
        exit 0
    fi
fi

# Fallback: bash (requires jq for full functionality)
echo "$input" | bash "$SCRIPT_DIR/token-stop-hook.sh"
exit 0
