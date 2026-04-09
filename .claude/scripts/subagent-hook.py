#!/usr/bin/env python3
"""vallorcine SubagentStart/SubagentStop hook — Python implementation.

Writes/clears .claude/.subagent-state when subagents start/stop.
The status line reads this to show which subagent is active.

Stdlib only — no pip dependencies.
"""

import json
import sys
from datetime import datetime, timezone
from pathlib import Path


def main() -> None:
    state_file = Path(".claude/.subagent-state")
    try:
        data = json.loads(sys.stdin.read())
    except (json.JSONDecodeError, ValueError):
        # If we can't parse input, clean up stale state as a safe default
        try:
            state_file.unlink(missing_ok=True)
        except OSError:
            pass
        return

    event = data.get("hook_event_name", "")

    if event == "SubagentStart":
        state = {
            "active": True,
            "agent_id": data.get("agent_id", ""),
            "description": data.get("description", ""),
            "started": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        }
        try:
            state_file.parent.mkdir(parents=True, exist_ok=True)
            tmp = state_file.with_suffix(".tmp")
            tmp.write_text(json.dumps(state) + "\n")
            tmp.rename(state_file)
        except OSError:
            pass
    elif event == "SubagentStop":
        try:
            state_file.unlink(missing_ok=True)
        except OSError:
            pass


if __name__ == "__main__":
    try:
        main()
    except Exception:
        pass
