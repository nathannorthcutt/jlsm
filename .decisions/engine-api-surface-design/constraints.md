---
problem: "Engine API surface design for jlsm-engine"
slug: "engine-api-surface-design"
captured: "2026-03-19"
status: "draft"
---

# Constraint Profile — engine-api-surface-design

## Problem Statement
What should the jlsm-engine public API look like — engine lifecycle, table handle pattern, operation routing — to support concurrent callers in embedded mode now and remote client proxies over a future network layer?

## Constraints

### Scale
- 100K+ tables per engine instance
- Millions of QPS and inserts concurrently
- API overhead must be negligible relative to underlying I/O

### Resources
- Constrained memory/CPU in containers
- Handle objects must be lightweight
- No unnecessary object allocation per operation

### Complexity Budget
- High — expert team
- Complex interface hierarchies acceptable if justified

### Accuracy / Correctness
- Thread-safe for concurrent callers
- Type-safe API — compile-time errors over runtime errors where possible
- Clear ownership semantics for handles and engine lifecycle

### Operational Requirements
- Two modes of operation:
  1. **Embedded** — engine in-process, caller is likely sole user, wants low-overhead direct access to table operations
  2. **Remote** — table handle is a client proxy over the network, multiple remote callers
- API must look the same in both modes — caller code should not change
- Must support future interception points: rate limiting, metrics, throttling, backpressure, cluster routing
- Engine lifecycle: open → serve → close (Closeable/AutoCloseable)
- Graceful rejection of traffic the engine cannot accept

### Fit
- Java 25 — sealed interfaces, records, pattern matching available
- JPMS — clean module exports
- Pure library — no framework dependencies
- Must compose with existing jlsm-table fluent query API (pass-through)
- Must not preclude future network protocol or cluster distribution layers

## Key Constraints (most narrowing)
1. **Dual-mode API (embedded + remote proxy)** — the same interface must work for direct in-process access and as a client proxy contract, meaning interfaces are mandatory and implementations must be swappable
2. **Future interception points** — rate limiting, metrics, cluster routing must be insertable without changing the caller-facing API
3. **Pass-through to existing fluent query API** — the table handle must expose the existing jlsm-table query API without wrapping or duplicating it

## Updates 2026-03-19

### Handle lifecycle constraints (added during deliberation)
- Callers will NOT reliably close handles — engine must be self-protecting
- Handles must be tracked with source attribution (thread/caller in embedded, client/connection in remote)
- Under handle pressure, evict the greediest source's handles first (protect well-behaved clients)
- Evicted handles must provide diagnostic information to the client: source identity, handle counts, allocation site, eviction reason
- Configurable limits: per-source-per-table, per-table total, engine-wide handle budget

## Unknown / Not Specified
None — full profile captured.
