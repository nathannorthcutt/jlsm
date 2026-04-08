---
description: "Query specs, discover gaps, and trace change impact"
argument-hint: "\"<question or proposed change>\""
---

# /spec "<question or proposed change>"

Single entry point for spec queries, gap discovery, and change impact
analysis. Mirrors `/kb` — natural language input, the skill determines
what you need.

---

## Pre-flight

Check that `.spec/` exists. If not:
```
No specs found. Run /setup-vallorcine first, then /spec-author to create specs.
```
Stop.

Read `.spec/CLAUDE.md` for the domain taxonomy and registry.

---

## Step 0 — Classify the intent

Parse the user's input to determine the mode:

**Discovery mode** — the user is asking about existing requirements:
- "what covers file sizes?"
- "which specs mention SSTable?"
- "do we have requirements for thread safety?"
- No specific requirement ID referenced, no change proposed

**Change mode** — the user is proposing or exploring a change:
- "what if R31 uses long instead of int?"
- "what would break if we removed the 2GB limit?"
- "I want to add validation for negative offsets"
- References a specific requirement ID, or uses change language
  ("what if", "change", "remove", "add", "update")

If ambiguous, use AskUserQuestion with these options:
- `Find requirements (discovery)` (description: "Search for existing requirements about this area")
- `Explore a change (impact analysis)` (description: "See what would break or need updating if something changed")

---

## Discovery Mode

### D1. Search all specs

Search every spec file in `.spec/domains/` for requirements matching
the user's query:

1. Extract 3-5 keywords from the query
2. For each spec file, scan all requirement lines (R<N>.) for keyword
   matches
3. Also scan design narratives and category headings for contextual
   matches
4. Rank results by relevance: exact keyword match > partial match >
   contextual match

### D2. Present results

Group matching requirements by spec:

```
── Spec Discovery ─────────────────────────────
Query: "<user's question>"

F02 (block-compression) — 3 matching requirements:
  R27. Writer must use long arithmetic for compression map sizes
  R31. All file offsets must be stored and computed as long values
  R40. Eager reader must reject SSTables with data region > Integer.MAX_VALUE

F08 (streaming-block-decompression) — 1 matching requirement:
  R15. Block offsets must not be narrowed to int during lazy reads

No matching requirements found in: F01, F03, F04, F05, F06, F07, F09, F10, F11
───────────────────────────────────────────────
```

### D3. Surface gaps

After showing matches, check for gaps:

**No specs match at all:**
```
No spec requirements cover this area. This may be a spec gap.
```

Use AskUserQuestion with these options:
- `Explore` (description: "Search the codebase to see if this behavior exists without a spec")
- `Create` (description: "Start authoring a spec for this area via /spec-author")
- `Done` (description: "Just wanted to check — no action needed")

**Partial coverage (some specs mention it but no spec owns it):**
```
This area is referenced by <N> specs but no spec fully defines it.
<Type/concept> appears in requirements from <specs> but has no
dedicated spec.

  extract  — I'll extract a spec from the implementation and
             cross-reference against all consuming specs
  done     — noted, will address later
```

If "explore": search the codebase for the concept, present what the
implementation does, and offer `/spec-extract` with auto-discovered
source files.

If "extract": invoke the spec-extract prompt with the discovered type.

If "create": invoke `/spec-author` with the area as the feature
description.

### D4. Offer change exploration

After presenting results, offer:
```
Want to explore changing any of these requirements?
  Type a requirement ID (e.g. R31) to see the impact of changing it.
  Type done to finish.
```

Note: Use AskUserQuestion with options for "Done" and "Other" (description:
"Type a requirement ID to explore its change impact"). If the user selects
"Other", wait for them to provide the requirement ID as free text.

If the user provides a requirement ID, transition to Change Mode with
that requirement as the target.

---

## Change Mode

### C1. Identify the target requirement

From the user's input, identify:
- **Which spec** contains the requirement (search if not specified)
- **Which requirement ID** (R<N>)
- **What change** is proposed (modify, remove, add new)

If the user described a change without a specific ID ("add validation
for negative offsets"), search for the relevant spec area first
(Discovery D1-D2), then ask which requirement to modify or whether
to add a new one.

Read the target spec file. Display the current requirement:

```
── Current requirement ────────────────────────
Spec: F02 (block-compression)
R31. All file offsets must be stored and computed as long values,
     not narrowed to int. Int narrowing silently truncates values
     above Integer.MAX_VALUE.

What change do you want to make?
  (describe the change, or type the new requirement text)
```

### C2. Conflict check (MANDATORY)

Before analyzing impact, check if the proposed change contradicts any
existing requirement:

1. Run the contradiction check from `spec-resolve.sh` against all
   specs in the registry
2. Check if any spec's `requires` references the target requirement
3. Check if any spec has requirements that assume the behavior being
   changed

If conflicts found:
```
⚠ This change would conflict with existing requirements:

  F08.R15 assumes block offsets are long (depends on F02.R31)
  F01.R5 references encoding offsets that may be affected

These conflicts must be resolved alongside the change.
```

Use AskUserQuestion with these options:
- `Continue` (description: "Proceed with impact analysis — conflicts will be included")
- `Stop` (description: "Reconsider the change before analyzing further")

### C3. Trace downstream impact

For the proposed change, trace four dimensions:

**Tests:**
- Search test files for `covers: R<N>` or `Finding: F-R<round>.<spec>.R<N>`
  comments matching the target requirement
- Search test method names that reference the requirement's construct
- List each affected test with its file path and what it asserts

**Implementations:**
- From the spec's domain, identify source files that implement the
  requirement's behavior
- Search for the behavioral pattern described in the requirement
  (e.g., "long arithmetic" → search for int casts of offset variables)
- List each affected source file with the specific lines

**Dependent specs:**
- Which specs `require` the target spec?
- Which specs reference the same construct/concept in their requirements?
- For each: which of their requirements depend on the behavior being
  changed?

**Contradictions:**
- From C2: any requirements that directly conflict
- Any requirements that would become redundant after the change
- Any requirements that would need tightening after the change

### C4. Present impact report

```
── Change Impact ──────────────────────────────
Proposed: <description of change>
Target: <spec ID>.<requirement ID>

Tests affected (<N>):
  <TestClass>.<method> — asserts <what> (covers: R31)
  <TestClass>.<method> — asserts <what> (covers: R31)

Source files affected (<N>):
  <path>:<lines> — <what needs to change>
  <path>:<lines> — <what needs to change>

Dependent specs (<N>):
  F08.R15 — assumes long offsets, needs review
  F01.R5 — references encoding offsets, may need update

Conflicts (<N>):
  <spec>.<req> — <contradiction description>

Estimated scope: <small | medium | large>
───────────────────────────────────────────────
```

### C5. Offer next steps

```
What would you like to do?

  apply    — update the spec requirement now and start a work plan
             for the affected tests and implementations
  save     — save this impact report to review later
             (.feature/_spec-changes/<req-id>-impact.md)
  plan     — generate a scoped work plan for this change
             (routes to /feature-plan with the impact as context)
  done     — just exploring, no action needed
```

**If "apply":**
1. Update the requirement in the spec file
2. Run `spec-validate.sh` to verify the spec is still valid
3. Check for conflicts via `spec-resolve.sh`
4. If conflicts found: present resolution options (same as
   resolve-spec-conflict prompt — keep change + update conflicting
   spec, revert, split, defer)
5. After spec is clean: offer to start `/feature-plan` scoped to
   the change

**If "save":**
Write the impact report to `.feature/_spec-changes/<req-id>-impact.md`
with full details. Display the path. The user can pick it up later.

**If "plan":**
Invoke `/feature-plan` with context:
- The changed requirement as the primary input
- The impact report's affected tests and source files as scope
- The dependent specs as constraints
This produces a scoped work plan — only the delta from the change,
not a full feature plan.

**If "done":** End the session.

---

## Seamless transitions

The user should never hit a dead end. Every output offers a natural
next step:

- Discovery → "want to explore a change?" → Change Mode
- Discovery → "no specs cover this" → extract or author
- Change Mode → "want to apply?" → spec update + work plan
- Change Mode → "save for later" → impact file
- Change Mode → conflicts found → resolution flow
- Any gap found → routes to the right command

The user can also chain queries without restarting:
```
After any result, the user can type another question or requirement ID
to continue exploring. Only "done" ends the session.
```

---

## Hard constraints

- Never modify a spec without showing the conflict check results first
- Never skip the conflict check — even for "obvious" changes
- Always show the impact before applying a change
- Discovery results must include gap analysis, not just matches
- Save the impact report when the user asks — don't lose the analysis
