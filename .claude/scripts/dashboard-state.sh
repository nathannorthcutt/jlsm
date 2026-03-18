#!/usr/bin/env bash
# vallorcine dashboard state helpers
# Sourced by pipeline commands and hooks to write dashboard state files.
#
# Usage (sourced by pipeline commands — not called directly):
#   source .claude/scripts/dashboard-state.sh
#   dashboard_init "compaction-engine"                    # initialize pipeline.json
#   dashboard_stage_start "testing" "compaction-engine"   # mark stage in_progress
#   dashboard_stage_complete "testing" 2847               # mark stage complete with token actual
#   dashboard_set_estimates '{"testing":3000,"implementation":8000,...}'  # set estimates
#   dashboard_set_alert "testing" "warning" "2 failing"   # set alert on a stage
#   dashboard_clear_alert "testing"                       # clear alert
#   dashboard_set_units 1 3                               # set work unit progress
#   dashboard_stage_detail "testing" "△" "config-parser"  # write stage.json header
#   dashboard_task "Write parse_config tests" "in_progress" # add/update task
#   dashboard_artifact "ConfigParser" "implementing"      # add/update artifact
#   dashboard_update_live_tokens 2847                     # update live counter (Stop hook)
#   dashboard_hint                                        # show hint if conditions met
#
# Requires: jq
# State directory: .claude/dashboard/ (gitignored, project-local)

# ── Globals ──────────────────────────────────────────────────────────────────

_DASHBOARD_DIR=".claude/dashboard"
_PIPELINE_FILE="$_DASHBOARD_DIR/pipeline.json"
_STAGE_FILE="$_DASHBOARD_DIR/stage.json"

_dashboard_jq_available() {
    command -v jq &>/dev/null
}

# ── Pipeline state ───────────────────────────────────────────────────────────

# Initialize pipeline.json with all 7 stages as pending.
dashboard_init() {
    local feature="$1"
    local version
    version="$(cat .claude/.vallorcine-version 2>/dev/null || echo "unknown")"

    _dashboard_jq_available || return 0
    mkdir -p "$_DASHBOARD_DIR"

    jq -n \
        --arg feature "$feature" \
        --arg version "$version" \
        '{
            feature: $feature,
            version: $version,
            stages: [
                { name: "scoping",        icon: "⬡", status: "pending", tokens: { actual: null, estimate: null, live: null }, alert: null },
                { name: "domains",        icon: "◈", status: "pending", tokens: { actual: null, estimate: null, live: null }, alert: null },
                { name: "planning",       icon: "▣", status: "pending", tokens: { actual: null, estimate: null, live: null }, alert: null },
                { name: "testing",        icon: "△", status: "pending", tokens: { actual: null, estimate: null, live: null }, alert: null },
                { name: "implementation", icon: "⚙", status: "pending", tokens: { actual: null, estimate: null, live: null }, alert: null },
                { name: "refactor",       icon: "◆", status: "pending", tokens: { actual: null, estimate: null, live: null }, alert: null },
                { name: "pr",             icon: "⎘", status: "pending", tokens: { actual: null, estimate: null, live: null }, alert: null }
            ],
            units: null,
            totals: null
        }' > "$_PIPELINE_FILE"
}

# Mark a stage as in_progress.
dashboard_stage_start() {
    local stage="$1"

    _dashboard_jq_available || return 0
    [[ -f "$_PIPELINE_FILE" ]] || return 0

    jq --arg stage "$stage" '
        .stages |= map(if .name == $stage then .status = "in_progress" else . end)
    ' "$_PIPELINE_FILE" > "$_PIPELINE_FILE.tmp" && mv "$_PIPELINE_FILE.tmp" "$_PIPELINE_FILE"
}

# Mark a stage as complete and record token actuals.
dashboard_stage_complete() {
    local stage="$1"
    local token_actual="${2:-}"

    _dashboard_jq_available || return 0
    [[ -f "$_PIPELINE_FILE" ]] || return 0

    local actual_arg="null"
    [[ -n "$token_actual" && "$token_actual" != "unknown" ]] && actual_arg="$token_actual"

    jq --arg stage "$stage" --argjson actual "$actual_arg" '
        .stages |= map(
            if .name == $stage then
                .status = "complete" | .tokens.actual = $actual | .tokens.live = null
            else . end
        )
        | .totals = {
            actual: ([.stages[].tokens.actual | select(. != null)] | add // 0),
            estimate: (
                if ([.stages[].tokens.estimate | select(. != null)] | length) > 0
                then [.stages[].tokens.estimate | select(. != null)] | add
                else null end
            )
        }
    ' "$_PIPELINE_FILE" > "$_PIPELINE_FILE.tmp" && mv "$_PIPELINE_FILE.tmp" "$_PIPELINE_FILE"
}

# Set token estimates (called by /feature-plan).
# Expects a JSON object: {"testing":3000,"implementation":8000,...}
dashboard_set_estimates() {
    local estimates_json="$1"

    _dashboard_jq_available || return 0
    [[ -f "$_PIPELINE_FILE" ]] || return 0

    jq --argjson est "$estimates_json" '
        .stages |= map(
            if $est[.name] then .tokens.estimate = $est[.name] else . end
        )
        | .totals = {
            actual: ([.stages[].tokens.actual | select(. != null)] | add // 0),
            estimate: ([.stages[].tokens.estimate | select(. != null)] | add // null)
        }
    ' "$_PIPELINE_FILE" > "$_PIPELINE_FILE.tmp" && mv "$_PIPELINE_FILE.tmp" "$_PIPELINE_FILE"
}

# Set an alert on a stage.
dashboard_set_alert() {
    local stage="$1"
    local level="$2"
    local message="$3"

    _dashboard_jq_available || return 0
    [[ -f "$_PIPELINE_FILE" ]] || return 0

    jq --arg stage "$stage" --arg level "$level" --arg msg "$message" '
        .stages |= map(
            if .name == $stage then .alert = { level: $level, message: $msg } else . end
        )
    ' "$_PIPELINE_FILE" > "$_PIPELINE_FILE.tmp" && mv "$_PIPELINE_FILE.tmp" "$_PIPELINE_FILE"
}

# Clear alert on a stage.
dashboard_clear_alert() {
    local stage="$1"

    _dashboard_jq_available || return 0
    [[ -f "$_PIPELINE_FILE" ]] || return 0

    jq --arg stage "$stage" '
        .stages |= map(if .name == $stage then .alert = null else . end)
    ' "$_PIPELINE_FILE" > "$_PIPELINE_FILE.tmp" && mv "$_PIPELINE_FILE.tmp" "$_PIPELINE_FILE"
}

# Set work unit progress.
dashboard_set_units() {
    local current="$1"
    local total="$2"
    shift 2
    local names=("$@")

    _dashboard_jq_available || return 0
    [[ -f "$_PIPELINE_FILE" ]] || return 0

    local names_json
    names_json=$(printf '%s\n' "${names[@]}" | jq -R . | jq -s .)

    jq --argjson current "$current" --argjson total "$total" --argjson names "$names_json" '
        .units = { current: $current, total: $total, names: $names }
    ' "$_PIPELINE_FILE" > "$_PIPELINE_FILE.tmp" && mv "$_PIPELINE_FILE.tmp" "$_PIPELINE_FILE"
}

# Update live token counter for the in_progress stage (called by Stop hook).
dashboard_update_live_tokens() {
    local live_tokens="$1"

    _dashboard_jq_available || return 0
    [[ -f "$_PIPELINE_FILE" ]] || return 0

    jq --argjson live "$live_tokens" '
        .stages |= map(
            if .status == "in_progress" then .tokens.live = $live else . end
        )
    ' "$_PIPELINE_FILE" > "$_PIPELINE_FILE.tmp" && mv "$_PIPELINE_FILE.tmp" "$_PIPELINE_FILE"
}

# ── Stage detail state ───────────────────────────────────────────────────────

# Write/overwrite stage.json header. Resets tasks and artifacts.
dashboard_stage_detail() {
    local stage="$1"
    local icon="$2"
    local unit="${3:-null}"

    _dashboard_jq_available || return 0
    mkdir -p "$_DASHBOARD_DIR"

    local unit_arg="null"
    [[ "$unit" != "null" && -n "$unit" ]] && unit_arg="\"$unit\""

    jq -n \
        --arg stage "$stage" \
        --arg icon "$icon" \
        --argjson unit "$unit_arg" \
        --arg updated "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        '{
            stage: $stage,
            icon: $icon,
            unit: $unit,
            updated: $updated,
            tasks: [],
            artifacts: []
        }' > "$_STAGE_FILE"
}

# Add or update a task in stage.json.
dashboard_task() {
    local name="$1"
    local status="$2"

    _dashboard_jq_available || return 0
    [[ -f "$_STAGE_FILE" ]] || return 0

    jq --arg name "$name" --arg status "$status" --arg updated "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '
        .updated = $updated
        | if (.tasks | map(.name) | index($name)) then
            .tasks |= map(if .name == $name then .status = $status else . end)
          else
            .tasks += [{ name: $name, status: $status }]
          end
    ' "$_STAGE_FILE" > "$_STAGE_FILE.tmp" && mv "$_STAGE_FILE.tmp" "$_STAGE_FILE"
}

# Add or update an artifact in stage.json.
dashboard_artifact() {
    local name="$1"
    local status="$2"

    _dashboard_jq_available || return 0
    [[ -f "$_STAGE_FILE" ]] || return 0

    jq --arg name "$name" --arg status "$status" --arg updated "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '
        .updated = $updated
        | if (.artifacts | map(.name) | index($name)) then
            .artifacts |= map(if .name == $name then .status = $status else . end)
          else
            .artifacts += [{ name: $name, status: $status }]
          end
    ' "$_STAGE_FILE" > "$_STAGE_FILE.tmp" && mv "$_STAGE_FILE.tmp" "$_STAGE_FILE"
}

# Clear stage.json (called when pipeline stage completes).
dashboard_stage_detail_clear() {
    rm -f "$_STAGE_FILE"
}

# ── Dashboard hint ───────────────────────────────────────────────────────────

# Show dashboard hint once per session if conditions are met.
# Returns 0 if hint was shown, 1 if suppressed.
dashboard_hint() {
    # Must be in tmux
    [[ -n "${TMUX:-}" ]] || return 1

    # Must not be suppressed
    [[ ! -f "$_DASHBOARD_DIR/.nodashboard" ]] || return 1

    # Must not be already running
    if tmux list-panes -F '#{pane_title}' 2>/dev/null | grep -q 'vallorcine'; then
        return 1
    fi

    # Must not have prompted this session
    [[ ! -f "$_DASHBOARD_DIR/.prompted" ]] || return 1

    mkdir -p "$_DASHBOARD_DIR"
    touch "$_DASHBOARD_DIR/.prompted"
    echo ""
    echo "Dashboard available. Run /dashboard to open, or /dashboard off to suppress."
    echo ""
    return 0
}
