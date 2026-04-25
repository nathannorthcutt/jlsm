---
title: "Module-DAG Spec Anticipation: sealed-type construction across non-internal packages"
type: knowledge-base
tags: [jpms, sealed-types, spec-authoring, module-graph, encapsulation, factory-pattern, trust-boundary]
related_adrs:
  - ".decisions/module-dag-sealed-type-public-factory-carve-out/adr.md"
  - ".decisions/table-handle-scope-exposure/adr.md"
related_specs:
  - ".spec/domains/sstable/footer-encryption-scope.md (v5, R8f)"
related_features:
  - "implement-encryption-lifecycle--wd-02"
sources:
  - title: "ADR — module-dag-sealed-type-public-factory-carve-out"
    url: "../../../.decisions/module-dag-sealed-type-public-factory-carve-out/adr.md"
    accessed: "2026-04-25"
  - title: "Spec — sstable.footer-encryption-scope v5 (R8f)"
    url: "../../../.spec/domains/sstable/footer-encryption-scope.md"
    accessed: "2026-04-25"
  - title: "Cycle log — implement-encryption-lifecycle--wd-02 WU-1 implementation note"
    url: "../../../.feature/implement-encryption-lifecycle--wd-02/cycle-log.md"
    accessed: "2026-04-25"
last_updated: "2026-04-25"
---

# Module-DAG Spec Anticipation

## Pattern statement

> A spec mandating "non-public constructor" for a sealed-permitted internal
> type must anticipate that the legitimate construction caller may live in a
> sibling **public** (exported) package within the same module. JPMS package
> visibility is per-package, not per-module — package-private members are
> not reachable across package boundaries even within the same module.
> Specs that ignore this collide with the JLS access rules and force
> implementation deviations.

## When this matters

The pattern fires when *all* of the following hold:

1. A spec mandates a sealed type whose permits live in **non-exported**
   (`<module>/<feature>.internal`) packages. This is the standard project
   pattern for trust boundaries (R8e/R8f shape).
2. The spec additionally mandates a **non-public constructor** as
   defence-in-depth.
3. The legitimate caller of that constructor lives in a **different,
   exported** package within the same module (e.g. a public engine entry
   point that returns the sealed handle).

Java's package-private modifier does not span packages, so condition (3)
makes the literal phrasing in (2) unreachable from the legitimate caller.

## The empirical example (WD-02)

`sstable.footer-encryption-scope` v5 R8f mandates a non-public constructor
for **both** sealed permits of `Table`:

- `CatalogTable` lives in `jlsm.engine.internal`, constructed by
  `LocalEngine` from inside the same internal package — the package-private
  constructor is reachable directly. **No deviation.**
- `CatalogClusteredTable` lives in `jlsm.engine.cluster.internal`,
  constructed by `ClusteredEngine` which lives in the **public**
  `jlsm.engine.cluster` package (it is part of the cluster API surface
  per `engine-api-surface-design`). The package-private constructor is
  **not** reachable from there.

The WD-02 implementation deviated from the literal spec phrasing: it kept
the package-private canonical constructor as the load-bearing trust
boundary, and added a `public static forEngine(...)` factory whose body is
1:1 delegation to the constructor. The factory is the single legitimate
construction surface within the module; external modules cannot reach it
because the impl's package is not exported.

The runtime trust boundary is therefore the **non-exported package** plus
the package-private constructor. The factory's `public` modifier only
matters intra-module, where defence-in-depth is already cooperatively
maintained.

## The three options when this fires

| Option | Description | Cost |
|--------|-------------|------|
| **A. Public static factory + non-exported package + pkg-private ctor** | Add `public static forCaller(...)` whose body is 1:1 delegation to the canonical pkg-private ctor. Non-exported package remains the load-bearing trust boundary. | Spec phrasing is technically violated unless re-phrased; defence-in-depth is intra-module reachable but external modules cannot reach the factory because the package is not exported. **Chosen for WD-02.** |
| **B. Co-locate caller and impl in same package** | Move the public caller into the internal package, or move the impl into the public package. | Removes the public caller from the API surface (breaks consumers) or makes the impl externally subclassable (collapses the trust boundary). Both contradict existing ADRs in jlsm's case. |
| **C. Module-export carve-out** | Export the internal package conditionally so the caller can reach the package-private ctor. | Category error. JPMS `exports` governs **inter-module** access only — cross-package access within the same module is governed by package-level visibility modifiers, which `exports` does not affect. |

A fourth option — reflection-based factories (`MethodHandles.privateLookupIn`
etc.) — is explicitly out-of-scope for jlsm per R8f bullet 4's threat
model: reflection access is treated as already inside the module boundary.

## Spec-authoring guidance

When writing or amending a spec that mandates a trust boundary on a
sealed-permitted internal type, **avoid the literal phrasing**:

> "The canonical constructor must be non-public."

This phrasing is module-graph-blind. Replace it with the canonical
project-aware phrasing:

> "Construction is gated by a non-exported package containing the
> canonical (package-private) constructor. When the legitimate caller
> lives in a sibling public package within the same module, the impl
> class additionally exposes a `public static` factory whose body is
> 1:1 delegation to the canonical constructor; the factory is the sole
> public construction surface within the module. External modules
> cannot reach the factory because the impl's package is not exported."

The non-exported package status is the load-bearing trust boundary. The
constructor visibility modifier is defence-in-depth above that boundary.
Specs should express the trust boundary in terms of the **package-export
declaration**, not the constructor modifier alone.

## Likely future candidates

Any future sealed-with-internal-permits type whose construction caller
lives in a non-internal package within the same module is a candidate:

- **Index manifests** — if an `IndexManifest` interface seals over multiple
  internal manifest impls and a public `IndexEngine` constructs them.
- **Cluster routing tables** — if a `RoutingTable` seals over internal
  partitioning strategies constructed from a public `ClusterEngine`.
- **Scan engines** — if a `ScanEngine` interface seals over local/remote
  scan impls constructed from a public `Engine` entry point.

Each of these would face the same module-graph collision and should
adopt the canonical pattern from the start, rather than discovering it
during implementation review.

## Why this is JPMS-specific (not generic Java)

The collision is not visible in non-modular Java because the
"non-exported package" trust boundary does not exist there. Pre-JPMS,
package-private was the only intra-JAR encapsulation primitive, and
specs phrased in terms of "non-public constructor" carried their full
intent.

JPMS introduced a **second** encapsulation layer (the `exports`
declaration) which is strictly stronger than package-private for
inter-module trust. Specs written in the pre-JPMS idiom under-specify
the load-bearing boundary by omitting the export declaration and
over-specify the defence-in-depth layer by mandating a particular
visibility modifier.

The pattern statement above is the project's response to that gap.

## Cross-references

- **ADR**: [`module-dag-sealed-type-public-factory-carve-out`](../../../.decisions/module-dag-sealed-type-public-factory-carve-out/adr.md)
  — formal decision record codifying the pattern with a worked example
  (`CatalogClusteredTable.forEngine`).
- **Related ADR**: [`table-handle-scope-exposure`](../../../.decisions/table-handle-scope-exposure/adr.md)
  v2 — establishes the sealed-`Table` shape with two permits across
  two internal packages that produced this collision.
- **Spec**: `sstable.footer-encryption-scope` v5 R8f — the spec text
  whose literal phrasing collides with the module DAG. A v6 amendment
  is recommended (per the ADR's spec-amendment guidance) to adopt the
  canonical phrasing above.
- **Feature footprint**: `implement-encryption-lifecycle--wd-02` — the
  WD-02 cycle log captures the WU-1 implementation deviation note that
  surfaced this pattern.

## Updates

(none — initial entry)
