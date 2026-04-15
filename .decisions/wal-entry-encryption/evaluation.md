# Evaluation — WAL Entry Encryption

## Candidates

### A. Per-Record AES-GCM with Sequence-Number Nonce
Encrypt each WAL record independently with AES-GCM-256. Use the record's sequence number (zero-padded to 12 bytes) as the nonce. Per-segment encryption key (SEK) wrapped by caller's principal key.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 9 | AES-GCM at AES-NI speeds: 2-4 GB/s; < 5% overhead on I/O-bound writes | [`.kb/systems/security/wal-encryption-approaches.md#performance-and-integration`](../../.kb/systems/security/wal-encryption-approaches.md) |
| Resources | 8 | 28B overhead per record; Cipher instance reused per thread | [`.kb/systems/security/wal-encryption-approaches.md#record-format`](../../.kb/systems/security/wal-encryption-approaches.md) |
| Complexity | 9 | Standard pattern; sequence-number nonce requires zero additional state | [`.kb/systems/security/wal-encryption-approaches.md#nonce-iv-management`](../../.kb/systems/security/wal-encryption-approaches.md) |
| Accuracy | 9 | GCM tag provides authenticated encryption — detects tampering and corruption | — |
| Operational | 9 | Random-access replay; crash-truncated records detected by GCM tag failure | [`.kb/systems/security/wal-encryption-approaches.md#how-it-works`](../../.kb/systems/security/wal-encryption-approaches.md) |
| Fit | 9 | Composes with both WAL implementations, compression pipeline, key rotation | — |
| **Total** | **53/60** | | |

### B. Segment-Level Stream Encryption (AES-CTR)
Encrypt the entire WAL segment as a continuous AES-CTR stream. Single IV per segment.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 8 | CTR is 3-5 GB/s; marginally faster than GCM | [`.kb/systems/security/wal-encryption-approaches.md#cipher-mode-selection`](../../.kb/systems/security/wal-encryption-approaches.md) |
| Resources | 9 | 12B IV total per segment; minimal overhead | — |
| Complexity | 6 | Requires separate integrity mechanism (HMAC or CRC) — GCM provides this free | — |
| Accuracy | 5 | No per-record integrity unless separate HMAC added | — |
| Operational | 4 | No random-access replay — must decrypt from segment start. Incompatible with partial replay | [`.kb/systems/security/wal-encryption-approaches.md#encryption-granularity-comparison`](../../.kb/systems/security/wal-encryption-approaches.md) |
| Fit | 4 | Incompatible with RemoteWriteAheadLog (one-file-per-record model) | — |
| **Total** | **36/60** | | |

### C. Field-Selective WAL Encryption
Encrypt only sensitive fields within each WAL record; leave metadata (key, timestamp, type) in plaintext.

| Dimension | Score | Rationale | KB Source |
|-----------|-------|-----------|-----------|
| Scale | 6 | Multiple encrypt calls per record (one per sensitive field) | — |
| Resources | 5 | 28B overhead per encrypted field; multiplied by sensitive field count | — |
| Complexity | 4 | WAL record format must be restructured to separate sensitive/non-sensitive sections | — |
| Accuracy | 7 | Protects sensitive data; metadata exposure may be acceptable | — |
| Operational | 6 | Complex record parsing — must know which fields are encrypted before reading | — |
| Fit | 4 | Requires WAL to understand field-level schema — violates current WAL's opaque-payload design | — |
| **Total** | **32/60** | | |

## Recommendation
**Candidate A — Per-Record AES-GCM with Sequence-Number Nonce**. This is the DuckDB approach and the KB's recommendation. Random-access replay, per-record integrity, zero additional state for nonce management, clean composition with both WAL implementations and the compression pipeline.

## Falsification Check
- **What about extreme throughput (> 1M records/sec)?** Cipher.init() overhead is ~1us, which at 1M/sec adds ~1 second per million — significant. Mitigation: batch encrypt multiple records per Cipher.init() call by treating consecutive records as associated data. If this becomes a real bottleneck, switch to AES-CTR + HMAC. But at typical WAL throughput (10K-100K/sec), GCM overhead is negligible.
- **What about nonce reuse after unclean recovery?** Sequence numbers are monotonic and persisted. After recovery, the next sequence number is derived from the last valid WAL record + 1. No reuse risk.
- **What about KMS availability at recovery?** The principal key must be available before WAL replay. If KMS is unreachable, recovery fails with a clear error. This is inherent to any encryption scheme — not specific to this design.
