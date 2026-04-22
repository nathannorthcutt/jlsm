# jlsm Competitive Analysis

*Generated 2026-04-20 from spec inventory (48 specs, ~3,640 requirements)*

## What jlsm is when all specs are delivered

A fully encrypted, distributed, strongly consistent document database with
embedded vector search and SQL queries. It combines capabilities that today
require 4-5 separate systems:

| Layer | jlsm | Today you'd combine |
|-------|-------|---------------------|
| Storage | LSM with 3 codec layers, striped cache, CRC-32C integrity | RocksDB / LevelDB |
| Distribution | SWIM + Raft-per-partition + rebalancing + anti-entropy | Cassandra / CockroachDB |
| Transactions | Percolator cross-partition ACID, snapshot isolation | Spanner / CockroachDB |
| Encryption | Field-level query-preserving, key rotation, client-side SDK | Vault transit + app-layer |
| Search | SQL + full-text + vector similarity (float16/32, HNSW/IVF) | Lucene + Pinecone/Qdrant |

## Head-to-head comparison

### vs Lucene/Elasticsearch

jlsm converges SQL + vector + full-text into one query interface. Lucene's
full-text engine is decades mature (BM25, analyzers, tokenizers, facets,
suggesters, spatial). jlsm specs full-text as an integration point
(LsmFullTextIndex) — the depth of the text analysis pipeline isn't specified.
Lucene has no distribution, encryption, or transactions. Different trade-off.

### vs Cassandra

jlsm specs stronger consistency (Raft vs tunable quorum), ACID cross-partition
transactions (Cassandra only has lightweight transactions), and field-level
encryption Cassandra doesn't have. Cassandra has production-hardened operations
(repair, streaming, hinted handoff, read repair) refined over 15 years. jlsm's
operational maturity gaps: no hinted handoff spec, no streaming repair spec, no
read repair spec (F48 corruption repair covers anti-entropy but not the full
Cassandra repair toolkit).

### vs CockroachDB/Spanner

Similar transaction model (Percolator-style). CockroachDB has full SQL
compatibility (joins, CTEs, window functions, stored procedures). jlsm's SQL
spec covers SELECT/WHERE/ORDER/LIMIT/aggregates but no CTEs, window functions,
subqueries, or DDL. No cost-based query optimizer spec.

### vs Pinecone/Qdrant

jlsm's vector support is native (schema-declared VectorType, 5 similarity
functions, float16/32) but HNSW/IVF-Flat implementations are external module
integrations. Dedicated vector DBs have filtering, metadata, namespaces, hybrid
search, and billion-scale optimizations not in jlsm's specs.

## The moat

The encryption story is the strongest differentiator. No comparable system
offers field-level, query-preserving encryption with key rotation as a
first-class concern. Five encryption variants (None, Deterministic,
Order-Preserving, Distance-Preserving, Opaque) let each field choose its
security/queryability trade-off. The storage engine itself cannot read
plaintext values — only the client holding the key can.

This is the feature that justifies jlsm existing as a separate system rather
than using Cassandra + an encryption proxy.

## Gaps not covered by any spec

1. **Access control / RBAC** — no authentication, authorization, or role model
2. **Operational tooling** — no backup/restore, point-in-time recovery, admin API, metrics/observability
3. **Read repair / hinted handoff** — F48 covers anti-entropy but not Cassandra-style read repair or hinted handoff
4. **Query optimizer** — no cost-based plan selection between index scan, full scan, join strategies
5. **Full-text analysis pipeline** — tokenizers, analyzers, stemming, scoring (BM25/TF-IDF) are in the external jlsm-indexing module, not spec'd
6. **Advanced SQL** — no CTEs, window functions, subqueries, stored procedures, views, DDL
7. **Multi-region / geo-replication** — no cross-datacenter async replication or conflict resolution
8. **Schema evolution** — no online migration spec (add column, backfill, compatibility rules)

## Gap priority ordering

Ordered by: which gaps, if closed, eliminate the "just use X instead" argument
for the largest number of use cases.

### Tier 1 — Deployability (close these and nobody can say "just use Cassandra + Vault")

**1. Access control / RBAC**

Without this, jlsm can't be deployed in any multi-tenant or team environment.
Encryption without access control is like a vault with no lock on the door.
Every competitor has this. Table stakes.

**2. Operational tooling (backup/restore, PITR, admin API)**

The gap between "runs" and "can be operated." No one adopts a distributed
database they can't back up. Cassandra, CockroachDB, even Lucene have this.
Without it jlsm is a research project, not a deployable system.

**3. Read repair + hinted handoff**

What makes a distributed database reliable under partial failure, not just
available. Raft handles consensus but not the eventual-consistency repair paths
that keep replicas converged during degraded operation. Cassandra's operational
reputation is built on these. Without them, any node restart or network blip
risks silent divergence.

### Tier 2 — Query competitiveness (close these and the query story overtakes CockroachDB for document workloads)

**4. Query optimizer (cost-based)**

jlsm has SQL + 5 index types + vector + full-text — broader than CockroachDB
for document workloads. But without a cost-based optimizer, the engine can't
choose between index scan and full scan intelligently. At distributed scale,
this is the difference between 10ms and 10s queries.

**5. Full-text analysis pipeline**

The integration point exists but tokenizers, analyzers, stemming, and scoring
aren't spec'd. Separates "has full-text search" from "competes with
Elasticsearch." The encrypted search story (F46/F47 prefix + fuzzy) is already
unique — but users expect standard BM25 on unencrypted fields too.

**6. Advanced SQL (CTEs, window functions, subqueries)**

Matters for analytics workloads, not simple document CRUD. CockroachDB and
Spanner have them. Required if jlsm positions as "encrypted analytical
database." Nice-to-have if positioned as "encrypted document store with query."

### Tier 3 — Market expansion (close these to address enterprise/global deployments)

**7. Multi-region / geo-replication**

Expands from single-datacenter to global deployments. CockroachDB's primary
selling point. The encryption differentiator already justifies single-region
deployments where compliance is the driver.

**8. Schema evolution (online migration)**

Important for long-lived production systems but workarounds exist (dual-write,
application-level migration). Not a blocker for adoption, friction point for
retention.

## Summary

Tier 1 (access control + ops tooling + repair) turns jlsm from "impressive
spec" into "deployable encrypted database." The pitch becomes: "Everything
Cassandra does, but your data is encrypted at rest and in query, field by
field, with key rotation" — and there's no combination of existing tools that
matches that.
