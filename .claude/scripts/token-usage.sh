#!/usr/bin/env bash
# vallorcine token usage tracker
# Tracks token consumption per pipeline phase by reading Claude Code session JSONL.
#
# Usage (sourced by pipeline commands — not called directly):
#   source .claude/scripts/token-usage.sh
#   token_checkpoint ".feature/<slug>" "scoping"    # mark phase start
#   token_summary ".feature/<slug>" "scoping"       # mark phase end, append to log
#   token_report ".feature/<slug>"                   # display full token log with totals
#   token_phase_banner "scoping" ".feature/<slug>"   # display phase usage in completion banner
#
# Requires: jq (gracefully degrades if not installed)
# Zero token cost — runs as shell outside Claude's context window.
# Performance: ~200ms per call on a typical session file.

# ── jq availability check ────────────────────────────────────────────────────

_TOKEN_JQ_AVAILABLE=0
if command -v jq &>/dev/null; then
    _TOKEN_JQ_AVAILABLE=1
fi

# ── Find current session transcript ──────────────────────────────────────────

_find_transcript() {
    local project_dir
    project_dir="$(pwd)"
    local project_id
    project_id=$(echo "$project_dir" | sed 's|^/||; s|/|-|g')
    local session_dir="$HOME/.claude/projects/-${project_id}"

    if [[ ! -d "$session_dir" ]]; then
        echo ""
        return
    fi

    ls -t "$session_dir"/*.jsonl 2>/dev/null | head -1
}

# ── Sum usage from a range of lines ──────────────────────────────────────────

_sum_usage() {
    local transcript="$1"
    local start_line="$2"

    if [[ -z "$transcript" || ! -f "$transcript" ]]; then
        echo '{"input":0,"output":0,"cache_create":0,"cache_read":0,"messages":0}'
        return
    fi

    # Only count completed assistant messages (stop_reason != null)
    # to avoid double-counting streaming progress updates
    tail -n +"$start_line" "$transcript" | jq -s '
        [.[] | select(.type == "assistant" and .message.stop_reason != null
               and .message.usage.input_tokens > 0) | .message.usage]
        | {
            input: (map(.input_tokens) | add // 0),
            output: (map(.output_tokens) | add // 0),
            cache_create: (map(.cache_creation_input_tokens) | add // 0),
            cache_read: (map(.cache_read_input_tokens) | add // 0),
            messages: length
          }
    ' 2>/dev/null || echo '{"input":0,"output":0,"cache_create":0,"cache_read":0,"messages":0}'
}

# ── Format token count for display (e.g. 1234567 → "1.2M") ──────────────────

_fmt_tokens() {
    local n="$1"
    [[ "$n" =~ ^[0-9]+$ ]] || { echo "${n:-0}"; return; }
    if [[ "$n" -ge 1000000 ]]; then
        local whole=$(( n / 1000000 ))
        local frac=$(( (n % 1000000) / 100000 ))
        echo "${whole}.${frac}M"
    elif [[ "$n" -ge 1000 ]]; then
        local whole=$(( n / 1000 ))
        local frac=$(( (n % 1000) / 100 ))
        echo "${whole}.${frac}K"
    else
        echo "$n"
    fi
}

# ── Public API ───────────────────────────────────────────────────────────────

# Mark the start of a pipeline phase.
token_checkpoint() {
    local feature_dir="$1"
    local phase="$2"

    if [[ "$_TOKEN_JQ_AVAILABLE" != "1" ]]; then return 0; fi

    local transcript
    transcript="$(_find_transcript)"
    if [[ -z "$transcript" ]]; then return 0; fi

    local line_count
    line_count="$(wc -l < "$transcript")"

    mkdir -p "$feature_dir"
    printf 'transcript=%q\nstart_line=%s\nphase=%q\ntimestamp=%s\n' \
        "$transcript" "$((line_count + 1))" "$phase" \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$feature_dir/.token-checkpoint.tmp" \
        && mv "$feature_dir/.token-checkpoint.tmp" "$feature_dir/.token-checkpoint"
}

# End a pipeline phase, append usage to token-log.md, return summary string.
token_summary() {
    local feature_dir="$1"
    local phase="$2"

    if [[ "$_TOKEN_JQ_AVAILABLE" != "1" ]]; then
        echo "unknown"
        return 0
    fi

    local checkpoint_file="$feature_dir/.token-checkpoint"
    if [[ ! -f "$checkpoint_file" ]]; then
        echo "unknown"
        return 0
    fi

    local transcript start_line cp_timestamp
    transcript="$(grep '^transcript=' "$checkpoint_file" 2>/dev/null | cut -d= -f2- || true)"
    start_line="$(grep '^start_line=' "$checkpoint_file" 2>/dev/null | cut -d= -f2- || true)"
    cp_timestamp="$(grep '^timestamp=' "$checkpoint_file" 2>/dev/null | cut -d= -f2- || true)"

    if [[ -z "$transcript" || ! -f "$transcript" ]]; then
        echo "unknown"
        return 0
    fi

    local usage
    usage="$(_sum_usage "$transcript" "$start_line")"

    local input output cache_create cache_read messages
    input="$(echo "$usage" | jq -r '.input')"
    output="$(echo "$usage" | jq -r '.output')"
    cache_create="$(echo "$usage" | jq -r '.cache_create')"
    cache_read="$(echo "$usage" | jq -r '.cache_read')"
    messages="$(echo "$usage" | jq -r '.messages')"

    local end_timestamp
    end_timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

    # Append to token log
    local log_file="$feature_dir/token-log.md"
    if [[ ! -f "$log_file" ]]; then
        cat > "$log_file" <<'HEADER'
# Token Usage Log

| Phase | Messages | Input | Output | Cache Create | Cache Read | Started | Ended |
|-------|----------|-------|--------|--------------|------------|---------|-------|
HEADER
    fi

    echo "| $phase | $messages | $input | $output | $cache_create | $cache_read | $cp_timestamp | $end_timestamp |" >> "$log_file"

    rm -f "$checkpoint_file"

    # Return compact summary for embedding in banners
    echo "$(_fmt_tokens "$input") in · $(_fmt_tokens "$output") out · $(_fmt_tokens "$cache_read") cached · ${messages} calls"
}

# Display the full token log with computed totals.
token_report() {
    local feature_dir="$1"

    local log_file="$feature_dir/token-log.md"
    if [[ ! -f "$log_file" ]]; then
        echo "No token usage data recorded yet."
        return 0
    fi

    if [[ "$_TOKEN_JQ_AVAILABLE" != "1" ]]; then
        cat "$log_file"
        return 0
    fi

    cat "$log_file"

    # Compute totals from the table rows (skip header and separator lines)
    local total_messages=0 total_input=0 total_output=0 total_cache_create=0 total_cache_read=0
    while IFS='|' read -r _ phase messages input output cache_create cache_read _ _; do
        # Skip header, separator, and blank lines
        [[ "$phase" =~ ^[[:space:]]*Phase || "$phase" =~ ^-+ || -z "$phase" ]] && continue
        messages="$(echo "$messages" | tr -d ' ')"
        input="$(echo "$input" | tr -d ' ')"
        output="$(echo "$output" | tr -d ' ')"
        cache_create="$(echo "$cache_create" | tr -d ' ')"
        cache_read="$(echo "$cache_read" | tr -d ' ')"
        # Skip non-numeric (safety)
        [[ "$messages" =~ ^[0-9]+$ ]] || continue
        total_messages=$((total_messages + messages))
        total_input=$((total_input + input))
        total_output=$((total_output + output))
        total_cache_create=$((total_cache_create + cache_create))
        total_cache_read=$((total_cache_read + cache_read))
    done < "$log_file"

    echo "| **TOTAL** | **$total_messages** | **$total_input** | **$total_output** | **$total_cache_create** | **$total_cache_read** | | |"
    echo ""
    echo "Totals: $(_fmt_tokens "$total_input") input · $(_fmt_tokens "$total_output") output · $(_fmt_tokens "$total_cache_create") cache-create · $(_fmt_tokens "$total_cache_read") cache-read · ${total_messages} API calls"
}

# Format a one-line token usage string for embedding in completion banners.
# Call AFTER token_summary. Reads the last row of the log.
token_phase_banner() {
    local phase="$1"
    local feature_dir="$2"

    if [[ "$_TOKEN_JQ_AVAILABLE" != "1" ]]; then
        echo "  Tokens : unavailable (jq not installed)"
        return 0
    fi

    local log_file="$feature_dir/token-log.md"
    if [[ ! -f "$log_file" ]]; then
        echo "  Tokens : no data"
        return 0
    fi

    # Read last data row
    local last_row
    last_row="$(tail -1 "$log_file")"
    [[ "$last_row" =~ ^\| ]] || { echo "  Tokens : no data"; return 0; }

    local input output cache_read messages
    input="$(echo "$last_row" | awk -F'|' '{print $4}' | tr -d ' ')"
    output="$(echo "$last_row" | awk -F'|' '{print $5}' | tr -d ' ')"
    cache_read="$(echo "$last_row" | awk -F'|' '{print $7}' | tr -d ' ')"
    messages="$(echo "$last_row" | awk -F'|' '{print $3}' | tr -d ' ')"

    echo "  Tokens : $(_fmt_tokens "$input") in · $(_fmt_tokens "$output") out · $(_fmt_tokens "$cache_read") cached · ${messages} calls"
}

# Get current session totals (not phase-specific).
token_session_total() {
    if [[ "$_TOKEN_JQ_AVAILABLE" != "1" ]]; then
        echo "Token tracking unavailable (jq not installed)"
        return 0
    fi

    local transcript
    transcript="$(_find_transcript)"
    if [[ -z "$transcript" ]]; then
        echo "No session transcript found"
        return 0
    fi

    local usage
    usage="$(_sum_usage "$transcript" "1")"

    local input output cache_create cache_read messages
    input="$(echo "$usage" | jq -r '.input')"
    output="$(echo "$usage" | jq -r '.output')"
    cache_create="$(echo "$usage" | jq -r '.cache_create')"
    cache_read="$(echo "$usage" | jq -r '.cache_read')"
    messages="$(echo "$usage" | jq -r '.messages')"

    echo "Session: $(_fmt_tokens "$input") in · $(_fmt_tokens "$output") out · $(_fmt_tokens "$cache_create") cache-create · $(_fmt_tokens "$cache_read") cached · ${messages} calls"
}
