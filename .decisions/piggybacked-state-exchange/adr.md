---
problem: "piggybacked-state-exchange"
date: "2026-04-13"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/"
---

# ADR — Piggybacked State Exchange

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Cluster Membership Protocols | Piggybacked state efficiency section — delta-CRDTs, digest exchange, propagation-count priority | [`.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md) |

## Related ADRs
| ADR | Relationship |
|-----|-------------|
| [cluster-membership-protocol](../cluster-membership-protocol/adr.md) | Parent — heartbeat messages this extends |
| [slow-node-detection](../slow-node-detection/adr.md) | Consumer — peer comparison scoring reads piggybacked metadata |

---

## Files Constrained by This Decision
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/` — heartbeat message format, metadata section

## Problem
The Rapid membership protocol's heartbeat messages carry only protocol data. Node metadata
— performance metrics for slow-node detection, health scores — needs to travel across
the cluster without a separate channel. How should metadata be encoded and piggybacked
on existing heartbeats?

## Constraints That Drove This Decision
- **Heartbeat size budget**: metadata must not inflate heartbeats enough to affect phi accrual timing
- **O(1) parsing**: fixed-offset access, no per-field iteration at heartbeat frequency
- **Backward compatible**: nodes that don't understand metadata must still process heartbeats

## Decision
**Chosen approach: Fixed-Field Heartbeat Metadata with Version Byte**

A fixed-format metadata section appended to every heartbeat message. The section starts
with a 1-byte version identifier, followed by known fields at fixed offsets. Nodes that
don't recognize the version skip the metadata section entirely. The format is:

```
[1 byte: version]
[4 bytes: p99_query_ms (IEEE 754 float32)]
[4 bytes: p99_replication_ms (IEEE 754 float32)]
[1 byte: local_health_multiplier (unsigned)]
```

Total: 10 bytes per heartbeat. On a ~100-byte heartbeat, this is 10% overhead — well
within UDP MTU and negligible for phi accrual timing. Each heartbeat carries the sender's
latest local values. Convergence happens naturally within K heartbeat rounds (K = number
of observers in the expander graph, typically 3-5).

### Encoding

- **p99 values**: IEEE 754 float32 in big-endian (network order). Represents milliseconds.
  Range: microsecond precision up to ~16M ms (~4.5 hours). Sufficient for any practical
  query or replication latency.
- **LHM**: unsigned byte 0-255. Reserved for Local Health Multiplier (future use in
  slow-node-detection). Initially set to 0 (healthy).
- **Version byte**: enables adding fields in future versions. Version 1 = 10 bytes total.
  Version 2+ appends additional fields after the v1 layout. Receivers that see an unknown
  version read the bytes they understand (v1 fields at known offsets) and skip the rest.

### Parsing

```java
// O(1) fixed-offset access — no iteration, no key lookup
float p99Query = segment.get(ValueLayout.JAVA_FLOAT_UNALIGNED, offset + 1);
float p99Replication = segment.get(ValueLayout.JAVA_FLOAT_UNALIGNED, offset + 5);
byte lhm = segment.get(ValueLayout.JAVA_BYTE, offset + 9);
```

## Rationale

### Why Fixed-Field Heartbeat Metadata
- **O(1) parsing**: fixed offsets, no key lookup, no framing — fastest possible access at heartbeat frequency ([KB: `#piggybacked-state-efficiency`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md))
- **Minimal overhead**: 10 bytes on ~100-byte heartbeats = 10%. No measurable impact on phi accrual
- **Version byte**: clean upgrade path — new fields appended in future versions without breaking backward compatibility
- **No protocol changes**: metadata section is an optional extension of the heartbeat payload

### Why not Extensible Key-Value Metadata
- **Parsing overhead**: O(n) key lookup per heartbeat for n metadata fields — unnecessary for 3 known, always-present fields
- **Framing complexity**: requires length-prefix framing within the heartbeat payload for variable-size entries

### Why not Delta-State CRDT Metadata
- **Massive overkill**: performance metrics are last-writer-wins values (each node reports its own p99). CRDTs solve conflict-free merge for concurrent writes — a problem that doesn't exist here.

### Why not Separate Metadata Channel
- **Violates fit constraint**: requires new message type registration on ClusterTransport, adding transport complexity for data that fits trivially on existing heartbeats.

## Implementation Guidance

Heartbeat message format extension:
```
[existing heartbeat payload]
[metadata section — present only if heartbeat has remaining bytes]
  [1B version] [4B p99_query_ms] [4B p99_replication_ms] [1B lhm]
```

Receiver logic:
```java
if (heartbeatLength > PROTOCOL_PAYLOAD_SIZE) {
    byte version = segment.get(ValueLayout.JAVA_BYTE, PROTOCOL_PAYLOAD_SIZE);
    if (version >= 1) {
        float p99Query = segment.get(JAVA_FLOAT_UNALIGNED, PROTOCOL_PAYLOAD_SIZE + 1);
        float p99Repl = segment.get(JAVA_FLOAT_UNALIGNED, PROTOCOL_PAYLOAD_SIZE + 5);
        byte lhm = segment.get(JAVA_BYTE, PROTOCOL_PAYLOAD_SIZE + 9);
        updatePeerMetrics(sender, p99Query, p99Repl, lhm);
    }
}
```

Edge cases:
- **Metadata not present**: heartbeat length == PROTOCOL_PAYLOAD_SIZE → no metadata section. Receiver uses stale cached values or defaults.
- **Unknown version**: receiver reads v1 fields (known offsets), ignores additional bytes.
- **NaN/Infinity p99 values**: receiver should treat as "no data available" and not use for peer comparison scoring.

## What This Decision Does NOT Solve
- Large metadata distribution (partition ownership maps, capacity vectors — need digest exchange or separate channel)
- Dynamic field discovery (nodes cannot advertise custom metadata keys at runtime)

## Conditions for Revision
This ADR should be re-evaluated if:
- Metadata field count grows beyond ~8 fields (version byte + fixed offsets becomes unwieldy; consider extensible format)
- Metadata values need merge semantics (conflicting values from different observers — would need CRDTs)
- A consumer needs metadata from nodes that are not direct heartbeat peers (requires gossip-based dissemination, not just direct piggybacking)

---
*Confirmed by: user deliberation | Date: 2026-04-13*
*Full scoring: [evaluation.md](evaluation.md)*
