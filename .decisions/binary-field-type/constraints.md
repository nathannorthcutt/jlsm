---
problem: "Binary field type for raw byte data in jlsm-table document model"
slug: "binary-field-type"
captured: "2026-04-13"
status: "draft"
---

# Constraint Profile — binary-field-type

## Problem Statement
Add a binary/byte[] field type to jlsm-table's sealed FieldType hierarchy so
documents can store raw byte data alongside other fields. Primary use case:
storing images, videos, and document chunks alongside their vector embeddings
for search-and-return workflows.

## Constraints

### Scale
Must support both small blobs (document chunks, hashes, encryption artifacts,
typically < 1 KiB) and large objects (images 1-50 MiB, video segments, PDFs).
The image/vector co-location pattern — store original content alongside its
embedding vectors — is the primary driver.

### Resources
Pure Java 25, no external dependencies. Must work within existing
ArenaBufferPool memory budget. Large blobs must not cause OOM during
serialization/deserialization or compaction.

### Complexity Budget
Weight 1 (expert team). BoundedString sealed-permit pattern is established —
Binary follows the same mechanical pattern for the type system. The storage
layer for large objects may be more complex.

### Accuracy / Correctness
Lossless byte[] round-trip required. Binary data must survive
serialize → store → deserialize without any transformation. No encoding,
no interpretation — raw bytes in, raw bytes out.

### Operational Requirements
Serialization format must support partial deserialization — reading non-binary
fields without deserializing large binary payloads. This is critical for
queries that filter/project on metadata fields but don't need the blob.

### Fit
Must integrate with: sealed FieldType hierarchy (~10 switch sites),
DocumentSerializer (binary format), FieldValueCodec (index encoding),
FieldEncryptionDispatch (encryption — AES-GCM only for binary, no DET/OPE),
IndexRegistry (equality index via hash, no range indexing on binary).

## Key Constraints (most narrowing)
1. **Large object support** — must handle multi-MiB payloads without OOM,
   which means the serialization format must support skip/stream semantics
2. **Partial deserialization** — queries that don't project binary fields
   must not pay the cost of reading them
3. **Fit with sealed hierarchy** — must follow the established BoundedString
   pattern to minimize switch-site churn

## Constraint Falsification — 2026-04-13

Checked: 5 specs (F06, F10, F12, F13, F14), 1 ADR (bounded-string-field-type),
1 KB article (schema-type-systems.md)

Implied constraints added:
- **Accuracy**: F06-R20 — on-disk binary format backward compatibility. Adding
  Binary requires either a format version bump or a backward-compatible encoding.
- **Fit**: F14-R18 — byte[] already used for encrypted ciphertext. Binary field
  values must be distinguishable from encrypted byte[] in the serialization format.
- **Accuracy**: F06-R14 — serialize/deserialize round-trip equality must hold for
  byte[] values on Binary fields.

## Unknown / Not Specified
- Maximum binary field size limit (unbounded? or configurable cap?)
- Whether streaming upload/download API is in scope for this decision or
  deferred as a separate concern (large object storage mechanics)
