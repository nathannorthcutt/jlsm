---
title: "Client-Side Encryption Patterns and SDK Design"
aliases: ["CSFLE", "client-side encryption", "encryption SDK", "pre-encrypted documents"]
topic: "systems"
category: "security"
tags: ["csfle", "client-encryption", "sdk", "key-distribution", "pre-encrypted", "envelope", "queryable"]
complexity:
  time_build: "N/A"
  time_query: "O(1) per field encrypt/decrypt"
  space: "O(fields) key metadata"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "systems/security/jvm-key-handling-patterns.md"
  - "systems/security/encryption-key-rotation-patterns.md"
  - "algorithms/encryption/searchable-encryption-schemes.md"
decision_refs: ["client-side-encryption-sdk", "pre-encrypted-flag-persistence", "per-field-pre-encryption"]
sources:
  - url: "https://www.mongodb.com/docs/manual/core/csfle/"
    title: "MongoDB CSFLE Overview"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://docs.aws.amazon.com/database-encryption-sdk/latest/devguide/client-server-side.html"
    title: "AWS Database Encryption SDK — Client vs Server-Side"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://www.mongodb.com/docs/manual/core/queryable-encryption/"
    title: "MongoDB Queryable Encryption Overview"
    accessed: "2026-04-13"
    type: "docs"
---

# Client-Side Encryption Patterns and SDK Design

## summary

Client-side field-level encryption (CSFLE) encrypts individual document fields
before they reach the database. The server stores opaque ciphertext and never
sees plaintext for protected fields. This article covers the SDK design pattern,
how databases detect pre-encrypted content, key distribution via envelope
encryption, and the extension to queryable encryption.

## core pattern: schema-driven field encryption

The dominant model (MongoDB CSFLE, AWS Database Encryption SDK) uses a
schema annotation to declare which fields are encrypted and with what algorithm.
The SDK intercepts writes and reads, encrypting/decrypting transparently.

```
// Schema declaration (pseudocode)
schema = {
  "ssn":     { encrypt: DET, keyId: "key-ssn" },
  "salary":  { encrypt: RND, keyId: "key-salary" },
  "name":    { encrypt: NONE }
}

// Write path — SDK intercepts before storage
fn sdk_encrypt(doc, schema, keyVault):
  for field, spec in schema:
    if spec.encrypt == NONE: continue
    dek = keyVault.getOrCreate(spec.keyId)
    ciphertext = encrypt(dek, doc[field], spec.encrypt)
    doc[field] = wrap_as_encrypted_blob(ciphertext, spec.keyId, spec.encrypt)
  return doc

// Read path — SDK intercepts after retrieval
fn sdk_decrypt(doc, schema, keyVault):
  for field, spec in schema:
    if spec.encrypt == NONE: continue
    blob = unwrap_encrypted_blob(doc[field])
    dek = keyVault.get(blob.keyId)
    doc[field] = decrypt(dek, blob.ciphertext, blob.algorithm)
  return doc
```

Algorithm choice per field:
- **DET** (deterministic, e.g. AES-SIV): enables equality queries; same
  plaintext always produces same ciphertext under same key. See
  `algorithms/encryption/searchable-encryption-schemes.md`.
- **RND** (randomized, e.g. AES-GCM): strongest confidentiality; no query
  support. Each encryption produces unique ciphertext.

## pre-encrypted document detection

The server must distinguish encrypted fields from plaintext without knowing the
key. Two production approaches exist:

**1. Type-tagged binary wrapper (MongoDB approach)**
MongoDB wraps each encrypted field in a BinData subtype 6 blob with a
fixed header:

```
| 1B subtype | 16B key-UUID | 1B algorithm | 1B original-BSON-type | ciphertext... |
```

The driver checks subtype on read; the server recognizes subtype 6 as
opaque and skips validation of the inner value. This is the cleanest
approach when the storage format supports typed binary.

**2. Attribute-level metadata flag (DynamoDB approach)**
AWS Database Encryption SDK stores a material description attribute alongside
the item. The SDK checks for this attribute on read to determine which fields
are encrypted. Primary key fields are never encrypted (they must remain
queryable by the server). Encrypted attributes appear as ordinary binary
values to DynamoDB.

**Key design choice for jlsm**: a per-field metadata byte in the serialized
document (analogous to MongoDB's subtype byte) is the most natural fit. The
byte encodes: `0x00` = plaintext, `0x01` = DET-encrypted, `0x02` =
RND-encrypted, with the key identifier following as a fixed-length UUID.

## envelope encryption and key distribution

All production CSFLE systems use two-tier envelope encryption:

```
CMK (Customer Master Key)       — lives in KMS, never leaves
 |
 +-- wraps --> DEK (Data Encryption Key)  — encrypted at rest in KeyVault
                |
                +-- encrypts --> field values
```

**KeyVault abstraction** (pseudocode):

```
interface KeyVault:
  fn getOrCreate(keyId) -> DEK:
    if cache.has(keyId): return cache.get(keyId)
    encryptedDek = store.load(keyId)
    dek = kmsClient.decrypt(encryptedDek.wrappedKey, encryptedDek.cmkId)
    cache.put(keyId, dek, ttl=5min)
    return dek

  fn createKey(cmkId, keyId) -> DEK:
    dek = generateRandom(256 bits)
    wrappedKey = kmsClient.encrypt(dek, cmkId)
    store.save(keyId, wrappedKey, cmkId)
    return dek
```

Key distribution flow:
1. Application configures a CMK reference (KMS ARN, Vault path, etc.)
2. On first use of a keyId, the SDK generates a random DEK, wraps it with
   the CMK via KMS, and stores the wrapped DEK in the KeyVault collection
3. On subsequent use, the SDK loads the wrapped DEK and unwraps via KMS
4. DEKs are cached in memory for a bounded TTL to avoid per-field KMS calls
5. Key rotation replaces the CMK; DEKs are re-wrapped lazily (see
   `encryption-key-rotation-patterns.md` for compaction-driven strategies)

For JVM implementations, DEK material in memory should follow the patterns
in `jvm-key-handling-patterns.md` (off-heap Arena storage, zeroing on close).

## queryable encryption

MongoDB Queryable Encryption extends CSFLE to allow the server to evaluate
equality and range predicates on encrypted fields without decryption:

- Client encrypts the field value and also produces an **encrypted index
  token** derived from the plaintext
- Server stores both the ciphertext and the index token
- Queries encrypt the search term into a matching token; the server matches
  tokens without seeing plaintext
- A **contention factor** controls how many distinct tokens map to the same
  plaintext, trading query performance for frequency-hiding security

This is an application of structured encryption; the underlying schemes are
covered in `algorithms/encryption/searchable-encryption-schemes.md` (SSE,
DET) and `algorithms/encryption/prefix-fuzzy-searchable-encryption.md`.

## how systems do it

| System | Model | Key Tier | Encrypted Field Detection | Query Support |
|--------|-------|----------|--------------------------|---------------|
| MongoDB CSFLE | Schema-driven auto-encrypt | CMK + DEK via KMS | BinData subtype 6 | None (opaque) |
| MongoDB QE | Schema-driven + index tokens | CMK + DEK via KMS | BinData subtype 6 | Equality, range |
| AWS DB Enc SDK | Attribute-action config | Keyring wrapping keys | Material description attr | None (primary key only) |
| Google Tink | Primitive-based (AEAD/DET) | KEK + DEK | Caller-managed | Via DET primitive |
| CipherStash | Schema annotations | KMS-wrapped keys | Encrypted index columns | Equality, range, match |

## design implications for jlsm

1. **Schema-driven encryption config**: `JlsmSchema` field definitions should
   carry an optional encryption spec (algorithm + keyId). The serializer
   checks this spec and delegates to the encryption layer before writing.

2. **Per-field type byte**: the serialized document format needs a 1-byte
   encryption marker per field. This avoids a separate metadata structure
   and lets the deserializer detect encrypted fields without the schema.

3. **KeyVault as caller-provided interface**: the library should define a
   `KeyVault` interface (resolve keyId to DEK material) but never implement
   KMS integration directly. Callers inject their KMS-backed implementation.

4. **No plaintext key persistence**: DEKs exist in memory only as
   Arena-backed segments with bounded lifetime. The library stores only
   wrapped (encrypted) DEK blobs. See `jvm-key-handling-patterns.md`.

5. **Queryable fields use DET path**: fields marked as queryable-encrypted
   go through the DET encryption path to produce index-compatible ciphertext.
   Fields marked RND are stored as opaque blobs with no index entry.
