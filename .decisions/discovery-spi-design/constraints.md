---
problem: "What should the pluggable discovery SPI look like?"
slug: "discovery-spi-design"
captured: "2026-03-20"
status: "draft"
---

# Constraint Profile — discovery-spi-design

## Problem Statement
Define the SPI interface for cluster discovery. Discovery is the mechanism by which an engine node finds other nodes to bootstrap the Rapid membership protocol. Must be pluggable to support diverse environments (in-JVM, k8s, static seed lists, multicast, service registries).

## Constraints

### Scale
Must work from 1 to hundreds of nodes. Discovery only needs to find a few seeds — the membership protocol handles full cluster discovery from there.

### Resources
Not constraining.

### Complexity Budget
High. But the SPI itself should be minimal — complexity belongs in implementations, not the interface.

### Accuracy / Correctness
- Must reliably return at least one reachable seed for bootstrap to succeed
- Stale results are acceptable — membership protocol handles liveness
- Total discovery failure must be surfaced as an explicit error

### Operational Requirements
- In-JVM implementation must be trivial (for testing)
- Must support diverse environments without changing the engine code
- Should not require the engine to know which discovery mechanism is in use

### Fit
- Java SPI (ServiceLoader) or builder-injected. Pure Java 25.
- Consumed by Rapid membership protocol for bootstrap
- Must not overlap with membership protocol responsibilities (failure detection, view management)

## Key Constraints (most narrowing)
1. **Minimal contract** — discovery finds seeds, membership protocol does the rest
2. **SPI pluggability** — different environments, same engine code
3. **No overlap with membership** — discovery doesn't detect failures or manage views

## Unknown / Not Specified
None.
