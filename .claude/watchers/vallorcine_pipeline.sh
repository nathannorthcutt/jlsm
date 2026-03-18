#!/usr/bin/env bash
# vallorcine dashboard — pipeline pane watcher
# Renders stage progression, token spend, and failure alerts.
#
# Usage: bash vallorcine_pipeline.sh [project-root]
#   project-root defaults to current directory.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/vallorcine_theme.sh"

PROJECT_ROOT="${1:-.}"
DASHBOARD_DIR="$(resolve_dashboard_dir "$PROJECT_ROOT")"
STATE="$DASHBOARD_DIR/pipeline.json"

# ── Rendering ────────────────────────────────────────────────────────────────

render() {
    tput cup 0 0 2>/dev/null || true
    tput ed 2>/dev/null || true

    # ── Header ───────────────────────────────────────────────────────────
    echo -e "${C_BOLD_CYAN}═══════════════════════════════════${C_RESET}"

    if [[ ! -f "$STATE" ]]; then
        echo -e "${C_BOLD_CYAN}  ⬡ PIPELINE${C_RESET}"
        echo -e "${C_BOLD_CYAN}═══════════════════════════════════${C_RESET}"
        echo ""
        echo -e "  Waiting for feature work to start..."
        echo -e "  Run ${C_BOLD}/feature${C_RESET} to begin."
        return
    fi

    local feature version
    feature="$(jq -r '.feature // "unknown"' "$STATE")"
    version="$(jq -r '.version // ""' "$STATE")"

    local version_display=""
    [[ -n "$version" && "$version" != "null" ]] && version_display="  v${version}"

    echo -e "${C_BOLD_CYAN}  ⬡ PIPELINE${C_DIM}${version_display}${C_RESET}"
    echo -e "${C_BOLD_CYAN}═══════════════════════════════════${C_RESET}"
    echo -e "  ${C_DIM}feature:${C_RESET} ${C_BOLD}$feature${C_RESET}"
    echo ""

    # ── Work units ───────────────────────────────────────────────────────
    local units_current units_total
    units_current="$(jq -r '.units.current // empty' "$STATE" 2>/dev/null)"
    units_total="$(jq -r '.units.total // empty' "$STATE" 2>/dev/null)"

    if [[ -n "$units_current" && -n "$units_total" ]]; then
        echo -e "  ${C_DIM}unit:${C_RESET} $units_current/$units_total"
        echo ""
    fi

    # ── Stages ───────────────────────────────────────────────────────────
    local stage_count
    stage_count="$(jq '.stages | length' "$STATE")"

    for ((i=0; i<stage_count; i++)); do
        local name status icon token_actual token_estimate token_live alert_level alert_msg
        name="$(jq -r ".stages[$i].name" "$STATE")"
        status="$(jq -r ".stages[$i].status" "$STATE")"
        icon="$(jq -r ".stages[$i].icon" "$STATE")"
        token_actual="$(jq -r ".stages[$i].tokens.actual // empty" "$STATE")"
        token_estimate="$(jq -r ".stages[$i].tokens.estimate // empty" "$STATE")"
        token_live="$(jq -r ".stages[$i].tokens.live // empty" "$STATE")"
        alert_level="$(jq -r ".stages[$i].alert.level // empty" "$STATE")"
        alert_msg="$(jq -r ".stages[$i].alert.message // empty" "$STATE")"

        # Status indicator
        local s_icon="${STATUS_ICON[$status]:-○}"
        local s_color="${STATUS_COLOR[$status]:-$C_DIM}"

        # Stage color from theme (fallback to status color)
        local st_color="${STAGE_COLOR[$name]:-$s_color}"

        # Token display: actual > live > estimate > nothing
        local token_display=""
        if [[ -n "$token_actual" ]]; then
            token_display="$(fmt_tokens "$token_actual")"
        elif [[ -n "$token_live" ]]; then
            token_display="$(fmt_tokens "$token_live") ${C_DIM}▸${C_RESET}"
        elif [[ -n "$token_estimate" ]]; then
            token_display="${C_DIM}est $(fmt_tokens "$token_estimate")${C_RESET}"
        fi

        # Active stage indicator
        local active_marker=""
        [[ "$status" == "in_progress" ]] && active_marker=" ${C_BOLD}▸${C_RESET}"

        # Alert
        local alert_display=""
        if [[ -n "$alert_level" ]]; then
            local a_icon="${STATUS_ICON[$alert_level]:-⚠}"
            local a_color="${STATUS_COLOR[$alert_level]:-$C_YELLOW}"
            alert_display=" ${a_color}${a_icon} ${alert_msg}${C_RESET}"
        fi

        # Compose line: "  ✓ ⬡ scoping          1.8K"
        local padded_name
        padded_name="$(printf '%-16s' "$name")"

        echo -e "  ${s_color}${s_icon}${C_RESET} ${st_color}${icon}${C_RESET} ${padded_name} ${token_display}${active_marker}${alert_display}"
    done

    echo ""

    # ── Progress bar + totals ────────────────────────────────────────────
    local total_actual total_estimate
    total_actual="$(jq -r '.totals.actual // empty' "$STATE")"
    total_estimate="$(jq -r '.totals.estimate // empty' "$STATE")"

    if [[ -n "$total_estimate" && "$total_estimate" != "0" ]]; then
        local actual_val="${total_actual:-0}"
        local bar
        bar="$(render_bar "$actual_val" "$total_estimate")"

        if [[ -n "$bar" ]]; then
            echo -e "  ${C_BOLD_YELLOW}[${bar}]${C_RESET}"
            echo -e "  ${C_DIM}$(fmt_tokens "$actual_val") / $(fmt_tokens "$total_estimate")${C_RESET}"
        fi
    elif [[ -n "$total_actual" && "$total_actual" != "0" ]]; then
        echo -e "  ${C_DIM}tokens used: $(fmt_tokens "$total_actual")${C_RESET}"
    fi
}

# ── Main loop ────────────────────────────────────────────────────────────────

mkdir -p "$DASHBOARD_DIR"
render

# Watch for changes — inotifywait on Linux, fswatch on macOS, poll fallback
if command -v inotifywait &>/dev/null; then
    while inotifywait -q -e modify,create "$STATE" 2>/dev/null; do
        sleep 0.1  # debounce rapid writes
        render
    done
elif command -v fswatch &>/dev/null; then
    fswatch -o "$STATE" 2>/dev/null | while read -r _; do
        sleep 0.1
        render
    done
else
    while true; do
        sleep 1
        render
    done
fi
