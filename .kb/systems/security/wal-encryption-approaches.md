---
title: "WAL Encryption Approaches"
aliases: ["WAL encryption", "write-ahead log encryption", "WAL TDE"]
topic: "systems"
category: "security"
tags: ["wal", "encryption", "aes-gcm", "aes-ctr", "nonce", "tde", "record-encryption"]
complexity:
  time_build: "N/A"
  time_query: "O(1) per record decrypt"
  space: "O(IV size) per record overhead"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "systems/security/jvm-key-handling-patterns.md"
decision_refs: ["wal-entry-encryption"]
sources:
  - url: "https://duckdb.org/2025/11/19/encryption-in-duckdb"
    title: "Data-at-Rest Encryption in DuckDB"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://github.com/cockroachdb/cockroach/blob/master/docs/RFCS/20171220_encryption_at_rest.md"
    title: "CockroachDB Encryption at Rest RFC"
    accessed: "2026-04-13"
    type: "rfc"
  - url: "https://github.com/facebook/rocksdb/pull/2424"
    title: "RocksDB Encryption at Rest PR"
    accessed: "2026-04-13"
    type: "pr"
  - url: "https://docs.percona.com/pg-tde/architecture/index.html"
    title: "Percona TDE for PostgreSQL — Architecture"
    accessed: "2026-04-13"
    type: "docs"
---

# WAL Encryption Approaches

## summary

WAL encryption protects mutation data at rest. Three granularities exist:
**record-level** (encrypt each WAL entry independently), **segment-level**
(encrypt the entire WAL segment file as a stream), and **field-selective**
(encrypt only sensitive fields within each record). For jlsm, record-level
encryption with AES-GCM-256 is the strongest fit: it preserves random-access
replay, provides per-record authentication, and composes cleanly with both
the mmap'd LocalWriteAheadLog and the one-file-per-record RemoteWriteAheadLog.
The critical ordering constraint is **compress-then-encrypt** -- encrypted
data has maximum entropy and does not compress.

## how-it-works

### encryption-granularity-comparison

| Approach | Random replay | Auth per record | Overhead per record | Complexity |
|----------|--------------|-----------------|---------------------|------------|
| Record-level | Yes | Yes (GCM tag) | 12B IV + 16B tag = 28B | Low |
| Segment-level (stream) | No -- must decrypt from start | Whole-segment only | 12B IV + 16B tag total | Low |
| Field-selective | Yes | Per-field tags | 28B per encrypted field | High |

**Record-level** is the dominant choice in production systems. DuckDB encrypts
each WAL entry independently with a per-entry nonce and appends the GCM tag.
CockroachDB/RocksDB use AES-CTR per file but the WAL is effectively a sequence
of independently-addressable encrypted blocks. PostgreSQL Percona TDE uses
AES-128-CTR for WAL with per-page IVs.

**Segment-level** treats the segment as a single encrypted stream. Higher
throughput but recovery must decrypt from the start. Incompatible with partial
replay. **Field-selective** encrypts only marked fields -- preserves metadata
visibility but adds per-field IV/tag overhead. Best at the application layer.

### cipher-mode-selection

| Mode | Authentication | Random access | Throughput (AES-NI) | Fit |
|------|---------------|---------------|---------------------|-----|
| AES-GCM-256 | Yes (GHASH tag) | Per-record | ~2-4 GB/s | Best for record-level |
| AES-CTR-256 | No | Per-block | ~3-5 GB/s | Use with separate HMAC |
| AES-CBC-256 | No | No (chained) | ~1-2 GB/s | Poor fit for WAL |

**AES-GCM** is recommended. It provides authenticated encryption in a single
pass -- the 16-byte tag detects tampering or corruption. AES-CTR is faster but
requires a separate integrity mechanism (HMAC or CRC), adding implementation
complexity. The JDK's `javax.crypto.Cipher` with `AES/GCM/NoPadding` uses
AES-NI and PCLMULQDQ hardware intrinsics since Java 9, making GCM overhead
negligible on modern hardware.

### nonce-iv-management

The 12-byte GCM nonce must never repeat for a given key. Three strategies:
(1) **Sequence-number derived** -- use the WAL record's sequence number
zero-padded to 12 bytes; monotonic and never reused. (2) **Counter per
segment** -- segment ID (4B) + record counter (8B); resets at rotation.
(3) **Random nonce** -- 12 random bytes; birthday bound at ~2^48 records.

**Recommendation**: Strategy 1. jlsm's `WalRecord` already carries a
sequence number. Deterministic, zero additional state, guaranteed unique.

### key-management-model

Two-tier envelope encryption (same pattern as RocksDB/CockroachDB):
**Principal Key** (caller-provided via KMS) encrypts a **Segment Encryption
Key** (SEK), which encrypts WAL records. One SEK per WAL segment, stored
encrypted in the segment header. Key rotation re-encrypts only SEK headers
-- no bulk re-encryption. For RemoteWriteAheadLog (one file per record), a
per-batch SEK in a sidecar file keeps overhead manageable.

See [jvm-key-handling-patterns.md](jvm-key-handling-patterns.md) for off-heap
key storage and zeroing discipline.

### compression-encryption-ordering

**Compress then encrypt. Never the reverse.**

Encrypted data has maximum entropy (~8 bits/byte) and is incompressible. If
the WAL already uses compression (e.g., ZSTD per-record), the pipeline is:

```
raw record --> compress (ZSTD) --> encrypt (AES-GCM) --> write
read --> decrypt (AES-GCM) --> decompress (ZSTD) --> raw record
```

The GCM authentication tag covers the compressed ciphertext, so corruption
in either the compressed data or the encryption is detected at decrypt time.
This is the standard ordering used by TLS, DuckDB, and PostgreSQL TDE.

## record-format

Per-record encrypted WAL entry layout:

```
┌─────────────────────────────────────────────────────┐
│ [4B] total entry length (plaintext)                 │
│ [12B] nonce/IV                                      │
│ [variable] encrypted payload (compressed record)    │
│ [16B] GCM authentication tag                        │
└─────────────────────────────────────────────────────┘
```

The 4-byte length prefix remains plaintext so the reader can seek to the next
record without decrypting. The nonce is plaintext (it is not secret). The tag
is appended by GCM and verified before the decrypted payload is trusted.

Overhead: 32 bytes per record (12B nonce + 16B tag + 4B length delta from
the encrypted size). For a typical 256-byte WAL record, this is ~12.5%.
For 4 KiB records, ~0.8%.

## how-systems-do-it

| System | Granularity | Cipher | Nonce strategy | Key scope |
|--------|-------------|--------|----------------|-----------|
| RocksDB | File-level stream | AES-CTR-128/256 | Random 96-bit per file + 32-bit block counter | Per-file data key, rotated weekly |
| CockroachDB | File-level (via RocksDB) | AES-CTR-128/192/256 | Same as RocksDB | Store key + data key envelope |
| DuckDB | Per WAL entry | AES-GCM-256 (default) | Per-entry nonce | Database-level key |
| PostgreSQL (Percona) | Per WAL page | AES-128-CTR | Page LSN as IV | Internal key + principal key |
| SQLCipher | Per page | AES-256-CBC | Page number as IV | Database passphrase derived |
| WiredTiger | Per page | AES-256-CBC | Counter per page | Per-table key |

## performance-and-integration

Java's `Cipher.getInstance("AES/GCM/NoPadding")` uses AES-NI + PCLMULQDQ
intrinsics (since JDK 9): ~2-4 GB/s for GCM-256, ~3-5 GB/s for CTR-256,
typically <5% wall-clock overhead on I/O-bound WAL writes. Reuse a `Cipher`
instance (reset via `init()` with new IV) and use the offset-based `doFinal`
overload to avoid per-record `byte[]` allocation.

**mmap interaction**: encrypt the record into a heap buffer, then copy to the
mmap'd segment. Never write plaintext to the mmap first -- a crash between
write and encrypt leaves plaintext on disk.

**Recovery**: crash recovery requires the decryption key before replay begins.
Bootstrap: caller provides principal key, read segment header to decrypt the
SEK, then replay records (decrypt, verify GCM tag, decompress, apply). A
failed GCM tag on the final record indicates a crash-truncated write -- skip
it, same policy as existing CRC-based detection but cryptographically strong.

## tradeoffs

**Strengths**: random-access replay with per-record integrity; sequence-number
nonce requires zero additional state; composable with ZSTD compression pipeline;
envelope encryption enables key rotation without bulk re-encryption.

**Weaknesses**: 28 bytes overhead per record (non-trivial for records <64B);
`Cipher.init()` overhead (~1us) may matter at >1M records/sec -- consider CTR
with separate HMAC for extreme throughput; KMS must be reachable at crash
recovery or WAL replay fails (fatal startup error).

**When to use**: compliance/multi-tenant data-at-rest requirements; WAL contains
PII or sensitive field values; remote WAL on S3/GCS traverses untrusted networks.
**When not to use**: dev/test; OS-level FDE (LUKS/BitLocker) is sufficient;
WAL is ephemeral and only SSTable encryption is needed.

## sources

1. [DuckDB Encryption at Rest](https://duckdb.org/2025/11/19/encryption-in-duckdb) -- per-entry WAL encryption with AES-GCM-256, nonce + tag layout
2. [CockroachDB Encryption at Rest RFC](https://github.com/cockroachdb/cockroach/blob/master/docs/RFCS/20171220_encryption_at_rest.md) -- AES-CTR file-level, two-tier key management, 4KB prefix with nonce+counter
3. [RocksDB Encryption at Rest PR #2424](https://github.com/facebook/rocksdb/pull/2424) -- EncryptedEnv, CTR cipher stream, per-file IV
4. [Percona TDE for PostgreSQL](https://docs.percona.com/pg-tde/architecture/index.html) -- AES-128-CTR for WAL, internal + principal key architecture

## Updates 2026-04-13

### misuse-resistant-ae-aes-gcm-siv

AES-GCM-SIV (RFC 8452) is nonce misuse-resistant: if a nonce repeats, the
only leak is that identical plaintexts produce identical ciphertexts -- no
authenticity collapse as with AES-GCM. Two-pass construction (POLYVAL then
AES-CTR with synthetic IV). Decryption runs within ~5% of AES-GCM speed;
encryption is ~2/3 speed due to the second pass.

**Fit for jlsm WAL**: the sequence-number nonce strategy already prevents
reuse, so GCM-SIV's main benefit is defense-in-depth against bugs in nonce
management (e.g., sequence reset after unclean recovery, duplicate WAL
replay). Pseudocode change is minimal:

```
// Drop-in replacement in WAL record encrypt path
cipher = Cipher.getInstance("AES/GCM-SIV/NoPadding")  // requires BouncyCastle
cipher.init(ENCRYPT_MODE, sek, new GCMParameterSpec(128, nonce))
// Same 12B nonce + 16B tag layout; wire format unchanged
```

**Caveat**: JDK does not ship AES-GCM-SIV natively as of Java 25 --
requires BouncyCastle or a custom provider. No AES-NI-accelerated JDK
intrinsic path exists yet, so throughput will be lower than native GCM.

### hardware-acceleration-beyond-aes-ni

- **VAES (VEX-encoded AES)**: AVX-512 extension processing 4 AES blocks
  in parallel per instruction. Available on Ice Lake+. JDK intrinsics do
  not yet exploit VAES -- a Panama-based (`MethodHandle` + `MemorySegment`)
  native call could access it directly.
- **Key Locker (Intel)**: CPU-internal key wrapping -- the AES key never
  appears in software-readable registers. Mitigates side-channel extraction.
  Requires kernel support and is not accessible from JVM without JNI/Panama.
- **ARMv8 Crypto Extensions**: AES + PMULL instructions; JDK uses these on
  aarch64 since JDK 17. Relevant for ARM-based container deployments.

### pmem-and-format-preserving-encryption

- **PMEM (persistent memory) WAL**: byte-addressable persistent storage
  changes the threat model -- plaintext persists without explicit flush.
  Encryption must happen before the store instruction, not before `force()`.
  AES-XTS (used by LUKS/BitLocker for block devices) is a natural fit for
  fixed-size PMEM pages but lacks authentication -- pair with a separate
  HMAC or use AES-GCM-SIV per page.
- **Format-preserving encryption (FPE)**: FF1/FF3-1 (NIST SP 800-38G)
  encrypts data while preserving format and length. Niche use: encrypting
  WAL sequence numbers or fixed-width metadata fields without changing the
  record layout. Not suitable for bulk payload encryption (slow, no
  authentication).

---
*Researched: 2026-04-13 | Next review: 2027-04-13*
