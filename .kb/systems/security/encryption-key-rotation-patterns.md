---
title: "Encryption Key Rotation Patterns"
aliases: ["key rotation", "envelope encryption", "KEK rotation", "lazy re-encryption"]
topic: "systems"
category: "security"
tags: ["encryption", "key-rotation", "envelope-encryption", "kek", "dek", "compaction", "re-encryption"]
complexity:
  time_build: "O(data size) for full re-encryption"
  time_query: "O(1) key lookup per record"
  space: "O(key versions) metadata"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "systems/security/jvm-key-handling-patterns.md"
  - "systems/security/three-level-key-hierarchy.md"
  - "systems/security/sstable-block-level-ciphertext-envelope.md"
  - "systems/security/dek-revocation-vs-rotation.md"
  - "algorithms/encryption/searchable-encryption-schemes.md"
decision_refs: ["encryption-key-rotation", "per-field-key-binding"]
sources:
  - url: "https://github.com/cockroachdb/cockroach/blob/master/docs/RFCS/20171220_encryption_at_rest.md"
    title: "CockroachDB RFC: Encryption at Rest"
    accessed: "2026-04-13"
    type: "rfc"
  - url: "https://www.cockroachlabs.com/docs/stable/encryption"
    title: "CockroachDB Encryption at Rest — Key Rotation Docs"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://www.mongodb.com/docs/manual/core/queryable-encryption/fundamentals/manage-keys/"
    title: "MongoDB CSFLE — Encryption Key Management"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://medium.com/@panayot.atanasov/rotating-encryption-keys-for-bank-data-with-hashicorp-vault-without-re-encrypting-a-single-record-f12c1ed923db"
    title: "Rotating Vault Encryption Keys — Zero Re-encryption Pattern"
    accessed: "2026-04-13"
    type: "blog"
  - url: "https://medium.com/@madhurajayashanka/key-rotation-in-aws-and-gcp-kms-what-really-happens-to-your-encrypted-data-7d2a12b07303"
    title: "Key Rotation in KMS: What Really Happens to Your Encrypted Data?"
    accessed: "2026-04-13"
    type: "blog"
---

# Encryption Key Rotation Patterns

## summary

Rotating encryption keys without downtime requires envelope encryption (KEK
wraps DEKs) and key versioning (version tag in ciphertext header). For LSM-tree
engines, compaction is the natural re-encryption point — data is already being
read, merged, and rewritten, so re-encrypting with the current DEK adds minimal
overhead and guarantees eventual convergence without a dedicated migration pass.

## how-it-works

### envelope-encryption-model

KEK (key encryption key) wraps DEKs (data encryption keys). Rotating the KEK
re-wraps only the DEK registry (a few hundred bytes), not any data. DEK
rotation requires data re-encryption but can be deferred to compaction.

```
  KEK v3 (active) ──wraps──▶ DEK-002 (current write key)
  KEK v2 (retired) ─wraps──▶ DEK-001 (read-only, compaction will phase out)

  Ciphertext: [4B key-version | 12B IV | payload | 16B auth-tag]
```

Version tags allow mixed DEK versions within a single SSTable — the reader
resolves per-record by looking up the DEK version in the registry.

### compaction-driven-re-encryption

The LSM-tree-specific advantage:

1. **Read** input SSTables, decrypting each record with its tagged DEK version
2. **Merge** by normal compaction logic (dedup, tombstone removal)
3. **Write** output SSTable, encrypting every record with the **current** DEK

Old DEK versions become unreferenced once all SSTables containing them compact
away. Convergence time equals a full compaction cycle — hours to days.

CockroachDB uses this model: data keys rotate weekly by default, RocksDB
compaction gradually re-encrypts all files. The store key (KEK) is rotated by
node restart; only the data key registry is re-wrapped.

### lazy-re-encryption-on-read

Alternative: decrypt on read, re-encrypt with current DEK, write back.
Concentrates effort on hot data but adds write amplification.

**Not recommended for jlsm**: SSTables are immutable — read-modify-write
violates the append-only model. Compaction-driven re-encryption is the
architecturally clean path.

### per-field-key-binding

With field-level encryption (DET, OPE, AES-GCM), different fields use different
DEKs and rotate independently:

- **Non-indexed fields** (AES-GCM): cheapest — compaction re-encrypts with no
  secondary effects
- **Indexed equality** (AES-SIV/DET): rotation invalidates index entries
  (ciphertext IS the index key) — requires index rebuild
- **Indexed range** (OPE): rotation changes sort order — requires re-ordering

### key-metadata-storage

| Location | Pros | Cons |
|----------|------|------|
| Separate registry file | Single source of truth, atomic update | Extra file to manage |
| SSTable footer | Self-contained files | Duplicated across SSTables |
| External KMS | Strongest security | Network dependency on reads |

**Recommended for jlsm**: registry file alongside the SSTable manifest.
Format: `[dek-version, wrapped-dek-bytes, kek-version, created-ts]`.
Atomic update via write-to-temp + fsync + rename. CockroachDB uses a similar
`COCKROACHDB_REGISTRY` file with periodic GC for orphaned entries.

### rollback-safety

1. **Never delete old KEK** until all DEK entries re-wrapped under new KEK
2. **Never delete old DEK** until zero live SSTables reference that version
3. **Atomic registry writes**: temp file + fsync + rename — crash leaves old
   registry intact
4. **Mixed versions are normal state**: readers dispatch by version tag, so
   partial rotation is safe — no special recovery needed

## production-systems

| System | Model | KEK Rotation | DEK Rotation |
|--------|-------|-------------|-------------|
| CockroachDB | Store key (KEK) + data key (DEK) | Node restart with new key file | Weekly default; compaction re-encrypts |
| MongoDB CSFLE | CMK wraps per-field DEKs in key vault | `rewrapManyDataKey()` — no data touch | Re-encrypt affected documents |
| AWS DynamoDB | KMS CMK wraps table DEK | Re-wrap table key via KMS | Transparent, managed by DynamoDB |
| Vault Transit | Native key versioning | `min_decryption_version` policy | `rewrap` endpoint — no plaintext exposure |

## implementation-notes

### jlsm-design-sketch

```
KeyRegistry
  ├── activeKekVersion: int
  ├── dekEntries: Map<Integer, WrappedDek>
  └── retiredKekVersions: Set<Integer>

CompactionTask.merge(inputs, output):
  currentDek = registry.currentDek()
  for record in mergedInput:
    plaintext = decrypt(record, registry.dekFor(record.keyVersion()))
    output.write(encrypt(plaintext, currentDek))
  output.metadata.dekVersionsUsed = Set.of(currentDek.version())
```

DET/OPE field rotation requires full index rebuild — schedule during
maintenance or mark affected SSTables for priority compaction.

## tradeoffs

### strengths

- **Envelope encryption**: KEK rotation is O(DEK count), not O(data size)
- **Compaction piggyback**: zero additional I/O for re-encryption in LSM engines
- **Version tags**: mixed key versions coexist safely, no big-bang migration
- **Per-field independence**: rotate non-indexed fields without index rebuild

### weaknesses

- **Convergence time**: cold data may retain old keys for days until compaction
- **Indexed field cost**: DET/OPE rotation is O(n) with potential latency spikes
- **Registry criticality**: corruption loses DEK mappings — must be backed up
- **Version accumulation**: need GC policy tied to SSTable manifest

## Updates 2026-04-13

### updatable-encryption-for-compaction

Updatable encryption (UE) allows re-encrypting ciphertext under a new key
without decrypting, using a short **update token** derived from old and new
keys. The server applies the token to each ciphertext independently -- no
plaintext exposure. This maps naturally to LSM compaction:

```
// During compaction -- no decrypt/re-encrypt cycle
updateToken = deriveToken(dekOld, dekNew)
for block in inputSSTable:
    outputBlock = UE.update(block.ciphertext, updateToken)
    output.write(outputBlock)   // never sees plaintext
```

**Unidirectional UE** (token works old->new only) provides forward security:
a compromised current key cannot decrypt past ciphertexts once the token is
discarded. Constructions exist from DDH (standard assumptions).

**Fit for jlsm**: UE eliminates the decrypt-then-encrypt overhead in
compaction-driven rotation. The tradeoff is ciphertext expansion (~32-64B
per update epoch metadata) and that UE schemes are not yet in standard
JCA providers -- would require a custom `Cipher` wrapper or a native binding.

### proxy-re-encryption

Proxy re-encryption (PRE) is the public-key analogue: a proxy transforms
ciphertext from Alice's key to Bob's key without seeing plaintext. Relevant
for multi-tenant LSM stores where per-tenant key rotation must not require
the service to hold plaintext.

### forward-secure-encryption

Forward-secure schemes derive epoch keys via a one-way function and erase
prior state. Combined with compaction-driven re-encryption, once old SSTables
are removed, prior-epoch keys are irrecoverable -- strongest key compromise
resilience. The erasure step fits naturally after SSTable deletion in the
compaction lifecycle.

---
*Researched: 2026-04-13 | Next review: 2027-04-13*
