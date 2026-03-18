#!/usr/bin/env bash
# vallorcine dashboard — shared icon and color definitions
# Sourced by pipeline.sh and stage-detail.sh.
# Do not run directly.

# ── ANSI colors ──────────────────────────────────────────────────────────────

C_RESET='\033[0m'
C_BOLD='\033[1m'
C_DIM='\033[0;90m'

C_RED='\033[0;31m'
C_GREEN='\033[0;32m'
C_YELLOW='\033[1;33m'
C_BLUE='\033[0;34m'
C_MAGENTA='\033[0;35m'
C_CYAN='\033[0;36m'
C_WHITE='\033[0;37m'

C_BOLD_RED='\033[1;31m'
C_BOLD_GREEN='\033[1;32m'
C_BOLD_YELLOW='\033[1;33m'
C_BOLD_BLUE='\033[1;34m'
C_BOLD_MAGENTA='\033[1;35m'
C_BOLD_CYAN='\033[1;36m'

# ── Stage icons and colors ───────────────────────────────────────────────────
# Indexed by stage name. Used by both panes for consistent visual language.

declare -A STAGE_ICON=(
    [scoping]="⬡"
    [domains]="◈"
    [planning]="▣"
    [testing]="△"
    [implementation]="⚙"
    [refactor]="◆"
    [pr]="⎘"
)

declare -A STAGE_COLOR=(
    [scoping]="$C_CYAN"
    [domains]="$C_BLUE"
    [planning]="$C_MAGENTA"
    [testing]="$C_GREEN"
    [implementation]="$C_YELLOW"
    [refactor]="$C_CYAN"
    [pr]="$C_MAGENTA"
)

# ── Status indicators ────────────────────────────────────────────────────────
# Used for tasks, artifacts, and stage status across both panes.

declare -A STATUS_ICON=(
    [complete]="✓"
    [completed]="✓"
    [in_progress]="◉"
    [pending]="○"
    [warning]="⚠"
    [error]="✗"
    [failed]="✗"
)

declare -A STATUS_COLOR=(
    [complete]="$C_GREEN"
    [completed]="$C_GREEN"
    [in_progress]="$C_YELLOW"
    [pending]="$C_DIM"
    [warning]="$C_YELLOW"
    [error]="$C_RED"
    [failed]="$C_RED"
)

# ── Artifact status display ──────────────────────────────────────────────────
# Maps artifact lifecycle statuses to visual indicators.

declare -A ARTIFACT_ICON=(
    [identified]="○"
    [pending]="○"
    [test_written]="△"
    [implementing]="◉"
    [implemented]="✓"
    [reviewing]="◉"
    [reviewed]="✓"
    [failed]="✗"
)

declare -A ARTIFACT_COLOR=(
    [identified]="$C_DIM"
    [pending]="$C_DIM"
    [test_written]="$C_GREEN"
    [implementing]="$C_YELLOW"
    [implemented]="$C_GREEN"
    [reviewing]="$C_YELLOW"
    [reviewed]="$C_GREEN"
    [failed]="$C_RED"
)

# ── Token formatting ─────────────────────────────────────────────────────────

fmt_tokens() {
    local n="$1"
    if [[ -z "$n" || "$n" == "null" ]]; then
        echo "──"
        return
    fi
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

# ── Progress bar ─────────────────────────────────────────────────────────────

render_bar() {
    local actual="$1"
    local estimate="$2"
    local width="${3:-20}"

    if [[ -z "$estimate" || "$estimate" == "null" || "$estimate" -eq 0 ]]; then
        return 1
    fi

    local pct=$(( actual * 100 / estimate ))
    [[ "$pct" -gt 100 ]] && pct=100
    local filled=$(( pct * width / 100 ))

    local bar=""
    for ((i=0; i<width; i++)); do
        if [[ $i -lt $filled ]]; then
            bar+="█"
        else
            bar+="░"
        fi
    done

    echo "$bar $pct%"
}

# ── Divider ──────────────────────────────────────────────────────────────────

render_divider() {
    local color="${1:-$C_DIM}"
    echo -e "${color}────────────────────────────────────${C_RESET}"
}

# ── Dashboard state directory ────────────────────────────────────────────────
# Resolved relative to the project root (passed as $1 or auto-detected).

resolve_dashboard_dir() {
    local project_root="${1:-.}"
    echo "$project_root/.claude/dashboard"
}
