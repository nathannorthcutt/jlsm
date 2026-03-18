#!/usr/bin/env bash
# vallorcine dashboard — Stop hook
# Updates the live token counter in pipeline.json on each Claude turn.
#
# Called by Claude Code's Stop hook. Receives JSON on stdin.
# Reads the active session transcript to compute running token total
# for the current in_progress stage.
#
# Exit codes: 0 always (never block Claude).

set -euo pipefail

DASHBOARD_DIR=".claude/dashboard"
PIPELINE_FILE="$DASHBOARD_DIR/pipeline.json"

# Bail fast if no dashboard state or jq missing
[[ -f "$PIPELINE_FILE" ]] || exit 0
command -v jq &>/dev/null || exit 0

# Check if any stage is in_progress
has_active=$(jq '[.stages[] | select(.status == "in_progress")] | length' "$PIPELINE_FILE" 2>/dev/null || echo 0)
[[ "$has_active" -gt 0 ]] || exit 0

# Read stdin (Stop hook payload) but we don't need it — just consume it
cat > /dev/null

# Source token-usage.sh to get _find_transcript and _sum_usage
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/token-usage.sh" 2>/dev/null || exit 0

# Find the feature directory by looking for an active token checkpoint
# The checkpoint file tells us which line to start counting from
CHECKPOINT=""
for f in .feature/*/. ; do
    dir="${f%/.}"
    [[ -f "$dir/.token-checkpoint" ]] && CHECKPOINT="$dir/.token-checkpoint" && break
done

[[ -n "$CHECKPOINT" ]] || exit 0

# Read checkpoint
transcript=""
start_line=""
while IFS='=' read -r key val; do
    case "$key" in
        transcript)  transcript="$val" ;;
        start_line)  start_line="$val" ;;
    esac
done < "$CHECKPOINT"

[[ -n "$transcript" && -f "$transcript" && -n "$start_line" ]] || exit 0

# Compute running total from checkpoint to current
usage=$(_sum_usage "$transcript" "$start_line")
input=$(echo "$usage" | jq -r '.input')
output=$(echo "$usage" | jq -r '.output')
live_tokens=$((input + output))

# Update pipeline.json
source "$SCRIPT_DIR/dashboard-state.sh" 2>/dev/null || exit 0
dashboard_update_live_tokens "$live_tokens"

exit 0
