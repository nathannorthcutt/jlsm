---
{
  "id": "query.encrypted-prefix-index",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "query"
  ],
  "requires": [
    "F03",
    "F41"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "encrypted-prefix-wildcard-queries",
    "encrypted-index-strategy"
  ],
  "kb_refs": [
    "algorithms/encryption/prefix-fuzzy-searchable-encryption",
    "algorithms/encryption/searchable-encryption-schemes"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F46"
  ]
}
---
# F46 -- Encrypted Prefix Index

## Requirements

### Prefix tokenization

R1. The `EncryptedPrefixIndexer` must generate all byte-level prefixes of a term from a configurable minimum prefix length up to the full term length. For a term of byte length L and minimum prefix length M, the indexer must produce exactly `L - M + 1` prefix tokens when `L >= M`, and zero tokens when `L < M`.

R2. The minimum prefix length must be configurable at construction time with a default of 3 bytes. The constructor must reject a minimum prefix length of zero or negative with `IllegalArgumentException`.

R3. The maximum prefix length must be configurable at construction time. When set, the indexer must produce prefixes from the minimum length up to `min(maxPrefixLength, termLength)`. The default maximum prefix length must be `Integer.MAX_VALUE` (no limit). The constructor must reject a maximum prefix length less than the minimum prefix length with `IllegalArgumentException`.

R4. Each prefix token must be encrypted using AES-SIV (deterministic encryption per F03 R26-R30) with the per-field derived key (F41 R9-R16c). The encryption must use the field name as associated data per F03 R21 to prevent cross-field prefix correlation.

R5. The term must be encoded as UTF-8 bytes before prefix extraction. Prefix boundaries must be byte-aligned, not character-aligned. A multi-byte UTF-8 character split at a prefix boundary is acceptable because prefix lookup uses the same byte-aligned tokenization on both index and query sides.

R6. The indexer must prepend the 4-byte DEK version tag (F41 R22) to each encrypted prefix token. All tokens from a single indexing operation must use the current active DEK version. The version must be captured once at the start of the indexing operation and used for all tokens in that operation.

### Inverted index integration

R7. Each encrypted prefix token must be stored in the `LsmInvertedIndex` using the existing composite key format: `[4-byte BE token length][encrypted token bytes][doc-id bytes]`. When using field-scoped inverted indexes (one per indexed field, per R18a), no structural changes to `LsmInvertedIndex` are required.

R8. The `EncryptedPrefixIndexer` must provide an `indexTerm(String term, byte[] docId)` method that produces the set of composite keys for all prefix tokens of the term. The method must return a `List<MemorySegment>` of composite keys ready for insertion.

R9. The `EncryptedPrefixIndexer` must provide an `indexTerms(Set<String> terms, byte[] docId)` method that tokenizes and encrypts all terms for a document. Duplicate prefix tokens across terms must be deduplicated (a prefix shared by multiple terms produces one index entry per document, not one per term).

R10. The indexer must reject a null term with `NullPointerException` and an empty string with `IllegalArgumentException`.

R11. The indexer must reject a null or empty `docId` with `NullPointerException` or `IllegalArgumentException` respectively.

### Query path

R12. The `EncryptedPrefixIndexer` must provide a `queryPrefix(String prefix)` method that encrypts the query prefix using the same AES-SIV key and field name as associated data. The result must be a single encrypted token suitable for exact lookup in the `LsmInvertedIndex`.

R13. The `queryPrefix` method must reject a query prefix shorter than the minimum prefix length with `IllegalArgumentException` stating the minimum required length.

R14. The `queryPrefix` method must reject a null prefix with `NullPointerException`.

R15. The `queryPrefix` method must prepend the 4-byte DEK version tag. The version tag must reference the current active DEK version, not the version used at index time. This means prefix lookups during a key rotation window may miss entries encrypted under the old DEK (a known limitation documented in R25).

### Key rotation behavior

R16. During a key rotation window (when the index contains prefix tokens encrypted under both old and new DEK versions), a prefix query using the current DEK version may return incomplete results. This is the same limitation as DET-indexed fields during rotation (F41 R36).

R16a. The `EncryptedPrefixIndexer` must expose an `isRotationPending()` method that returns `true` when the key registry contains DEK versions other than the current active version that are still referenced by live SSTables. This allows callers to detect the rotation window and decide whether to accept potentially incomplete results or wait for convergence.

R16b. The `EncryptedPrefixIndexer` must provide a `queryPrefixAllVersions(String prefix)` method that returns a `List<MemorySegment>` of encrypted tokens -- one per DEK version present in the key registry. The caller can query the inverted index with each token to obtain complete results during a rotation window. This method must reject a prefix shorter than the minimum prefix length with `IllegalArgumentException`.

R16c. To support `queryPrefixAllVersions`, the `EncryptedPrefixIndexer` must accept a reference to the key registry and the field's table name and field name at construction time. For each DEK version in the registry, the method must: (a) unwrap the DEK using the KEK, (b) construct a temporary `EncryptionKeyHolder` with the unwrapped DEK as master key, (c) derive the per-field key via `deriveFieldKey(tableName, fieldName)`, (d) encrypt the prefix with the derived key, and (e) close the temporary holder (which zeros the unwrapped DEK and derived key). Steps (a) through (e) must execute in a try-with-resources block. For the current active DEK version, the method must use the indexer's own pre-derived key (R24a) without constructing a temporary holder.

R17. After rotation converges (no live SSTables reference old DEK versions per F41 R37), the prefix index must be rebuilt by re-tokenizing and re-encrypting all terms with the current DEK.

R18. During compaction, encrypted prefix tokens must be re-encrypted from the old DEK to the current DEK. Each token is an independent ciphertext with its own version tag.

R18a. The `EncryptedPrefixIndexer` must store prefix index entries in a field-scoped inverted index (one `LsmInvertedIndex` instance per indexed field) or must embed the field name in the composite key format so that the compactor can recover the field name required as AES-SIV associated data. If a shared inverted index is used across fields, the composite key format must be extended to: `[2-byte BE field name length][field name UTF-8 bytes][4-byte BE token length][encrypted token bytes][doc-id bytes]`. The compactor must extract the field name from the composite key before decrypting and re-encrypting the token.

R18b. When re-encrypting a prefix token during compaction, the compactor must supply the same field name as associated data that was used at index time. A field name mismatch causes AES-SIV decryption to fail with an authentication error (F03 R29). The compactor must propagate this error as IOException per F41 R60.

### Leakage profile

R19. The `EncryptedPrefixIndexer` must expose a `leakageProfile()` method returning a `LeakageProfile` with: `frequency=true`, `searchPattern=true`, `accessPattern=true`, `volume=true`, `order=false`, level `L4`. The description must state: "Prefix tokenization reveals prefix frequency distribution, exact per-term byte lengths, and prefix hierarchy (which tokens share a prefix relationship). Leakage is strictly greater than DET equality -- the adversary observes which terms share prefixes and can reconstruct exact term lengths from the token count per term (L - M + 1 tokens implies byte length L)."

R20. The leakage profile must note that the exact byte length of each indexed term is recoverable from the number of prefix tokens produced for that term. This is per-term length recovery, not merely a distribution. Additionally, byte-aligned prefix splitting reveals whether characters at each byte position are single-byte (ASCII) or multi-byte (UTF-8), because single-byte character boundaries produce one additional token while multi-byte characters produce multiple tokens with identical plaintext prefix portions.

R20a. The leakage profile must note that cross-field term length correlation is possible even with per-field keys: the number of prefix tokens per term reveals the term's byte length, and an adversary observing two prefix-indexed fields on the same document can correlate terms by matching their length signatures. Per-field keys prevent ciphertext correlation but do not prevent length-based correlation.

### Storage overhead bounds

R21. The storage overhead of prefix indexing must be bounded. For a field with N documents, T average terms per document, and average term length L bytes (UTF-8), the index produces at most `N * T * (L - minPrefixLength + 1)` entries before deduplication. The `EncryptedPrefixIndexer` must expose an `estimateIndexEntries(int documentCount, int averageTermsPerDocument, int averageTermLengthBytes)` method that returns this upper-bound estimate. The single-term convenience overload `estimateIndexEntries(int documentCount, int averageTermLengthBytes)` must delegate with `averageTermsPerDocument=1`.

R22. Each index entry's byte size when using field-scoped inverted indexes is: `4 (token length header) + 4 (DEK version tag) + 16 (AES-SIV synthetic IV) + prefixLength + docId.length`. The AES-SIV ciphertext is 16 bytes longer than the plaintext prefix. When using the extended composite key format (R18a shared index), each entry additionally includes `2 (field name length header) + fieldNameUtf8.length` bytes.

### Concurrency

R23. The `EncryptedPrefixIndexer` must be safe for concurrent use from multiple threads without external synchronization.

### Resource lifecycle

R24. The `EncryptedPrefixIndexer` must implement `AutoCloseable`. On close, it must zero the per-field derived key segment using `MemorySegment.fill((byte) 0)` and then close the Arena that owns the key segment. Close must be idempotent.

R24a. The `EncryptedPrefixIndexer` must allocate the per-field derived key into an Arena it owns (via `Arena.ofShared()`), not a caller-supplied Arena. The constructor must accept the derived key bytes, copy them into the indexer's own Arena, and zero the caller's byte array in a finally block. This ensures the indexer has sole ownership of the key segment's lifecycle and close() can safely zero and release it without affecting caller-owned Arenas.

R24b. Any method call on a closed `EncryptedPrefixIndexer` must throw `IllegalStateException`. The closed check must use an `AtomicBoolean` or equivalent thread-safe mechanism.

### Rotation-window limitation documentation

R25. The `EncryptedPrefixIndexer` Javadoc must document that prefix queries return incomplete results during key rotation windows. The workaround is to wait for rotation convergence (F41 R37) and then rebuild the prefix index.

## Cross-References

- Spec: F03 -- Field-Level In-Memory Encryption (AES-SIV, capability matrix)
- Spec: F41 -- Encryption Lifecycle (per-field key derivation, key rotation, leakage profiles)
- ADR: .decisions/encrypted-prefix-wildcard-queries/adr.md
- ADR: .decisions/encrypted-index-strategy/adr.md (prefix queries listed as out-of-scope)
- KB: .kb/algorithms/encryption/prefix-fuzzy-searchable-encryption.md

---

## Design Narrative

### Intent

Enable `LIKE 'foo%'` queries on DET-encrypted text fields by tokenizing each term into its prefixes and encrypting each prefix independently with AES-SIV. The encrypted prefixes are stored in the existing `LsmInvertedIndex` using the standard composite key format. Query execution encrypts the query prefix and performs an exact lookup -- no new index structure or query path is needed.

### Why prefix tokenization + DET

The KB research evaluated four approaches (prefix tokenization + DET, ORE, SSE + prefix index, homomorphic encryption). Prefix tokenization + DET is recommended because: (1) it composes directly with `LsmInvertedIndex` without structural changes (~100 lines of new code), (2) it uses only `javax.crypto` (no external dependencies), (3) the leakage profile (prefix frequency at rest) is tolerable when combined with per-field keys (F41 R9-R16c) and documented leakage profiles (F41 R44-R50).

ORE (Lewi-Wu) enables range-based prefix queries but leaks order and common-prefix lengths between all pairs of values. It requires ~800-1000 lines and has 4-8x ciphertext expansion. SSE + prefix provides stronger at-rest security but requires ~600 lines and a separate index structure. Neither justifies the additional complexity for v1.

### Why byte-aligned prefixes

UTF-8 encoding means a multi-byte character (e.g., emoji, CJK) may be split at a prefix boundary, producing a prefix that ends mid-character. This is acceptable because: (1) the query path uses the same byte-aligned tokenization, so a query for a prefix that ends mid-character produces the same encrypted token and matches correctly, and (2) character-aligned prefixes would require UTF-8 decoding on every prefix boundary, adding complexity and CPU cost without improving query functionality.

### Storage overhead

Prefix tokenization multiplies index entries by the average term length. For a field with 1M documents and 10-byte average terms (default min prefix length 3), the index contains ~8M entries. The configurable min/max prefix lengths allow operators to bound this: increasing min prefix length to 5 reduces entries to ~6M. The `estimateIndexEntries` method lets callers evaluate the tradeoff before enabling prefix indexing.

### What this spec does NOT cover

- Wildcard queries with infix patterns (`LIKE '%foo%'`) -- would require n-gram tokenization, not prefix tokenization. Infix matching on encrypted data is a separate, harder problem.
- Suffix queries (`LIKE '%foo'`) -- would require reverse tokenization. Deferred.
- Integration with SQL query planner -- the prefix indexer is a component; query planning is F07's concern.

### Adversarial hardening (Pass 1 -- 2026-04-15)

18 findings from structured adversarial review across cryptographic correctness,
leakage analysis, edge cases, storage, query correctness, key rotation,
concurrency, and integration lenses.

Critical (6 fixed):
- C1: Leakage profile understated -- exact per-term byte lengths recoverable from
  token count, prefix hierarchy observable, byte-aligned splitting leaks character
  encoding information (R19, R20, R20a amended)
- C2: Cross-field length correlation not mitigated by per-field keys -- added
  R20a documenting length-based correlation vector
- C3: Compaction re-encryption impossible -- field name (AES-SIV associated data)
  not recoverable from composite key format (R18a, R18b added)
- C4: Silent incomplete query results during rotation with no caller signal --
  added R16a (isRotationPending) and R16b (queryPrefixAllVersions)
- C5: Storage estimate formula wrong for multi-term documents -- R21 amended to
  include averageTermsPerDocument parameter
- C6: Key lifecycle ambiguous -- Arena ownership unspecified, close() could leave
  keys un-zeroed or close wrong Arena (R24, R24a, R24b amended)

High (5 noted): H1 unbounded token count per term, H2 byte-aligned UTF-8
leakage (folded into R20), H3 thread safety mechanism unspecified, H4
deduplication leaks term overlap, H5 empty docId edge case.

Medium (3 noted): M1 no ciphertext size validation on query result, M2
AES-SIV/CTR no-padding assumption, M3 no rate limiting.

Low (2 noted): L1 LeakageProfile coupling, L2 R6 DEK version ambiguity (fixed).

### Adversarial verification (Pass 2 -- 2026-04-15)

3 fix-consequence findings from structured depth review of Pass 1 amendments.

Critical (1 fixed):
- C7: queryPrefixAllVersions (R16b) requires per-field keys derived from old
  DEKs, but the indexer holds only the current DEK's derived key -- added R16c
  specifying temporary key derivation with Arena lifecycle for old DEK versions

High (1 fixed):
- R7 unconditionally claimed "no structural changes" but R18a introduces
  conditional format extension -- R7 amended to scope the claim to field-scoped
  indexes

Medium (1 fixed):
- R22 ciphertext size formula did not account for R18a extended format --
  R22 amended with conditional size calculation

Zero new CRITICALs remain after Pass 2 verification.
