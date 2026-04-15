# Constraints — Encryption Key Rotation

## Scale
- Key rotation must not require a stop-the-world data rewrite
- Mixed key versions must coexist within and across SSTables during rotation window

## Resources
- No bulk re-encryption pass — leverage existing compaction I/O
- Key registry overhead: O(key versions) metadata, not O(data size)

## Complexity Budget
- Envelope encryption (KEK wraps DEKs) is the standard pattern — no novel cryptography
- Version tags in ciphertext header enable mixed-version reads

## Accuracy / Correctness
- Old keys must remain accessible until all SSTables referencing them are compacted away
- Atomic registry writes (temp + fsync + rename) prevent corruption on crash
- DET/OPE field rotation invalidates index entries — must trigger index rebuild

## Operational
- Key rotation is a caller-initiated operation, not automatic
- Convergence time equals full compaction cycle (hours to days for cold data)
- Old KEK versions never deleted until all DEKs re-wrapped

## Fit
- Must compose with per-field-key-binding (HKDF derivation from master key)
- Must compose with compaction pipeline (re-encrypt during merge)
- Must compose with wal-entry-encryption (WAL SEK rotation)
- Key registry alongside SSTable manifest
