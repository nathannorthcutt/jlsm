# Evaluation — Index Access Pattern Leakage

## Candidates

### A. Low-Cost Mitigation Bundle (Per-Field Keys + Padding + Documentation)
Implement three low-cost mitigations: (1) per-field HKDF keys (already decided), (2) power-of-2 response padding, (3) leakage profile documentation per EncryptionSpec. Defer ORAM and differential privacy.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 9 | Per-field keys: O(1) at construction. Padding: at most 2x bandwidth | [`.kb/algorithms/encryption/index-access-pattern-leakage.md#response-padding`](../../.kb/algorithms/encryption/index-access-pattern-leakage.md) |
| Resources | 9 | HKDF: one HMAC per field. Padding: dummy entries in result iterator | — |
| Complexity | 9 | Per-field keys already decided. Padding: ~50 lines. Documentation: per-spec annotations | [`.kb/algorithms/encryption/index-access-pattern-leakage.md#practical-recommendations-for-jlsm`](../../.kb/algorithms/encryption/index-access-pattern-leakage.md) |
| Accuracy | 7 | Reduces but does not eliminate leakage. DET still leaks frequency; OPE still leaks order. Documented tradeoff | — |
| Operational | 9 | Per-field keys transparent. Padding opt-in. Documentation always available | — |
| Fit | 9 | Per-field keys compose with per-field-key-binding ADR. Padding composes with query iterators | [`.decisions/per-field-key-binding/adr.md`](../per-field-key-binding/adr.md) |
| **Total** | **52/60** | | |

### B. Full ORAM Integration
Implement Path ORAM as the default access pattern hiding mechanism for all encrypted index operations.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 2 | 10-100x throughput reduction; each logical read = ~20 physical reads | [`.kb/algorithms/encryption/index-access-pattern-leakage.md#oram`](../../.kb/algorithms/encryption/index-access-pattern-leakage.md) |
| Resources | 2 | O(N) server-side storage for ORAM tree + stash | — |
| Complexity | 3 | ~1000+ lines of Path ORAM; complex stash management | — |
| Accuracy | 9 | Eliminates access pattern leakage completely | — |
| Operational | 3 | Massive performance regression for all encrypted queries | — |
| Fit | 4 | Requires fundamental restructuring of SSTable reads | — |
| **Total** | **23/60** | | |

### C. Differential Privacy Query Noise
Add noise to query results (dummy results, query obfuscation) with epsilon-calibrated differential privacy.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 6 | Query latency increases by noise generation + dummy queries | — |
| Resources | 6 | Dummy queries consume I/O and cache capacity | — |
| Complexity | 4 | Epsilon calibration is domain-specific; wrong epsilon either leaks or destroys utility | [`.kb/algorithms/encryption/index-access-pattern-leakage.md#differential-privacy-and-noise`](../../.kb/algorithms/encryption/index-access-pattern-leakage.md) |
| Accuracy | 6 | Tunable but requires calibration per workload | — |
| Operational | 4 | Exposes epsilon parameter to callers — complex API | — |
| Fit | 5 | Changes query API semantics (results may include dummy entries) | — |
| **Total** | **31/60** | | |

## Recommendation
**Candidate A — Low-Cost Mitigation Bundle**. Per-field HKDF keys, power-of-2 response padding, and leakage documentation. These are the KB's top 3 recommendations (#1, #2, #3 in the practical recommendations table). ORAM is deferred as a potential opt-in wrapper for high-security use cases.

## Falsification Check
- **Is this "shipping a leaky feature"?** No. The project convention says "defer half-baked/leaky ones." This decision is not shipping a leaky encryption scheme — it is documenting the inherent leakage properties of DET/OPE (which are already confirmed in the parent ADR) and adding low-cost mitigations. The parent ADR already accepted these leakage properties; this decision makes them explicit and provides mitigations.
- **Will power-of-2 padding actually help?** It reduces volume leakage from exact count to order-of-magnitude. Poddar et al. showed volume attacks require exact counts — log-scale bucketing significantly degrades attack accuracy.
- **Should we defer entirely?** No. Per-field keys and leakage documentation have zero cost and high value. Deferring them means shipping encryption without basic hygiene.
