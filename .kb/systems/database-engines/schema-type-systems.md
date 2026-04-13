---
title: "Schema Type Systems for Document Databases"
aliases: ["field types", "schema validation", "bounded types", "binary fields", "schema migration"]
topic: "systems"
category: "database-engines"
tags: ["schema", "types", "validation", "binary", "bounded", "migration", "bson", "document-model"]
complexity:
  time_build: "N/A"
  time_query: "O(1) per field validation"
  space: "O(schema definition)"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "systems/database-engines/catalog-persistence-patterns.md"
  - "distributed-systems/replication/catalog-replication-strategies.md"
decision_refs: ["binary-field-type", "parameterized-field-bounds", "string-to-bounded-string-migration", "non-vector-index-type-review"]
sources:
  - url: "https://bsonspec.org/spec.html"
    title: "BSON Specification"
    accessed: "2026-04-13"
    type: "spec"
  - url: "https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.NamingRulesDataTypes.html"
    title: "DynamoDB Data Types"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://www.postgresql.org/docs/current/sql-createdomain.html"
    title: "PostgreSQL CREATE DOMAIN"
    accessed: "2026-04-13"
    type: "docs"
---

# Schema Type Systems for Document Databases

## Type System Taxonomy

Document databases fall on a spectrum from schemaless to strongly typed:

| Approach | Examples | Write cost | Read cost | Migration cost |
|----------|----------|-----------|-----------|----------------|
| Schema-on-read (no validation) | DynamoDB, early CouchDB | None | Caller must handle any shape | None |
| Optional validation | MongoDB (JSON Schema) | O(1) per field when enabled | None extra | Re-validate on rule change |
| Schema-on-write (enforced) | PostgreSQL domains, FaunaDB | O(1) per field always | None extra | Backfill required |

DynamoDB enforces types only on primary key attributes (must be S, N, or B).
MongoDB validates via `$jsonSchema` with configurable enforcement: `strict` (all
writes) or `moderate` (only documents already matching the schema).

## Primitive and Composite Types

### BSON type vocabulary (MongoDB)

BSON defines 20+ element types (byte-tagged): Double (0x01), String (0x02),
Document (0x03), Array (0x04), Binary (0x05), Boolean (0x08), DateTime (0x09),
Int32 (0x10), Int64 (0x12), Decimal128 (0x13), among others. Every value is
self-describing -- the type tag precedes the payload. DynamoDB uses six scalar
types (S, N, B, BOOL, NULL), two document types (M, L), and three set types
(SS, NS, BS).

jlsm-table's `FieldType` sealed interface covers 10 primitives, ArrayType,
ObjectType, VectorType, and BoundedString -- closer to the BSON model (explicit
type tags) than DynamoDB's schemaless approach.

## Binary Field Storage

### Inline vs external threshold

Systems that store binary data inline face a tradeoff: small binaries (icons,
hashes, short blobs) belong in the document; large binaries degrade read
performance when the document is deserialized but only non-binary fields are
needed.

| Strategy | Threshold | Used by |
|----------|-----------|---------|
| Always inline | No limit | BSON (16 MiB doc limit acts as ceiling) |
| Chunked external | > 255 KiB typical | MongoDB GridFS (255 KiB chunks) |
| External reference | > ~1 KiB | Application-level pattern |

BSON Binary (0x05) carries a subtype byte: generic (0x00), UUID (0x04),
encrypted (0x06), compressed column (0x07), user-defined (0x80-0xFF). This
subtype tag enables dispatch without parsing the payload.

### Design considerations for jlsm-table

```
// Pseudocode: binary field with inline/external threshold
sealed interface BinaryStorage {
    record Inline(byte[] data) {}
    record ExternalRef(String location, long size) {}
}

if (payload.length <= INLINE_THRESHOLD) store as Inline
else store as ExternalRef  // separate SSTable or blob store
```

Key questions: (1) can the serializer skip binary payloads during partial
deserialization, and (2) should binary fields participate in indexing.

## Parameterized Type Bounds

### Patterns across systems

| System | Bounded string | Constrained numeric | Array length |
|--------|---------------|--------------------:|-------------|
| PostgreSQL | `VARCHAR(N)`, `DOMAIN + CHECK` | `DOMAIN + CHECK(VALUE BETWEEN a AND b)` | No native limit |
| MongoDB | `$jsonSchema maxLength` | `$jsonSchema minimum/maximum` | `$jsonSchema maxItems` |
| DynamoDB | 400 KiB item limit only | 38-digit precision only | 32 nesting levels |
| BSON spec | No limit (int32 length prefix) | Fixed widths (int32/int64/decimal128) | No limit |

PostgreSQL `CREATE DOMAIN` defines a named type with CHECK constraints evaluated
at type-conversion time. When a new CHECK is added via `ALTER DOMAIN`, **existing
data is not re-validated** -- a known migration hazard.

### Enforcement timing

```
// Pseudocode: eager validation at write path
void validateField(FieldDefinition def, Object value) {
    switch (def.type()) {
        case BoundedString(maxLen) ->
            if (utf8ByteLength(value) > maxLen) throw ConstraintViolation;
        case BoundedNumeric(min, max) ->
            if (value < min || value > max) throw ConstraintViolation;
        case BoundedArray(maxItems, elementType) ->
            if (array.length > maxItems) throw ConstraintViolation;
            for (element : array) validateField(elementType, element);
    }
}
```

**Eager (write-time) validation** is strongly preferred for an LSM-based system:
once data is flushed to an immutable SSTable, retroactive rejection is impossible
without rewriting the table. Lazy validation (read-time or compaction-time) means
invalid data persists indefinitely in older levels.

## Schema Evolution and Migration

### Adding constraints to existing fields

Four strategies for tightening a field (e.g., STRING to BoundedString(256)):

| Strategy | Downtime | Data safety | Complexity |
|----------|----------|-------------|------------|
| **Reject-on-read** | None | Existing violations surface as errors | Low |
| **Validate-on-compaction** | None | Invalid entries flagged/quarantined during merge | Medium |
| **Background backfill scan** | None | Explicit scan + rewrite of violating docs | Medium |
| **Version-tagged schema** | None | Schema version in each doc; old version = old rules | High |

### Version-tagged schema pattern

```
// Pseudocode: schema version embedded in document header
record DocumentHeader(int schemaVersion, ...)

void validateOnRead(Document doc, Schema currentSchema) {
    if (doc.header().schemaVersion() < currentSchema.version()) {
        migrate(doc, doc.header().schemaVersion(), currentSchema.version());
    }
    validate(doc, currentSchema);
}
```

MongoDB's `moderate` validation level is a variant: documents written before
validation was enabled are exempt until next modified.

### Compaction-time migration for LSM

LSM compaction is a natural migration point -- every entry is already being
read and rewritten. A compaction merge can: (1) read the document's schema
version tag, (2) apply migration transforms, (3) write with the current schema
version, (4) quarantine documents that cannot be auto-migrated. This is
zero-downtime but not immediate -- migration completes only when all levels
containing old-version documents have been compacted through.

### Migration ordering for string-to-bounded-string

Specific to tightening STRING to BoundedString(N):

1. Deploy schema version V+1 with BoundedString(N) constraint
2. New writes are validated eagerly against the bound
3. Existing documents in SSTables retain schema version V
4. During compaction, version-V documents are checked:
   - If compliant: update version tag to V+1, write normally
   - If non-compliant: quarantine to error log or dead-letter SSTable
5. Once all levels are compacted past V, migration is complete

## Validation Strategy Comparison

| Property | Eager (write-time) | Lazy (read/compact) |
|----------|-------------------|---------------------|
| Invalid data on disk | Never | Until compacted |
| Write throughput impact | O(1) per field | None at write |
| Read complexity | No extra work | Must check + possibly reject |
| Suitable for immutable SSTables | Yes (prevents bad data entering) | Requires rewrite to fix |
| Migration friendliness | Blocks old-format writes | Allows gradual transition |

**Recommendation for LSM engines**: eager validation on the write path for new
writes; compaction-time validation as migration for data written under older
schema versions. Prevents new violations while allowing zero-downtime evolution.
