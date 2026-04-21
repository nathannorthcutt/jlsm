---
{
  "id": "wal.encryption",
  "version": 3,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "wal",
    "encryption"
  ],
  "requires": [
    "encryption.primitives-lifecycle",
    "compression.codec-contract"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "wal-entry-encryption"
  ],
  "kb_refs": [
    "systems/security/wal-encryption-approaches",
    "systems/security/encryption-key-rotation-patterns",
    "systems/security/jvm-key-handling-patterns"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F42"
  ]
}
---
# wal.encryption — WAL Encryption

## Requirements

### Opt-in configuration

R1. WAL encryption must be disabled by default. A WAL built without an encryption configuration must write and read records in plaintext, with no encryption overhead in the write or read path.

R2. The WAL builder must accept an encryption configuration that takes a KEK (Key Encryption Key) as defined by F41. When encryption is configured, all records written to that WAL instance must be encrypted.

R3. When encryption is configured, the builder must reject a null KEK with NullPointerException before the WAL instance is constructed.

R4. A WAL instance built with encryption enabled must refuse to open a segment whose header indicates encryption if no KEK is available. The refusal must throw an IOException identifying the segment path and stating that a decryption key is required.

### Compress-then-encrypt pipeline

R5. When both compression (F17) and encryption are enabled, the WAL must compress the record payload before encrypting it. The pipeline order on write must be: serialize record payload, compress payload, encrypt compressed payload, write encrypted bytes. Reversing this order (encrypting then compressing) is a correctness violation.

R6. When both compression and encryption are enabled, the WAL must decrypt the record payload before decompressing it on read. The pipeline order on read must be: read encrypted bytes, decrypt to compressed payload, decompress to record payload.

R7. When compression is disabled and encryption is enabled, the WAL must encrypt the serialized record payload directly. The compression step must be skipped entirely, not invoked with a no-op codec.

R8. When encryption is disabled and compression is enabled, the WAL must compress the record payload as specified by F17 with no encryption step. The flags byte from F17.R18 must indicate compression-only.

### Per-segment SEK

R9. Each WAL segment must use a dedicated Segment Encryption Key (SEK). The SEK must be a 256-bit (32-byte) key generated from a SecureRandom instance at segment creation time.

R10. The SEK must be encrypted (wrapped) using AES-GCM-256 with the caller's KEK and stored in the segment header. The wrapped SEK entry in the header must contain: the wrapped key ciphertext, the 12-byte wrapping nonce, and the 16-byte GCM authentication tag.

R10a. The encrypted segment header byte-level layout must be: `[1B encryption indicator (0xE1 = encrypted, 0x00 = unencrypted)] [12B wrapping nonce] [48B wrapped SEK ciphertext including GCM tag]` — total 61 bytes for encrypted segments. For unencrypted segments, the header is 1 byte (0x00). The first record begins immediately after the header. The header size must be deterministic from the encryption indicator. The encrypted indicator must be 0xE1 (not 0x01) so that zero-filled pre-allocated sparse segments are distinguishable from valid unencrypted segment headers — both have first byte 0x00, but a valid unencrypted segment must also have a valid first record frame length at offset 1. Recovery must validate that an unencrypted segment's first record has a positive frame length before attempting to parse records.

R10b. The segment header (including encryption indicator and wrapped SEK) must be written and flushed to disk before the segment is registered as the active segment. A crash before the header flush must result in a segment that recovery either skips (truncated header per R38b) or treats as empty unencrypted.

R10c. The SEK wrapping AAD must include the encryption indicator byte and the segment identifier. For LocalWriteAheadLog, the full AAD is: `[1B encryption indicator (0xE1)] [8B big-endian segment sequence number]`. For RemoteWriteAheadLog, the full AAD is: `[1B encryption indicator (0xE1)] [8B big-endian batchId]`. The RemoteWAL segment identifier for SEK wrapping is the batchId (R13a) encoded as 8-byte big-endian — not a record filename, since the SEK covers the entire batch. This binds the wrapped SEK to both its encryption status and its specific segment/batch, preventing a wrapped SEK from being copied to a different segment header or sidecar. During recovery, if an attacker has tampered with the encryption indicator or moved the header/sidecar to a different segment, the SEK unwrap will fail due to AAD mismatch.

R11. At segment open time during recovery, the WAL must read the wrapped SEK from the segment header and unwrap it using the caller's KEK. If unwrapping fails (GCM authentication tag mismatch), the WAL must throw an IOException identifying the segment path and stating that the KEK does not match the key used to wrap the SEK.

R12. On segment rollover, the WAL must generate a new SEK for the new segment. The writer must release its reference to the previous segment's SEK during the rollover operation. If no other references exist (no active readers per R49a), the SEK must be zeroed immediately. If active readers hold references, the SEK must be zeroed when the last reader releases its reference via R49a's reference counting. The writer must not retain a usable reference to the previous SEK after the new segment is opened, but the SEK memory must remain valid until all references are released. Reuse of a previous segment's SEK would cause catastrophic nonce reuse (both segments may have records with the same sequence numbers).

R13. For RemoteWriteAheadLog, the wrapped SEK must be stored in a sidecar file named `wal-sek-{batchId}.key` alongside the WAL record files. Each batch of records sharing a SEK must reference the same sidecar file.

R13a. The batchId must be the sequence number of the first record encrypted with that SEK, zero-padded to 16 digits (e.g., `wal-sek-0000000000000001.key`). Each record file must store the batchId in a header (first 8 bytes, big-endian) before the encrypted record data. During recovery, the reader extracts the batchId from the record file header, locates the corresponding sidecar file, and unwraps the SEK. The record file's filename sequence number must be greater than or equal to the batchId; recovery must log a warning if this invariant is violated.

R13b. The batchId stored in the RemoteWAL record file header must be included in the record's AAD (R28) to authenticate it. If an attacker modifies the batchId header to redirect decryption to a different SEK, GCM authentication will fail due to the AAD mismatch, and the implementation can attribute the failure to batchId tampering rather than generic corruption.

R14. The SEK wrapping nonce (used to wrap the SEK with the KEK) must be generated from SecureRandom, not derived from the sequence number. The wrapping nonce is a one-time value used once per segment creation — it is independent of the record nonce scheme.

### Nonce construction

R15. The record encryption nonce must be exactly 12 bytes (96 bits), matching the AES-GCM IV size.

R16. The nonce must be constructed by zero-padding the record's sequence number to 12 bytes in big-endian byte order. The 8-byte sequence number must occupy bytes 4-11 of the nonce. Bytes 0-3 must be 0x00.

R16a. The sequence number used to construct the nonce must be strictly greater than zero. Assigning SequenceNumber.ZERO (the pre-write sentinel) to a WAL record is a violation.

R17. The sequence number used as nonce must be the WAL record's monotonically increasing sequence number. Within a single segment, no two records may share the same sequence number.

R17a. If the next sequence number would exceed Long.MAX_VALUE, the WAL must throw IllegalStateException stating that the sequence number space is exhausted. This check must occur before encryption.

R17b. Sequence number gaps are permitted. If an append operation fails after sequence number assignment but before the record is persisted, the consumed sequence number must not be reused. Recovery must not treat sequence number gaps as corruption.

R18. If the WAL detects that a record's sequence number is less than or equal to the previous record's sequence number within the same segment, it must throw an IllegalStateException before encrypting. This prevents nonce reuse, which would catastrophically compromise AES-GCM security.

R19. The nonce must be written in plaintext as part of the encrypted record (see R21). The nonce is not secret; it is required for decryption.

R19a. The plaintext nonce reveals the record's sequence number to any party with access to the encrypted WAL file. This leaks: record count per segment, write ordering, and sequence number gaps. This is inherent to the AES-GCM nonce construction and is accepted.

R20. After a crash and recovery, the WAL must determine the highest sequence number in the recovered segment before appending new records. New records must use sequence numbers strictly greater than the recovered maximum. Failure to enforce this invariant after recovery constitutes a nonce-reuse vulnerability.

### Record format

R21. An encrypted WAL record must have the following layout: `[4B frame length (plaintext)] [flags byte] [12B nonce (plaintext)] [encrypted payload] [16B GCM authentication tag]`. The frame length, flags byte, and nonce are plaintext; the payload is ciphertext.

R22. The flags byte from F17.R18 must be extended: bit 0 indicates compression (per F17), and bit 1 must indicate encryption (1 = encrypted, 0 = unencrypted). Both bits may be set simultaneously when compression and encryption are both active.

R23. When both compression and encryption are active, the record layout must be: `[4B frame length] [flags byte (bits 0+1 set)] [12B nonce] [encrypted(codec ID + uncompressed payload size + compressed payload)] [16B GCM auth tag]`. The compression header (codec ID + uncompressed size) is encrypted alongside the compressed payload. The reader decrypts first, then reads the codec ID and uncompressed size from the decrypted output before decompressing. This prevents the plaintext compression header from leaking original record sizes.

R24. When encryption is active without compression, the record layout must be: `[4B frame length] [flags byte (bit 1 set, bit 0 clear)] [12B nonce] [encrypted payload] [16B GCM auth tag]`.

R25. The frame length field must include all bytes after itself: flags byte + nonce + encrypted payload + GCM tag. When compression is active, the compression header (codec ID + uncompressed size) is inside the encrypted payload and does not appear separately in the frame. Note: F17.R22 defines frame length for the compression-only case (CRC as a separate component). When encryption is active, F42's frame length definition (R25, R25a) takes precedence — the CRC is inside the encrypted envelope (R34) and does not appear as a separate frame component.

R25a. The maximum encrypted record payload size must be bounded such that frame length (a 4-byte signed integer) does not overflow. The frame length is: 1 (flags) + 12 (nonce) + encrypted content length + 16 (GCM tag). When compression is active, the encrypted content length includes the 5-byte compression header (codec ID + uncompressed size per R34a) plus the compressed payload bytes. The overflow check must be performed after compression (if applicable) but before encryption. The implementation must reject payloads whose total frame length would exceed Integer.MAX_VALUE, throwing IOException.

R26. The total per-record overhead from encryption is 28 bytes: 12 bytes for the nonce and 16 bytes for the GCM authentication tag. This overhead is in addition to any compression overhead from F17. When both compression and encryption are active, the 5-byte compression header (1B codec ID + 4B uncompressed size) is inside the encrypted payload and adds to the encrypted content size, not to the plaintext frame overhead.

### Encryption operation

R27. Encryption must use the `AES/GCM/NoPadding` cipher transformation via `javax.crypto.Cipher`. The key size must be 256 bits (the SEK). The authentication tag length must be 128 bits (16 bytes).

R28. The Additional Authenticated Data (AAD) for each record must be the concatenation of the segment identifier, the batch identifier (for RemoteWAL), and the 12-byte nonce. For LocalWriteAheadLog, the AAD is: `[8B big-endian segment sequence number] [12B nonce]`. For RemoteWriteAheadLog, the AAD is: `[UTF-8 bare filename from SegmentFile.toFileName()] [8B big-endian batchId from R13a] [12B nonce]`. Including the batchId in RemoteWAL AAD authenticates the SEK selection (R13b). AAD binds the ciphertext to its position — moving an encrypted record to a different segment or position, or tampering with the batchId, must cause authentication failure.

R29. The Cipher instance must be reused across records within the same segment writer by calling `Cipher.init()` with the new nonce for each record. A new Cipher instance must not be created per record.

R30. Encryption must operate on a heap-allocated byte buffer. The encrypted output must be copied to the mmap'd segment (LocalWriteAheadLog) or written to the output channel (RemoteWriteAheadLog) only after encryption completes. Plaintext must never be written to persistent storage, even transiently.

### Recovery and replay

R31. WAL recovery must require the KEK when any segment header indicates encryption. If the caller does not provide a KEK and encrypted segments are present, recovery must throw an IOException listing the encrypted segment paths.

R32. During recovery, the WAL must unwrap the per-segment SEK from each segment header before replaying that segment's records.

R32a. During recovery, the record scan for an encrypted segment must begin at the byte offset immediately following the segment header. The header size is fixed and deterministic from the encryption indicator (R10a): 61 bytes for encrypted segments, 1 byte for unencrypted segments. If the remaining byte count after the header offset is zero, the segment must be treated as empty (valid header, no records) with no warning. If the remaining byte count is less than the minimum encrypted record size (4B frame length + 1B flags + 12B nonce + 16B GCM tag = 33 bytes), the trailing bytes must be treated as a crash-truncated write and skipped.

R33. For each encrypted record during replay, the WAL must construct the nonce from the record header, decrypt the payload using the segment's SEK, and then decompress (if the compression flag is set).

R34. The CRC32 checksum must be part of the plaintext payload that is encrypted. It must not appear outside the encrypted envelope. On write, the CRC is computed over the uncompressed serialized payload (before compression), appended to the uncompressed payload, and the combined (payload + CRC) is compressed (if compression is enabled) and then encrypted. On read, the encrypted data is decrypted, decompressed (if compressed), and the CRC is verified against the uncompressed payload (excluding the CRC bytes themselves). This is consistent with F17.R21.

R34a. The uncompressed payload size field in the compression header (R23, inside the encrypted envelope) must record the byte count of the full compression input, which includes the serialized payload bytes plus the 4-byte CRC32 checksum. On decompression, the reader must decompress to exactly `uncompressed_size` bytes, then split the result into `uncompressed_size - 4` bytes of payload and the final 4 bytes of CRC32.

R35. If GCM authentication fails on the final record in a segment, the WAL must treat it as a crash-truncated write and skip the record. This is the same policy as CRC failure on the final record.

R36. If GCM authentication fails on a non-final record in a segment, the WAL must log a warning identifying the segment and sequence number, skip the record, and continue recovery. Corruption in the middle of a segment must not abort recovery of subsequent valid records.

R36a. If all records in an encrypted segment fail GCM authentication (after successful SEK unwrap), the WAL must log a warning identifying the segment and the number of failed records. This pattern may indicate systematic data corruption distinct from KEK mismatch.

R37. If GCM authentication fails because the wrong KEK was provided (causing SEK unwrap failure, R11), recovery must fail immediately for that segment. The error must be distinguishable from single-record corruption: KEK mismatch is a fatal configuration error, not a data corruption event.

R37a. The IOException thrown for KEK mismatch (SEK unwrap failure) must be a distinct exception type (e.g., `WalKeyMismatchException extends IOException`) so callers can catch it programmatically without parsing exception messages.

R38. The recovery path must handle mixed segments: some segments encrypted, others unencrypted. The segment header's encryption indicator determines the processing path per segment.

R38a. The segment header must contain a plaintext encryption indicator that distinguishes encrypted segments from unencrypted segments. The indicator must be readable without the KEK. A reader that encounters an encrypted segment header without a KEK must be able to report the situation (R31) without attempting to parse wrapped key material as record data.

R38b. If a segment header is truncated (crash during segment creation before the wrapped SEK was fully written), the WAL must treat the entire segment as unrecoverable and skip it with a warning. A truncated header must not cause an ArrayIndexOutOfBoundsException or BufferUnderflowException — the header read must validate the available byte count before parsing.

R38c. GCM authentication failures during record replay must count toward the consecutive-skip threshold defined by F17.R35. If more than the configured threshold of consecutive records fail GCM authentication within a single segment (after successful SEK unwrap), the WAL must throw an IOException indicating systematic decryption failure rather than silently skipping all records.

### Remote WAL

R39. RemoteWriteAheadLog must apply the same encryption to each record file as LocalWriteAheadLog applies to records within a segment. The encrypted record format (R21-R26) must be identical.

R40. For RemoteWriteAheadLog, the nonce must be derived from the sequence number encoded in the record filename (`wal-{seqnum:016d}.log`). The filename sequence number must match the nonce used for encryption. A mismatch must cause GCM authentication failure during recovery.

R41. The RemoteWriteAheadLog SEK sidecar file (R13) must be written before any record files that use that SEK. If the sidecar file is missing during recovery, the WAL must throw an IOException identifying the missing sidecar path and the record files that depend on it.

R41a. During RemoteWriteAheadLog recovery, the builder must identify sidecar files that have no corresponding record files and delete them. A sidecar file is orphaned if no record file in the directory has a batchId (R13a) matching the sidecar's batchId.

R41b. If a RemoteWAL sidecar file exists but SEK unwrapping fails, the error must distinguish between "sidecar corrupt or truncated" (file size does not match expected wrapped SEK size of 60 bytes: 12B nonce + 48B wrapped ciphertext including GCM tag) and "KEK does not match" (file size correct but GCM authentication failed). The `WalKeyMismatchException` (R37a) must include the sidecar path and, for truncation, the expected and actual file sizes.

R42. When RemoteWriteAheadLog rotates to a new SEK batch, it must write a new sidecar file with a new SEK before writing subsequent records. SEK rotation in RemoteWAL must set the batchId to the sequence number of the next record. The rotation boundary is implicit: records with different batchId headers (R13a) use different sidecar files.

### Input validation

R43. Configuring encryption with a KEK that is not exactly 32 bytes must throw IllegalArgumentException at builder time. The KEK must be 256 bits (32 bytes) to match the AES-GCM-256 wrapping requirement in R10. Keys of 16 or 24 bytes would use AES-128 or AES-192 respectively, which contradicts the AES-256 mandate.

R44. Attempting to read an encrypted record from a WAL opened without encryption configuration must throw an IOException stating that the record is encrypted but no decryption key was provided. The WAL must detect this from the flags byte (bit 1 set) when no SEK is available.

R45. Attempting to decrypt a record whose flags byte indicates no encryption (bit 1 clear) must not invoke the decryption path. The reader must use the flags byte, not the presence of an encryption configuration, to determine whether to decrypt.

R46. If the encrypted payload length (frame length minus nonce minus tag minus flags) is negative, the reader must treat the record as corrupt and skip it. A zero-length encrypted payload is valid (corresponds to a zero-length plaintext input to encryption).

### Concurrency

R47. The SEK must be confined to the segment writer that created it. Concurrent writers to different segments must each hold their own independent SEK. No SEK may be shared across threads without synchronization.

R48. The Cipher instance used for encryption must be confined to the segment writer thread. Each segment writer must maintain its own Cipher instance. The Cipher class is not thread-safe; sharing a Cipher instance across threads without synchronization is a correctness violation.

### Resource lifecycle

R49. When a WAL segment is closed, the unwrapped SEK held in memory must be zeroed before the memory is released. Zeroing must use `MemorySegment.fill((byte) 0)` for off-heap storage or `Arrays.fill(array, (byte) 0)` for on-heap byte arrays.

R49a. The SEK for a segment must remain available until all readers of that segment have completed. If the segment writer is rolled over while readers are active, the writer must defer SEK zeroing until the last reader closes. The implementation may use reference counting or a shared Arena with explicit lifecycle management.

R49b. For recovery (single-threaded), the SEK must be zeroed after all records in the segment have been replayed and before the next segment is processed.

R49c. If a ReplayIterator is active when the WAL is closed, the iterator must hold a reference to the SEK that prevents zeroing until the iterator is exhausted or closed. The SEK must be zeroed when the last reference is released. Close() must not block waiting for iterator references to be released — it must mark SEKs for deferred zeroing and return. After close(), any subsequent call to the iterator's `next()` must throw IllegalStateException indicating the WAL has been closed. The iterator may still hold the SEK reference for in-progress decryption of an already-fetched record, but must not initiate new decryptions.

R50. The unwrapped SEK must be stored in off-heap memory allocated from a segment-scoped Arena, not in on-heap byte arrays. On-heap copies created temporarily for JCA Cipher initialization must be zeroed in a finally block immediately after `Cipher.init()` completes.

R50a. The JCA SecretKeySpec used for Cipher initialization retains an internal copy of the key bytes that cannot be zeroed by the library (see F41.R68a). The implementation must minimize SecretKeySpec lifetime — construct it immediately before `Cipher.init()` and null the reference after initialization. This is a known residual risk.

R51. Closing an encrypted WAL instance must zero all unreferenced in-memory SEKs immediately and mark referenced SEKs (held by active ReplayIterators per R49c) for deferred zeroing. The backing Arena must not be released until all deferred-zeroing SEKs have been zeroed (i.e., until all iterator references are released). Close must be idempotent: a second close must have no effect and must not throw.

R51a. The idempotent close implementation must track whether SEK zeroing has already been performed (e.g., via a volatile boolean flag). On subsequent close calls, the SEK zeroing and Arena closing steps must be skipped. The flag must be checked before any Arena or MemorySegment operations.

R51b. The close() method must acquire the write lock before zeroing any SEK material. All in-flight append operations must complete before SEK zeroing begins. Close must not interrupt or cancel in-flight appends.

R52. If SEK unwrapping produces a temporary on-heap byte array (from JCA `Cipher.doFinal()`), that array must be zeroed in a finally block before the method returns.

## Cross-References

- Spec: F41 — Encryption Lifecycle (KEK hierarchy, key zeroization patterns)
- Spec: F17 — WAL Compression with MemorySegment Codec API (flags byte, compressed record format)
- Spec: F03 — Field-Level In-Memory Encryption (encryption specification model)
- ADR: .decisions/wal-entry-encryption/adr.md
- KB: .kb/systems/security/wal-encryption-approaches.md
- KB: .kb/systems/security/encryption-key-rotation-patterns.md
- KB: .kb/systems/security/jvm-key-handling-patterns.md

---

## Design Narrative

### Intent

This spec adds opt-in encryption to the WAL write path and recovery path using per-record AES-GCM-256. The WAL stores raw mutation data including potentially sensitive field values. While SSTable encryption (via F41) protects data at rest after flush, the WAL is the first durable surface — mutations live in the WAL before they reach SSTables. Without WAL encryption, a system with field-level encryption still leaks plaintext mutations through WAL files.

### Relationship to F41 key hierarchy

F41 defines a two-tier key hierarchy (KEK wraps DEKs) for SSTable field encryption. WAL encryption uses the same KEK but introduces a separate key tier: the Segment Encryption Key (SEK). The SEK is a WAL-specific concept — a short-lived symmetric key that encrypts all records within one WAL segment. The KEK wraps the SEK directly, without going through an SSTable DEK. This is appropriate because the WAL encrypts entire record payloads (not individual fields), and WAL segments have a much shorter lifecycle than SSTables. The SEK is generated at segment creation and discarded (zeroed) at segment close.

### Why per-record, not per-segment stream

Per-segment stream encryption (e.g., AES-CTR over the entire segment) cannot support random-access replay — recovery would need to decrypt from the start of the segment to reach any record. Per-record encryption preserves the existing recovery model: any record can be independently decrypted and verified. This is especially important for RemoteWriteAheadLog, where each record is a separate file.

### Nonce safety

The sequence-number nonce strategy is safe because: (1) sequence numbers are monotonically increasing within a segment, (2) each segment has its own SEK, so nonce uniqueness is only required per-SEK, and (3) post-crash recovery enforces that new records use sequence numbers above the recovered maximum. The critical invariant is R18 + R20: the system must never encrypt two records with the same (SEK, nonce) pair. A violation would allow an attacker to XOR two ciphertexts and recover plaintext differences — a catastrophic failure mode for GCM.

The 12-byte nonce provides 2^96 possible values. With 8-byte sequence numbers (2^63 positive values), the nonce space is never exhausted within a single segment's lifetime.

### Composition with F17 compression

The flags byte introduced by F17.R18 is extended with bit 1 for encryption. This allows four states: uncompressed+unencrypted (0x00), compressed+unencrypted (0x01), uncompressed+encrypted (0x02), compressed+encrypted (0x03). When both compression and encryption are active, the compression header (codec ID + uncompressed size) is placed inside the encrypted envelope. The reader decrypts first, then reads the compression header from the decrypted output before decompressing. This prevents the compression header from leaking original record sizes in plaintext. The nonce follows the flags byte as the last plaintext field before the encrypted payload.

### mmap safety

For LocalWriteAheadLog, plaintext must never touch the mmap'd segment. The ADR's implementation guidance is explicit: encrypt into a heap buffer, then copy to the mmap'd segment. A crash between a plaintext write and subsequent encryption would leave plaintext on disk. The encrypt-then-copy sequence ensures that the only bytes persisted are ciphertext. This is enforced by R30.

### AAD binding

The AAD (Additional Authenticated Data) includes the segment identifier and the nonce. This binds each encrypted record to its position: an attacker who copies an encrypted record from one segment to another, or reorders records within a segment, will trigger GCM authentication failure. Without AAD, a record could be replayed in a different context without detection — the ciphertext itself would decrypt successfully because GCM only authenticates what is fed to it.

### Recovery strategy

Recovery follows the existing skip-on-corruption model. The key distinction is between KEK mismatch (R37) and individual record corruption (R35-R36). KEK mismatch is a fatal configuration error — every record in the segment will fail to decrypt because the SEK cannot be unwrapped. Single-record GCM failure indicates localized corruption or a crash-truncated write, and recovery can continue past it.

### What this spec does NOT cover

- **AES-GCM-SIV**: nonce misuse-resistant variant, but requires BouncyCastle (no JDK native support). The sequence-number nonce strategy already prevents reuse; GCM-SIV is defense-in-depth, deferred per the ADR.
- **KEK rotation for WAL**: when the KEK rotates, existing segment headers contain SEKs wrapped under the old KEK. Old segments are immutable — the old KEK must remain available to replay them. KEK rotation re-wraps only the current segment's SEK.
- **VAES / Key Locker hardware acceleration**: no JDK intrinsics yet. Deferred per the ADR.
- **WAL encryption for PMEM**: different threat model (byte-addressable persistent storage). Deferred per the ADR.
- **Forced re-encryption of old WAL segments**: old segments are immutable. They are encrypted under their original SEK and KEK. Decryption requires the original KEK. If the KEK is retired, old WAL segments that have not been replayed and flushed become unrecoverable by design.

### Adversarial falsification (Pass 2 — 2026-04-15)

22 findings from structured adversarial review (all mandatory probes). All promoted.
Critical: segment header byte-level layout undefined (R10a), RemoteWAL batchId undefined
(R13a), unauthenticated encryption indicator enables silent data loss (R10c). High: KEK
size contradicts AES-256 mandate (R43 amended), recovery scan offset (R32a), SEK lifetime
vs concurrent readers (R49a-c), SEK rollover zeroing (R12 amended), AAD filename ambiguity
(R28 amended), close/append ordering (R51b), replay iterator + close race (R49c),
compression header moved inside encrypted envelope (R23 amended). Medium: sequence-0
nonce (R16a), frame length overflow (R25a), JCA SecretKeySpec residual (R50a), double-close
idempotency (R51a), CRC pipeline position clarified (R34 amended), KEK mismatch exception
type (R37a). Low: zero-length payload (R46 amended), sequence exhaustion (R17a), header
write ordering (R10b), orphaned sidecars (R41a), all-GCM-failures logging (R36a),
sequence gaps (R17b), nonce metadata leakage documented (R19a).

### Adversarial depth pass (Pass 3 — 2026-04-15)

10 fix-consequence findings from structured depth review. All promoted.
Critical: R12 zeroing contradicts R49a reference counting (R12 amended — writer releases
reference, zeroing deferred to last reader). High: batchId unauthenticated in RemoteWAL
(R13b + R28 amended — batchId in AAD), SEK wrapping AAD unspecified (R10c amended —
indicator + segment identifier with explicit LocalWAL/RemoteWAL formats), ReplayIterator
blocks close indefinitely (R49c amended — non-blocking deferred zeroing). Medium: 0x00
indicator vs sparse pre-allocation (R10a amended — 0xE1 for encrypted), valid header +
zero records (R32a amended), CRC vs uncompressed size ambiguity (R34a), corrupt sidecar
handling (R41b). Low: frame overflow formula (R25a amended).

### Adversarial verification (Pass 4 — 2026-04-15)

Zero critical findings. All 10 Pass 3 fixes verified for internal consistency,
cross-fix interactions, and dangling dependencies. Promoted: R10c RemoteWAL SEK wrapping
AAD clarified (batchId as 8-byte BE, not record filename), R51 Arena release timing
clarified (deferred with SEK zeroing), R25 cross-reference noting F42 supersedes F17.R22
when encryption is active.

### Adversarial depth pass (Pass 3 — 2026-04-15)

10 fix-consequence findings from structured depth review. All promoted.
Critical: R12 zeroing contradicts R49a reference counting (R12 amended — writer releases
reference, zeroing deferred to last reader). High: batchId unauthenticated in RemoteWAL
(R13b + R28 amended — batchId in AAD), SEK wrapping AAD unspecified (R10c amended —
indicator + segment identifier), ReplayIterator blocks close indefinitely (R49c amended —
non-blocking deferred zeroing). Medium: 0x00 indicator vs sparse pre-allocation (R10a
amended — 0xE1 for encrypted), valid header + zero records (R32a amended), CRC vs
uncompressed size ambiguity (R34a), corrupt sidecar handling (R41b). Low: frame overflow
formula (R25a amended).

### Clarification: Three-tier-hierarchy mapping (2026-04-21)

This spec's R2 accepts a "KEK" parameter at WAL builder construction. Under
`encryption.primitives-lifecycle` v5 (which this spec requires) and the
`three-tier-key-hierarchy` ADR, the three-tier key hierarchy is
**tenant KEK → data-domain KEK → DEK**. The F42 "KEK" parameter resolves
internally, as follows:

- Each tenant carries a synthetic `_wal` data domain per
  `primitives-lifecycle` R75.
- WAL envelope encryption uses a DEK belonging to that tenant's `_wal`
  domain.
- Field payload bytes embedded in WAL records are the **per-field
  ciphertext already produced at ingress** and are not re-encrypted by
  the WAL envelope (R74b in `primitives-lifecycle`).
- The grace-period invariant (R75b) binds WAL retention to Tenant KEK
  retirement: a retired Tenant KEK must remain in the registry's
  retired-references set until unreplayed WAL segments encrypted under
  `_wal` domain DEKs (whose wrapping depends on the retired Tenant KEK)
  have been replayed or compacted away.

**No F42 requirement text changes are required.** The "KEK" parameter
name is preserved; the implementation resolves it to the tenant's
`_wal` domain DEK-resolver internally. This clarification is a
Verification Note only, not an amendment.
