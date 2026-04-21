---
{
  "id": "query.encrypted-fuzzy-matcher",
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
    "encrypted-fuzzy-matching",
    "encrypted-index-strategy"
  ],
  "kb_refs": [
    "algorithms/encryption/prefix-fuzzy-searchable-encryption",
    "algorithms/encryption/searchable-encryption-schemes"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F47"
  ]
}
---
# F47 -- Encrypted Fuzzy Matcher

## Requirements

### N-gram shingling

R1. The `EncryptedFuzzyMatcher` must extract character-level n-gram shingles from input terms. The n-gram size must be configurable at construction time with a default of 2 (bigrams). The constructor must reject an n-gram size of zero or negative with `IllegalArgumentException`.

R2. For a term of character length C and n-gram size G, the matcher must produce exactly `max(C - G + 1, 0)` shingle positions. A term shorter than the n-gram size produces zero shingles.

R2a. Duplicate n-grams (identical shingle strings at different positions) must be deduplicated before LSH signature computation and Bloom filter insertion. The queryNgramCount used in the threshold formula (R13) must be the count of distinct n-grams, not the count of shingle positions. This prevents terms with repeated characters (e.g., "aaa" producing duplicate bigram "aa") from inflating the expected threshold and causing false negatives.

R3. N-gram extraction must operate on Unicode characters (code points), not bytes. Each shingle must be a substring of G characters. The shingles must be encoded as UTF-8 bytes for hashing.

R3a. Before n-gram extraction, the matcher must normalize all input terms and query strings to Unicode NFC (Canonical Decomposition followed by Canonical Composition) using `java.text.Normalizer`. This ensures that visually identical strings composed differently (e.g., "cafe\u0301" vs "caf\u00e9") produce identical n-grams. Normalization must occur before code point extraction.

### LSH signature computation

R4. The matcher must compute k locality-sensitive hash (LSH) signatures per n-gram shingle. The number of hash functions k must be configurable at construction time with a default of 4. The constructor must reject k of zero or negative with `IllegalArgumentException`.

R5. Each LSH hash function must be a keyed hash: `HMAC-SHA256(lshKey_i, shingle_bytes)` truncated to 8 bytes (64 bits). The k LSH keys must be derived from the per-field key using HKDF-SHA256 (F41 R10) with info bytes following the length-prefixed convention of F41 R11: `"jlsm-fuzzy-lsh:" || 4-byte-BE(0) || 4-byte-BE(i)` where i is the zero-based hash function index encoded as a 4-byte big-endian integer. The prefix `"jlsm-fuzzy-lsh:"` is distinct from the field key prefix `"jlsm-field-key:"` to prevent namespace collisions. The two zero-padded length fields maintain structural consistency with F41 R11's format.

R5a. The LSH sub-keys must be stored in off-heap MemorySegments allocated from a shared Arena, not in on-heap byte arrays, consistent with F41 R68. On-heap copies created temporarily for JCA Mac initialization must be zeroed in a finally block immediately after the Mac is initialized. Thread-safe HMAC computation must be ensured (e.g., via thread-local Mac instances) since Mac is not thread-safe.

R6. The LSH signatures must be deterministic: the same shingle under the same per-field key must always produce the same k signatures.

### Per-document Bloom filter construction

R7. The matcher must insert all LSH signatures for all n-grams of all terms in a document into a single per-document Bloom filter. The Bloom filter must use the existing `BlockedBloomFilter` implementation.

R8. The Bloom filter's expected insertions must be computed as: `totalDistinctNgrams * k`, where totalDistinctNgrams is the count of distinct n-grams across all terms in the document (after deduplication per R2a). The false positive rate must be configurable at construction time with a default of 0.01 (1%).

R8a. If totalDistinctNgrams is zero (all terms are shorter than the n-gram size), the `buildFilter` method must return a sentinel empty filter (a minimal valid encrypted payload) rather than attempting to construct a BlockedBloomFilter with zero expected insertions. The `maybeFuzzyMatch` method must recognize the sentinel and return `false` immediately without Bloom filter deserialization.

R9. The constructed Bloom filter must be serialized to bytes using `BlockedBloomFilter`'s serialization format and encrypted with AES-GCM (opaque encryption per F03 R31-R36) using the per-field key. The 4-byte DEK version tag (F41 R22) must be prepended.

R10. The encrypted Bloom filter must be stored as an opaque field value alongside the document. The storage mechanism is document metadata, not the inverted index.

### Fuzzy query evaluation

R11. The `EncryptedFuzzyMatcher` must provide a `maybeFuzzyMatch(String query, int maxEditDistance, byte[] encryptedFilter)` method that returns `true` if the filter suggests a fuzzy match within the specified edit distance, `false` otherwise.

R12. The match evaluation must: (a) decrypt the Bloom filter using AES-GCM with the per-field key, (b) extract n-gram shingles from the query term (after NFC normalization per R3a), (c) compute k LSH signatures for each query shingle, (d) for each query shingle, check all k of its signatures against the Bloom filter, and (e) count the number of matching n-grams — an n-gram matches if and only if all k of its LSH signatures are present in the Bloom filter. The count is in n-gram units, not signature units.

R13. The match threshold must be computed as: `max(queryNgramCount - maxEditDistance * ngramSize, 1)`, where queryNgramCount is the number of distinct n-grams in the query term (after deduplication). If the count of matching n-grams (per R12e) is at least the threshold, the method must return `true`. The threshold formula derives from the Gravano et al. lower bound on shared q-grams for strings within edit distance D: at most D*G n-grams are affected by D edit operations with n-gram size G.

R14. The `maybeFuzzyMatch` method must reject a null query with `NullPointerException`.

R15. The `maybeFuzzyMatch` method must reject a negative `maxEditDistance` with `IllegalArgumentException`.

R16. The `maybeFuzzyMatch` method must reject a null or empty `encryptedFilter` with `NullPointerException` or `IllegalArgumentException` respectively.

### Accuracy characteristics

R17. The `EncryptedFuzzyMatcher` must document that matching is inherently approximate. False positives arise from two sources: Bloom filter false positive rate and LSH hash collisions. False negatives arise when the edit distance between query and document term exceeds the n-gram overlap threshold.

R18. The accuracy depends on three tunable parameters: n-gram size (larger = fewer shingles per edit, better precision but lower recall for short terms), number of LSH hash functions k (larger = more signatures per shingle, better recall but larger Bloom filter), and Bloom filter false positive rate (lower = fewer false positives but larger filter). The matcher must document these tradeoffs.

R19. For a query term of length Q and max edit distance D with n-gram size G, the minimum term length that can produce a meaningful match is `D * G + 1`. The `maybeFuzzyMatch` method must return `false` immediately without decrypting the filter when the query term is shorter than this threshold.

### Key rotation behavior

R20. During a key rotation window, the matcher must decrypt Bloom filters using the DEK version tag embedded in the encrypted filter. The DEK for the referenced version must be available in the key registry (F41 R35).

R21. After DEK rotation, encrypted Bloom filters must be fully rebuilt — not merely re-encrypted — during compaction (F41 R25). Because LSH keys are derived from the per-field key (R5), and the per-field key changes when the DEK rotates (F41 R16b), the LSH signatures in the old Bloom filter are bound to the old key and cannot be queried with new LSH keys. During compaction, the compaction task must: (a) decrypt the old Bloom filter with the old DEK's field key, (b) extract and re-index all document terms to compute new LSH signatures using the current DEK's field key, (c) build a new Bloom filter from the new signatures, and (d) encrypt the new filter with the current DEK. This requires the compaction path to have access to the original document terms for fields with fuzzy matching enabled.

R21a. During the key rotation window (before all Bloom filters are rebuilt), the `maybeFuzzyMatch` method must compute query LSH signatures using the LSH keys derived from the DEK version embedded in the encrypted filter (per R20), not the current DEK. This ensures query signatures match the filter's signatures. The matcher must retain the ability to derive LSH keys from any DEK version present in the key registry.

R21b. As an alternative to full Bloom filter rebuilds (R21), the matcher may derive LSH keys from a dedicated LSH root key that is independent of the DEK and does not rotate with DEK rotation. If this approach is used: the LSH root key must be stored in the key registry alongside the DEK entries, it must be wrapped by the KEK (same envelope encryption as DEKs per F41 R17), and it must rotate only when explicitly requested via a separate `rotateLshKey()` operation. When the LSH root key rotates, all Bloom filters must be rebuilt during compaction (same process as R21). The choice between DEK-derived LSH keys (R21) and independent LSH root key (R21b) is an implementation decision that must be documented at construction time.

### Leakage profile

R22. The `EncryptedFuzzyMatcher` must expose a `leakageProfile()` method returning a `LeakageProfile` with: `frequency=false`, `searchPattern=true`, `accessPattern=true`, `volume=true`, `order=false`, level `L2`. The description must state: "Per-document Bloom filter is encrypted with AES-GCM (opaque at rest). Leakage occurs on query: the server observes which documents are accessed during fuzzy matching and can link repeated queries for the same term (identical match result sets). Filter size reveals approximate n-gram count per document, which correlates with total term character volume. During query evaluation, the decrypted Bloom filter is transiently visible to the query executor — a compromised executor can probe arbitrary n-gram signatures against the filter to enumerate document n-gram vocabulary via chosen-query attacks."

### Storage overhead

R23. Each document with fuzzy-matching-enabled fields stores one encrypted Bloom filter per field. The filter size is determined by the expected insertions and false positive rate. The matcher must expose an `estimateFilterSizeBytes(int termCount, int avgTermLength)` method that returns the estimated encrypted filter size for the given document statistics.

### Input validation

R24. The `EncryptedFuzzyMatcher` constructor must reject a null per-field key with `NullPointerException`.

R25. The `buildFilter(Set<String> terms)` method must reject a null terms set with `NullPointerException` and an empty set with `IllegalArgumentException`.

### Concurrency

R26. The `EncryptedFuzzyMatcher` must be safe for concurrent use from multiple threads without external synchronization.

### Resource lifecycle

R27. The `EncryptedFuzzyMatcher` must implement `AutoCloseable`. On close, it must zero the per-field derived key, all LSH sub-keys, and the LSH root key if the independent root key approach (R21b) is used. Close must be idempotent. Use-after-close on any method must throw `IllegalStateException`.

## Cross-References

- Spec: F03 -- Field-Level In-Memory Encryption (AES-GCM, BlockedBloomFilter)
- Spec: F41 -- Encryption Lifecycle (per-field key derivation, key rotation, leakage profiles)
- ADR: .decisions/encrypted-fuzzy-matching/adr.md
- ADR: .decisions/encrypted-index-strategy/adr.md (fuzzy matching listed as out-of-scope)
- KB: .kb/algorithms/encryption/prefix-fuzzy-searchable-encryption.md

---

## Design Narrative

### Intent

Enable approximate string matching (edit distance / fuzzy search) on encrypted text fields using locality-sensitive hashing (LSH) combined with Bloom filters. Each document's terms are hashed into LSH signatures and inserted into a per-document Bloom filter, which is then encrypted with AES-GCM. Fuzzy queries compute the same LSH signatures for the query term and check against decrypted candidate filters.

### Why LSH + Bloom filter

The KB research evaluated four approaches for fuzzy matching on encrypted data. LSH + Bloom filter is recommended because: (1) it provides minimal at-rest leakage (AES-GCM encrypted filter is opaque), (2) it composes with existing `BlockedBloomFilter` implementation, (3) it requires ~200 lines of new code, and (4) it uses only `javax.crypto` (no external dependencies).

FHIPE (Function-Hiding Inner Product Encryption) is theoretically applicable via cosine/Jaccard on n-gram vectors but requires bilinear pairings not available in `javax.crypto`. Not pure-Java viable.

### Why n-grams, not full terms

Edit distance operates on character-level structure. Two terms with edit distance 1 share most of their n-gram shingles. By hashing shingles into LSH buckets and checking overlap, the matcher approximates edit distance without computing it on ciphertext (which is impossible). The threshold formula `max(queryNgramCount - maxEditDistance * ngramSize, 1)` derives from the Gravano et al. lower bound on shared q-grams for strings within edit distance D: each edit operation affects at most G n-grams, so D edits affect at most D*G n-grams, leaving at least `queryNgramCount - D*G` shared n-grams. The queryNgramCount uses distinct n-grams to avoid threshold inflation from duplicate shingles.

### Accuracy tradeoffs

This is inherently an approximate matching scheme. The accuracy depends on parameter tuning:
- **Bigrams (default)**: 2-character shingles. Each edit operation (insert, delete, substitute) affects at most 2 shingles. Good for most use cases.
- **k=4 LSH hashes (default)**: Each shingle produces 4 signatures. More hashes improve recall but increase Bloom filter size.
- **1% Bloom FPR (default)**: Reasonable tradeoff between filter size and false positives.

No production encrypted database supports fuzzy matching -- this is novel functionality with explicitly approximate semantics.

### What this spec does NOT cover

- Exact edit distance computation -- the matcher provides a probabilistic filter, not an exact distance
- Phonetic matching (Soundex, Metaphone) -- separate concern, could layer on top
- Multi-field fuzzy queries -- each field has its own matcher; cross-field fuzzy is out of scope
- Integration with SQL query planner -- the fuzzy matcher is a component; query planning is F07's concern

### Adversarial hardening (Pass 1 — 2026-04-15)

18 findings from structured adversarial review across cryptographic correctness,
leakage analysis, threshold formula analysis, Unicode edge cases, key rotation,
and resource safety.

Critical (5 — all fixed):
- Threshold formula off-by-one: `+1` made threshold stricter than Gravano et al.
  bound, causing false negatives for legitimate edit-distance matches (R13 amended)
- Unit mismatch in match counting: R12 counted individual LSH signatures but
  threshold was in n-gram units; clarified that matching counts n-grams where
  all k signatures are present (R12 amended)
- Unicode normalization absent: visually identical strings with different
  compositions produced different n-grams (R3a added)
- Key rotation breaks Bloom filter contents: LSH keys derived from per-field
  key change on DEK rotation, making old Bloom filters unqueryable with new
  keys; R21 requires full rebuild not just re-encryption (R21, R21a, R21b added)
- Leakage profile understates searchPattern: repeated queries produce identical
  match sets, which is search pattern leakage (R22 amended, searchPattern=true)

High (3 — all fixed):
- HKDF info string collision risk: LSH key info strings could collide with
  field key info strings from F41 R11; changed to length-prefixed format with
  distinct prefix (R5 amended)
- Decrypted Bloom filter transient leakage: during query evaluation, decrypted
  filter enables chosen-query n-gram enumeration attacks (R22 description amended)
- Duplicate n-grams inflate threshold: repeated characters produce duplicate
  shingles counted in queryNgramCount but stored once in Bloom filter, causing
  false negatives (R2a, R13 amended to use distinct n-gram count)

Medium (7 — documented, no spec change needed):
- Filter size leaks total character volume, not just term count (captured in R22)
- Cross-query intersection attacks enable n-gram vocabulary reconstruction
  (inherent to access pattern leakage, noted in R22 description)
- Zero-insertion Bloom filter edge case when all terms shorter than G (R8a added)
- LSH sub-keys stored on heap risk (R5a added for off-heap storage)
- Thread-safe HMAC computation required (R5a added)
- Concurrency for Mac instances not explicit (R5a added)
- Terms with only repeated chars produce degenerate n-gram sets (R2a handles)

Low (3 — no change):
- HMAC truncation to 8 bytes: 2^32 birthday bound adequate for typical docs
- AES-GCM nonce handling: inherits F03 R32 fresh IV mandate
- R19 minimum length very permissive at high D values: inherent to scheme
