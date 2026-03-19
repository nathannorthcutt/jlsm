---
problem: "encrypted-index-strategy"
evaluated: "2026-03-18"
candidates:
  - path: "candidate-A-capability-matrix"
    name: "Static Capability Matrix (EncryptionSpec → allowed index ops)"
  - path: "candidate-B-encrypted-index-adapter"
    name: "Encrypted Index Adapters (wrapper per EncryptionSpec)"
  - path: "candidate-C-no-index-on-encrypted"
    name: "No Index on Encrypted Fields (decrypt-then-query only)"
constraint_weights:
  scale: 2
  resources: 1
  complexity: 1
  accuracy: 3
  operational: 2
  fit: 3
---

# Evaluation — encrypted-index-strategy

## References
- Constraints: [constraints.md](constraints.md)
- Related ADR: [field-encryption-api-design](../field-encryption-api-design/adr.md)
- KB sources used:
  - [`.kb/algorithms/encryption/searchable-encryption-schemes.md`](../../.kb/algorithms/encryption/searchable-encryption-schemes.md)
  - [`.kb/algorithms/encryption/vector-encryption-approaches.md`](../../.kb/algorithms/encryption/vector-encryption-approaches.md)

## Constraint Summary
The index strategy must map each EncryptionSpec variant to the set of index and query operations it supports. Correctness is paramount — an encrypted field must never be indexed with an operation its encryption scheme doesn't support. The strategy must compose with the existing SecondaryIndex sealed interface (FieldIndex, VectorFieldIndex) and QueryExecutor.

## Weighted Constraint Priorities
| Constraint | Weight (1–3) | Why this weight |
|------------|-------------|-----------------|
| Scale | 2 | Index operations on encrypted fields add overhead but it's bounded by the encryption scheme |
| Resources | 1 | No differentiation between candidates — all CPU/RAM only |
| Complexity | 1 | High team capability — all approaches implementable |
| Accuracy | 3 | Correctness is the top constraint — wrong index results are unacceptable |
| Operational | 2 | Clear error messages for incompatible operations; graceful degradation |
| Fit | 3 | Must work with existing FieldIndex, VectorFieldIndex, IndexRegistry, QueryExecutor |

---

## Candidate A: Static Capability Matrix

Define a compile-time mapping from `(EncryptionSpec, IndexOperation)` → `allowed | prohibited`. The schema builder validates at construction: if a field has an index definition AND an EncryptionSpec, check the matrix. If the combination is prohibited, throw `IllegalArgumentException` at schema build time — fail fast, not at query time. IndexRegistry uses the matrix to decide whether to build an index for a field. QueryExecutor checks the matrix before executing a query operation on an encrypted field.

**Capability matrix (from KB research):**

| EncryptionSpec | Equality (=) | Range (<, >) | Prefix (LIKE) | ANN (nearest) | Full Scan |
|----------------|-------------|-------------|--------------|--------------|-----------|
| None | ✓ | ✓ | ✓ | ✓ | ✓ |
| Deterministic | ✓ | ✗ | ✗ | ✗ | ✓ (on ciphertext) |
| OrderPreserving | ✓ | ✓ | ✗ | ✗ | ✓ (on ciphertext) |
| DistancePreserving | ✗ | ✗ | ✗ | ✓ (approx) | ✓ (on encrypted vectors) |
| Opaque | ✗ | ✗ | ✗ | ✗ | ✗ (must decrypt) |

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 2 | 5 | 10 | Zero runtime overhead — validation at construction; indices operate on encrypted values directly |
| Resources | 1 | 4 | 4 | No additional memory structures |
| Complexity | 1 | 5 | 5 | Simple matrix lookup — no new abstractions |
| Accuracy | 3 | 5 | 15 | Fail-fast at schema build; impossible to create an incompatible index |
| Operational | 2 | 5 | 10 | Clear error at construction; QueryExecutor can report "operation not supported on encrypted field" |
| Fit | 3 | 5 | 15 | Matrix encoded as methods on EncryptionSpec (`supportsEquality()`, `supportsRange()`, etc.); IndexRegistry checks before building; no structural changes to index classes |
| **Total** | | | **59** | |

**Hard disqualifiers:** None

**Key strengths:**
- Fail-fast: incompatible index/encryption combinations are caught at schema construction, not at query time
- Zero runtime overhead: existing FieldIndex and VectorFieldIndex operate on encrypted values (DET ciphertexts, OPE ciphertexts, DCPE vectors) using the same data structures — the encryption scheme is designed so the index operation works on ciphertext
- Simple: capability methods on the sealed EncryptionSpec interface — no new classes

**Key weaknesses:**
- Matrix is static — adding a new EncryptionSpec or index operation requires updating the matrix
- DET index on low-cardinality fields leaks frequency through the index (inherent to DET, not the strategy)

---

## Candidate B: Encrypted Index Adapters

Create adapter classes for each EncryptionSpec that wrap the underlying index. `DeterministicFieldIndex` wraps `FieldIndex` and encrypts the query value before index lookup. `DistancePreservingVectorIndex` wraps `VectorFieldIndex` and encrypts the query vector. `OpaqueFieldIndex` throws on any query. Each adapter handles the encrypt-before-query step.

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 2 | 4 | 8 | Adapter indirection adds one virtual call per query — negligible but unnecessary |
| Resources | 1 | 3 | 3 | Adapter objects per field per index — small but non-zero memory |
| Complexity | 1 | 3 | 3 | Multiple adapter classes to maintain — grows with EncryptionSpec × IndexType combinations |
| Accuracy | 3 | 4 | 12 | Correct but validation happens at query time (adapter throws) rather than construction time |
| Operational | 2 | 3 | 6 | Errors at query time rather than schema build — harder to diagnose in production |
| Fit | 3 | 2 | 6 | SecondaryIndex is sealed — adding adapters requires expanding the permits list or using delegation pattern outside the sealed hierarchy |
| **Total** | | | **38** | |

**Hard disqualifiers:** None, but sealed interface friction is significant

**Key strengths:**
- Encapsulates encrypt-before-query logic in one place per encryption scheme
- Clear separation of concerns — index doesn't know about encryption

**Key weaknesses:**
- SecondaryIndex is sealed — adapters either break the sealed hierarchy or live outside it as wrappers
- N × M adapter classes (N encryption specs × M index types)
- Validation at query time, not construction — user discovers incompatible index+encryption during a production query

---

## Candidate C: No Index on Encrypted Fields

Encrypted fields cannot be indexed. Any query on an encrypted field requires full-table scan with per-document decryption. Simplest but eliminates the primary value proposition of the feature (the brief explicitly says "simple k/v loses all useful functionality").

| Constraint | Weight | Score (1–5) | Weighted | Evidence |
|------------|--------|-------------|----------|----------|
| Scale | 2 | 1 | 2 | Full scan + decrypt at millions of QPS is prohibitive |
| Resources | 1 | 5 | 5 | No additional index structures |
| Complexity | 1 | 5 | 5 | Trivially simple — just prohibit indices on encrypted fields |
| Accuracy | 3 | 5 | 15 | No wrong results possible — no index to be wrong |
| Operational | 2 | 2 | 4 | Full scan latency may exceed operation caps |
| Fit | 3 | 4 | 12 | Simple prohibition in IndexRegistry; no structural changes |
| **Total** | | | **43** | |

**Hard disqualifiers:** Contradicts brief acceptance criterion #4: "at least one field type supports indexed search while encrypted"

---

## Comparison Matrix

| Candidate | Scale (2) | Resources (1) | Complexity (1) | Accuracy (3) | Operational (2) | Fit (3) | Weighted Total |
|-----------|-----------|---------------|----------------|--------------|-----------------|---------|----------------|
| A: Static Capability Matrix | 10 | 4 | 5 | 15 | 10 | 15 | **59** |
| B: Encrypted Index Adapters | 8 | 3 | 3 | 12 | 6 | 6 | **38** |
| C: No Index on Encrypted | 2 | 5 | 5 | 15 | 4 | 12 | **43** (disqualified) |

## Preliminary Recommendation
**Candidate A: Static Capability Matrix** wins decisively (59 vs 38/43). It provides fail-fast validation at schema construction, zero runtime overhead (indices operate directly on encrypted values), and composes cleanly with the existing sealed interfaces via capability methods on EncryptionSpec. Candidate C is disqualified by the brief's acceptance criteria.

## Risks and Open Questions
- DET index on low-cardinality fields leaks frequency — must be documented as a security tradeoff, not prevented by the capability matrix
- DCPE ANN recall degradation with strong perturbation — the matrix says "supported" but recall may be poor, needs benchmark-driven guidance
