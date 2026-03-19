---
problem: "field-encryption-api-design"
evaluated: "2026-03-18"
candidates:
  - path: "candidate-A-schema-annotation"
    name: "Schema Annotation (FieldDefinition carries EncryptionSpec)"
  - path: "candidate-B-encrypted-fieldtype"
    name: "Encrypted FieldType Variants"
  - path: "candidate-C-pluggable-encryptor"
    name: "Pluggable FieldEncryptor Interface"
constraint_weights:
  scale: 3
  resources: 1
  complexity: 1
  accuracy: 2
  operational: 2
  fit: 3
---

# Evaluation — field-encryption-api-design

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used:
  - [`.kb/algorithms/encryption/searchable-encryption-schemes.md`](../../.kb/algorithms/encryption/searchable-encryption-schemes.md)
  - [`.kb/algorithms/encryption/vector-encryption-approaches.md`](../../.kb/algorithms/encryption/vector-encryption-approaches.md)
  - [`.kb/systems/security/jvm-key-handling-patterns.md`](../../.kb/systems/security/jvm-key-handling-patterns.md)

## Constraint Summary
The API must express per-field encryption configuration within the existing sealed FieldType + FieldDefinition + JlsmSchema hierarchy without breaking the unencrypted API contract. Different field types need different encryption schemes (DET for equality, OPE for range, DCPE for vectors, AES-GCM for opaque). At millions of QPS, the encryption dispatch must be resolvable at schema-construction time (not per-document).

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 3 | Millions of QPS — encryption dispatch overhead directly impacts throughput |
| Resources | 1 | CPU-only is the only option anyway — no differentiation between candidates |
| Complexity | 1 | High team capability — all candidates are implementable |
| Accuracy | 2 | Encryption scheme selection affects query capability — API must make this clear |
| Operational | 2 | Graceful fallback, bounded ops, rebuild — API must expose encryption state |
| Fit | 3 | Must compose with sealed FieldType hierarchy, DocumentSerializer, indices |

---

## Candidate A: Schema Annotation (FieldDefinition carries EncryptionSpec)

Extend `FieldDefinition` from `record(name, type)` to `record(name, type, encryptionSpec)` where `encryptionSpec` is an optional sealed interface describing the encryption scheme. `EncryptionSpec` has permits: `None`, `Deterministic`, `OrderPreserving`, `DistancePreserving`, `Opaque`. The schema builder gets `.field("email", STRING, EncryptionSpec.deterministic())`. DocumentSerializer reads the spec at construction time and builds per-field encryptor/decryptor functions (same pattern as the FieldDecoder dispatch table). Keys are held in an `EncryptionKeyHolder` (Arena-backed) passed to the table builder, not embedded in the schema.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 3 | 5 | 15 | Dispatch table built at construction — zero per-document overhead beyond crypto ops |
| Resources | 1 | 4 | 4 | Uses javax.crypto + Arena — no external deps |
| Complexity | 1 | 4 | 4 | Straightforward extension of existing record pattern |
| Accuracy | 2 | 5 | 10 | EncryptionSpec type makes scheme choice explicit per field |
| Operational | 2 | 4 | 8 | Encryption state queryable from schema; key rotation = new table |
| Fit | 3 | 5 | 15 | FieldDefinition is a record — adding an optional component is natural; sealed FieldType unchanged; DocumentSerializer already has dispatch table pattern |
| **Total** | | | **56** | |

**Hard disqualifiers:** None

**Key strengths:**
- Encryption config lives in the schema where it belongs — visible, queryable, and type-safe
- FieldType sealed hierarchy unchanged — no new permits needed
- DocumentSerializer dispatch table pattern already proven (just-optimized in this session)
- `EncryptionSpec.NONE` as default means zero overhead for unencrypted fields

**Key weaknesses:**
- FieldDefinition changes from 2-field record to 3-field record — all existing code creating FieldDefinitions needs a default `EncryptionSpec.NONE`
- Schema serialization format must encode the encryption spec (backward-compatible extension)

---

## Candidate B: Encrypted FieldType Variants

Add encrypted variants to the sealed FieldType hierarchy: `FieldType.EncryptedPrimitive`, `FieldType.EncryptedVector`, etc. Each wraps the underlying type and carries encryption metadata. The sealed interface permits list grows. DocumentSerializer switches on the encrypted variants in its existing pattern-match.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 3 | 4 | 12 | Pattern match slightly deeper but still O(1) dispatch |
| Resources | 1 | 4 | 4 | Same crypto primitives as A |
| Complexity | 1 | 3 | 3 | Sealed interface expansion — every existing switch/pattern-match needs new cases |
| Accuracy | 2 | 4 | 8 | Type carries encryption but scheme-per-type conflation is awkward |
| Operational | 2 | 3 | 6 | Encrypted types complicate schema evolution — is EncryptedString compatible with String? |
| Fit | 3 | 2 | 6 | Breaks sealed permits list; every pattern match in DocumentSerializer, index code, query executor must add encrypted arms; FieldType is used pervasively |
| **Total** | | | **39** | |

**Hard disqualifiers:** None, but high friction

**Key strengths:**
- Type system enforces that encrypted fields are handled differently at compile time
- Clear in the API what is encrypted just from the FieldType

**Key weaknesses:**
- Sealed FieldType permits explosion — every existing type gets an encrypted variant
- Every `switch(type)` across the codebase needs new arms — DocumentSerializer, QueryExecutor, FieldIndex, SqlTranslator, all measure/encode/decode methods
- Conflates the data type (what the field holds) with the storage concern (how it's encrypted)

---

## Candidate C: Pluggable FieldEncryptor Interface

Define a `FieldEncryptor` interface with `encrypt(byte[])` / `decrypt(byte[])` methods. The table builder accepts a `Map<String, FieldEncryptor>` mapping field names to encryptors. DocumentSerializer receives the map and applies the encryptor per field during serialize/deserialize. No schema changes — encryption is wired at table construction time, not expressed in the schema.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 3 | 3 | 9 | Map lookup per field per document — cacheable but not precomputed in schema |
| Resources | 1 | 4 | 4 | Same crypto primitives |
| Complexity | 1 | 5 | 5 | Most flexible — any encryptor for any field, no type system changes |
| Accuracy | 2 | 3 | 6 | No compile-time connection between field type and encryption scheme — user can misconfigure (OPE on a string, DCPE on an int) |
| Operational | 2 | 3 | 6 | Encryption config not visible in schema — harder to reason about, diagnose, persist |
| Fit | 3 | 3 | 9 | No schema changes needed (pro), but encryption is invisible in the schema (con); index code can't know if a field is encrypted without external context |
| **Total** | | | **39** | |

**Hard disqualifiers:** None

**Key strengths:**
- Maximum flexibility — any encryption for any field, user-defined encryptors
- Zero schema changes — fully additive

**Key weaknesses:**
- Encryption invisible in schema — secondary indices can't adapt behavior based on field encryption without separate signaling
- Map<String, FieldEncryptor> lookup per field per document at millions of QPS
- No type safety — user can assign incompatible encryptors to field types

---

## Comparison Matrix

| Candidate | Scale (3) | Resources (1) | Complexity (1) | Accuracy (2) | Operational (2) | Fit (3) | Weighted Total |
|-----------|-----------|---------------|----------------|--------------|-----------------|---------|----------------|
| A: Schema Annotation | 15 | 4 | 4 | 10 | 8 | 15 | **56** |
| B: Encrypted FieldType | 12 | 4 | 3 | 8 | 6 | 6 | **39** |
| C: Pluggable Encryptor | 9 | 4 | 5 | 6 | 6 | 9 | **39** |

## Preliminary Recommendation
**Candidate A: Schema Annotation** wins decisively (56 vs 39). It composes naturally with the existing record-based type system, keeps encryption visible in the schema where indices can adapt, and the dispatch table pattern is already proven in DocumentSerializer. The FieldDefinition record extension is backward-compatible with a default `EncryptionSpec.NONE`.

## Risks and Open Questions
- FieldDefinition record change requires updating `JlsmSchema.Builder.field()` overloads — needs careful backward compatibility
- Schema binary format must encode EncryptionSpec — forward-compatible extension to the header
- The sealed `EncryptionSpec` interface must cover all encryption families without being too fine-grained
