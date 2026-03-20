---
problem: "discovery-spi-design"
evaluated: "2026-03-20"
candidates:
  - path: "general-knowledge"
    name: "Minimal Seed Provider"
  - path: "general-knowledge"
    name: "Reactive Discovery Service"
  - path: "general-knowledge"
    name: "Two-Phase Discovery (find + register)"
constraint_weights:
  scale: 1
  resources: 1
  complexity: 1
  accuracy: 2
  operational: 3
  fit: 3
---

# Evaluation — discovery-spi-design

## References
- Constraints: [constraints.md](constraints.md)
- Related ADRs: cluster-membership-protocol (Rapid consumes discovery for bootstrap)

## Constraint Summary
The SPI must be minimal (discovery finds seeds, membership does the rest), pluggable across
environments, and must not overlap with the membership protocol's responsibilities.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 1 | Discovery is a bootstrap concern — membership handles scale |
| Resources | 1 | Not constraining |
| Complexity | 1 | Complexity in implementations, not the SPI |
| Accuracy | 2 | Must find seeds reliably, but stale results are OK |
| Operational | 3 | Must be trivial to implement for testing; diverse environments |
| Fit | 3 | Minimal contract, no overlap with membership, Java SPI compatible |

---

## Candidate: Minimal Seed Provider

Single-method interface: `Set<NodeAddress> discoverSeeds()`. Returns addresses
of nodes that might be in the cluster. No lifecycle, no callbacks, no continuous
watching. Implementations registered via ServiceLoader or builder injection.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Only needs to find a few seeds — membership handles the rest |
| Resources | 1 | 5 | 5 | One method call, no background threads |
| Complexity | 1 | 5 | 5 | One method — simplest possible SPI |
| Accuracy | 2 | 4 | 8 | Returns point-in-time snapshot; stale seeds OK since membership verifies liveness |
| Operational | 3 | 5 | 15 | In-JVM impl: return a static set. K8s: call API. Static: read config. All trivial |
| Fit | 3 | 5 | 15 | Minimal contract — zero overlap with membership. ServiceLoader compatible. Pure function |
| **Total** | | | **53** | |

**Hard disqualifiers:** none

**Key strengths:**
- Absolute minimum contract — implementors write one method
- No lifecycle management, no background threads, no state
- Clear separation: discovery finds seeds, membership handles everything after

**Key weaknesses:**
- No self-registration — node must be findable by other means (seed list, k8s label, etc.)
- No continuous re-discovery — if initial seeds are all dead, must retry the whole call

---

## Candidate: Reactive Discovery Service

Interface with lifecycle: `start()`, `stop()`, `Stream<DiscoveryEvent> watch()`.
Continuously monitors for new peers, emits join/leave events.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Can discover nodes continuously |
| Resources | 1 | 3 | 3 | Background threads, event streams, lifecycle management |
| Complexity | 1 | 2 | 2 | Lifecycle (start/stop), event model, threading — heavyweight SPI |
| Accuracy | 2 | 5 | 10 | Continuous monitoring catches new nodes and departures |
| Operational | 3 | 3 | 9 | In-JVM impl needs background thread and event emission — not trivial |
| Fit | 3 | 2 | 6 | Overlaps with membership protocol — both detect joins/leaves. Two systems disagreeing on membership is a bug factory |
| **Total** | | | **35** | |

**Hard disqualifiers:** overlaps with Rapid membership protocol responsibilities

---

## Candidate: Two-Phase Discovery (find + register)

Two methods: `Set<NodeAddress> discoverSeeds()` + `void register(NodeAddress self)`.
Discovery handles both finding and being found.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Same as minimal for finding; registration helps new nodes be found |
| Resources | 1 | 4 | 4 | Registration may need a registry backend |
| Complexity | 1 | 4 | 4 | Two methods — slightly more than minimal but still simple |
| Accuracy | 2 | 5 | 10 | Self-registration ensures new nodes are discoverable |
| Operational | 3 | 4 | 12 | In-JVM: register in a shared ConcurrentHashMap. K8s: labels or annotations. Slightly more work |
| Fit | 3 | 4 | 12 | Registration adds lifecycle coupling — must deregister on shutdown. But doesn't overlap with membership protocol |
| **Total** | | | **47** | |

**Hard disqualifiers:** none

**Key strengths:**
- Self-registration solves the "how do other nodes find me" problem explicitly

**Key weaknesses:**
- Registration adds lifecycle coupling (must deregister on shutdown, handle crashes)
- Some environments handle registration externally (k8s service discovery, DNS) making `register()` redundant

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| Minimal Seed Provider | 5 | 5 | 5 | 8 | 15 | 15 | **53** |
| Two-Phase Discovery | 5 | 4 | 4 | 10 | 12 | 12 | **47** |
| Reactive Discovery Service | 5 | 3 | 2 | 10 | 9 | 6 | **35** |

## Preliminary Recommendation
Minimal Seed Provider wins on weighted total (53). The single-method contract is the thinnest possible SPI that still bootstraps the membership protocol. Registration and continuous discovery are either unnecessary (membership handles liveness) or handled by the environment (k8s, DNS).

## Risks and Open Questions
- If all initial seeds are dead, the caller must retry `discoverSeeds()` — the SPI should document that the caller may retry with backoff
- Self-registration may be needed for some environments — can be added as an optional second method later without breaking existing implementations
