---
title: "Blob Store Patterns for LSM-Tree Storage"
aliases: ["blob store", "BlobDB", "WiscKey", "key-value separation", "large object storage"]
topic: "systems"
category: "database-engines"
tags: ["blob", "large-object", "blobdb", "wisckey", "chunking", "key-value-separation", "gc"]
complexity:
  time_build: "O(blob size / chunk size) for chunked write"
  time_query: "O(1) reference lookup + O(chunks) for retrieval"
  space: "O(blob size) + O(reference) in document"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "systems/database-engines/schema-type-systems.md"
  - "systems/database-engines/corruption-detection-repair.md"
  - "systems/database-engines/pool-aware-sstable-block-sizing.md"
decision_refs: ["binary-field-type"]
sources:
  - url: "https://github.com/facebook/rocksdb/wiki/BlobDB"
    title: "RocksDB BlobDB Wiki"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://www.usenix.org/conference/fast16/technical-sessions/presentation/lu"
    title: "WiscKey: Separating Keys from Values in SSD-Conscious Storage (FAST '16)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://www.mongodb.com/docs/manual/core/gridfs/"
    title: "MongoDB GridFS Documentation"
    accessed: "2026-04-13"
    type: "docs"
---

# Blob Store Patterns for LSM-Tree Storage

## Problem

LSM trees are optimized for small-to-medium key-value pairs. Large values
(images, PDFs, video segments in the 1-50 MiB range) cause severe write
amplification: every compaction rewrites the full value even when only the
key or metadata changed. A 10 MiB value at write amplification factor 10
produces 100 MiB of I/O over the value's lifetime.

## Pattern 1: Key-Value Separation (RocksDB BlobDB / WiscKey)

The dominant approach: store keys and small values in the LSM tree, large
values in separate blob files. The LSM entry holds a blob reference (file ID
+ offset + size) instead of the value itself.

### Architecture

```
Document LSM:  key → { fields..., blob_ref: (blob_file_id, offset, size) }
Blob files:    append-only files, each written by one flush/compaction job
```

**RocksDB BlobDB** creates one blob file per background job (flush or
compaction). Blob files are immutable once written. Values at or above
`min_blob_size` are extracted; smaller values stay inline in SSTables.

**WiscKey** (Lu et al., FAST '16) uses a single append-only value log (vLog).
The LSM stores `key → (vLog_offset, size)`. Reads do a LSM lookup then a
random read into the vLog. On SSDs this is efficient because random read
latency is close to sequential.

### Inline vs External Threshold

The threshold determines when separation pays off. Below it, the indirection
cost (extra seek per read, GC overhead) exceeds the write amplification saved.

| System | Default Threshold | Configurable? |
|--------|------------------|---------------|
| RocksDB BlobDB | none (must set `min_blob_size`) | yes, per column family |
| Badger (WiscKey-inspired) | 1 KiB | yes |
| TiKV Titan | 1 KiB | yes |
| MongoDB GridFS | 16 MiB (BSON limit triggers GridFS) | chunk size: 255 KiB default |

**Rule of thumb**: separate when `value_size > 2 * SSTable_block_size`. For a
4 KiB block size, the crossover is ~8 KiB. For jlsm's 1-50 MiB blobs, all
values exceed any practical threshold — the design choice is whether to use
separate blob files or a dedicated LSM instance.

## Pattern 2: LSM-Backed Blob Store

Instead of a custom blob file format, use a second LSM tree instance where
`key = blob_id` and `value = blob_content` (or chunks thereof). This reuses
all existing LSM machinery: WAL for durability, compaction for space
reclamation, bloom filters for existence checks.

```
Document LSM:  doc_id → { fields..., blob_ref: blob_id }
Blob LSM:      blob_id → blob_content (or chunked: blob_id:chunk_n → chunk_data)
```

**Advantages**: no new file format, no new GC mechanism — tombstone-based
deletion and compaction handle cleanup. Bloom filter answers "does this blob
exist?" in O(1) for the idempotent write pattern.

**Disadvantage**: write amplification applies to the blob LSM too. Acceptable
for write-once-read-many blobs — they settle to the bottom level and stay.

## Pattern 3: Chunked Storage

For blobs exceeding available memory, chunking is required.

**Fixed-size chunks** (GridFS model): split at a fixed boundary (e.g., 1 MiB).
Simple, predictable. Key scheme: `blob_id:chunk_index`.

**Content-defined chunks** (Rabin fingerprint): boundaries determined by content
hash. Enables cross-blob dedup but adds significant complexity.

Fixed-size chunking is the pragmatic choice for an LSM-backed blob store. The
chunk size balances per-chunk overhead against streaming memory budget. At 1 MiB
chunks, a 50 MiB blob = 50 chunks.

```
Write:  for each chunk_n in split(blob, chunk_size):
          blob_lsm.put(blob_id + ":" + padded(chunk_n), chunk_data)
        blob_lsm.put(blob_id + ":meta", { size, chunk_count, content_hash })

Read:   meta = blob_lsm.get(blob_id + ":meta")
        for n in 0..meta.chunk_count:
          yield blob_lsm.get(blob_id + ":" + padded(n))
```

## Dual-Write Atomicity

The write sequence for a document with a blob field:

```
1. hash = content_hash(blob_bytes)
2. blob_id = derive_id(hash)          // content-addressed
3. if not blob_lsm.exists(blob_id):   // bloom filter check, fast
     write_chunked(blob_lsm, blob_id, blob_bytes)
4. doc_lsm.put(doc_id, { ..., blob_ref: blob_id })
```

**Crash analysis**: after step 3 but before 4 = orphaned blob (harmless, GC
reclaims). During step 3 = partial chunks (next attempt rewrites idempotently).
After step 4 = fully consistent. Content-addressing gives idempotency and
automatic deduplication when multiple documents reference the same content.

## Garbage Collection for Orphaned Blobs

Blobs become orphaned when the referencing document is deleted or updated.
Two strategies:

**Reference counting**: increment on doc insert, decrement on delete/update,
delete blob at zero. Immediate reclamation but crash-sensitive — a crash
between doc delete and refcount decrement leaks the count.

**Periodic GC scan**: build a live blob_id set from all documents, tombstone
any blob_id not in the set. O(documents + blobs) but self-healing — corrects
leaked refcounts and crash residue. Runs as a background task.

```
live_set = {}
for doc in doc_lsm.scan_all():
  if doc.has_blob_ref(): live_set.add(doc.blob_ref)
for blob_id in blob_lsm.scan_keys():
  if blob_id not in live_set: blob_lsm.delete(blob_id)
```

**Recommended**: periodic GC scan as the primary mechanism. Reference counting
can layer on top if immediate reclamation latency matters.

**RocksDB BlobDB** integrates GC with compaction via `blob_garbage_collection_age_cutoff`
(oldest 25% of blob files are candidates) and a force threshold that triggers
targeted compaction when a blob file's garbage ratio is too high.

## Streaming Read/Write

Multi-MiB blobs must not be materialized fully in memory. The chunked model
supports streaming naturally: write accepts a byte channel and flushes each
chunk immediately; read returns a channel that fetches chunks lazily. Peak
memory per concurrent blob operation = one chunk buffer. With 1 MiB chunks
and 10 concurrent streams, peak blob I/O memory is ~10 MiB.

## System Comparison

| System | Model | GC | Threshold |
|--------|-------|-----|-----------|
| RocksDB BlobDB | Blob files alongside SSTs | Compaction-integrated | Configurable `min_blob_size` |
| WiscKey | Append-only vLog | Tail-based vLog GC | All values separated |
| TiKV Titan | Blob files (BlobDB-like) | Compaction-integrated | ~1 KiB default |
| MongoDB GridFS | Chunks collection (255 KiB) | Manual / TTL | 16 MiB (BSON limit) |
| Badger | vLog (WiscKey-like) | vLog GC + move-on-read | 1 KiB default |

## Key Takeaways for jlsm

1. **LSM-backed blob store** reuses all existing primitives (WAL, compaction,
   bloom filter). No new file format needed.
2. **Content-addressed blob IDs** give idempotent writes and free dedup.
3. **Fixed-size chunking at 1 MiB** balances streaming memory budget against
   per-chunk overhead for the 1-50 MiB target range.
4. **Periodic GC scan** is the simplest correct approach — orphaned blobs are
   harmless until reclaimed and compaction handles physical deletion.
5. **Dual-write order** (blob first, document second) ensures crash safety
   with only harmless orphan potential.
