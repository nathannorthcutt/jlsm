---
problem: "continuous-rediscovery"
evaluated: "2026-04-13"
candidates:
  - path: ".kb/distributed-systems/cluster-membership/service-discovery-patterns.md"
    name: "Periodic Rediscovery Loop"
    section: "#continuous-rediscovery"
  - path: ".kb/distributed-systems/cluster-membership/service-discovery-patterns.md"
    name: "Event-Driven Rediscovery"
    section: "#pluggable-discovery-spi"
  - path: ".kb/distributed-systems/cluster-membership/service-discovery-patterns.md"
    name: "Reactive Watch"
    section: "#production-system-survey"
  - path: ".kb/distributed-systems/cluster-membership/service-discovery-patterns.md"
    name: "No Rediscovery (Gossip Only)"
    section: "#bootstrap-strategies"
constraint_weights:
  scale: 2
  resources: 2
  complexity: 1
  accuracy: 3
  operational: 3
  fit: 3
---

# Evaluation — continuous-rediscovery

## References
- Constraints: [constraints.md](constraints.md)
- KB source: [`.kb/distributed-systems/cluster-membership/service-discovery-patterns.md`](../../.kb/distributed-systems/cluster-membership/service-discovery-patterns.md)

## Constraint Summary
The mechanism must be configurable (disableable for static environments), respect cloud
API rate limits, and not interfere with membership protocol correctness. Accuracy
(new nodes discoverable within configurable interval), operational (rate-limit compliance,
observability), and fit (build on existing SPI) are the binding constraints.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | Works per-node; cluster size doesn't change the algorithm |
| Resources | 2 | Single virtual thread + one API call per interval is negligible |
| Complexity | 1 | Not a concern |
| Accuracy | 3 | Core purpose — new nodes must become discoverable |
| Operational | 3 | Rate-limit compliance is a hard requirement for cloud environments |
| Fit | 3 | Must compose with existing SPI without new methods |

---

## Candidate: Periodic Rediscovery Loop

**KB source:** [`.kb/distributed-systems/cluster-membership/service-discovery-patterns.md`](../../.kb/distributed-systems/cluster-membership/service-discovery-patterns.md)
**Relevant sections read:** `#continuous-rediscovery`, `#practical-guidance`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 5 | 10 | One call to discoverSeeds() per interval per node — independent of cluster size (#continuous-rediscovery) |
|       |   |   |    | **Would be a 2 if:** discoverSeeds() cost grew with cluster size (it doesn't — it returns seeds, not full membership) |
| Resources | 2 | 5 | 10 | Single virtual thread, one API call per 30-120s, O(seeds) comparison per cycle (#continuous-rediscovery) |
|           |   |   |    | **Would be a 2 if:** the discovery provider leaked connections or held locks across sleep intervals |
| Complexity | 1 | 5 | 5 | 8-line algorithm in the KB (#continuous-rediscovery pseudocode) |
| Accuracy | 3 | 4 | 12 | New nodes discoverable within one interval (30-120s). Not instant — there's a window where a new node exists but isn't yet discovered (#continuous-rediscovery) |
|          |   |   |    | **Would be a 2 if:** the interval was too long for latency-sensitive join requirements (mitigated by configurable interval down to 30s) |
| Operational | 3 | 5 | 15 | Configurable interval; KB notes "30s-120s balances freshness against API rate limits" (#continuous-rediscovery). DNS interval should be >= DNS TTL |
|             |   |   |    | **Would be a 2 if:** a provider's rate limit was below 1 request per 120s (no known cloud API is this restrictive) |
| Fit | 3 | 5 | 15 | Uses existing discoverSeeds() — no SPI change. Engine-level loop. KB pseudocode shows exact integration (#continuous-rediscovery) |
|     |   |   |    | **Would be a 2 if:** introduceSeed() didn't exist on the membership protocol API (it does — Rapid has an equivalent) |
| **Total** | | | **67** | |

**Hard disqualifiers:** None.

**Key strengths:**
- Simplest correct approach — KB provides a proven 8-line algorithm
- Uses existing SPI without modification
- Configurable and disableable

**Key weaknesses:**
- Not instant — discovery latency is bounded by the interval

---

## Candidate: Event-Driven Rediscovery

**KB source:** [`.kb/distributed-systems/cluster-membership/service-discovery-patterns.md`](../../.kb/distributed-systems/cluster-membership/service-discovery-patterns.md)
**Relevant sections read:** `#pluggable-discovery-spi`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 4 | 8 | Triggered by membership events — frequency scales with churn, not cluster size |
|       |   |   |   | **Would be a 2 if:** high churn caused excessive rediscovery calls (mitigated by debouncing) |
| Resources | 2 | 4 | 8 | Calls discoverSeeds() only on events — fewer calls than periodic. But burst of events = burst of API calls |
|           |   |   |   | **Would be a 2 if:** a mass failure event triggered 100 concurrent rediscovery calls (needs debouncing) |
| Complexity | 1 | 3 | 3 | Requires event subscription from membership protocol + debouncing logic |
| Accuracy | 3 | 2 | 6 | New nodes in a stable cluster are NEVER discovered — events only fire on membership changes, not on new nodes appearing in the discovery source. A node that joins a stable cluster with no recent churn is invisible |
| Operational | 3 | 3 | 9 | Debouncing adds tuning parameters; burst behavior under mass failure is hard to predict |
| Fit | 3 | 3 | 9 | Requires membership protocol to expose an event listener API that doesn't currently exist in the SPI |
| **Total** | | | **43** | |

**Hard disqualifiers:** Accuracy score of 2 — in a stable cluster with no churn, event-driven rediscovery NEVER fires, so new nodes are never discovered. This is a fundamental design flaw for the primary use case (autoscaling adds nodes to a healthy cluster).

**Key strengths:**
- Fewer API calls than periodic during normal operation

**Key weaknesses:**
- Cannot discover new nodes in a stable cluster — the trigger (membership change) doesn't fire when the cluster is healthy

---

## Candidate: Reactive Watch

**KB source:** [`.kb/distributed-systems/cluster-membership/service-discovery-patterns.md`](../../.kb/distributed-systems/cluster-membership/service-discovery-patterns.md)
**Relevant sections read:** `#production-system-survey` (Elasticsearch file-based provider watches for changes)

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 5 | 10 | Push-based — no polling overhead |
| Resources | 2 | 5 | 10 | Single long-lived connection; no repeated API calls |
| Complexity | 1 | 2 | 2 | Requires each DiscoveryProvider to implement a watch/subscription API — new SPI method |
| Accuracy | 3 | 5 | 15 | Instant notification when discovery source changes (K8s API watch, file watcher) |
|          |   |   |    | **Would be a 2 if:** the discovery source didn't support push (static seed lists, many DNS servers don't support NOTIFY) |
| Operational | 3 | 3 | 9 | Watch reconnection logic needed; long-lived connections can silently disconnect |
| Fit | 3 | 1 | 3 | Requires a new SPI method (e.g., `watchSeeds(Consumer<Set<NodeAddress>>)`) — violates the minimal-contract design from discovery-spi-design ADR. Not all providers support push. Static seeds and DNS have no watch mechanism. |
| **Total** | | | **49** | |

**Hard disqualifiers:** Fit score of 1 — requires SPI change that violates the confirmed discovery-spi-design ADR (minimal contract, one required method). Not all discovery sources support push/watch semantics.

**Key strengths:**
- Instant discovery — zero-latency notification for K8s and file-based providers

**Key weaknesses:**
- Requires SPI change, violating the parent ADR
- Not all discovery sources support push (static seeds, DNS without NOTIFY)

---

## Candidate: No Rediscovery (Gossip Only)

**KB source:** [`.kb/distributed-systems/cluster-membership/service-discovery-patterns.md`](../../.kb/distributed-systems/cluster-membership/service-discovery-patterns.md)
**Relevant sections read:** `#bootstrap-strategies`

| Constraint | Weight | Score (1–5) | Weighted | Evidence from KB |
|------------|--------|-------------|----------|-----------------|
| Scale | 2 | 5 | 10 | Zero overhead — no rediscovery mechanism |
| Resources | 2 | 5 | 10 | Zero resources — nothing to run |
| Complexity | 1 | 5 | 5 | Nothing to implement |
| Accuracy | 3 | 1 | 3 | **DISQUALIFIER:** Nodes not in the original seed set and not introduced by another node are invisible. In K8s/autoscaling, new pods at new IPs are never discovered. |
| Operational | 3 | 5 | 15 | Nothing to configure or monitor |
| Fit | 3 | 5 | 15 | No changes to anything |
| **Total** | | | **58** | |

**Hard disqualifiers:** Accuracy score of 1 — cannot discover nodes that appear at new addresses in dynamic environments. Defeats the purpose of supporting K8s, autoscaling, and spot instances.

**Key strengths:**
- Zero overhead, zero complexity — correct for static environments

**Key weaknesses:**
- Fundamentally broken for dynamic environments

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| Periodic Rediscovery Loop | 10 | 10 | 5 | 12 | 15 | 15 | **67** |
| No Rediscovery (Gossip Only) | 10 | 10 | 5 | 3 | 15 | 15 | **58** |
| Reactive Watch | 10 | 10 | 2 | 15 | 9 | 3 | **49** |
| Event-Driven Rediscovery | 8 | 8 | 3 | 6 | 9 | 9 | **43** |

## Preliminary Recommendation
Periodic Rediscovery Loop wins (67) by 9 points over No Rediscovery (58). It is the only
candidate that scores well on both Accuracy (new nodes discoverable) and Fit (no SPI change).
Reactive Watch has better accuracy (instant) but fails on Fit (requires SPI change).
No Rediscovery is correct for static environments — the recommendation includes making
the rediscovery loop opt-in and disabled by default for static configurations.

## Risks and Open Questions
- Risk: if cloud API rate limits tighten, the minimum interval may need to increase
- Risk: DNS caching may cause discoverSeeds() to return stale results even after re-resolution
- Open: should the engine auto-detect dynamic environments and enable rediscovery automatically?
