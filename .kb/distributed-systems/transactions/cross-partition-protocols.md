---
title: "Cross-Partition Transaction Protocols"
aliases: ["distributed transactions", "2PC", "Percolator", "Calvin", "OCC", "Aria", "SLOG", "Chardonnay", "CCaaS"]
topic: "distributed-systems"
category: "transactions"
tags: ["2pc", "percolator", "calvin", "occ", "mvcc", "snapshot-isolation", "distributed-transactions", "aria", "slog", "deterministic-database", "epoch-based", "chardonnay"]
complexity:
  time_build: "N/A"
  time_query: "varies by protocol"
  space: "varies by protocol"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "distributed-systems/data-partitioning/partitioning-strategies.md"
  - "distributed-systems/networking/multiplexed-transport-framing.md"
  - "distributed-systems/cluster-membership/cluster-membership-protocols.md"
decision_refs: ["cross-partition-transactions"]
sources:
  - url: "https://tikv.org/deep-dive/distributed-transaction/distributed-algorithms/"
    title: "TiKV Deep Dive — Distributed Algorithms (2PC vs Percolator)"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://blog.acolyer.org/2019/03/29/calvin-fast-distributed-transactions-for-partitioned-database-systems/"
    title: "Calvin: Fast Distributed Transactions — the morning paper"
    accessed: "2026-04-13"
    type: "blog"
  - url: "https://tikv.org/deep-dive/distributed-transaction/percolator/"
    title: "TiKV Deep Dive — Percolator"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://cs.yale.edu/homes/thomson/publications/calvin-sigmod12.pdf"
    title: "Calvin: Fast Distributed Transactions for Partitioned Database Systems (SIGMOD 2012)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "http://www.vldb.org/pvldb/vol13/p2047-lu.pdf"
    title: "Aria: A Fast and Practical Deterministic OLTP Database (PVLDB 2020)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "http://www.vldb.org/pvldb/vol12/p1747-ren.pdf"
    title: "SLOG: Serializable, Low-latency, Geo-replicated Transactions (PVLDB 2019)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://www.vldb.org/pvldb/vol18/p1376-lu.pdf"
    title: "HDCC: Hybrid Deterministic and Concurrent Control (PVLDB 2024)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://www.usenix.org/conference/osdi23/presentation/eldeeb"
    title: "Chardonnay: Fast and General Datacenter Transactions for On-Disk Databases (OSDI 2023)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://www.vldb.org/pvldb/vol18/p2761-zhou.pdf"
    title: "Concurrency Control as a Service (PVLDB 2025)"
    accessed: "2026-04-13"
    type: "paper"
---

## summary

Cross-partition transactions require coordination to maintain atomicity when a single logical write spans multiple range partitions. The four major protocol families — 2PC, Calvin (deterministic), Percolator (timestamp-based MVCC), and OCC — trade off latency, throughput, fault tolerance, and contention sensitivity in fundamentally different ways. For a peer-to-peer LSM-tree system with per-partition WALs and no external dependencies, Percolator-style timestamp-ordered 2PC is the strongest fit: it requires only single-key atomicity per partition (which LSM WAL already provides), avoids a dedicated coordinator node, and degrades gracefully under partial failure.

## how-it-works

**2PC (Two-Phase Commit):** A coordinator sends `PREPARE` to all participants; if all vote `YES`, it sends `COMMIT`. Blocking: if the coordinator crashes after `PREPARE` but before `COMMIT`, participants are stuck indefinitely. Requires a dedicated coordinator or leader election.

**Calvin (Deterministic):** A sequencing layer batches transactions into 10ms epochs, replicates the batch via consensus, then every node executes the same deterministic schedule under strict 2PL. No commit protocol needed — determinism guarantees identical outcomes. Requires read/write sets declared upfront; dependent reads need workarounds.

**Percolator (Timestamp-ordered MVCC):** Each transaction gets `start_ts` from a timestamp oracle. Prewrite phase locks keys and writes tentative values. Commit phase replaces the primary lock with a write record at `commit_ts`. Other transactions detect stale locks and roll forward or back based on primary lock state. Provides snapshot isolation.

**OCC (Optimistic Concurrency Control):** Transactions execute without acquiring locks, then validate at commit time that no conflicting writes occurred. Low overhead under low contention; abort rate grows quadratically with contention.

## algorithm-steps

### 2pc
1. Coordinator assigns transaction ID, sends `PREPARE(txn, writes)` to each partition.
2. Each participant writes to WAL, acquires locks, responds `VOTE_COMMIT` or `VOTE_ABORT`.
3. Coordinator logs decision, sends `COMMIT` or `ABORT` to all.
4. Participants apply or discard, release locks, acknowledge.

### calvin
1. Sequencer collects transaction requests during a 10ms epoch window.
2. Batch is replicated to all replicas (Paxos or async).
3. Each scheduler reconstructs global order by interleaving batches deterministically.
4. Scheduler acquires locks in global order (strict 2PL), executes transactions.
5. No commit message needed — deterministic execution guarantees identical results.

### percolator
1. Client obtains `start_ts` from timestamp oracle.
2. **Prewrite:** For each key, check for conflicting locks or writes after `start_ts`. Write value to data column at `start_ts`, place lock. One key is designated primary; others are secondary (pointing to primary).
3. If any prewrite fails (conflict), abort: remove all locks and data entries.
4. Client obtains `commit_ts` from timestamp oracle.
5. **Commit primary:** Remove primary lock, write commit record `(commit_ts -> start_ts)` to write column. Transaction is now committed.
6. **Commit secondaries:** Asynchronously remove secondary locks and write commit records. Crash-safe: other transactions roll forward stale secondary locks by checking primary.

### occ
1. Transaction reads from a consistent snapshot, buffers all writes locally.
2. At commit: acquire validation locks, check that no key in the read-set was modified since snapshot.
3. If validation passes, apply writes atomically; otherwise abort and retry.

## implementation-notes

### wal-integration
Per-partition LSM WAL already provides single-partition atomicity. For Percolator-style protocols, each partition stores lock/write metadata in dedicated column families (or key prefixes) within the same LSM engine. The existing WAL guarantees that a prewrite (lock + tentative value) is atomic within a single partition — no additional log is needed. Cross-partition atomicity comes from the protocol itself (primary lock as commit point), not from a global WAL.

### timestamp-oracle-without-central-node
A central timestamp oracle is a single point of failure. Alternatives for peer-to-peer topology: (a) Hybrid Logical Clocks (HLC) — combine physical clock with logical counter, provide causal ordering without central coordination, but only guarantee causal consistency (not strict serializability). (b) Partitioned oracle — assign timestamp ranges to nodes, each node issues timestamps from its range; requires range coordination on exhaustion. (c) Lamport-style logical clocks with per-partition sequence numbers — sufficient for snapshot isolation if combined with a conflict detection protocol.

### crash-recovery-and-lock-cleanup
When a transaction crashes mid-protocol, its locks become orphaned. Any subsequent transaction encountering a stale lock checks the primary lock: if committed (write record exists), roll forward the secondary; if absent (no write record, no lock), roll back. This makes recovery distributed and lock-free — no coordinator involvement needed.

### edge-cases
- **Lock contention hot keys:** Back-off with jitter when encountering live locks. Configurable TTL on locks to distinguish crashed vs slow transactions.
- **Clock skew (HLC):** Bounded by max physical clock drift. Transactions with `start_ts` too far in the future are rejected.
- **Partial prewrite failure:** Must clean up all successfully placed locks before reporting abort.

## complexity-analysis

| Protocol | Message rounds | Latency (best) | Throughput | Contention cost | Fault tolerance |
|----------|---------------|-----------------|------------|-----------------|-----------------|
| 2PC | 2 (prepare + commit) | 2 RTT | High | Lock-based, moderate | Blocking on coordinator failure |
| Calvin | 1 (batch replication) | 10ms epoch + exec | Very high (500K TPC-C/100 nodes) | Degrades with cross-partition txns | Deterministic replay from replica |
| Percolator | 2 (prewrite + commit) | 2 RTT + oracle | High | Abort on conflict | Non-blocking (primary lock is commit point) |
| OCC | 1-2 (validate + commit) | 1-2 RTT | High (low contention) | Quadratic abort rate | Depends on substrate |

## tradeoffs

### strengths
- **2PC:** Simple, well-understood, works with any storage engine.
- **Calvin:** Highest throughput for cross-partition transactions; no per-transaction coordination.
- **Percolator:** No dedicated coordinator, non-blocking, graceful crash recovery, maps naturally to LSM column families.
- **OCC:** Zero lock overhead in read path; ideal for read-heavy, low-contention workloads.

### weaknesses
- **2PC:** Blocking on coordinator failure; requires leader election or dedicated coordinator — poor fit for peer-to-peer.
- **Calvin:** Requires upfront read/write sets (dependent reads need reconnaissance queries); slow nodes bottleneck the cluster; 10ms epoch floor on latency; sequencer layer adds architectural complexity.
- **Percolator:** Requires timestamp oracle (or HLC substitute with weaker guarantees); reads must check lock column (extra read per key); write amplification from lock/write column metadata.
- **OCC:** Abort storms under contention; wasted work on retry; not suitable for write-heavy hot partitions.

### compared-to-alternatives
For jlsm's constraints (pure Java, peer-to-peer, per-partition WAL, up to 1000 nodes):
- Calvin is the throughput winner but demands a sequencer layer and upfront read/write sets — high implementation complexity and architectural cost.
- 2PC is simplest but the blocking problem is unacceptable without a consensus-backed coordinator, which conflicts with the peer-to-peer requirement.
- OCC works well for read-dominated workloads but is not a general-purpose solution.
- **Percolator is the best fit:** it layers naturally on per-partition LSM storage, needs only single-key atomicity (already provided by WAL), and its primary-lock-as-commit-point design eliminates the coordinator. The timestamp oracle can be approximated with HLC for snapshot isolation.

## practical-usage

### when-to-use
- **Percolator:** Default choice for cross-partition writes in a range-partitioned LSM system. Use when transactions touch 2-10 partitions and contention is moderate.
- **Calvin:** When throughput is the dominant concern and read/write sets are known at transaction start (e.g., batch ETL, pre-planned schema operations).
- **OCC:** Read-heavy workloads with rare cross-partition writes (e.g., secondary index updates that rarely conflict).

### when-not-to-use
- **Percolator:** Extremely high contention on a single key (use partition-local fast path instead).
- **Calvin:** When transaction read/write sets depend on data read during the transaction (interactive queries).
- **2PC:** Peer-to-peer topology without consensus-backed coordinator election.

## code-skeleton

Percolator-style transaction manager (Java pseudocode, simplified):

```java
// Transaction lifecycle — maps to per-partition LSM operations
sealed interface TxnResult permits TxnResult.Committed, TxnResult.Aborted {
    record Committed(long commitTs) implements TxnResult {}
    record Aborted(String reason) implements TxnResult {}
}

TxnResult executeTransaction(List<Write> writes, TimestampOracle oracle) {
    long startTs = oracle.next();
    var primary = writes.getFirst();   // first key is primary
    var secondaries = writes.subList(1, writes.size());

    // Phase 1: Prewrite
    for (var w : writes) {
        var partition = router.partitionFor(w.key());
        boolean ok = partition.prewrite(w.key(), w.value(), startTs,
                primary.key(), w == primary);
        if (!ok) {
            rollbackAll(writes, startTs);
            return new TxnResult.Aborted("conflict at " + w.key());
        }
    }

    // Phase 2: Commit primary, then secondaries
    long commitTs = oracle.next();
    boolean committed = router.partitionFor(primary.key())
            .commitPrimary(primary.key(), startTs, commitTs);
    if (!committed) {
        rollbackAll(writes, startTs);
        return new TxnResult.Aborted("primary commit failed");
    }
    // Secondaries are best-effort; stale locks cleaned up by readers
    for (var w : secondaries) {
        router.partitionFor(w.key())
              .commitSecondary(w.key(), startTs, commitTs);
    }
    return new TxnResult.Committed(commitTs);
}
```

Each `partition.prewrite()` and `partition.commitPrimary()` are single-partition operations backed by the existing LSM WAL — no global log required.

## sources

1. TiKV Deep Dive — Distributed Algorithms. https://tikv.org/deep-dive/distributed-transaction/distributed-algorithms/
2. TiKV Deep Dive — Percolator. https://tikv.org/deep-dive/distributed-transaction/percolator/
3. Thomson et al. "Calvin: Fast Distributed Transactions for Partitioned Database Systems." SIGMOD 2012. https://cs.yale.edu/homes/thomson/publications/calvin-sigmod12.pdf
4. Adrian Colyer. "Calvin: Fast Distributed Transactions." the morning paper, 2019. https://blog.acolyer.org/2019/03/29/calvin-fast-distributed-transactions-for-partitioned-database-systems/

## Updates 2026-04-13

### What changed
Added cutting-edge research papers covering frontier transaction protocols
not yet in production systems. Focus: approaches that eliminate coordination
overhead, remove upfront read/write set requirements, or exploit determinism
in novel ways.

### frontier-research

#### key-papers
| Paper | Venue | Year | Key Contribution |
|-------|-------|------|------------------|
| Aria: A Fast and Practical Deterministic OLTP Database | PVLDB 13(12) | 2020 | Deterministic execution without upfront read/write sets — batch-execute against snapshot, then deterministic parallel commit |
| SLOG: Serializable, Low-latency, Geo-replicated Transactions | PVLDB 12(11) | 2019 | Home-region optimization for Calvin — single-home txns commit with intra-region coordination only |
| HDCC: Hybrid Deterministic and Concurrent Control | PVLDB 18 | 2024 | Dynamically switches between deterministic ordering and concurrent execution per-transaction |
| Chardonnay: Fast and General Datacenter Transactions for On-Disk Databases | OSDI '23 | 2023 | Dry-run execution to discover read/write sets, pin to memory, then fast 2PL+2PC (~150us on Azure) |
| Concurrency Control as a Service (CCaaS) | PVLDB 18(9) | 2025 | Decouples CC from storage — epoch-based conflict resolution with read/write set packing at epoch boundaries |

#### emerging-approaches

**Aria (batch-deterministic OCC):** Executes a batch of transactions against the
same snapshot, then deterministically selects which commit — no upfront read/write
sets needed. Up to 2x throughput over Calvin on TPC-C. Not in production because
the batch window introduces latency and abort-retry under contention is expensive.
*jlsm relevance:* the snapshot-then-validate pattern maps well to LSM immutable
memtable snapshots — a flush boundary is a natural batch boundary.

**SLOG (home-region deterministic):** Extends Calvin for geo-distribution by
assigning each data granule a home region. Single-home transactions avoid
cross-region coordination entirely. Multi-home transactions fall back to global
ordering. Not widely adopted due to complexity of home-region metadata management.
*jlsm relevance:* the single-home fast path is analogous to partition-local
transactions in a range-partitioned LSM — the optimization applies directly.

**HDCC (hybrid deterministic/concurrent):** Identifies per-transaction whether
deterministic ordering is needed or concurrent execution is safe, avoiding the
all-or-nothing tradeoff of pure deterministic systems. Outperforms both Calvin
and Aria on mixed workloads.
*jlsm relevance:* a hybrid approach could let jlsm use partition-local OCC for
non-conflicting transactions and deterministic ordering only when needed.

**Chardonnay (dry-run prefetch + fast 2PC):** Runs transactions in "dry run"
mode to discover read/write sets without holding locks, prefetches data from
disk to memory, then re-executes with 2PL+2PC. Throughput under high contention
drops only 15% (vs 85% in traditional architectures). Uses an epoch service for
consistent snapshot reads.
*jlsm relevance:* the dry-run pattern could inform a read-phase that scans LSM
levels without acquiring locks, then a write-phase that only touches the WAL.

**CCaaS (epoch-based decoupled CC):** Separates concurrency control into a
standalone service that collects read/write sets at epoch boundaries and resolves
conflicts centrally. Storage engines become pluggable underneath.
*jlsm relevance:* if jlsm exposes per-partition read/write sets at epoch
boundaries (e.g., memtable flush intervals), an external CC service could
coordinate cross-partition transactions without modifying the storage engine.

### New sources
5. [Aria: A Fast and Practical Deterministic OLTP Database](http://www.vldb.org/pvldb/vol13/p2047-lu.pdf) — PVLDB 2020; deterministic OCC without upfront read/write sets
6. [SLOG: Serializable, Low-latency, Geo-replicated Transactions](http://www.vldb.org/pvldb/vol12/p1747-ren.pdf) — PVLDB 2019; home-region optimization for deterministic databases
7. [SLOG — the morning paper](https://blog.acolyer.org/2019/09/04/slog/) — accessible summary of SLOG's design
8. [HDCC: Hybrid Deterministic and Concurrent Control](https://www.vldb.org/pvldb/vol18/p1376-lu.pdf) — PVLDB 2024; hybrid deterministic/concurrent protocol
9. [Chardonnay: Fast and General Datacenter Transactions](https://www.usenix.org/conference/osdi23/presentation/eldeeb) — OSDI 2023; dry-run prefetch + fast 2PC for on-disk databases
10. [Concurrency Control as a Service](https://www.vldb.org/pvldb/vol18/p2761-zhou.pdf) — PVLDB 2025; epoch-based decoupled concurrency control
