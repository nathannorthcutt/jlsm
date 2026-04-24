---
title: "DEK Revocation Semantics vs Rotation"
aliases: ["key revocation", "crypto-shredding", "cryptographic erasure", "key disable", "key destruction", "DEK invalidation", "tenant offboarding"]
topic: "systems"
category: "security"
tags: ["encryption", "key-revocation", "crypto-shredding", "kms", "gdpr", "tenant-offboarding", "dek", "kek", "compromise-response"]
complexity:
  time_build: "O(1) per key operation"
  time_query: "O(1) decrypt attempt → failure on revoked key"
  space: "O(revoked key tombstones) metadata"
research_status: "active"
confidence: "high"
last_researched: "2026-04-23"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/encryption/"
related:
  - "systems/security/encryption-key-rotation-patterns.md"
  - "systems/security/three-level-key-hierarchy.md"
  - "systems/security/jvm-key-handling-patterns.md"
  - "systems/security/sstable-block-level-ciphertext-envelope.md"
  - "systems/security/dek-caching-policies-multi-tenant.md"
decision_refs: []
sources:
  - url: "https://en.wikipedia.org/wiki/Crypto-shredding"
    title: "Crypto-shredding — Wikipedia"
    accessed: "2026-04-23"
    type: "docs"
  - url: "https://docs.aws.amazon.com/kms/latest/developerguide/deleting-keys.html"
    title: "AWS KMS — Delete a KMS key"
    accessed: "2026-04-23"
    type: "docs"
  - url: "https://docs.aws.amazon.com/kms/latest/developerguide/enabling-keys.html"
    title: "AWS KMS — Enable and disable keys"
    accessed: "2026-04-23"
    type: "docs"
  - url: "https://www.conduktor.io/blog/crypto-shredding-in-kafka-a-cost-effective-way-to-ensure-compliance"
    title: "Crypto Shredding in Kafka — per-user DEK pattern"
    accessed: "2026-04-23"
    type: "blog"
  - url: "https://csrc.nist.gov/glossary/term/cryptographic_erase"
    title: "NIST CSRC Glossary — Cryptographic Erase"
    accessed: "2026-04-23"
    type: "docs"
  - url: "https://learn.microsoft.com/en-us/azure/security/fundamentals/encryption-atrest"
    title: "Azure data encryption at rest — KEK-mediated DEK destruction"
    accessed: "2026-04-23"
    type: "docs"
---

# DEK Revocation Semantics vs Rotation

## summary

**Rotation** introduces a new key version for future writes while old versions remain
valid for reads — it does not invalidate existing data. **Revocation** renders a key
unusable for any future operation. **Destruction** (cryptographic erasure, "crypto-
shredding") is irreversible revocation — the key material is deleted and all data
encrypted under it becomes permanently unrecoverable. These are three distinct
operations with different threat models and different signalling semantics at the
data layer. For a multi-tenant LSM like jlsm, the key-hierarchy design (root KEK →
domain KEK → DEK) makes domain-scoped crypto-shredding the primary revocation
mechanism: destroying a domain KEK renders all DEKs wrapped under it unusable
without rewriting any payload data.

## how-it-works

### four-operations-compared

| Operation | Reversible | Existing data readable? | Use case | KMS term |
|-----------|------------|-------------------------|----------|----------|
| **Rotation** | n/a (additive) | Yes, with prior key version | Scheduled hygiene; limit key lifetime | AWS "automatic rotation", GCP "new version" |
| **Revocation (disable)** | Yes | No (while disabled); Yes (after re-enable) | Incident response without data-loss commitment | AWS "disable key", GCP "disable version" |
| **Destruction (crypto-shredding)** | No | No, permanently | GDPR erasure, tenant offboarding, compromised key with no re-encrypt budget | AWS "schedule deletion", NIST "cryptographic erase" |
| **Forced re-encryption** | n/a (migration) | Yes, under new key | Compromised key where data must be retained | Implementation-defined (compaction-driven) |

### signalling-semantics

How does a reader learn a DEK has been revoked? Three patterns:

1. **Decrypt-and-fail** — Reader attempts unwrap via the KMS; the KMS returns
   "disabled" / "pending deletion" / "access denied"; decrypt fails cleanly. This
   is the default in envelope-encryption systems. Operationally simple; introduces
   a KMS roundtrip per DEK-cache miss.

2. **Pre-checked key state** — Reader consults a registry or KMS metadata call
   before attempting unwrap. Adds latency; useful when revocation is rare and the
   registry is local.

3. **Short-lived DEK cache with TTL** — Cached DEKs expire; on expiry, re-check
   the KMS. Revocation propagates within the TTL window. Standard pattern; the
   TTL bounds how long a revoked DEK remains usable in a running process.

For **crypto-shredding** specifically, revocation is achieved by destroying the
parent KEK: once the KEK is gone, wrapped DEKs cannot be unwrapped at all. No
registry lookup needed — the cryptographic primitive itself enforces inaccessibility.

### jlsm-three-tier-alignment

The three-level hierarchy (see [Three-Level Key Hierarchy](./three-level-key-hierarchy.md))
gives jlsm natural granularities for revocation:

| Revocation scope | Operation | Effect |
|------------------|-----------|--------|
| Single table/object | Destroy DEK; cached copies must be zeroised | Data for that object becomes unrecoverable; others unaffected |
| Tenant / data domain | Destroy domain KEK | All DEKs wrapped under it become unusable; effectively tenant-scoped crypto-shredding |
| Entire instance | Destroy root KEK | All domain KEKs unusable; universal shredding (last-resort) |

This is operationally clean: offboarding a tenant is a single KEK destruction, not
a per-table sweep. GDPR erasure at per-user granularity requires a per-user DEK or
sub-domain KEK, a design decision WD-03 must make.

## tradeoffs

### strengths

- **Domain KEK destruction is O(1)** — no need to locate and re-encrypt/rewrite
  payloads; the key hierarchy does the work
- **Crypto-shredding is instant at the cryptographic layer** — unlike physical
  deletion, no need to track down every replica of the data
- **Reversible disable path exists** — for incident response where the decision
  to shred is not yet committed, KMS disable is a safe intermediate state with a
  grace window (AWS default 30 days for scheduled deletion)

### weaknesses

- **Cached plaintext defeats revocation** — A DEK cache or a plaintext block cache
  (from [SSTable Block-Level Ciphertext Envelope](./sstable-block-level-ciphertext-envelope.md))
  must be purged on revocation; otherwise the running process still holds a
  decryption path. This requires an explicit cache-invalidation signal tied to
  the revocation event, **not** just KMS metadata change
- **Backups and off-site copies** — crypto-shredding only works if **every** copy
  of the data was encrypted under the destroyed key. An unencrypted backup (or a
  backup re-encrypted under a different key the customer cannot destroy) defeats
  shredding. Wikipedia notes: *"Encrypting data at the time it is written is
  always more secure than encrypting it after it has been stored without encryption."*
- **Not a cure for a compromised key** — Crypto-shredding assumes the key material
  itself is not already in an attacker's possession. If a key was exfiltrated
  before destruction, shredding only prevents *future* access to ciphertext the
  attacker hasn't yet copied
- **Memory remanence** — Plaintext keys in RAM remain vulnerable to cold-boot
  attacks and process-memory scans; see [JVM Key Handling Patterns](./jvm-key-handling-patterns.md)
  for mitigations (Arena zeroisation, bounded cache lifetime)

### compared-to-alternatives

- vs [Key Rotation](./encryption-key-rotation-patterns.md) — rotation is
  non-disruptive and additive; revocation is disruptive and terminal. Never
  substitute one for the other
- vs forced re-encryption (compaction-driven) — re-encryption is the right
  response to a *compromised* DEK where data must be retained; crypto-shredding
  is the right response to *unwanted* data (GDPR) or compromised KEKs where
  retention is not required

## implementation-notes

### revocation-events-and-cache-invalidation

When a DEK or KEK is revoked in the KMS, the jlsm instance must:

1. **Invalidate DEK cache entries** that depend on the revoked key (for KEK
   revocation: invalidate all DEKs under that KEK)
2. **Evict plaintext blocks** from the block cache that were decrypted using the
   revoked DEK — otherwise cached plaintext remains readable for the life of the
   cache entry
3. **Abort in-flight reads** that are holding a DEK reference, or let them
   complete (choice depends on whether the revocation is "stop now" or "no new
   reads")
4. **Zeroise DEK memory** per [JVM Key Handling Patterns](./jvm-key-handling-patterns.md)
   — MemorySegment.fill(0) on the confined Arena holding the key bytes

A push-based invalidation channel (KMS webhook, admin API) is the typical
mechanism. Pull-based (TTL expiry) is a safety net; it alone is insufficient for
timely revocation response.

### grace-period-model

Mirror AWS KMS's pattern: schedule deletion with a configurable grace window
(7–30 days). During the window, operations still see the key as "pending
deletion" — writes may continue or fail based on policy, but the deletion is
reversible. After the window, the key material is destroyed; all associated
ciphertext becomes unrecoverable.

### per-tenant-dek-vs-sub-domain-kek

For fine-grained erasure (e.g., GDPR per-user), the choice is:

- **Per-user DEK**: straightforward but scales poorly if user count is large
  (DEK metadata grows linearly). Kafka crypto-shredding example derives per-user
  DEKs from a master and stores them locally, avoiding KMS cost per user
- **Sub-domain KEK per user**: each user's DEKs are wrapped by a user-scoped KEK.
  Destroying the user's KEK shreds all their DEKs. More flexible but adds a
  layer to the hierarchy
- **Append-only with tombstones**: mark records as erased, re-encrypt under a
  new DEK during compaction, destroy the old DEK. Works with the
  compaction-driven re-encryption pattern

### edge-cases-and-gotchas

- **Partial destruction is not atomic** across multiple KMS regions — a key
  destroyed in one region may persist in a replica for a short window
- **Some KMS implementations cache the unwrapped key locally** inside their HSM
  for performance — destruction in the control plane may not invalidate HSM-
  local copies immediately. Check KMS vendor guarantees
- **Writes after revocation** — a writer with a cached DEK can continue
  appending ciphertext until its cache expires or is invalidated. Ensure the
  writer path also respects revocation signals
- **Backup/restore** — restoring an old snapshot can resurrect data that was
  crypto-shredded after the snapshot was taken, unless the backup itself was
  encrypted under the destroyed key

## practical-usage

### when-to-use-revocation

- Incident response: a DEK may be compromised, decision to destroy not yet made
  → disable first, investigate, then destroy or re-enable
- Temporary lockout: tenant owes payment, access suspended reversibly

### when-to-use-destruction

- GDPR right-to-erasure: destroy user-scoped KEK or sub-domain KEK
- Tenant offboarding: destroy tenant's domain KEK
- Confirmed key compromise with no retention obligation: destroy the compromised
  DEK; data is lost intentionally

### when-to-use-forced-re-encryption-instead

- Confirmed key compromise with retention obligation: re-encrypt all data under
  a new DEK via compaction; destroy the compromised DEK only after the migration
  completes (see [Encryption Key Rotation Patterns](./encryption-key-rotation-patterns.md))

## sources

1. [Crypto-shredding — Wikipedia](https://en.wikipedia.org/wiki/Crypto-shredding) — definition, threat models, limitations
2. [AWS KMS — Delete a KMS key](https://docs.aws.amazon.com/kms/latest/developerguide/deleting-keys.html) — scheduled deletion with 7–30 day grace window
3. [AWS KMS — Enable and disable keys](https://docs.aws.amazon.com/kms/latest/developerguide/enabling-keys.html) — reversible disable as revocation path
4. [Crypto Shredding in Kafka — Conduktor](https://www.conduktor.io/blog/crypto-shredding-in-kafka-a-cost-effective-way-to-ensure-compliance) — per-user DEK pattern, scaling concerns
5. [NIST CSRC Glossary — Cryptographic Erase](https://csrc.nist.gov/glossary/term/cryptographic_erase) — formal definition
6. [Azure data encryption at rest](https://learn.microsoft.com/en-us/azure/security/fundamentals/encryption-atrest) — KEK-mediated DEK destruction model

---
*Researched: 2026-04-23 | Next review: 2026-10-20*
