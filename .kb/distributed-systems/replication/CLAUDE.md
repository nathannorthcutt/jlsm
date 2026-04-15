# Replication — Category Index
*Topic: distributed-systems*
*Tags: catalog, metadata, schema, ddl, replication, gossip, consensus, epoch, online-schema-change, partition-map*

Replication strategies for metadata and catalog data in distributed storage
systems. Covers catalog authority models, schema change protocols, and
partition map distribution.

## Contents

| File | Subject | Status | Key Metric | Best For |
|------|---------|--------|------------|----------|
| [catalog-replication-strategies.md](catalog-replication-strategies.md) | Catalog and Metadata Replication Strategies | active | Raft catalog group + epoch cache | Distributed catalog with strong DDL consistency |

## Comparison Summary
<!-- Narrative comparison — write once 2+ subjects exist -->

## Recommended Reading Order
1. Start: [catalog-replication-strategies.md](catalog-replication-strategies.md) — consensus vs gossip vs epoch for catalog

## Research Gaps
- Data replication strategies (beyond catalog — covered in consensus/ for partition data)
- Change data capture (CDC) for replication streams
