---
problem: "tenant-key-revocation-and-external-rotation"
evaluated: "2026-04-21"
candidates:
  - path: "n/a (design evaluation, not candidate comparison)"
    name: "API primary + opt-in polling; streaming paginated rekey; three-state failure machine"
constraint_weights:
  scale: 3
  resources: 2
  complexity: 2
  accuracy: 3
  operational: 3
  fit: 2
---

# Evaluation — tenant-key-revocation-and-external-rotation

## Approach

This ADR is downstream of `three-tier-key-hierarchy` (ADR A) and operates
within its pinned constraints (sharded registry, per-tenant KMS isolation,
lazy domain open, cascading rewrap). The constraint profile narrows the
design space to near-determinism: streaming paginated rekey is implied by
unbounded scale; dual-reference is implied by resumable rekey; three-state
failure machine was confirmed by the user. No meaningful candidate
comparison exists — the evaluation is a design validation, not a scoring
matrix.

## References

- Constraints: [constraints.md](constraints.md)
- Prerequisite: [`../three-tier-key-hierarchy/adr.md`](../three-tier-key-hierarchy/adr.md)

## Design shape

### Rekey coordination (API + opt-in polling)

- `rekey(tenantId, oldKekRef, newKekRef, continuationToken?)` — primary
  tenant-driven path
- Authenticated via proof-of-control: caller provides a nonce-bound
  sentinel unwrapped under old KEK and re-wrapped under new KEK
- Opt-in polling per tenant (default 15min cadence); unwraps a
  sentinel; classifier distinguishes transient vs permanent failures

### Rekey execution (streaming, resumable, per-shard atomic)

- Iterates the tenant's sharded registry one batch at a time
- Per-shard: unwrap(old), rewrap(new), temp+fsync+rename commit
- Progress file `{oldKekRef, newKekRef, nextShardIndex, startedAt}`
  enables crash-resume
- Bounded per-call (default batch=100 domains) returns continuation
  token
- Per-tenant isolation — one tenant's rekey cannot starve others

### Dual-reference during migration

- Shard entries are `(kekRef, wrappedBlob)` tuples; migration adds
  `(newKekRef, newWrappedBlob)` alongside
- Reads: prefer `newKekRef`, fall back to `oldKekRef`
- Writes: always use `newKekRef` once rekey started
- Completion: GC `oldKekRef` entries when all shards have new-only

### Three-state failure machine

| State | Entry trigger | Behavior | Exit trigger |
|---|---|---|---|
| healthy | Default | Normal ops | N=5 permanent-class failures (jittered backoff) |
| grace-read-only | N permanent failures | Writes rejected; reads from cache within ADR-C TTL | Grace window exhausts (default 1h) OR cache TTLs expire OR rekey success |
| failed | Grace exhausted | Reads + writes both rejected | Rekey API success OR polling detects usable KEK |

Classifier: permanent errors (AccessDenied, KeyDisabled, NotFound) count
toward N. Transient errors (throttling, timeout) retry without counting.

### Explicit decommission

Deferred to a future `tenant-lifecycle` ADR. Out of scope for this ADR.

## Falsification (inline)

### Challenged invariants

**Streaming rekey safety under concurrent writes**
- Risk: a write arriving mid-rekey could land under the old KEK if
  ordering is wrong.
- Holds: dual-reference design forces writes under newKekRef as soon as
  rekey is initiated. The rekey-progress file is the serialisation point.

**Proof-of-control replay attacks**
- Risk: caller replays an old sentinel to spoof proof-of-control.
- Mitigation: sentinel includes a nonce + timestamp; jlsm validates
  freshness (window ≤ 5 min).

**Classifier false positives**
- Risk: sustained KMS outage (network split) misclassified as permanent
  revocation; tenants forced into grace-read-only unnecessarily.
- Mitigation: N=5 consecutive permanent-class errors with jittered
  backoff. Tolerant of ~30min hiccups; detects real revocation within
  ~5min of consistent failure.

**Stuck rekey progress**
- Risk: rekey started but never resumed; tenant hangs in half-migrated
  state.
- Mitigation: progress files carry startedAt; stale progress (>24h)
  emits an observable event; operator can abort or resume.

**Dual-reference registry bloat**
- Risk: in-progress rekey doubles per-shard storage overhead.
- Bounded: new entries replace old on shard commit; doubling is
  transient (only between shard unwrap and commit, typically <100ms).

### Strongest counter-argument

An alternative would be: require tenants to dual-write to both old and
new CMK before rekey starts (like database online-migration patterns).
This shifts the burden from jlsm to the tenant. Rejected: tenants
shouldn't have to know about jlsm's internal rekey mechanics; the
sentinel proof-of-control is simpler and more auditable.

### Most dangerous assumption

**That tenant operators will call the rekey API before revoking the
old CMK.** If they don't, jlsm enters grace-read-only then failed;
data may become permanently undecryptable if the old CMK was deleted
(not just disabled) without a rekey.

Mitigation for this assumption: clearly documented runbook; polling
opt-in for deployers who cannot rely on tenant operator discipline.
The failure mode is loud (clear exception, observable state
transition) rather than silent.

## Confidence

Medium-high. The design is tightly constrained by ADR A. Residual
uncertainty: whether the 1h grace window and N=5 threshold are the
right defaults, and whether the explicit-decommission deferral will
bite compliance teams who expect it as first-class.
