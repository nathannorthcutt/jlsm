---
title: "Encrypted Cross-Field Join Strategies"
aliases: ["encrypted join", "cross-field join", "DET join", "private set intersection"]
topic: "algorithms"
category: "encryption"
tags: ["encrypted-join", "det", "cross-field", "psi", "leakage-amplification", "re-encryption"]
complexity:
  time_build: "O(n) join token generation"
  time_query: "O(n*m) or O(n+m) depending on strategy"
  space: "O(join tokens)"
research_status: "active"
confidence: "medium"
last_researched: "2026-04-13"
applies_to: []
related:
  - "algorithms/encryption/searchable-encryption-schemes.md"
  - "algorithms/encryption/index-access-pattern-leakage.md"
  - "algorithms/encryption/prefix-fuzzy-searchable-encryption.md"
  - "systems/query-processing/lsm-join-algorithms.md"
decision_refs: ["encrypted-cross-field-joins"]
sources:
  - url: "https://ar5iv.labs.arxiv.org/html/2103.05792"
    title: "Equi-Joins over Encrypted Data for Series of Queries (Shafieinejad et al., 2021)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://cacm.acm.org/research/cryptdb-processing-queries-on-an-encrypted-database/"
    title: "CryptDB: Processing Queries on an Encrypted Database (Popa et al., CACM 2012)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://www.usenix.org/conference/usenixsecurity24/presentation/hoover"
    title: "Leakage-Abuse Attacks Against Structured Encryption for SQL (Hoover et al., USENIX Security 2024)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://link.springer.com/chapter/10.1007/978-3-642-39256-6_13"
    title: "Optimal Re-encryption Strategy for Joins in Encrypted Databases (Springer 2013)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://link.springer.com/chapter/10.1007/978-3-031-22969-5_11"
    title: "Efficient Searchable Symmetric Encryption for Join Queries (Springer 2022)"
    accessed: "2026-04-13"
    type: "paper"
  - url: "https://decentralizedthoughts.github.io/2020-03-29-private-set-intersection-a-soft-introduction/"
    title: "Private Set Intersection: A Soft Introduction"
    accessed: "2026-04-13"
    type: "blog"
---

# Encrypted Cross-Field Join Strategies

## summary

Joining on encrypted fields is straightforward when both columns use the same DET
key -- ciphertext equality implies plaintext equality. Every other scenario needs
cryptographic machinery: proxy re-encryption for cross-key equi-joins, shared
order space for OPE range joins, pre-computed tokens for SSE joins, or PSI for
privacy-preserving joins. The dominant risk is **leakage amplification** --
joining two encrypted tables reveals strictly more than either table alone.

## strategies

### 1. Same-Key DET Equi-Join

`Enc_K(a) == Enc_K(b)` iff `a == b`. Standard hash or sort-merge join on ciphertexts.

```
joinResult = hashJoin(tableA.encCol, tableB.encCol)  // no special handling
```

**Leakage**: frequency of each ciphertext in both tables; PK/FK joins reveal
full multiplicity distribution. **Use when**: same key encrypts semantically
equivalent columns (CryptDB default for declared column groups).

### 2. Cross-Key DET Equi-Join (Proxy Re-Encryption)

Different keys make ciphertexts incomparable. CryptDB's JOIN-ADJ layer lets
the server transform ciphertexts without learning plaintext.

```
delta_AB = deriveReEncKey(K_A, K_B)       // client computes once
for each row r in tableB:
    r.joinCol = reEncrypt(r.joinCol, delta_AB)  // server transforms
joinResult = hashJoin(tableA.joinCol, tableB.joinCol)
```

**Cost**: O(m) re-encryptions, cached for repeated joins. **Leakage**: same as
same-key DET post-transform, plus server learns which column pairs are
join-compatible. **Risk**: delta_AB is a long-lived secret; compromise links all
past and future ciphertexts between those columns.

### 3. OPE Range Joins

Both columns must share the **same OPE key and order space**. No practical OPE
equivalent of proxy re-encryption exists -- different OPE keys produce
incompatible orderings.

```
rangeResult = sortMergeRangeJoin(tableA.opeCol, tableB.opeLow, tableB.opeHigh)
```

**Leakage**: total order of all values across both tables. Strictly worse than
DET; enables density estimation attacks (Boldyreva et al.).

### 4. SSE Join Tokens

Pre-computed tokens reveal equality only for rows matching a WHERE predicate,
not all rows. Shafieinejad et al. (2021) generate fresh random keys per query,
preventing cross-query linkage. Token generation <2ms; per-row decryption 21-53ms.

```
token = generateJoinToken(query.predicate, K_A, K_B)
matches = evaluateJoinToken(token, tableA, tableB)
```

**Leakage**: equality of matching rows only. No super-additive leakage across
query series. **Trade-off**: requires client-side token generation per query;
incompatible with ad-hoc joins.

### 5. Private Set Intersection (PSI)

Each table's join column is treated as a set. Double-encryption (DH-based):

```
encA  = { H(x)^a for x in S_A }          // A encrypts own set
encB  = { H(y)^b for y in S_B }          // B encrypts own set
reEncA = { (H(x)^a)^b for x in S_A }    // = H(x)^(ab)
reEncB = { (H(y)^b)^a for y in S_B }    // = H(y)^(ab)
intersection = reEncA intersect reEncB    // matching keys only
```

**Cost**: O(n+m) EC exponentiations. **Leakage**: intersection cardinality and
membership only; non-matching elements hidden. Best for federated/multi-tenant
scenarios where parties should not learn each other's full key sets.

## leakage amplification

**This is the critical risk.** Applies to all strategies except PSI.

A single table leaks at most the frequency distribution of encrypted column
values. Joining creates a **cross-column equality pattern** revealing:

1. **Join multiplicity** -- how many rows in B match each row in A
2. **Co-occurrence structure** -- which row groups across tables share a value
3. **Joint frequency distribution** -- not just marginal distributions

Hoover et al. (USENIX Security 2024) showed cross-column equality leakage
enables >15% plaintext recovery on real datasets (Chicago taxi/crime/rideshare),
versus <5% from single-table attacks. The amplification is **multiplicative** --
cross-table structure constrains the frequency analysis solution space.

**LSM-specific concerns**: bloom filters and key indexes reveal membership
information that compounds with join equality patterns; sorted run structure
reveals ordering when the join key is the LSM sort key; compaction patterns
expose temporal access structure.

### Mitigations

| Mitigation | Reduces | Cost | Practical? |
|---|---|---|---|
| SSE join tokens | Cross-query linkage | Per-query token gen | Yes, known patterns |
| PSI | Non-matching elements | O(n+m) EC ops | Yes, batch/federated |
| Result padding | Result cardinality | O(pad factor) space | Yes, low cost |
| Limit join columns | Attack surface | Schema constraint | Yes, design-time |
| ORAM for join access | Access pattern | O(log N) per access | No, prohibitive |

## system comparison

| System | Join Support | Mechanism | Leakage |
|---|---|---|---|
| CryptDB | Equi-join | JOIN onion + proxy re-encryption | Full cross-column equality |
| Arx | None | Deliberately omitted | N/A |
| SEAL | Limited | Adjustable leakage profiles | Configurable |
| Shafieinejad | Equi-join | Per-query tokens | Per-query equality only |

## jlsm implications

1. **Same-key DET joins are the only zero-cost option.** Standard LSM join
   algorithms apply to ciphertexts directly when schema declares shared keys.
2. **Cross-key joins require client-side participation** -- proxy re-encryption
   or token generation, both needing key material at query time.
3. **OPE range joins: same key + same order space only.** No cross-key path.
4. **Leakage documentation is mandatory.** Every encrypted join must expose a
   leakage profile the caller can inspect. Silent encrypted joins are an
   anti-pattern.
5. **PSI is out of scope** for a single-process library but is the recommended
   approach for federated/multi-tenant joins across trust domains.
