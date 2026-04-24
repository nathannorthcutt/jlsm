---
description: "Run adversarial audit pipeline against shipped code"
argument-hint: "<entry-point>"
effort: high
---

# /audit "<entry-point>"

Runs the adversarial audit pipeline. Accepts any entry point: feature slug,
file list, spec reference, or prior audit report path. Finds bugs, proves
them with failing tests, fixes the code, and leaves the codebase clean.

---

## Orchestrator discipline — MANDATORY

You are a state machine. Your ONLY job is to dispatch subagents serially
and relay their one-line summaries. You use TodoWrite to show progress.

**What you may do:**
- Check file existence (does audit-report.md exist? yes/no)
- Read one-line return summaries from subagents
- Parse counts from return summaries (cluster count, finding count,
  lens count)
- Display progress via TodoWrite
- Decide which subagent comes next based on return summaries
- Run mechanical pipeline scripts (reconcile-cards.py, extract-views.py)
  — these are data transformations, not analytical work

**What you must NEVER do — these are HARD RULES, not suggestions:**
- DO NOT read any pipeline output file (scope-definition.md,
  cluster-*-packet.md, suspect-*.md, prove-fix-*.md,
  audit-report.md, exploration-decisions.jsonl, construct-cards.yaml,
  analysis-cards.yaml, lens-*-cards.yaml, active-lenses.md)
- DO NOT read source code files
- DO NOT run build/test commands (./gradlew, npm test, cargo test, etc.)
- DO NOT run git commands (git diff, git log, etc.)
- DO NOT parse XML test results, grep test output, or count test files
- DO NOT write any pipeline output file
- DO NOT make analytical decisions about scope, bugs, tests, or fixes
- DO NOT do the work of ANY subagent yourself, even if it seems simple

**How to detect a violation:** If you are about to use Bash, Read, Write,
Edit, or Grep for anything other than checking file existence or reading
a return summary, STOP. That work belongs in a subagent.

**Why:** Every tool call result accumulates in your context window. You
exist for the entire pipeline. A single file read adds ~500 tokens that
you carry through every subsequent turn. Subagents absorb work in
isolated context windows that are discarded after they return.

---

## Pipeline overview

Five jobs, executed serially. All subagents run one at a time.
Mechanical scripts run between subagent phases (no LLM cost).

```
Scope:      Classification → Exploration → Card Construction
            (interactive)    (discovery)    (assertion sweeps)
                                  ↓
            [reconcile-cards.py] → [extract-views.py detect]
                                  ↓
            Domain Pruning → [extract-views.py project] → Assembly
            (lens challenge)                               (domain-lens clustering)
                                  ↓
Suspect:    Lens1/C1 → Lens1/C2 → ... → Lens2/C1 → ... → LensK/CN
                                  ↓
Prove-Fix:  Finding 1 → Finding 2 → ... → Finding M
            (sequential — each finding: write test → confirm/impossible → fix)
                                  ↓
Report:     [aggregate-results.py] → Single subagent → audit-report.md + audit-prior.md
            (pre-aggregate, then cross-domain finding combination)
                                  ↓
Reconcile:  Single subagent → spec-updates.md + kb-suggestions.md
            (optional — only when .spec/ exists)
```

---

## Determine entry point

Parse the argument to determine what kind of audit this is:

- **Feature slug** (e.g., "float16-vector-support"): look for
  `.feature/<slug>/` directory
- **File path or glob** (e.g., "src/index/*.java"): direct file audit
- **Spec reference** (e.g., "spec:F01"): audit code against spec
- **Prior report** (e.g., "audit-report.md" path): incremental audit

If the argument is ambiguous, ask the user using AskUserQuestion:

- Question: "What would you like to audit?"
- Options:
  1. "Feature" — audit a feature by slug (you'll ask for the slug next)
  2. "Specific files" — audit files by path or glob pattern
  3. "Spec coverage" — audit code against a spec (provide spec ID)
  4. "Prior report" — incremental audit from a previous report

Store the entry point type and value for the Classification subagent.

---

## Spec-state gate (spec: entry points only)

Before proceeding, verify the target spec is APPROVED. `/audit` on a
DRAFT or INVALIDATED spec cannot do conformance checking — there is no
authoritative contract to prove code against. Adversarial exploration
alone would produce misleading findings that the user will interpret as
conformance bugs.

```bash
# Only for "spec:" entry points. Skip for feature / file / prior-report.
bash .claude/scripts/audit-state-gate.sh "<spec-id>"
```

If the script exits non-zero, stop immediately. It prints a clear error
message and the recommended next step (usually `/spec-verify` to promote
DRAFT → APPROVED). Do not ask the user to override — a non-APPROVED spec
makes /audit structurally unsound for this entry point.

For **feature-slug** or **file** entry points, do not run this check —
those entry points don't target a specific spec, and if specs are
resolved during classification, that phase handles state awareness.

---

## Budget

After determining the entry point, ask the user for a budget using
AskUserQuestion:

- Question: "What's your budget? Budget is split proportionally:
  ~30% discovery, ~45% prove-fix, ~15% reporting. Discovery costs
  ~$8-12 per file; prove-fix costs ~$5 per finding."
- Options:
  1. "$300 (default)" — full scope for most features
  2. "$150" — may narrow to 4-5 files on larger features
  3. "$80 (focused)" — 2-3 files, ~7 findings
  4. "Unlimited" — no cap on spending

The user can type a custom dollar amount via "Other". If the custom
amount is below $50, display a note: "Budgets under $50 typically
cover only 1-2 files. Consider $80+ for meaningful results." Still
allow the user to proceed.

- If the user picks a dollar amount: store as `AUDIT_BUDGET`
- If the user picks "Unlimited": leave `AUDIT_BUDGET` empty

**Budget allocation model** (computed when `AUDIT_BUDGET` is set):

```
DISCOVERY_ALLOC  = AUDIT_BUDGET * 0.30
SUSPECT_ALLOC    = AUDIT_BUDGET * 0.10
PROVE_FIX_ALLOC  = AUDIT_BUDGET * 0.45
POST_RESERVE     = max(AUDIT_BUDGET * 0.15, 10)
```

The budget is a **planning target**, not a hard wall. Each phase scopes
its work to fit its allocation. Post phases (test cleanup, report,
reconciliation) always run regardless of budget. See "Budget control"
in Job 3 for the soft cap mechanism.

---

## Resume detection

Before starting any work, run the state management script to determine
pipeline state. This is **mechanical, not LLM judgment**.

### For new audits

After determining the entry point, locate or initialize the audit state:

```bash
# Locate the audit directory
AUDIT_DIR=$(python3 .claude/prompts/audit/audit-state.py locate <type> <value>)

# Check for existing state
python3 .claude/prompts/audit/audit-state.py resume "$AUDIT_DIR"
```

The resume command outputs one of:
- `RESUME_AT=init` — no prior runs, start fresh
- `RESUME_AT=new_run` — prior run is complete, start a new run
- `RESUME_AT=<step>` + `RUN_DIR=<path>` — interrupted run, resume at step

### When starting fresh (init or new_run)

```bash
python3 .claude/prompts/audit/audit-state.py init <type> <value>
```

This creates the run directory and state.json. Parse `INIT_RUN_DIR` from
the output — all pipeline artifacts for this run go in that directory.

### When resuming

If resuming at prove-fix, run `extract-findings.sh` first to get the
accurate remaining count (the script filters out findings with existing
output files):

```bash
bash .claude/scripts/extract-findings.sh <RUN_DIR>
```

Parse the "N remaining" count from the script output. Display to the user
with the actual remaining count:

```
Resuming audit — picking up at <RESUME_AT>.
Run directory: <RUN_DIR>
<N already processed>, <M remaining> findings to prove-fix.
```

Use AskUserQuestion to confirm:
- "Resume" — process the remaining findings
- "Restart fresh" — discard this run and start a new audit
- "Cancel" — stop

Initialize TodoWrite with completed steps already checked. Start from
the resume step.

### After each step completes

Update state immediately:
```bash
python3 .claude/prompts/audit/audit-state.py complete "$AUDIT_DIR" <step> [key=value ...]
```

Include metadata when available:
- After assembly: `clusters=9 lenses=4`
- After suspect: `findings=39`
- After prove-fix: `fixed=20 impossible=10 fix_impossible=0 deferred=0`

**All pipeline artifacts go in the run directory** (`RUN_DIR`), not in
`.feature/<slug>/` directly. This keeps runs isolated — no stale files
from prior runs can confuse the pipeline.

---

## Progress tracking

Before launching the first subagent (or after resume detection),
initialize the TodoWrite checklist:

```
◻ Step 1.1: Classification
◻ Step 1.2: Exploration
◻ Step 1.3: Card Construction
◻ Step 1.4: Reconciliation + Lens Detection
◻ Step 1.5: Domain Pruning
◻ Step 1.6: View Projection
◻ Step 1.7: Assembly
◻ Job 2: Suspect
◻ Job 2b: Pre-prove gates
◻ Job 3: Prove-Fix
◻ Job 3b: Test Cleanup
◻ Job 4: Report
◻ Job 5: Reconciliation (if .spec/ exists)
```

Update rules:
- **Steps 1.1–1.7:** Mark each step complete immediately when it finishes.
  Do NOT mark the next step complete until it actually finishes.
- **Jobs 2–3:** Update the label with running counts as subagents return.
  Examples: "Suspect (3/8 clusters, 12 findings)", "Prove-Fix (5/12
  findings, 4 fixed, 1 impossible)". Mark complete only when all subagents
  for that job finish.
- **Job 4:** Mark complete when Report returns.
- **Job 5:** Only shown if `.spec/` exists. Mark complete when Reconciliation
  returns.

---

## Job 1: Scope

### Step 1.1 — Classification subagent

Launch a subagent. Tell it:

> You are the Classification subagent for an audit pipeline.
> Read `.claude/prompts/audit/classification.md` for your complete
> instructions.
>
> Entry point: <type> = <value>
> Working directory: <cwd>
>
> Resolve scope deterministically. Do NOT ask the user questions.
> Write your outputs and return a summary.

The subagent will:
- Resolve the entry point to initial file paths
- Detect prior audit reports and load prior work
- Gather specs, KB entries, ADRs
- Detect language and project structure
- Write classification.md with scope summary

Expected return format:
"Classification complete — <n> initial files, <n> specs resolved,
prior_round=<yes|no>, language=<lang>"

**After Classification returns, apply the budget scope gate:**

Parse the file count from the return summary. If `AUDIT_BUDGET` is set,
compute the discovery allocation and maximum affordable files:

```
max_files = floor(DISCOVERY_ALLOC / 10)   # ~$10 per file
```

**Case 1: Budget fits the scope** (`max_files >= file_count` or no budget):

Display the return summary and use AskUserQuestion:
- Question: "Audit scope: `<n>` files, budget `$<budget>`.
  Estimated total: ~$`<file_count * 30>` (within budget)."
- Options:
  1. "Proceed" — explore all files
  2. "Adjust scope" — change which files to include
  3. "Cancel" — stop the pipeline

**Case 2: Budget requires narrowing** (`max_files < file_count`):

The orchestrator narrows scope automatically to the top `max_files`
files from classification's priority-ordered list. Display:

```
Audit scope: <n> files, budget $<budget>
  Budget covers ~<max_files> of <n> files for full analysis.
  Focusing on the <max_files> highest-risk files:
    1. <file> — <rationale from classification>
    2. <file> — <rationale>
    ...
  Remaining <n - max_files> files deferred to a follow-up round.
```

Use AskUserQuestion:
- Question: (the text above)
- Options:
  1. "Proceed" — explore the selected files (recommended)
  2. "Override" — choose different files to include
  3. "Raise budget to $`<suggested>`" — increase budget to cover all files
  4. "Cancel" — stop the pipeline

If "Override": ask which files to include/exclude and adjust. This is
the escape hatch for when the user knows something classification
doesn't.

If "Raise budget": update `AUDIT_BUDGET` and recompute allocations.
Proceed with the full file list.

**Passing scope to Exploration:** When scope was narrowed, pass ONLY the
selected files to the Exploration subagent (not the full list). Include
a note in the subagent prompt: "Scope narrowed to <n> of <total> files
for budget. Explore only these files."

Mark **Step 1.1: Classification** complete.

### Step 1.2 — Exploration subagent

Launch a subagent. Tell it:

> You are the Exploration subagent for an audit pipeline.
> Read `.claude/prompts/audit/exploration.md` for your complete
> instructions.
>
> Read the classification output at `.feature/<slug>/classification.md`
> for your starting files, prior work summary, and context package.
>
> Write exploration-decisions.jsonl as you work — every tiering
> decision, clearing check, and frontier stop must be logged.
>
> Return a summary line when done.

Expected return format:
"Exploration complete — <n> analyze, <n> boundary, <n> ignore,
<n> domain signals, frontier=<n> stops"

Mark **Step 1.2: Exploration** complete.

### Step 1.3 — Card Construction subagent

Launch a subagent. Tell it:

> You are the Card Construction subagent for an audit pipeline.
> Read `.claude/prompts/audit/card-construction.md` for your complete
> instructions.
>
> Read the exploration output at `.feature/<slug>/exploration-graph.md`
> for the construct list, locations, and boundary contracts.
> Read the classification context at `.feature/<slug>/classification.md`.
>
> Build construct cards using assertion-first sweeps.
>
> Return a summary line when done.

Expected return format:
"Cards built — <n> constructs, <n> batches, <n> with assumptions,
<n> with empty contracts"

Mark **Step 1.3: Card Construction** complete.

### Step 1.4 — Reconciliation + View Detection (mechanical scripts)

Run the reconciliation script to add incoming edges and derive
co_mutators/co_readers:

```bash
python3 .claude/prompts/audit/reconcile-cards.py .feature/<slug>/
```

Expected output:
"Reconciled <n> cards — <n> inconsistencies, <n> with co_mutators"

Then run view extraction phase 1 to detect candidate domain lenses:

```bash
python3 .claude/prompts/audit/extract-views.py detect .feature/<slug>/
```

Expected output:
"Detected <n> candidate lenses, <n> eliminated — active: <lens names>"

Mark **Step 1.4: Reconciliation + Lens Detection** complete.

### Step 1.5 — Domain Pruning subagent

Launch a subagent. Tell it:

> You are the Domain Pruning subagent for an audit pipeline.
> Read `.claude/prompts/audit/domain-pruning.md` for your complete
> instructions.
>
> Read `.feature/<slug>/active-lenses.md` for candidate lenses.
> Read `.feature/<slug>/exploration-graph.md` for domain signal
> corroboration.
>
> Challenge each candidate lens. Update active-lenses.md with
> CONFIRMED or PRUNED status.
>
> Return a summary line when done.

Expected return format:
"Domain pruning — <n> confirmed, <n> pruned: <pruned names or 'none'>"

Mark **Step 1.5: Domain Pruning** complete.

### Step 1.6 — View Projection (mechanical script)

Run view extraction phase 2 to produce per-lens card projections:

```bash
python3 .claude/prompts/audit/extract-views.py project .feature/<slug>/
```

Expected output:
"Projected <n> lenses, <n> total card projections — <details>"

Mark **Step 1.6: View Projection** complete.

### Step 1.7 — Assembly subagent

Launch a subagent. Tell it:

> You are the Assembly subagent for an audit pipeline.
> Read `.claude/prompts/audit/assembly.md` for your complete instructions.
>
> Read the reconciled cards at `.feature/<slug>/analysis-cards.yaml`,
> the confirmed lenses at `.feature/<slug>/active-lenses.md`,
> the per-lens projections at `.feature/<slug>/lens-*-cards.yaml`,
> and the classification context at `.feature/<slug>/classification.md`.
>
> DO NOT read source code. Work only with structured data.
>
> Return a summary line when done.

Expected return format:
"Assembly complete — <n> lenses, <n> total clusters, <n> unique
constructs clustered, <n> constructs in multiple lenses, <n> excluded"

Parse the lens count and total cluster count from the return. You need
these to dispatch Suspect subagents.

**Cost checkpoint:** If `AUDIT_BUDGET` is set, run:
```bash
bash .claude/scripts/audit-budget.sh
```
Display to the user:
```
Discovery complete — $<cost> of $<budget> spent
  <n> clusters across <n> lenses identified
  Remaining: ~$<budget - cost - POST_RESERVE> for suspect + prove-fix
```

Mark **Step 1.7: Assembly** complete.

---

## Job 2: Suspect

For each cluster packet (organized by lens), launch a subagent serially.
The Assembly return tells you the total cluster count across all lenses.
Cluster packets are named `cluster-<lens>-<N>-packet.md`.

For each cluster, tell the subagent:

> You are the Suspect subagent for <lens>/cluster <N>.
> Read `.claude/prompts/audit/suspect.md` for your complete instructions.
>
> Your cluster packet is at
> `.feature/<slug>/cluster-<lens>-<N>-packet.md`.
> It contains everything you need — construct cards, domain lens,
> analysis guidance, boundary contracts, specs, KB. Read source files
> directly using the file paths and line ranges from the construct cards.
>
> Your analysis is scoped to the <lens> domain. Focus your attack
> reasoning on <lens> concerns as described in the packet's domain
> guidance section.
>
> DO NOT read any file not referenced in your construct cards.
> DO NOT query specs, KB, or ADRs — they are embedded in your packet.
>
> Return a summary line when done.

Expected return format:
"Suspect <lens>/C<N> — <n> findings, <n> cleared, <n> boundary
observations, <n> card-driven"

After each subagent returns:
1. Parse finding count from return
2. Accumulate total findings across all clusters and lenses
3. Update **Job 2** label: "Suspect (<completed>/<total> clusters,
   <accumulated> findings)"

After all Suspect subagents complete:

**Cost checkpoint:** If `AUDIT_BUDGET` is set, run:
```bash
bash .claude/scripts/audit-budget.sh
```
Display to the user:
```
Suspect complete — $<cost> of $<budget> spent
  <n> findings to prove-fix (~$<findings * 5> estimated)
  Prove-fix capacity: ~<floor((budget - cost - POST_RESERVE) / 5)> of <n> findings within budget
```

Mark **Job 2: Suspect** complete.
If zero total findings, skip to Job 4 (Report).

---

## Job 3: Prove-Fix

Process findings ONE AT A TIME, sequentially. Each finding gets its own
subagent with a fresh context window. The subagent writes a test to prove
the bug, then fixes the source code if confirmed.

### Build the finding list

After Suspect completes, run the finding list extraction script:

```bash
bash .claude/scripts/extract-findings.sh .feature/<slug>/audit/<run-dir>
```

This produces `finding-list.txt` — one line per finding, pipe-delimited:
`<finding-id>|<title>|<construct>|<lens>|<cluster>|<suspect-file>`

### Pre-prove gates (mechanical scripts — no LLM cost)

Run two scripts to optimize the dispatch queue before prove-fix:

```bash
# Gate 1: Reorder findings so cross-lens duplicates run after their primary
python3 .claude/prompts/audit/dedup-findings.py .feature/<slug>/audit/<RUN_DIR>/

# Gate 2: Check for existing adversarial tests that cover findings
python3 .claude/prompts/audit/check-test-coverage.py .feature/<slug>/audit/<RUN_DIR>/ .
```

**Dedup** identifies behavioral duplicates across domain lenses and reorders
the dispatch queue so the primary finding runs first. After the primary
confirms and fixes, Phase 0 catches duplicates in ~3 turns instead of ~35.
No findings are skipped. If `dispatch-order.txt` exists, use it as the
dispatch order instead of the default lens/cluster ordering.

**Coverage check** matches findings against existing adversarial tests from
prior audit rounds. Covered findings get stub `prove-fix-*.md` files with
`Result: COVERED_BY_EXISTING_TEST` — the existing "skip findings with
output files" mechanism handles the rest.

Report gate results to the user:
```
Pre-prove gates:
  Dedup: N duplicates in M groups (reordered for Phase 0 cascade)
  Coverage: K findings covered by existing tests
  Active: J findings to prove-fix
```

### Build the dispatch queue

Read `finding-list.txt` to build the dispatch queue. If `dispatch-order.txt`
exists (from dedup), use its ordering. The extraction script automatically
filters out findings that already have `prove-fix-*.md` output files — so
covered findings and resumed findings are skipped automatically.
**Do NOT read suspect files directly** — the script already extracted
what you need and reading suspect files would add thousands of tokens
to your context.

### Lens → test class mapping

Each domain lens has ONE shared adversarial test class. Determine the
test directory from the project's build structure (e.g.,
`src/test/java/<package>/` for Java). Test class naming:

| Lens | Test class name |
|------|----------------|
| shared_state | `SharedStateAdversarialTest` |
| contract_boundaries | `ContractBoundariesAdversarialTest` |
| data_transformation | `DataTransformationAdversarialTest` |
| dispatch_routing | `DispatchRoutingAdversarialTest` |
| resource_lifecycle | `ResourceLifecycleAdversarialTest` |

Add rows for any other active lenses following the same pattern.

### Processing order

Process findings grouped by lens, then by cluster, then by sequence
number. Shorter lenses first for early signal:

1. Count findings per lens
2. Sort lenses by finding count (ascending)
3. Within each lens, process clusters in order (C1, C2, ...)
4. Within each cluster, process findings in sequence order

### Dispatch loop

For each finding, launch ONE subagent:

> Read `.claude/prompts/audit/prove-fix.md` for your full instructions,
> then execute them.
>
> Your assignment:
> - Finding: <finding ID> — "<finding title>"
> - Construct: <construct name and location>
> - Domain lens: <lens>
> - Test class: <test class path from mapping above>
> - Suspect file: `.feature/<slug>/suspect-<lens>-cluster-<N>.md`
>   (your finding only — <finding ID>)
> - Cluster packet: `.feature/<slug>/cluster-<lens>-<N>-packet.md`
> - Write output to: `.feature/<slug>/prove-fix-<short-id>.md`

For the short-id in the output filename, use the finding ID with dots
replaced by dashes (e.g., F-R5.cb.1.1 → prove-fix-F-R5-cb-1-1.md).

Wait for the subagent to return its summary line. Record the result.

Expected return format:
"<finding ID>: <CONFIRMED_AND_FIXED | IMPOSSIBLE | FIX_IMPOSSIBLE> —
<one-line summary>"

### Error handling

If a subagent fails (API error, timeout, 0 tokens returned, no output
file written):
1. **Retry once** — transient API errors (500, 529) usually resolve.
   Re-launch the same subagent with the same prompt.
2. If the retry also fails: mark the finding as DEFERRED with reason
   "subagent failure — <error description>". Do NOT stop the pipeline.
3. Continue to the next finding. Deferred findings can be retried in
   a subsequent run.
4. Report the failure: `[N/total] <finding ID>: DEFERRED (API error)`

After each subagent returns successfully:
1. Parse the result from the return summary
2. Accumulate totals (fixed, impossible, fix_impossible, deferred)
3. If `AUDIT_BUDGET` is set, run `bash .claude/scripts/audit-budget.sh`
   to get the running cost.
4. Report progress to the user (include cost if budget is set):
   `[N/total] <finding ID>: <result> — <summary> ($<cost>/$<budget>)`
5. Update **Job 3** label: "Prove-Fix (<completed>/<total>, <fixed>
   fixed, <impossible> impossible) — $<cost>/$<budget>"
   (omit cost portion if no budget set)

After all findings are processed, mark **Job 3: Prove-Fix** complete.
If zero confirmed findings, skip to Report.

### Budget control

If `AUDIT_BUDGET` is set:

The budget is a **soft cap**. The goal is to stop prove-fix dispatch
near the budget while guaranteeing post phases always run.

```
STOP_THRESHOLD = AUDIT_BUDGET - POST_RESERVE - 5
```

The `-5` allows one more prove-fix finding (~$5) before stopping.
This means overshoot is bounded to at most one prove-fix cycle plus
post costs.

1. The cost check runs as part of the per-finding progress reporting
   (step 3 above: `bash .claude/scripts/audit-budget.sh`).

2. Compare the returned cost against `STOP_THRESHOLD`:
   - **cost >= STOP_THRESHOLD:** Dispatch the current finding (already
     in progress), then stop. Mark all remaining findings as DEFERRED.
     Report: `Budget reached ($X of $Y). N findings deferred.`
   - **cost < STOP_THRESHOLD:** Continue dispatching normally.

3. In the completion summary, include:
   `Budget: $X spent of $Y limit (N findings deferred)`

4. **Post phases always run.** Jobs 3b (test cleanup), 4 (report),
   and 5 (reconciliation) execute regardless of budget. The budget
   controls only prove-fix dispatch — it never skips post activities
   since those ensure a clean codebase.

If `AUDIT_BUDGET` is not set, omit budget checks entirely — the
pipeline runs unbounded.

---

## Job 3b: Test Cleanup (after prove-fix, before report)

After all prove-fix agents complete, pre-existing tests may assert old
(buggy) behavior that the fixes changed. These are not regressions — they
are tests written against the previous behavior that need updating to
match the corrected behavior.

Launch a subagent:

> You are the Test Cleanup subagent for an audit pipeline.
>
> The prove-fix stage has fixed <N> bugs. Some pre-existing tests may
> now fail because they were asserting the old (buggy) behavior.
>
> Run the full project test suite. For each failing pre-existing test
> (NOT the adversarial tests written by this audit):
>
> 1. Read the failing test method and its assertion
> 2. Read the prove-fix output that changed the behavior it tests
> 3. Check: do the failing test and the passing audit test both
>    reference the SAME spec requirement (via `covers:` comments,
>    finding IDs, or construct/method under test)?
> 4. Classify the failure into one of three categories:
>
> **STALE** — test asserts OLD (buggy) behavior the fix corrected:
>   - Update the test to assert the NEW (correct) behavior
>   - Add a comment: "// Updated by audit <finding ID>: <old behavior>
>     was a bug, now correctly <new behavior>"
>
> **SPEC AMBIGUITY** — both the failing test and the audit test are
> valid interpretations of the same spec requirement, but they expect
> opposite behavior. The spec allows both readings:
>   - Do NOT modify either test
>   - Flag: "SPEC AMBIGUITY: <test method> and audit test <method>
>     interpret <spec>.<requirement> differently.
>     Failing test expects: <behavior A>
>     Audit test expects: <behavior B>
>     The spec requirement allows both — it needs tightening."
>   - Include the spec requirement text and both interpretations
>
> **REGRESSION** — the fix genuinely broke unrelated behavior:
>   - Do NOT modify the test
>   - Flag: "REGRESSION: fix <finding ID> broke <test method> —
>     the fix may need revision"
>
> Return: "Test cleanup — <N> stale updated, <N> spec ambiguities,
> <N> regressions"

Expected return: stale test count + regression count.

If spec ambiguities found, ask the user using AskUserQuestion for each
ambiguity. Include the spec requirement and conflicting test behaviors as
context in the question text:

- Question: "<N> spec ambiguity detected — a requirement allows conflicting
  interpretations:\n\n  <spec>.<requirement>: \"<requirement text>\"\n
  Test A (<method>) expects: <behavior A>\n  Test B (<method>) expects:
  <behavior B>\n\nWhich interpretation should the code follow?"
- Options:
  1. "<behavior A>" — update Test B to match this interpretation
  2. "<behavior B>" — update Test A to match this interpretation
  3. "Defer" — leave both tests, resolve in /spec-author

Apply the user's decision: update the losing test and add a comment
noting the decision. If deferred, note both tests as conflicting in
the audit report.

If regressions found, ask the user using AskUserQuestion before
proceeding to Report. Include the regression details as context in the
question text:

- Question: "<N> pre-existing tests broke as regressions (not stale
  assertions):\n  <test method> — broken by <finding ID>\n\nThese fixes
  may need revision."
- Options:
  1. "Continue to Report" — proceed to the report phase as-is
  2. "Review regressions" — examine the regressions before continuing

If zero failures in the full suite, skip this step entirely — no
subagent needed.

Mark **Job 3b: Test Cleanup** complete.

---

## Job 4: Report

### 4a. Aggregate results (mechanical script — no LLM)

Run the aggregation script to pre-compute summaries for the report
subagent. This replaces ~80 individual file reads with 2 summary files.

```bash
python3 .claude/prompts/audit/aggregate-results.py .feature/<slug>/audit/<RUN_DIR>/
```

Expected output: "Aggregated N findings → prove-fix-summary.md"

If the script fails, fall back to the original behavior (the report
subagent can still read individual files, just at higher cost).

### 4b. Launch report subagent

Launch a single subagent:

> You are the Report subagent.
> Read `.claude/prompts/audit/report.md` for your complete instructions.
>
> Read the pre-aggregated pipeline output files in `.feature/<slug>/audit/<RUN_DIR>/`:
> - scope-definition.md (or classification.md)
> - scope-exclusions.md
> - active-lenses.md
> - prove-fix-summary.md (pre-aggregated — do NOT read individual prove-fix-*.md files)
> - boundary-summary.md (pre-aggregated — do NOT read individual suspect-*.md files)
> - finding-list.txt
>
> Perform cross-domain finding combination for constructs that appear
> in findings from multiple domain lenses.
>
> Write audit-report.md and audit-prior.md.
>
> Return a summary block for display.

Display the Report subagent's summary as the final output:

```
── Audit complete ─────────────────────────────
  Scope: <n> constructs, <n> clusters, <n> domain lenses active
  Findings: <n> suspected, <n> fixed, <n> impossible, <n> fix_impossible
  Cross-domain compositions: <n>
  Deferred (budget): <n>
  Spec conflicts: <n> (or "none")
  Fix-impossible needing resolution: <n> (or "none")
  Pipeline health: fix=<n>% impossible=<n>%
  Report: .feature/<slug>/audit-report.md
───────────────────────────────────────────────
```

If the report summary mentions spec conflicts, display the resolution flow:

```
── Spec conflicts detected ────────────────────
A fix from this audit contradicts an existing spec requirement.
This is a design tradeoff, not a bug — both the fix and the spec
had valid reasons. A decision is needed.

  CONFLICT-1: <description>
    Fix: <finding ID> — <what the fix changed>
    Spec: <spec>.<req> — <requirement text>
    Tradeoff: <why this is a tension>
```

Ask the user using AskUserQuestion for each conflict:

- Question: "CONFLICT-1: <description>\n  Fix: <finding ID> — <what the
  fix changed>\n  Spec: <spec>.<req> — <requirement text>\n  Tradeoff:
  <why this is a tension>\n\nHow should this conflict be resolved?"
- Options:
  1. "Keep fix, update spec" — update the spec requirement to match the
     new behavior and check if other specs depend on the old behavior
  2. "Revert fix, keep spec" — revert the source change and mark the
     finding as FIX_IMPOSSIBLE with the spec requirement as the reason
  3. "Defer" — log the conflict as an open obligation on the spec

For option 1: read the conflicting spec, update the requirement to match
the fix, and run `spec-resolve.sh` to check if any other spec's `requires`
references the changed requirement. If so, flag the downstream specs.

For option 2: revert the fix (git checkout the changed lines), update the
prove-fix output to FIX_IMPOSSIBLE, and note the spec requirement as the
architectural constraint.

For option 3: add `[UNRESOLVED]` marker to the spec requirement and add
an `open_obligations` entry. The spec becomes DRAFT if it was APPROVED.

If the report summary mentions `Fix-impossible needing resolution: N` with
N > 0, walk each RELAX-<N> entry from the report's *Fix Impossible —
needs resolution* section. Each entry represents a confirmed bug whose
fix conflicts with an existing test that pins the old behavior; without
explicit resolution the bug stays in the codebase invisibly.

```
── Fix-impossible findings need resolution ────
A fix was blocked by an existing test that pins the old behavior.
The bug is real (Phase 1 confirmed it). The decision is whether the
test, the spec, or the bug is authoritative.

  RELAX-1: <finding ID> — <one-line summary>
    Confirmed bug: <what the test proved>
    Blocking test: <test method> in <test class>
    Relaxation request: <what the fix would change + which test pins old behavior>
    Suggested route: <relax-test | wontfix | spec-author | defer>
```

Ask the user using AskUserQuestion for each RELAX-<N>:

- Question: "RELAX-<N>: <finding ID> — <summary>\n
  Confirmed bug: <bug description>\n
  Blocking test: <test method> in <test class>\n
  Relaxation request: <what the fix needs + which test pins>\n\n
  How should this be resolved?"
- Options:
  1. "Relax the blocking test" — the test pinned an implementation
     detail. Update it to match the new (correct) behavior, re-run
     prove-fix to confirm the fix now lands.
  2. "Accept as wontfix" — the old behavior is authoritative (backward
     compat, external contract). Record the finding in the report as a
     known-accepted-risk with rationale, and log a non-blocking
     `wontfix:` obligation on the most-relevant spec so `/curate`
     analysis 19 can resurface it if the rationale ever stales.
  3. "Escalate to /spec-author" — the pin test encodes an implicit spec
     requirement that was never written down. Author the spec, then the
     decision becomes a normal spec-conflict.
  4. "Defer to obligation" — log on the relevant spec as an open
     obligation; the spec becomes DRAFT if APPROVED.

For option 1: read the blocking test, update it to assert the new
behavior, re-invoke the prove-fix subagent for this finding (now
unblocked). Update the report with the new outcome (CONFIRMED_AND_FIXED).
Append a `pre-existing-test-modified` entry to the report's *Pre-existing
Test Modifications* section with the diff and proof of safety.

For option 2: append to the report's *Removed Tests (Not Fixed)* section
classified `DESIGN-CHANGE` with the rationale (e.g. "external API
contract pins this behavior; cannot change without versioned migration").
Update the prove-fix output's `fix_detail` to record the user's
rationale. The finding stays FIX_IMPOSSIBLE in the audit report but is
now explicitly accepted, not silent drag.

Then, **if `.spec/` exists and a relevant spec can be identified** (same
heuristic used for option 4 — domain matches the construct under test),
append a non-blocking obligation to that spec's `open_obligations` array:

```
"wontfix: <finding ID> — <brief rationale>"
```

The `wontfix:` prefix lets humans reading `/curate` output distinguish
accepted-risk entries from deferred work. **Unlike option 4, the spec
stays APPROVED** — wontfix is a landed design decision, not a gap.
The obligation is the resurface hook: `/curate` analysis 19 ages it by
last-commit time and flags it once it exceeds `--obligation-age-days`
(default 30). A human reviewing the curate report can then decide
whether the rationale still holds or the wontfix is now fixable.

If `.spec/` is absent or no relevant spec exists, skip the obligation
write — the audit report entry is the only durable record. Note this in
the prove-fix output so reviewers know the resurface hook is not wired.

For option 3: invoke `/spec-author` with a brief describing the pinned
behavior the test encodes. After spec-author returns, re-evaluate the
finding under the new spec — it usually becomes a spec-conflict
(handled above) or wontfix.

For option 4: add an `open_obligations` entry on the most-relevant spec
(the one whose domain matches the construct under test). The
obligation text references the finding ID and the relaxation request so
a future `/curate` analysis 19 (aging obligations) can resurface it.
The spec becomes DRAFT if it was APPROVED.

If cross-cluster unresolved findings exist, ask the user using
AskUserQuestion:

- Question: "<n> cross-cluster findings could not be analyzed in this
  round. Recommend another round with co-clustered constructs."
- Options:
  1. "Another round" — run an additional audit round with co-clustered
     constructs
  2. "Done" — finish the audit as-is

---

## Job 5: Reconciliation (optional)

This job only runs when `.spec/` exists. Check for the directory before
launching.

```bash
# Check if .spec/ exists — mechanical check, not LLM judgment
test -d .spec/
```

If `.spec/` does not exist, skip this job. Mark it as "Reconciliation
(skipped — no specs)".

If `.spec/` exists, launch a single subagent:

> You are the Reconcile Findings subagent.
> Read `.claude/prompts/audit/reconcile-findings.md` for your complete
> instructions.
>
> Feature slug: <slug>
> Run directory: <RUN_DIR>
>
> Read all prove-fix outputs and the audit report from the run directory.
> Check .spec/ and .kb/ for existing entries.
>
> Write spec-updates.md and kb-suggestions.md to `.feature/<slug>/`.
>
> Return a summary line when done.

Expected return format: a structured `RECONCILIATION_SUMMARY_START` block
containing spec update summaries, KB pattern summaries, and spec coverage
(see `reconcile-findings.md` for the exact format).

Parse the summary block from the return. **Do NOT read spec-updates.md or
kb-suggestions.md** — the subagent's return contains everything needed to
present the feedback menus. The files exist for "review" mode (when the
user wants full details) and for `/curate` to pick up later if skipped.

### 5a. Present and process spec updates

Extract the `SPEC_UPDATES:` section from the reconciliation return.
If updates were suggested (count > 0), present inline:

Display the spec updates to the user:

```
── Feedback loop: spec updates ────────────────

Spec updates (<n> suggested):

  1. <requirement text — one line, observable behavior>
  2. <requirement text>
  ...
```

Then use AskUserQuestion to get the user's choice:
- "Apply all" — add all requirements to their specs now
- "Review" — show full details before deciding
- "Skip" — defer (resurfaces via /curate)

Do not present the KB menu or proceed until the user has responded.

If **apply**: for each suggested requirement, read the target spec file,
append the requirement to the appropriate category section, and run
`spec-validate.sh` to verify the spec is still valid. Then run
`spec-resolve.sh "<feature slug>"` to check whether the new requirements
conflict with or invalidate requirements in other specs. If conflicts are
found, display them and ask the user to resolve before continuing.
Report what was added. After all applies complete, rename the file:
`mv spec-updates.md spec-updates.applied.md`

If **review**: read `.feature/<slug>/spec-updates.md` NOW (this is the one
case where reading the file is justified — the user explicitly asked for
full details). Display the full requirement blocks with notes, then re-offer
apply/skip per item. Run the same validation after any applies. If all
items were applied or skipped individually, rename the file.

If **skip**: note in the audit report that suggestions were deferred. They
will resurface when `/curate` detects the deferred feedback file.

### 5b. Present and process KB patterns

Only present this menu **after the user has responded to 5a** (or if no
spec updates exist). Extract the `KB_PATTERNS:` section from the
reconciliation return. If patterns were found (count > 0), present inline:

Display the KB patterns to the user:

```
── Feedback loop: KB patterns ─────────────────

KB patterns (<n> updates + <n> new):

  1. <Update/New> <pattern name> — <one-line description>
  2. <Update/New> <pattern name> — <one-line description>
  ...
```

Then use AskUserQuestion to get the user's choice:
- "Create all" — create/update all KB entries now
- "Select" — choose which to create
- "Skip" — defer (patterns stay in kb-suggestions.md for later)

Do not proceed until the user has responded.

If **create**: for each pattern, invoke
`/research "<subject>" context: "audit adversarial pattern from <target>. Suggested: <topic>/adversarial-findings"` as a sub-agent with the pattern's
description and affected constructs as context. After all creates complete,
rename: `mv kb-suggestions.md kb-suggestions.applied.md`

If **select**: present each pattern individually for create/skip. After all
items are processed, rename the file.

If **skip**: note as deferred. The file remains for `/curate` to pick up.

### 5c. Show spec coverage

After the user has responded to both menus (or if no updates/patterns
were found), show spec coverage:

```
Spec coverage: <exercised>/<total> <spec-ID> requirements exercised
  (<N> unexercised — either well-implemented or not reached by this audit)
──────────────────────────────────────────────
```

Mark **Job 5: Reconciliation** complete.
