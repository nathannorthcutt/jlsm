---
description: "Launch, enable, or disable the vallorcine tmux dashboard"
---

# /dashboard

Launch, enable, or disable the vallorcine tmux dashboard.

## Usage

- `/dashboard` — launch the dashboard (requires tmux)
- `/dashboard off` — suppress the dashboard hint in pipeline commands
- `/dashboard on` — re-enable the dashboard hint

## Behavior

### `/dashboard` (no arguments, or argument is empty)

1. **Check tmux** — verify we're inside a tmux session:
   ```bash
   if [[ -z "${TMUX:-}" ]]; then
     echo "Dashboard requires tmux. Start a tmux session first, then run /dashboard."
     # STOP here — do not proceed
   fi
   ```

2. **Check if already running** — look for existing dashboard panes:
   ```bash
   # Check if watchers are already running in tmux panes
   if tmux list-panes -F '#{pane_title}' 2>/dev/null | grep -q 'vallorcine'; then
     echo "Dashboard is already running."
     # STOP here
   fi
   ```

3. **Check watchers exist** — verify the watcher scripts are installed:
   ```bash
   if [[ ! -f .claude/watchers/vallorcine_pipeline.sh ]] || [[ ! -f .claude/watchers/vallorcine_stage-detail.sh ]]; then
     echo "Dashboard watcher scripts not found."
     echo ""
     echo "This happens when vallorcine was installed as a plugin or upgraded"
     echo "from a version before v0.4.0. To install the missing files, run:"
     echo "  bash install.sh <project-path>"
     echo "or upgrade with: /upgrade-vallorcine"
     echo ""
     # STOP here — do not proceed
   fi
   ```

4. **Create state directory**:
   ```bash
   mkdir -p .claude/dashboard
   ```

5. **Launch two panes** — split right for pipeline, then split that pane for stage detail:
   ```bash
   PROJECT_ROOT="$(pwd)"
   tmux split-window -h -l 40 -t '{last}' "printf '\\033]2;vallorcine-pipeline\\033\\\\'; bash .claude/watchers/vallorcine_pipeline.sh \"$PROJECT_ROOT\""
   tmux split-window -v -t '{right}' -l 50% "printf '\\033]2;vallorcine-stage\\033\\\\'; bash .claude/watchers/vallorcine_stage-detail.sh \"$PROJECT_ROOT\""
   ```

6. **Confirm**:
   ```
   Dashboard launched. Two panes: pipeline (top-right), stage detail (bottom-right).
   ```

### `/dashboard off`

1. Create the suppression sentinel:
   ```bash
   mkdir -p .claude/dashboard
   touch .claude/dashboard/.nodashboard
   ```

2. Confirm:
   ```
   Dashboard hint suppressed. Run /dashboard on to re-enable.
   ```

### `/dashboard on`

1. Remove the suppression sentinel:
   ```bash
   rm -f .claude/dashboard/.nodashboard
   ```

2. Confirm:
   ```
   Dashboard hint re-enabled. You'll be prompted at next pipeline start.
   ```

## Dashboard hint (for pipeline commands)

Pipeline commands should check at their start whether to show the dashboard hint.
The check is:

```bash
# Show dashboard hint (once per session) if:
#   1. We're in tmux
#   2. Dashboard is not suppressed
#   3. Dashboard is not already running
#   4. We haven't prompted this session
if [[ -n "${TMUX:-}" ]] \
   && [[ ! -f .claude/dashboard/.nodashboard ]] \
   && ! tmux list-panes -F '#{pane_title}' 2>/dev/null | grep -q 'vallorcine' \
   && [[ ! -f .claude/dashboard/.prompted ]]; then
    mkdir -p .claude/dashboard
    touch .claude/dashboard/.prompted
    echo ""
    echo "Dashboard available. Run /dashboard to open, or /dashboard off to suppress."
    echo ""
fi
```

The `.prompted` sentinel prevents repeated hints within a session. It lives in
`.claude/dashboard/` which is gitignored and ephemeral.
