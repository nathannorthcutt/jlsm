---
description: "Query, browse, and manage the project capability index"
argument-hint: "[subcommand] [arguments]"
---

# /capabilities [subcommand] [arguments]

Single entry point for the project capability index. Capabilities describe
what the project can do — organized by domain, with types that distinguish
core capabilities from emergent compositions and refinements.

**Hierarchy:** domain → capability. Domains group capabilities by area of
system function (e.g., `data-management`, `security`, `query`). Each domain
has its own index. Capabilities are the leaf entries.

**Three capability types:**
- **core** — primary user-visible capability
- **emergent** — arises from composition of other capabilities (no single
  feature created it)
- **refinement** — quality/performance improvement to an existing domain area

**Feature mapping:** many-to-many with roles (`core`, `extends`, `quality`,
`enables`). A feature can contribute to multiple capabilities.

## Subcommands

| Invocation | What it does |
|------------|-------------|
| `/capabilities "<question>"` | Search capabilities by natural language |
| `/capabilities list` | Browse all capabilities by domain |
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

Read `.capabilities/CLAUDE.md`. Extract the domain map.

### Step 2 — Match against domain indexes

For each domain, read its `CLAUDE.md` index. Match the query against:
1. Capability title (strongest signal)
2. Tags (query keywords against tags)
3. Domain description (broader match)

Only read full capability entry files for entries whose title or tags
partially match. This keeps the search cheap.

### Step 3 — Present results

**Match found (1-3 results):** Read the matching capability entry files.
Display:

```
Found <n> matching capabilities:

  <title> (<type>, <status>) — <domain>
  <first 2-3 sentences of "What it does">

  Specs: <spec_refs>
  Decisions: <decision_refs>
  KB: <kb_refs>
  Features: <feature count> (<feature descriptions, abbreviated>)

  Full entry: .capabilities/<domain>/<slug>.md
```

For emergent capabilities, also show:
```
  Composes: <list of composed capabilities>
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

## capabilities list — browse all by domain

Read `.capabilities/CLAUDE.md` for the domain map. For each domain, read
its `CLAUDE.md` index. Display:

```
───────────────────────────────────────────────
Project Capabilities (<n> total, <d> domains)
───────────────────────────────────────────────

<domain name> — <domain description>

  <title> (<type>) — <tags>
    <first sentence of "What it does">
    Features: <count> | Specs: <spec_refs> | Status: <status>

  <title> (<type>) — <tags>
    ...

<domain name> — <domain description>
  ...

───────────────────────────────────────────────
```

Refinement-type capabilities are displayed after core capabilities within
their domain, visually indented or annotated. Emergent capabilities show
their `composes` field.

---

## capabilities add "<name>" — create a new entry

Interactive creation of a capability entry.

### Step 1 — Domain placement

Read `.capabilities/CLAUDE.md` for existing domains. Use AskUserQuestion:
- One option per existing domain
- "New domain" — create a new domain (prompt for name and description)

### Step 2 — Capability type

Use AskUserQuestion:
- "Core" — primary user-visible capability
- "Emergent" — arises from composition of other capabilities
- "Refinement" — quality/performance improvement in this domain

### Step 3 — Gather information

**Description:** "Describe this capability in 1-2 sentences. What can the
user do?"

**Tags:** "What tags describe this capability? (comma-separated)"
Suggest tags based on the name, description, and domain.

**Status:** Use AskUserQuestion:
- "Active" — capability is implemented and available
- "Planned" — capability is designed but not yet built
- "Deprecated" — capability is being phased out

### Step 4 — Type-specific fields

**For emergent capabilities:** Read all domain indexes. Ask: "Which
capabilities does this compose?" Use AskUserQuestion with multiSelect,
listing capabilities from all domains. Require at least 2.

**For refinement capabilities:** Ask: "Which domain area does this refine?"
(The answer is implicit from the domain placement, but confirm.)

### Step 5 — Cross-reference discovery

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

### Step 6 — Feature descriptions and roles

If features were linked, for each feature ask:
1. One-line description of the feature's contribution
2. Role: Use AskUserQuestion:
   - "Core" — primary implementation of this capability
   - "Extends" — adds a new dimension
   - "Quality" — performance/cleanup improvement
   - "Enables" — prerequisite, but the capability is its own concern

### Step 7 — Dependencies

Ask: "Does this capability depend on any other capabilities?"

Read domain indexes and present existing capabilities as options (using
`<domain>/<slug>` format for cross-domain references).

Ask: "Does this capability enable any planned capabilities?"

### Step 8 — Key behaviors

Ask: "What are the 3-8 key behaviors a user should know about? Link to
spec requirements where possible (e.g., F03.R1)."

### Step 9 — Write the entry

Write `.capabilities/<domain>/<slug>.md` with the gathered information
following the entry template.

Update `.capabilities/<domain>/CLAUDE.md` — add a row to the Capabilities
table.

Update `.capabilities/CLAUDE.md` — update domain capability count and add
a row to Recently Updated.

Display:
```
Created capability: <title>
  Domain: <domain>
  Type: <type>
  Entry: .capabilities/<domain>/<slug>.md
  Tags: <tags>
  Links: <n> specs, <n> decisions, <n> KB topics, <n> features
```

---

## capabilities update "<slug>" — update an existing entry

Find the entry by searching domain indexes for the slug. Read the existing
entry. Present what's there and ask what to change.

Use AskUserQuestion:
- "Add features" — link new features to this capability (with role)
- "Update description" — revise the description or key behaviors
- "Update cross-references" — add/remove spec, ADR, KB, or dependency links
- "Change status" — active/planned/deprecated
- "Move domain" — move to a different domain

After changes, append an `## Updates <YYYY-MM-DD>` section to the entry
(following the KB pattern of never overwriting). Update the Recently Updated
table in the root CLAUDE.md and the domain CLAUDE.md.

---

## capabilities backfill — bootstrap from existing project artifacts

Scans existing features, specs, and ADRs to propose an initial set of
domain-organized capabilities. Use this when adopting the capability index
on a project that already has work done.

### Step 1 — Gather existing artifacts

Read these sources (skip any that don't exist):

1. **Feature briefs:** `.feature/_archive/*/brief.md` and `.feature/*/brief.md`
   — extract feature name and description
2. **Spec registry:** `.spec/registry/manifest.json` — extract domain
   taxonomy and feature IDs
3. **Confirmed ADRs:** `.decisions/CLAUDE.md` — extract accepted decision
   slugs and their recommendation summaries
4. **KB topics:** `.kb/CLAUDE.md` — extract topic map for cross-reference

### Step 2 — Detect existing flat capabilities

If `.capabilities/CLAUDE.md` exists and contains a flat Capability Map
table (no Domain Map), this is a migration. Read existing entries and
incorporate them into the domain proposal.

### Step 3 — Propose domain groupings

Analyze the features, specs, and ADRs to identify logical domains:

1. Use the spec registry's domain taxonomy as the primary signal for
   domain boundaries (e.g., `encryption`, `query`, `engine` spec domains
   suggest capability domains)
2. Group features by the user-visible concern they serve — not by
   implementation coupling
3. Identify cross-cutting capabilities that compose multiple domains
   (candidates for emergent type)

Present proposed domains:

```
Backfill analysis suggests <n> capability domains:

  <domain name> — <description>
    Capabilities: <list>

  <domain name> — <description>
    ...
```

Use AskUserQuestion:
- "Accept domains" — proceed with these domains
- "Adjust" — modify domain names, merge, or split (describe changes)

### Step 4 — Propose capabilities within domains

For each accepted domain, propose capabilities:

1. Group features that contribute to the same user-visible capability
2. Assign feature roles: `core` for primary implementation, `extends` for
   new dimensions, `quality` for performance/cleanup
3. Identify pre-existing capabilities implied by specs or ADRs that have
   no feature backing
4. Identify refinement candidates — capabilities where all features have
   `quality` role
5. Identify emergent candidates — capabilities that compose 2+ other
   capabilities from different domains

Present all proposals grouped by domain:

```
<domain name>:

  1. <capability name> (core) — <description>
     Features: <slug> (core), <slug> (quality)
     Specs: <refs>  ADRs: <refs>

  2. <capability name> (emergent) — <description>
     Composes: <capability A> + <capability B>

  ...
```

Use AskUserQuestion:
- "Create all" — create entries for all proposed capabilities
- "Select" — choose which ones to create
- "Review" — show full details before deciding

### Step 5 — Create entries

For each accepted capability:
1. Create the domain directory and domain CLAUDE.md if it doesn't exist
2. Write the capability entry file with gathered data pre-filled
3. The user can adjust descriptions, tags, and cross-references before
   writing

### Step 6 — Summary

```
Backfill complete:
  Domains: <n>
  Capabilities: <n> (<n> core, <n> emergent, <n> refinement)
  Features linked: <n>
  Specs linked: <n>
  ADRs linked: <n>

Run /capabilities list to see the full index.
```

---

## Setup — seed template

When creating `.capabilities/CLAUDE.md` for the first time:

```markdown
# Project Capabilities

> Managed by vallorcine agents. Use /capabilities to query.
> Pull model. Navigate: domain → capability file.
> Do not scan this directory recursively.
> Structure: .capabilities/<domain>/<capability>.md

## Domain Map

| Domain | Path | Capabilities | Last Updated |
|--------|------|-------------|--------------|

## Recently Updated (last 5)

| Date | Domain | Capability | Change |
|------|--------|-----------|--------|
```

---

## Domain index template

When creating `.capabilities/<domain>/CLAUDE.md`:

```markdown
# <Domain Name> — Capability Domain

> Pull model. Read capability files for details.

<1-2 sentence description of what this domain covers>

## Capabilities

| Capability | Type | Status | Tags | Features |
|-----------|------|--------|------|----------|

## Cross-references

- **KB topics:** <related KB topic paths>
- **Spec domains:** <related spec domain names>
```

---

## Capability entry template

When writing a new `.capabilities/<domain>/<slug>.md`:

```markdown
---
title: "<name>"
slug: "<slug>"
domain: <domain-slug>
status: <active | planned | deprecated>
type: <core | emergent | refinement>
tags: [<tags>]
features:
  - slug: <feature-slug>
    role: <core | extends | quality | enables>
    description: "<one-line contribution>"
composes: [<domain/capability slugs>]  # Only for type: emergent
spec_refs: [<spec IDs>]
decision_refs: [<ADR slugs>]
kb_refs: [<KB topic/category paths>]
depends_on: [<domain/capability slugs>]
enables: [<domain/capability slugs>]
---

# <title>

<2-5 sentences: what the user can do, what problem it solves>

## What it does

<expanded description>

## Features

<rendered from frontmatter features array, grouped by role>

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
- **Composes:** <list, only for emergent>
- **Deferred work:** <list of related deferred ADRs>
```
