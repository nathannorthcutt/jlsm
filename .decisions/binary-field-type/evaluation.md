---
problem: "binary-field-type"
evaluated: "2026-04-13"
candidates:
  - path: "design-approach"
    name: "Binary sealed permit (parameterized)"
  - path: "design-approach"
    name: "Binary as Primitive enum value"
  - path: "design-approach"
    name: "BlobRef reference type"
constraint_weights:
  scale: 3
  resources: 2
  complexity: 1
  accuracy: 3
  operational: 3
  fit: 2
---

# Evaluation — binary-field-type

## References
- Constraints: [constraints.md](constraints.md)
- KB source: [`.kb/systems/database-engines/schema-type-systems.md`](../../.kb/systems/database-engines/schema-type-systems.md) §binary-field-storage

## Constraint Summary
The binary field must support both small inline blobs and large multi-MiB payloads
(images/video alongside vector embeddings). Serialization must allow skipping binary
payloads during partial deserialization. The type must be distinguishable from
encrypted byte[] ciphertext in the format. Backward-compatible format evolution required.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 3 | Large objects are the primary use case — must not break at multi-MiB |
| Resources | 2 | ArenaBufferPool budget matters for large blobs but not the deciding factor |
| Complexity | 1 | Expert team, established pattern |
| Accuracy | 3 | Lossless round-trip + format backward compatibility are hard requirements |
| Operational | 3 | Partial deserialization (skip binary) is critical for query performance |
| Fit | 2 | Must integrate but team can handle switch-site churn |

---

## Candidate: Binary sealed permit (parameterized)

**Design:** `record Binary(OptionalInt maxLength)` as 6th sealed permit of FieldType.
Factory: `FieldType.binary()` (unbounded) and `FieldType.binary(int maxLength)` (bounded).
Serialization: length-prefixed byte[] with a Binary type tag distinguishing from
encrypted fields. Type tag enables partial deserialization (skip N bytes).

**KB source:** [schema-type-systems.md §binary-field-storage](../../.kb/systems/database-engines/schema-type-systems.md)

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 3 | 5 | 15 | Length-prefix enables streaming; optional maxLength provides validation for bounded use cases |
|  |  |  |  | **Would be a 2 if:** values exceed 2 GiB (int length prefix overflow) |
| Resources | 2 | 4 | 8 | Inline storage for small blobs; large blobs use same MemorySegment path as other fields |
|  |  |  |  | **Would be a 2 if:** large blobs must be held fully in heap during serialization with no streaming |
| Complexity | 1 | 4 | 4 | Follows exact BoundedString pattern — ~10 switch arms, all trivial |
|  |  |  |  | **Would be a 2 if:** every switch site needed non-trivial Binary handling |
| Accuracy | 3 | 5 | 15 | Raw byte[] in/out, type-tagged in format, distinguishable from encrypted fields |
|  |  |  |  | **Would be a 2 if:** the type tag conflicted with an existing format byte |
| Operational | 3 | 5 | 15 | Length prefix at field start enables exact skip without reading payload |
|  |  |  |  | **Would be a 2 if:** the serialization format required reading the full payload to find the next field |
| Fit | 2 | 4 | 8 | Sealed permit with optional parameter; encryption dispatch returns AES-GCM only |
|  |  |  |  | **Would be a 2 if:** OptionalInt caused boxing overhead in hot paths |
| **Total** | | | **65** | |

**Hard disqualifiers:** None.

**Key strengths:**
- Follows established BoundedString precedent exactly
- Type-tagged serialization enables partial deserialization and encrypted/binary distinction
- Optional maxLength supports both bounded (document chunks) and unbounded (large objects)

**Key weaknesses:**
- Another sealed permit adds ~10 switch arms (mechanical but still churn)
- OptionalInt parameter slightly more complex than BoundedString's plain int

---

## Candidate: Binary as Primitive enum value

**Design:** Add `BINARY` to the Primitive enum. No new sealed permit — existing
`case Primitive p` arms handle it automatically (with a runtime check for binary-specific behavior).

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 3 | 3 | 9 | No parameterization — can't express max length for bounded use cases |
| Resources | 2 | 4 | 8 | Same as sealed permit for storage |
| Complexity | 1 | 5 | 5 | Zero new switch arms — BINARY is just another Primitive case |
| Accuracy | 3 | 3 | 9 | Must use runtime checks to distinguish BINARY from other Primitives in codec; no compile-time safety |
| Operational | 3 | 3 | 9 | Partial deserialization requires runtime type check inside Primitive handling |
| Fit | 2 | 3 | 6 | Primitives are unparameterized; binary needs length info for serialization |
| **Total** | | | **46** | |

**Hard disqualifiers:** None, but significant friction.

**Key strengths:**
- Zero switch-site churn — BINARY is handled by existing Primitive arms
- Simplest possible change

**Key weaknesses:**
- No compile-time distinction: `case Primitive p` arms must runtime-check `p == BINARY`
  at every site where binary differs from other scalars (serialization, encryption, indexing)
- Can't parameterize with maxLength — loses the bounded validation capability
- Muddies the Primitive enum semantics (BINARY is not a "scalar" in the same way as INT32)

---

## Candidate: BlobRef reference type

**Design:** `record BlobRef(String blobId)` — documents store only a reference ID.
Actual bytes live in a separate blob store. Always external.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|---------|
| Scale | 3 | 4 | 12 | Handles arbitrarily large objects via external storage |
|  |  |  |  | **Would be a 2 if:** the blob store doesn't exist yet (it doesn't) |
| Resources | 2 | 5 | 10 | Documents stay small — blobs never inline |
| Complexity | 1 | 2 | 2 | Requires building a blob store before this field type is usable |
| Accuracy | 3 | 4 | 12 | Round-trip correct if blob store is reliable |
|  |  |  |  | **Would be a 2 if:** blob store has different durability guarantees than SSTable |
| Operational | 3 | 2 | 6 | Separate blob retrieval for every binary read — extra I/O hop |
| Fit | 2 | 2 | 4 | Doesn't follow FieldType pattern — BlobRef is a reference, not a value |
| **Total** | | | **46** | |

**Hard disqualifiers:** Requires blob store infrastructure that doesn't exist.

**Key strengths:**
- Clean separation of concerns — documents stay small
- Handles arbitrarily large objects

**Key weaknesses:**
- Requires a blob store to be built first — this is a full feature, not a minor design
- Two-phase read (document + blob) adds latency and complexity
- Doesn't fit the FieldType value-semantics model

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| Binary sealed permit | 15 | 8 | 4 | 15 | 15 | 8 | **65** |
| Primitive enum | 9 | 8 | 5 | 9 | 9 | 6 | **46** |
| BlobRef reference | 12 | 10 | 2 | 12 | 6 | 4 | **46** |

## Preliminary Recommendation
Binary sealed permit wins decisively (65 vs 46/46). It follows the established
BoundedString pattern, provides compile-time type safety, enables partial
deserialization via length-prefix, and handles both small and large payloads
without requiring external infrastructure.
