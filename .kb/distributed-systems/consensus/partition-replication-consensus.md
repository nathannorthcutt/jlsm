---
title: "Consensus Protocols for Partition Replication"
aliases: ["Raft", "Paxos", "EPaxos", "ISR", "leaderless replication"]
topic: "distributed-systems"
category: "consensus"
tags: ["raft", "paxos", "epaxos", "isr", "replication", "consensus", "leader-election", "quorum"]
complexity:
  time_build: "N/A"
  time_query: "varies by protocol"
  space: "O(replicas x data)"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "distributed-systems/cluster-membership/cluster-membership-protocols.md"
  - "distributed-systems/networking/multiplexed-transport-framing.md"
  - "distributed-systems/data-partitioning/partitioning-strategies.md"
decision_refs: ["partition-replication-protocol"]
sources:
  - url: "https://www.alibabacloud.com/blog/paxos-raft-epaxos-how-has-distributed-consensus-technology-evolved_597127"
    accessed: "2026-04-13"
  - url: "https://docs.confluent.io/kafka/design/replication.html"
    accessed: "2026-04-13"
  - url: "https://efficientcodeblog.wordpress.com/2017/12/26/read-repair-and-anti-entropy-two-ways-to-remedy-replication-lag-in-dynamo-style-datastores-leaderless-replication/"
    accessed: "2026-04-13"
  - url: "https://charap.co/reading-group-paxos-vs-raft-have-we-reached-consensus-on-distributed-consensus/"
    accessed: "2026-04-13"
---

## summary

Per-partition replication keeps copies of each partition's data on multiple nodes so that reads and writes survive node failures. The core choice is between leader-based consensus (Raft, Multi-Paxos), leaderless consensus (EPaxos), and leader-based non-consensus (ISR/Kafka-style). For a pure-Java LSM-tree system with 3-5 replicas per partition and SWIM-based failure detection, Raft is the recommended default: it is the simplest to implement correctly, maps directly onto the existing WAL, and integrates cleanly with SWIM for leader step-down. ISR is a viable alternative when linearizable reads are not required and operational simplicity is preferred over formal consensus.

## how-it-works

### raft

Leader-based log replication. One node per partition group is elected leader; it appends entries to its log and replicates them to followers. A write is committed once a majority (N/2 + 1) acknowledges. Terms (monotonic epoch numbers) prevent stale leaders. Only nodes with up-to-date logs can become leader, which guarantees the leader's log contains all committed entries.

### multi-paxos

Generalizes single-decree Paxos across a sequence of log slots. A stable leader skips the Prepare phase and proposes directly (Accept phase only), reducing steady-state to one round trip -- identical to Raft's happy path. The key difference: any node can become leader without having the latest log, but must then retrieve missing entries before proposing. This flexibility adds recovery complexity for marginal gain in small replica groups.

### epaxos

Leaderless: any replica can propose. The fast path (one round trip) commits when no concurrent proposals conflict on ordering. When conflicts exist, a second round (Accept phase) resolves dependency ordering. The fast-path quorum is F + ceil((F+1)/2), larger than Raft's majority. Best suited for geo-distributed deployments where avoiding a single leader reduces cross-region latency. Significantly harder to implement and verify.

### viewstamped-replication (vr)

Predates Raft with a similar leader-based design. Uses view numbers (analogous to Raft terms) and a view-change protocol for leader election. Historically important but offers no practical advantage over Raft for new implementations.

### isr (in-sync replicas)

Kafka-style: a leader tracks which followers are caught up (the ISR set). A write commits when all ISR members acknowledge -- not a fixed majority. Followers that fall behind are removed from the ISR. Any ISR member can become leader on failure. Advantage: with f+1 replicas, tolerates f failures (vs 2f+1 for majority quorum). Disadvantage: if all ISR members fail, either wait (unavailable) or promote a stale replica (data loss risk).

### leaderless (dynamo-style)

No leader; clients write to W of N replicas and read from R of N, where W + R > N. Consistency is probabilistic: read-repair fixes stale replicas on read; anti-entropy (background Merkle-tree comparison) fixes replicas that are rarely read. Provides eventual consistency only -- no linearizability, no total ordering of writes.

## algorithm-steps

Raft steady-state replication (per partition group):

1. Client sends write to leader. Leader appends entry to its WAL with current term and index.
2. Leader sends `AppendEntries(term, prevLogIndex, prevLogTerm, entries[], leaderCommit)` to all followers over the multiplexed transport.
3. Each follower checks `prevLogIndex`/`prevLogTerm` against its own log. If match: append entries, reply success. If mismatch: reply failure (leader decrements `nextIndex` and retries).
4. Once a majority of replicas (including leader) have the entry, leader advances `commitIndex` and applies the entry to the state machine (LSM MemTable).
5. Leader piggybacks `leaderCommit` on next `AppendEntries`; followers apply committed entries to their own MemTable.

Leader election (triggered by SWIM failure detection or election timeout):

1. Follower increments term, transitions to candidate, votes for itself.
2. Candidate sends `RequestVote(term, lastLogIndex, lastLogTerm)` to all peers.
3. Voter grants vote if: candidate's term >= voter's term, voter has not voted this term, and candidate's log is at least as up-to-date.
4. Candidate receiving majority of votes becomes leader; begins sending heartbeat `AppendEntries`.

## implementation-notes

### lsm-tree wal as raft log

The existing WAL is a natural fit for the Raft log. Each WAL record already has a sequence number; map this to the Raft log index. Add a term field to the WAL record header. On followers, WAL replay is identical to crash recovery -- the same code path handles both. Committed entries flush to the MemTable; uncommitted entries are truncated on leader change (same as crash-recovery truncation of incomplete records).

### swim integration

SWIM provides distributed failure detection with O(log N) dissemination. Integration points: (1) When SWIM marks a partition leader as suspect/failed, followers in that partition group start an election timer. (2) When a new leader is elected, it announces via SWIM protocol extension (piggyback on SWIM protocol messages). (3) SWIM membership changes trigger partition reassignment, which triggers Raft joint-consensus membership changes for affected groups.

### partition group management

Each partition has its own independent Raft group (3-5 nodes). A single node participates in multiple Raft groups (one per partition it hosts). All Raft messages for all groups multiplex over the single TCP connection per peer using Kafka-style length-prefixed framing with a partition-group-id header.

### linearizable reads

By default, leader serves reads without consensus (stale reads possible during partition). For linearizable reads: leader confirms it still holds leadership by sending a heartbeat round and waiting for majority ack before responding. Alternatively, use read-index optimization: leader records its commit index at read time and responds once that index is applied.

## complexity-analysis

| Protocol | Message complexity (steady-state) | Round trips (write) | Leader election | Failure tolerance |
|---|---|---|---|---|
| Raft | O(N) per write | 1 | 1 round (majority vote) | f failures with 2f+1 nodes |
| Multi-Paxos | O(N) per write | 1 (stable leader) | 1-2 rounds | f failures with 2f+1 nodes |
| EPaxos | O(N) per write | 1 (no conflict) / 2 (conflict) | none (leaderless) | f failures with 2f+1 nodes |
| ISR | O(ISR) per write | 1 | controller assigns | f failures with f+1 nodes |
| Leaderless | O(W) per write | 1 | none | depends on W, R, N |

For 3-replica partition groups: Raft needs 2/3 ack per write. ISR needs all 3 (or 2 if one drops from ISR). EPaxos fast path needs 2/3 but with higher message overhead.

## tradeoffs

### raft

- **Strengths**: simple to implement and reason about; well-tested in production (etcd, CockroachDB, TiKV); deterministic leader with total log ordering; maps directly to WAL-based replication.
- **Weaknesses**: leader bottleneck for write throughput; leader failure causes brief unavailability during election; cross-region latency when leader is remote.
- **Compared to alternatives**: simpler than Multi-Paxos (equivalent steady-state performance); much simpler than EPaxos (gives up leaderless flexibility).

### isr

- **Strengths**: fewer replicas needed (f+1 vs 2f+1); simple protocol; proven at massive scale in Kafka.
- **Weaknesses**: ISR shrinkage under load can reduce durability silently; no formal consensus -- relies on controller/coordinator correctness; unclean leader election risks data loss.
- **Compared to alternatives**: operationally simpler than Raft but weaker consistency guarantees.

### epaxos

- **Strengths**: no leader bottleneck; optimal for geo-distributed with clients near different replicas.
- **Weaknesses**: conflict resolution adds complexity and latency; very difficult to implement correctly (known bugs in original paper's TLA+ spec); dependency tracking overhead.
- **Compared to alternatives**: only justified for geo-distributed deployments where leader placement is problematic.

### leaderless

- **Strengths**: highest availability; no single point of failure; no election pauses.
- **Weaknesses**: eventual consistency only; conflict resolution (LWW, vector clocks) loses writes; read-repair and anti-entropy add background load; no total ordering.
- **Compared to alternatives**: inappropriate for systems requiring strong consistency or ordered writes.

## practical-usage

- **Per-partition Raft (recommended default)**: use when the system needs strong consistency, ordered writes, and the cluster is within a single region or nearby AZs. 3 replicas per partition, majority quorum. This is what CockroachDB, TiKV, and YugabyteDB do.
- **ISR**: use when the replica count must be minimized (2 replicas tolerating 1 failure) and the system has a reliable controller/coordinator. Suitable when eventual or session consistency is acceptable.
- **EPaxos**: consider only for multi-region deployments where write latency to a single leader is unacceptable and the engineering team can invest in formal verification.
- **Leaderless**: use for caching layers or secondary indices where eventual consistency is acceptable and availability is paramount. Not suitable for the primary replication path of an LSM-tree WAL.

## code-skeleton

```java
// Per-partition Raft state (simplified)
sealed interface RaftMessage permits AppendEntries, AppendEntriesResponse,
                                     RequestVote, RequestVoteResponse {
    int partitionId();
    long term();
}

record AppendEntries(int partitionId, long term, long prevLogIndex,
                     long prevLogTerm, List<LogEntry> entries,
                     long leaderCommit) implements RaftMessage {}

record LogEntry(long term, long index, MemorySegment key, MemorySegment value) {}

// Partition Raft group -- one instance per partition hosted on this node
final class PartitionRaftGroup {
    private final int partitionId;
    private volatile long currentTerm;
    private volatile int votedFor = -1;  // nodeId or -1
    private volatile Role role;          // FOLLOWER, CANDIDATE, LEADER
    private final WriteAheadLog wal;     // existing jlsm WAL as Raft log
    private long commitIndex;
    private long lastApplied;

    // Called when SWIM detects leader failure or election timeout fires
    void startElection() {
        currentTerm++;
        role = Role.CANDIDATE;
        votedFor = selfNodeId;
        // send RequestVote to all peers in this partition group
    }

    // Leader path: replicate a client write
    void propose(MemorySegment key, MemorySegment value) {
        var entry = new LogEntry(currentTerm, nextIndex(), key, value);
        wal.append(entry);  // local WAL append
        // send AppendEntries to followers; on majority ack, advance commitIndex
    }
}

enum Role { FOLLOWER, CANDIDATE, LEADER }
```

## sources

- [Paxos, Raft, EPaxos: How Has Distributed Consensus Technology Evolved? (Alibaba Cloud)](https://www.alibabacloud.com/blog/paxos-raft-epaxos-how-has-distributed-consensus-technology-evolved_597127)
- [Kafka Replication Design (Confluent)](https://docs.confluent.io/kafka/design/replication.html)
- [Read Repair and Anti-Entropy in Dynamo-style Datastores](https://efficientcodeblog.wordpress.com/2017/12/26/read-repair-and-anti-entropy-two-ways-to-remedy-replication-lag-in-dynamo-style-datastores-leaderless-replication/)
- [Paxos vs Raft: Have we reached consensus on distributed consensus? (Reading Group)](https://charap.co/reading-group-paxos-vs-raft-have-we-reached-consensus-on-distributed-consensus/)
- Ongaro, D. and Ousterhout, J. "In Search of an Understandable Consensus Algorithm" (USENIX ATC 2014)
- Moraru, I., Andersen, D., and Kaminsky, M. "There Is More Consensus in Egalitarian Parliaments" (SOSP 2013)
- DeCandia, G. et al. "Dynamo: Amazon's Highly Available Key-Value Store" (SOSP 2007)

## Updates 2026-04-13

### key papers (2023-2025)

| Paper | Venue | Key Contribution |
|-------|-------|------------------|
| FlexiRaft (Yadav et al.) | CIDR 2023 | Flexible quorums for Raft — decouples commit quorums from election quorums; deployed at Meta for MySQL replication |
| LeaseGuard (Davis et al.) | arXiv 2512.15659 (2025) | Correct Raft lease protocol — "the log is the lease"; linearizable local reads without quorum round-trip; TLA+ verified |
| Fast Raft | arXiv 2506.17793 (2025) | Dual-track commit with parallel voting; fast path at 3N/4 quorum, falls back to classic Raft on conflict |
| Taming Consensus in the Wild (Balakrishnan) | SIGOPS OSR 2024 | Survey of shared-log abstraction (Corfu → Delos); disaggregated consensus separating ordering from storage |
| Rabia (Pan et al.) | SOSP 2021 | Randomized leaderless SMR; simpler than EPaxos with comparable single-region latency |

### emerging approaches

**Flexible quorums.** FlexiRaft shows that Raft commit and election quorums need not both be majorities — they only need pairwise intersection. Dynamic mode (Meta) limits commit quorum to the leader's local region, eliminating cross-region WAN writes. Tradeoff: reduced availability if leader region fails. Applicable when write latency dominates and cross-region RPO is relaxed.

**Shared-log / disaggregated consensus.** Delos (Meta, OSDI 2020) and Corfu (VMware) separate the ordering layer (a shared log) from storage. Consensus runs once to assign sequence numbers; replicas consume the log independently. Delos can hot-swap its consensus engine ("virtual consensus") and scale throughput 10x by switching to a disaggregated Loglet. Relevant if jlsm ever needs pluggable replication backends.

**Read optimizations.** Three production-proven techniques reduce leader load for reads: (1) ReadIndex — leader confirms commit index, follower serves read once caught up (TiKV). (2) Lease reads — leader serves locally while lease is valid; LeaseGuard formalizes this with TLA+ proof and shows read latency drops from milliseconds to microseconds. (3) Follower reads — followers serve bounded-stale reads using MVCC timestamps (CockroachDB). For jlsm, lease reads are the simplest win — the leader already tracks heartbeat acks.

**Leader-bottleneck mitigations without full leaderless.** Fast Raft's parallel voting track and FlexiRaft's region-local commits both reduce leader pressure while keeping Raft's simplicity. These are more practical than EPaxos for single-region deployments. Batching + pipelining AppendEntries (as in MicroRaft, Aeron Cluster) further amortizes per-entry cost.

**Kernel-bypass I/O.** No peer-reviewed consensus paper yet isolates io_uring's impact on Raft latency, but production systems (Redpanda, ScyllaDB) report 2-5x latency reduction on the WAL fsync path by replacing synchronous write+fsync with io_uring submission queues. This directly benefits the Raft leader's critical path (log append → replicate → commit).

### sources (update)

- [FlexiRaft: Flexible Quorums with Raft (CIDR 2023)](https://www.cidrdb.org/cidr2023/papers/p83-yadav.pdf)
- [LeaseGuard: Raft Leases Done Right (arXiv 2025)](https://arxiv.org/html/2512.15659)
- [Fast Raft: Optimizations to the Raft Consensus Protocol (arXiv 2025)](https://arxiv.org/html/2506.17793v1)
- [Taming Consensus in the Wild via the Shared Log Abstraction (SIGOPS OSR 2024)](https://dl.acm.org/doi/10.1145/3689051.3689053)
- [FlexiRaft summary — Murat Demirbas blog](http://muratbuffalo.blogspot.com/2024/09/flexiraft-flexible-quorums-with-raft.html)
- [TiKV Lease Read](https://tikv.org/blog/lease-read/)
- [CockroachDB Follower Reads](https://www.cockroachlabs.com/blog/follower-reads-stale-data/)
