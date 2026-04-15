#!/usr/bin/env bash
# vallorcine narrative generation wrapper
# Detects available runtimes and delegates to the best implementation.
# Reports failures instead of swallowing them — narrative is required
# for pipeline observability (token data, phase durations, session counts).
#
# Priority: python3 → node
# Both implementations produce identical markdown output.
#
# Usage: narrative-wrapper.sh <slug> <feature-dir>
# Exit codes:
#   0 = narrative.md written successfully
#   1 = generation failed (stderr has details)
#   2 = no runtime available (python3 or node required)
#   3 = missing arguments

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

slug="${1:-}"
feature_dir="${2:-}"

if [[ -z "$slug" || -z "$feature_dir" ]]; then
    echo "narrative-wrapper: missing arguments (slug='$slug', feature_dir='$feature_dir')" >&2
    exit 3
fi

output_file="$feature_dir/narrative.md"

# _try_generate runtime command
# Attempts generation, returns 0 on success (file exists + non-empty)
_try_generate() {
    local runtime="$1" cmd="$2"
    if $cmd "$SCRIPT_DIR/narrative/generate.$3" "$slug" "$feature_dir" 2>/tmp/vallorcine-narrative-err.log; then
        if [[ -f "$output_file" && -s "$output_file" ]]; then
            return 0
        fi
        echo "narrative-wrapper: $runtime exited 0 but $output_file not written or empty" >&2
    else
        echo "narrative-wrapper: $runtime failed (exit $?)" >&2
    fi
    cat /tmp/vallorcine-narrative-err.log >&2 2>/dev/null
    return 1
}

# Determine which runtime to use
runtime=""
if command -v python3 &>/dev/null; then
    runtime="python3"
    ext="py"
elif command -v node &>/dev/null; then
    runtime="node"
    ext="js"
fi

if [[ -z "$runtime" ]]; then
    echo "narrative-wrapper: neither python3 nor node available — cannot generate narrative" >&2
    exit 2
fi

# First attempt
if _try_generate "$runtime" "$runtime" "$ext"; then
    exit 0
fi

# Retry once after 2s — covers the case where JSONL is still being flushed
# by the active Claude Code session (the retro runs inside the session that's
# writing the JSONL we need to read)
sleep 2
echo "narrative-wrapper: retrying after 2s (JSONL may have been mid-flush)" >&2
if _try_generate "$runtime" "$runtime" "$ext"; then
    exit 0
fi

# If primary runtime failed twice, try the other runtime once
if [[ "$runtime" == "python3" ]] && command -v node &>/dev/null; then
    echo "narrative-wrapper: falling back to node" >&2
    if _try_generate "node" "node" "js"; then
        exit 0
    fi
elif [[ "$runtime" == "node" ]] && command -v python3 &>/dev/null; then
    echo "narrative-wrapper: falling back to python3" >&2
    if _try_generate "python3" "python3" "py"; then
        exit 0
    fi
fi

exit 1

# No runtime available
echo "narrative-wrapper: neither python3 nor node available — cannot generate narrative" >&2
exit 2
