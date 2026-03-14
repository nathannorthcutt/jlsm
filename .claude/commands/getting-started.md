# /getting-started

Entry point for anyone not familiar with the command structure.
No arguments required. Figures out what you need and routes you to the right command.

This command never does pipeline work itself — it reads context, asks one question,
and hands you a pre-filled command to run. It is a router, not an agent.

---

## Step 1 — Read project context

Read silently before saying anything:
1. `CLAUDE.md` — project overview, technology stack
2. `.feature/project-config.md` — if it exists (project is initialised)
3. `.feature/CLAUDE.md` — if it exists (check for active features)

---

## Step 2 — Check initialisation

If `.feature/project-config.md` does NOT exist:
```
Welcome! This project hasn't been set up for the feature pipeline yet.

Run this first — it's a one-time setup that reads your project files
and configures everything automatically:

  /feature-init

Then come back and run /start again.
```

Stop.

---

## Step 3 — Check for active features

If `.feature/CLAUDE.md` exists and the Active Features table has any rows:
```
You have feature work already in progress:

  <slug>  —  <feature description>  —  Stage: <stage>  —  Last checkpoint: <checkpoint>

Would you like to continue one of these, or start something new?
  1. Continue <slug>
  2. Start something new
```

If the user picks a number: jump to Step 5 (resume path) with that slug.
If the user says "new": continue to Step 4.

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

Use when the description sounds like: a single method or function, a small fix,
an addition that follows an obvious existing pattern, something fully describable
in one sentence with no design decisions.

Signals: "add X to Y", "fix X", "make X return Y", "expose X as Y"
```
That sounds like a good fit for a quick task — small and well-understood,
so you can skip the planning pipeline and go straight to tests.

Here's your command:

  /quick "<their description, cleaned up into one clear sentence>"

What happens:
  1. I'll check the codebase briefly and confirm my understanding
  2. Write failing tests for the described behaviour
  3. Implement until the tests pass
  4. Refactor for quality and check for anything missing

The whole thing runs in one session with no handoffs.
```

### Full pipeline path

Use when the description sounds like: new functionality, something touching
multiple parts of the system, design decisions to make, uncertain scope, or
anything with "support for X" or "system for X" phrasing.

Signals: "add support for X", "build a X system", "implement X feature",
"I want X to work", anything involving multiple components
```
That sounds like it needs the full pipeline — there are design decisions to make
and enough scope that it's worth planning properly before writing any code.

Here's how it works, in order:

  1. /feature "<description>"
     Scoping interview — clarifying questions, then a written feature brief.
     Produces: .feature/<slug>/brief.md

  2. /feature-domains "<slug>"
     Checks the knowledge base and decision records for relevant research.
     Produces: .feature/<slug>/domains.md

  3. /feature-plan "<slug>"
     Designs the implementation structure and writes empty stubs with contracts.
     Produces: .feature/<slug>/work-plan.md + stub files

  4. /feature-test "<slug>"
     Tests written against contracts — verified failing before any code is written.
     Produces: failing tests in your test directory

  5. /feature-implement "<slug>"
     Implementation until all tests pass.
     Produces: passing tests + working code

  6. /feature-refactor "<slug>"
     Quality review: standards, security, performance, missing coverage,
     and integration tests if configured.
     Produces: clean, reviewed code

  7. /feature-pr "<slug>"
     Drafts your PR title, description, and review checklist.
     Produces: .feature/<slug>/pr-draft.md

  8. /feature-complete "<slug>"  (after the PR merges)
     Archives working files. Source code and tests stay in git.

If you stop at any point:
  /feature-resume "<slug>"  — shows where you are and what to run next

Ready to start? Run:

  /feature "<their description, cleaned up into one clear sentence>"
```

### Ambiguous path

If it could go either way, lean toward the full pipeline and say so:
```
That could go either way. Here's how to decide:

  /quick   if: you know exactly what to build and it follows an existing pattern
  /feature if: there are design decisions to make or scope isn't fully clear

My lean: <quick / full pipeline> because <one sentence reason>.

  Quick:  /quick "<description>"
  Full:   /feature "<description>"
```

---

## Step 6 — Offer a reference

After routing, add only if relevant:
```
Other useful commands:
  /feature-status "<slug>"  — progress summary for any active feature
  /feature-init             — update the project profile
```