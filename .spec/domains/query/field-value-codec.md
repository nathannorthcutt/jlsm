---
{
  "id": "query.field-value-codec",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "query"
  ],
  "requires": [],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [],
  "open_obligations": [],
  "_migrated_from": [
    "F10"
  ]
}
---

# query.field-value-codec — Field Value Codec

## Requirements

### FieldValueCodec — sort-preserving binary encoding

R1. `FieldValueCodec.encode` must accept a non-null value and a non-null `FieldType`, and return a `MemorySegment` containing the sort-preserving binary encoding.

R2. `FieldValueCodec.encode` must reject a null `fieldType` with a `NullPointerException`.

R3. `FieldValueCodec.encode` must reject a null `value` with a `NullPointerException`.

R4. `FieldValueCodec.encode` must reject non-primitive and non-BoundedString field types with an `IllegalArgumentException`.

R5. For signed integer types (INT8, INT16, INT32, INT64), `FieldValueCodec` must apply sign-bit-flip encoding so that the unsigned byte comparison of encoded forms preserves the signed numeric ordering.

R6. For FLOAT32, `FieldValueCodec` must apply IEEE 754 sort-preserving encoding: if the raw int bits are negative, invert all bits; otherwise set the sign bit. This must preserve ordering for negative values, positive values, negative zero, positive zero, and positive/negative infinity.

R7. For FLOAT64, `FieldValueCodec` must apply the same IEEE 754 sort-preserving encoding as FLOAT32, using long raw bits.

R8. For FLOAT16, `FieldValueCodec` must apply IEEE 754 sort-preserving encoding on the 2-byte raw representation: if the sign bit is set, invert all bits; otherwise set the sign bit. This preserves ordering across negative and positive half-precision values.

R9. For STRING and BoundedString, `FieldValueCodec` must encode as raw UTF-8 bytes with no transformation.

R10. For BOOLEAN, `FieldValueCodec` must encode false as `0x00` and true as `0x01`.

R11. For TIMESTAMP, `FieldValueCodec` must encode identically to INT64 (sign-bit-flipped big-endian 8 bytes).

R12. `FieldValueCodec.decode` must round-trip: for every supported field type, `decode(encode(value, type), type)` must return a value equal to the original.

R13. For FLOAT32, the encoding must sort NaN values above positive infinity in unsigned byte order.

R14. For FLOAT64, the encoding must sort NaN values above positive infinity in unsigned byte order.

---

## Design Narrative

### Intent

Generated during the 2026-04-20 spec migration. See `.spec/MIGRATION.md` for
the migration plan and `.spec/_archive/migration-2026-04-20/` for the
pre-migration source spec(s) this spec was derived from.
