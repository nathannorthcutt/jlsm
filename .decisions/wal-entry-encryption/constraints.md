# Constraints — WAL Entry Encryption

## Scale
- WAL write path is latency-critical; encryption overhead must be < 5% at AES-NI speeds
- Recovery replay must support random-access per-record decryption

## Resources
- Reuse Cipher instance per thread to avoid per-record allocation
- 28 bytes overhead per record (12B nonce + 16B tag) — acceptable for records > 64B

## Complexity Budget
- Record-level AES-GCM with sequence-number nonce — standard pattern, no novel crypto
- Compose with existing WalRecord binary format and both WAL implementations

## Accuracy / Correctness
- GCM auth tag provides per-record integrity — replaces or augments CRC
- Nonce must never repeat for a given key — sequence number guarantees this
- Compress-then-encrypt ordering is mandatory (encrypted data does not compress)

## Operational
- Encryption is opt-in — unencrypted WAL remains the default
- Recovery requires decryption key before replay begins
- GCM tag failure on final record indicates crash-truncated write — skip, same as CRC failure

## Fit
- Must compose with LocalWriteAheadLog (mmap'd segments) and RemoteWriteAheadLog (one-file-per-record)
- Must compose with WAL compression (compress-then-encrypt pipeline)
- Must compose with encryption-key-rotation (WAL Segment Encryption Key rotation)
- Key management: envelope encryption — caller's principal key wraps per-segment SEK
