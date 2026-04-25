---
problem: "Spec mandate of 'non-public constructor' for sealed-permitted internal types collides with the JPMS module DAG when the constructor caller lives in a different (public) package within the same module."
slug: "module-dag-sealed-type-public-factory-carve-out"
captured: "2026-04-25"
status: "draft"
---

# Constraint Profile — module-dag-sealed-type-public-factory-carve-out

## Problem Statement

A spec requirement (e.g. `sstable.footer-encryption-scope` v5 R8f) mandates
that a sealed-permitted internal type's canonical constructor be **non-public**
to enforce a runtime trust boundary: only the engine's catalog-mediated
factory code may construct the permitted subtype, so external callers cannot
forge a `Table` whose `metadata()` returns attacker-controlled scope.

Within `jlsm-engine`, the cluster path forces a deviation from the literal
spec text:
- `ClusteredEngine` lives in `jlsm.engine.cluster` (public, exported package).
- `CatalogClusteredTable` (the sealed-permitted impl) lives in
  `jlsm.engine.cluster.internal` (non-exported package).
- Java package-private visibility does **not** cross packages, even within
  the same module. So `ClusteredEngine` cannot reach a package-private
  constructor on `CatalogClusteredTable`.

The implementation therefore exposes `public static forEngine(...)` factory
methods on `CatalogClusteredTable` and keeps the canonical constructors
package-private. The non-exported package + package-private ctor are the
real runtime trust boundary; the public factory is the legitimate
construction surface.

This decision asks: **what is the canonical pattern for jlsm specs and code
when a spec mandates "non-public constructor" for a sealed internal type
whose construction caller lives in a sibling public package within the same
module?** The decision codifies a project-wide pattern so that future specs
phrase the requirement in module-graph-aware language and future
implementations apply the same shape consistently.

## Constraints

### Scale

Small N — currently 5 main jlsm modules (`jlsm-core`, `jlsm-indexing`,
`jlsm-vector`, `jlsm-table`, `jlsm-sql`) plus `jlsm-engine`. The pattern
applies wherever a public package needs to instantiate a sealed-permitted
internal type. Today: `Table` → `CatalogTable` (single-package: ctor is
package-private, fine) and `Table` → `CatalogClusteredTable` (cross-package:
needs the carve-out). Future: any sealed type added across modules with
sibling public/internal packages.

### Resources

Compile-time module discipline only — no runtime cost. JPMS module
descriptors and javac visibility rules enforce the boundary; no reflection,
no `MethodHandles`, no `Module.implAddOpens`. Build configuration is
already in place: `--add-exports jlsm.engine/jlsm.engine.cluster.internal=ALL-UNNAMED`
in the test task, never in production.

### Complexity Budget

Low — pattern-level decision. The chosen pattern must be expressible in
~10 lines of spec language and ~3 lines of Javadoc per affected class.
Any pattern requiring runtime checks, reflection guards, or build-system
gymnastics beyond what already exists is over-budget.

### Accuracy / Correctness

High — this is a security boundary. The carve-out must preserve the
trust property the spec actually wants:
1. External (out-of-module, no `--add-exports`) callers cannot construct
   or subclass the permitted impl.
2. Reflection-based construction by callers with `--add-opens` access is
   explicitly out-of-threat-model (per R8f bullet 4 and R8h carve-out).
3. The **HKDF scope binding** (encryption.primitives-lifecycle R11)
   remains the cryptographic backstop — even if a forged handle reaches
   the read path, the wrong-scope decryption is cryptographically
   impossible.

### Operational Requirements

None — this is a static code-shape decision; no runtime operational
behaviour changes. Test code already uses `--add-exports` to subclass
internal types under R8h's trusted-export carve-out.

### Fit

Must work consistently for jlsm's existing JPMS layout:
- `jlsm-engine` exports `jlsm.engine` and `jlsm.engine.cluster`; does
  NOT export `jlsm.engine.internal` or `jlsm.engine.cluster.internal`.
- `jlsm-core` exports `jlsm.encryption`, `jlsm.bloom`, etc.; does NOT
  export `jlsm.bloom.hash`, `jlsm.wal.internal`, `jlsm.memtable.internal`,
  etc.
- Other modules follow the same `<module>/<feature>.internal` convention.

The pattern must integrate with these existing JPMS exports without
forcing module-graph changes for every sealed type.

## Key Constraints (most narrowing)

1. **JPMS visibility is per-package, not per-module.** Java's
   package-private modifier does not span packages; this is an immutable
   language rule, not a project choice. Any pattern that pretends a
   sealed type's constructor can be both "non-public" and reachable
   from a sibling public package is incoherent with the JVM type system.

2. **The exported-package boundary is the actual trust boundary.** The
   spec's intent is "external callers cannot construct"; the JPMS
   `exports` declaration in `module-info.java` is the mechanism that
   enforces this. Constructor visibility is a secondary enforcement
   layer that only matters within the module.

3. **Cryptographic defence is the final barrier.** R6b/R11's HKDF scope
   binding makes wrong-scope decryption cryptographically impossible
   regardless of how an attacker reaches the reader. Any pattern that
   trades type-system rigour for module-graph fit is acceptable so long
   as the cryptographic backstop remains intact.

## Unknown / Not Specified

None — full profile captured. All six dimensions specified by the
caller and corroborated against existing module-info, build.gradle,
and the v5 spec text already in `.spec/domains/sstable/footer-encryption-scope.md`.
