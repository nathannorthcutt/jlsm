---
problem: "discovery-spi-design"
date: "2026-03-20"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/"
---

# ADR — Discovery SPI Design

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| (general knowledge) | No direct KB entry — API design decision | — |

## Related ADRs
| ADR | Relationship |
|-----|-------------|
| [cluster-membership-protocol](../cluster-membership-protocol/adr.md) | Rapid consumes discovery seeds for bootstrap |

---

## Files Constrained by This Decision
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/` — discovery SPI and in-JVM implementation

## Problem
What should the pluggable discovery SPI look like for cluster bootstrap across diverse environments (k8s, VMs on VPC, bare metal, Docker, etc.)?

## Constraints That Drove This Decision
- **Minimal contract**: discovery finds seeds, membership protocol does the rest — no overlap
- **SPI pluggability**: different environments need different discovery, same engine code
- **Self-announcement**: nodes on unmanaged infrastructure (VMs, bare metal) must be able to announce themselves for others to discover

## Decision
**Chosen approach: Minimal Seed Provider with Optional Registration**

Three-method interface with default no-op registration:

```java
interface DiscoveryProvider {
    /** Return addresses of nodes that might be in the cluster. */
    Set<NodeAddress> discoverSeeds();

    /** Announce this node to the discovery mechanism. No-op by default. */
    default void register(NodeAddress self) {}

    /** Remove this node from the discovery mechanism. No-op by default. */
    default void deregister(NodeAddress self) {}
}
```

- `discoverSeeds()` is the only required method. Returns a point-in-time snapshot of candidate seed addresses. Stale or dead seeds are acceptable — the membership protocol (Rapid) verifies liveness.
- `register()`/`deregister()` are default no-ops. Implementations that need self-announcement (shared-file, etcd, database-backed, multicast) override them. Managed environments (k8s, Consul) typically handle registration externally and leave the defaults.
- The engine calls `register(self)` on startup and `deregister(self)` on graceful shutdown. Stale registrations from crashes are harmless — Rapid detects dead nodes.

## Rationale

### Why Minimal Seed Provider with Optional Registration
- **One required method**: simplest implementations (static seed list, k8s label query) implement one method — minimal barrier
- **Self-announcement when needed**: bare metal and VM-on-VPC environments have no external service discovery — `register()`/`deregister()` solves this without requiring it everywhere
- **No overlap with membership**: discovery finds seeds; Rapid handles liveness, views, failure detection. Clear separation of concerns
- **Crash-safe**: stale registrations from crashes don't affect correctness — Rapid's failure detection handles them

### Why not Reactive Discovery Service
- Continuous watch with join/leave events overlaps with Rapid's membership protocol. Two systems making membership decisions creates inconsistency risk.

### Why not mandatory Two-Phase Discovery
- Requiring `register()`/`deregister()` for all implementations adds lifecycle coupling that managed environments don't need. Default methods make it optional.

## Implementation Guidance

**In-JVM test implementation:**
```java
class InJvmDiscoveryProvider implements DiscoveryProvider {
    private static final Set<NodeAddress> registry = ConcurrentHashMap.newKeySet();

    public Set<NodeAddress> discoverSeeds() { return Set.copyOf(registry); }
    public void register(NodeAddress self) { registry.add(self); }
    public void deregister(NodeAddress self) { registry.remove(self); }
}
```

**Static seed list implementation:**
```java
class StaticSeedDiscovery implements DiscoveryProvider {
    private final Set<NodeAddress> seeds;
    StaticSeedDiscovery(Set<NodeAddress> seeds) { this.seeds = seeds; }
    public Set<NodeAddress> discoverSeeds() { return seeds; }
    // register/deregister: default no-ops
}
```

**Engine bootstrap sequence:**
1. Engine starts, calls `discoveryProvider.register(myAddress)`
2. Engine calls `discoveryProvider.discoverSeeds()`
3. If seeds found: pass to Rapid for membership bootstrap
4. If no seeds (first node): start as solo cluster, wait for others to join
5. On shutdown: call `discoveryProvider.deregister(myAddress)`

**Retry on bootstrap failure:**
- If all seeds are dead, retry `discoverSeeds()` with exponential backoff
- Retry is the engine's responsibility, not the SPI's

**ServiceLoader compatibility:**
- Implementations can be registered via `META-INF/services/` for zero-config pluggability
- Or injected via the engine builder for programmatic configuration

## What This Decision Does NOT Solve
- Continuous re-discovery (handled by Rapid membership protocol)
- Environment-specific configuration (each implementation handles its own config)
- Authenticated discovery (TLS, tokens — implementation concern, not SPI concern)
- Discovery of which *tables* a node owns (handled by HRW ownership assignment)

## Conditions for Revision
This ADR should be re-evaluated if:
- The optional registration pattern proves confusing — consider splitting into `SeedProvider` and `RegisterableDiscovery extends SeedProvider`
- Discovery needs to return node metadata beyond addresses (capabilities, capacity) — extend `NodeAddress` or add a richer return type
- A standard discovery protocol emerges in the Java ecosystem that obsoletes custom SPI

---
*Confirmed by: user deliberation | Date: 2026-03-20*
*Full scoring: [evaluation.md](evaluation.md)*
