---
problem: "compression-codec-api-design"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-03-17"
---

# Compression Codec API Design — Decision Index

**Problem:** Interface shape, registration, and tree builder integration for pluggable compression codecs
**Status:** confirmed
**Current recommendation:** Open interface + explicit codec list — non-sealed `CompressionCodec` interface, reader takes varargs codecs, builder captures in factory lambdas
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
| Block Compression Algorithms | Informed codec design | [`.kb/algorithms/compression/block-compression-algorithms.md`](../../.kb/algorithms/compression/block-compression-algorithms.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-03-17 | active | Open interface + explicit codec list chosen over sealed and enum patterns |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
