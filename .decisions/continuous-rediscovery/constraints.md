---
problem: "Should the engine periodically re-invoke discoverSeeds() to find new nodes in dynamic environments?"
slug: "continuous-rediscovery"
captured: "2026-04-13"
status: "draft"
---

# Constraint Profile — continuous-rediscovery

## Problem Statement
The discovery SPI's `discoverSeeds()` is called once at bootstrap. In dynamic environments
(K8s pod reschedules, autoscaling groups, spot instances), new nodes may appear at addresses
not in the original seed set. Without rediscovery, these nodes can only join if they
independently discover an existing member — the cluster cannot proactively find them.
Should the engine periodically re-invoke `discoverSeeds()` and introduce newly discovered
addresses to the membership protocol?

## Constraints

### Scale
Up to 1000 nodes. Dynamic environments where nodes appear and disappear (K8s, autoscaling,
spot instances). Also supports static environments (bare metal, fixed VMs) where
rediscovery is unnecessary.

### Resources
Pure Java. Rediscovery loop runs on a single virtual thread. `discoverSeeds()` may involve
network calls (DNS resolution, K8s API, AWS DescribeInstances) — must not consume excessive
resources or hit API rate limits.

### Complexity Budget
Not a constraint per user feedback.

### Accuracy / Correctness
Stale seeds are harmless — Rapid verifies liveness independently. Rediscovery must not
interfere with the membership protocol's correctness (no duplicate join attempts, no
conflicting membership views). New nodes must become discoverable within a configurable
interval after appearing in the discovery source.

### Operational Requirements
Configurable interval with a sensible default. Must respect cloud API rate limits (AWS
DescribeInstances: 100 requests/second, K8s API: 5 QPS client-side default). Observable:
last rediscovery time, new seeds found count, errors. Must be disableable for static
environments.

### Fit
Builds on the existing `DiscoveryProvider.discoverSeeds()` method and
`MembershipProtocol.introduceSeed()` (or equivalent). No new SPI methods needed. The
rediscovery loop is an engine-level concern, not a provider concern.

## Key Constraints (most narrowing)
1. **Must be opt-in/configurable** — static environments don't need it; enabling it by default wastes resources
2. **Must respect API rate limits** — cloud providers throttle metadata APIs; interval must be >= 30s for cloud
3. **Must not interfere with membership protocol** — stale seeds are fine, but the mechanism must not create pathological retry loops or duplicate join storms

## Unknown / Not Specified
None — full profile captured.
