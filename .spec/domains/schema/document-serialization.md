---
{
  "id": "schema.document-serialization",
  "version": 1,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "schema",
    "serialization"
  ],
  "requires": [
    "F13"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [],
  "kb_refs": [],
  "open_obligations": [],
  "_migrated_from": [
    "F14"
  ]
}
---

# schema.document-serialization — Document Serialization

## Requirements

### Serialization (JSON)

R1. `toJson()` must serialize the document to a compact JSON string (no indentation). `[EXPLICIT]`

R2. `toJson(boolean pretty)` must serialize with 2-space indentation when `pretty` is true, compact when false. `[EXPLICIT]`

R3. `fromJson(String json, JlsmSchema schema)` must deserialize a JSON string into a `JlsmDocument` conforming to the given schema. `[EXPLICIT]`
