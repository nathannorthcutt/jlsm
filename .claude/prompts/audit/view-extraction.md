# View Extraction — Design Specification

This document specifies the mechanical view extraction step that produces
per-lens card projections from reconciled construct cards. This is a script,
not an LLM pass. It runs after card construction + reconciliation and before
clustering.

---

## Context

Construct cards are the single source of truth for what was observed during
analysis. Each card contains execution, state, and contract fields. Different
pipeline stages need different subsets of this data:

- **Clustering** needs execution + state fields only. Contract fields must
  not be visible to the clustering agent — LLMs use everything they can see,
  and contracts would influence cluster boundaries in unintended ways.
- **Suspect** needs the full reconciled card including contracts.
- **Domain-lens clustering** needs per-lens projections containing only the
  fields relevant to that domain.

View extraction is the mechanism that produces these projections. It is
mechanical (bash/python script), deterministic, and produces no analytical
judgments.

---

## Input

Reconciled construct cards in YAML format, one file per construct or one
combined file with YAML document separators (`---`). Each card has the full
schema:

```yaml
construct: <name>
kind: <class | interface | function | module | inner_type | enum>
location: <file:line_range>

execution:
  invokes: [<construct names>]
  invoked_by: [<construct names>]          # added by reconciliation
  entry_points: [<method/function names>]

state:
  owns: [<field/resource names>]
  reads_external: [<construct.field>]
  writes_external: [<construct.field>]
  read_by: [<construct names>]             # added by reconciliation
  written_by: [<construct names>]          # added by reconciliation
  co_mutators: [<construct names>]         # derived by reconciliation
  co_readers: [<construct names>]          # derived by reconciliation

contracts:
  guarantees:
    - what: <postcondition>
      evidence: <code reference>
  assumptions:
    - what: <unvalidated condition>
      evidence: <absence of check or implicit dependency>
      failure_mode: <what breaks>

reconciliation:
  inconsistencies:                         # added by reconciliation
    - type: <invokes_without_entry_point | ...>
      source: <construct>
      target: <construct>
      detail: <description>
```

---

## Domain lens definitions

Each domain lens specifies which card fields are relevant for clustering
in that domain. Fields not listed are stripped from the lens view.

### Shared state lens
```
fields:
  - state.owns
  - state.reads_external
  - state.writes_external
  - state.read_by
  - state.written_by
  - state.co_mutators
  - state.co_readers
  - execution.invokes        # needed for call-chain context
  - execution.invoked_by
include_when: >
  construct has non-empty owns, reads_external, writes_external,
  co_mutators, or co_readers
```

### Resource lifecycle lens
```
fields:
  - state.owns
  - execution.invokes
  - execution.invoked_by
  - execution.entry_points
include_when: >
  construct has non-empty owns AND (invokes or invoked_by reference
  constructs that also have non-empty owns on the same resource)
```

### Contract boundaries lens
```
fields:
  - execution.invokes
  - execution.invoked_by
  - execution.entry_points
  - reconciliation.inconsistencies
include_when: >
  construct has cross-module invokes or invoked_by edges
  (source and target in different files)
```

### Data transformation lens
```
fields:
  - state.reads_external
  - state.writes_external
  - execution.invokes
  - execution.invoked_by
include_when: >
  construct reads from one construct and writes to another,
  or participates in a chain where data changes form
```

### Concurrency lens
```
fields:
  - state.owns
  - state.co_mutators
  - state.co_readers
  - state.writes_external
  - state.written_by
  - execution.invoked_by     # who triggers this code
include_when: >
  construct has non-empty co_mutators
```

### Dispatch routing lens
```
fields:
  - execution.invokes
  - execution.invoked_by
  - execution.entry_points
include_when: >
  construct invokes 3+ other constructs that share no mutual
  execution edges between them
```

---

## Process

### Step 1: Read all reconciled cards

Parse the reconciled card file(s) into an in-memory representation.
Each card is a dictionary keyed by construct name.

### Step 2: Determine active lenses

For each domain lens, check the `include_when` predicate against all
cards. A lens is **candidate-active** if at least one construct matches
its inclusion predicate.

Write the candidate-active lens list to `active-lenses.md`. This list
is the input to the LLM domain pruning step (separate from this script),
where each lens is challenged with "This codebase has [domain]. Prove
it doesn't."

Lenses that survive pruning are **confirmed-active**. The pruning step
writes back to `active-lenses.md` with confirmed/pruned status.

### Step 3: Produce per-lens card projections

For each confirmed-active lens:

1. Filter constructs: only include constructs matching the lens's
   `include_when` predicate
2. Project fields: for each included construct, copy only the fields
   listed in the lens's `fields` definition
3. Preserve identity fields on every projected card: `construct`, `kind`,
   `location` (these are always included regardless of lens)
4. Write the projection to `lens-<domain>-cards.yaml`

### Step 4: Produce the full analysis view

Copy all reconciled cards verbatim (no field stripping) to
`analysis-cards.yaml`. This is what Suspect receives. It includes
contracts, reconciliation inconsistencies, and all fields.

---

## Output files

| File | Contents | Consumer |
|------|----------|----------|
| `active-lenses.md` | Candidate and confirmed domain lenses | Domain pruning (LLM), orchestrator |
| `lens-<domain>-cards.yaml` | Per-lens projected cards | Clustering (per lens) |
| `analysis-cards.yaml` | Full reconciled cards | Suspect, Prove |

---

## Implementation notes

- This script has no LLM dependency. It is pure data transformation.
- The `include_when` predicates are evaluated mechanically against card
  fields (non-empty checks, cross-reference lookups). They do not require
  semantic interpretation.
- The domain pruning LLM step runs between Step 2 and Step 3. The script
  can be run in two phases: (1) produce active-lenses.md, (2) after
  pruning, produce projections and analysis view.
- For the dispatch routing lens, "share no mutual execution edges" means:
  for the set of constructs that X invokes, check whether any pair in
  that set has an invokes/invoked_by edge between them. If none do,
  X is a dispatcher candidate.
- Empty projections (lens active but no constructs after filtering) should
  produce an empty file with a comment, not be silently omitted.
