---
title: "SSTable Block-Level Ciphertext Envelope and Key-ID Signalling"
aliases: ["SSTable encryption envelope", "block ciphertext framing", "per-block encryption", "key-id signalling"]
topic: "systems"
category: "security"
tags: ["sstable", "encryption", "ciphertext-envelope", "aes-gcm", "aes-ctr", "key-id", "nonce", "block-encryption", "tde"]
complexity:
  time_build: "O(block) per-block encrypt during flush/compaction"
  time_query: "O(1) per-block decrypt on cache miss"
  space: "~28–40 bytes per block header overhead"
research_status: "active"
confidence: "high"
last_researched: "2026-04-23"
applies_to:
  - "modules/jlsm-core/src/main/java/jlsm/sstable/"
  - "modules/jlsm-core/src/main/java/jlsm/encryption/"
related:
  - "systems/security/wal-encryption-approaches.md"
  - "systems/security/encryption-key-rotation-patterns.md"
  - "systems/security/three-level-key-hierarchy.md"
decision_refs: []
sources:
  - url: "https://github.com/cockroachdb/cockroach/blob/master/docs/RFCS/20171220_encryption_at_rest.md"
    title: "CockroachDB RFC: Encryption at Rest"
    accessed: "2026-04-23"
    type: "rfc"
  - url: "https://github.com/facebook/rocksdb/blob/master/include/rocksdb/env_encryption.h"
    title: "RocksDB env_encryption.h — EncryptionProvider / BlockAccessCipherStream"
    accessed: "2026-04-23"
    type: "repo"
  - url: "https://docs.pingcap.com/tidb/stable/encryption-at-rest/"
    title: "TiDB/TiKV — Encryption at Rest"
    accessed: "2026-04-23"
    type: "docs"
  - url: "https://duckdb.org/2025/11/19/encryption-in-duckdb"
    title: "Data-at-Rest Encryption in DuckDB (1.4 block-based design)"
    accessed: "2026-04-23"
    type: "docs"
  - url: "https://github.com/duckdb/duckdb/pull/17275"
    title: "DuckDB PR #17275 — Block based encryption"
    accessed: "2026-04-23"
    type: "pr"
  - url: "https://dev.mysql.com/doc/refman/8.4/en/innodb-data-encryption.html"
    title: "MySQL 8.4 Reference Manual — InnoDB Data-at-Rest Encryption"
    accessed: "2026-04-23"
    type: "docs"
---

# SSTable Block-Level Ciphertext Envelope and Key-ID Signalling

## summary

For an LSM on-disk file (SSTable), two orthogonal design choices define the ciphertext
envelope: **granularity** (whole-file vs per-block framing) and **key-id signalling**
(how a reader locates which DEK encrypted the data). Production systems pick one of
three patterns: external registry (CockroachDB, TiKV), per-file prefix header (RocksDB
generic encrypted env, MySQL InnoDB tablespace header), or per-block header with
plaintext nonce/tag (DuckDB 1.4). For jlsm — whose SSTable reader is already
block-oriented with a block cache — per-block AES-GCM framing with a per-file footer
carrying the key-id is the natural fit.

## how-it-works

### design-space-axes

| Axis | Options | Key constraint |
|------|---------|----------------|
| Granularity | whole-file / per-file-with-block-cipher-stream / per-block | Must support random block read without whole-file decrypt |
| Key-id location | external registry / per-file prefix / per-block header / filename-encoded | Must survive file-copy and backup-restore |
| Cipher mode | AES-CTR + separate MAC / AES-GCM / AES-GCM-SIV | Nonce-reuse resistance, auth built-in, CPU cost |
| Nonce strategy | random 12-byte / deterministic (file-id ‖ block-off) / counter | 2^96 collision headroom is enough if random |
| Auth scope | per-block tag / per-file MAC / per-entry tag | Per-block aligns with block cache; per-entry is overhead-heavy |

### production-systems-compared

| System | Granularity | Key-id location | Cipher | Auth |
|--------|-------------|-----------------|--------|------|
| **CockroachDB** | File (CTR stream, random-access within file) | External `COCKROACHDB_REGISTRY` file, path-keyed | AES-128/192/256-CTR | Separate (no per-block GCM) |
| **TiKV** | File (CTR stream) | External encryption-meta file (+ file-dictionary-log v4.0.9+) | AES-128/192/256-CTR, SM4-CTR | Separate |
| **RocksDB `env_encryption`** | File with page-aligned prefix header (default 4 KiB) | `GetPrefixLength()` embedded header, provider-defined format | Pluggable (default CTR) | Provider-defined |
| **MySQL InnoDB TDE** | Page-level (each 16 KiB page) | Tablespace key stored in tablespace header, wrapped by master key | AES-CBC for data, AES-ECB to wrap tablespace key | Separate |
| **DuckDB 1.4** | Per-block (256 KiB blocks) | Implicit — single-key per database file; canary verifies on open | AES-GCM-256 or AES-CTR-256 | Per-block 16-byte GCM tag |

### the-three-dominant-patterns

**Pattern A — External registry**
- One file maps path → `{key_id, iv, method}`; registry is itself encrypted by the KEK
- Pros: no wasted bytes in data files; easy to dump/audit; centralizes rotation metadata
- Cons: registry is an extra durability target; file moves must carry registry state; registry corruption = "all files undecipherable"

**Pattern B — Per-file prefix header**
- First N bytes of every data file contain `{marker, key_id, iv_base, algorithm}`, page-aligned to preserve block offsets
- Pros: files are self-describing, portable across hosts, backup/restore "just works"
- Cons: fixed overhead per file (4 KiB typical); per-file rotation requires rewriting the whole file; does not authenticate individual blocks

**Pattern C — Per-block header with per-file footer**
- Each block carries its own 12-byte nonce + 16-byte GCM tag; per-file footer (or main-file header, or external registry) carries key-id and algorithm parameters
- Pros: block-cache reads decrypt-on-miss at block granularity; GCM tag authenticates per-block (no separate MAC); random access without side-channels
- Cons: ~28 bytes overhead per block (0.7% for 4 KiB, 0.04% for 64 KiB); nonce uniqueness across a file requires care

### jlsm-design-sketch

Given existing `TrieSSTableWriter` / `TrieSSTableReader` with 4 KiB data blocks
(target), block cache keyed by block offset, and v5 footer carrying per-block CRC32
integrity (PR #48), the fit is:

- **Granularity: per-block.** Block cache holds plaintext; decrypt on cache miss
- **Cipher: AES-GCM-256.** Auth tag replaces the CRC32 (per-block integrity + confidentiality in one primitive). GCM-SIV is a future upgrade if nonce-reuse risk becomes material
- **Block layout (encrypted):** `[12-byte nonce][ciphertext || 16-byte GCM tag]` — plaintext block is `ciphertext`'s length minus the tag
- **AAD:** `file-id ‖ block-offset ‖ level ‖ algorithm-tag` — binds block to its file location so a block from another file cannot be swapped in without tag failure
- **Key-id signalling: per-file footer.** Footer holds `{dek_handle, kek_ref, algorithm, nonce_strategy}`. Footer is authenticated by a separate footer-MAC under the DEK (prevents tampering with key-id to redirect decryption). Footer is tiny (tens of bytes); no per-block key-id needed because a single file has a single DEK
- **Nonce strategy:** deterministic `(file-id-low-64 ‖ block-counter-32)` — guarantees uniqueness within a file-id without RNG cost. File-id is unique per SSTable creation, so nonce space never repeats under the same DEK. Fallback to random 12-byte if determinism is undesirable

This keeps block cache semantics clean, makes single files portable (no external
registry), and authenticates both confidentiality and block-to-position binding.

## tradeoffs

### strengths

- **Per-block decrypt is cheap and composable** with the existing block cache — no change to cache keying or eviction policy
- **Per-block GCM tag replaces per-block CRC32** — one primitive gives both integrity and confidentiality (no double-dipping)
- **Self-contained files** — no external registry to lose or corrupt; each SSTable is independently decryptable given the DEK
- **Deterministic nonces** (file-id ‖ block-counter) eliminate RNG dependency in the write path and guarantee uniqueness under a fixed DEK

### weaknesses

- **No cross-file authentication** — an attacker who can swap whole files cannot be caught by per-block GCM alone; manifest/level-metadata MAC is required at the tree layer
- **Block overhead scales with block count** — negligible for 64 KiB blocks (0.04%), noticeable for 512-byte blocks (5%); jlsm's 4 KiB target is fine (~0.7%)
- **GCM nonce reuse is catastrophic** — the deterministic scheme must guarantee `file-id` uniqueness across all files ever written under a DEK. If a DEK is long-lived, this means file-ids must come from a monotonic generator that never wraps within a DEK's lifetime

### compared-to-alternatives

- See [WAL Encryption Approaches](./wal-encryption-approaches.md) — the WAL's per-record envelope is the same pattern at record granularity; SSTable block-level is the structural analogue
- See [Key Rotation Patterns](./encryption-key-rotation-patterns.md) for how compaction-driven re-encryption interacts with this envelope (new file = new DEK)

## implementation-notes

### per-block-envelope-layout

```
┌──────────────────────────────────────────────────────────────────────┐
│                            SSTable File                              │
├──────────────────────────────────────────────────────────────────────┤
│  DataBlock 0  │  DataBlock 1  │  ...  │  IndexBlock  │  BloomBlock   │
│  ┌─────────┬─────────────┬───────┐                                   │
│  │ 12-byte │  ciphertext │16-byte│                                   │
│  │  nonce  │  (N bytes)  │  tag  │                                   │
│  └─────────┴─────────────┴───────┘                                   │
│                                                                      │
│  Footer: { dek_handle, kek_ref, algorithm_tag,                       │
│            nonce_strategy, footer_mac } (authenticated)              │
└──────────────────────────────────────────────────────────────────────┘
```

### aad-contents

AAD for each block's GCM operation: `file_id_u64 ‖ block_offset_u64 ‖ level_u32 ‖
algorithm_tag_u16`. This binds the ciphertext to its position and prevents
cross-file or cross-offset replay.

### footer-mac

The footer carries the key-id needed to decrypt blocks, so it must be
authenticated — otherwise an attacker who edits the footer's `dek_handle` causes
the reader to try the wrong DEK (not a confidentiality break, but a denial
of correct reads). Use a 16-byte HMAC-SHA-256 truncated, or a separate GCM seal
with AAD = `file_id ‖ "footer"`.

### edge-cases-and-gotchas

- **Bloom filter blocks must be encrypted too** — leaving them plaintext leaks
  key-presence information for probed hashes
- **Index blocks must be encrypted too** — otherwise key prefixes are exposed
- **Partial-block reads** (tail block smaller than target size) — tag length is
  fixed, nonce format is fixed, only ciphertext length varies
- **Block cache eviction vs DEK lifecycle** — if a DEK is zeroised, cached
  plaintext blocks decrypted under it should also be evicted (see
  [JVM Key Handling Patterns](./jvm-key-handling-patterns.md))

## practical-usage

### when-to-use

- LSM SSTable format where reader is already block-oriented and block-cached
- Random-access workloads where decrypting whole files per read is wasteful
- Backup/restore workflows where files must be portable without an external registry

### when-not-to-use

- Very small blocks (<1 KiB) where 28-byte per-block overhead is material
- Streaming-only workloads with no random access — file-level AES-CTR is simpler
  and marginally cheaper

## sources

1. [CockroachDB RFC: Encryption at Rest](https://github.com/cockroachdb/cockroach/blob/master/docs/RFCS/20171220_encryption_at_rest.md) — two-tier store-key/data-key, registry pattern, CTR mode rationale
2. [RocksDB env_encryption.h](https://github.com/facebook/rocksdb/blob/master/include/rocksdb/env_encryption.h) — `EncryptionProvider.GetPrefixLength()`, `BlockAccessCipherStream`, `GetMarker()`
3. [TiKV Encryption at Rest](https://docs.pingcap.com/tidb/stable/encryption-at-rest/) — file-dictionary-log metadata optimization, absolute-path file tracking
4. [Data-at-Rest Encryption in DuckDB](https://duckdb.org/2025/11/19/encryption-in-duckdb) — 40-byte block header, 12+16 split, encrypted checksum, canary verification
5. [DuckDB PR #17275 — Block based encryption](https://github.com/duckdb/duckdb/pull/17275) — nonce generation, AAD field reserved-but-unused, KDF/cipher metadata bytes
6. [MySQL InnoDB Data-at-Rest Encryption](https://dev.mysql.com/doc/refman/8.4/en/innodb-data-encryption.html) — tablespace-header-embedded wrapped key, AES-CBC data + AES-ECB wrap

---
*Researched: 2026-04-23 | Next review: 2026-10-20*
