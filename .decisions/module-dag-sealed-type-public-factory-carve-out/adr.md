---
problem: "module-dag-sealed-type-public-factory-carve-out"
date: "2026-04-25"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-engine/src/main/java/module-info.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/Table.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/internal/CatalogTable.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/internal/CatalogClusteredTable.java"
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/ClusteredEngine.java"
  - ".spec/domains/sstable/footer-encryption-scope.md"
---

# ADR — Module-DAG Sealed-Type Public-Factory Carve-Out

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision

| Subject | Role in decision | Link |
|---------|-----------------|------|
| (none — Java/JPMS pattern) | Pattern grounded in language-spec invariants and existing project ADRs | — |

Related ADRs surveyed:
- [`.decisions/table-handle-scope-exposure/adr.md`](../table-handle-scope-exposure/adr.md) v2 — sealed `Table`
  with two permits (`CatalogTable`, `CatalogClusteredTable`)
- [`.decisions/sstable-footer-scope-format/adr.md`](../sstable-footer-scope-format/adr.md) — consumer of
  the sealed-Table trust boundary
- [`.decisions/engine-api-surface-design/adr.md`](../engine-api-surface-design/adr.md) — public Engine
  surface that the factories serve

---

## Files Constrained by This Decision

The frontmatter `files:` field lists the source files this decision
shapes:

- `modules/jlsm-engine/src/main/java/module-info.java` — declares the
  public/internal export boundary that is the load-bearing trust mechanism
- `modules/jlsm-engine/src/main/java/jlsm/engine/Table.java` — the sealed
  interface
- `modules/jlsm-engine/src/main/java/jlsm/engine/internal/CatalogTable.java`
  — single-package permit (no carve-out needed)
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/internal/CatalogClusteredTable.java`
  — cross-package permit using the carve-out
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/ClusteredEngine.java`
  — public-package construction caller using the factory
- `.spec/domains/sstable/footer-encryption-scope.md` — contains R8f text
  this ADR amends in its phrasing

## Problem

Spec text (e.g. `sstable.footer-encryption-scope` R8f) mandates a
**non-public constructor** for the sealed-permitted internal types
that implement `Table`. Java's per-package access rules collide with
this when the construction caller lives in a sibling **public** package
within the same module: package-private members are not visible across
package boundaries, so a literal "non-public constructor" cannot be
reached from `jlsm.engine.cluster.ClusteredEngine` (public, exported)
into `jlsm.engine.cluster.internal.CatalogClusteredTable` (non-exported)
without a workaround.

This ADR codifies the project-wide pattern for this collision so future
specs and implementations apply it consistently.

## Constraints That Drove This Decision

- **JPMS visibility is per-package, not per-module.** Java's
  package-private modifier does not span packages. This is a JLS
  invariant, not a project choice — any pattern that ignores it is
  incoherent with the type system.
- **The exported-package boundary is the actual trust boundary.** The
  `module-info.java` exports clause is the load-bearing security
  mechanism; intra-module visibility modifiers are defence-in-depth.
- **Cryptographic backstop is the final defence.** R6b/R11's HKDF
  scope binding makes wrong-scope decryption cryptographically
  impossible regardless of how a forged handle reaches the read path.
  This means a small relaxation of constructor visibility is
  acceptable so long as the export boundary stays intact.

## Decision

**Chosen pattern: Public static factory + non-exported package +
package-private constructor.**

When a sealed-permitted internal type T must be constructed by a caller
C that lives in a sibling **public** package within the same module:

1. T lives in a **non-exported** package (e.g. `jlsm.<feature>.internal`).
   The package's non-export status is the load-bearing trust boundary.
   `module-info.java` MUST NOT export the package; production deployments
   MUST NOT set `--add-exports` for it (per `sstable.footer-encryption-scope`
   R8j's deployment guidance).
2. T's canonical constructor is **package-private**. External and
   cross-package callers cannot reach the constructor through the
   language access rules.
3. T exposes one **`public static`** factory method per legitimate
   construction arity, named to identify the legitimate caller class
   (e.g. `forEngine(...)`, `forClusteredEngine(...)`). The factory's
   body is **1:1 delegation**: it accepts the same arguments as the
   ctor and immediately returns `new T(args)`. No defaulting, no
   validation that differs from the ctor — the factory is a pure
   visibility bridge.
4. The factory is reachable intra-module from the sibling public
   package (the legitimate caller). External modules cannot reference
   T at all (the package is not exported), so the factory's `public`
   modifier only matters intra-module.
5. `module-info.java` continues NOT to export the internal package.
   Tests reach T via `--add-exports
   <module>/<feature>.internal=ALL-UNNAMED` under R8h's trusted-export
   carve-out; production deployments must not set this flag.

When the construction caller lives in the **same** package as T, the
factory is unnecessary — the package-private constructor is reachable
directly. Single-package examples in jlsm today: `CatalogTable` is
constructed by `LocalEngine` from inside `jlsm.engine.internal`; no
factory is needed.

### Spec phrasing pattern

Specs MUST express the trust boundary in module-graph-aware language.
Replace literal phrasings of the form

> "The canonical constructor must be non-public."

with the project-canonical phrasing:

> "Construction is gated by a non-exported package containing the
> canonical (package-private) constructor. When the legitimate caller
> lives in a sibling public package within the same module, the impl
> class additionally exposes a `public static` factory whose body is
> 1:1 delegation to the canonical constructor; the factory is the
> sole public construction surface within the module. External
> modules cannot reach the factory because the impl's package is not
> exported."

### Worked example (current code)

```java
// module-info.java — jlsm-engine
module jlsm.engine {
    requires transitive jlsm.table;
    requires jlsm.core;
    exports jlsm.engine;             // public API
    exports jlsm.engine.cluster;     // public API
    // jlsm.engine.internal NOT exported
    // jlsm.engine.cluster.internal NOT exported
}

// jlsm.engine — public sealed type
public sealed interface Table extends AutoCloseable
        permits jlsm.engine.internal.CatalogTable,
                jlsm.engine.cluster.internal.CatalogClusteredTable { ... }

// jlsm.engine.cluster.internal — sealed permit, non-exported package
public non-sealed class CatalogClusteredTable implements Table {

    /** Public static factory — sole public construction surface. */
    public static CatalogClusteredTable forEngine(/* args */) {
        return new CatalogClusteredTable(/* args */);   // 1:1 delegation
    }

    /** Canonical constructor — package-private. */
    CatalogClusteredTable(/* args */) { ... }
}

// jlsm.engine.cluster — public package, sibling of internal
public final class ClusteredEngine implements Engine {
    Table tableHandle = CatalogClusteredTable.forEngine(/* args */);
}
```

## Rationale

### Why public static factory + non-exported package + package-private ctor

- **Module-graph compatible.** The pattern works without any
  `module-info.java` change. The non-exported package is the trust
  boundary that already aligns with every other internal package in
  the project.
- **Already validated by shipping code.** `CatalogClusteredTable.forEngine(...)`
  passed adversarial review and ships in the WD-02 implementation.
  Tests pass; `./gradlew check` is green.
- **Preserves the spec's intent at the load-bearing layer.** External
  callers cannot import `CatalogClusteredTable`, cannot reference the
  factory, and cannot subclass the type. The factory's `public`
  modifier is irrelevant outside the module because the package is
  not exported.
- **Defence-in-depth survives.** Even with intra-module reachability,
  the package-private ctor still bars accidental cross-package
  construction within `jlsm-engine` (e.g. a future internal helper in
  another package). The factory is the deliberate exception.
- **Pairs with the cryptographic backstop.** HKDF scope binding (R11)
  makes any wrong-scope read cryptographically infeasible regardless
  of how the forged handle was constructed. The type-system layer is
  defence-in-depth above an already-hard cryptographic floor.

### Why not co-locate caller and impl (Candidate B)

`ClusteredEngine` is a deliberate public API entry point per
`engine-api-surface-design`. Moving it into `jlsm.engine.cluster.internal`
removes it from the exported API surface and breaks every consumer.
Moving `CatalogClusteredTable` into `jlsm.engine.cluster` (public)
makes it externally subclassable and collapses the trust boundary.
Both moves contradict existing ADRs.

### Why not module-export carve-out (Candidate C)

JPMS `exports` only governs **inter-module** access. Cross-package
access **within the same module** is governed by package-level
visibility modifiers, which `exports` does not affect. The candidate
is a category error.

### Why not reflection-based factory (Candidate D)

`sstable.footer-encryption-scope` R8f bullet 4 declares reflection
access (`--add-opens`, `MethodHandles.privateLookupIn`,
`sun.misc.Unsafe`) **explicitly out of the threat model** — the
project's posture is "if an attacker has reflection access, they are
already inside the module boundary." Using reflection for legitimate
construction blurs the line that the threat model deliberately draws
and forces audit reviewers to trace lookup chains to distinguish
legitimate from adversarial reflection sites.

## Implementation Guidance

### Applying the pattern

When introducing a new sealed-permitted internal type T whose construction
caller lives in a sibling public package within the same module:

1. Place T in `<module>/<feature>.internal` (non-exported per
   `module-info.java`).
2. Make T's canonical constructor package-private.
3. Add one `public static` factory per legitimate construction arity.
   Each factory body MUST be 1:1 delegation: same parameter list,
   immediate `return new T(args)`. No additional logic.
4. Document the factory's purpose in Javadoc with a `@spec` reference
   to whichever spec mandates the trust boundary (e.g.
   `@spec sstable.footer-encryption-scope.R8f`).
5. Class-level Javadoc MUST state that the package's non-export
   status is the actual trust boundary and that the factory is the
   intra-module bridge.
6. No `module-info.java` change is required. Continue to NOT export
   the internal package.

### Spec authoring guidance

When writing or amending a spec that mandates a trust boundary on a
sealed-permitted internal type, use the canonical phrasing in the
**Spec phrasing pattern** section above. Specs that mandate a literal
"non-public constructor" without acknowledging the module-graph case
SHOULD be re-phrased.

The current R8f text in `sstable.footer-encryption-scope` (v5) is
compliant in intent but ambiguous in literal reading. A v6 amendment
SHOULD re-phrase R8f bullet 1 to the canonical pattern, with the
existing `CatalogClusteredTable.forEngine` shape cited as the
reference example. Spec amendment is tracked separately.

### Test-code interaction

The R8h trusted-export carve-out is unaffected. In-tree tests reach
internal classes via
`--add-exports jlsm.engine/jlsm.engine.cluster.internal=ALL-UNNAMED`
in `modules/jlsm-engine/build.gradle`'s test task. Tests that
subclass `CatalogClusteredTable` for adversarial scenarios (e.g.
`RecordingTable`, `MetadataOnlyStub`) continue to work without
needing the factory — direct constructor access is permitted under
the carve-out.

### Build/CI verification

`./gradlew check` is the verification gate:
- Compile-time check: external (test) modules without `--add-exports`
  receive a compile error if they reference internal classes.
- The existing `engine-api-surface-design` ADR's contracts continue
  to hold.

## What This Decision Does NOT Solve

- **Cross-module sealed types.** This ADR addresses the same-module,
  cross-package case. A sealed type whose permits live in a
  different module would face additional questions (qualified
  exports, module read-edges) not covered here. Defer until such
  a case actually arises.
- **Test-code factory pattern.** Whether tests should prefer the
  factory or direct ctor access via `--add-exports` is a test-style
  choice covered by R8g/R8h, not this ADR.
- **Spec text amendment for R8f.** This ADR documents the canonical
  phrasing and recommends amendment; the actual spec amendment to
  `sstable.footer-encryption-scope` v6 is tracked outside this ADR
  (likely in WD-02 retro or a follow-up spec-write pass).

## Conditions for Revision

This ADR should be re-evaluated if:

- A future Java version (Java 26+) introduces module-private access
  modifiers (e.g. `module-private`, JEP-level changes to sealed-type
  access semantics) that supersede the factory pattern.
- A jlsm module ever gains a sealed type whose permits live in a
  **different** module — this ADR explicitly does not cover that case.
- The HKDF scope binding (R11 cryptographic backstop) is materially
  weakened or removed — the pattern's tolerance for intra-module
  factory reachability assumes the cryptographic floor remains.
- The number of public-factory entries on a single internal class
  grows past ~6 (current `CatalogClusteredTable` has 6 `forEngine`
  overloads) — at that point a builder pattern may simplify the
  surface, requiring a localized revision.

---
*Confirmed by: autonomous architect protocol (per session directive) | Date: 2026-04-25*
*Full scoring: [evaluation.md](evaluation.md)*
