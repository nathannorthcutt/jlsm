---
{
  "id": "sstable.footer-encryption-scope",
  "version": 5,
  "status": "ACTIVE",
  "state": "APPROVED",
  "domains": [
    "sstable",
    "engine",
    "encryption"
  ],
  "requires": [
    "sstable.v3-format-upgrade",
    "sstable.end-to-end-integrity",
    "encryption.primitives-lifecycle",
    "encryption.ciphertext-envelope"
  ],
  "invalidates": [],
  "amends": null,
  "amended_by": null,
  "decision_refs": [
    "sstable-footer-scope-format",
    "table-handle-scope-exposure",
    "three-tier-key-hierarchy",
    "sstable-end-to-end-integrity",
    "sstable-active-tamper-defence"
  ],
  "kb_refs": [
    "systems/security/sstable-block-level-ciphertext-envelope",
    "systems/security/three-level-key-hierarchy",
    "patterns/validation/dispatch-discriminant-corruption-bypass",
    "patterns/validation/version-discovery-self-only-no-external-cross-check",
    "patterns/validation/silent-fallthrough-integrity-defense-coupled-to-flag",
    "patterns/validation/integer-overflow-silent-truncation",
    "patterns/validation/assert-only-guard-anti-pattern",
    "patterns/validation/one-shot-api-missing-guard",
    "patterns/concurrency/torn-volatile-publish-multi-field",
    "patterns/concurrency/check-then-act-across-paired-acquire-release",
    "patterns/resource-management/atomic-move-vs-fallback-commit-divergence"
  ],
  "open_obligations": []
}
---

# sstable.footer-encryption-scope — SSTable Footer Encryption Scope Metadata

## Requirements

### Format version dispatch

R1. Encrypted SSTables must be written with format magic `v6` (a distinct 8-byte magic value from the existing `v5` magic defined by `sstable.end-to-end-integrity`). Unencrypted SSTables must continue to be written with format magic `v5`. A writer whose owning `Table` has `TableMetadata.encryption = Optional.of(_)` at `finish()`-time (re-check per R10c) must emit `v6`; a writer whose owning `Table` has `TableMetadata.encryption = Optional.empty()` at `finish()`-time must emit `v5`.

R1a. Readers must dispatch on footer magic. A `v5` footer indicates no encryption scope metadata is present and the reader must not attempt scope parsing. A `v6` footer indicates scope metadata is present and the reader must parse and validate it per R4–R6c. An unrecognised magic must cause the reader to throw `IOException` identifying the unknown magic without revealing file bytes beyond the magic itself. Magic-dispatch validation must be a runtime conditional — not a Java `assert` — because assertions are disabled in production builds.

R1b. Mixed-fleet coexistence is required only for a bounded set of `v5` files: those whose creation timestamp predates the `enableEncryption` commit for the containing table (pre-encryption legacy). Writers in an encrypted table must always emit `v6` at `finish()` regardless of the DEK-version set being empty (R3c). Compaction output in an encrypted table must always emit `v6`. A reader encountering a `v5` file whose presence is not accounted for by the pre-encryption legacy window must tolerate it (the reader cannot always distinguish legitimate-legacy from attacker-substituted without the tamper defence deferred to `sstable-active-tamper-defence`), but the writer side must not *produce* `v5` files in encrypted tables. See R13 for the threat-model boundary this requirement operates within.

### Footer scope section layout (v6)

R2. The v6 footer must append a scope section after all existing v5 sections and before the footer-wide CRC32C and magic. The order is: `[v5 sections][scope section][footerChecksum:u32 BE][magic:u64]`.

R2a. The scope section byte layout must be:

```
[scope-section-length:u32 BE]        — total byte length of the scope section body (fields below), excluding this length prefix
[tenantId-utf8-length:u32 BE]
[tenantId-utf8-bytes]
[domainId-utf8-length:u32 BE]
[domainId-utf8-bytes]
[tableId-utf8-length:u32 BE]
[tableId-utf8-bytes]
[dek-version-count:u16 BE]
[dek-version-1:u32 BE]
...
[dek-version-N:u32 BE]
```

All multi-byte integers are big-endian. `MemorySegment` access paths must use `ValueLayout.withByteAlignment(1)` — the footer is not naturally aligned.

R2b. Each identifier length prefix (`tenantId-utf8-length`, `domainId-utf8-length`, `tableId-utf8-length`) must be a non-negative 32-bit big-endian integer. A writer must reject an identifier whose UTF-8 byte length exceeds 65536 (2^16) bytes with `IllegalArgumentException` before emitting the footer; a reader encountering a length field greater than 65536 must throw `IOException` indicating corrupt footer without revealing further bytes.

R2c. Each identifier (`tenantId`, `domainId`, `tableId`) must have a UTF-8 byte length of at least 1. A writer must reject an empty identifier with `IllegalArgumentException`; a reader encountering a zero-length identifier must throw `IOException` indicating corrupt footer.

R2d. The reader must validate the scope section's internal consistency in this order, independent of source:

1. **First**, validate each identifier length prefix individually per R2b (reject any >65536 or <1 before any arithmetic).
2. **Second**, compute the expected scope-section-length as a `long` (u64) sum of: 4 (tenantId-len) + tenantId-utf8-length + 4 (domainId-len) + domainId-utf8-length + 4 (tableId-len) + tableId-utf8-length + 2 (dek-version-count) + 4 × dek-version-count. The computation must use `long` arithmetic.
3. **Third**, if the computed value exceeds `Integer.MAX_VALUE`, throw `IOException` indicating corrupt footer. Otherwise compare to the declared `scope-section-length` prefix; any mismatch must throw `IOException` indicating corrupt footer before allocating buffers for identifier bytes.

A writer emitting `scope-section-length` must use the same `long`-arithmetic computation and must assert internal consistency before emit.

R2e. Identifier UTF-8 bytes must be well-formed UTF-8. Identifiers must not contain any byte in the range `[0x00, 0x1F]` or the byte `0x7F` (ASCII control codepoints and DEL). Identifiers must not contain any UTF-8 sequence that decodes to a codepoint in the Unicode general categories `Cc` (Other, Control), `Cf` (Other, Format — includes U+200B, U+200C, U+200D, U+200E, U+200F, U+202A–U+202E, U+2066–U+2069, U+FEFF), `Co` (Private Use), or `Cs` (Surrogate). Identifiers must not contain U+0085 (NEL), U+2028 (LINE SEPARATOR), or U+2029 (PARAGRAPH SEPARATOR). A writer must reject a violating identifier with `IllegalArgumentException` (reporting the offending position without revealing byte values); a reader encountering a violating byte must throw `IOException` indicating corrupt footer.

### DEK version set

R3. The `dek-version-count` field must be a non-negative 16-bit big-endian integer. Writers must reject emitting more than 65535 DEK versions with `IllegalStateException`; this is a structural ceiling. The practical ceiling during rotation-straddle is 16; exceeding it suggests unbounded rotation accumulation and is a caller error.

R3a. DEK versions must appear in strictly ascending order. A writer emits them sorted. A reader encountering a descending or equal pair must throw `IOException` indicating corrupt footer.

R3b. Each DEK version must be a positive 32-bit big-endian integer (`version ≥ 1`). A writer must reject version `0` or negative values with `IllegalArgumentException`; a reader encountering version `0` or negative must throw `IOException` indicating corrupt footer. The check applies uniformly to every version in the list. Enforcement must be a runtime conditional, not a Java `assert`.

R3c. When `dek-version-count` is zero, the DEK version list is absent (zero bytes follow). This state is permitted only for a `v6` SSTable that persists no encrypted entries (e.g., a post-compaction SSTable whose pre-image contained only tombstones). See R3e for the reader-side check that enforces this.

R3d. The DEK version set must contain exactly the distinct DEK versions used to encrypt fields persisted within the SSTable. Compaction output SSTables record exactly one current DEK version per `encryption.primitives-lifecycle` R23a/R25b. Flush output SSTables record the single DEK version active at flush time.

R3e. **Reader-side DEK-set pre-resolution check.** The reader must materialise the `dek-version-set` as an in-memory `Set<Integer>` (or equivalent constant-time lookup structure) at footer-parse time. Before invoking any DEK resolver — including bloom-filter probes, key-index scans, or any envelope-header prefetch — the reader must membership-check the envelope's DEK-version field against this set. A check failure must throw `IOException` (or the codebase's `CorruptSectionException`) before any DEK lookup, cache access, or decryption is initiated. This closes the attack where envelope-header reads could touch DEK material for a version not declared in the footer.

R3f. **Empty-DEK-set invariant.** A v6 SSTable with `dek-version-count = 0` must contain no entries whose per-field envelope carries a DEK-version field. The reader must assert this structurally during entry iteration — encountering any envelope-header DEK-version field in a file whose footer declared `dek-version-count = 0` must throw `IOException` indicating corrupt footer (not a DEK-mismatch error, because the attack shape differs: the writer violated R3d, producing a footer that under-reports). The error message must distinguish "empty-set file contained encrypted entries" from R3e's "version not in declared set" to aid diagnosis.

### Reader scope validation protocol

R4. A reader opening a `v6` SSTable must, after magic verify and footer CRC32C verify (R2f), parse the scope section per R2a. Parsing must happen before any DEK resolution, block decryption, entry read, bloom-filter probe, or key-index scan that would touch envelope-header DEK-version bytes.

R5. The reader must materialise the **expected scope** from the caller's `Table` handle via `table.metadata().encryption().orElseThrow(IllegalStateException::new)`. The `IllegalStateException` message must identify the Table by name and state "attempt to decrypt SSTable belonging to a Table without encryption metadata" without revealing key material.

R6. The reader must compare the expected scope (from R5) against the SSTable's declared scope (from R2a) by component-wise equality on `(tenantId, domainId, tableId)`. A mismatch on any component must cause the reader to throw `IllegalStateException` before any DEK lookup or block decryption. The error message must identify both the expected and declared scopes but must not reveal any DEK, key material, or bytes beyond the scope identifiers.

R6a. The expected scope must NOT be derived from the same SSTable footer it is validating. Deriving expected scope from the footer would make R6 tautological. The expected scope must come from the catalog-mediated `Table` handle obtained via `Engine.getTable(name)` — this is the independent source of truth for R6's check to have meaning.

R6b. R6's comparison is a fast-fail / clear-error mechanism. The cryptographic defence against wrong-scope decryption is the HKDF scope binding from `encryption.primitives-lifecycle` R11 (DEK material is bound to the full `(tenantId, domainId, tableId, dekVersion)` tuple). R6 exists to produce a clear, early error; R11's binding exists to make wrong-scope decryption cryptographically impossible. Both must hold.

R6c. **Trust boundary on the Table handle.** The `Table` interface that the reader consumes for R5 must be `sealed` (see R8e) with exactly two permitted implementations, both in non-exported internal packages: `jlsm.engine.internal.CatalogTable` (single-node) and `jlsm.engine.cluster.internal.CatalogClusteredTable` (clustered). External consumers of `jlsm-engine` cannot implement `Table`; only `Engine` / `ClusteredEngine` factory methods (`getTable`, `createTable`, `createEncryptedTable`) can produce handles that reach the reader. Both internal classes are catalog-mediated — the single-node class reads directly from the local `TableCatalog`; the clustered class wraps a multi-partition router whose per-node scatter targets dispatch through the local catalog on each node. This enforces at compile time that any handle passed to R5 is catalog-mediated and that its `metadata().encryption()` value is authoritative — not an attacker-constructed Table whose `metadata()` returns forged scope.

R2f. **Footer CRC32C covers the full v6 footer; file-size matches footer-end.** Every byte of the v6 footer except the `footerChecksum` field itself must be covered by the CRC32C — specifically, the CRC is computed over `[0 .. footerChecksumOffset) ∪ [footerChecksumOffset+4 .. footerEnd)`, where `footerEnd` is the offset of the byte after `magic`. This generalises `sstable.end-to-end-integrity` R16 (which specifies a fixed 104-byte offset range for v5) to the variable-length v6 footer.

Additionally, a reader must assert `fileSize == footerEnd` (using `Files.size(path)` or the equivalent `SeekableByteChannel.size()`) and that the last 8 bytes of the file are the magic, **before** computing the footer CRC. Any file whose size exceeds `footerEnd` (trailing bytes after magic) or whose last 8 bytes are not a recognised magic must be rejected with `IOException` indicating corrupt footer. This closes the attack where remote NIO providers or storage-layer padding could shift the CRC region.

### F9 threat-model boundary (Model 2)

R13. **Threat-model scope.** The reader-side scope validation (R4–R6c) and per-field envelope integrity (from `encryption.ciphertext-envelope`) defend against **accidental mis-routing** of SSTables across tenant / domain / table scopes, and against the confidentiality attack on encrypted data under wrong-key derivation (via `encryption.primitives-lifecycle` R11's HKDF binding). They do NOT defend against **active on-disk tamper** — an attacker with write access to SSTable bytes can substitute a fabricated `v5` plaintext file for a legitimate `v6` encrypted file and cause the reader to return attacker-controlled plaintext without engaging the encryption layer (R1b's bounded legitimate-legacy window limits but does not eliminate this).

R13a. jlsm's encryption features delegate integrity defence against active on-disk tamper to the storage substrate. Supported storage substrates include:
- Local filesystem with OS-level access controls and (where applicable) filesystem integrity primitives
- Object storage (S3, GCS, Azure Blob) with authenticated IAM, bucket-level access controls, and (where applicable) object-lock or WORM configurations

R13b. Deploying jlsm encryption on storage where write access to SSTable bytes is not authenticated is unsupported. Operational guidance for deployers will state this explicitly (documentation item, not a spec requirement).

R13c. Deferred work on active-tamper defence is tracked by the ADR slug `sstable-active-tamper-defence`. If that decision lands (manifest MAC, per-block AEAD, or other cryptographic file-integrity scheme), this spec will be revisited to refine R13's scope.

### Engine API surface

R7. The `Engine` interface must expose a method `createEncryptedTable(String name, JlsmSchema schema, TableScope scope) throws IOException` that creates a table with `TableMetadata.encryption = Optional.of(EncryptionMetadata(scope))`. The scope must be non-null; a null scope must be rejected with `NullPointerException`.

R7a. The existing `Engine.createTable(String name, JlsmSchema schema) throws IOException` must continue to create unencrypted tables with `TableMetadata.encryption = Optional.empty()`. Its behaviour must not change.

R7b. The `Engine` interface must expose a method `enableEncryption(String name, TableScope scope) throws IOException`. The method must execute steps 1–5 under a **single held exclusive lock** — the read (step 2), precondition check (step 3), persist (step 4), and cache invalidation (step 5) must be serialised atomically as a unit. Any implementation that releases the lock between any of these steps is a spec violation.

1. Acquire an exclusive lock on the table's catalog metadata (per `table-catalog-persistence`). This lock must be the same lock that **every** SSTable writer — flush, compaction, repair, or any other path that produces a committed SSTable in this table — must hold at `finish()`-time to commit (see R10c).
2. Read the current `TableMetadata` for the named table via a fresh catalog read.
3. If `TableMetadata.encryption.isPresent()`, throw `IllegalStateException` indicating the table is already encrypted. The error message must not reveal the existing scope.
4. Construct a new `TableMetadata` with `encryption = Optional.of(EncryptionMetadata(scope))` and persist atomically to `table.meta` using the atomic-move pattern established in `table-catalog-persistence`.
5. Invalidate cached handle metadata so subsequent `metadata()` calls observe the updated state. Publication must establish a happens-before edge readable by any subsequent `Engine.tableMetadata(name)` / `Engine.getTable(name)` / writer re-check (R10c) call.

R7b must be one-way: the `Engine` interface must NOT expose a `disableEncryption` method. A table, once encrypted, cannot be returned to the unencrypted state in place. Concurrent calls to `Engine.enableEncryption` on the same table are serialised by step 1's lock; at most one succeeds, and subsequent calls must observe `TableMetadata.encryption.isPresent()` in their step 2 and throw at step 3.

R7c. After `enableEncryption` completes successfully, the encryption metadata must be visible to all subsequent `Engine.getTable(name)` and `Engine.tableMetadata(name)` calls. Handles obtained before the transition must, on their next `metadata()` call, observe the updated state via the same publication mechanism as R7b step 5.

### TableMetadata, EncryptionMetadata, TableScope types

R8. The `TableMetadata` record in `jlsm.engine` must be extended to include a component `encryption` of type `Optional<EncryptionMetadata>`, where `EncryptionMetadata` is a new record in `jlsm.engine`.

R8a. `EncryptionMetadata` must be a record with a component `scope` of type `TableScope`. The canonical constructor must reject a null scope with `NullPointerException`. Future encryption-related per-table facts (e.g., cipher suite, KEK reference, rotation state) may compose into this record without changing `TableMetadata`'s shape.

R8b. `TableScope` must be a record in the `jlsm.encryption` package (`jlsm-core` module) with components `(TenantId tenantId, DomainId domainId, TableId tableId)`. The canonical constructor must reject any null component with `NullPointerException`. `TableScope` composes the identity records already present in `jlsm.encryption` from `encryption.primitives-lifecycle` (WD-01).

R8c. `TableScope`, `EncryptionMetadata`, and the extended `TableMetadata` must all be immutable (Java records). Equality must be component-wise (default record semantics) — two `TableScope` instances are equal iff their three component records are equal; two `EncryptionMetadata` instances are equal iff their scopes are equal.

R8d. `TableMetadata` and `EncryptionMetadata` and `TableScope` must be thread-safe: because they are immutable records with final components whose types are themselves immutable, instances may be shared freely across threads without synchronisation.

R8e. **`Table` interface is sealed.** The public `Table` interface in `jlsm.engine` must be declared:

```java
public sealed interface Table extends AutoCloseable
    permits jlsm.engine.internal.CatalogTable,
            jlsm.engine.cluster.internal.CatalogClusteredTable {
    // existing methods unchanged
}
```

The two permitted implementations are: `CatalogTable` (single-node, in `jlsm.engine.internal`) and `CatalogClusteredTable` (clustered, in `jlsm.engine.cluster.internal`). Neither package is exported in `module-info.java`. Both are constructed exclusively by their respective engine factory methods (`Engine.getTable` / `createTable` / `createEncryptedTable`; `ClusteredEngine.getTable` / `createTable` / `createEncryptedTable`). External consumers of `jlsm-engine` cannot implement `Table` — the Java compiler rejects any non-permitted subtype.

The two-permits shape reflects that jlsm has two production paths (single-node and clustered) and both are catalog-mediated. `ClusteredTable` (the prior public class in the exported `jlsm.engine.cluster` package) relocates to `jlsm.engine.cluster.internal.CatalogClusteredTable` as part of this requirement; callers consume the public sealed `Table` type instead.

R8f. **Runtime defence for the sealed Table**. The discipline applies uniformly to BOTH permitted implementations (`CatalogTable` and `CatalogClusteredTable`):

- Each canonical constructor must be **non-public** (package-private) and must only be callable by its respective `Engine` / `ClusteredEngine` factory code within the same internal package.
- Neither `CatalogTable` nor `CatalogClusteredTable` may implement `java.io.Serializable` or `Externalizable`. No deserialisation constructor may exist on either class.
- The `module-info.java` of the `jlsm-engine` module must NOT `opens` or `exports` the `jlsm.engine.internal` package OR the `jlsm.engine.cluster.internal` package to any external module in production builds. Test builds may `opens` either internal package to test modules via a test-only `module-info.java` or a `--add-opens` flag scoped to test tasks.
- Reflection-based instantiation (via `--add-opens ... = ALL-UNNAMED`, `MethodHandles.privateLookupIn`, or `sun.misc.Unsafe`) is outside the language-level trust boundary. If an attacker has reflection access to internal packages, they are already inside the module boundary — the cryptographic defence (R6b HKDF binding) remains the final barrier, but R8e/R8f's defence-in-depth raises the attack bar meaningfully.

R8g. Test code requiring a mock Table must use a test-only factory constructed within `jlsm-engine`'s test module boundary. The production module descriptor must not expose any mechanism for external modules to construct `CatalogTable` or `CatalogClusteredTable`. Existing test stubs that previously declared `implements Table` (e.g., cluster test helpers `RecordingTable`, `StubTable`, `StubTableImpl`, `PermissiveStubTable`) must migrate to the test-only factory pattern as part of this requirement.

### Catalog persistence

R9. The `table.meta` file format (governed by `table-catalog-persistence`) must be extended to persist the optional `EncryptionMetadata`. The extension must be backward-compatible: `table.meta` files written before encryption support must load with `TableMetadata.encryption = Optional.empty()` and must not trigger recovery errors.

R9a. The extended `table.meta` format must carry a format-version byte at the head. A loader encountering the pre-encryption format version must populate `encryption = Optional.empty()`; a loader encountering the encryption-aware format version must parse the optional scope block. An unknown format version must cause loading to throw `IOException` identifying the unknown version. Enforcement must be a runtime conditional (explicit `if`/`throw`), not a Java `assert`.

R9a-mono. **Format-version monotonicity.** The catalog must track the high-water format version observed for each table. The high-water must be persisted in the **catalog index** (not derived from per-table `table.meta`, which is the very file being validated). The catalog index entry for a table must carry a `meta_format_version_highwater` field that is initialised at table creation to the format version the creating writer emits (e.g., pre-encryption or encryption-aware). A loader encountering a `table.meta` whose format version is strictly lower than the catalog-index's high-water must reject the load with `IOException` indicating a corruption signal (a tampered or downgraded metadata file). Cold-start with an empty catalog index must not load any `table.meta` without a corresponding catalog-index entry — a table with no catalog-index entry is treated as "table does not exist" regardless of any `table.meta` file present on disk. This prevents a downgrade attack in which a stale / malicious write rewrites `table.meta` in the pre-encryption format to silently disable the scope check, including the first-load bootstrap case.

R9b. The encryption block in `table.meta`, when present, must contain the same three length-prefixed UTF-8 identifiers as the SSTable footer scope section (R2a), in the same order and encoding (big-endian, UTF-8, non-empty, length-bounded, control-codepoint-rejecting per R2e). This uniformity simplifies round-trip validation during recovery.

R9c. Loading a `table.meta` file whose format version is the encryption-aware version and whose encryption block is malformed (zero-length identifier, length-prefix overflow, truncated bytes, R2e-violating byte) must fail with `IOException` rather than yielding a partial metadata. The loader must not silently degrade to `encryption = Optional.empty()` on malformed encryption blocks — silent degradation would allow a tampered `table.meta` to disable the scope check.

### Writer invariants

R10. A writer opening a `v6` SSTable must have been constructed with a non-null `TableScope` and a non-negative DEK version stream. If the `Table` handle's `TableMetadata.encryption` is `Optional.empty()` at writer construction, the writer must not emit a `v6` footer at the end; it must emit `v5`. The writer must fail with `IllegalStateException` at `open` time if the configuration is internally inconsistent (e.g., `TableScope` provided but encryption metadata absent on the owning Table).

R10 must also hold for compaction writers, repair writers, and any other writer path that produces a committed SSTable in the table. A writer whose owning Table's `TableMetadata.encryption.isPresent()` at `finish()` time must emit `v6` unconditionally, including the empty-DEK-set case (R3c).

R10a. A writer must record the DEK version(s) it used to encrypt fields within the SSTable and include them in the scope section's DEK version set at `finish` time. A writer must not emit a `dek-version-count` that disagrees with the actual versions written.

R10b. A writer that fails mid-write (IOException during any section emit including the scope section) must transition to `FAILED` state and refuse all subsequent operations, consistent with `sstable.end-to-end-integrity` R3/R22 close-atomicity. Partially-written footer bytes must not be committed — the magic-as-commit-marker invariant is preserved.

R10c. **Writer-finish-time metadata re-check against fresh catalog read.** At `finish()`, the writer must partition its work so that the exclusive catalog lock (R7b step 1) is held for the minimum time necessary — specifically, the lock covers only the fresh-catalog-read, the encryption-state compare, and the footer-commit. Bulk data-section emission and fsync must complete **before** the lock is acquired.

The required sequence is:

1. **Pre-lock (fsync heavy work first):** Complete all v5-section emits (data, metadata) and fsync them per `sstable.end-to-end-integrity` R20. This step must NOT hold the catalog lock. Long-running I/O (large SSTables, slow remote storage) is fenced out of the critical section.
2. **Acquire the catalog exclusive lock** (R7b step 1). The writer holds it for steps 3–7 only.
3. Perform a **fresh catalog read** of the owning Table's `TableMetadata` — either by calling `Engine.tableMetadata(name)` or by reading through the `AtomicReference<TableMetadata>` that R7b step 5 publishes. A read through the cached `Table` handle reference (e.g., the reference held since writer construction) is NOT acceptable.
4. Compare the freshly-read `encryption` state to the state observed at writer construction.
5. If the encryption state has transitioned from `Optional.empty()` to `Optional.of(_)` between construction and finish, the writer must:
   - release the lock,
   - transition to `FAILED` state,
   - refuse to commit the file (no footer magic emitted; the file must be deleted per R10e), and
   - surface the state change to the caller via `IOException` indicating the encryption-state transition without revealing scope.
6. If the encryption state is consistent, emit the scope section body, `footerChecksum`, `magic`, and perform the footer fsync per R10d — all under the held lock.
7. Release the lock.

Implementations must also provide a **lock-holder liveness recovery** mechanism: the lock must support reclaim if the holder process dies mid-operation (e.g., file-lock leases with expiry, or catalog-stored holder PID with bounded reclaim window). Indefinite lock orphaning must not be possible.

This requirement applies to every writer path — flush, compaction, repair, or any other path that produces a committed SSTable. The implementation must not use a separate weaker serialisation for any writer path.

R10d. **v6 footer emit order with double-fsync commit barrier.** The writer must emit footer bytes in exactly this order:

1. All v5 sections (data, metadata) with fsync per `sstable.end-to-end-integrity` R20. (Pre-lock, per R10c step 1.)
2. The scope section body (scope-section-length through last dek-version).
3. The `footerChecksum` (u32 BE).
4. **fsync** — the "pre-magic fsync" — forces all footer bytes prior to the magic to stable storage before the magic can become durable. This is a commit barrier that closes torn-footer attacks on filesystems without implicit write ordering.
5. The `magic` (u64).
6. **fsync** — the "post-magic fsync" — makes the commit durable.

The `magic` write must be the final byte-emit before the post-magic fsync. The pre-magic fsync is mandatory: byte-emit order does not imply reach-disk order on all substrates, and without the pre-magic fsync, an out-of-order writeback could produce a file that has durable magic but non-durable scope / footerChecksum bytes. A partial write that has emitted `footerChecksum` but not `magic` must be classified as incomplete per `sstable.end-to-end-integrity` R40. This pins the magic-as-commit-marker invariant and guarantees that a file with durable magic has all prior footer bytes also durable.

R10e. **Writers are not restart-resumable.** On process restart, any SSTable tmp file lacking committed magic (see `sstable.end-to-end-integrity` R40) must be deleted during recovery, not resumed. A writer's decision about `v5` vs `v6` emit is made at `open` time (and re-checked at `finish` per R10c) within a single process lifetime. No mechanism may rehydrate a partially-written SSTable from a prior process's state. This closes the attack where a pre-crash writer's `v5`-emit intent could survive a restart after `enableEncryption` committed.

### Concurrency contract

R11. Reader-side scope validation (R4–R6c) must be thread-safe. Multiple threads may open and validate the same SSTable concurrently. Implementations must not rely on thread-local state during scope validation; footer bytes, once read into a caller-owned segment, are immutable and may be shared for the caller's lifetime. Sharing of footer-backed `MemorySegment` instances across the reader's close() boundary is the caller's responsibility — see `io-internals` for arena lifecycle governance.

R11a. The `TableCatalog`'s metadata update during `enableEncryption` (R7b) must be atomic with respect to concurrent `Engine.getTable(name)` and `Engine.tableMetadata(name)` calls. A reader must never observe a `TableMetadata` that is partially transitioned (e.g., a new format-version byte with stale encryption block bytes). Atomicity must be achieved by the write-to-temp-fsync-rename pattern established in `table-catalog-persistence`. Non-atomic fallback paths (e.g., copy-then-delete for filesystems without `ATOMIC_MOVE`) must preserve the all-or-nothing visibility semantic or refuse to persist.

R11b. Concurrent calls to `Engine.enableEncryption` on the same table must be serialised by the single exclusive lock in R7b step 1; at most one must succeed, and subsequent calls must observe `TableMetadata.encryption.isPresent()` and throw `IllegalStateException` per R7b step 3.

### Error messaging discipline

R12. Every error thrown from scope validation (R1a, R5, R6, R6a, R6c, R9c, R9a-mono), scope parsing (R2a–R2f, R3a–R3b, R3e), catalog persistence (R9a, R9a-mono, R9c), or writer-finish re-check (R10c) must NOT reveal:
- DEK bytes or key material
- Footer bytes beyond the identifiers being reported
- Internal catalog paths or file offsets
- Offending byte values of control codepoints (R2e — report position only)

The error message may include:
- The table name
- The scope identifiers involved in a mismatch
- The corrupt field's identity (e.g., "tenantId length field")
- The format version encountered

This discipline matches the error-messaging conventions in `encryption.primitives-lifecycle` R22b/R24.

---

## Design Narrative

### Intent

This spec codifies the SSTable-side half of the encryption scope
signalling contract. Its consumer is the encryption read path
(`encryption.primitives-lifecycle` R22b/R23a/R24): a reader must
reject a cross-scope SSTable access with a clear, fast-fail error
before any DEK lookup or cryptographic work. The spec specifies
(a) how scope is encoded in the footer, (b) how the reader validates
it, (c) how the `Table` handle exposes the expected scope via a
sealed type hierarchy that enforces the trust boundary at the
compiler + runtime, and (d) how the catalog persists scope across
restarts.

### Threat model

**In scope**:
- Accidental mis-routing of SSTables across tenant/domain/table scopes
- Confidentiality of encrypted data under wrong-key derivation
  attempts (defended by HKDF scope binding in
  `encryption.primitives-lifecycle` R11)
- Accidental corruption of footer bytes (defended by R2f CRC32C)
- Cross-module type-level trust boundary (defended by R8e sealed Table
  + R8f runtime constructor/serialisation defence)
- Format-version downgrade attacks on `table.meta` (defended by
  R9a-mono monotonicity)
- TOCTOU between `enableEncryption` and in-flight writers of every
  kind (defended by R7b step 1 single-lock + R10c fresh-read re-check
  + R10e no-restart-resume)

**Out of scope** (delegated to the storage substrate):
- Active on-disk tamper by attackers with write access to SSTable
  bytes. Running jlsm encryption on unauthenticated storage is
  unsupported. Deferred decision:
  [`sstable-active-tamper-defence`](../../.decisions/sstable-active-tamper-defence/adr.md)
  evaluates manifest MAC, per-block AEAD, or other cryptographic
  file-integrity options if jlsm commits to that threat model.

### Why this approach

Two architectural decisions from 2026-04-24 drive the shape of this
spec:

- [`sstable-footer-scope-format`](../../.decisions/sstable-footer-scope-format/adr.md)
  chose a v5→v6 format bump with a fixed-position scope section,
  rejecting TLV extensions (speculative generality), optional-within-v5
  (breaks magic-as-commit-marker), and external registry (doubled
  durability surface). The format extension participates in the existing
  v5 section-CRC32C integrity scheme from
  [`sstable-end-to-end-integrity`](../../.decisions/sstable-end-to-end-integrity/adr.md),
  generalised to variable-length footers by R2f.

- [`table-handle-scope-exposure`](../../.decisions/table-handle-scope-exposure/adr.md)
  v2 chose `TableMetadata.encryption` as `Optional<EncryptionMetadata>`,
  a sub-record composing `TableScope` (which composes WD-01's identity
  records), PLUS sealed `Table` with one permitted internal subtype
  `CatalogTable`, with runtime defences (non-public constructor,
  non-`Serializable`, module-opens restriction in production). The
  sealing enforces that only catalog-mediated handles reach the
  encryption read path — closing trust-boundary attacks at compile
  time and raising the reflection-bypass bar meaningfully.
  `Engine.createEncryptedTable` and `Engine.enableEncryption` cover
  creation-time and post-creation enablement. Encryption is one-way;
  disable is deferred to
  [`encryption-disable-policy`](../../.decisions/encryption-disable-policy/adr.md).

The per-field ciphertext envelope remains governed by
`encryption.ciphertext-envelope` (APPROVED). The per-SSTable footer
scope adds a layer above it for R22b's cross-scope-rejection
invariant. The HKDF scope binding in `encryption.primitives-lifecycle`
R11 provides the cryptographic defence; this spec's R6/R6c/R8e/R8f
provide the type-level + runtime + fast-fail clear-error behaviour.

### What was ruled out

- **Per-block AES-GCM encryption transition** — rejected because it
  would break OPE/DCPE encryption variants, violate
  `encryption.ciphertext-envelope` R1a (cross-tier uniformity),
  and cascade amendments across 6+ APPROVED specs. Tracked as
  [`encryption-granularity-per-field-vs-per-block`](../../.decisions/encryption-granularity-per-field-vs-per-block/adr.md)
  (deferred).
- **TLV extensions for future footer growth** — rejected as speculative
  generality with no committed second use-case.
- **Optional extension within v5 (no version bump)** — hard-disqualified
  because it undermines the magic-as-commit-marker invariant.
- **External path-keyed registry** — rejected because it doubles the
  durability surface and creates registry/manifest drift failure modes.
- **Package-private wrapper type without sealing** — v1 of
  `table-handle-scope-exposure` accepted runtime-check as the trust
  boundary; Pass 2 falsification (F1) demonstrated this left the
  public `Table` interface open to external implementation. v2 of
  the ADR adopts sealed `Table` — type-level enforcement.
- **In-place disable encryption** — rejected to avoid a DRAINING state
  machine, compaction reverse-migration cost, and the
  plaintext-during-drain compliance window. Callers who need to
  un-encrypt a table will do so via future `copyTable` + `dropTable`
  primitives. Deferred as
  [`encryption-disable-policy`](../../.decisions/encryption-disable-policy/adr.md).
- **SSTable-level active-tamper cryptographic defence (manifest MAC,
  per-SSTable signatures)** — out of scope for this spec. Partial
  integrity patches (e.g., catalog-tracked per-SSTable encryption era
  to close the v5-swap attack alone) were explicitly rejected as
  worse than clean threat-model boundary declaration because they
  give false confidence. Deferred as
  [`sstable-active-tamper-defence`](../../.decisions/sstable-active-tamper-defence/adr.md).
- **"Equivalent serialisation guarantee" escape hatch in R10c** —
  v2 of the spec contained an "OR equivalent" clause allowing weaker
  writer/`enableEncryption` synchronisation. Pass 3 falsification
  (F13) demonstrated this permitted a cached-handle read path that
  defeated the re-check. v3 (this spec) requires fresh catalog read
  through the `AtomicReference<TableMetadata>` publication that
  R7b step 5 establishes.
