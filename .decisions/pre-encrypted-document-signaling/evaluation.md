---
problem: "pre-encrypted-document-signaling"
date: "2026-03-19"
version: 1
---

# Evaluation — Pre-Encrypted Document Signaling

## Candidates

### Candidate 1: Boolean field on JlsmDocument

Add a `private final boolean preEncrypted` field to JlsmDocument. Provide a second factory method `JlsmDocument.ofPreEncrypted(schema, nameValuePairs...)` that sets the flag. The existing `JlsmDocument.of(...)` defaults to `false`. The serializer reads the flag via `DocumentAccess` or a package-private getter.

**API surface:**
```java
JlsmDocument doc = JlsmDocument.ofPreEncrypted(schema, "email", encryptedEmail, ...);
```

| Dimension | Score (1-5) | Rationale |
|-----------|-------------|-----------|
| Scale | 5 | One boolean field — zero allocation overhead. Branch prediction on the fast path (preEncrypted=false) is near-free. |
| Resources | 5 | Single boolean adds 0 bytes to object layout (fits in existing alignment padding on 64-bit JVMs). No new classes. |
| Complexity | 5 | One new factory method, one boolean check in serialize(). Immediately obvious to consumers. |
| Accuracy | 4 | All-or-nothing enforced at serializer level. No compile-time prevention of setting the flag on a schema with no encrypted fields (but that is a no-op, not an error). |
| Operational | 5 | Fully backward compatible. Existing `of()` unchanged. Pre-encrypted documents use a different factory method — easy to audit. |
| Fit | 5 | JlsmDocument is already a final class with package-private internals. Adding a boolean field is trivial. DocumentAccess accessor can expose it. Serializer dispatch is a single `if` branch. |

**Total: 29/30**

### Candidate 2: Wrapper type (PreEncryptedDocument)

Create a new class `PreEncryptedDocument` that wraps a `JlsmDocument`. The serializer checks `instanceof PreEncryptedDocument` and dispatches accordingly. The wrapper delegates all getters to the inner document.

**API surface:**
```java
JlsmDocument inner = JlsmDocument.of(schema, "email", encryptedEmail, ...);
PreEncryptedDocument doc = new PreEncryptedDocument(inner);
```

| Dimension | Score (1-5) | Rationale |
|-----------|-------------|-----------|
| Scale | 4 | Extra object allocation per pre-encrypted document. instanceof check on the hot path. Minor but measurable at millions QPS. |
| Resources | 3 | New public class in the exported package. Wrapper must duplicate or delegate every typed getter. JlsmDocument is final, so the wrapper cannot extend it — must be a separate type, which means callers receiving documents cannot use a common type without introducing an interface. |
| Complexity | 2 | Forces a type split in the API. Callers that accept JlsmDocument must now accept either JlsmDocument or PreEncryptedDocument. This ripples through any code that processes documents generically (QueryExecutor, IndexRegistry, JSON serialization). |
| Accuracy | 5 | Type system enforces the signal — a PreEncryptedDocument cannot accidentally be treated as unencrypted. Strongest compile-time guarantee. |
| Operational | 2 | Not backward compatible without introducing a shared interface or making JlsmDocument non-final. Significant API disruption for a binary signal. |
| Fit | 2 | JlsmDocument is final. Every consumer that accepts JlsmDocument would need changes. DocumentAccess, JsonWriter, YamlWriter, IndexRegistry, QueryExecutor all operate on JlsmDocument directly. |

**Total: 18/30**

### Candidate 3: Metadata map

Add a `Map<String, Object> metadata` field to JlsmDocument. A well-known key `"pre-encrypted"` signals the pre-encrypted state. The serializer checks the metadata map for this key.

**API surface:**
```java
JlsmDocument doc = JlsmDocument.of(schema, "email", encryptedEmail, ...)
    .withMetadata(Map.of("pre-encrypted", true));
```

| Dimension | Score (1-5) | Rationale |
|-----------|-------------|-----------|
| Scale | 3 | Map allocation + hash lookup per serialize() call. The unencrypted path still pays the cost of checking an empty/null map. |
| Resources | 3 | A generic metadata map is over-engineered for a single boolean signal. Invites misuse — callers may stuff arbitrary data into the map, creating coupling. |
| Complexity | 2 | String-keyed lookup with no compile-time safety. Typos in the key name silently fail. Requires documenting the magic string. JlsmDocument is currently immutable (values set at construction) — adding a mutable metadata map or `withMetadata()` copy method changes the design philosophy. |
| Accuracy | 2 | No type safety on the value. Caller could put `"pre-encrypted" -> "yes"` instead of `true`. No compile-time enforcement. |
| Operational | 3 | Backward compatible (metadata defaults to empty map), but the unencrypted path now carries dead weight. The metadata map concept may invite scope creep. |
| Fit | 2 | JlsmDocument is designed as a tight, schema-validated value holder. A generic metadata map is architecturally inconsistent with its current design — it introduces an untyped, unvalidated escape hatch. |

**Total: 15/30**

### Candidate 4: Factory method pattern (JlsmDocument.preEncrypted(...))

A variant of Candidate 1 that uses a differently-named static factory to construct the pre-encrypted document. Internally identical to Candidate 1 (boolean field), but the naming convention makes the intent clearer at the call site.

**API surface:**
```java
JlsmDocument doc = JlsmDocument.preEncrypted(schema, "email", encryptedEmail, ...);
```

| Dimension | Score (1-5) | Rationale |
|-----------|-------------|-----------|
| Scale | 5 | Same as Candidate 1 — single boolean. |
| Resources | 5 | Same as Candidate 1 — no new classes. |
| Complexity | 5 | Same as Candidate 1. The factory name `preEncrypted` is self-documenting — reads naturally at the call site. |
| Accuracy | 4 | Same as Candidate 1. The factory name communicates intent but does not add compile-time safety beyond Candidate 1. |
| Operational | 5 | Same as Candidate 1. Fully backward compatible. |
| Fit | 5 | Same as Candidate 1. The factory name follows the existing `of()` pattern. |

**Total: 29/30**

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Total |
|-----------|-------|-----------|------------|----------|-------------|-----|-------|
| 1. Boolean field (`ofPreEncrypted`) | 5 | 5 | 5 | 4 | 5 | 5 | 29 |
| 2. Wrapper type | 4 | 3 | 2 | 5 | 2 | 2 | 18 |
| 3. Metadata map | 3 | 3 | 2 | 2 | 3 | 2 | 15 |
| 4. Factory method (`preEncrypted`) | 5 | 5 | 5 | 4 | 5 | 5 | 29 |

## Recommendation

**Candidate 4: Factory method pattern** — `JlsmDocument.preEncrypted(schema, nameValuePairs...)`

Candidates 1 and 4 are functionally identical (both use a boolean field internally). The tiebreaker is API ergonomics: `JlsmDocument.preEncrypted(schema, ...)` reads more naturally than `JlsmDocument.ofPreEncrypted(schema, ...)`. The factory name acts as documentation at the call site.

The wrapper type (Candidate 2) provides the strongest compile-time safety but at unacceptable cost: it requires restructuring every consumer of JlsmDocument across the codebase to handle a new type. The metadata map (Candidate 3) is over-engineered and architecturally inconsistent with JlsmDocument's design as a tight, schema-validated value holder.

### Serializer integration
- `SchemaSerializer.serialize()` reads `doc.isPreEncrypted()` (package-private getter, exposed via DocumentAccess)
- If `true`: skip the encrypt loop, instead validate each encrypted field's ciphertext length against the expected output size for that field's EncryptionSpec
- If `false`: encrypt as before (no behavioral change)
- Deserialization is unchanged — it always decrypts

### Validation on pre-encrypted write
For each field where `EncryptionSpec != NONE`:
- The serialized field bytes (before writing to the buffer) must match the expected ciphertext length for the scheme
- AES-GCM (Opaque): plaintext_len + 12 (IV) + 16 (tag)
- AES-SIV (Deterministic): plaintext_len + 16 (synthetic IV)
- OPE (OrderPreserving): always 8 bytes (long)
- DCPE (DistancePreserving): dimensions * 4 bytes (float array, same size as plaintext)
- Mismatch throws `IllegalArgumentException` with the field name, expected length, and actual length
