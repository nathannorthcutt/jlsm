## 2026-03-30 — out-of-scope-promoted

**Agent:** Curation Agent
**Event:** out-of-scope-promoted
**Parent ADR:** transport-abstraction-design
**Summary:** Promoted from out-of-scope item in parent ADR to tracked deferred decision.

---

## 2026-04-13 — created

**Agent:** Architect Agent
**Event:** created
**Summary:** Full evaluation started. Constraint profile captured. No direct KB coverage — proceeding with general knowledge. Coverage gap noted in evaluation.

**Files written/updated:**
- `constraints.md` — full constraint profile

---

## 2026-04-13 — research-commissioned

**Agent:** Architect Agent
**Event:** research-commissioned
**Summary:** Research commissioned after two deliberation rounds. Initial recommendation (VThread+Pool) revised to Connection-per-Peer after falsification, then revised again to Single-Connection Multiplexing after user challenge exposed that framing is baseline for all approaches. Research needed on message framing patterns for multiplexed transports to provide KB-backed evidence for the framing protocol design.

**Deliberation rounds:**
1. Falsification weakened VThread+Pool → Connection-per-Peer
2. User challenged: framing is baseline, channel exclusivity blocks pings, deferred decisions all need framing layer → Multiplexing
3. User requested research before finalizing — open questions should get research, not assumptions

**Subjects requested:**
- Message framing for multiplexed transports (framing formats, correlation dispatch, write serialization, connection lifecycle)

**Files written/updated:**
- `research-brief.md` — research commission
- `evaluation.md` — revised twice with deliberation results

---

## 2026-04-13 — research-received

**Agent:** Architect Agent
**Event:** research-received
**Summary:** Research completed. KB entry written at `.kb/distributed-systems/networking/multiplexed-transport-framing.md`. Covers Kafka-style 4-byte length-prefix framing, int32 correlation IDs, ReentrantLock write serialization, lazy connection establishment, and passive health checking via membership protocol heartbeats. High confidence — backed by Cassandra CQL v5 spec and Kafka protocol guide.

**KB files read:**
- [`.kb/distributed-systems/networking/multiplexed-transport-framing.md`](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md) (new)

---

## 2026-04-13 — decision-confirmed

**Agent:** Architect Agent
**Event:** decision-confirmed
**Summary:** Single-Connection Multiplexing confirmed after 3 rounds of deliberation with falsification and KB research.

### Deliberation Summary

**Rounds of deliberation:** 3
**Recommendation presented:** VThread-per-Message + Shared Pool (round 1), Connection-per-Peer (round 2), Single-Connection Multiplexing (round 3)
**Final decision:** Single-Connection Multiplexing

**Topics raised during deliberation:**
- Round 1 — Falsification: weakened 5 of 6 scores on VThread+Pool. Strongest counter-argument: burst-to-same-peer is rare for LSM-tree transport. Revised recommendation to Connection-per-Peer.
- Round 2 — User challenge: framing is baseline for ALL approaches, not unique to multiplexing. Channel exclusivity in Connection-per-Peer blocks membership pings during query RTT. Deferred decisions (message-serialization-format, scatter-backpressure, transport-traffic-priority) all build on the framing layer. Revised recommendation to Single-Connection Multiplexing.
- Round 3 — User requested KB research before finalizing. Research commissioned and completed. KB entry confirmed the design with production evidence from Cassandra CQL v5 and Kafka. Fit score upgraded to 5. Confidence upgraded to High.

**Constraints updated during deliberation:**
- Scale: upgraded from "tens-to-hundreds" to "up to 1000 nodes" per F04 R35 (constraint falsification)
- Complexity weight: 2→1 (user confirmed expert team, complexity not a narrowing constraint)
- Accuracy: added atomic lifecycle transitions (F04 R87) and silent send failure absorption (F04 R28)

**Assumptions explicitly confirmed by user:**
- Expert team available — complexity is not a narrowing constraint
- Framing is baseline work that should be done now, not deferred
- Research should always be done for open questions — KB is a long-term investment

**Override:** None
**Confirmation:** User confirmed with: "yes"

**Files written after confirmation:**
- `adr.md` — decision record v1
- `constraints.md` — updated during falsification (scale, accuracy additions)
- `evaluation.md` — revised 3 times (falsification, deliberation round 2, KB research)

**KB files read during evaluation:**
- [`.kb/distributed-systems/networking/multiplexed-transport-framing.md`](../../.kb/distributed-systems/networking/multiplexed-transport-framing.md)
- [`.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md`](../../.kb/distributed-systems/cluster-membership/cluster-membership-protocols.md)

---
