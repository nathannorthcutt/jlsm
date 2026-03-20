#!/usr/bin/env bash
# vallorcine SubagentStart/SubagentStop hook
# Writes/clears .claude/.subagent-state when subagents start/stop.
# The status line reads this to show which subagent is active.
#
# Registered for both SubagentStart and SubagentStop events.
# Event type and agent details come from stdin JSON.
#
# State file: .claude/.subagent-state (JSON)
#   {"active":true,"agent_id":"...","description":"...","started":"..."}

input=$(cat)

STATE_FILE=".claude/.subagent-state"

# ── Detect event type from stdin JSON ─────────────────────────────────────────

# Extract hook_event_name to determine start vs stop
event=""
agent_id=""
description=""

if command -v jq &>/dev/null; then
    event=$(echo "$input" | jq -r '.hook_event_name // empty' 2>/dev/null)
    agent_id=$(echo "$input" | jq -r '.agent_id // empty' 2>/dev/null)
    description=$(echo "$input" | jq -r '.description // empty' 2>/dev/null)
else
    # Fallback: grep-based extraction for flat JSON
    event=$(echo "$input" | grep -o '"hook_event_name":"[^"]*"' | head -1 | cut -d'"' -f4)
    agent_id=$(echo "$input" | grep -o '"agent_id":"[^"]*"' | head -1 | cut -d'"' -f4)
    description=$(echo "$input" | grep -o '"description":"[^"]*"' | head -1 | cut -d'"' -f4)
fi

case "$event" in
    SubagentStart)
        mkdir -p .claude
        # Write JSON state
        printf '{"active":true,"agent_id":"%s","description":"%s","started":"%s"}\n' \
            "$agent_id" "$description" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$STATE_FILE"
        ;;
    SubagentStop)
        rm -f "$STATE_FILE"
        ;;
    *)
        # Unknown event — ignore silently
        ;;
esac

exit 0
