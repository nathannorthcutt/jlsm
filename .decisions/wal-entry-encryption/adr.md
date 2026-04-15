---
problem: "wal-entry-encryption"
date: "2026-04-14"
version: 1
status: "confirmed"
supersedes: null
files:
  - "modules/jlsm-core/src/main/java/jlsm/wal/local/LocalWriteAheadLog.java"
  - "modules/jlsm-core/src/main/java/jlsm/wal/remote/RemoteWriteAheadLog.java"
  - "modules/jlsm-core/src/main/java/jlsm/wal/internal/WalRecord.java"
---

# ADR — WAL Entry Encryption

## Document Links
| Document | Path |
|----------|------|
| Constraints | [constraints.md](constraints.md) |
| Evaluation | [evaluation.md](evaluation.md) |
| Decision log | [log.md](log.md) |

## KB Sources Used in This Decision
| Subject | Role in decision | Link |
|---------|-----------------|------|
| WAL Encryption Approaches | Record-level vs segment-level, cipher selection, nonce management, record format | [`.kb/systems/security/wal-encryption-approaches.md`](../../.kb/systems/security/wal-encryption-approaches.md) |
| Encryption Key Rotation Patterns | Envelope encryption model for WAL SEKs | [`.kb/systems/security/encryption-key-rotation-patterns.md`](../../.kb/systems/security/encryption-key-rotation-patterns.md) |

---

## Files Constrained by This Decision

- `LocalWriteAheadLog.java` — encrypt record into heap buffer, then copy to mmap'd segment (never write plaintext to mmap first)
- `RemoteWriteAheadLog.java` — encrypt record before write; each file is independently decryptable
- `WalRecord.java` — extended record format with nonce prefix and GCM tag suffix

## Problem
WAL entries contain raw mutation data including potentially sensitive field values. How should WAL entries be encrypted at rest to protect this data while preserving recovery semantics?

## Constraints That Drove This Decision
- **Random-access replay**: recovery must be able to decrypt any individual record without decrypting preceding records
- **Per-record integrity**: each record must be independently verified (tamper + corruption detection)
- **Compose with both WAL implementations**: mmap'd local and one-file-per-record remote

## Decision
**Chosen approach: Per-Record AES-GCM-256 with Sequence-Number Nonce**

Each WAL record is encrypted independently with AES-GCM-256. The record's monotonic sequence number (zero-padded to 12 bytes) serves as the GCM nonce, guaranteeing uniqueness without additional state. A per-segment encryption key (SEK) is wrapped by the caller's principal key and stored in the segment header. Encryption is opt-in.

## Rationale

### Why Per-Record AES-GCM
- **Random replay**: each record is independently decryptable — recovery can start from any record
- **Authenticated encryption**: GCM tag detects both tampering and corruption in a single pass. Replaces or augments existing CRC checks
- **Sequence-number nonce**: deterministic, zero additional state, guaranteed unique across the WAL's lifetime. The existing `WalRecord` sequence number is repurposed
- **Production alignment**: DuckDB uses this exact pattern. CockroachDB/RocksDB use file-level CTR (slightly weaker per-record guarantees)

### Why not Segment-Level Stream
No random-access replay — must decrypt from segment start. Incompatible with `RemoteWriteAheadLog`'s one-file-per-record model. Requires separate integrity mechanism.

### Why not Field-Selective
Would require the WAL to understand field-level schema, violating its current opaque-payload design. The WAL stores raw serialized bytes — it does not know which bytes are sensitive.

## Implementation Guidance

### Encrypted record format

```
[4B] total entry length (plaintext — enables seeking without decryption)
[12B] nonce/IV (= sequence number, zero-padded, plaintext)
[variable] encrypted payload (compressed record bytes)
[16B] GCM authentication tag
```

Overhead: 32 bytes per record (12B nonce + 16B tag + 4B length delta). For a 256-byte record, ~12.5%. For 4 KiB records, ~0.8%.

### Compression ordering

```
Write: raw record --> compress (ZSTD) --> encrypt (AES-GCM) --> write to WAL
Read:  read from WAL --> decrypt (AES-GCM) --> decompress (ZSTD) --> raw record
```

Encrypted data has maximum entropy and does not compress. Compress-then-encrypt is mandatory.

### Key management

```
Principal Key (caller-provided) --wraps--> SEK (per-segment, library-generated)
SEK stored encrypted in segment header (for LocalWriteAheadLog)
SEK stored in sidecar file (for RemoteWriteAheadLog, one per batch)
```

SEK rotation: generate new SEK on segment rollover. Old SEKs remain in their segment headers for replay. Principal key rotation re-wraps only the current SEK — old segment headers are immutable.

### LocalWriteAheadLog integration
Encrypt into a heap buffer, then copy to the mmap'd segment. Never write plaintext to the mmap first — a crash between write and encrypt leaves plaintext on disk.

```java
// In LocalWriteAheadLog.append():
byte[] compressed = compress(recordBytes);
byte[] encrypted = aesGcmEncrypt(sek, seqNum, compressed);
// Copy encrypted bytes to mmap'd segment
segment.asSlice(writePosition, encrypted.length)
       .copyFrom(MemorySegment.ofArray(encrypted));
```

### Recovery
1. Caller provides principal key at startup
2. Read segment header, unwrap SEK
3. Replay: for each record, decrypt with SEK using nonce from record header
4. GCM tag failure on the final record → crash-truncated write → skip (same policy as existing CRC-based detection)
5. GCM tag failure on a non-final record → corruption → log warning, skip record

### Opt-in behavior
WAL encryption is configured via builder:

```java
LocalWriteAheadLog.builder()
    .directory(walDir)
    .encryption(principalKey)  // opt-in; omit for unencrypted WAL
    .build();
```

## What This Decision Does NOT Solve
- AES-GCM-SIV for nonce misuse resistance (requires BouncyCastle — no JDK native support)
- VAES/Key Locker hardware acceleration (requires Panama FFM JNI — no JDK intrinsics yet)
- WAL encryption for PMEM/persistent memory (different threat model)

## Conditions for Revision
This ADR should be re-evaluated if:
- Throughput exceeds 1M records/sec and GCM `Cipher.init()` overhead becomes measurable — consider AES-CTR + separate HMAC
- JDK ships native AES-GCM-SIV support — switch for nonce misuse resistance
- Field-selective WAL encryption is required — would need WAL schema awareness

---
*Confirmed by: architect agent (WD-09 batch, pre-accepted) | Date: 2026-04-14*
*Full scoring: [evaluation.md](evaluation.md)*
