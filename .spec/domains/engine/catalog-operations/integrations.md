---
{
  "id": "engine.catalog-operations.integrations",
  "version": 1,
  "status": "ACTIVE",
  "state": "DRAFT",
  "domains": [
    "engine"
  ],
  "requires": [],
  "invalidates": [],
  "decision_refs": [],
  "kb_refs": [],
  "parent_spec": "engine.catalog-operations",
  "_split_from": "engine.catalog-operations"
}
---

# engine.catalog-operations.integrations — Cross-Module Integrations (Table Migration, Remote Serialization)

This spec was carved from `engine.catalog-operations` during a domain subdivision. The
parent retains cross-cutting requirements that span multiple sub-domains;
the requirements below are specific to this concern.

R49. During partition migration cutover (F33 R34-R38), the `MigrationCoordinator` must submit the ownership metadata update (new owning node, incremented ownership epoch) to the catalog leader as a catalog Raft entry.

R50. The catalog leader must validate the migration ID in the ownership update against the currently active migration for that partition (F33 R38). A mismatched migration ID must be rejected with an error response.

R51. The catalog ownership update must be atomic: the partition's owning node and ownership epoch must change in a single Raft log entry. The catalog epoch must also increment.

R52. After the catalog ownership update is committed, the catalog leader must respond to the `MigrationCoordinator` with a confirmation containing the new catalog epoch and the new ownership epoch.

R53. The ownership update must be propagated to all cluster nodes via the standard epoch dissemination mechanism (R36-R38). Nodes that receive the updated epoch must refresh their partition routing caches.

R54. Until the ownership update is committed in the catalog, the source node must continue serving reads for the migrating partition (F33 R36). The catalog serves as the single authority for partition ownership.

### Interaction with F36 (Remote Serialization)

R55. Atomic DDL operations must be encodable using the F36 remote serialization protocol. The protocol must define a new operation code for ATOMIC_DDL (reserved code 0x40) carrying a serialized batch of DDL operations.

R56. The ATOMIC_DDL request payload must begin with a 4-byte big-endian int32 operation count.

R56a. Each sub-operation in the ATOMIC_DDL payload must be encoded as: 1-byte operation type (0x01=CREATE, 0x02=DROP, 0x03=ALTER), followed by operation-specific fields. CREATE fields must match F36 R13 encoding. DROP fields must match F36 R14 encoding. ALTER fields must contain: table name (string per F36 R11), followed by the `SchemaUpdate` encoded as a sequence of field-level changes (each: 1-byte change type, field name string, and type-specific parameters per F36 R11-R12).

R57. The ATOMIC_DDL response must follow the F36 response envelope (F36 R27-R28). On success, the response must contain the new catalog epoch (8-byte int64). On failure, the error must carry the index of the failing operation and the specific error code.

R58. Catalog epoch queries must be encodable as a new operation code CATALOG_EPOCH (reserved code 0x41) with an empty request payload. The success response must contain: 8-byte int64 current catalog epoch, 8-byte int64 catalog leader node ID hash.

### Configuration



---

## Notes
