---
description: "Query, browse, and manage the project capability index"
argument-hint: "[subcommand] [arguments]"
---

# /capabilities [subcommand] [arguments]

Single entry point for the project capability index. Capabilities describe
what the project can do — they are a routing layer that connects natural
language questions to specs, ADRs, KB research, and feature history.

## Subcommands

| Invocation | What it does |
|------------|-------------|
| `/capabilities "<question>"` | Search capabilities by natural language |
| `/capabilities list` | Browse all capabilities |
| `/capabilities add "<name>"` | Create a new capability entry |
| `/capabilities update "<slug>"` | Update an existing capability entry |
| `/capabilities backfill` | Bootstrap from existing features, specs, and ADRs |

**Default (no subcommand):** if the first argument looks like a question
rather than a subcommand name, treat it as `/capabilities "<question>"`.

---

## Pre-flight guard

Check that `.capabilities/CLAUDE.md` exists. If not:

```
No capability index found. Would you like to set one up?
```

Use AskUserQuestion:
- "Set up now" — create `.capabilities/CLAUDE.md` with the seed template
  (see Setup section below), then continue
- "Not now" — stop

---

## capabilities "<question>" — natural language search

Search the capability index for entries matching a natural language query.
This is the primary discovery mechanism — users ask "do we support X?"
and get a structured answer.

### Step 1 — Read the index

Read `.capabilities/CLAUDE.md`. Extract the capability map table.

### Step 2 — Match

For each capability in the map, check for matches against the query:
1. Title match (strongest signal)
2. Tag match (query keywords against tags)
3. Feature description match (query keywords against feature descriptions
   in the entry files — read entries whose title or tags partially match)

### Step 3 — Present results

**Match found (1-3 results):** Read the matching capability entry files.
Display:

```
Found <n> matching capabilities:

  <title> (<status>)
  <first 2-3 sentences of "What it does">

  Specs: <spec_refs>
  Decisions: <decision_refs>
  KB: <kb_refs>
  Features: <feature count> (<feature descriptions, abbreviated>)

  Full entry: .capabilities/<slug>.md
```

**Multiple matches (4+):** Display a summary table and let the user pick:

Use AskUserQuestion with one option per match (up to 4) plus "Other" for
the full list.

**No match:**

```
No capability matches "<question>".

This might be a gap in the project. Options:
```

Use AskUserQuestion:
- "Search KB" — try `/kb "<question>"` for research that might be related
- "Check decisions" — try `/decisions "<question>"` for deferred work
- "Create capability" — start `/capabilities add` if this should exist
- "Done" — stop

---

## capabilities list — browse all

Display the capability map from `.capabilities/CLAUDE.md`:

```
───────────────────────────────────────────────
Project Capabilities (<n> total)
───────────────────────────────────────────────

  <title> — <tags>
    <first sentence of "What it does">
    Features: <count> | Specs: <spec_refs> | Status: <status>

  <title> — <tags>
    ...

───────────────────────────────────────────────
```

If the user wants details on a specific capability, they can run
`/capabilities "<title>"` or read the entry file directly.

---

## capabilities add "<name>" — create a new entry

Interactive creation of a capability entry.

### Step 1 — Gather information

Use AskUserQuestion for each field that needs user input:

**Description:** "Describe this capability in 1-2 sentences. What can the
user do?"

**Tags:** "What tags describe this capability? (comma-separated)"
Suggest tags based on the name and description.

**Status:** Use AskUserQuestion:
- "Active" — capability is implemented and available
- "Planned" — capability is designed but not yet built
- "Deprecated" — capability is being phased out

### Step 2 — Cross-reference discovery

Search existing artifacts for links:

1. **Specs:** Search `.spec/registry/manifest.json` (if exists) for domain
   keywords matching the capability name and tags
2. **Decisions:** Search `.decisions/CLAUDE.md` for ADR slugs matching
   keywords
3. **KB:** Search `.kb/CLAUDE.md` for topic/category matches
4. **Features:** Search `.feature/` and `.feature/_archive/` for feature
   slugs with matching brief descriptions

Present discovered cross-references and let the user confirm:
"I found these potential links. Select which ones apply."

Use AskUserQuestion with multiSelect for each artifact type.

### Step 3 — Feature descriptions

If features were linked, ask for a one-line description of each feature's
contribution to this capability. These descriptions persist in the
capability entry even when `.feature/` is gone.

For features that are quality improvements (performance fixes, bug fixes)
rather than new capability: mark them with `type: quality`.

### Step 4 — Dependencies

Ask: "Does this capability depend on any other capabilities?"

Read `.capabilities/CLAUDE.md` and present existing capabilities as options.

Ask: "Does this capability enable any planned capabilities?"

### Step 5 — Key behaviors

Ask: "What are the 3-8 key behaviors a user should know about? Link to
spec requirements where possible (e.g., F03.R1)."

### Step 6 — Write the entry

Write `.capabilities/<slug>.md` with the gathered information following the
entry template (see plan for full format).

Update `.capabilities/CLAUDE.md` — add a row to the Capability Map table
and a row to Recently Updated.

Display:
```
Created capability: <title>
  Entry: .capabilities/<slug>.md
  Tags: <tags>
  Links: <n> specs, <n> decisions, <n> KB topics, <n> features
```

---

## capabilities update "<slug>" — update an existing entry

Read the existing entry. Present what's there and ask what to change.

Use AskUserQuestion:
- "Add features" — link new features to this capability
- "Update description" — revise the description or key behaviors
- "Update cross-references" — add/remove spec, ADR, KB, or dependency links
- "Change status" — active/planned/deprecated

After changes, append an `## Updates <YYYY-MM-DD>` section to the entry
(following the KB pattern of never overwriting). Update the Recently Updated
table in CLAUDE.md.

---

## capabilities backfill — bootstrap from existing project artifacts

Scans existing features, specs, and ADRs to propose an initial set of
capabilities. Use this when adopting the capability index on a project
that already has work done.

### Step 1 — Gather existing artifacts

Read these sources (skip any that don't exist):

1. **Feature briefs:** `.feature/_archive/*/brief.md` and `.feature/*/brief.md`
   — extract feature name and description
2. **Spec registry:** `.spec/registry/manifest.json` — extract domain
   taxonomy and feature IDs
3. **Confirmed ADRs:** `.decisions/CLAUDE.md` — extract accepted decision
   slugs and their recommendation summaries
4. **KB topics:** `.kb/CLAUDE.md` — extract topic map for cross-reference

### Step 2 — Propose capability groupings

Analyze the features and specs to identify logical capabilities:

1. Group features that contribute to the same user-visible capability
   (e.g., encrypt-memory-data + extract-core-encryption + fix-encryption-
   performance → "field-level-encryption")
2. Identify pre-existing capabilities implied by specs or ADRs that have
   no feature backing (e.g., schema/document model, WAL, compaction)
3. Distinguish capability features from quality improvements (performance
   fixes, bug fixes → mark as `type: quality` on the parent capability)

### Step 3 — Present proposals

Display the proposed capabilities as a numbered list:

```
Backfill analysis found <n> potential capabilities from <n> features,
<n> specs, and <n> ADRs:

  1. <capability name> — <description>
     Features: <slugs>
     Specs: <refs>
     ADRs: <refs>

  2. ...
```

Use AskUserQuestion:
- "Create all" — create entries for all proposed capabilities
- "Select" — choose which ones to create
- "Review" — show full details before deciding

### Step 4 — Create entries

For each accepted capability, run the same write flow as `/capabilities add`
(Step 6) but with the gathered data pre-filled. The user can adjust
descriptions, tags, and cross-references before writing.

### Step 5 — Summary

```
Backfill complete:
  Created: <n> capabilities
  Features linked: <n>
  Specs linked: <n>
  ADRs linked: <n>
  Pre-existing (no feature): <n>

Run /capabilities list to see the full index.
```

---

## Setup — seed template

When creating `.capabilities/CLAUDE.md` for the first time:

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

---

## Capability entry template

When writing a new `.capabilities/<slug>.md`:

```markdown
---
title: "<name>"
slug: "<slug>"
status: <active | planned | deprecated>
tags: [<tags>]
features:
  - slug: <feature-slug>
    description: "<one-line contribution>"
spec_refs: [<spec IDs>]
decision_refs: [<ADR slugs>]
kb_refs: [<KB topic/category paths>]
depends_on: [<capability slugs>]
enables: [<capability slugs>]
---

# <title>

<2-5 sentences: what the user can do, what problem it solves>

## What it does

<expanded description>

## Features

<rendered from frontmatter features array>

## Key behaviors

- <behavior 1> (<spec ref if available>)
- <behavior 2>
...

## Related

- **Specs:** <list>
- **Decisions:** <list>
- **KB:** <list>
- **Depends on:** <list>
- **Enables:** <list>
```
