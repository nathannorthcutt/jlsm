---
problem: "pre-ga-format-deprecation-policy"
requested: "2026-04-24"
status: "pending"
---

# Research Brief — pre-ga-format-deprecation-policy

## Context

The Architect is evaluating options for a jlsm-wide deprecation policy
governing all versioned on-disk and on-wire artefacts (SSTable formats,
WAL segments, catalog `table.meta`, ciphertext envelope, document
serializer, transport framing). The policy must work both pre-GA
(zero on-disk users; eager delete safe) and post-GA (users have data
in old formats; migration must be transparent or operator-actionable).

The KB has strong analogical coverage on adjacent topics
(`encryption-key-rotation-patterns`, `dispatch-discriminant-corruption-
bypass`, `version-discovery-self-only-no-external-cross-check`,
`catalog-persistence-patterns`) but no direct entry on format-version
deprecation strategies. This brief commissions that entry.

Binding constraints for this evaluation:
- **Automatic forward migration path required** (writer at v_N
  encountering v_{N-k} produces a v_N rewrite without operator
  intervention; primary vector is compaction)
- **No en-masse auto-rewrite without explicit operator request** (the
  bounded background sweep stays bounded; cascading allowed; bulk
  rewrites require explicit operator command)
- **≥ 1 major release cycle** deprecation window post-GA after write
  support drops
- **Cross-artefact uniform application** — policy applies to SSTable,
  WAL, catalog, envelope, serializer uniformly; rules out
  per-artefact-bespoke approaches
- **Read-only past-window = hard error** — writable storage gets
  inline rewrite-on-read; read-only storage past window throws with
  diagnostic pointing to operator action
- **Atomic-commit invariant** — format-upgrade rewrites use
  per-writer tmp-path + atomic-move commit (per
  `sstable.end-to-end-integrity` R39); no direct overwrite
- **Cross-substrate uniformity** — the rewrite path works on
  FileChannel and remote NIO providers
- **Format-version downgrade-attack defence** — policy must specify
  whether intrinsic to this policy or delegated to per-format
  integrity specs

## Subjects Needed

### Format-version deprecation strategies in production database systems

- **Requested path:** `.kb/systems/database-engines/format-version-deprecation-strategies.md`
- **Why needed:** No direct KB coverage on the deprecation-policy
  question. This decision will govern jlsm format evolution
  permanently; production patterns from established databases
  provide the strongest evidence base for the policy mechanisms.

#### Production systems in scope

The KB entry should cover these systems' deprecation strategies:

1. **PostgreSQL `pg_upgrade`** — out-of-band one-shot upgrade utility;
   how the live binary's reader/writer compatibility window is bounded;
   what happens when a database is on a too-old format
2. **CockroachDB cluster-version-gate** — feature gates tied to a
   cluster-wide version variable; how cluster-version negotiation
   works; pre-version-bump validation; downgrade safety
3. **RocksDB format compatibility matrix** — declarative
   reader-version × writer-version compatibility table; how operators
   determine which upgrades are safe; format-version-byte in the
   manifest; post-upgrade rollback support
4. **MongoDB FeatureCompatibilityVersion (FCV)** — operator-set
   version floor that gates feature usage; how FCV transitions are
   coordinated; `setFeatureCompatibilityVersion` semantics; downgrade
   prerequisites
5. **MySQL versioned config / `mysql_upgrade`** — versioned config
   variables (e.g., `default_authentication_plugin`); the
   `mysql_upgrade` tool that fixes catalog discrepancies after binary
   bumps; `--upgrade` startup flag
6. **SQLite database file version + WAL format version** — minimalist
   approach where a single read/write version byte gates compatibility;
   what SQLite does NOT do (no migration tooling) and why that works
   for its model

#### Cross-cutting questions the entry must answer

The entry's organisation should support scoring the policy candidates
(A–E). Answer these directly:

- **Window length conventions**: how long do production systems carry
  read support after dropping write support? (one major, multiple
  majors, indefinite, configurable?)
- **Migration mechanism**: is the rewrite vector inline (during normal
  workload), background (separate sweep / autovacuum), out-of-band
  (separate utility), or operator-triggered (DDL command)?
- **Inventory and observability**: how do operators see "what versions
  exist on disk"? Is this a metric, a CLI command, a DDL query?
- **Downgrade-attack defence**: how do these systems defend against
  files claiming an older format than they should? What's the
  cross-check mechanism (manifest, catalog watermark, signed metadata,
  etc.)?
- **Cluster coordination**: in distributed systems (CockroachDB,
  MongoDB), how do all nodes agree on the active version? What
  happens when a node lags?
- **Read-only deployments**: how are read-only replicas / backups /
  WORM-mounted data handled when the live binary's deprecation
  window has moved past their files?
- **Crash safety**: are upgrades atomic (commit-or-bust), or can a
  partial upgrade leave the database in an inconsistent state?
- **Failure mode taxonomy**: what goes wrong with these strategies
  in production? What are the documented incidents / known footguns?

#### Sections most important for this decision

The entry must include these standard subsections:

- `## strategies-by-system` — one section per system above with the
  mechanism summary
- `## comparison-matrix` — a table comparing window length, rewrite
  vector, inventory mechanism, downgrade defence, cluster coordination
- `## patterns` — cross-cutting patterns (compaction-driven rewrite,
  cluster version negotiation, declarative compat matrix, out-of-band
  utility, FCV-style operator-controlled gate)
- `## tradeoffs` — when each pattern is preferred; what each costs
- `## anti-patterns` — known footguns from production incidents
  (silent format drift, downgrade bypass, partial upgrade hangs)
- `## practical-usage` — guidance for choosing a pattern given
  constraints similar to jlsm's profile

These map directly to the constraint dimensions used in evaluation.md.

## Commands to run

```
/research systems database-engines "format-version deprecation strategies in production database systems" context: "architect decision: pre-ga-format-deprecation-policy"
```

After research completes, re-run `/architect "pre-ga-format-deprecation-policy"`.
The architect re-survey will pick up the new entry and re-score candidates
D (cluster-version-gate) and E (out-of-band utility) with KB-backed
evidence rather than industry-knowledge inline citations.
