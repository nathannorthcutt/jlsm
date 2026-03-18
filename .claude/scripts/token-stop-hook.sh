#!/usr/bin/env bash
# vallorcine token tracking Stop hook
# Fires on every Claude response. Detects pipeline stage transitions and logs
# token usage automatically. No skill-level bash calls needed.
#
# Performance:
#   No-op path (~1ms): 2 stat() calls, exit.
#   Active, same stage (~5ms): read 2 small files, compare, exit.
#   Stage transition (~200ms): jq on transcript range, write log. ~6x per feature.
#
# Requires: jq (gracefully exits if not installed)

cat > /dev/null  # consume stdin (required by Stop hooks)

STATE_FILE=".claude/.token-state"

# ── Fast bail: no state file and no feature directory ────────────────────────
if [[ ! -f "$STATE_FILE" && ! -d .feature ]]; then
    exit 0
fi

command -v jq &>/dev/null || exit 0
[[ -f .claude/scripts/token-usage.sh ]] || exit 0

# ── Tracking active: check for stage transition ─────────────────────────────
if [[ -f "$STATE_FILE" ]]; then
    feature_dir=""
    cached_stage=""
    transcript=""
    start_line=""
    timestamp=""
    source "$STATE_FILE" 2>/dev/null || { rm -f "$STATE_FILE"; exit 0; }

    [[ -n "$feature_dir" && -f "$feature_dir/status.md" ]] || { rm -f "$STATE_FILE"; exit 0; }

    # Read current stage from status.md
    current_stage=$(grep -m1 '^\*\*Stage:\*\*' "$feature_dir/status.md" 2>/dev/null \
        | sed 's/\*\*Stage:\*\* *//' | tr -d '[:space:]')

    # Check for terminal states — clean up tracking
    substage=$(grep -m1 '^\*\*Substage:\*\*' "$feature_dir/status.md" 2>/dev/null \
        | sed 's/\*\*Substage:\*\* *//' | tr -d '[:space:]')
    case "$current_stage/$substage" in
        pr/created|pr/complete)
            rm -f "$STATE_FILE"
            exit 0
            ;;
    esac

    # Same stage = no-op
    [[ -n "$current_stage" && "$current_stage" != "$cached_stage" ]] || exit 0

    # ── Stage transition detected ────────────────────────────────────────────
    source .claude/scripts/token-usage.sh 2>/dev/null || exit 0

    # Verify transcript is from current session
    current_transcript="$(_find_transcript)"
    if [[ "$current_transcript" != "$transcript" || ! -f "$transcript" ]]; then
        # New session — can't compute delta for old stage, reset
        transcript="$current_transcript"
        start_line=1
    fi

    if [[ -n "$transcript" && -f "$transcript" ]]; then
        usage="$(_sum_usage "$transcript" "$start_line")"

        read -r input output cache_create cache_read messages < <(
            echo "$usage" | jq -r '[.input, .output, .cache_create, .cache_read, .messages] | @tsv'
        )

        # Append to token log
        log_file="$feature_dir/token-log.md"
        if [[ ! -f "$log_file" ]]; then
            cat > "$log_file" <<'HEADER'
# Token Usage Log

| Phase | Messages | Input | Output | Cache Create | Cache Read | Started | Ended |
|-------|----------|-------|--------|--------------|------------|---------|-------|
HEADER
        fi

        echo "| $cached_stage | $messages | $input | $output | $cache_create | $cache_read | ${timestamp:-unknown} | $(date -u +%Y-%m-%dT%H:%M:%SZ) |" >> "$log_file"

        # Update Actual Tokens column in status.md Stage Completion table
        if [[ -f "$feature_dir/status.md" ]]; then
            actual_str="$(_fmt_tokens "$input") in / $(_fmt_tokens "$output") out"
            # Capitalize stage name to match table format (scoping → Scoping)
            stage_cap="$(echo "$cached_stage" | sed 's/^\(.\)/\U\1/')"
            # Replace 6th pipe-delimited field (Actual Tokens) in the matching row
            awk -v stage="$stage_cap" -v actual="$actual_str" '
                BEGIN { FS="|"; OFS="|" }
                {
                    f2 = $2; gsub(/^[ \t]+|[ \t]+$/, "", f2)
                    if (f2 == stage) {
                        $6 = " " actual " "
                    }
                    print
                }
            ' "$feature_dir/status.md" > "$feature_dir/status.md.tmp" && \
                mv "$feature_dir/status.md.tmp" "$feature_dir/status.md"
        fi

        # Update state for new stage
        current_line=$(wc -l < "$transcript" 2>/dev/null || echo "1")
        printf 'feature_dir=%q\ncached_stage=%q\ntranscript=%q\nstart_line=%s\ntimestamp=%s\n' \
            "$feature_dir" "$current_stage" "$transcript" "$((current_line + 1))" \
            "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$STATE_FILE"
    fi

    exit 0
fi

# ── Cold start: look for active features ─────────────────────────────────────
source .claude/scripts/token-usage.sh 2>/dev/null || exit 0

for status_file in .feature/*/status.md; do
    [[ -f "$status_file" ]] || continue

    stage=$(grep -m1 '^\*\*Stage:\*\*' "$status_file" 2>/dev/null \
        | sed 's/\*\*Stage:\*\* *//' | tr -d '[:space:]')
    [[ -n "$stage" ]] || continue

    substage=$(grep -m1 '^\*\*Substage:\*\*' "$status_file" 2>/dev/null \
        | sed 's/\*\*Substage:\*\* *//' | tr -d '[:space:]')

    # Skip completed features (pr/complete, pr/created)
    case "$stage/$substage" in
        pr/complete|pr/created) continue ;;
    esac

    # Found an active feature — start tracking
    feature_dir=$(dirname "$status_file")
    transcript="$(_find_transcript)"
    [[ -n "$transcript" ]] || exit 0

    current_line=$(wc -l < "$transcript" 2>/dev/null || echo "1")

    mkdir -p .claude
    printf 'feature_dir=%q\ncached_stage=%q\ntranscript=%q\nstart_line=%s\ntimestamp=%s\n' \
        "$feature_dir" "$stage" "$transcript" "$((current_line + 1))" \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$STATE_FILE"

    exit 0
done

exit 0
