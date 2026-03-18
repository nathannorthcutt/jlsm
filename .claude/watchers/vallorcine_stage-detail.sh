#!/usr/bin/env bash
# vallorcine dashboard — stage detail pane watcher
# Renders current stage tasks, artifact status, and interrupt guidance.
#
# Usage: bash vallorcine_stage-detail.sh [project-root]
#   project-root defaults to current directory.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/vallorcine_theme.sh"

PROJECT_ROOT="${1:-.}"
DASHBOARD_DIR="$(resolve_dashboard_dir "$PROJECT_ROOT")"
STATE="$DASHBOARD_DIR/stage.json"

# ── Time delta helper ────────────────────────────────────────────────────────

time_ago() {
    local ts="$1"
    if [[ -z "$ts" || "$ts" == "null" ]]; then
        echo ""
        return
    fi

    local now ts_epoch delta
    now="$(date +%s)"

    # Parse ISO 8601 timestamp — handle both GNU and BSD date
    if date -d "$ts" +%s &>/dev/null 2>&1; then
        ts_epoch="$(date -d "$ts" +%s)"
    elif date -jf "%Y-%m-%dT%H:%M:%SZ" "$ts" +%s &>/dev/null 2>&1; then
        ts_epoch="$(date -jf "%Y-%m-%dT%H:%M:%SZ" "$ts" +%s)"
    else
        echo ""
        return
    fi

    delta=$(( now - ts_epoch ))
    [[ "$delta" -lt 0 ]] && delta=0

    if [[ "$delta" -lt 60 ]]; then
        echo "${delta}s ago"
    elif [[ "$delta" -lt 3600 ]]; then
        echo "$(( delta / 60 ))m ago"
    else
        echo "$(( delta / 3600 ))h ago"
    fi
}

time_ago_color() {
    local ts="$1"
    if [[ -z "$ts" || "$ts" == "null" ]]; then
        echo "$C_DIM"
        return
    fi

    local now ts_epoch delta
    now="$(date +%s)"

    if date -d "$ts" +%s &>/dev/null 2>&1; then
        ts_epoch="$(date -d "$ts" +%s)"
    elif date -jf "%Y-%m-%dT%H:%M:%SZ" "$ts" +%s &>/dev/null 2>&1; then
        ts_epoch="$(date -jf "%Y-%m-%dT%H:%M:%SZ" "$ts" +%s)"
    else
        echo "$C_DIM"
        return
    fi

    delta=$(( now - ts_epoch ))

    if [[ "$delta" -ge 120 ]]; then
        echo "$C_YELLOW"
    else
        echo "$C_DIM"
    fi
}

# ── Rendering ────────────────────────────────────────────────────────────────

render() {
    tput cup 0 0 2>/dev/null || true
    tput ed 2>/dev/null || true

    # ── Empty state ──────────────────────────────────────────────────────
    if [[ ! -f "$STATE" ]]; then
        echo -e "${C_BOLD_MAGENTA}═══════════════════════════════════${C_RESET}"
        echo -e "${C_BOLD_MAGENTA}  📋 STAGE DETAIL${C_RESET}"
        echo -e "${C_BOLD_MAGENTA}═══════════════════════════════════${C_RESET}"
        echo ""
        echo -e "  No active stage."
        return
    fi

    local stage icon unit updated
    stage="$(jq -r '.stage // "unknown"' "$STATE")"
    icon="$(jq -r '.icon // ""' "$STATE")"
    unit="$(jq -r '.unit // empty' "$STATE")"
    updated="$(jq -r '.updated // empty' "$STATE")"

    # ── Header ───────────────────────────────────────────────────────────
    local st_color="${STAGE_COLOR[$stage]:-$C_MAGENTA}"

    echo -e "${C_BOLD}${st_color}═══════════════════════════════════${C_RESET}"

    # Status: check if any task is in_progress
    local has_active
    has_active="$(jq '[.tasks[]? | select(.status == "in_progress")] | length' "$STATE" 2>/dev/null || echo 0)"

    local header_status="◉"
    [[ "$has_active" == "0" ]] && header_status="○"

    local unit_display=""
    [[ -n "$unit" ]] && unit_display=" — $unit"

    local time_display=""
    if [[ -n "$updated" ]]; then
        local ago ago_color
        ago="$(time_ago "$updated")"
        ago_color="$(time_ago_color "$updated")"
        [[ -n "$ago" ]] && time_display="${ago_color}${ago}${C_RESET}"
    fi

    # "◉ ⚙ IMPLEMENTATION — unit-name          12s ago"
    local header_left="${STATUS_COLOR[in_progress]}${header_status}${C_RESET} ${st_color}${icon}${C_RESET} ${C_BOLD}$(echo "$stage" | tr '[:lower:]' '[:upper:]')${unit_display}${C_RESET}"

    echo -e "  ${header_left}"
    if [[ -n "$time_display" ]]; then
        echo -e "  ${time_display}"
    fi
    echo -e "${C_BOLD}${st_color}═══════════════════════════════════${C_RESET}"
    echo ""

    # ── Tasks ────────────────────────────────────────────────────────────
    local task_count
    task_count="$(jq '.tasks | length' "$STATE" 2>/dev/null || echo 0)"

    if [[ "$task_count" -gt 0 ]]; then
        for ((i=0; i<task_count; i++)); do
            local t_name t_status t_icon t_color
            t_name="$(jq -r ".tasks[$i].name" "$STATE")"
            t_status="$(jq -r ".tasks[$i].status" "$STATE")"
            t_icon="${STATUS_ICON[$t_status]:-○}"
            t_color="${STATUS_COLOR[$t_status]:-$C_DIM}"

            echo -e "  ${t_color}${t_icon}${C_RESET} ${t_name}"
        done
        echo ""
    fi

    # ── Artifacts ────────────────────────────────────────────────────────
    local has_artifacts
    has_artifacts="$(jq '.artifacts // null | length' "$STATE" 2>/dev/null || echo 0)"

    if [[ "$has_artifacts" -gt 0 ]]; then
        render_divider "$st_color"
        echo -e "  ${C_DIM}artifacts${C_RESET}"
        echo ""

        local artifact_count
        artifact_count="$(jq '.artifacts | length' "$STATE")"

        for ((i=0; i<artifact_count; i++)); do
            local a_name a_status a_icon a_color
            a_name="$(jq -r ".artifacts[$i].name" "$STATE")"
            a_status="$(jq -r ".artifacts[$i].status" "$STATE")"
            a_icon="${ARTIFACT_ICON[$a_status]:-○}"
            a_color="${ARTIFACT_COLOR[$a_status]:-$C_DIM}"

            echo -e "  ${a_color}${a_icon}${C_RESET} ${a_name} ${C_DIM}${a_status}${C_RESET}"
        done
        echo ""
    fi

    # ── Interrupt hint ───────────────────────────────────────────────────
    if [[ "$has_active" -gt 0 ]]; then
        render_divider "$C_DIM"
        echo -e "  ${C_DIM}Press Esc to pause and provide guidance.${C_RESET}"
        echo -e "  ${C_DIM}Resume with /feature-resume.${C_RESET}"
    fi
}

# ── Main loop ────────────────────────────────────────────────────────────────

mkdir -p "$DASHBOARD_DIR"
render

if command -v inotifywait &>/dev/null; then
    while inotifywait -q -e modify,create "$STATE" 2>/dev/null; do
        sleep 0.1
        render
    done
elif command -v fswatch &>/dev/null; then
    fswatch -o "$STATE" 2>/dev/null | while read -r _; do
        sleep 0.1
        render
    done
else
    # Poll fallback — refresh every second (also updates time_ago display)
    while true; do
        sleep 1
        render
    done
fi
