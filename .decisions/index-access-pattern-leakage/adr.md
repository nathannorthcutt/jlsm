---
problem: "index-access-pattern-leakage"
date: "2026-04-14"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-table/src/main/java/jlsm/table/EncryptionSpec.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/QueryExecutor.java"
---

# ADR — Index Access Pattern Leakage

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Index Access Pattern Leakage and Mitigations | Leakage taxonomy, attack classes, practical recommendations | [`.kb/algorithms/encryption/index-access-pattern-leakage.md`](../../.kb/algorithms/encryption/index-access-pattern-leakage.md) |
| Per-Field Key Binding (ADR) | HKDF derivation already decided | [`.decisions/per-field-key-binding/adr.md`](../per-field-key-binding/adr.md) |

---

## Files Constrained by This Decision

- `EncryptionSpec.java` — gains `leakageProfile()` method returning structured leakage description
- `QueryExecutor.java` — gains optional power-of-2 response padding for encrypted field queries

## Problem
Encrypted indices (DET, OPE, SSE) leak information through observable access patterns — frequency, search pattern, volume, and ordering. The parent ADR (`encrypted-index-strategy`) acknowledged T1/T2 leakage as a known tradeoff. How should jlsm mitigate leakage and communicate residual risk to callers?

## Constraints That Drove This Decision
- **No ORAM**: 10-100x overhead is unacceptable as a default mode
- **Documentation is mandatory**: callers must understand leakage before choosing an encryption scheme
- **Low-cost mitigations first**: per-field keys and response padding add negligible overhead

## Decision
**Chosen approach: Low-Cost Mitigation Bundle** — per-field HKDF keys + power-of-2 response padding + leakage profile documentation

Three complementary mitigations:
1. **Per-field HKDF keys** (already decided in `per-field-key-binding`) — prevents cross-field frequency correlation
2. **Power-of-2 response padding** — pads query result counts to the next power of 2 to hide exact volume
3. **Leakage profile documentation** — each EncryptionSpec variant exposes a structured leakage profile describing what is leaked at rest and at query time

## Rationale

### Why these three mitigations
- **Per-field keys**: highest value, lowest cost. Eliminates the simplest attack vector (cross-field correlation) with a single HMAC call per field at schema construction. Already decided.
- **Response padding**: Poddar et al. showed volume attacks require exact result counts. Power-of-2 bucketing reduces leakage from exact count to order-of-magnitude at most 2x bandwidth overhead.
- **Leakage documentation**: zero implementation cost, high value. Callers can make informed decisions about which EncryptionSpec to use based on their threat model. This aligns with the project convention: "ship functional encryption features" — but make the tradeoffs explicit.

### Why not ORAM
10-100x throughput reduction for access pattern hiding. No production database uses ORAM as a default mode. Could be offered as an opt-in wrapper for niche high-security use cases in the future.

### Why not Differential Privacy
Requires epsilon calibration per workload — wrong epsilon either leaks or destroys utility. Changes query API semantics. Too complex for v1.

## Implementation Guidance

### Leakage profile on EncryptionSpec

```java
public sealed interface EncryptionSpec {
    // Existing capability methods...

    default LeakageProfile leakageProfile() {
        return LeakageProfile.NONE;
    }

    record None() implements EncryptionSpec {
        @Override public LeakageProfile leakageProfile() {
            return LeakageProfile.NONE; // no encryption, no leakage concept
        }
    }
    record Deterministic() implements EncryptionSpec {
        @Override public LeakageProfile leakageProfile() {
            return new LeakageProfile(
                /* frequency */ true,
                /* searchPattern */ true,
                /* accessPattern */ true,
                /* volume */ true,
                /* order */ false,
                /* level */ LeakageLevel.L4,
                /* description */ "Identical plaintexts produce identical ciphertexts. " +
                    "Leaks frequency distribution and cross-document equality."
            );
        }
    }
    // ... similar for OrderPreserving (adds order=true), DistancePreserving, Opaque
}

public record LeakageProfile(
    boolean frequency, boolean searchPattern, boolean accessPattern,
    boolean volume, boolean order, LeakageLevel level, String description
) {
    public static final LeakageProfile NONE = new LeakageProfile(
        false, false, false, false, false, LeakageLevel.NONE, "No encryption applied."
    );
}

public enum LeakageLevel { NONE, L1, L2, L3, L4 }
```

### Power-of-2 response padding

```java
// In QueryExecutor, opt-in via query options:
if (queryOptions.padResults()) {
    int actual = results.size();
    int padded = Integer.highestOneBit(actual - 1) << 1; // next power of 2
    while (results.size() < padded) {
        results.add(PADDING_SENTINEL); // stripped by caller
    }
}
```

Padding sentinels are distinguishable by callers (e.g., a `JlsmDocument.isPadding()` method or a special marker value). The caller strips padding before processing results.

### Fixed bloom filter sizing (optional)

Bloom filter size reveals approximate SSTable key count. A fixed-size mode (configurable) uses a constant bit array regardless of actual cardinality. Low priority — metadata often includes key count anyway.

## What This Decision Does NOT Solve
- ORAM-based access pattern hiding (deferred — opt-in wrapper for high-security use cases)
- Differential privacy query noise (deferred — requires epsilon calibration)
- Forward/backward private SSE (handled by T3 SSE in encrypted-index-strategy)

## Conditions for Revision
This ADR should be re-evaluated if:
- A compliance requirement mandates ORAM-level access pattern hiding
- Adaptive ORAM (V-ORAM style) becomes practical at < 5x overhead
- Volume-hiding encrypted multimaps achieve production readiness

---
*Confirmed by: architect agent (WD-09 batch, pre-accepted) | Date: 2026-04-14*
*Full scoring: [evaluation.md](evaluation.md)*
