---
title: "Searchable Encryption for Prefix and Fuzzy Queries"
aliases: ["prefix encryption", "fuzzy encrypted search", "ORE", "prefix-preserving encryption"]
topic: "algorithms"
category: "encryption"
tags: ["searchable-encryption", "prefix", "fuzzy", "ope", "ore", "sse", "lsh", "edit-distance", "leakage"]
complexity:
  time_build: "varies by scheme"
  time_query: "varies by scheme"
  space: "varies by scheme"
research_status: "active"
confidence: "medium"
last_researched: "2026-04-13"
applies_to: []
related:
  - "algorithms/encryption/searchable-encryption-schemes.md"
  - "algorithms/encryption/deterministic-encryption-performance.md"
decision_refs: ["encrypted-prefix-wildcard-queries", "encrypted-fuzzy-matching"]
sources:
  - url: "https://dl.acm.org/doi/10.1145/2976749.2978376"
    title: "Order-Revealing Encryption (Lewi-Wu, CCS 2016)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://eprint.iacr.org/2016/612"
    title: "ORE: New Constructions, Applications, and Lower Bounds (Lewi-Wu)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://faculty.cc.gatech.edu/~aboldyre/papers/bclo.pdf"
    title: "Order-Preserving Symmetric Encryption (Boldyreva et al.)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://people.eecs.berkeley.edu/~raluca/CryptDB-sosp11.pdf"
    title: "CryptDB (SOSP 2011)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://www.eecs.harvard.edu/~michaelm/postscripts/alenex2006.pdf"
    title: "Distance-Sensitive Bloom Filters (Kirsch-Mitzenmacher)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://link.springer.com/chapter/10.1007/978-3-642-15317-4_10"
    title: "Searching Keywords with Wildcards on Encrypted Data (Hu-Cao)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://crypto.stanford.edu/ore/"
    title: "Stanford ORE Project"
    accessed: "2026-04-13"
    type: "web"
---

# Searchable Encryption for Prefix and Fuzzy Queries

## summary

Prefix (`LIKE 'foo%'`) and fuzzy (edit-distance) queries are the hardest to
support on encrypted data. Every known approach trades security for
functionality. The two most practical pure-Java approaches: (1) **prefix
tokenization + DET** -- encrypt every prefix of each term, store in inverted
index; (2) **LSH + Bloom filter** -- hash terms into locality-sensitive buckets
for fuzzy matching. Both compose with `LsmInvertedIndex` and use only
`javax.crypto`. No production encrypted database (CryptDB, Arx, Acra) has
solved prefix or fuzzy queries; application-layer tokenization is the industry
workaround.

## how-it-works

### prefix-tokenization-with-det (recommended for prefix)

Generate all prefixes of each term (down to min length), encrypt each with
AES-SIV, store in inverted index. Query: encrypt the query prefix, exact lookup.
**Leakage**: prefix frequency and equality at rest -- strictly more than DET
equality because it multiplies encrypted tokens per term.
**Storage**: O(L * N) entries where L = avg term length, N = doc count.

### ore-for-prefix-via-range

ORE enables range queries on ciphertext. `LIKE 'foo%'` becomes
`field >= 'foo' AND field < 'fop'`. **Lewi-Wu ORE** (CCS 2016) splits plaintext
into blocks; comparison reveals only the first differing block index. Stronger
than OPE but still leaks order and common-prefix lengths between all pairs.
Large ciphertexts; no production pure-Java ORE library exists.

### sse-with-prefix-index

Curtmola-style SSE indexing all prefixes: `PRF(K, prefix) -> encrypted doc-ID
list`. Opaque at rest unlike DET, but leaks search/access pattern on query.

### lsh-bloom-filter-for-fuzzy (recommended for fuzzy)

Index: for each term, extract character n-gram shingles (e.g., bigrams of
"hello" = {he, el, ll, lo}), compute k LSH hash signatures, insert into
per-document Bloom filter, encrypt with AES-GCM. Query: compute query n-gram
signatures, decrypt candidate Bloom filters, check if >= threshold signatures
present. Threshold: `(len - max_edit_distance * ngram_size + 1)`.
**Leakage**: minimal at rest (AES-GCM); access pattern on query.

### homomorphic-encryption (not viable)

FHE is 10^5-10^6x slower than plaintext; partial HE lacks comparisons. Not viable.

## algorithm-steps

**Prefix index build**: tokenize field -> generate prefixes `t[0..1]..t[0..L]`
-> encrypt each with AES-SIV(field_key) -> insert (encrypted_prefix, doc_id)
into inverted index. **Query**: encrypt prefix, exact lookup.

**Fuzzy index build**: extract n-grams per term -> compute k LSH signatures ->
insert into per-doc Bloom filter -> encrypt filter with AES-GCM. **Query**:
compute query n-gram signatures -> decrypt filter -> count hits >= threshold.

## implementation-notes

| Approach | Java Deps | Lines | Practical? |
|----------|-----------|-------|------------|
| Prefix token + DET | `javax.crypto` | ~100 | Yes -- recommended |
| ORE (Lewi-Wu) | Custom crypto | ~800+ | Research only |
| SSE + prefix | `javax.crypto` | ~600 | Yes, if access-pattern hiding needed |
| LSH + Bloom | `javax.crypto` + existing Bloom | ~200 | Yes -- recommended for fuzzy |

Prefix tokenization composes with `LsmInvertedIndex` composite key
`[4-byte BE len][encrypted-prefix][doc-id]` -- no structural changes. LSH Bloom
filters store as encrypted metadata in `DocumentSerializer`, evaluated as
post-filter. Per-field keys prevent cross-field correlation.

## complexity-analysis

| Approach | Index Build | Query | Storage Overhead |
|----------|-------------|-------|------------------|
| Prefix token + DET | O(L*N) enc ops | O(1) lookup | L*N index entries |
| ORE range | O(N) enc ops | O(log N + r) scan | ~4-8x ciphertext expansion |
| LSH + Bloom | O(k*N) hash+enc | O(k) per candidate | 1 Bloom filter per doc |

## tradeoffs

**Leakage spectrum** (least to most at rest): SSE+prefix (opaque until queried)
> LSH Bloom AES-GCM (minimal) > prefix token DET (frequency/equality) > ORE
(order + prefix lengths) > OPE (approximate position, half the bits).

No scheme achieves strong security AND rich query support -- any encryption
permitting server-side computation leaks information. Prefix tokenization + DET
is best cost/benefit for prefix (bounded overhead, trivial implementation,
tolerable leakage with per-field keys and high cardinality). LSH + Bloom is the
only practical fuzzy option (accuracy depends on n-gram size and LSH tuning;
false positives from Bloom FPR + LSH collisions; false negatives when edit
distance exceeds overlap threshold).

**Production systems**: CryptDB does NOT support LIKE. Arx handles
range/equality only. StealthDB requires SGX. Acra SE: equality only.

## code-skeleton

```java
// Prefix tokenization -- integrates with LsmInvertedIndex
class EncryptedPrefixIndexer {
    private final SecretKey fieldKey;
    private final int minPrefixLen;

    List<byte[]> encryptPrefixes(String term) {
        byte[] raw = term.getBytes(UTF_8);
        var tokens = new ArrayList<byte[]>(raw.length - minPrefixLen + 1);
        for (int len = minPrefixLen; len <= raw.length; len++)
            tokens.add(aesSivEncrypt(fieldKey, Arrays.copyOf(raw, len)));
        return tokens;
    }
    byte[] encryptQuery(String prefix) {
        return aesSivEncrypt(fieldKey, prefix.getBytes(UTF_8));
    }
}

// LSH + Bloom for fuzzy matching
class EncryptedFuzzyMatcher {
    private final int ngramSize, numHashes, bloomBits;

    byte[] buildFilter(Set<String> terms, SecretKey docKey) {
        var filter = new BloomFilter(bloomBits, numHashes);
        for (var t : terms)
            for (var ng : extractNgrams(t, ngramSize))
                filter.add(ng.getBytes(UTF_8));
        return aesGcmEncrypt(docKey, filter.toByteArray());
    }
    boolean maybeFuzzyMatch(String query, int maxDist,
                            byte[] encFilter, SecretKey docKey) {
        var filter = BloomFilter.from(aesGcmDecrypt(docKey, encFilter));
        var ngrams = extractNgrams(query, ngramSize);
        int hits = (int) ngrams.stream()
            .filter(ng -> filter.mightContain(ng.getBytes(UTF_8))).count();
        return hits >= Math.max(query.length() - maxDist * ngramSize + 1, 1);
    }
}
```

## sources

1. [Lewi-Wu ORE](https://dl.acm.org/doi/10.1145/2976749.2978376) -- block-wise ORE with limited leakage (CCS 2016)
2. [Boldyreva et al. OPE](https://faculty.cc.gatech.edu/~aboldyre/papers/bclo.pdf) -- hypergeometric OPE
3. [CryptDB](https://people.eecs.berkeley.edu/~raluca/CryptDB-sosp11.pdf) -- documents LIKE as unsupported (SOSP 2011)
4. [Distance-Sensitive Bloom Filters](https://www.eecs.harvard.edu/~michaelm/postscripts/alenex2006.pdf) -- approximate matching
5. [Wildcard Search on Encrypted Data](https://link.springer.com/chapter/10.1007/978-3-642-15317-4_10) -- hidden vector encryption
6. [Stanford ORE Project](https://crypto.stanford.edu/ore/) -- ORE overview and research prototypes

*Researched: 2026-04-13 | Next review: 2026-10-13*

## Updates 2026-04-13

### Frontier research survey (2023-2026)

**Function-Hiding Inner Product Encryption (FHIPE).** A 2024 paper (Sciopen,
s11390-024-3670-y) reduces FH-IPFE setup from O(n^3) to O(n) and encryption
from O(n^2) to O(n log n) using pairings. FHIPE enables computing inner
products on encrypted vectors without revealing either vector -- theoretically
applicable to fuzzy matching via cosine/Jaccard on n-gram vectors. However,
all practical FHIPE constructions require bilinear pairings (not available in
javax.crypto), and post-quantum FHIPE remains an open problem. **Verdict**:
genuinely promising for fuzzy encrypted search but requires a pairing library
(e.g., JPBC or mcl-java JNI wrapper). Not pure-Java viable today.

**Parameter-Hiding ORE without Pairings (2024).** Eprint 2024/434 proposes
pORE using identification schemes, achieving ~31% smaller ciphertexts and 2x
faster comparison vs prior pairing-based pORE. The key advance: pairing-free
construction using map-invariance of identification schemes. CipherStash's
ore.rs (Rust, Lewi-Wu based, pre-1.0) is the closest to production ORE but
remains unaudited. **Pure Java feasibility**: Lewi-Wu block-ORE requires only
AES as a PRF/random oracle -- no pairings. A Java port is ~800-1000 lines,
implementable with javax.crypto. The blocker is not crypto complexity but
ciphertext expansion (4-8x) and the leakage profile (first-differing-block).
Orgs avoid ORE due to the leakage analysis burden, not implementation cost.

**Forward/Backward Private DSSE (Sophos, Diana, Janus).** Bost et al. (CCS
2017) formalized forward privacy (new insertions don't leak about past
queries) and backward privacy (deletions are invisible to future queries).
OpenSSE provides C++ implementations of Sophos (forward-private, RSA-based),
Diana (forward-private, symmetric-only), and Janus (backward-private,
puncturable encryption). Diana is the most portable -- symmetric crypto only,
no pairings. **Practical assessment**: Diana could be ported to Java (~600
lines, AES+HMAC). Forward privacy is the real win for append-heavy LSM
workloads: new SSTable flushes don't leak query history. Large orgs haven't
adopted this primarily because existing SSE products (MongoDB CSFLE, AWS DBES)
use simpler DET/RND and the forward-privacy benefit requires rethinking index
update protocols.

**TEE-based approaches (SGX/TDX/SEV).** Intel TDX (2023+) extends protection
to entire VMs, reducing the SGX enclave programming burden. StealthDB and
EnclaveDB demonstrated full SQL on encrypted data inside SGX. TDX and AMD
SEV-SNP now offer VM-level confidential computing with lower overhead (~5-15%
vs SGX's ~20-30%). **Trade-off**: hardware trust replaces cryptographic
guarantees -- side-channel attacks (Foreshadow, AEPIC Leak) remain a concern.
For a pure-Java library like jlsm, TEEs are a deployment-time choice, not a
code-level one: the library runs unmodified inside a confidential VM. This is
the most practical path for orgs that need prefix/fuzzy queries on sensitive
data today.

**Proxy re-encryption for key rotation.** Prefix-tokenized indexes (our
recommended approach) require re-encrypting all prefix tokens on key rotation.
Proxy re-encryption (PRE) allows a proxy to transform ciphertexts from old key
to new key without decrypting. This is well-studied (Ateniese et al., 2006)
and implementable in Java, but adds a trust assumption (the proxy). For
LSM-tree compaction, a simpler approach: re-encrypt during compaction merges
(tokens are already being rewritten), amortizing rotation cost over background
I/O. No PRE needed if rotation aligns with compaction.

**Bottom line.** The gap between research and production has narrowed since
2016, but the main barriers are organizational, not cryptographic: (1) Diana
forward-private SSE is implementable in pure Java today but requires protocol
changes at the index layer; (2) Lewi-Wu ORE is implementable but the leakage
analysis scares compliance teams; (3) TEE-based confidential computing is the
path of least resistance for orgs that need rich queries on encrypted data now.
