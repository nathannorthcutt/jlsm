---
problem: "binary-field-type"
date: "2026-04-13"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-table/src/main/java/jlsm/table/FieldType.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/DocumentSerializer.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/JlsmDocument.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/FieldEncryptionDispatch.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/IndexRegistry.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/FieldValueCodec.java"
  - "modules/jlsm-table/src/main/java/jlsm/table/internal/JsonValueAdapter.java"
---

# ADR — Binary Field Type with BlobStore SPI

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| Schema Type Systems | Type taxonomy, binary storage patterns | [`.kb/systems/database-engines/schema-type-systems.md`](../../.kb/systems/database-engines/schema-type-systems.md) |
| Blob Store Patterns | LSM-backed blob store, chunking, GC, dual-write | [`.kb/systems/database-engines/blob-store-patterns.md`](../../.kb/systems/database-engines/blob-store-patterns.md) |

---

## Files Constrained by This Decision

- `FieldType.java` — new `Binary(OptionalInt maxLength)` record as 6th sealed permit
- `DocumentSerializer.java` — Binary fields serialize as fixed-size BlobRef (not inline bytes)
- `JlsmDocument.java` — Binary field values are BlobRef instances, validated at construction
- `FieldEncryptionDispatch.java` — Binary fields use AES-GCM only (no DET/OPE)
- `IndexRegistry.java` — Binary fields support equality index via content hash only (no range)
- `FieldValueCodec.java` — BlobRef encoding for index keys (hash-based)
- `JsonValueAdapter.java` — Binary field JSON representation (base64 or ref ID)

## Problem
Add a binary/byte[] field type to jlsm-table's sealed FieldType hierarchy so
documents can store raw byte data (images, video, PDFs, document chunks)
alongside vector embeddings. Must support both small blobs and large objects
(1-50 MiB) without OOM, while keeping the document serialization format clean.

## Constraints That Drove This Decision
- **Large object support (Scale, weight 3)**: Primary use case is images/video
  stored alongside vector embeddings. Multi-MiB payloads cannot be stored inline
  in the document's byte[] buffer.
- **Lossless round-trip + format safety (Accuracy, weight 3)**: Binary values must
  be distinguishable from encrypted byte[] ciphertext in the serialization format.
  Backward-compatible format evolution required.
- **Composable storage (Fit, weight 2)**: jlsm is a composable library —
  prescribing a specific blob storage backend violates the design philosophy.
  Callers provide implementations through SPIs.

## Decision
**Chosen approach: Binary sealed permit + opaque BlobRef + BlobStore SPI**

Add `record Binary(OptionalInt maxLength) implements FieldType` as the 6th
sealed permit. Binary fields store an opaque `BlobRef` in the document — never
inline bytes. Actual blob content is managed by a caller-provided `BlobStore`
implementation. jlsm defines the SPI contract; consumers wire in their storage
backend (LSM-backed, S3 direct, local file, or custom).

## Rationale

### Why Binary sealed permit + BlobStore SPI
- **Compile-time safety**: Sealed exhaustiveness ensures no switch site forgets
  Binary. `instanceof Binary` cleanly separates binary fields from other types
  at encryption dispatch and index validation — same pattern as BoundedString.
- **No inline byte[] for large objects**: Documents store only a BlobRef (~32-64
  bytes). The serializer never materializes multi-MiB payloads. This solves the
  OOM risk identified during falsification (DocumentSerializer's byte[] buffer).
- **Composable**: BlobStore is an SPI like WAL, MemTableFactory, and WriterFactory.
  Callers choose their backend:
  - LSM-backed (default implementation, uses existing primitives)
  - S3/GCS direct upload (no double-write through LSM to object storage)
  - Local filesystem (embedded use cases)
  - Hybrid (route by blob size or deployment environment)
- **Content-addressable by default**: `BlobRef = SHA-256(content)` gives free
  deduplication and idempotent writes. Not mandated — UUID refs also supported.
- **Streaming**: BlobStore SPI uses `ReadableByteChannel`/`WritableByteChannel`,
  never `byte[]`. Peak memory per blob operation = one chunk buffer.

### Why not Binary as Primitive enum value
- No compile-time distinction from other Primitives — runtime checks needed at
  every site where binary behavior differs (serialization, encryption, indexing).
- No parameterization (maxLength). Not a "scalar" in the same sense as INT32.

### Why not inline-only Binary (no BlobStore)
- DocumentSerializer materializes full documents as `byte[]`. A 50 MiB image
  inline in the document causes OOM under memory pressure, especially during
  compaction. Falsification proved this conclusively.

### Why not prescribed LSM-backed blob store (previous revision)
- Over-specifies storage in the type system ADR. Object storage deployments
  should upload blobs directly to S3 — not double-write through an intermediate
  LSM then to S3. The BlobStore SPI decouples the type system from storage.

## Implementation Guidance

### FieldType.java
```
sealed interface FieldType permits Primitive, ArrayType, ObjectType,
                                    VectorType, BoundedString, Binary {

    record Binary(OptionalInt maxLength) implements FieldType {
        Binary { /* validate maxLength if present */ }
    }

    static FieldType binary() { return new Binary(OptionalInt.empty()); }
    static FieldType binary(int maxLength) {
        return new Binary(OptionalInt.of(maxLength));
    }
}
```

### BlobStore SPI
```
interface BlobStore {
    BlobRef store(ReadableByteChannel content, BlobMetadata meta);
    ReadableByteChannel retrieve(BlobRef ref);
    boolean exists(BlobRef ref);
    void delete(BlobRef ref);
}

record BlobRef(byte[] id) { /* opaque reference, typically SHA-256 hash */ }
record BlobMetadata(long size, String contentType, OptionalInt chunkSize) {}
```

### Switch site pattern
```
// Non-specialized sites (most):
case Binary _ -> handleAsOpaque(blobRef);

// Encryption dispatch:
case Binary _ -> EncryptionSpec.aesGcm();  // never DET/OPE

// Index validation:
case Binary _ -> IndexType.EQUALITY;  // hash-based only, no range
```

### Write path
```
1. Application: blobRef = blobStore.store(channel, metadata)
2. Application: doc.set("image", blobRef)  // Binary field
3. Table: doc write stores BlobRef (32-64 bytes) in document record
```

### GC contract
```
// Table engine provides live refs; BlobStore deletes dead ones
Set<BlobRef> liveRefs = table.scanAllBlobRefs();
blobStore.retainOnly(liveRefs);
```

## What This Decision Does NOT Solve
- **Default LSM-backed BlobStore implementation** — a reference implementation
  using jlsm's existing LSM primitives (chunked storage, content-addressed keys,
  periodic GC scan). Separate feature.
- **Blob storage strategy for object storage backends** — whether blobs on S3/GCS
  should be separate objects, co-located with SSTables, or use a hybrid approach.
  Different cost model from local storage.
- **Blob streaming API design** — the chunked upload/download interface contract,
  progress callbacks, resumable uploads.
- **Cross-node blob replication** — when partitioning is added, how blob refs
  resolve across nodes.
- **Inline small blob optimization** — a BlobStore wrapper that stores values
  below a threshold inline in the document rather than externally. Could be
  added as a decorator over any BlobStore implementation.

## Conditions for Revision
This ADR should be re-evaluated if:
- The BlobStore SPI proves too abstract — if every implementation needs the same
  chunking/GC logic, a concrete base class may be better than a bare interface
- Inline small blobs become a performance requirement (every small blob requiring
  a BlobStore round-trip adds latency for use cases like encryption artifacts)
- The content-addressing default proves problematic (e.g., if the same content
  must be stored under different refs for access control reasons)

---
*Confirmed by: user deliberation (3 rounds — initial recommendation challenged,
scope widened to include blob store, then revised to SPI) | Date: 2026-04-13*
*Full scoring: [evaluation.md](evaluation.md)*
