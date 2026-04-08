---
description: "Parallel batch coordinator for independent work units"
argument-hint: "<feature-slug>"
---

# /feature-coordinate "<feature-slug>"

Parallel batch coordinator. Launches independent work units as parallel subagents,
waits for completion, then launches the next batch. Only used when `execution_strategy`
is `balanced` or `speed`.

---

## Step 0 — Pre-flight

Read `.feature/<slug>/status.md`.

- Verify `execution_strategy` is `balanced` or `speed`. If it is `cost` or `not-set`:
  ```
  🔀 COORDINATOR · <slug>
  ───────────────────────────────────────────────
  Execution strategy is '<strategy>' — parallel coordination not applicable.
  Use /feature-test "<slug>" to start sequential execution.
  ```
  Stop.

- Read `automation_mode` from status.md.

Display opening header:
```
───────────────────────────────────────────────
🔀 COORDINATOR · <slug>
───────────────────────────────────────────────
```

---

## Step 0a — Progress tracking

Use TodoWrite to show progress in the Claude Code UI (visible via Ctrl+T).
Each TodoWrite call replaces the full list — always include all items.

**The coordinator owns TodoWrite in parallel mode.** Subagents launched by the
coordinator MUST NOT call TodoWrite — their writes would overwrite the
coordinator's checklist since only one list exists at a time.

Build the checklist dynamically from the Work Units table. Include pipeline
context and one item per work unit. Use `activeForm` to show which stage each
active unit is in.

Example for 3 units mid-batch:
```json
[
  {"id": "pipeline-scoping", "content": "Scoping", "status": "completed", "priority": "medium"},
  {"id": "pipeline-domains", "content": "Domain analysis", "status": "completed", "priority": "medium"},
  {"id": "pipeline-planning", "content": "Work planning", "status": "completed", "priority": "medium"},
  {"id": "pipeline-parallel", "content": "Parallel execution", "status": "in_progress", "priority": "high",
   "activeForm": "Batch 1 — 2 units running"},
  {"id": "pipeline-pr", "content": "PR draft", "status": "pending", "priority": "medium"},
  {"id": "wu-1", "content": "WU-1: Auth types", "status": "in_progress", "priority": "high",
   "activeForm": "implementing — 4/6 tests passing"},
  {"id": "wu-2", "content": "WU-2: Token store", "status": "in_progress", "priority": "high",
   "activeForm": "writing tests"},
  {"id": "wu-3", "content": "WU-3: Middleware", "status": "pending", "priority": "high"},
  {"id": "merge", "content": "Merge logs and integration tests", "status": "pending", "priority": "medium"}
]
```

Update unit items to `completed` as each subagent returns. The coordinator
can update `activeForm` on in-progress units based on their per-unit status.md
between batches.

---

## Step 1 — Build dependency graph and display execution plan

Read the Work Units table from status.md. Build the full dependency graph.

**If all units are `complete`:** skip to Step 4 (merge + finalize).

Compute the **critical path** — the longest chain of dependent units. This
determines the minimum wall-clock time regardless of parallelism.

Display the execution plan:
```
── Execution plan ─────────────────────────────
Dependency graph:
  WU-1 ──┐
          ├── WU-3 ── WU-5
  WU-2 ──┘
  WU-4 (independent)

Critical path: WU-1 → WU-3 → WU-5 (3 sequential stages)
Max parallelism: 3 units (WU-1 + WU-2 + WU-4)

Ready now:
  → WU-1: <name>
  → WU-2: <name>
  → WU-4: <name>

Waiting:
  ○ WU-3: <name> (needs WU-1, WU-2)
  ○ WU-5: <name> (needs WU-3)

Previously completed:
  ✓ <any completed units>
```

**In `speed` mode:** launch all ready units immediately — no batch grouping.
As each unit completes, check if new units are unblocked and launch them in
the same cycle. Units start as soon as their dependencies resolve, not when
an entire batch finishes.

**In `balanced` mode:** group ready units into batches as before, wait for
the full batch, then launch the next. This is simpler and uses fewer parallel
sub-agents.

---

## Step 2 — Launch and monitor (speed mode)

The coordinator runs a **completion-driven loop** instead of batch-wait:

1. **Launch** all currently ready units as parallel sub-agents (invoke
   `/feature-test "<slug>" --unit WU-<n>` for each). All invocations in a
   single response — Claude Code runs them in parallel.

2. **Monitor** — as each sub-agent returns:
   a. **Verify working directory** — run `pwd` and confirm it is the project
      root (not a `.claude/worktrees/` path). If in a worktree path, `cd` back
      to the project root before any file operations. This prevents phantom
      "file not found" errors after subagent completion.
   b. Read its `units/WU-N/status.md` to check the result.
   c. If refactor complete → mark `complete` in the feature-level Work Units table.
   d. If escalation → handle immediately (see escalation handling below).
   e. **Check for newly unblocked units** — scan the Work Units table for any
      unit whose dependencies are now all `complete` and status is `not-started`.
   f. If new units are ready → launch them immediately as sub-agents.
      Display: `  → WU-<n>: <name> (unblocked by WU-<completed>)`

3. **Repeat** until all units are complete or blocked by escalations.

This means a unit deep in the dependency chain starts as soon as its last
dependency finishes — it doesn't wait for unrelated units in an earlier "batch"
to complete. For a graph like:

```
WU-1 (fast, 2 constructs) ──→ WU-3 ──→ WU-5
WU-2 (slow, 6 constructs) ──↗
WU-4 (independent)
```

Batch mode would run: {WU-1, WU-2, WU-4} → wait for all → {WU-3} → {WU-5}.
Speed mode runs: {WU-1, WU-2, WU-4} → WU-1 finishes → can't start WU-3 yet
(needs WU-2) → WU-4 finishes → WU-2 finishes → immediately start WU-3 →
WU-3 finishes → immediately start WU-5. If WU-3 only depended on WU-1, it
would start as soon as WU-1 finishes without waiting for WU-2.

**While sub-agents are running:** update the TodoWrite checklist with each
unit's latest state by reading per-unit `status.md`:

```
"activeForm": "<stage> — <substage>"
```

Examples:
- `"testing — writing tests (3 of 5)"`
- `"implementing — 4/6 tests passing"`
- `"refactor — 2c security"`

---

## Step 2b — Launch and monitor (balanced mode)

Balanced mode uses the simpler batch approach:

1. **Compute batch** — find all units where status is `not-started` AND all
   dependencies are `complete`. Group as the current batch.
2. **Launch** all batch units as parallel sub-agents.
3. **Wait** for all sub-agents in the batch to return.
4. **Verify working directory** — same as speed mode Step 2.2a. Always
   confirm `pwd` is the project root after subagents complete.
5. **Check results** — mark completed units, handle escalations.
6. **Loop** — go back to step 1 for the next batch.

This is the same behaviour as before — predictable batch boundaries with
clear checkpoints between batches.

---

## Step 2c — Stall detection

Subagents can hang silently with no output or progress. The coordinator
cannot set hard timeouts (Claude Code does not expose this), but it should
surface staleness to the user.

**Between sub-agent checks:** if a sub-agent has been running with no status.md
update for an extended period, note this in the TodoWrite checklist:

```
"activeForm": "running — no status update in >10m ⚠️"
```

**If the user reports a stall or asks about progress:** check the unit's
`status.md` for the last substage update. If the substage has not changed,
advise the user:

```
── Possible stall ────────────────────────────
  WU-<n>: last status update was <substage> at <time>

Options:
  wait   — continue waiting (may resolve on its own)
  kill   — cancel the subagent and retry this unit
  skip   — mark unit as failed, block dependents
```

This is advisory — the coordinator cannot kill subagents directly. The user
must cancel manually if needed. The coordinator can then retry the unit.

---

## Step 3 — Escalation handling

When any unit hits an escalation (from sub-agent return or status.md check):

```
── Escalation ────────────────────────────────
  WU-<n>: <escalation details from unit status>

How would you like to proceed?
  retry  — re-run the failed unit
  skip   — mark as failed, continue with dependent units blocked
  stop   — pause for manual intervention
```
Wait for user input. In speed mode, other running sub-agents continue while
waiting — the escalation only blocks the failed unit and its dependents.

## Step 3b — Completion display

When all units are complete (or between batches in balanced mode):

```
── Progress ──────────────────────────────────
  ✓ WU-<n>: <name> — <n> tests, <n> constructs
  ✓ WU-<n>: <name> — <n> tests, <n> constructs
  → WU-<n>: <name> — in progress
  ○ WU-<n>: <name> — waiting on <deps>
```

- If `automation_mode: autonomous` and units remain: continue the loop.
- If `automation_mode: manual` and units remain:
  ```
  More units ready.
  ```

  Use AskUserQuestion with two options:
  - "Continue" (description: process next ready units)
  - "Stop" (description: display resume command and stop)

  If "Continue": continue. If "Stop": display resume command and stop.

---

## Step 4 — Merge and finalize

All work units are complete.

### 4a — Merge cycle logs

Read each `units/WU-N/cycle-log.md`. Merge into feature-level `cycle-log.md`:
- Interleave entries by timestamp
- Prefix each entry header with `[WU-N]`
- Example: `## <YYYY-MM-DD> — [WU-2] tests-written`

### 4b — Post-coordination verification

Before running integration tests, verify the final state is clean. This
catches worktree confusion, silent failures, or partial writes from subagents.

1. **Verify working directory** — confirm `pwd` is the project root.

2. **Verify all expected files exist** — for each work unit, read the unit's
   `work-plan.md` section and check that every file listed in its constructs
   exists on disk. Collect any missing files.

3. **Run the full test suite** (5-minute Bash timeout) — not per-unit tests,
   the complete suite from the project root (read test command from
   project-config.md). If the suite hangs, investigate before retrying.
   This catches cross-unit regressions that per-unit tests wouldn't see.

4. **Report results:**
   ```
   ── Verification ──────────────────────────────
     Files: <n>/<n> present ✓
     Tests: <n> passing ✓
   ```

   If any files are missing or tests fail:
   ```
   ── Verification ──────────────────────────────
     Files: <n>/<total> present
       MISSING: <path> (WU-<n>)
       MISSING: <path> (WU-<n>)
     Tests: <n> failing

   How would you like to proceed?
     retry <WU-n>  — re-run a specific work unit
     continue      — proceed despite issues
     stop          — pause for manual investigation
   ```

   Wait for user input before continuing.

### 4c — Run integration tests

Run integration tests (equivalent to refactor Step 2f). In parallel mode,
integration tests are deferred from individual refactor stages to here.

Read project-config.md for the `Run integration tests` command.

**If an integration test command is configured:**

Run it. Three possible outcomes:

1. **All pass** — note the result and continue.
   ```
   Integration tests: <n> passing ✓
   ```

2. **Some fail** — determine whether the failure is related to this feature:
   - If YES: treat as a bug. Fix, re-run unit tests, re-run integration tests.
     Append `integration-test-failure` to cycle-log.md.
   - If NO: note as pre-existing. Append `integration-test-warning` to cycle-log.md.
     Continue.

3. **Cannot run** — note and continue.

**If no integration test command is configured:** check for integration test
directories. If found, note for manual check. If not found, skip silently.

### 4d — Update feature-level status

Update feature-level status.md:
- All units marked `complete` in Work Units table
- Stage → `refactor`, substage → `refactor complete`

### 4e — Hand off

Read the Stage Completion table from status.md and display a token summary:

```
───────────────────────────────────────────────
🔀 COORDINATOR complete · <slug>
───────────────────────────────────────────────
All <n> work units complete.
Integration tests: <result>
Merged cycle log: .feature/<slug>/cycle-log.md

── Token Summary ──────────────────────────────
  | Stage          | Est.   | Actual           | Δ      |
  |----------------|--------|------------------|--------|
  | Scoping        | ~5K    | 4.2K in / 3K out | -16%   |
  | Domains        | ~6K    | 8.1K in / 2K out | +35%   |
  | Planning       | ~8K    | 7.5K in / 5K out | -6%    |
  | Testing        | ~5K    | 6.0K in / 4K out | +20%   |
  | Implementation | ~8K    | 9.2K in / 7K out | +15%   |
  | Refactor       | ~6K    | 5.8K in / 3K out | -3%    |
  |----------------|--------|------------------|--------|
  | Total          | ~38K   | 40.8K in / 24K out       |
───────────────────────────────────────────────
```

- If `automation_mode: autonomous`: invoke `/feature-pr "<slug>"` immediately.
- If `automation_mode: manual`:
  ```
  Feature is ready for PR.
  ```

  Use AskUserQuestion with two options:
  - "Create PR" (description: invoke /feature-pr)
  - "Stop" (description: display resume command)

  If "Create PR": invoke `/feature-pr "<slug>"`.
  If "Stop": display `Next: /feature-pr "<slug>"` and stop.

---

## Write authority

The coordinator writes to:
- `.feature/<slug>/status.md` — Work Units table (unit status), batch tracking comments
- `.feature/<slug>/cycle-log.md` — merged log from per-unit logs

The coordinator does NOT write to per-unit files (`units/WU-N/`). Those are owned
by the TDD subagents.
