---
{
  "id": "membership.continuous-rediscovery",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "membership"
  ],
  "requires": [],
  "invalidates": [],
  "decision_refs": [
    "continuous-rediscovery",
    "discovery-spi-design"
  ],
  "kb_refs": [
    "distributed-systems/cluster-membership/service-discovery-patterns"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F22"
  ]
}
---
# membership.continuous-rediscovery — Continuous Rediscovery

## Requirements

### SPI Extension

R1. The `DiscoveryProvider` interface must be extended with two new default
methods:
- `boolean watchSeeds(Consumer<Set<NodeAddress>> callback)` — default returns
  `false` (no-op). Providers supporting push-based discovery (K8s API watch,
  Consul watch, file watcher) override and return `true`. The callback
  receives the full current seed set on each change.
- `void unwatchSeeds()` — default no-op. Cancels a prior `watchSeeds()`
  registration.

R2. `watchSeeds()`/`unwatchSeeds()` follow the same default-no-op pattern as
`register()`/`deregister()`. The boolean return value of `watchSeeds()` is the
no-op detection mechanism — the engine checks it to determine whether push
discovery is available.

R3. `DiscoveryProvider` implementations must be thread-safe for the following
concurrent-call pairs: `discoverSeeds()` with itself (reentrant),
`discoverSeeds()` with `watchSeeds()` callbacks firing from the provider's
internal thread. `register()` and `deregister()` are lifecycle methods called
once and need not be concurrent-safe with other methods.

R3a. `discoverSeeds()` must return an unmodifiable snapshot (e.g.,
`Set.copyOf()`). The engine must not rely on the returned set being mutable
or backed by provider internal state.

### Rediscovery Loop

R4. The engine must run a background rediscovery loop on a dedicated virtual
thread. The loop calls `discoverSeeds()` at a configurable interval, compares
results to current membership, and contacts any newly discovered seeds via the
existing bootstrap contact path.

R5. The rediscovery loop algorithm:
```
while running:
    sleep(interval)
    freshSeeds = provider.discoverSeeds()
    knownMembers = membership.members().map(m -> m.address())
    newSeeds = freshSeeds - knownMembers
    for seed in newSeeds (bounded by maxConcurrentContacts):
        if seed not in pendingContacts:
            pendingContacts.add(seed)
            contactSeed(seed)    // async, removes from pendingContacts on completion
```

R6. The periodic loop ALWAYS runs, regardless of whether `watchSeeds()`
returned true. When watch is active, the loop interval is multiplied by a
configurable factor (default 5x — e.g., 300s instead of 60s). This provides a
health backstop: if the watch silently dies, the periodic loop continues
discovering nodes at a reduced frequency. When watch is not active, the loop
runs at the normal interval.

R7. The loop must run on a virtual thread (I/O-bound — `discoverSeeds()` may
call external services). Providers SHOULD avoid `synchronized` blocks in
`discoverSeeds()` to prevent virtual thread pinning.

### Watch Path

R8. When `watchSeeds()` returns true, the callback is invoked with the full
current seed set whenever the set changes. The engine diffs against current
membership and contacts new seeds (bounded by `maxConcurrentContacts`).

R8a. The `Set<NodeAddress>` passed to the `watchSeeds()` callback MUST be an
unmodifiable snapshot, consistent with R3a. The engine's callback SHOULD
additionally defensively copy the set before processing.

R9. The callback may run on any thread (the provider's internal watch thread).
The engine must handle it thread-safely.

R9a. The engine's callback implementation MUST catch all exceptions internally
(try-catch around all work, log errors at warning level). The callback MUST
check the running flag before processing seeds — if running is false
(shutdown in progress), the callback returns immediately without contacting
seeds. Provider implementations SHOULD guard against callback exceptions but
MUST NOT rely on the callback being exception-free.

R10. Watch reconnection is the provider's responsibility. If the watch
silently dies, the always-running periodic loop (R6) serves as a backstop.

R10a. If `watchSeeds()` returned true but the watch callback counter (R20f)
remains zero after 2x the base rediscovery interval, the engine SHOULD log a
warning indicating possible watch failure. Implementations MAY fall back to
the base interval. This is advisory — the spec does not mandate automatic
fallback.

### Engine Startup Sequence

R11. The engine startup sequence for discovery must be:
1. Initialize local membership view (self as sole member)
2. `discoveryProvider.register(myAddress)` — announce self
3. `boolean watchActive = discoveryProvider.watchSeeds(this::onNewSeedsDiscovered)` — attempt push
4. Start periodic rediscovery loop (interval adjusted per R6 based on watchActive)
5. `discoveryProvider.discoverSeeds()` — initial bootstrap
6. Contact discovered seeds via bootstrap path

The membership view must be initialized (step 1) before `watchSeeds()` (step
3) to ensure the callback's comparison against membership operates on a valid
view.

R11a. Duplicate `contactSeed` calls for the same address within a short window
must be deduplicated via a `pendingContacts` set. The `pendingContacts` set
tracks in-flight contacts only. Entries are removed when the contact attempt
completes (success or failure). The set does not persist historical contacts.
The set size is bounded by `maxConcurrentContacts` (R16).

R11b. If the initial `discoverSeeds()` returns an empty set (or a set
containing only the local address after filtering), the engine must log a
warning indicating single-node bootstrap. This is not an error — the periodic
rediscovery loop (R4) will discover peers when they become available.

R12. The engine shutdown sequence must be:
1. Set running flag to false
2. Interrupt the rediscovery loop virtual thread
3. Await termination with bounded timeout (5 seconds)
4. `discoveryProvider.unwatchSeeds()` — cancel watch
5. `discoveryProvider.deregister(myAddress)` — remove self

Each step must execute even if a prior step throws (deferred exception
pattern). The loop must check the running flag after `discoverSeeds()` returns
and before processing results.

### Error Handling

R13. If `discoverSeeds()` throws `IOException` during the periodic loop, the
loop must log a warning, increment the error counter (R20b), check the running
flag, and retry on the next interval. The engine must not crash or stop the
loop on transient discovery failures.

R14. If `discoverSeeds()` throws `IOException` during initial bootstrap (R11
step 5), the engine must propagate the exception — bootstrap cannot proceed
without at least one discovered seed (see also R11b for the empty-but-no-error
case).

R15. Contacting an already-known member (duplicate seed) must be harmless. The
membership protocol (Rapid) ignores contact attempts from known members.

R15a. `contactSeed` is fire-and-forget with no built-in retry. Failed contacts
are discovered again on the next rediscovery loop iteration if the seed still
appears in `discoverSeeds()` and is not yet a member. The engine does not
maintain a retry queue.

### Concurrency

R16. `contactSeed` must be asynchronous (fire-and-forget). The engine must
bound concurrent contact attempts (configurable, default max 10). Seeds beyond
the bound are not contacted in the current iteration — they will be
re-evaluated on subsequent loop iterations if they still appear in
`discoverSeeds()` and are not yet members. No persistent queue is maintained.
When more than `maxConcurrentContacts` new seeds are discovered, the engine
SHOULD log a warning including the total count and the number deferred.

R17. When both watch and periodic loop are active (R6), `contactSeed` is
called from both the watch callback thread and the loop virtual thread. This
is safe because `contactSeed` is idempotent (R15) and the `pendingContacts`
set (R11a) deduplicates concurrent attempts to the same address.

### Configuration

R18. Configurable via engine builder: rediscovery interval (default 60s, range
5s–300s), watch-active interval multiplier (default 5x), max concurrent
contacts (default 10), max seeds warning threshold (default 1000).

R18a. The effective watch-active interval (base interval x multiplier) SHOULD
NOT exceed 600 seconds. If the configured combination exceeds this, the engine
must log a warning at startup indicating the backstop gap duration. The engine
does not enforce a hard cap — the operator may intentionally accept a long
backstop interval.

R19. Guidance: DNS-based providers should use an interval >= DNS TTL to avoid
redundant lookups. Cloud API providers (AWS, GCP) should use >= 30s to respect
rate limits. These are operator guidance, not enforced constraints.

### Observability

R20. The engine must expose queryable metrics for rediscovery:
(a) rediscovery loop executions (counter)
(b) rediscovery loop errors (counter — `discoverSeeds()` IOException)
(c) seeds discovered (gauge — count from last `discoverSeeds()`)
(d) new seeds found (counter — seeds not in membership)
(e) seeds contacted (counter)
(f) watch callbacks received (counter)
(g) watch active (boolean gauge)
(h) contact failures (counter — `contactSeed` errors)

---

## Design Narrative

### Intent

Enable the engine to discover new cluster members in dynamic environments
(K8s, autoscaling, spot instances) where nodes appear at addresses not in the
original seed set. Without rediscovery, these nodes can only join if they
independently discover an existing member.

### Why two tiers

Watch-capable providers (K8s, Consul) deliver sub-second discovery — critical
for cluster repair latency. But not all providers support push (static seeds,
DNS). The two-tier approach gives the best of both: sub-second notification
when available, universal polling fallback when not.

### Why always-run periodic loop

Round 1 adversarial review revealed that relying solely on the watch creates
an undetectable silent failure mode: if the watch dies, the engine never
discovers new nodes. The always-run periodic loop (at a longer interval when
watch is active) provides defense-in-depth. This is cheaper than implementing
a watch health-check protocol — a single `discoverSeeds()` call every 300s is
negligible overhead.

### Why boolean return for watchSeeds()

The engine needs to detect whether the provider supports push to set the
periodic loop interval. The ADR originally suggested "detected by default
method check or flag." Reflection on default methods is fragile (a provider
could override with an empty body). A boolean return is explicit and
unambiguous. Backward-compatible as a default method returning false.

### What was ruled out

- **No rediscovery (gossip only)**: cannot discover nodes at new IPs unknown
  to any existing member
- **Event-driven rediscovery**: triggers on membership changes, not discovery
  source changes — a healthy cluster with autoscaled nodes would never trigger
- **Watch-only**: not all providers support push — static seeds and DNS have
  no watch mechanism

### Hardening summary

Two adversarial falsification rounds:
- Round 1: 16 findings — no-op detection (boolean return), callback exception
  safety, watch loss (always-run backstop), unbounded contacts, startup race,
  shutdown semantics, phantom members, zero observability
- Round 2: 6 findings — watch liveness detection, pendingContacts lifecycle,
  shutdown callback race, callback set snapshot, empty bootstrap, excess
  seed handling, effective interval warning
