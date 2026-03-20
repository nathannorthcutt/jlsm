#!/usr/bin/env bash
# vallorcine SubagentStart/SubagentStop hook wrapper
# Detects available runtimes and delegates to the best implementation.
# Falls back silently on errors — always delegates to bash as last resort.
#
# Priority: python3 → node → bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
input=$(cat)

# Try Python
if command -v python3 &>/dev/null; then
    echo "$input" | python3 "$SCRIPT_DIR/subagent-hook.py" 2>/dev/null
    if [[ $? -eq 0 ]]; then
        exit 0
    fi
fi

# Try Node.js
if command -v node &>/dev/null; then
    echo "$input" | node "$SCRIPT_DIR/subagent-hook.js" 2>/dev/null
    if [[ $? -eq 0 ]]; then
        exit 0
    fi
fi

# Fallback: bash
echo "$input" | bash "$SCRIPT_DIR/subagent-hook.sh"
