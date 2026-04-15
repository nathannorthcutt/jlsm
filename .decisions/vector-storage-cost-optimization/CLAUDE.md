---
problem: "vector-storage-cost-optimization"
status: "confirmed"
active_adr: "adr.md"
last_updated: "2026-04-13"
---

# Vector Storage Cost Optimization — Decision Index

**Problem:** Reduce vector storage cost via quantization at billion-scale
**Status:** confirmed
**Current recommendation:** QuantizationConfig on IndexDefinition + custom SPI escape hatch
**Last activity:** 2026-04-13 — decision-confirmed

## Decision Files

| File | Purpose | Last Updated |
|------|---------|--------------|
| [adr.md](adr.md) | Active Architecture Decision Record | 2026-04-13 |
| [evaluation.md](evaluation.md) | Candidate scoring matrix | 2026-04-13 |
| [constraints.md](constraints.md) | Constraint profile | 2026-04-13 |
| [log.md](log.md) | Full decision history + deliberation summaries | 2026-04-13 |

## KB Sources Used

| Subject | Status in decision | Link |
|---------|-------------------|------|
| Scalar Quantization | First implementation target | [`.kb/algorithms/vector-quantization/scalar-quantization.md`](../../.kb/algorithms/vector-quantization/scalar-quantization.md) |
| RaBitQ | Second target | [`.kb/algorithms/vector-quantization/rabitq.md`](../../.kb/algorithms/vector-quantization/rabitq.md) |
| Binary Quantization | Third target | [`.kb/algorithms/vector-quantization/binary-quantization.md`](../../.kb/algorithms/vector-quantization/binary-quantization.md) |

## ADR Version History

| Version | File | Date | Status | Summary |
|---------|------|------|--------|---------|
| v1 | [adr.md](adr.md) | 2026-04-13 | active | QuantizationConfig on IndexDefinition + custom SPI |

## Tangents Captured During Deliberation

| Topic | Slug | Disposition | Resume When |
|-------|------|-------------|-------------|
| Query routing between quantized indices | vector-index-query-routing | deferred | Multiple quantization levels implemented |
| Automatic quantization selection | automatic-quantization-selection | deferred | SQ8/RaBitQ implemented with benchmark data |
