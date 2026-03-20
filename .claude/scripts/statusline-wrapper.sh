#!/usr/bin/env bash
# vallorcine status line wrapper
# Detects available runtimes and delegates to the best implementation.
# Falls back silently on errors — always delegates to bash as last resort.
#
# Priority: python3 → node → bash
# All implementations produce identical ANSI output.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
input=$(cat)

# Try Python (best: native JSON parsing, subagent state)
if command -v python3 &>/dev/null; then
    output=$(echo "$input" | python3 "$SCRIPT_DIR/statusline.py" 2>/dev/null)
    if [[ $? -eq 0 ]]; then
        [[ -n "$output" ]] && echo -e "$output"
        exit 0
    fi
fi

# Try Node.js
if command -v node &>/dev/null; then
    output=$(echo "$input" | node "$SCRIPT_DIR/statusline.js" 2>/dev/null)
    if [[ $? -eq 0 ]]; then
        [[ -n "$output" ]] && echo -e "$output"
        exit 0
    fi
fi

# Fallback: bash (always available)
echo "$input" | bash "$SCRIPT_DIR/statusline.sh"
