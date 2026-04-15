---
problem: "sstable-end-to-end-integrity"
evaluated: "2026-04-14"
candidates:
  - name: "Per-section CRC32C only"
    source: "Natural extension of per-block-checksums"
  - name: "Three-layer integrity (fsync + VarInt blocks + CRC)"
    source: "Deliberation — user challenge on recovery"
constraint_weights:
  scale: 2
  resources: 1
  complexity: 2
  accuracy: 3
  operational: 3
  fit: 2
---

# Evaluation — sstable-end-to-end-integrity

## References
- Constraints: [constraints.md](constraints.md)
- Parent ADR: [per-block-checksums](../per-block-checksums/adr.md)
- KB: [corruption-detection-repair](../../.kb/systems/database-engines/corruption-detection-repair.md)

## Constraint Summary
End-to-end integrity requires both detection (checksums) and recoverability
(self-describing data). The compression map is a single point of failure — if
it's corrupt, all data blocks are unreachable. Prevention (fsync discipline)
eliminates the most common corruption vector on local filesystems.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | Must work across local/remote, all block sizes |
| Resources | 1 | Minimal overhead; pure Java |
| Complexity | 2 | Format extension, not redesign |
| Accuracy | 3 | No silent corruption; data must be recoverable |
| Operational | 3 | Self-healing reader; no external tools |
| Fit | 2 | Extends existing format lineage |

---

## Candidate: Per-Section CRC32C Only

Detection-only approach. Add CRC32C checksums for metadata sections in footer.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 2 | 4 | 8 | Works for all backends |
| Resources | 1 | 5 | 5 | 16 bytes added to footer |
| Complexity | 2 | 5 | 10 | Simple footer extension |
| Accuracy | 3 | 3 | 9 | Detects but cannot recover; corrupt compression map = total data loss |
|          |   |   |   | **Would be a 2 if:** compression map corruption occurs (all data unreachable) |
| Operational | 3 | 3 | 9 | Detects at open time but throws; no recovery path |
| Fit | 2 | 5 | 10 | Natural extension of per-block pattern |
| **Total** | | | **51** | |

---

## Candidate: Three-Layer Integrity

1. **fsync discipline** — prevention layer
2. **VarInt-prefixed blocks** — recovery layer
3. **Per-section CRC32C** — detection layer

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 2 | 5 | 10 | Remote: atomic writes + CRC. Local: fsync prevents partial writes |
| Resources | 1 | 4 | 4 | VarInt prefix: 2 bytes per block (common case). CRC: 16 bytes in footer |
| Complexity | 2 | 4 | 8 | Three concerns but each is small; VarInt is trivial |
| Accuracy | 3 | 5 | 15 | Prevents corruption (fsync), enables recovery (self-describing blocks), detects residual (CRC) |
| Operational | 3 | 5 | 15 | Self-healing: reader can scan VarInt-prefixed blocks to rebuild compression map |
| Fit | 2 | 4 | 8 | Extends format; VarInt prefix is a data section change |
| **Total** | | | **60** | |

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Total |
|-----------|-------|-----------|------------|----------|-------------|-----|-------|
| CRC only | 8 | 5 | 10 | 9 | 9 | 10 | **51** |
| **Three-layer** | **10** | **4** | **8** | **15** | **15** | **8** | **60** |

## Recommendation
Three-layer integrity dominates on accuracy and operational dimensions. The
additional complexity (VarInt prefix, fsync ordering) is justified by the
recovery capability it enables. Detection alone leaves the compression map
as a single point of failure.
