---
problem: "aad-canonical-encoding"
evaluated: "2026-04-23"
candidates:
  - name: "Length-prefixed TLV (custom, sorted keys, Purpose.code())"
    source: "implemented in WU-2; spec v9 R80a"
  - name: "JSON with RFC 8785 (JCS) canonicalization"
    source: "general knowledge + RFC 8785"
  - name: "CBOR with Deterministic Encoding (RFC 8949 §4.2.1)"
    source: "general knowledge + RFC 8949"
  - name: "Protobuf with deterministic serialization"
    source: "general knowledge + protobuf docs"
constraint_weights:
  scale: 1
  resources: 2
  complexity: 2
  accuracy: 3
  operational: 3
  fit: 2
---

# Evaluation — aad-canonical-encoding

## References
- Constraints: [constraints.md](constraints.md)
- KB sources used:
  [`.kb/systems/security/three-level-key-hierarchy.md`](../../.kb/systems/security/three-level-key-hierarchy.md)
  (precedent: length-prefixed HKDF info in R11)
- Spec source: `.spec/domains/encryption/primitives-lifecycle.md` R11, R80a, R80a-1

## KB Coverage Note

No dedicated KB subject compares canonical AAD encoding alternatives. The three
security KB entries (three-level-hierarchy, dek-caching-policies, sstable-envelope)
all assume a canonical encoding exists but do not evaluate format choices. Scores
for **rejected** candidates (JSON/JCS, CBOR, Protobuf) draw on general industry
knowledge and the cited RFC/docs, not dedicated KB research. This gap is
acceptable because the chosen candidate mirrors an already-approved encoding
pattern in the same spec (R11 HKDF info), and the rejected candidates have
well-documented determinism hazards that disqualify them on the primary constraint.

## Constraint Summary

Determinism (byte-exact AAD across Map implementations, Java versions, and
callers) and wire-format stability (no migration on refactor) are the two
binding constraints. The encoding is not in a hot path; the encoder routine
runs once per wrap/unwrap. Zero-dep and R11-consistency are secondary drivers.

## Weighted Constraint Priorities

| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 1 | Encoding is not a hot path; called once per wrap/unwrap |
| Resources | 2 | Zero-dep is firm but payload is tiny (~200 bytes) |
| Complexity | 2 | Must be reproducible from spec by third parties |
| Accuracy | 3 | Byte-exact determinism is the whole point — any mismatch is an outage |
| Operational | 3 | Wire-format lock-in; re-wrap migration is O(DEKs) = millions |
| Fit | 2 | Consistency with R11 (length-prefixed HKDF info) reduces maintenance |

---

## Candidate: Length-prefixed TLV (custom, sorted keys, Purpose.code())

**Encoding:** `[4B BE Purpose.code() | 4B BE attr-count | sorted-by-key (4B BE key-len | UTF-8 key | 4B BE val-len | UTF-8 val) pairs]`

**Source:** implemented in WU-2, codified in spec v9 R80a + R80a-1
**Precedent:** R11 HKDF info uses the same length-prefixed UTF-8 pattern for a parallel problem

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | O(n log n) sort of attr-count = 5; encoder is <50 lines Java |
|        |   |   |   | **Would be a 2 if:** contexts had thousands of attributes (not applicable — R80a-1 bounds it) |
| Resources | 2 | 5 | 10 | Zero dependencies; `byte[]` output via `ByteBuffer` in one method |
|           |   |   |    | **Would be a 2 if:** producing an output `MemorySegment` required pool allocation (tiny payload — `byte[]` is acceptable) |
| Complexity | 2 | 4 | 8 | Spec-reproducible in any language from the 1-line BNF; `TreeMap` handles sort |
|            |   |   |    | **Would be a 2 if:** encoding required endian-independence (it doesn't — big-endian is universal) |
| Accuracy | 3 | 5 | 15 | Length prefixes prevent canonicalization collisions (see R11 proof); sorted keys eliminate Map iteration nondeterminism; `code()` survives enum reorder |
|          |   |   |    | **Would be a 2 if:** callers provided non-Unicode strings or relied on locale-specific `toLowerCase` before passing the key (spec requires the caller pass bytes-as-supplied) |
| Operational | 3 | 5 | 15 | Inspectable: hex dump of AAD is directly readable (length + UTF-8 text); diagnosable when an auth failure occurs |
|             |   |   |    | **Would be a 2 if:** the format needed to evolve (e.g., adding a type tag per attribute for non-string values). Currently not needed; R80a fixes types as strings. |
| Fit | 2 | 5 | 10 | Exactly parallels R11's HKDF info encoding — one pattern to audit across both context-binding sites |
|     |   |   |    | **Would be a 2 if:** R11 changed to a different encoding later (R11 is confirmed and unlikely to change) |
| **Total** | | | **63** | |

**Hard disqualifiers:** none

**Key strengths for this problem:**
- Length prefixes + sorted keys + stable `code()` directly answer the three
  determinism failure modes (canonicalization collision, Map order, enum reorder)
- Byte-identical to R11's HKDF info structure — one canonical pattern in the
  codebase for "bind identifiers to crypto operation"
- Trivially reproducible by non-Java consumers from the BNF alone

**Key weaknesses for this problem:**
- No schema validation — a buggy caller passing a null key would produce
  malformed bytes. Mitigated by eager input validation at the public API per
  coding-guidelines.
- If jlsm ever needed heterogeneous attribute value types (numbers, bytes,
  booleans), this encoding would need a type tag. Currently out of scope;
  values are strings (R80a-1 serializes `dekVersion` as decimal UTF-8).

---

## Candidate: JSON with RFC 8785 (JCS) canonicalization

**Encoding:** `{"purpose":"dek","tenantId":"...",...}` serialized per RFC 8785 (JSON Canonicalization Scheme): sorted keys, no whitespace, specific number encoding rules, UTF-8.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 4 | 4 | JCS canonicalization is not free (number serialization spec is elaborate) but context size is small |
| Resources | 2 | 2 | 4 | Requires a JCS-compliant JSON library; `org.json` and Jackson are NOT JCS-compliant by default. Adds a dep (e.g., `erdtman/java-json-canonicalization`) |
| Complexity | 2 | 2 | 4 | JCS covers number canonicalization, Unicode normalization, escape rules — each a determinism hazard the implementer must know. Spec-reproducibility requires pointing consumers at RFC 8785 (12k words) |
| Accuracy | 3 | 4 | 12 | JCS *is* deterministic when correctly implemented. But *correctness* is the problem: any bug in Unicode escape handling, number formatting (exponent placement), or surrogate-pair handling produces a silent mismatch |
| Operational | 3 | 4 | 12 | Human-readable (a plus for debugging). But wire-format changes implicitly if the JSON library's canonicalization subtly changes — lock-in to a specific library version |
| Fit | 2 | 2 | 4 | Inconsistent with R11's binary encoding; the codebase would have two canonical-encoding patterns to review |
| **Total** | | | **40** | |

**Hard disqualifiers:** none, but the library dependency puts it at the edge of the zero-dep constraint. A custom JCS implementation is possible but pushes 1000+ lines into the codebase.

**Why not:** determinism is achieved via a standard, not a property of the format. A subtle implementation bug (especially in Unicode escape rules or number canonicalization) produces outage-class failures. TLV avoids this by having nothing to canonicalize — just concatenate bytes.

---

## Candidate: CBOR with Deterministic Encoding (RFC 8949 §4.2.1)

**Encoding:** CBOR map with deterministic encoding rules: keys sorted by byte-wise lexicographic order of their serialized form, shortest integer encoding, no indefinite-length items.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Compact binary; encoder is efficient |
| Resources | 2 | 2 | 4 | Requires a CBOR library (Jackson CBOR, c-rack/cbor-java). No pure-JDK option |
| Complexity | 2 | 3 | 6 | CBOR itself is simple (~30-page RFC) but deterministic-encoding mode has subtle rules (e.g., "keys sorted by encoded form" means key-length affects sort order, opposite of natural string order) |
| Accuracy | 3 | 4 | 12 | Well-specified determinism when implemented correctly. Same library-bug hazard as JCS but smaller surface area |
| Operational | 3 | 3 | 9 | Not human-readable; requires `cbor-diag` or hex+decoder. Library-version lock-in |
| Fit | 2 | 2 | 4 | Inconsistent with R11; adds a dep that no other jlsm component needs |
| **Total** | | | **40** | |

**Hard disqualifiers:** none, but CBOR library dependency is the first new external dep in the encryption path.

**Why not:** adds a library dependency for a payload that custom TLV handles in ~30 lines. CBOR's deterministic encoding rules would need to be audited in the library version chosen, which is a worse maintenance posture than owning a 30-line encoder.

---

## Candidate: Protobuf with deterministic serialization

**Encoding:** A `.proto` schema for `EncryptionContext`, serialized with `deterministic=true` option.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 1 | 5 | 5 | Efficient encoder, small output |
| Resources | 2 | 1 | 2 | **DISQUALIFIER:** requires `protobuf-java` runtime (~1.5 MB). Violates zero-dep constraint |
| Complexity | 2 | 2 | 4 | Schema file + codegen + deterministic-serialization flag. Spec-reproducibility requires consumers run protoc with matching schema version |
| Accuracy | 3 | 3 | 9 | Google's own docs warn: "deterministic serialization does not guarantee deterministic serialization across languages, or across different versions of the library." An AAD computed by a Java jlsm writer might not match one computed by a hypothetical Go reader |
| Operational | 3 | 2 | 6 | Schema evolution rules are well-understood but schema-file-as-wire-format is a maintenance burden |
| Fit | 2 | 1 | 2 | Protobuf is nowhere else in jlsm; introducing it for one 200-byte payload is disproportionate |
| **Total** | | | **28** | |

**Hard disqualifiers:** **Violates zero-dep constraint (Resources weight 2 × score 1 = 2)**. Also Google's explicit warning that deterministic serialization is not cross-language-stable is a direct hit on the Accuracy constraint.

**Why not:** two hard failures (dep + cross-language determinism warning).

---

## Comparison Matrix

| Candidate | Scale | Resources | Complexity | Accuracy | Operational | Fit | Weighted Total |
|-----------|-------|-----------|------------|----------|-------------|-----|----------------|
| **Length-prefixed TLV (landed)** | 5 | 5 | 4 | 5 | 5 | 5 | **63** |
| JSON + JCS (RFC 8785) | 4 | 2 | 2 | 4 | 4 | 2 | 40 |
| CBOR (RFC 8949 §4.2.1) | 5 | 2 | 3 | 4 | 3 | 2 | 40 |
| Protobuf (deterministic) | 5 | 1 | 2 | 3 | 2 | 1 | 28 |

## Sub-choice evaluations

### Sorted keys vs. insertion-ordered

- **Sorted (chosen):** byte-identical AAD regardless of Map implementation or iteration order. Required by Accuracy constraint.
- **Insertion-ordered:** requires a specific Map type contract (`LinkedHashMap`) at every call site. A caller accidentally passing a `HashMap` would produce a wrap that cannot be unwrapped. **Disqualified by Accuracy.**

**Verdict:** sorted, confirmed.

### Purpose.code() vs. Purpose.ordinal()

- **code() (chosen):** spec-pinned integer stable across refactors (`domain_kek=1, dek=2, rekey_sentinel=3, health_check=4`). A code review rejects PRs that change these values.
- **ordinal():** JVM-defined as position in source; silently changes if a developer moves an enum constant or inserts a new one before an existing one. **Disqualified by Operational** (silent ciphertext invalidation).

**Verdict:** code(), confirmed. Spec R80a explicitly forbids ordinal().

### UTF-8 bytes-as-supplied vs. Unicode NFC normalization

- **Bytes-as-supplied (chosen):** caller owns identifier stability; jlsm encodes whatever bytes it's given.
- **NFC normalization:** jlsm would need a Unicode normalization library and would silently transform caller data.

**Verdict:** bytes-as-supplied, confirmed. If identifier drift occurs across runtimes, it's addressed at the ingestion layer (callers sanitize identifiers before passing them), not inside the AAD encoder. Flagged as a potential future deferred ADR in constraints.md.

## Preliminary Recommendation

**Length-prefixed TLV (custom, sorted keys, Purpose.code())** — weighted total 63, 23 points ahead of the nearest alternatives. The choice is already landed and codified in spec v9 R80a / R80a-1; this evaluation documents *why* it is the right choice against the three rejected alternatives.

## Risks and Open Questions

- **Attribute-value heterogeneity** — if a future `purpose` value needs a
  non-string attribute (e.g., a binary blob or a timestamp), the encoding
  would need a type tag. Currently all values are strings per R80a-1. A spec
  amendment would be required before adding a non-string attribute.
- **Identifier drift across runtimes** — UTF-8 bytes-as-supplied means the
  caller is responsible for identifier stability. If jlsm gains a
  non-Java client that does lossy string handling (e.g., a Python client
  that normalizes strings differently), auth failures would result. Flagged
  as a candidate future deferred ADR.
- **KB gap** — no dedicated KB subject on canonical AAD encoding
  alternatives exists. The evaluation would be stronger with such an entry,
  but given the landed choice mirrors R11 and industry precedents
  (AWS ESDK, Tink, Vault), the gap does not affect the recommendation.
