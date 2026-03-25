#!/usr/bin/env bash
# vallorcine narrative generation wrapper
# Detects available runtimes and delegates to the best implementation.
# If no runtime is available, exits silently — narrative is optional.
#
# Priority: python3 → node → silent exit
# Both implementations produce identical markdown output.
#
# Usage: narrative-wrapper.sh <slug> <feature-dir>

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

slug="$1"
feature_dir="$2"

if [[ -z "$slug" || -z "$feature_dir" ]]; then
    exit 0
fi

# Try Python (best: native JSON parsing, streaming JSONL)
if command -v python3 &>/dev/null; then
    python3 "$SCRIPT_DIR/narrative/generate.py" "$slug" "$feature_dir" 2>/dev/null
    exit 0
fi

# Try Node.js
if command -v node &>/dev/null; then
    node "$SCRIPT_DIR/narrative/generate.js" "$slug" "$feature_dir" 2>/dev/null
    exit 0
fi

# No runtime available — silent exit, narrative is an enhancement
exit 0
