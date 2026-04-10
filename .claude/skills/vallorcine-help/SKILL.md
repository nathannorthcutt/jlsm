---
description: "Help entry point — answers questions and routes to the right command"
argument-hint: "[question]"
---

# /vallorcine-help [question]

Entry point for anyone not familiar with the command structure. Two modes:

**With a question:** answers questions about vallorcine commands, workflows, and
capabilities. "How do I resume a feature?" "What does the architect do?" "How do
I check the KB?" Routes to the right command with context.

**Without arguments:** figures out what you need and routes you to the right command.

This command never does pipeline work itself — it reads context, answers questions,
and hands you a pre-filled command to run. It is a router, not an agent.

---

## Step 0 — Question detection

If the user provided an argument, determine whether it is:
- **A question about vallorcine** — contains question words (how, what, where, when,
  why, can, does, is) or is phrased as a question about commands, workflows, or
  capabilities. Jump to **Step Q** (question answering).
- **A task description** — describes something to build or fix. Jump to **Step 4**
  (routing) with this as the description.

If no argument provided: continue to Step 1.

---

## Step Q — Answer questions about vallorcine

Read `.claude/.vallorcine-version` if it exists. Display:
```
───────────────────────────────────────────────
🚀 HELP · vallorcine v<version>
───────────────────────────────────────────────
```
If the version file doesn't exist, omit the version suffix.

Answer the question using the command reference below. Lead with the specific
command to run, then explain what it does and what the user can expect.

### Command reference (for answering questions)

**Starting and resuming work:**
- `/feature "<description>"` — start a new feature (full pipeline: scoping → domains → specs → plan → test → implement → refactor → audit → PR)
- `/feature-quick "<description>"` — small, well-understood tasks (single session, no planning overhead)
- `/feature-resume "<slug>"` — see where a feature is and what to run next (crash recovery, session resume)
- `/feature-resume "<slug>" --status` — detailed session briefing with next-session agenda
- `/feature-resume "<slug>" --list` — list all active features with their current stage

**Specifications:**
- `/spec "<question>"` — query specs, discover gaps, and trace change impact. Searches all specs for matching requirements, surfaces areas with no spec coverage, and analyzes the downstream impact of proposed requirement changes.
- `/spec-author "<feature-id>" "<title>"` — author a hardened spec through two-pass adversarial review (structured draft + falsification)
- `/spec-write "<id>" "<title>"` — register a spec in `.spec/` storage with conflict check
- `/spec-verify "<id>"` — verify a spec against the current implementation

**Knowledge and decisions:**
- `/kb "<question>"` — query the knowledge base in plain language
- `/research "<subject>"` — run a research session, agent determines placement, writes to `.kb/`
- `/architect "<problem>"` — run an architecture decision session with full deliberation and falsification
- `/decisions "<question>"` — query existing decisions in plain language
- `/decisions backfill [<path>]` — surface implicit decisions from past work (prefer `/curate` for broader review)
- `/decisions revisit "<slug>"` — revisit a confirmed decision
- `/decisions triage` — review all deferred/draft items
- `/decisions defer "<problem>"` — park a topic for later
- `/decisions close "<problem>"` — rule out permanently

**Codebase quality:**
- `/audit "<entry-point>"` — run the adversarial audit pipeline against shipped code. Finds bugs, proves them with failing tests, fixes the code. Accepts feature slugs, file paths, spec references, or prior audit reports.
- `/curate` — review codebase quality: find stale decisions, knowledge gaps, implicit dependencies, spec coverage gaps, unspecified shared types, and spec-code drift
- `/curate --init` — first-time scan on an existing codebase (bootstraps quality signals)
- `/curate --deeper` — scan 6 months of history instead of default 3

**Setup and maintenance:**
- `/project-context add "<entry>"` — add team-shared knowledge about the codebase
- `/project-context cleanup` — review expired context entries
- `/project-context` — display all active context entries
- `/setup-vallorcine` — one-time project setup (KB, decisions, specs, feature pipeline, project profile)
- `/feature-cleanup` — interactive walkthrough of stale feature directories
- `/upgrade-vallorcine` — check for and apply kit updates

**Pipeline stages (usually invoked automatically, not manually):**
- `/feature-domains "<slug>"` — domain analysis, commissions research/architect. Routes to spec authoring when `.spec/` exists.
- `/feature-plan "<slug>"` — work plan, stubs, execution strategy. Consumes specs as primary context.
- `/feature-coordinate "<slug>"` — parallel batch coordinator (balanced/speed mode)
- `/feature-test "<slug>"` — operationalizes spec requirements into tests (Lens A) + adversarial implementation risk analysis (Lens B). Falls back to inline spec analysis when no specs exist.
- `/feature-implement "<slug>"` — implement until tests pass. Detects spec conflicts in test failures.
- `/feature-refactor "<slug>"` — quality review checklist, then delegates to `/audit` for adversarial bug finding.
- `/feature-pr "<slug>"` — draft PR title, description, checklist
- `/feature-retro "<slug>"` — post-feature retrospective (scope, assumptions, gaps, tokens)
- `/feature-complete "<slug>"` — archive after PR merges

### Answer format

```
<Direct answer: the specific command to run and why>

  <command with arguments filled in where possible>

<1-2 sentences of context: what it does, what to expect>
```

If the question doesn't match any command, say so and suggest `/vallorcine-help`
with no arguments to get routed interactively.

After answering, stop. Do not continue to the routing flow.

---

## Step 1 — Read project context

Read silently before saying anything:
1. `CLAUDE.md` — project overview, technology stack
2. `.feature/project-config.md` — if it exists (project is initialised)
3. `.feature/CLAUDE.md` — if it exists (check for active features)

---

## Step 2 — Check initialisation

Read `.claude/.vallorcine-version` if it exists. Display opening header:
```
───────────────────────────────────────────────
🚀 START · vallorcine v<version>
───────────────────────────────────────────────
```
If the version file doesn't exist, omit the version suffix.

If `.feature/project-config.md` does NOT exist:

```
Welcome! This project hasn't been set up for the feature pipeline yet.

Run this first — it's a one-time setup that reads your project files
and configures everything automatically:

  /setup-vallorcine

Then come back and run /vallorcine-help again.
```

Stop.

---

## Step 3 — Check for active features

If `.feature/CLAUDE.md` exists and the Active Features table has any rows:

```
You have feature work already in progress:

  <slug>  —  <feature description>  —  Stage: <stage>  —  Last checkpoint: <checkpoint>
  <slug>  —  <feature description>  —  Stage: <stage>  —  Last checkpoint: <checkpoint>

Would you like to continue one of these, or start something new?
  1. Continue <slug>
  2. Continue <slug>
  3. Start something new
```

If the user picks a number: jump to Step 5 (resume path) with that slug.
If the user says "new" or picks the last option: continue to Step 4.

---

## Step 4 — Ask the one question

```
What would you like to build or fix?

(Describe it however feels natural — a sentence or two is plenty.)
```

Wait for the user's description. Then evaluate it.

---

## Step 5 — Route to the right command

### Resume path (continuing an existing feature)

```
To pick up where you left off:

  /feature-resume "<slug>"

This will show you exactly what was completed, what's in progress,
and the specific command to run next.
```

### Quick path

Use this when the description sounds like: a single method or function, a small
fix, an addition that follows an obvious existing pattern, something that could
be fully described in one sentence with no design decisions.

Signals: "add X to Y", "fix X", "make X return Y", "expose X as Y"

```
That sounds like a good fit for a quick task — it's small and well-understood,
so you can skip the full planning pipeline and go straight to tests.

Here's your command:

  /feature-quick "<their description, cleaned up into one clear sentence>"

What happens:
  1. I'll check the codebase briefly and confirm my understanding
  2. Write failing tests for the described behaviour
  3. Implement until the tests pass
  4. Refactor for quality and check for anything missing

The whole thing runs in one session with no handoffs.
```

### Full pipeline path

Use this when the description sounds like: new functionality, a feature that
touches multiple parts of the system, something with design decisions to make,
something the user isn't sure how to structure, or anything with "support for X"
or "system for X" phrasing.

Signals: "add support for X", "build a X system", "implement X feature",
"I want X to work", anything involving multiple components or uncertain scope

```
That sounds like it needs the full pipeline — there are design decisions to make
and enough scope that it's worth planning properly before writing any code.

Here's how it works, in order:

  1. /feature "<description>"
     Scoping interview — I'll ask clarifying questions and write a feature brief.
     Takes: 5-10 minutes of back-and-forth.
     Produces: .feature/<slug>/brief.md

  2. /feature-domains "<slug>"
     I'll check the knowledge base and decision records for relevant research,
     and commission any missing architectural decisions.
     Produces: .feature/<slug>/domains.md

  3. Spec authoring (automatic when .spec/ exists)
     Two-pass adversarial spec authoring: structured draft then falsification.
     Produces hardened behavioral requirements in .spec/.

  4. /feature-plan "<slug>"
     I'll design the implementation structure and write empty stubs with contracts.
     Uses specs as primary context alongside brief and domains.
     Produces: .feature/<slug>/work-plan.md + stub files

  5. /feature-test "<slug>"
     Operationalizes spec requirements into tests (Lens A) and finds
     implementation risk patterns (Lens B). Tests verified failing before
     any code is written.
     Produces: failing tests (spec-driven + defensive + structural)

  6. /feature-implement "<slug>"
     Implementation until all tests pass. Detects spec conflicts in test
     failures and escalates to spec-author instead of misdiagnosing.
     Produces: passing tests + working code

  7. /feature-refactor "<slug>"
     Quality review: standards, security, performance, missing test coverage.
     Then delegates to /audit for adversarial bug finding — writes targeted
     tests, proves bugs, fixes confirmed issues. Findings feed back into
     specs and KB.
     Produces: clean, audited code

  8. /feature-pr "<slug>"
     Drafts your PR title, description, and review checklist.
     Produces: .feature/<slug>/pr-draft.md

  9. /feature-complete "<slug>"  (run after the PR merges)
     Archives the working files. Source code and tests stay in git.

If you stop at any point: /feature-resume "<slug>" will tell you exactly where
you are and what to run next.

Ready to start? Run:

  /feature "<their description, cleaned up into one clear sentence>"
```

### Ambiguous path

If the description could go either way, lean toward the full pipeline and say so:

```
That could go either way. Here's how to decide:

  Use /feature-quick if:  you already know exactly what to build and it follows
                  an existing pattern in the codebase

  Use /feature if: there are design decisions to make, the scope isn't
                   fully clear, or it touches multiple parts of the system

My lean: <quick / full pipeline> because <one sentence reason>.

Your commands:
  Quick:    /feature-quick "<description>"
  Full:     /feature "<description>"
```

---

## Step 6 — Offer a reference

After any routing decision, add:

```
Other commands you might want:
  /feature-resume "<slug>" --status  — detailed progress summary for any active feature
  /spec "<question>"                 — query specs, discover gaps, trace change impact
  /audit "<entry-point>"             — adversarial bug finding on shipped code
  /kb "<question>"                   — query the knowledge base
  /decisions "<question>"            — query architecture decisions
  /curate                            — review codebase quality, spec gaps, undocumented decisions
  /setup-vallorcine                   — update the project profile
  /vallorcine-help "<question>"      — ask me anything about these commands
```

Only show these if they're relevant — omit if the user is clearly a first-timer
who just needs to get started.
