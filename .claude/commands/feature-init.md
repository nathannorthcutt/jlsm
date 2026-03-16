# /feature-init

Sets up the .feature/ directory and project configuration profile.
Run once per project on the main/shared branch before creating feature branches.
Safe to re-run — updates config rather than overwriting.
Also manages the .gitignore entries for .feature/ scratch directories.

> **Important:** project-config.md is committed and shared. Running /feature-init
> on separate branches with different answers causes merge conflicts. Run it once
> on main, commit, then branch from there.

---

Display opening header:
```
───────────────────────────────────────────────
🔧 FEATURE INIT
───────────────────────────────────────────────
```

## Step 1 — Check what exists

- Check for `.feature/project-config.md`
  - If it exists: display current values and ask "Update, or keep as-is?"
  - If keep as-is: skip to Step 4

---

## Step 2 — Infer project profile from existing files

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

---

## Step 3 — Present inferred profile and confirm gaps

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

---

## Step 4 — Write project-config.md

Write `.feature/project-config.md`:

```markdown
---
created: "<YYYY-MM-DD>"
last_updated: "<YYYY-MM-DD>"
---

# Project Configuration

> Read by all TDD agents at the start of every session.
> Update with /feature-init when the project profile changes.

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

## Step 7 — Hand off into feature planning

Display:
```
───────────────────────────────────────────────
🔧 FEATURE INIT complete
───────────────────────────────────────────────
Project profile saved to .feature/project-config.md
.gitignore updated for .feature/ scratch directories.
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
