---
{
  "id": "query.encrypted-positional-posting",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "query",
    "encryption"
  ],
  "requires": [
    "encryption.primitives-variants"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [],
  "open_obligations": [],
  "_extracted_from": "encryption.primitives-variants (R42,R43,R44,R45)"
}
---
# query.encrypted-positional-posting — Encrypted Positional Posting Codec

## Requirements

R1. The positional posting codec must encrypt term positions using order-preserving encryption. Encrypted positions must preserve the relative ordering of plaintext positions, enabling phrase queries (consecutive positions) and proximity queries (position difference within a threshold) on encrypted data.

R2. The positional posting encoding format must be: 4-byte big-endian document ID length, document ID bytes, 4-byte big-endian position count, followed by position count OPE-encrypted positions as 8-byte big-endian longs.

R3. The positional posting codec must reject null document IDs and null position arrays at the public API boundary with NullPointerException.

R4. Positional posting deserialization must validate that the encoded byte array is at least the minimum posting size (4 + 1 + 4 + 8 = 17 bytes). Undersized input must be rejected with an IllegalArgumentException, not an ArrayIndexOutOfBoundsException.
