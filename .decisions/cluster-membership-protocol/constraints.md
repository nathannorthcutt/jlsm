---
problem: "What protocol should engine nodes use to form a cluster, track membership, and detect split-brain scenarios?"
slug: "cluster-membership-protocol"
captured: "2026-03-20"
status: "draft"
---

# Constraint Profile — cluster-membership-protocol

## Problem Statement
Choose a membership protocol for jlsm-engine clustering. Peer nodes (no leaders) discover each other via a pluggable SPI, form a cluster, maintain consistent membership views, and detect network partitions (split-brain). Minority partitions must stop serving affected tables. Initial testing is in-JVM with a transport abstraction; future work adds NIO networking.

## Constraints

### Scale
Clusters range from a single node to hundreds. No artificial upper bound — practical limits are hardware-related (IP space, network bandwidth). QPS, table count, and throughput should grow near-linearly with node count following single-engine expectations.

### Resources
Not a constraining factor for protocol design. Practical infrastructure limits (IP exhaustion, network saturation) may bound total cluster size, but the protocol itself should not introduce resource bottlenecks.

### Complexity Budget
High. The team is expert-level in distributed systems. Complex protocols are acceptable if they deliver the required correctness and scalability properties.

### Accuracy / Correctness
High. Membership views must converge to a consistent state across all live nodes. Small transient inconsistencies that self-recover are acceptable if infrequent. Sustained outages caused by rebalancing storms, false split-brain detection, or membership protocol failure are unacceptable.

### Operational Requirements
- Fast convergence: membership changes should propagate quickly (sub-second in-JVM, bounded time over network)
- Split-brain detection must be reliable — false positives (unnecessary table unavailability) and false negatives (serving stale/split data) are both costly
- Grace period for node departure before rebalancing (configurable)
- Protocol must not block query serving on unaffected tables during membership changes

### Fit
Java 25 (Amazon Corretto). Pure library — no external runtime dependencies. Transport is abstracted (in-JVM initially, NIO later). Discovery is SPI-based. Protocol implementation must work over both transport layers without modification.

## Key Constraints (most narrowing)
1. **Correctness over availability** — split-brain must be detected reliably; minority partitions must stop serving rather than risk inconsistency
2. **Scale to hundreds of nodes** — protocol overhead must not grow super-linearly with cluster size
3. **Transport-agnostic** — must work identically over in-JVM direct calls and future network transport

## Unknown / Not Specified
- Exact latency targets for membership convergence over network (only "bounded time" specified)
- Maximum acceptable false-positive rate for failure detection
- Whether membership protocol should handle asymmetric network partitions (A can reach B but not vice versa)
