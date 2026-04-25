---
problem: "cluster-format-version-coexistence"
date: "2026-04-24"
version: 1
status: "deferred"
---

# Cluster Format-Version Coexistence — Deferred

## Problem

When jlsm runs embedded in a cluster product (the consumer adds cluster
coordination on top of the library), the per-node format-version
deprecation policy may conflict with cluster-wide format-version
negotiation that the consumer layers on. Specifically: if cluster
nodes run different jlsm binary versions during a rolling upgrade, the
older nodes cannot read v_N artefacts produced by newer nodes — but
they should at least surface this failure clearly so the cluster's
control plane can route around it.

The pre-GA-format-deprecation-policy ADR provides per-node behaviour
only and explicitly defers cluster-mode coexistence.

## Why Deferred

Scoped out during `pre-ga-format-deprecation-policy` decision. jlsm is
a library, not a cluster product; cluster-version-gate semantics
require a control plane that's outside the library's scope. The
falsification subagent surfaced a "C+D hybrid" alternative — exposing
a no-op single-node cluster-version hook in the library that an
embedder optionally drives — as a compelling extension, but with no
concrete consumer driving the requirement today, building it now would
be speculative generality.

## Resume When

A real consumer embeds jlsm in a cluster product and cluster-version
coordination is observed to fight with the per-node deprecation
policy. Likely signals: rolling upgrades produce read errors that
should have been catchable by the control plane; operators ask for
a "supported binary versions for this data" query that has no answer
today.

## What Is Known So Far

Identified during architecture evaluation of
`pre-ga-format-deprecation-policy`. See
[`.decisions/pre-ga-format-deprecation-policy/adr.md`](../pre-ga-format-deprecation-policy/adr.md)
for the architectural context and the C+D hybrid sketch.

KB evidence on cluster-version-gate strategies:
- [`.kb/systems/database-engines/format-version-deprecation-strategies.md#strategies-by-system`](../../.kb/systems/database-engines/format-version-deprecation-strategies.md#strategies-by-system)
  — CockroachDB cluster-version-gate, MongoDB FCV.
- The KB entry's `practical-usage#when-not-to-use` section explicitly
  names "library, not a cluster product" as the case where pure
  cluster-version-gate is wrong.

The C+D hybrid sketch: expose `Engine.formatNegotiationHook(consumer)`
that an embedder optionally drives. Library-only consumers see a
no-op; cluster embedders can implement the hook to coordinate
cross-node version state.

## Next Step

Run `/architect "cluster format-version coexistence"` when ready to
evaluate.
