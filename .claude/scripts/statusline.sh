#!/usr/bin/env bash
# vallorcine status line script
# Displays feature pipeline stage, per-stage token usage, and context % in Claude Code's status line.
# Also detects stage transitions and logs per-stage token usage to token-log.md.
#
# HOW IT WORKS:
# Claude Code fires this script after each assistant message AND between tool
# calls within a response. Each pipeline agent updates status.md as its first
# action (idempotency pre-flight). This script reads the actual Stage from
# status.md — not from .token-state (which only updates when the Stop hook
# fires). This means stage transitions are detected in real-time even during
# chained sub-agent execution where the Stop hook never runs.
#
# On stage transition: logs the completed stage's context tokens to token-log.md
# and resets the baseline for the new stage. Per-stage tokens are derived from
# context_window.used_percentage * context_window_size (from session JSON).
#
# State files (JSON format, backwards-compatible with shell variable format):
#   .claude/.token-state          — {"feature_dir":"...","cached_stage":"...",...}
#   .claude/.statusline-baseline  — {"baseline_stage":"...","baseline_ctx_tokens":N,...}
#   .claude/.subagent-state       — {"active":true,"agent_id":"...","description":"..."}
#   .feature/<slug>/token-log.md  — per-stage token log (appended here)
#
# Performance: <10ms typical (reads 2-3 small files + stdin JSON).
# Stage transition path adds one file write (~6 times per feature).
# Zero token cost — runs locally by Claude Code.

input=$(cat)

# ── JSON state file helpers ───────────────────────────────────────────────────
# Reads a value from a flat JSON object without jq.
# Usage: _json_val '{"key":"value"}' "key" → value
_json_val() {
    local json="$1" key="$2"
    echo "$json" | grep -o "\"$key\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -1 | sed 's/.*:.*"\([^"]*\)"/\1/'
}

# Read a numeric value from flat JSON (unquoted numbers)
_json_num() {
    local json="$1" key="$2"
    echo "$json" | grep -o "\"$key\"[[:space:]]*:[[:space:]]*[0-9]*" | head -1 | grep -o '[0-9]*$'
}

# Detect whether a file is JSON (starts with {) or shell variables (has = without {)
_is_json() {
    [[ -f "$1" ]] || return 1
    local first_char
    first_char=$(head -c1 "$1" 2>/dev/null)
    [[ "$first_char" == "{" ]]
}

# Read .token-state — works with both JSON and legacy shell variable format
_read_token_state() {
    local file="$1"
    feature_dir=""
    cached_stage=""
    if _is_json "$file"; then
        local content
        content=$(cat "$file" 2>/dev/null)
        feature_dir=$(_json_val "$content" "feature_dir")
        cached_stage=$(_json_val "$content" "cached_stage")
    else
        # Legacy shell variable format
        source "$file" 2>/dev/null
    fi
}

# Read .statusline-baseline — works with both JSON and legacy shell variable format
_read_baseline() {
    local file="$1"
    baseline_stage=""
    baseline_ctx_tokens=""
    baseline_timestamp=""
    if _is_json "$file"; then
        local content
        content=$(cat "$file" 2>/dev/null)
        baseline_stage=$(_json_val "$content" "baseline_stage")
        baseline_ctx_tokens=$(_json_num "$content" "baseline_ctx_tokens")
        baseline_timestamp=$(_json_val "$content" "baseline_timestamp")
    else
        # Legacy shell variable format
        source "$file" 2>/dev/null
    fi
}

# ── Extract session info from stdin JSON ─────────────────────────────────────

context_pct=""
ctx_size=""
if command -v jq &>/dev/null; then
    read -r context_pct ctx_size < <(
        echo "$input" | jq -r '[(.context_window.used_percentage // empty), (.context_window.context_window_size // empty)] | @tsv' 2>/dev/null
    )
fi

# ── Format token count (e.g. 1234567 → "1.2M") ─────────────────────────────

fmt_tokens() {
    local n="$1"
    [[ "$n" =~ ^[0-9]+$ ]] || return
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

# ── Compute current context tokens ───────────────────────────────────────────

current_ctx_tokens=""
if [[ "$ctx_size" =~ ^[0-9]+$ && "$context_pct" =~ ^[0-9.]+$ ]]; then
    current_ctx_tokens=$(awk "BEGIN { printf \"%d\", $context_pct * $ctx_size / 100 }")
fi

# ── Read pipeline state ──────────────────────────────────────────────────────

stage_display=""
stage_tokens=""
BASELINE_FILE=".claude/.statusline-baseline"

if [[ -f .claude/.token-state ]]; then
    _read_token_state .claude/.token-state

    if [[ -n "$feature_dir" && -f "$feature_dir/status.md" ]]; then
        slug=$(basename "$feature_dir")

        # Read ACTUAL stage from status.md (not cached_stage from .token-state)
        # This detects transitions during chained sub-agent execution
        actual_stage=$(grep -m1 '^\*\*Stage:\*\*' "$feature_dir/status.md" 2>/dev/null \
            | sed 's/\*\*Stage:\*\* *//' | tr -d '[:space:]')
        substage=$(grep -m1 '^\*\*Substage:\*\*' "$feature_dir/status.md" 2>/dev/null \
            | sed 's/\*\*Substage:\*\* *//' | tr -d '[:space:]')

        # Use actual stage for display (falls back to cached if status.md unreadable)
        current_stage="${actual_stage:-$cached_stage}"

        # Terminal states — feature is done, don't show stale stage
        is_terminal=0
        case "$current_stage/$substage" in
            pr/created|pr/complete) is_terminal=1 ;;
        esac

        if [[ "$is_terminal" == "0" ]]; then
            # Build stage display with substage detail
            sub=""
            case "$current_stage" in
                scoping)
                    case "$substage" in
                        interviewing)     sub="interviewing" ;;
                        confirming-brief) sub="confirming brief" ;;
                        complete)         sub="complete" ;;
                    esac
                    stage_display="$slug · scoping${sub:+ · $sub}" ;;
                domains)
                    stage_display="$slug · domains${substage:+ · $substage}" ;;
                planning)
                    case "$substage" in
                        loading-context)    sub="loading context" ;;
                        surveying-codebase) sub="surveying code" ;;
                        confirmed-design)   sub="design confirmed" ;;
                        writing-stubs)      sub="writing stubs" ;;
                        contract-revised)   sub="contract revised" ;;
                    esac
                    stage_display="$slug · planning${sub:+ · $sub}" ;;
                testing)
                    case "$substage" in
                        planning)             sub="planning tests" ;;
                        confirming-plan)      sub="confirming plan" ;;
                        writing-tests)        sub="writing tests" ;;
                        verifying-failures)   sub="verifying failures" ;;
                        *verified*failing*)   sub="tests verified" ;;
                        escalation*)          sub="escalation" ;;
                    esac
                    stage_display="$slug · testing${sub:+ · $sub}" ;;
                implementation)
                    case "$substage" in
                        loading-context)    sub="loading context" ;;
                        implementing)       sub="implementing" ;;
                        implemented:*)      sub="${substage#implemented: }" ;;
                        *all*tests*passing) sub="all passing" ;;
                        escalat*)           sub="escalation" ;;
                    esac
                    stage_display="$slug · implementing${sub:+ · $sub}" ;;
                refactor)
                    case "$substage" in
                        loading-context)        sub="loading context" ;;
                        refactor:*coding*)      sub="coding standards" ;;
                        refactor:*duplication*) sub="DRY" ;;
                        refactor:*security)     sub="security" ;;
                        refactor:*performance*) sub="performance" ;;
                        refactor:*missing*)     sub="missing tests" ;;
                        refactor:*integration*) sub="integration" ;;
                        refactor:*documentation*) sub="docs" ;;
                        refactor:*security-review*) sub="security review" ;;
                        refactor:*final-lint*)  sub="final lint" ;;
                        *refactor*complete*)    sub="complete" ;;
                        escalat*)               sub="escalation" ;;
                        cycle-5*)               sub="cycle limit" ;;
                    esac
                    stage_display="$slug · refactor${sub:+ · $sub}" ;;
                pr)
                    case "$substage" in
                        pr-draft-written) sub="draft ready" ;;
                    esac
                    stage_display="$slug · PR draft${sub:+ · $sub}" ;;
                *)
                    stage_display="$slug · $current_stage" ;;
            esac

            # ── Per-stage token tracking via context % baseline ──────────
            if [[ "$current_ctx_tokens" =~ ^[0-9]+$ ]]; then
                baseline_stage=""
                baseline_ctx_tokens=""
                baseline_timestamp=""
                [[ -f "$BASELINE_FILE" ]] && _read_baseline "$BASELINE_FILE"

                if [[ "$baseline_stage" != "$current_stage" ]]; then
                    # ── Stage transition detected ────────────────────────
                    # Reset baseline for new stage. Token logging is handled
                    # by token-stop-hook.sh (transcript-based, more accurate).
                    printf '{"baseline_stage":"%s","baseline_ctx_tokens":%s,"baseline_timestamp":"%s"}\n' \
                        "$current_stage" "$current_ctx_tokens" \
                        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "${BASELINE_FILE}.tmp" \
                        && mv "${BASELINE_FILE}.tmp" "$BASELINE_FILE"
                    baseline_ctx_tokens="$current_ctx_tokens"

                elif [[ ! "$baseline_ctx_tokens" =~ ^[0-9]+$ ]]; then
                    # No valid baseline — initialize
                    printf '{"baseline_stage":"%s","baseline_ctx_tokens":%s,"baseline_timestamp":"%s"}\n' \
                        "$current_stage" "$current_ctx_tokens" \
                        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "${BASELINE_FILE}.tmp" \
                        && mv "${BASELINE_FILE}.tmp" "$BASELINE_FILE"
                    baseline_ctx_tokens="$current_ctx_tokens"
                fi

                stage_used=$(( current_ctx_tokens - baseline_ctx_tokens ))
                [[ "$stage_used" -lt 0 ]] && stage_used=0
                stage_tokens=$(fmt_tokens "$stage_used")
            fi
        else
            # Terminal state — clean up baseline
            rm -f "$BASELINE_FILE"
        fi
    fi
else
    # No active feature — clean up baseline if stale
    rm -f "$BASELINE_FILE"
fi

# ── Read subagent state ──────────────────────────────────────────────────────

subagent_display=""
SUBAGENT_FILE=".claude/.subagent-state"
if [[ -f "$SUBAGENT_FILE" ]]; then
    subagent_content=$(cat "$SUBAGENT_FILE" 2>/dev/null)
    # File existence = subagent active (SubagentStop removes it)
    subagent_desc=$(_json_val "$subagent_content" "description")
    if [[ -n "$subagent_desc" ]]; then
        subagent_display="agent: $subagent_desc"
    fi
fi

# ── Build output line ────────────────────────────────────────────────────────

parts=()

if [[ -n "$stage_display" ]]; then
    parts+=("\033[36m$stage_display\033[0m")
fi

# Show active subagent if any
if [[ -n "$subagent_display" ]]; then
    parts+=("\033[35m$subagent_display\033[0m")
fi

# Show per-stage tokens if available
if [[ -n "$stage_tokens" ]]; then
    parts+=("${stage_tokens} tokens")
fi

if [[ -n "$context_pct" ]]; then
    # Color context usage: green < 50%, yellow 50-80%, red > 80%
    ctx_int=${context_pct%.*}
    [[ "$ctx_int" =~ ^[0-9]+$ ]] || ctx_int=0
    if [[ "$ctx_int" -ge 80 ]]; then
        parts+=("\033[31mctx ${ctx_int}%\033[0m")
    elif [[ "$ctx_int" -ge 50 ]]; then
        parts+=("\033[33mctx ${ctx_int}%\033[0m")
    else
        parts+=("\033[32mctx ${ctx_int}%\033[0m")
    fi
fi

# Join with separator
if [[ ${#parts[@]} -gt 0 ]]; then
    output=""
    for i in "${!parts[@]}"; do
        if [[ $i -gt 0 ]]; then
            output+=" · "
        fi
        output+="${parts[$i]}"
    done
    echo -e "$output"
fi

exit 0
