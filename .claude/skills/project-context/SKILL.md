---
description: "Manage the project rolling context file for team-shared codebase knowledge"
argument-hint: "[subcommand] [arguments]"
---

# /project-context [subcommand] [arguments]

Manages the project's rolling context file — team-shared knowledge about the
codebase that doesn't fit in ADRs or KB entries. Things like "the legacy auth
module is fragile", "CI takes 20 min on the full suite", "prod DB has a 5K
connection limit we've hit before."

The file lives at `PROJECT-CONTEXT.md` in the project root (committed, shared
with the team).

## Subcommands

| Invocation | What it does |
|------------|-------------|
| `/project-context add "<entry>"` | Add a new context entry |
| `/project-context cleanup` | Review expired/stale entries |
| `/project-context` (no args) | Display all active entries |

---

## Pre-flight

If `PROJECT-CONTEXT.md` does not exist and the subcommand is not `add`:
```
No PROJECT-CONTEXT.md found. Add your first entry with:
  /project-context add "<what you've learned about this codebase>"
```
Stop.

---

## File format — PROJECT-CONTEXT.md

```markdown
# Project Context

Team-shared knowledge about this codebase. Read by vallorcine agents during
scoping and domain analysis. Entries expire after 90 days by default.

Managed by: `/project-context` — do not edit manually.

---

## Active

### <YYYY-MM-DD> — <one-line summary>
**Expires:** <YYYY-MM-DD>
**Scope:** <"global" | module/area name>
<1-3 sentences of detail.>

---

### <YYYY-MM-DD> — CI takes 20 min on full test suite
**Expires:** 2026-06-16
**Scope:** global
Run focused tests during implementation. Only run full suite in refactor step 2f.

---

## Expired
<!-- Entries moved here by /project-context cleanup. Not read by agents. -->
```

### Size management

- **Active section cap:** 50 entries or ~200 lines (whichever is hit first)
- When exceeded, `/project-context add` warns and suggests running cleanup:
  ```
  ⚠ Active context has <n> entries (~<n> lines). Consider running:
    /project-context cleanup
  ```
- Expired entries in the `## Expired` section are not counted toward the cap
  and are not read by pipeline agents

### Scope field

- `global` — always read by agents when they load project context
- `<module/area>` — only read when the feature being worked on touches that
  area (matched against constructs in work-plan.md or the feature description)

---

## project-context add "<entry>" — add a new entry

Display:
```
───────────────────────────────────────────────
📝 PROJECT CONTEXT · add
───────────────────────────────────────────────
```

### Step 1 — Parse and confirm

Parse the entry text. Ask:
```
── New context entry ──────────────────────────
  "<entry>"

  Scope: global  (or type a module/area name to scope it)
  Expires: <date 90 days from now>  (or type a date, or: never)

Use AskUserQuestion with options:
  - "Save"
  - "Adjust"

If "Adjust": ask what to change, re-display, confirm again.

### Step 2 — Write

If `PROJECT-CONTEXT.md` doesn't exist, create it with the file header.

Append the new entry to the `## Active` section:
```markdown
### <YYYY-MM-DD> — <one-line summary derived from entry>
**Expires:** <YYYY-MM-DD>
**Scope:** <scope>
<entry text>

---
```

Display:
```
  ✓ Added to PROJECT-CONTEXT.md
    Expires: <date>
    Scope: <scope>
```

---

## project-context cleanup — review expired and stale entries

Display:
```
───────────────────────────────────────────────
📝 PROJECT CONTEXT · cleanup
───────────────────────────────────────────────
```

### Step 1 — Scan entries

Read `PROJECT-CONTEXT.md`. Parse all entries in the `## Active` section.
For each entry, check:
- Is it past its expiry date? → `expired`
- Is it within 7 days of expiry? → `expiring-soon`
- Otherwise → `active`

### Step 2 — Display summary

```
── Context entries ────────────────────────────
  Active: <n>
  Expiring soon: <n>
  Expired: <n>
```

If no expired or expiring-soon entries:
```
  All entries are current. Nothing to clean up.
```
Stop.

### Step 3 — Process expired entries

For each expired entry:
```
── Expired: <summary> ─────────────────────────
  Added: <date>  |  Expired: <date>  |  Scope: <scope>
  <entry text>

  Type: keep (extend 90d) · archive · delete
```

- **keep** → update `Expires:` to 90 days from today
- **archive** → move entry from `## Active` to `## Expired` section
- **delete** → remove entry entirely

### Step 4 — Process expiring-soon entries

For each expiring-soon entry:
```
── Expiring soon: <summary> ───────────────────
  Added: <date>  |  Expires: <date> (<n> days)  |  Scope: <scope>
  <entry text>

  Type: keep (extend 90d) · archive · skip
```

Same actions as expired, plus `skip` to leave it alone.

### Step 5 — Summary

```
───────────────────────────────────────────────
📝 PROJECT CONTEXT · cleanup complete
  Extended: <n>   Archived: <n>   Deleted: <n>   Skipped: <n>
  Active entries remaining: <n>
───────────────────────────────────────────────
```

---

## project-context (no args) — display all active entries

Display:
```
───────────────────────────────────────────────
📝 PROJECT CONTEXT
───────────────────────────────────────────────

  <n> active entries (<n> global, <n> scoped)

  <date>  <scope>   <summary>                        <expires>
  ──────  ────────  ──────────────────────────────   ─────────
  03-16   global    CI takes 20 min on full suite    06-16
  03-10   jlsm-table  5K connection limit on prod   06-08
  03-01   global    Legacy auth module is fragile    05-30

  <If any expiring within 7 days:>
  ⚠ <n> entries expiring soon. Run /project-context cleanup to review.

───────────────────────────────────────────────
```

---

## Agent integration

Pipeline agents read `PROJECT-CONTEXT.md` at these points:

**`/feature` (scoping)** — Step 1 (read project config). Read the Active section
of `PROJECT-CONTEXT.md` if it exists. Use global entries and any scoped entries
matching the feature description to inform the scoping interview. Do not ask
questions that active context entries already answer.

**`/feature-domains`** — Step 2 (survey). Check active context entries for
known constraints that affect domain classification. An entry like "prod DB
has a 5K connection limit" is relevant when the feature involves database work.

**`/feature-plan`** — Step 1 (load context). Read active context entries
scoped to the modules being planned. Constraints from context entries should
be reflected in the work plan contracts.

**Pre-flight staleness check:** At pipeline start, if `PROJECT-CONTEXT.md`
exists and has expired entries, display:
```
  ℹ <n> expired project context entries. Run /project-context cleanup to review.
```
Informational only — never blocks.
