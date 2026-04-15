# Index Access Pattern Leakage — Decision Index

**Problem:** How should jlsm mitigate and document encrypted index leakage?
**Status:** confirmed
**Current recommendation:** Low-Cost Mitigation Bundle — per-field HKDF keys + power-of-2 response padding + leakage profile documentation
**Last activity:** 2026-04-14 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-14 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix | 2026-04-14 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-14 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-14 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Index Access Pattern Leakage and Mitigations | Leakage taxonomy, attack classes, recommendations | [`.kb/algorithms/encryption/index-access-pattern-leakage.md`](../../.kb/algorithms/encryption/index-access-pattern-leakage.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-14 | active | Per-field keys + padding + leakage documentation |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| ORAM index wrapper | — | deferred | Adaptive ORAM < 5x overhead or compliance mandate |
| Differential privacy noise | — | deferred | Epsilon calibration framework available |
