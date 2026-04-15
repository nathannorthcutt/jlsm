---
problem: "continuous-rediscovery"
date: "2026-04-13"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-engine/src/main/java/jlsm/engine/cluster/"
---

# ADR — Continuous Re-Discovery

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Service Discovery Patterns for Cluster Bootstrap | Chosen approach — rediscovery loop algorithm, watch patterns, production survey | [`.kb/distributed-systems/cluster-membership/service-discovery-patterns.md`](../../.kb/distributed-systems/cluster-membership/service-discovery-patterns.md) |

## Related ADRs
| ADR | Relationship |
|-----|-------------|
| [discovery-spi-design](../discovery-spi-design/adr.md) | Parent — this decision extends the SPI with optional watchSeeds() |
| [cluster-membership-protocol](../cluster-membership-protocol/adr.md) | Consumer — Rapid receives newly discovered seeds |

---

## Files Constrained by This Decision
- `modules/jlsm-engine/src/main/java/jlsm/engine/cluster/` — DiscoveryProvider SPI, engine rediscovery loop

## Problem
In dynamic environments (K8s, autoscaling, spot instances), nodes appear at addresses not
in the original seed set. Without rediscovery, these nodes can only join if they independently
discover an existing member. The cluster cannot proactively find them, delaying repair and
scaling.

## Constraints That Drove This Decision
- **Discovery latency for cluster repair**: failed node replacement must be discoverable in sub-seconds for watch-capable providers, not minutes
- **Universal fallback**: not all providers support push — static seeds and DNS need polling
- **SPI consistency**: new methods must follow the existing default-no-op pattern (register/deregister)

## Decision
**Chosen approach: Periodic Rediscovery + Optional Reactive Watch (composite)**

Two-tier rediscovery layered on the existing `DiscoveryProvider` SPI:

1. **Reactive Watch (fast path)**: an optional `watchSeeds(Consumer<Set<NodeAddress>>)` default
   method on `DiscoveryProvider`. Providers that support push (K8s API watch, Consul watch, file
   watcher) override it — the engine receives sub-second notification when new nodes appear.

2. **Periodic Rediscovery Loop (fallback)**: a background virtual thread that calls
   `discoverSeeds()` on a configurable interval (default 60s, range 5s–300s). Active when the
   provider does not override `watchSeeds()`. Stale seeds are harmless — Rapid verifies liveness.

The engine tries `watchSeeds()` first. If the provider leaves the default no-op, it falls back
to the periodic loop. Both paths feed newly discovered addresses to the existing bootstrap
contact logic — no new membership protocol API needed.

### SPI Extension

```java
interface DiscoveryProvider {
    Set<NodeAddress> discoverSeeds();                           // required (existing)
    default void register(NodeAddress self) {}                  // existing
    default void deregister(NodeAddress self) {}                // existing
    default void watchSeeds(Consumer<Set<NodeAddress>> cb) {}   // NEW — default no-op
    default void unwatchSeeds() {}                              // NEW — default no-op
}
```

### Periodic Loop Algorithm

```
rediscoveryLoop(provider, membership, interval):
  while running:
    sleep(interval)
    freshSeeds = provider.discoverSeeds()
    knownMembers = membership.members().map(m -> m.address())
    newSeeds = freshSeeds - knownMembers
    for seed in newSeeds:
      contactSeed(seed)  // reuses bootstrap contact path
```

## Rationale

### Why Periodic Rediscovery + Optional Reactive Watch
- **Sub-second discovery**: watch-capable providers (K8s, Consul, file) deliver instant
  notification — critical for cluster repair latency
  ([KB: `#continuous-rediscovery`](../../.kb/distributed-systems/cluster-membership/service-discovery-patterns.md))
- **Universal coverage**: periodic loop works for all providers including static seeds and DNS
  where push is unavailable
  ([KB: `#practical-guidance`](../../.kb/distributed-systems/cluster-membership/service-discovery-patterns.md))
- **SPI consistency**: `watchSeeds()` / `unwatchSeeds()` follow the same default-no-op pattern
  as `register()` / `deregister()` — providers that don't support push simply leave the defaults
  ([KB: `#pluggable-discovery-spi`](../../.kb/distributed-systems/cluster-membership/service-discovery-patterns.md))

### Why not No Rediscovery (Gossip Only)
- **Cannot discover new-IP nodes**: in autoscaling/K8s, new pods get new IPs unknown to any
  existing member. Gossip can only propagate what at least one member already knows.

### Why not Event-Driven Rediscovery
- **Dead in stable clusters**: triggers on membership changes, not discovery source changes. A
  healthy cluster with autoscaled nodes joining would never trigger rediscovery.

### Why not Watch-Only
- **Not all providers support push**: static seeds and DNS have no watch mechanism. Requiring
  watch would make these providers non-functional.

## Implementation Guidance

Key parameters:
- Periodic loop interval: 60s default, configurable 5s–300s
- For DNS providers: interval should be >= DNS TTL to avoid redundant lookups
- For cloud API providers (AWS, GCP): interval >= 30s to respect rate limits
- Watch providers: no interval — push-driven

Engine startup sequence:
1. Call `discoveryProvider.register(myAddress)`
2. Call `discoveryProvider.watchSeeds(this::onNewSeedsDiscovered)`
3. If watch is a no-op (detected by default method check or flag): start periodic loop
4. Call `discoveryProvider.discoverSeeds()` for initial bootstrap
5. On shutdown: `unwatchSeeds()` → `deregister(myAddress)` → stop periodic loop

Thread-safety requirement:
- `DiscoveryProvider` implementations MUST be thread-safe — `discoverSeeds()` may be called
  from the rediscovery loop thread while `watchSeeds()` callbacks fire from the provider's
  internal thread. Document this in the SPI javadoc.

Edge cases:
- Watch silently disconnects: provider should internally reconnect and re-fire with current
  state. Engine does not manage watch reconnection.
- Duplicate seed contact: contacting an already-known member is harmless — Rapid ignores it
- `discoverSeeds()` failure: log warning, retry on next interval. Do not crash the engine.

## What This Decision Does NOT Solve
- Watch reconnection policy for long-lived watches that silently disconnect (per-provider implementation concern)
- Automatic detection of dynamic vs static environments (user configures rediscovery explicitly via engine builder)

## Conditions for Revision
This ADR should be re-evaluated if:
- The default-no-op pattern for `watchSeeds()` proves insufficient (e.g., providers need to signal whether they support push — may need `supportsWatch()` method)
- Discovery latency requirements tighten beyond what push-based watch can deliver (would need transport-level peer announcement)
- A standard Java SPI for service discovery emerges that replaces custom providers

---
*Confirmed by: user deliberation (1 challenge — widened scope to include watch extension for sub-second discovery) | Date: 2026-04-13*
*Full scoring: [evaluation.md](evaluation.md)*
