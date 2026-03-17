---
problem: "vector-type-serialization-encoding"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-03-17"
---

# Vector Type Serialization Encoding — Decision Index

**Problem:** Design the binary encoding for VectorType in DocumentSerializer
**Status:** confirmed
**Current recommendation:** Flat Vector Encoding — contiguous `d × sizeof(T)` bytes, no per-vector metadata
**Last activity:** 2026-03-17 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-03-17 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix | 2026-03-17 |
| [constraints.md](constraints.md) | Constraint profile | 2026-03-17 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-03-17 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Flat Vector Encoding | Chosen | [`.kb/algorithms/vector-encoding/flat-vector-encoding.md`](../../.kb/algorithms/vector-encoding/flat-vector-encoding.md) |
| Sparse Vector Encoding | Rejected — variable size contradicts fixed-dim invariant | [`.kb/algorithms/vector-encoding/sparse-vector-encoding.md`](../../.kb/algorithms/vector-encoding/sparse-vector-encoding.md) |
| Lossless Vector Compression | Rejected — decode overhead on hot read path | [`.kb/algorithms/vector-encoding/lossless-vector-compression.md`](../../.kb/algorithms/vector-encoding/lossless-vector-compression.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-03-17 | active | Flat encoding chosen for zero-decode deserialization |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
