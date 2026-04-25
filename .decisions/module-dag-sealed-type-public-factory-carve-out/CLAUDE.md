---
problem: "module-dag-sealed-type-public-factory-carve-out"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-25"
---

# Module-DAG Sealed-Type Public-Factory Carve-Out — Decision Index

**Problem:** Spec mandates of "non-public constructor" for
sealed-permitted internal types collide with Java's per-package
visibility rules when the construction caller lives in a sibling
public package within the same module.
**Status:** confirmed
**Current recommendation:** Public static factory + non-exported
package + package-private constructor; the `module-info.java` exports
boundary is the load-bearing trust mechanism, the factory is the
intra-module construction surface (1:1 delegation to the ctor), and
the package-private ctor is residual defence-in-depth. Spec authors
should use module-graph-aware phrasing rather than literal "non-public
constructor."
**Last activity:** 2026-04-25 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-25 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix (4 candidates: A=53, B=28, C=23, D=20) | 2026-04-25 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-25 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-25 |

## KB Sources Used

None — Java/JPMS pattern decision grounded in language-spec invariants
and existing project ADRs (no algorithmic research required).

Related ADRs surveyed:
- [`.decisions/table-handle-scope-exposure/adr.md`](../table-handle-scope-exposure/adr.md) v2
- [`.decisions/sstable-footer-scope-format/adr.md`](../sstable-footer-scope-format/adr.md)
- [`.decisions/engine-api-surface-design/adr.md`](../engine-api-surface-design/adr.md)

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-25 | active | Public factory + non-exported package + package-private ctor pattern; spec phrasing guidance for module-graph-aware trust boundary |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| Cross-module sealed types (permits in different module) | — | not captured (out of scope per ADR; defer until case arises) | A jlsm sealed type ever has permits in a different module |
| Spec amendment for R8f canonical phrasing | — | tracked outside this ADR (WD-02 retro / spec-write pass) | When `sstable.footer-encryption-scope` v6 is authored |
