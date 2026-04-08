---
description: "One-time project setup — KB, decisions, feature pipeline, and project profile"
---

# /setup-vallorcine

Display opening header:
```
───────────────────────────────────────────────
🔧 SETUP
───────────────────────────────────────────────
```

One-time project setup. Initializes the knowledge base, decisions, and feature
pipeline directories, then builds a project configuration profile by reading
your build files.

Safe to re-run — skips files that exist, offers to update project-config.md
if it already has values.

> **Important:** project-config.md is committed and shared. Run /setup-vallorcine
> once on the main/shared branch before creating feature branches. Running it on
> separate branches with different answers causes merge conflicts.

---

## Step 1 — Check what exists

Inspect the project for each of these and report FOUND or MISSING:
- `.kb/CLAUDE.md`
- `.kb/_refs/complexity-notation.md`
- `.kb/_refs/benchmarking-methodology.md`
- `.decisions/CLAUDE.md`
- `.feature/project-config.md`
- `.feature/CLAUDE.md`
- Root `CLAUDE.md` (check for .kb/, .decisions/, and .feature/ pointer blocks)

If `.feature/project-config.md` exists: display current values and ask
"Update, or keep as-is?" If keep as-is: skip Step 4.

---

## Step 2 — Create .kb/ structure (missing files only)

**.kb/CLAUDE.md**

```markdown
# Knowledge Base — Root Index

> Pull model. Navigate: topic → category → subject file.
> Do not scan this directory recursively.
> Structure: .kb/<topic>/<category>/<subject>.md

## Topic Map

| Topic | Path | Categories | Files | Last Updated |
|-------|------|------------|-------|--------------|

## Recently Added (last 10)
| Date | Topic | Category | Subject |
|------|-------|----------|---------|

## Shared References
`_refs/complexity-notation.md` — notation key used in algorithm files
`_refs/benchmarking-methodology.md` — how benchmark figures are cited

Older entries: [_archive.md](_archive.md)
```

**.kb/_refs/complexity-notation.md**

```markdown
---
type: reference-fragment
title: Complexity Notation Guide
---
# Complexity Notation Used In This Knowledge Base

- **n** — number of items in the index / dataset size
- **d** — dimensionality of vectors
- **k** — number of nearest neighbors requested
- **M** — graph connectivity parameter (HNSW-specific)
- **nprobe** — number of cluster probes at query time (IVF-specific)
- All complexities are average-case unless marked ⚠️ worst-case
- Space complexity counts index storage only, not raw data storage
```

**.kb/_refs/benchmarking-methodology.md**

```markdown
---
type: reference-fragment
title: Benchmarking Methodology
---
# How Benchmarks Are Cited

Unless otherwise noted, performance figures reference the ANN-Benchmarks
suite (https://ann-benchmarks.com) under these conditions:
- Datasets: GloVe-100 (1.2M 100-dim vectors) or SIFT-1M (1M 128-dim vectors)
- Hardware: single CPU core unless GPU explicitly noted
- Metric: queries-per-second (QPS) at recall@10 = 0.90
- All figures are approximate — hardware variance is ±15%

When citing non-ANN-Benchmarks figures, state dataset, hardware, and metric explicitly.
```

---

## Step 3 — Create .decisions/ structure (missing files only)

**.decisions/CLAUDE.md**

```markdown
# Architecture Decisions — Master Index

> Pull model. Load on demand only.
> Structure: .decisions/<problem-slug>/adr.md
> Full history: [history.md](history.md)

## Active Decisions
<!-- Proposed or in-progress only. Accepted/superseded rows move to history.md. -->

| Problem | Slug | Date | Status | Recommendation |
|---------|------|------|--------|----------------|

## Recently Accepted (last 5)
<!-- Once this section exceeds 5 rows, oldest row moves to history.md -->

| Problem | Slug | Accepted | Recommendation |
|---------|------|----------|----------------|

## Archived
Decisions older than the 5 most recent accepted: [history.md](history.md)
```

---

## Step 3b — Create .capabilities/ structure (missing files only)

**.capabilities/CLAUDE.md**

```markdown
# Project Capabilities

> Managed by vallorcine agents. Use /capabilities to query.
> Each entry describes what the project can do — linking to specs,
> decisions, KB research, and feature history.

## Capability Map

| Capability | Status | Tags | Features | Specs |
|-----------|--------|------|----------|-------|

## Recently Updated (last 5)

| Date | Capability | Change |
|------|-----------|--------|
```

If the project already has features, specs, or decisions, suggest running
`/capabilities backfill` to bootstrap the index from existing artifacts.

---

## Step 4 — Infer and write project profile

Before asking the user anything, read the following files if they exist:

**Primary sources (read all that exist):**
- `CLAUDE.md` — language, framework, build tool, test/lint commands, module structure
- `build.gradle` / `build.gradle.kts` — dependencies, test framework, lint plugins
- `settings.gradle` / `settings.gradle.kts` — multi-module structure
- `pom.xml` — Maven projects: dependencies, plugins, module layout
- `package.json` — Node projects: scripts, dependencies, test framework
- `pyproject.toml` / `setup.cfg` / `setup.py` — Python projects: dependencies, tools
- `go.mod` — Go projects: module name, dependencies
- `Cargo.toml` — Rust projects

**Secondary sources (read if primary sources leave gaps):**
- `.github/workflows/*.yml` — CI config often has the definitive test/lint/type-check commands
- `CONTRIBUTING.md` or `docs/coding-standards.md` — style guide and conventions
- `checkstyle.xml` / `.eslintrc*` / `pyproject.toml [tool.ruff]` — linter config
- `src/test/` or `tests/` or `__tests__/` — scan directory structure to confirm test layout

**What to infer from each:**

| Source | Infer |
|--------|-------|
| CLAUDE.md | Language, framework, build commands, module names |
| build.gradle | Test framework (JUnit 4/5, TestNG), lint (Checkstyle, PMD, SpotBugs), source/test dirs |
| settings.gradle | Multi-module layout, subproject names |
| package.json scripts | `test`, `lint`, `typecheck`, `test:e2e` or `test:integration` commands |
| pyproject.toml | Tool versions (pytest, ruff, mypy), test paths, line length rules |
| .github/workflows | Exact commands used in CI — most reliable source for run commands |
| CONTRIBUTING.md | Style guide name/URL, naming conventions, PR process |

**Standard layout inference (use when not explicitly stated):**
- Gradle/Maven project → source: `src/main/java`, tests: `src/test/java`
- Node project → source: `src/`, tests: `__tests__/` or `src/**/*.test.*`
- Python project → source: `src/<package>/` or `<package>/`, tests: `tests/`
- Go project → tests co-located with source (`*_test.go`)

### Present inferred profile and confirm gaps

Display the draft profile with source attribution, then ask only about fields
that could not be inferred. Do not ask about fields you already know.

```
I found the following from your project files:

Language              : <value>  (from <source>)
Framework             : <value>  (from <source>, or "none — pure library")
Test framework        : <value>  (from <source>)
Test directory        : <value>  (from <source> or standard layout)
Source directory      : <value>  (from <source> or standard layout)
Style guide           : <value or "not found">  (from <source>)
Lint / format         : <value>  (from <source>)
Integration tests     : <value or "not found">
Run tests             : `<command>`  (from <source>)
Run single test       : `<command>`  (from <source>)
Lint command          : `<command or "not found">`
Type check            : `<command or "n/a — <language> is compiled">`
Key conventions       : <summary from CONTRIBUTING.md, or "not found">

<If any fields are "not found":>
Still needed:
  Style guide      : <please specify, e.g. "Google Java Style" or URL, or "none">
  Integration tests: <command to run integration/e2e tests, or "none">
  Key conventions  : <anything agents must know about naming, errors, logging>

Confirm to accept all inferred values, or correct any field.
```

Also ask about branch naming convention. Check if any convention is already
inferable (e.g. from CONTRIBUTING.md, .github/PULL_REQUEST_TEMPLATE.md, or
existing branch names in `git branch`). Display:

```
── Feature branch naming ───────────────────────────────
When you start a new feature, would you like to work on a separate branch?

Convention (leave blank for none):
  e.g.  feature/<slug>   feat/<slug>   wip/<slug>   or your own pattern
```

Accept any pattern string using `<slug>` as the placeholder (e.g. `feature/<slug>`).
If the user leaves it blank or says "none": record `branch_naming: none`.

Display:
```
  Type **yes**  to save  ·  or: describe corrections
```
If "yes": save. If the user types corrections: apply them and confirm again.
Do not re-ask about fields the user confirmed or did not mention.

### Write project-config.md

Write `.feature/project-config.md`:

```markdown
---
created: "<YYYY-MM-DD>"
last_updated: "<YYYY-MM-DD>"
---

# Project Configuration

> Read by all TDD agents at the start of every session.
> Update with /setup-vallorcine when the project profile changes.

## Language & Runtime
**Language:** <language and version>
**Framework:** <framework or "none">

## Testing
**Test framework:** <framework>
**Test directory:** <path>
**Test file naming:** <e.g. test_*.py, *.test.ts, *_test.go, *Test.java>
**Test conventions:**
<project-specific conventions — mocking approach, fixture patterns, etc.>

## Source Layout
**Source directory:** <path>
**Module/package structure:** <brief description — e.g. "3 Gradle submodules: core, indexing, vector">

## Style & Quality
**Style guide:** <guide name or URL, or "none">
**Linter / formatter:** <tools and config file locations>
**Key conventions:**
<Naming rules, error handling patterns, logging approach>

## Security Requirements
<Project-specific security requirements for the Refactor Agent, or "none specified">

## Run commands
**Run tests:** `<full test suite command>`
**Run single test:** `<single test command>`
**Run integration tests:** `<integration/e2e test command, or "none">`
**Lint:** `<lint command, or "none">`
**Type check:** `<type check command, or "n/a">`

## Branch naming
**Convention:** `<pattern using <slug> as placeholder, e.g. feature/<slug>  —  or "none">`

## Knowledge Base
**KB staleness threshold (days):** `90`

## Decisions
**Backfill file threshold:** `50`
```

---

## Step 5 — Create .feature/ structure (if missing)

Create `.feature/CLAUDE.md` if it does not exist:

```markdown
# Feature Work Index

> Pull model — do not scan proactively.
> Each feature lives at .feature/<slug>/
> Project configuration: .feature/project-config.md
> Resume any feature: /feature-resume "<slug>"

## Active Features

| Feature | Slug | Started | Stage | Last Checkpoint |
|---------|------|---------|-------|-----------------|

## Completed / Archived

| Feature | Slug | Completed | Archive |
|---------|------|-----------|---------|
```

Create `.feature/_archive/` directory with a `.gitkeep` placeholder if it
does not exist (the directory itself is gitignored — see Step 6).

---

## Step 6 — Manage .gitignore

Read the project root `.gitignore`. Add the following block if not already present.
If `.gitignore` does not exist, create it.

Append:
```
# Feature working state — scratch directories, not source-controlled
# project-config.md and CLAUDE.md are committed (see below)
.feature/*/
.feature/_archive/

# Explicitly track the project config and index
!.feature/project-config.md
!.feature/CLAUDE.md
```

Tell the user:
```
.gitignore updated — feature working directories will not be committed.
.feature/project-config.md and .feature/CLAUDE.md remain tracked.
```

---

## Step 7 — Check root CLAUDE.md

Read the project root CLAUDE.md. If it does not reference .kb/, .decisions/,
and .feature/, display this block and tell the user to add it manually (do not
edit automatically):

```
## Feature Development
`.feature/<slug>/` — on-demand only. Profile: `.feature/project-config.md`
Quick: `/feature-quick "<description>"` — Full: `/feature "<description>"`
Resume: `/feature-resume "<slug>"` — Status: `/feature-resume "<slug>" --status`
Entry point: `/vallorcine-help`

## Knowledge Base & Decisions
`.kb/<topic>/<category>/<subject>.md` and `.decisions/<slug>/adr.md` — on-demand only.
Commands: `/research` `/architect` `/kb` `/decisions`

## Codebase Quality
`/curate` — review quality signals, find stale decisions, knowledge gaps, and implicit dependencies.
`/curate --init` — first-time scan on existing codebase.
```

---

## Step 8 — Report and hand off

Print a summary of what was found, created, and skipped. Then show:

```
───────────────────────────────────────────────
🔧 SETUP complete
───────────────────────────────────────────────
  KB:           .kb/CLAUDE.md
  Decisions:    .decisions/CLAUDE.md
  Capabilities: .capabilities/CLAUDE.md
  Features:  .feature/project-config.md
  .gitignore updated

Available commands:
  /feature "<description>"            start a new feature (full pipeline)
  /feature-quick "<description>"      small task (single session)
  /research <topic> <cat> "<subj>"    add research to the KB
  /architect "<problem>"              architecture decision session
  /curate                             codebase quality review
  /vallorcine-help                    entry point — routes to the right command
───────────────────────────────────────────────
```

Then immediately invite the user to start their first feature:

```
What would you like to build? Tell me as much or as little as you know —
a rough idea is enough to get started, and I'll ask about anything that
matters before we commit to a plan.
```

Wait for the user's response.

**If the user describes a feature** (any length, any detail level): treat this
as the feature description input to `/feature`. Generate the slug from their
description, invoke the Scoping Agent, and begin the scoping interview as if
the user had run `/feature "<their description>"` directly. Do not display a
separate header for the transition — the scoping interview opening header is
sufficient.

**If the user says they're not ready / want to continue later**: respond with:
```
No problem. When you're ready:
  /feature "<describe what you want to build>"
  /feature-quick "<description>"  (for small, well-understood changes)
  /feature-resume "<slug>"  (to pick up an existing feature)
```

**If the user asks a question instead of describing a feature**: answer it,
then re-offer the invitation once:
```
Ready to start something, or want to explore first?
```
