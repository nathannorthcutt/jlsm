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

## Step 1 — Compute batch

Read the Work Units table from status.md.

Find all units where:
- Status is `not-started` AND all `Depends On` units are `complete`

**If none ready but some in-progress:**
```
🔀 COORDINATOR · <slug>
───────────────────────────────────────────────
Units are in progress — waiting for completion.
Re-run /feature-coordinate "<slug>" when current batch finishes.
```
Stop.

**If all units are `complete`:** skip to Step 4 (merge + finalize).

Group ready units as the current batch. Update `<!-- current_batch: -->` in
status.md.

Display batch plan:
```
── Batch <n> ──────────────────────────────────
Previously completed:
  ✓ WU-<n>: <name>

Launching now (parallel):
  → WU-<n>: <name>
  → WU-<n>: <name>

Remaining:
  ○ WU-<n>: <name> (blocked on <deps>)
```

---

## Step 2 — Launch parallel subagents

For each unit in the batch: invoke `/feature-test "<slug>" --unit WU-<n>` as a
sub-agent.

**All invocations in a single response** — Claude Code runs them in parallel.

Each subagent runs the full test → implement → refactor cycle for its unit.
Subagents always chain internally (autonomous within a unit — even in manual mode,
the manual checkpoint is at the batch boundary, not within units).

Wait for all subagents to return.

---

## Step 3 — Batch completion

Read each unit's `units/WU-N/status.md` to check results.

For each unit:
- If refactor complete → mark `complete` in feature-level Work Units table in status.md

If any unit hit an escalation:
```
── Batch <n> escalation ───────────────────────
  WU-<n>: <escalation details from unit status>

How would you like to proceed?
  retry  — re-run the failed unit
  skip   — mark as failed, continue with dependent units blocked
  stop   — pause for manual intervention
```
Wait for user input.

If all succeeded:
```
── Batch <n> complete ─────────────────────────
  ✓ WU-<n>: <name> — <n> tests, <n> constructs
  ✓ WU-<n>: <name> — <n> tests, <n> constructs
```

- If `automation_mode: autonomous`: loop back to Step 1 for next batch.
- If `automation_mode: manual`:
  ```
  Next batch ready.
    Type **yes**  ·  or: stop
  ```
  If "yes": loop back to Step 1.
  If "stop": display `Next: /feature-coordinate "<slug>"` and stop.

---

## Step 4 — Merge and finalize

All work units are complete.

### 4a — Merge cycle logs

Read each `units/WU-N/cycle-log.md`. Merge into feature-level `cycle-log.md`:
- Interleave entries by timestamp
- Prefix each entry header with `[WU-N]`
- Example: `## <YYYY-MM-DD> — [WU-2] tests-written`

### 4b — Run integration tests

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

### 4c — Update feature-level status

Update feature-level status.md:
- All units marked `complete` in Work Units table
- Stage → `refactor`, substage → `refactor complete`

### 4d — Hand off

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
    Type **yes**  ·  or: stop
  ```
  If "yes": invoke `/feature-pr "<slug>"`.
  If "stop": display `Next: /feature-pr "<slug>"` and stop.

---

## Write authority

The coordinator writes to:
- `.feature/<slug>/status.md` — Work Units table (unit status), batch tracking comments
- `.feature/<slug>/cycle-log.md` — merged log from per-unit logs

The coordinator does NOT write to per-unit files (`units/WU-N/`). Those are owned
by the TDD subagents.
