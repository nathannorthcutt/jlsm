#!/usr/bin/env bash
# vallorcine audit budget tracker
#
# Returns the running API cost in dollars for the current session's subagents.
# Called by the prove-fix orchestrator after each finding to check budget.
#
# Usage:
#   bash .claude/scripts/audit-budget.sh
#   # Output: single line, e.g. "247.50"
#
# Pricing (Opus):
#   Input:        $15.00 / 1M tokens
#   Output:       $75.00 / 1M tokens
#   Cache create: $18.75 / 1M tokens
#   Cache read:    $1.50 / 1M tokens
#
# Performance: <2s with 130 subagent files (grep+awk, no full file loads).
# Always exits 0 — errors produce "0.00".

set -euo pipefail

# ── Find current session's subagent directory ────────────────────────────────

_find_subagent_dir() {
    local project_dir
    project_dir="$(pwd)"
    local project_id
    project_id=$(echo "$project_dir" | sed 's|^/||; s|/|-|g')
    local session_base="$HOME/.claude/projects/-${project_id}"

    if [[ ! -d "$session_base" ]]; then
        echo ""
        return
    fi

    # Find the most recent session directory that has a subagents/ folder
    # with at least 5 JSONL files (audit sessions have many subagents)
    local best_dir=""
    local best_mtime=0

    for session_dir in "$session_base"/*/; do
        [[ -d "${session_dir}subagents" ]] || continue
        local count
        count=$(find "${session_dir}subagents" -name '*.jsonl' -maxdepth 1 2>/dev/null | wc -l)
        [[ "$count" -ge 5 ]] || continue

        # Use the directory's modification time to find the most recent
        local mtime
        mtime=$(stat -c %Y "${session_dir}subagents" 2>/dev/null || echo 0)
        if [[ "$mtime" -gt "$best_mtime" ]]; then
            best_mtime="$mtime"
            best_dir="${session_dir}subagents"
        fi
    done

    echo "$best_dir"
}

# ── Sum costs across all subagent JSONL files ────────────────────────────────

_sum_costs_jq() {
    local subagent_dir="$1"

    # jq processes each file, extracting usage objects from assistant messages
    # and computing cost. Final awk sums across all files.
    find "$subagent_dir" -name '*.jsonl' -maxdepth 1 2>/dev/null | while IFS= read -r f; do
        grep '"usage"' "$f" 2>/dev/null | jq -r '
            select(.message.usage.input_tokens != null) |
            .message.usage |
            ((.input_tokens // 0) * 15 / 1000000) +
            ((.output_tokens // 0) * 75 / 1000000) +
            ((.cache_creation_input_tokens // 0) * 18.75 / 1000000) +
            ((.cache_read_input_tokens // 0) * 1.5 / 1000000)
        ' 2>/dev/null || true
    done | awk '{s+=$1} END {printf "%.2f\n", s}'
}

_sum_costs_awk() {
    local subagent_dir="$1"

    # Fallback: grep for token fields and compute with awk
    # Extracts individual token count fields and computes cost per line
    find "$subagent_dir" -name '*.jsonl' -maxdepth 1 2>/dev/null | while IFS= read -r f; do
        grep '"usage"' "$f" 2>/dev/null || true
    done | grep -oP '"(input_tokens|output_tokens|cache_creation_input_tokens|cache_read_input_tokens)"\s*:\s*\d+' \
         | awk -F: '
        {
            gsub(/[" ]/, "", $1)
            val = $2 + 0
            if ($1 == "input_tokens") total += val * 15 / 1000000
            else if ($1 == "output_tokens") total += val * 75 / 1000000
            else if ($1 == "cache_creation_input_tokens") total += val * 18.75 / 1000000
            else if ($1 == "cache_read_input_tokens") total += val * 1.5 / 1000000
        }
        END { printf "%.2f\n", total }
    '
}

# ── Main ─────────────────────────────────────────────────────────────────────

main() {
    local subagent_dir
    subagent_dir="$(_find_subagent_dir)"

    if [[ -z "$subagent_dir" || ! -d "$subagent_dir" ]]; then
        echo "0.00"
        exit 0
    fi

    # Use jq if available, fall back to awk
    if command -v jq &>/dev/null; then
        _sum_costs_jq "$subagent_dir"
    else
        _sum_costs_awk "$subagent_dir"
    fi
}

main "$@" 2>/dev/null || echo "0.00"
exit 0
