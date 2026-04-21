---
{
  "id": "serialization.remote-serialization",
  "version": 2,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "serialization"
  ],
  "requires": [
    "engine.in-process-database-engine",
    "transport.multiplexed-framing",
    "transport.traffic-priority",
    "engine.handle-lifecycle",
    "engine.cross-table-transactions"
  ],
  "invalidates": [],
  "amends": [
    "transport.multiplexed-framing.R2a"
  ],
  "amended_by": null,
  "decision_refs": [
    "engine-api-surface-design",
    "connection-pooling"
  ],
  "kb_refs": [
    "distributed-systems/networking/transport-framing-patterns"
  ],
  "open_obligations": [],
  "_migrated_from": [
    "F36"
  ]
}
---
# serialization.remote-serialization — Remote Serialization

## Requirements

### Operation type registry

R1. The protocol must define a fixed operation type registry. Each operation
code must be a 2-byte big-endian unsigned integer in the range 0x0000-0xFFFF.
Operation code 0x0000 is reserved and must not be assigned. Codes 0x0001-0x00FF
are reserved for core engine operations defined in this spec. Codes
0x0100-0xFFFF are reserved for future extension and must not be used by this
protocol version.

R2. The registry must assign the following core operation codes:

| Code | Operation |
|------|-----------|
| 0x01 | CREATE_TABLE |
| 0x02 | DROP_TABLE |
| 0x03 | GET_TABLE |
| 0x04 | LIST_TABLES |
| 0x05 | TABLE_METADATA |
| 0x06 | ENGINE_METRICS |
| 0x10 | INSERT |
| 0x11 | GET |
| 0x12 | UPDATE |
| 0x13 | DELETE |
| 0x14 | QUERY |
| 0x15 | SCAN |
| 0x20 | HANDLE_ACQUIRE |
| 0x21 | HANDLE_RELEASE |
| 0x22 | HANDLE_HEARTBEAT |
| 0x30 | BEGIN_TRANSACTION |
| 0x31 | COMMIT_TRANSACTION |
| 0x32 | ABORT_TRANSACTION |
| 0x33 | TXN_TABLE |
| 0x34 | TXN_INSERT |
| 0x35 | TXN_GET |
| 0x36 | TXN_UPDATE |
| 0x37 | TXN_DELETE |

R3. A received operation code that does not map to a known operation must
result in an error response (R27) with error code UNKNOWN_OPERATION. The
server must not close the connection.

### F19 MessageType registration

R4. This spec amends F19 R2a to add a new MessageType enum constant:
ENGINE_OPERATION=0x07. This value must be appended to the existing F19
MessageType enum (after STATE_DELTA=0x06).

R5. All engine requests and responses must use the ENGINE_OPERATION
MessageType in the F19 frame type tag. The 2-byte operation code in the
envelope header (R6) provides fine-grained dispatch within this single
MessageType.

### Message envelope

R6. Every message body within an F19 ENGINE_OPERATION frame must begin with
a 4-byte envelope header containing, in order: 2-byte big-endian operation
code, 2-byte big-endian protocol version.

R7. The initial protocol version must be 1.

R8. A received message with a protocol version that the receiver does not
support must result in an error response with error code
UNSUPPORTED_VERSION. The error response must include the highest version the
receiver supports as a 2-byte big-endian unsigned integer appended after the
error message string (R28). The connection must not be closed.

### Request encoding

R9. After the 4-byte envelope header, each request must carry an operation-
specific payload whose format is determined solely by the operation code.
No additional type markers are encoded within the payload.

R10. String fields in request and response payloads must be encoded as
4-byte big-endian signed int32 length prefix followed by that many bytes of
UTF-8. A length of 0 encodes an empty string (zero following bytes). A
length of -1 encodes a null string (no following bytes). Any other negative
length value is invalid.

R10a. String bytes must be valid UTF-8. A receiver that encounters bytes
that are not valid UTF-8 within a string field must return PAYLOAD_MALFORMED.

R11. Binary fields (keys, document bodies) must be encoded as 4-byte
big-endian signed int32 length prefix followed by that many raw bytes. A
length of 0 encodes an empty binary (zero following bytes). A length of -1
encodes a null binary (no following bytes). Any other negative length value
is invalid.

R12. Integer fields must be encoded as big-endian. 32-bit integers use 4
bytes (signed int32). 64-bit integers use 8 bytes (signed int64). Each
field's width is specified in its payload definition. Boolean fields must be
encoded as a single byte: 0x00 for false, 0x01 for true. Any other boolean
byte value is invalid and must result in PAYLOAD_MALFORMED.

### DDL request payloads

R13. CREATE_TABLE request payload must contain: table name (string), schema
definition (encoded per R18).

R14. DROP_TABLE request payload must contain: table name (string).

R15. GET_TABLE request payload must contain: table name (string).

R15a. LIST_TABLES request payload must be empty (zero bytes after the
envelope header).

R16. TABLE_METADATA request payload must contain: table name (string).

R17. ENGINE_METRICS request payload must be empty (zero bytes after the
envelope header).

### Schema encoding

R18. A schema must be encoded as: 4-byte int32 field count, followed by
each field definition in order. The field count must be non-negative. A
field count of zero encodes a schema with no fields.

R18a. Each field definition must contain: field name (string), 1-byte field
type tag, followed by type-specific parameters. The field type tag values
must correspond to the F13 SchemaFieldType enum ordinals: STRING=0x00,
BOUNDED_STRING=0x01, INT=0x02, LONG=0x03, DOUBLE=0x04, BOOLEAN=0x05,
BINARY=0x06, DOCUMENT=0x07, VECTOR=0x08. An unrecognized field type tag
must result in SCHEMA_INVALID.

R19. Type-specific parameters for bounded types (e.g., BOUNDED_STRING with
min and max length as two int32 values) must be encoded immediately after
the field type tag using the same integer encoding rules (R12). A field type
with no parameters must have zero additional bytes after the type tag.

### Handle management payloads

R20. HANDLE_ACQUIRE request payload must contain: table name (string),
1-byte priority level (0x00=NORMAL, 0x01=HIGH, 0x02=ADMIN), caller tag
(string, nullable). An unrecognized priority byte value must result in
INVALID_ARGUMENT.

R21. HANDLE_RELEASE request payload must contain: 8-byte big-endian int64
handle ID.

R22. HANDLE_HEARTBEAT request payload must contain: 8-byte big-endian int64
handle ID.

R22a. On receiving HANDLE_HEARTBEAT, the server must reset the idle timeout
for the identified handle (F34 R11). If the handle has already expired or
been closed, the server must respond with error code HANDLE_INVALID.

### CRUD request payloads

R23. INSERT request payload must contain, in order: 8-byte big-endian int64
handle ID, document key (binary, non-null), document body (binary, non-null).

R23a. UPDATE request payload must contain, in order: 8-byte big-endian int64
handle ID, document key (binary, non-null), document body (binary, non-null).

R23b. GET request payload must contain, in order: 8-byte big-endian int64
handle ID, document key (binary, non-null).

R23c. DELETE request payload must contain, in order: 8-byte big-endian int64
handle ID, document key (binary, non-null).

R24. QUERY request payload must contain: 8-byte big-endian int64 handle ID,
followed by a serialized query predicate tree.

R24a. The predicate tree must use a recursive prefix encoding. Each node
begins with a 1-byte node type tag: FIELD_PREDICATE=0x00, AND=0x01,
OR=0x02, NOT=0x03. An unrecognized node type tag must result in
PAYLOAD_MALFORMED.

R24b. A FIELD_PREDICATE node must contain: field name (string), 1-byte
comparison operator (EQ=0x00, NE=0x01, LT=0x02, LE=0x03, GT=0x04,
GE=0x05), and comparison value (binary). An unrecognized comparison operator
must result in PAYLOAD_MALFORMED.

R24c. An AND node must contain: 4-byte int32 child count followed by that
many child nodes encoded recursively. The child count must be at least 1.

R24d. An OR node must contain: 4-byte int32 child count followed by that
many child nodes encoded recursively. The child count must be at least 1.

R24e. A NOT node must contain exactly one child node encoded recursively.

R24f. The predicate tree nesting depth must not exceed 64 levels. A tree
exceeding this depth must result in PAYLOAD_MALFORMED. This prevents stack
overflow during recursive decoding.

R25. SCAN request payload must contain: 8-byte big-endian int64 handle ID,
from key (binary, nullable for unbounded start), to key (binary, nullable
for unbounded end), 4-byte int32 limit (0 for no limit, negative values are
invalid and must result in PAYLOAD_MALFORMED).

### Transaction request payloads

R26. BEGIN_TRANSACTION request payload must be empty (zero bytes after the
envelope header).

R26a. TXN_TABLE request payload must contain: 8-byte big-endian int64
transaction ID, table name (string).

R26b. TXN_INSERT request payload must contain: 8-byte big-endian int64
transaction ID, 8-byte big-endian int64 transactional table handle ID,
document key (binary, non-null), document body (binary, non-null).

R26c. TXN_UPDATE request payload must contain: 8-byte big-endian int64
transaction ID, 8-byte big-endian int64 transactional table handle ID,
document key (binary, non-null), document body (binary, non-null).

R26d. TXN_GET request payload must contain: 8-byte big-endian int64
transaction ID, 8-byte big-endian int64 transactional table handle ID,
document key (binary, non-null).

R26e. TXN_DELETE request payload must contain: 8-byte big-endian int64
transaction ID, 8-byte big-endian int64 transactional table handle ID,
document key (binary, non-null).

R26f. COMMIT_TRANSACTION request payload must contain: 8-byte big-endian
int64 transaction ID.

R26g. ABORT_TRANSACTION request payload must contain: 8-byte big-endian
int64 transaction ID.

### Response encoding

R27. Every response must begin with the same 4-byte envelope header
(operation code echoed from the request, protocol version echoed from the
request), followed by a 1-byte status code. Status codes: 0x00=SUCCESS,
0x01=ERROR. Any other status byte value is invalid.

R28. On SUCCESS, the response payload is operation-specific (defined in
R31-R53). On ERROR, the response payload must contain: 2-byte big-endian
error code, error message (string, non-null).

### Error codes

R29. The protocol must define the following error codes:

| Code | Name | Meaning |
|------|------|---------|
| 0x0001 | UNKNOWN_OPERATION | Operation code not recognized |
| 0x0002 | UNSUPPORTED_VERSION | Protocol version not supported |
| 0x0003 | TABLE_NOT_FOUND | Named table does not exist |
| 0x0004 | TABLE_EXISTS | Table already exists (create conflict) |
| 0x0005 | TABLE_DROPPED | Table has been dropped |
| 0x0006 | TABLE_NOT_READY | Table is in loading or error state |
| 0x0007 | HANDLE_INVALID | Handle does not exist, expired, or closed |
| 0x0008 | HANDLE_EXHAUSTED | Handle budget exceeded, no evictable handles |
| 0x0009 | ENGINE_CLOSED | Engine has been shut down |
| 0x000A | INVALID_ARGUMENT | Null or invalid argument in request |
| 0x000B | IO_ERROR | Server I/O failure during operation |
| 0x000C | SCHEMA_INVALID | Schema definition malformed or unsupported |
| 0x000D | KEY_NOT_FOUND | Document key does not exist |
| 0x000E | TXN_ABORTED | Transaction was aborted due to conflict |
| 0x000F | TXN_INVALID | Transaction does not exist or already ended |
| 0x0010 | PAYLOAD_MALFORMED | Request body cannot be decoded |
| 0x0011 | TXN_TIMEOUT | Transaction exceeded its timeout duration |

R30. Error codes 0x0001-0x00FF are reserved for core protocol errors. Codes
0x0100-0xFFFF are reserved for future extension.

### DDL response payloads

R31. CREATE_TABLE success response must contain: table name (string) echoed
back.

R32. DROP_TABLE success response must be empty (zero bytes after the status
code).

R33. GET_TABLE success response must contain: 1-byte found flag (0x01=found,
0x00=not found). When found, followed by table name (string) and 1-byte
table state (encoded per R34).

R33a. LIST_TABLES success response must contain: 4-byte int32 table count,
followed by each table name (string) in sequence. A table count of zero is
valid and indicates no ready tables.

R34. TABLE_METADATA success response must contain: table name (string),
1-byte table state (0x00=LOADING, 0x01=READY, 0x02=DROPPED, 0x03=ERROR),
schema definition (encoded per R18). An unrecognized table state byte must
be treated as an unknown state by the client without error.

R35. ENGINE_METRICS success response must contain: 4-byte int32 ready table
count, 4-byte int32 total open handles, followed by per-table metrics as a
4-byte int32 table count then for each table: table name (string), 4-byte
int32 handle count.

### Handle management response payloads

R36. HANDLE_ACQUIRE success response must contain: 8-byte big-endian int64
handle ID.

R37. HANDLE_RELEASE success response must be empty (zero bytes after the
status code).

R38. HANDLE_HEARTBEAT success response must be empty (zero bytes after the
status code).

### CRUD response payloads

R39. INSERT success response must be empty (zero bytes after the status
code).

R39a. UPDATE success response must be empty (zero bytes after the status
code).

R40. DELETE success response must be empty (zero bytes after the status
code).

R41. GET success response must contain: 1-byte found flag (0x01=found,
0x00=not found). When found, followed by document body (binary).

R42. QUERY success response must contain: 4-byte int32 result count,
followed by each result as document key (binary) then document body (binary)
in sequence. A result count of zero is valid.

R43. SCAN success response must contain: 4-byte int32 result count, followed
by each result as key (binary) then document body (binary) in sequence. A
result count of zero is valid.

### Transaction response payloads

R44. BEGIN_TRANSACTION success response must contain: 8-byte big-endian
int64 transaction ID.

R45. COMMIT_TRANSACTION success response must contain: 8-byte big-endian
int64 commit timestamp.

R46. ABORT_TRANSACTION success response must be empty (zero bytes after the
status code).

R47. TXN_TABLE success response must contain: 8-byte big-endian int64
transactional table handle ID.

R48. TXN_INSERT success response must be empty (zero bytes after the status
code).

R48a. TXN_UPDATE success response must be empty (zero bytes after the status
code).

R48b. TXN_DELETE success response must be empty (zero bytes after the status
code).

R49. TXN_GET success response must contain: 1-byte found flag, and when
found, document body (binary), using the same encoding as R41.

### Traffic class mapping

R50. The F20 MessageType-to-traffic-class mapping (F20 R3) must be extended
to include ENGINE_OPERATION mapped to INTERACTIVE as a default. The
per-operation traffic class override (R51) refines this at the application
layer.

R51. The engine layer must determine the effective traffic class for each
ENGINE_OPERATION message based on the operation code in the envelope header,
before enqueueing into the F20 DRR scheduler. The mapping is:

| Operation Category | Traffic Class | Rationale |
|-------------------|---------------|-----------|
| DDL (CREATE_TABLE, DROP_TABLE, GET_TABLE) | METADATA | Schema changes are infrequent, metadata-class |
| Introspection (LIST_TABLES, TABLE_METADATA, ENGINE_METRICS) | METADATA | Read-only metadata |
| HANDLE_ACQUIRE, HANDLE_RELEASE | CONTROL | Handle lifecycle must not be delayed by query traffic |
| HANDLE_HEARTBEAT | CONTROL | Heartbeats share PING/ACK's small-and-rate-limited profile (F20 R10) |
| CRUD (INSERT, GET, UPDATE, DELETE) | INTERACTIVE | User data operations |
| QUERY, SCAN | INTERACTIVE | Query operations are latency-sensitive |
| Transaction ops (BEGIN, COMMIT, ABORT, TXN_*) | INTERACTIVE | Transactional operations are latency-sensitive |

R51a. HANDLE_ACQUIRE and HANDLE_RELEASE are classified as CONTROL because
they are small fixed-size messages (< 200 bytes). Implementations must
validate that a single connection does not exceed 100 HANDLE_ACQUIRE requests
per second to maintain the CONTROL bypass safety assumption (F20 R10).

### Version negotiation

R52. The client must send its requested protocol version in every request
envelope (R6). The server must respond using the same protocol version as
the request when the server supports that version. If the server does not
support the requested version, it must return UNSUPPORTED_VERSION (R8).

R53. When the protocol evolves (version > 1), new fields must be appended
to existing payload formats. Existing fields must not be reordered, resized,
or removed.

R54. A version-N receiver that encounters trailing bytes beyond the expected
payload for version N must skip the trailing bytes without error. This
allows forward compatibility: an older receiver can process messages from a
newer sender that appends fields the older receiver does not understand.

### Payload validation

R55. The server must validate all request payloads before executing the
operation. A payload that cannot be fully decoded must result in an error
response with error code PAYLOAD_MALFORMED.

R55a. Specific conditions that constitute a malformed payload: truncated
message (fewer bytes than the payload format requires), invalid string
encoding (R10a), negative length prefix other than -1, length prefix
exceeding size limits (R56), length prefix exceeding remaining message bytes
(R57), unrecognized enum byte values where specified (R12, R18a, R20, R24a,
R24b).

R56. String length prefixes in request payloads must not exceed 1 MiB
(1,048,576 bytes). Binary field length prefixes must not exceed 64 MiB
(67,108,864 bytes). A length prefix exceeding these limits must result in
PAYLOAD_MALFORMED without allocating the indicated buffer.

R57. The server must not allocate buffers based on untrusted length prefixes
before validating the prefix against remaining bytes in the message. If a
length prefix indicates more bytes than remain in the message, the server
must return PAYLOAD_MALFORMED.

### Remote handle lifecycle

R58. In remote mode, each HANDLE_ACQUIRE creates a server-side handle
tracked by the engine's handle tracker (F05 R40, F34). The handle ID
returned to the client is an opaque 8-byte identifier that the client must
treat as immutable and non-interpretable.

R59. The client must send HANDLE_HEARTBEAT at an interval shorter than the
server's configured idle timeout (F34 R11-R12) to prevent idle expiry. The
heartbeat interval must be no more than half the server's idle timeout. When
the server's idle timeout is disabled (F34 R12 default), the client is not
required to send heartbeats.

R60. If the transport connection drops (detected per F19 R20 — read failure
or end-of-stream), the server must invalidate all handles associated with
that connection. Each handle must transition to EXPIRED with reason EVICTION
as defined in F34 R6a.

R61. The source identity for remote handles (F05 R42) must be the connection
peer's NodeAddress. Per-source handle limits (F05 R42) apply per remote
connection.

### Partial message and connection failure semantics

R62. If the transport connection drops while the server is processing a
request (after full receipt but before response dispatch), the server must
complete the operation but discard the response. No error is surfaced to the
server application.

R63. If the transport connection drops while the server is receiving a
multi-frame request (F19 R35), the partial reassembly buffer is discarded
per F19 R20. No operation is executed for the partial request.

### Idempotency

R64. DDL operations (CREATE_TABLE, DROP_TABLE) are not idempotent. A
duplicate CREATE_TABLE must return TABLE_EXISTS. A duplicate DROP_TABLE (on
an already-dropped table) must return TABLE_NOT_FOUND.

R65. HANDLE_RELEASE on an already-released or expired handle must return
error code HANDLE_INVALID. This is not idempotent in the strict sense (it
returns an error, not success), but it is safe to retry: no state mutation
occurs and the error is distinguishable.

R66. HANDLE_HEARTBEAT on an already-expired or closed handle must return
error code HANDLE_INVALID. No state mutation occurs.

R67. CRUD write operations (INSERT, UPDATE, DELETE) with identical inputs
produce the same storage state. The protocol does not provide request
deduplication. Callers that require exactly-once semantics must implement
application-level deduplication.

### Byte order and alignment

R68. All multi-byte integer fields in the protocol must use big-endian byte
order, consistent with F19 R3.

R69. Fields must be packed with no padding or alignment gaps. The protocol
is byte-aligned (alignment 1).

### GET_TABLE semantics

R70. GET_TABLE (0x03) checks whether a table exists and is accessible.
If the named table exists and is in the READY state, the server must return
SUCCESS with the found flag set (0x01) and the table name and state. If the
table does not exist, is DROPPED, or is in LOADING or ERROR state, the
server must return SUCCESS with the found flag cleared (0x00).

---

## Design Narrative

### Intent

Define the wire encoding for engine operations (CRUD, DDL, handle management,
transactions) transmitted over the F19 multiplexed transport. This is the
content layer that rides inside F19 frames. F19 provides framing, correlation,
and reliability; F20 provides priority scheduling. This spec defines what the
bytes mean.

### Why a flat binary protocol

The engine API (F05) is a fixed set of well-defined operations with known
parameter types. A self-describing format (protobuf, JSON, CBOR) would add
per-message overhead for type tags and field names that are already known from
the operation code. The flat binary encoding minimizes per-message overhead
(4-byte envelope header) and avoids external codec dependencies, consistent
with the project's no-external-dependencies constraint.

Schema evolution is handled by append-only payload extension (R53-R54) rather
than by field-level self-description. This is the same approach Kafka uses for
its binary protocol: each API version defines a fixed schema, new versions
append fields, old readers skip unknown trailing bytes.

### Why a single ENGINE_OPERATION MessageType

F19 defines MessageType as a 1-byte enum for transport-level dispatch (PING,
ACK, VIEW_CHANGE, etc.). Engine operations are numerous (25+ operation codes)
and growing. Encoding each engine operation as a separate MessageType would
exhaust the 256-value type tag space and conflate transport-level message
routing with application-level operation dispatch. Instead, all engine messages
use a single MessageType (ENGINE_OPERATION=0x07), and the 2-byte operation code
in the envelope provides fine-grained dispatch at the application layer.

### Why handle heartbeats are CONTROL class

Handle heartbeats prevent idle timeout expiry of server-side handles. If
heartbeats are delayed behind a queue of large query responses, handles may
expire spuriously, causing the client to re-acquire and retry. Classifying
heartbeats as CONTROL (alongside PING/ACK) ensures they benefit from F20's
CONTROL bypass (F20 R9) with zero scheduling delay. Handle heartbeats share
the same small-and-rate-limited profile as membership pings. HANDLE_ACQUIRE
and HANDLE_RELEASE are also CONTROL: they are small fixed-size messages and
rate-limited by design (a client acquires handles infrequently relative to
CRUD traffic).

### Why CRUD responses are simple

QUERY and SCAN responses encode full result sets inline. For large result sets,
F19's automatic chunking (as amended by F20 R11) splits the response across
multiple frames transparently. The client receives a reassembled complete
response. Cursor-based streaming for very large result sets is a separate
concern (distributed scan cursors) and not addressed here.

### Traffic class interaction with F20

F20 R3 maps MessageType to traffic class, but ENGINE_OPERATION is a single
MessageType covering all engine operations. The engine layer inspects the
operation code in the envelope and selects the traffic class before enqueueing
into the DRR scheduler. This is a layered override: F20 sees
ENGINE_OPERATION=INTERACTIVE as the default; the engine layer refines it to
METADATA or CONTROL for specific operations. This avoids polluting F20's
transport-level mapping with application-level operation semantics.

### What was ruled out

- **Protobuf or similar IDL-based encoding:** Adds an external dependency or
  requires code generation. The operation set is small and stable enough for
  hand-coded encoding.
- **Per-operation MessageType values:** Exhausts the 1-byte F19 type tag space
  and makes traffic class assignment at the transport layer impractical.
- **Request/response framing separate from F19:** Would duplicate framing logic.
  F19 already provides correlation, chunking, and reassembly.
- **Stateless protocol (no server-side handles):** Would require the server to
  re-validate permissions and re-locate the table on every operation. The
  handle-based model amortizes setup cost across many operations.

### Schema evolution strategy

The protocol uses a version-per-request model (R52) with append-only field
extension (R53) and forward-compatible trailing-byte skipping (R54). This is
deliberately simple: the jlsm engine protocol has a single client and server
codebase, so coordinated upgrades are feasible. The version field exists to
handle rolling upgrades where nodes briefly run different versions. Full
schema negotiation (capability exchange, feature flags) is not needed at this
scale.

### Out of scope

- Streaming/cursor-based result delivery (separate concern: distributed scan)
- Compression of operation payloads (F19/F20 frame-level concern)
- Authentication and authorization of operations
- Batch operations (multiple operations in one request)
- Client-side proxy implementation (this spec defines the wire format only)
- Operation-level timeout at the protocol layer (F19 R26 request timeout applies)
