---
{
  "id": "sstable.footer-encryption-scope",
  "version": 9,
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

R2g. **Writer-side defensive snapshot of caller-supplied DEK-version array.** The writer of the v6 footer scope section must take a defensive snapshot (e.g. `Arrays.copyOf` or equivalent immutable copy) of any caller-supplied DEK-version array (or `int[]` / `Set<Integer>` / equivalent collection passed to the encode entry point) at method entry, before any subsequent length validation, body-length arithmetic, encode loop iteration, or CRC32C computation. All subsequent validation, arithmetic, and emission must operate exclusively on the snapshot, not on the caller-supplied original. Caller-side mutation of the original between method entry and footer emission must be observably impossible — the encoded bytes must reflect the caller's input as observed at method entry, not as observed at any later point inside the encode method. The snapshot discipline applies symmetrically to every public encode entry point that accepts a mutable collection (array or `Set<Integer>`); a writer that aliases the caller's collection and re-reads it during the encode loop is a spec violation. Validation that the snapshot is internally consistent (R3a ascending, R3b positive, R3 ≤ 65535) must run against the snapshot.

R3a. DEK versions must appear in strictly ascending order. A writer emits them sorted. A reader encountering a descending or equal pair must throw `IOException` indicating corrupt footer.

R3b. Each DEK version must be a positive 32-bit big-endian integer (`version ≥ 1`). A writer must reject version `0` or negative values with `IllegalArgumentException`; a reader encountering version `0` or negative must throw `IOException` indicating corrupt footer. The check applies uniformly to every version in the list. Enforcement must be a runtime conditional, not a Java `assert`.

R3c. When `dek-version-count` is zero, the DEK version list is absent (zero bytes follow). This state is permitted only for a `v6` SSTable that persists no encrypted entries (e.g., a post-compaction SSTable whose pre-image contained only tombstones). See R3e for the reader-side check that enforces this.

R3d. The DEK version set must contain exactly the distinct DEK versions used to encrypt fields persisted within the SSTable. Compaction output SSTables record exactly one current DEK version per `encryption.primitives-lifecycle` R23a/R25b. Flush output SSTables record the single DEK version active at flush time.

R3e. **Reader-side DEK-set pre-resolution check.** The reader must materialise the `dek-version-set` as an in-memory `Set<Integer>` (or equivalent constant-time lookup structure) at footer-parse time. Before invoking any DEK resolver — including bloom-filter probes, key-index scans, or any envelope-header prefetch — the reader must membership-check the envelope's DEK-version field against this set. A check failure must throw `IOException` (or the codebase's `CorruptSectionException`) before any DEK lookup, cache access, or decryption is initiated. This closes the attack where envelope-header reads could touch DEK material for a version not declared in the footer.

R3f. **Empty-DEK-set invariant.** A v6 SSTable with `dek-version-count = 0` must contain no entries whose per-field envelope carries a DEK-version field. The reader must assert this structurally during entry iteration — encountering any envelope-header DEK-version field in a file whose footer declared `dek-version-count = 0` must throw `IOException` indicating corrupt footer (not a DEK-mismatch error, because the attack shape differs: the writer violated R3d, producing a footer that under-reports). The error message must distinguish "empty-set file contained encrypted entries" from R3e's "version not in declared set" to aid diagnosis.

R3g. **ReadContext construction-time discrimination of empty-set semantics.** The construct that materialises the R3e dispatch gate (the per-read context value carrying the in-memory `Set<Integer>` of allowed DEK versions) must distinguish three semantically distinct construction-time states at its public construction surface, rather than collapsing them into a single empty-set sentinel:

- **State A — "scoped read, non-empty DEK set":** the R3e gate is active and admits exactly the declared versions. Construction must accept a non-null, non-empty `Set<Integer>` and reject any null element, any non-positive version, and an empty set with `IllegalArgumentException`.
- **State B — "scoped read, footer-declared zero DEK versions":** corresponds to a v6 SSTable whose footer declared `dek-version-count = 0` per R3c. The R3e gate must deny every version (any DEK-version field in any envelope is a footer/data inconsistency caught by R3f). This state is legitimate only as the consumer of a v6 footer with `dek-version-count = 0`.
- **State C — "unscoped read, no v6 footer present":** corresponds to opening a v5 SSTable (no scope section, no R3e gate semantics applicable). The R3e gate is structurally inert because the read path has no envelope DEK-version fields to compare against.

The general-purpose construction surface (the canonical record constructor accepting `Set<Integer>`) must be reserved for State A and must reject the empty set with `IllegalArgumentException` whose message identifies the silent-deny-all attack and references R3g. Loud-fail at the point of misuse closes the silent-construction gap that the prior contract permitted. Validation must be a runtime conditional (explicit `if`/`throw`), not a Java `assert`.

States B and C must be expressible only through explicitly named static factory methods (or the structural equivalent — for example, sealed-record-instance constants — chosen by the implementation). The implementation is free to choose names; the spec mandates that the names communicate the legitimate-empty-state contract at the call site (e.g. `forZeroDekFooter()` for State B and `forUnscopedRead()` for State C, or single-argument variants whose Javadoc states the contract). A reviewer reading any caller of these factories must, without leaving the call site, understand which of B / C the caller intends. Defaulting an empty set into the canonical constructor must not be an available production path.

R3h. **Caller-site discipline for the empty-set factories.** The factory methods introduced by R3g must be invoked only by:
1. **The v6 footer parse path** when the parsed `dek-version-set` has zero elements (State B). The parse path must select State B's factory at the same dispatch point that produces the populated-set `ReadContext` — branching on `Set.isEmpty()` at footer-parse time, before the value is propagated into the read pipeline. The footer parse path must NOT pass an empty set into the canonical constructor as a substitute for the State B factory.
2. **The v5 (no-scope) reader open path** as the structurally-inert R3e gate carrier (State C). The v5 path uses State C's factory at construction; downstream consumers may not assume State C's gate carries any meaningful version set.
3. **Unit tests of the factory methods themselves**, which are the legitimate construction sites for State B / State C in test code. Tests of State A's behaviour must use the canonical constructor with a non-empty set.

Engine-layer integration code (every reader site in `jlsm-engine` that opens an SSTable in an encrypted table) must consume the `ReadContext` produced by the reader's footer parse path. Engine-layer code must not construct a `ReadContext` directly — neither via the canonical constructor nor via R3g's factories. The audit boundary is: any non-test code outside the SSTable reader's footer-parse path that constructs a `ReadContext` is a spec violation.

R3h shares its trust-boundary disclosure shape with R10g (the byte-layer-test opt-out for scoped writers), R13b (storage substrate), and R8j (`--add-exports` flag): the library does not enforce caller-site discipline at runtime — the boundary is code review and the audit pipeline. The structural defence is R3g's loud-fail-at-canonical-constructor; R3h's purpose is to specify which call sites legitimately reach the empty-state factories so future audit passes have an unambiguous boundary against drift.

### Reader scope validation protocol

R4. A reader opening a `v6` SSTable must, after magic verify and footer CRC32C verify (R2f), parse the scope section per R2a. Parsing must happen before any DEK resolution, block decryption, entry read, bloom-filter probe, or key-index scan that would touch envelope-header DEK-version bytes.

R5. The reader must materialise the **expected scope** from the caller's `Table` handle via `table.metadata().encryption().orElseThrow(IllegalStateException::new)`. The `IllegalStateException` message must identify the Table by name and state "attempt to decrypt SSTable belonging to a Table without encryption metadata" without revealing key material.

R5a. **Parsed v6 footer DEK-version set must be an unmodifiable view independent of its source.** The parsed footer record made available to consumers (the value materialised by the v6 footer parse path that R3e and R4 reference) must expose the `dek-version-set` as an unmodifiable view that is independent of any source from which the parser materialised it (caller-supplied `Set`, internal builder collection, or transient parse buffer). A consumer that mutates (or attempts to mutate) the returned set must observe either no effect or `UnsupportedOperationException`; the parsed record's internal state must remain unchanged regardless of the consumer's actions. The publication discipline must be a defensive copy (`Set.copyOf` or equivalent) — wrapping the caller's source via `Collections.unmodifiableSet` that aliases the source is a spec violation, because mutation of the underlying source would propagate into the wrapper. The contract applies uniformly to every public accessor that returns the DEK-version set on the parsed footer record. R5a closes the immutability hazard surfaced when the parser accepted any `Set` without copy: aliasing the caller's collection lets caller-side mutation reach the parsed record's "immutable" view.

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

R8g. **(amended v5→v6)** Test code requiring a mock Table either (a) uses a test-only factory constructed within `jlsm-engine`'s test module boundary, or (b) extends one of the two permitted internal subtypes (`CatalogTable`, `CatalogClusteredTable`) under the trusted-export carve-out declared by R8h. Both patterns are permitted; the choice is a test-engineering convenience, not a trust-boundary decision. The production module descriptor must not expose any mechanism for external modules (callers without `--add-exports` to the relevant internal package) to construct or subclass `CatalogTable` or `CatalogClusteredTable`. Existing cluster test helpers (`RecordingTable`, `MetadataOnlyStub`, `PermissiveStub`, the `ClusteredTableLocalShortCircuitTest` `RecordingTable`, and the `ResourceLifecycleAdversarialTest` anonymous null-metadata subclass) extend `CatalogClusteredTable` directly under R8h's carve-out and remain compliant with this requirement; ~~existing test stubs that previously declared `implements Table` must migrate to the test-only factory pattern as part of this requirement~~ (struck through: superseded by R8h's explicit carve-out which permits subclass-extension under the trusted export). Test stubs that previously implemented `Table` directly (i.e., before sealing) must migrate to one of the two permitted patterns; mock libraries that fabricate `Table` instances reflectively are out-of-scope per R8f bullet 4.

R8h. **Trusted-export threat-model carve-out.** The trust boundary that R8e (sealed `Table`) and R8f (runtime defences) enforce is the JPMS `exports` declaration in `module-info.java` — which intentionally does NOT export `jlsm.engine.internal` or `jlsm.engine.cluster.internal`. Callers who add `--add-exports jlsm.engine/jlsm.engine.internal=...` or `--add-exports jlsm.engine/jlsm.engine.cluster.internal=...` to the JVM at startup are explicitly out of the threat model: they have build-system / startup-flag access equivalent to the reflection access already declared out-of-scope by R8f bullet 4. The library's test build sets these exports for `ALL-UNNAMED` (see `modules/jlsm-engine/build.gradle`) so that in-tree test code can subclass the permitted internal subtypes; this is a trusted in-tree convention, not a sanctioned external API. Under this carve-out the following attack vectors are accepted as out-of-model:

- Subclass override of any non-`final` method on `CatalogTable` or `CatalogClusteredTable` (including `metadata()`, the CRUD methods, and lifecycle methods) to forge return values.
- Direct construction of `CatalogTable` or `CatalogClusteredTable` instances via package-private constructors (which are reachable through the `--add-exports` flag because Java's package-private access is package-name based, not module-derived).
- Bypass of the catalog-mediated factory methods (`Engine.getTable`, `createTable`, `createEncryptedTable`; the `ClusteredEngine` analogues).

The cryptographic defence against the metadata-forgery vector is the HKDF scope binding from `encryption.primitives-lifecycle` R11 (cross-referenced at R6b). The HKDF binding makes wrong-scope decryption cryptographically impossible — an attacker who forges `metadata()` to return scope X cannot recover plaintext from an SSTable bound to scope Y. The HKDF binding does NOT prevent an attacker who has both `--add-exports` access AND legitimate read access to scope X from routing scope-X bytes through a Table handle whose `metadata()` reports scope Y (the resulting plaintext is scope-X plaintext, which the attacker is already authorised to read; the spec gap closed here is "the read appears in scope Y's audit trail rather than scope X's"). Operators relying on per-table audit-trail attribution must treat the trusted-export carve-out as a deployment-time invariant — see R8j.

R8i. **`CatalogClusteredTable.metadata()` and `CatalogTable.metadata()` are non-`final` by design.** The non-final declaration is a deliberate consequence of R8h: it permits in-tree test stubs to override `metadata()` for adversarial scenarios that the test-only factory pattern of R8g(a) cannot cover (e.g., returning `null` to drive rollback-NPE paths, returning a deliberately-mismatched scope to exercise R6's component-wise comparison, or returning a `TableMetadata` whose `encryption` flips between calls to exercise R10c's TOCTOU re-check). Authoring `metadata()` as `final` would block these tests; the cryptographic defence (R6b HKDF binding) renders the type-system finality contribution null in any case once the attacker has the `--add-exports` flag (R8h). 

This non-finality is scoped to `metadata()` specifically as the encryption-check entry point identified by R5. Implementations of the two permitted Table subtypes MUST NOT add internal logic — anywhere in the catalog or encryption read path — that depends on `metadata()` being non-overridable as a defence (e.g., a `getClass() == CatalogClusteredTable.class` check, or a `Table` reference comparison used as a "this is the real thing" assertion). Any internal logic that needs the construction-bound `TableMetadata` must read the private final `tableMetadata` field directly (or route through a `private final` helper that does so), not through a virtual call to `metadata()`. This rule applies equally to future code added to either permitted subtype.

R8j. **Production deployment guidance for the `--add-exports` flag.** Library packagers and integrators must not set `--add-exports jlsm.engine/jlsm.engine.internal=...` or `--add-exports jlsm.engine/jlsm.engine.cluster.internal=...` in production deployments. The library does not enforce this at runtime — the trust boundary is the build/deploy configuration, identical in shape to R13b's "deploying jlsm encryption on storage where write access to SSTable bytes is not authenticated is unsupported" pattern. This is a documentation item: jlsm's deployment guide must state explicitly that production builds run with the standard JPMS exports only (no `--add-exports` to internal packages); test builds may set the flag scoped to the `test` task. Setting the flag in production is unsupported and voids the threat-model coverage of R8e/R8f for the affected internal package.

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

R10b-bis. **Writer's `finish()` must trap RuntimeException from the commit-hook re-resolve step.** A scoped writer's `finish()` method must catch any `RuntimeException` that escapes from the commit-hook re-resolve step (R10c step 3 — fresh catalog read or re-resolved scope materialisation), transition the writer to the `FAILED` state per R10a, and propagate the original `RuntimeException` to the caller wrapped in `IOException` (with the original as the cause via `IOException(message, cause)`). Specifically: a commit hook that returns `null` (causing `NullPointerException` on the next field access), throws `IllegalStateException`, throws any other unchecked exception, or otherwise fails to produce a valid fresh scope must NOT leave the writer in `OPEN` state. The required catch chain in the `finish()` method must cover at minimum `IOException`, `ClosedByInterruptException`, and `RuntimeException` — narrower catches that miss `RuntimeException` are a spec violation because they leave the writer eligible for a retry that would re-run the same broken hook with the same broken result. The fault-containment discipline aligns with R10a's `FAILED`-state invariant and R10b's one-shot-after-failure invariant: any irrecoverable error during R10c steps 3–6, regardless of exception type, must terminate the writer in `FAILED` state.

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

R10d-bis. **Non-FileChannel writers must invoke the application-level fsync-listener at the close-before-move boundary.** When the writer's underlying output is not a `FileChannel` — for example, the remote `SeekableByteChannel` pattern that `io-internals` mandates for S3/GCS-compatible code paths — the application-level fsync-listener invocation that R10d implies at the close→rename boundary must still fire, with a documented `reason` value of `"close-before-move"`. The listener-symmetry contract is: every writer commit produces a single application-level fsync-listener event observable to subscribers (backup hooks, progress trackers, integrity-check schedulers, audit pipelines), regardless of whether the underlying substrate is a local `FileChannel` (where R10d's double-fsync orchestration produces the event) or a remote `SeekableByteChannel` (where the substrate has no `force()` operation and the listener fires from the close-before-move path). A non-`FileChannel` writer that closes its channel and renames its file without invoking the listener has skipped the listener-symmetry contract and is a spec violation. The contract is structural: observers see the same lifecycle event shape across local and remote backends, so cross-backend test fixtures and operational dashboards remain semantically uniform.

R10e. **Writers are not restart-resumable.** On process restart, any SSTable tmp file lacking committed magic (see `sstable.end-to-end-integrity` R40) must be deleted during recovery, not resumed. A writer's decision about `v5` vs `v6` emit is made at `open` time (and re-checked at `finish` per R10c) within a single process lifetime. No mechanism may rehydrate a partially-written SSTable from a prior process's state. This closes the attack where a pre-crash writer's `v5`-emit intent could survive a restart after `enableEncryption` committed.

R10f. **Scoped writers must be paired with a commit hook at construction.** A writer construction path that accepts a non-null `TableScope` must also accept a non-null commit-hook reference (the SPI defined for R10c steps 2–7) AND a non-null logical table name for the lock. If any of these three values are present without the other two, writer construction must fail with `IllegalStateException` at the construction-time validation site (i.e. at the public builder's `build()` call, before any output file is opened). The error message must identify which of the three values is missing and must reference R10c without revealing scope identifiers (R12).

This requirement closes the silent-bypass attack in which a scoped writer constructed without a hook would emit a v6 footer using construction-time scope and skip the R10c fresh-catalog re-read entirely — re-introducing the TOCTOU window that R10c exists to defend. The fail-fast belongs in the construction code path, not in a comment or in convention. R10f operates at the same trust-boundary level as R8e's sealed `Table` and R9a-mono's catalog-index downgrade defence: a structural rather than soft enforcement of the protocol.

R10f applies to every public construction path the writer exposes — every builder, factory, or constructor visible from outside the writer's owning package. Internal package-private constructors used only for byte-layer test harnesses are subject to R10g's narrow opt-out, not R10f.

R10g. **Narrow byte-layer test opt-out.** A separate construction path may permit a scoped writer without a commit hook **only** under all of the following conditions, none of which may be relaxed:

1. The path is exposed via an explicitly named opt-out method on the public builder (or an equivalent named API surface) — for example `commitHookOmittedForTesting()` — whose name communicates that the call site has knowingly opted out of the R10c TOCTOU defence. The opt-out must NOT be the default. A scoped writer that has not invoked the opt-out and has not supplied a commit hook must fail per R10f.
2. Once invoked, the opt-out flag is sticky for the lifetime of the builder; a subsequent `commitHook(...)` call on the same builder must reject the combination with `IllegalStateException` rather than silently un-opt-out. This prevents drive-by re-introduction of a hook without removing the opt-out marker, which would obscure the bypass intent.
3. Every writer constructed via the opt-out path must, at `finish()`-time, take the legacy `else if (scope != null)` branch (i.e. emit the v6 scope section with construction-time scope, no fresh re-read). The opt-out must NOT compose with the commit-hook protocol — it is a strict alternative, not a layered defence.
4. The opt-out is not a sanctioned engine-integration path. Engine integration code (every writer site in jlsm-engine that produces a committed SSTable in an encrypted table — flush, compaction, repair) MUST supply a commit hook and MUST NOT invoke the opt-out method. The audit boundary is: any production-path code calling the opt-out method is a spec violation.
5. Test code invoking the opt-out path accepts that the resulting writer does not enforce R10c. Tests may exercise the byte-layer footer emit, scope section encoding, and v5/v6 dispatch in this mode, but tests of the R10c protocol itself must use the commit-hook path.
6. The opt-out method's documentation must state explicitly that it bypasses R10c, must reference this requirement (R10g), and must state that it is intended only for byte-layer / unit-test harnesses.

The opt-out is the named test-engineering carve-out; it is not a "documented escape hatch in production." Its existence trades a structural fail-fast for a visible call-site signal — every site that opts out of R10c is grep-able by method name. Without the opt-out, the byte-layer unit tests for the v6 footer emit path could not be authored without standing up the engine layer's catalog and commit-hook implementation, which would couple jlsm-core unit tests to jlsm-engine. The carve-out preserves the layering boundary while keeping the bypass visible.

R10g shares its trust-boundary disclosure shape with R13b (storage substrate) and R8j (`--add-exports` flag): the library does not enforce that production code refrains from the opt-out at runtime. Code review and the audit pipeline are the enforcement mechanism. A production code site that invokes the opt-out is a spec violation surfaced by the next audit pass.

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

R12a. **Format-version byte values must be redacted from exception messages.** When loading or reading any catalog file (`table.meta`, catalog index) or SSTable file, an error caused by an unknown format-version byte must NOT include the offending byte's numeric value (decimal, hexadecimal, character escape, or any other encoding) in the exception message. The message must state that an unknown format version was encountered (e.g., "unknown format version in <file-identity>") and may identify the file by name or path, but the byte value itself must be omitted. R12a generalises R12's "no DEK / no key material" discipline to the format-discriminant byte, on the principle that file-structure bytes are part of the same redaction class as DEK material — leaking the byte value to a caller (or to a log forwarded out of the trust boundary) gives an attacker a positive signal that distinguishes "valid version" from "invalid version" by inspection of an error message produced by an unauthenticated probe. The R12a redaction applies uniformly to every catalog and SSTable file format-version byte, including future format versions added by amendments to `table-catalog-persistence`, `sstable.end-to-end-integrity`, or this spec. Validation must be a runtime conditional (explicit `if`/`throw`), not a Java `assert`.

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

**Out of scope** (delegated to the trusted-export carve-out per R8h):
- Subclass override of `metadata()` (or any other non-`final` method)
  on `CatalogTable` / `CatalogClusteredTable` by callers with
  `--add-exports jlsm.engine/jlsm.engine.{internal,cluster.internal}=...`
  access. This is treated identically to R8f bullet 4's reflection
  carve-out: callers with build-system / startup-flag access are
  in-tree trusted, equivalent to having reflection access. The HKDF
  scope binding (R11 of `encryption.primitives-lifecycle`) remains
  the cryptographic defence against wrong-scope plaintext recovery;
  the carve-out closes the audit-trail attribution gap (an attacker
  with both `--add-exports` and legitimate scope-X read access can
  route scope-X bytes through a forged scope-Y handle, but cannot
  recover plaintext they were not already authorised to read).
  Production deployments must not set the `--add-exports` flag —
  see R8j.

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

- **Soft "documented escape hatch" for scope-without-hook writer
  construction** — rejected. The audit run-001 finding
  F-R1.resource_lifecycle.1.2 demonstrated that the legacy `else if
  (scope != null)` branch in the writer's `finish()` produces a
  silent R10c bypass: a scoped writer without a hook emits a v6
  footer using construction-time scope, skipping the fresh-catalog
  re-read. Two resolutions were considered:
  - **Loud-fail with no carve-out** — every scoped writer must have
    a hook. Rejected because the byte-layer unit tests for v6 footer
    emit, scope-section encoding, and v5/v6 dispatch (in `jlsm-core`)
    cannot reasonably stand up the engine-layer catalog and commit-hook
    implementation without coupling the layering boundary.
  - **Documented escape hatch** — the legacy branch is permitted with
    a comment explaining R10c-bypass semantics. Rejected because this
    is exactly the
    `silent-fallthrough-integrity-defense-coupled-to-flag` pattern
    already in this spec's `kb_refs` — a "documented" bypass is
    indistinguishable from an undocumented bypass once the
    documentation drifts from the call sites.
  v7 (this amendment) adopts a third resolution: **structural fail-fast
  (R10f) plus a narrowly-named test opt-out (R10g)**. Production code
  paths cannot construct a scoped writer without a hook; test code
  paths must invoke an explicitly named opt-out method, making every
  bypass site grep-able. This trades a hard structural defence for a
  visible call-site signal — same trust-boundary shape as R13b
  (storage substrate) and R8j (`--add-exports` flag). The
  enforcement-against-production-misuse is code review and the audit
  pipeline, not runtime check; the structural defence is the
  fail-fast that catches the unintentional case (a developer forgetting
  the hook), and the named opt-out catches the intentional case (a test
  knowingly bypassing).

- **Forcing `metadata()` final on `CatalogTable` / `CatalogClusteredTable`** —
  rejected because the type-system finality contributes nothing to
  the threat model once the attacker has the project's `--add-exports`
  flag (R8h's carve-out). The cryptographic defence (R6b HKDF
  binding) remains the residual barrier against wrong-scope plaintext
  recovery, regardless of whether `metadata()` is final or virtual.
  Authoring `metadata()` as final would block five in-tree test
  stubs that depend on overriding it for adversarial scenarios
  (deliberate-null metadata to drive rollback-NPE paths,
  scope-mismatch to exercise R6, encryption-state flip to exercise
  R10c's TOCTOU re-check). v6 of the spec (this amendment) adopts
  R8i as the explicit non-finality declaration and R8h as the
  named threat-model carve-out, replacing the implicit "boundary
  observation" in v5 that the audit pipeline (run-001) flagged
  as a structural gap (F-R1.dispatch_routing.3.1, FIX_IMPOSSIBLE).

- **Single canonical constructor accepting any `Set<Integer>` (including
  empty)** — rejected. The audit run-001 finding F-R1.resource_lifecycle.4.10
  demonstrated that allowing the canonical constructor to accept an empty
  set silently constructs a "deny-all" R3e dispatch gate, separating the
  point of misuse (caller passes `Set.of()` by mistake or after a footer-parse
  bug) from the point of failure (R3f loud-fails on the next envelope, far
  from the cause). Two resolutions were considered:
  - **Reject empty at canonical constructor with no carve-out** — would
    correctly close the silent-deny-all gap but would simultaneously prevent
    the legitimate v5 (no-scope) reader path and the legitimate v6
    zero-DEK-footer reader path from constructing the read-context value
    at all. Both paths exist in the SSTable reader (`TrieSSTableReader`
    open paths) and both are valid R3c/R3e consumers. A pure loud-fail
    would force one of these legitimate paths into a contortion (e.g.,
    constructing with `Set.of(0)` and then post-filtering, which itself
    is a worse silent failure).
  - **Single named factory `forZeroDekFooter()` only** — addresses State B
    (v6 with `dek-version-count = 0`) but conflates State C (v5 reader
    with no scope section) with either State A (forcing v5 callers to
    invent a synthetic non-empty set) or State B (mis-signalling that
    a v5 reader has a "zero-DEK footer" when it has no footer scope at
    all). Naming State B without naming State C produces a less truthful
    contract than three explicit states.
  v8 (this amendment) adopts a third resolution: **canonical constructor
  loud-fails on empty (R3g), and TWO named factories — one each for
  State B and State C — express the legitimate empty-state contracts
  (R3g + R3h)**. The implementation chooses the names; the spec mandates
  that they communicate the legitimate-empty-state contract at the call
  site so a reviewer reading any production caller sees the intent.
  Caller-site discipline (R3h) is enforced by code review + audit pipeline,
  same trust-boundary disclosure shape as R10g (byte-layer-test opt-out),
  R13b (storage substrate), and R8j (`--add-exports` flag). The structural
  fail-fast catches the unintentional case (a developer passing an empty
  set into the canonical constructor); the named factories catch the
  intentional case (a footer-parse path or v5 path knowingly producing
  an empty-state read context).

---

## Verification Notes

### Verified: v9 — 2026-04-25 (state: APPROVED — audit reconciliation amendment, source change required)

Audit reconciliation work (audit run `implement-encryption-lifecycle--wd-02/audit/run-001`) surfaced five spec gaps where audit fixes addressed bugs the existing requirements did not cover. v9 adds five tightening requirements:

- **R2g (new):** writer-side defensive snapshot of caller-supplied DEK-version array at encode-method entry. Closes the TOCTOU between length validation and encode-loop emission. Source: F-R1.contract_boundaries.1.001 (`V6Footer.encodeScopeSection` trusted caller's `int[]`).
- **R5a (new):** parsed v6 footer record must expose `dek-version-set` as an unmodifiable view independent of any source. Closes the immutability hazard where the parser accepted any `Set` without copy. Source: F-R1.contract_boundaries.1.002 (`V6Footer.Parsed` accepted any `Set` without copy).
- **R10b-bis (new):** writer's `finish()` must trap `RuntimeException` from the commit-hook re-resolve step (R10c step 3), transition to `FAILED`, and propagate wrapped in `IOException`. Closes the silent-stay-OPEN bug when commit hook returned null / threw NPE / threw any other unchecked exception. Source: F-R1.resource_lifecycle.1.3 (`finishUnderCommitHook` with null `freshScope` NPE'd through writer with state==`OPEN`).
- **R10d-bis (new):** non-`FileChannel` writers must invoke the application-level fsync-listener with `reason="close-before-move"`. Closes the listener-symmetry gap on remote `SeekableByteChannel` writers where R10d's `FileChannel`-based double-fsync orchestration cannot fire. Source: F-R1.resource_lifecycle.1.5 (`closeChannelQuietly` ran before commit; remote writers never invoked listener).
- **R12a (new):** format-version byte values must be redacted from exception messages on unknown format versions, generalising R12's no-key-material discipline to file-structure bytes. Source: F-R1.contract_boundaries.2.3 (`TableCatalog.readMetadata` leaked offending byte value).

**Verification impact:**

- All five additions are tightening (gap closure), not scope changes. No existing requirement is invalidated.
- R2g pairs with R3/R3a/R3b on the writer side. R5a pairs with R3e/R4 on the reader side. R10b-bis tightens R10a/R10b's failure-state contract. R10d-bis closes the listener-symmetry gap that R10d's `FileChannel`-only ordering implicitly assumed away. R12a generalises R12's redaction class.
- Implementation impact: writer encode entry points adopt `Arrays.copyOf` / equivalent at method entry; parser materialises `Set.copyOf` of the parsed DEK versions; `finish()` adds `catch (RuntimeException)` to the commit-hook handler; non-`FileChannel` writers invoke the listener at the close-before-move boundary; catalog/SSTable readers omit the byte value from unknown-version exception messages.

**Overall: APPROVED — amendment with source changes required.** Audit run-001 already fixed each of the five bugs in source; v9 captures the contract invariants those fixes enforce.

### Verified: v8 — 2026-04-25 (state: APPROVED — amendment, source change required)

Audit relaxation work (audit run `implement-encryption-lifecycle--wd-02/audit/run-001`,
finding F-R1.resource_lifecycle.4.10) closed the silent-deny-all attack on the
`ReadContext` canonical constructor. The prove-fix protocol reached FIX_IMPOSSIBLE
because the existing TDD test
`ReadContextTest.constructor_acceptsEmptySet_forEmptyDekSetSstable` (lines 36-45
of `ReadContextTest.java`) encoded the bug as the contract: "`ReadContext` must
accept `Set.of()` and produce a record whose `allowedDekVersions()` is empty."
That test must change as part of resolving this finding (RELAX-5 escalation). v8
adds two new requirements to R3's tightening cluster:

- **R3g (new):** the public construction surface for the R3e dispatch gate must
  distinguish three states — State A (scoped read, non-empty DEK set; canonical
  constructor), State B (scoped read, footer-declared zero DEK versions; named
  factory), State C (unscoped read, no v6 footer present; named factory). The
  canonical constructor must reject the empty set with `IllegalArgumentException`.
  Loud-fail at construction belongs at the point of misuse, not deferred to R3f's
  iteration-time check; R3f remains as defence-in-depth.
- **R3h (new):** caller-site discipline. The named factories may be invoked only
  by the v6 footer parse path (State B), the v5 reader open path (State C), and
  unit tests of the factories themselves. Engine-layer reader sites must not
  construct `ReadContext` directly. Same trust-boundary disclosure shape as
  R10g, R13b, R8j: enforcement is code review + audit pipeline, not runtime.

**Implementation impact (the source change required):**

- `ReadContext` (`modules/jlsm-core/src/main/java/jlsm/encryption/ReadContext.java`)
  canonical constructor adds a guard: `if (allowedDekVersions.isEmpty()) throw
  new IllegalArgumentException(...)` whose message identifies the silent-deny-all
  attack and references R3g.
- `ReadContext` adds two static factories — names chosen by the implementation
  (e.g., `forZeroDekFooter()` for State B and `forUnscopedRead()` for State C, or
  semantically equivalent named factories that communicate the empty-state contract
  at the call site). Each factory's Javadoc cites R3g and identifies the
  legitimate caller path (v6 footer parse with `dek-version-count = 0` for State
  B; v5 reader open path for State C).
- `TrieSSTableReader` (existing call sites at lines 194, 366, 371) is updated:
  - Line 194 (legacy v1 reader path / pre-v6 default): switches to the State C
    factory; this site is structurally inert under R3e.
  - Line 366 (v6 path with parsed DEK-version set): branches on
    `parsed.dekVersionSet().isEmpty()` at this dispatch site — selecting the
    State B factory if empty, the canonical constructor if non-empty. The
    branch must occur at this call site, not be deferred into the constructor
    (R3h's "footer parse path must NOT pass an empty set into the canonical
    constructor as a substitute for the State B factory").
  - Line 371 (v5 path, no scope): switches to the State C factory.
- `ReadContextTest.constructor_acceptsEmptySet_forEmptyDekSetSstable` is replaced
  by three tests (this is the test contract change RELAX-5 explicitly authorises):
  - `constructor_rejectsEmptySet_R3g` — `assertThrows(IllegalArgumentException.class,
    () -> new ReadContext(Set.of()))`. Becomes the negative test for R3g.
  - `forZeroDekFooter_producesEmptyAllowedVersions_R3g_StateB` — exercises the
    State B factory: produces a `ReadContext` whose `allowedDekVersions().isEmpty()`
    is true, suitable for the v6 footer-declared-zero-DEK case.
  - `forUnscopedRead_producesEmptyAllowedVersions_R3g_StateC` — exercises the
    State C factory: produces a `ReadContext` whose `allowedDekVersions().isEmpty()`
    is true, suitable for the v5 reader open path.
- The hot-path same-instance-across-accessor invariant (Lens B finding asserted by
  `ReadContextTest.recordComponent_returnsSameInstanceAcrossAccessors`) must hold
  for both factory-produced instances — the empty unmodifiable set must be the
  same identity across `allowedDekVersions()` calls on a given factory-produced
  instance (and may legitimately be the same across all State B / State C
  instances if the implementation caches `Set.of()` as a singleton).

**Overall: APPROVED — amendment with source change required.** The current
`ReadContext` canonical constructor (lines 51-57 of `ReadContext.java`) silently
constructs a deny-all R3e dispatch gate when the empty set is passed; v8 requires
the canonical constructor to loud-fail and the legitimate empty-state cases to
flow through named factories. The blocking test at `ReadContextTest.java:36-45`
is replaced per the test contract change documented above.

#### Authoring log (autonomous decisions made under non-interactive subagent invocation)

This spec amendment was authored under a non-interactive subagent invocation
from the audit pipeline's spec-author dispatcher (RELAX-5). The protocol
normally prompts the user between Pass 1 and Pass 2 and during arbitration;
under the non-interactive constraint the author made the following best-judgment
decisions and recorded them here for review:

1. **Amendment vs new spec:** chose to amend `sstable.footer-encryption-scope`
   (bump v7 → v8) rather than author a separate `encryption.read-context-construction`
   spec. Rationale: R3c/R3e/R3f already live here as the DEK-set / R3e-gate
   cluster; R3g/R3h are tightening of the same protocol. Fragmenting the
   construction-time contract across a separate spec would split the threat-model
   boundary documentation. Aligns with project memory `feedback_spec_inplace_amendment`
   (in-place amendment over spec split) and matches RELAX-1's R8h–R8j choice
   (metadata-finality carve-out) and RELAX-4's R10f/R10g choice (scoped writer
   without commit hook).

2. **Two-state vs three-state factory model:** the brief offered two resolutions
   — (a) reject empty at construction, or (b) introduce a single
   `forZeroDekFooter()` factory. The author chose a third (three-state model).
   Rationale: the `ReadContext` empty-set case has THREE distinct production
   call sites in the existing source — `TrieSSTableReader` lines 194 (v1 legacy),
   366 (v6 with parsed empty set), and 371 (v5 no-scope). These collapse into
   the single `Set.of()` value today, but they correspond to two different
   contracts (State B = v6 with declared zero DEKs; State C = v5 with no scope
   section at all). A single factory would conflate them and force one of the
   call sites into a misleading factory name. Three-state separation makes each
   call site grep-able for its intent — same enforceability shape as R10g's
   named opt-out for scoped writers without a commit hook. This matches
   project memory `feedback_exercise_processes_pre_ga` (theoretical "soft"
   policies are worse than enforced ones; named factories are enforceable
   call-site signals, defaulting an empty set into the canonical constructor
   is not).

3. **Loud-fail at canonical constructor + R3f as defence-in-depth:** chose to
   keep R3f's iteration-time loud-fail unchanged, even though R3g now closes
   the silent-construction gap that R3f was the only line of defence against.
   Rationale: R3g and R3f defend against different attack shapes. R3g defends
   against caller misuse at the construction site (the silent-deny-all
   mistake). R3f defends against writer/footer corruption — a v6 SSTable whose
   footer declared `dek-version-count = 0` but whose entries carry envelope
   DEK-version fields (a writer-side R3d violation). A construction-time
   guard cannot catch the writer-side violation because the construction
   happens with the parsed-empty set; only iteration over actual entries
   reveals the inconsistency. R3f remains essential, layered as
   defence-in-depth behind R3g.

4. **Caller-site discipline (R3h) as audit-boundary, not runtime defence:**
   chose to specify R3h as a code-review/audit-pipeline boundary rather than
   add a runtime check (e.g., a stack-trace inspection or caller-class check
   in the factory methods). Rationale: jlsm's spec corpus consistently uses
   the trust-boundary disclosure pattern for this shape of contract (R10g,
   R13b, R8j) — the library does not enforce caller-site discipline at
   runtime; the boundary is the build/deploy/code-review configuration.
   Adding a runtime check at the factory would be a false-confidence defence
   (the engine-layer caller could trivially construct a `ReadContext` via
   the canonical constructor with a non-empty placeholder set and then
   never use it). The structural defence is R3g's loud-fail-at-canonical-constructor;
   R3h's purpose is to specify the legitimate boundary so future audit
   passes can detect drift.

5. **Test contract change:** chose to authorise the test rewrite (replacing
   `constructor_acceptsEmptySet_forEmptyDekSetSstable` with three new tests)
   as part of this amendment. Rationale: the prove-fix protocol reached
   FIX_IMPOSSIBLE precisely because the test encoded the bug as the contract;
   the spec is the source of truth for behaviour. The test was authored under
   the prior contract that conflated all three empty-set states into a single
   constructor call — once the spec distinguishes them, the test must follow.

6. **No prerequisite stubs needed:** R3g and R3h reference R3c, R3e, R3f, R10g,
   R13b, R8j — all are existing APPROVED requirements in the spec corpus. R3g
   does not name the factory methods (it mandates "explicitly named static
   factory methods or the structural equivalent"); the implementation is free
   to choose the exact names.

7. **Adversarial passes (Pass 2 + Pass 3) run autonomously:**
   - **Boundary validation probe (Pass 2):** asked whether R3g's
     `IllegalArgumentException` could be bypassed by caller construction
     of a single-element-then-clear pattern (e.g.,
     `new ReadContext(new HashSet<>(List.of(1))).allowedDekVersions().clear()`).
     Answer: no — the existing R3e contract (and the existing
     `recordComponent_returnsSameInstanceAcrossAccessors` test) requires
     `Set.copyOf` to produce an unmodifiable view; clear() on the returned
     set throws `UnsupportedOperationException`. The construction-time
     non-empty guard combined with the unmodifiable accessor view is
     sufficient.
   - **Cross-construct atomicity probe (Pass 2):** asked whether R3g
     interacts with R3e's "before invoking any DEK resolver" timing
     requirement. Answer: no — R3g is a construction-time check; R3e is
     a runtime dispatch check. They operate at different points in the
     read pipeline and are independent.
   - **Concurrency contract probe (Pass 2):** asked whether the new
     factories have thread-safety implications. Answer: no — `ReadContext`
     remains an immutable record (per existing R11 thread-safety
     declaration), and the factories produce immutable record instances.
     A factory may legitimately cache its empty-state instance as a
     singleton (no allocation per call) without thread-safety concern,
     since the instance is structurally immutable.
   - **Trust boundary probe (Pass 3):** asked whether R3h's "engine-layer
     code must not construct `ReadContext` directly" disclosure has the
     same enforceability problems as R8j and R10g. Answer: yes, identical.
     Added the explicit cross-reference in R3h's final paragraph so the
     trust-boundary shape is consistent across R3h, R8j, R10g, R13b.
   - **Identity and equality probe (Pass 3):** asked whether two
     `ReadContext` instances produced by State B and State C factories
     must be `equals()` to each other (both have empty allowed-versions
     sets). Answer: under the existing `equality_isComponentWise` test
     contract, yes — both produce records whose single component is
     equal (the empty unmodifiable set). The spec does not need a new
     requirement here because R3g distinguishes the states at the
     construction site, not at the equality / value level. Two
     factory-produced instances being structurally equal is acceptable;
     the call-site discipline (R3h) is what carries the semantic
     distinction. If a future spec needs to distinguish State B from
     State C at the value level (e.g., for distinct error messages from
     R3f), it would require a discriminator field — but the current
     R3f contract does not need this; R3f's distinction is between
     "footer declared zero versions" (State B's failure mode) and
     "version not in declared set" (State A's failure mode), and v5
     readers (State C) never reach R3f because v5 entries have no
     envelope DEK-version field at all.
   - **Error propagation probe (Pass 3):** asked whether R3g's
     `IllegalArgumentException` discloses information that R12 forbids
     (DEK bytes, footer offsets, control-codepoint values). Answer: no
     — R3g's exception identifies the silent-deny-all attack pattern
     and references R3g; it does not reveal any DEK material, footer
     bytes, or scope identifiers. The implementation message must remain
     within R12's discipline (the construction site has no scope or
     DEK context to leak).

### Verified: v7 — 2026-04-25 (state: APPROVED — amendment, source change required)

Audit relaxation work (audit run `implement-encryption-lifecycle--wd-02/audit/run-001`,
finding F-R1.resource_lifecycle.1.2) closed the silent R10c-bypass attack on the
public Builder path. The prove-fix protocol reached FIX_IMPOSSIBLE because the
existing test
`TrieSSTableWriterR10cTest.scopedWriter_withoutHook_finishStillSucceeds_butWarnsOrCommitsOptionally`
encoded the bug as the contract: "scoped writer without a hook builds and finishes
successfully." That test must change as part of resolving this finding (RELAX-4
escalation). v7 adds two new requirements to R10's tightening cluster:

- **R10f (new):** scoped writers must be paired with a commit hook at construction.
  Public construction paths must fail-fast with `IllegalStateException` if
  `scope` is supplied without `commitHook` and `tableNameForLock`. The fail-fast
  belongs in production code, not in a comment or in test convention. Operates
  at the same trust-boundary level as R8e's sealed `Table` (compile-time
  structural defence) and R9a-mono's catalog-index downgrade prevention.
- **R10g (new):** narrow byte-layer test opt-out. A separate, explicitly named
  builder method (e.g. `commitHookOmittedForTesting()`) permits a scoped writer
  without a hook for byte-layer unit tests. The opt-out is sticky (cannot
  un-opt-out by adding a hook later), is incompatible with the commit-hook
  protocol (strict alternative, not layered), and is not a sanctioned engine path.
  Trust-boundary disclosure shape matches R13b (storage substrate) and R8j
  (`--add-exports` flag): code review + audit pipeline are the enforcement
  mechanism for production-path misuse.

**Implementation impact (the source change required):**

- `TrieSSTableWriter.Builder.build()` adds a guard that throws
  `IllegalStateException` when `scope != null` and `commitHook == null` (and
  the opt-out flag has not been set), referencing R10f.
- `TrieSSTableWriter.Builder` adds a `commitHookOmittedForTesting()` method
  that sets a sticky bypass flag, referencing R10g. The method's javadoc
  references R10g and states the call site has knowingly opted out of R10c.
- `TrieSSTableWriter.Builder.commitHook(...)` adds a guard that throws
  `IllegalStateException` if invoked after `commitHookOmittedForTesting()`,
  per R10g bullet 2.
- `TrieSSTableWriterR10cTest.scopedWriter_withoutHook_finishStillSucceeds_butWarnsOrCommitsOptionally`
  is replaced by two tests (this is the test contract change RELAX-4 explicitly
  authorises):
  - `scopedWriter_withoutHook_failsAtBuild_R10f` — `assertThrows(IllegalStateException.class, () -> ...)`
    against the public Builder path with `.scope(...).dekVersions(...).build()`
    and no hook. Becomes the negative test for R10f.
  - `scopedWriter_withOptOut_finishStillSucceeds_R10g` — exercises the
    opt-out path: `.scope(...).dekVersions(...).commitHookOmittedForTesting().build()`,
    appends, finishes, asserts non-null `SSTableMetadata`, asserts the
    legacy `else if (scope != null)` branch was taken (no fresh re-read).
- Additional test asserting R10g bullet 2 (sticky opt-out): `.commitHookOmittedForTesting().commitHook(hook).build()`
  must throw `IllegalStateException`.

**Overall: APPROVED — amendment with source change required.** The current
writer source (lines 423-430 of `TrieSSTableWriter.java`) silently bypasses R10c
when scope is set without a commit hook; v7 requires the fail-fast guard at
`Builder.build()` plus the named opt-out method. The blocking test at
`TrieSSTableWriterR10cTest.java:165-181` is replaced per the test contract
change documented above.

#### Authoring log (autonomous decisions made under non-interactive subagent invocation)

This spec amendment was authored under a non-interactive subagent invocation
from the audit pipeline's spec-author dispatcher (RELAX-4). The protocol
normally prompts the user between Pass 1 and Pass 2 and during arbitration;
under the non-interactive constraint the author made the following best-judgment
decisions and recorded them here for review:

1. **Amendment vs new spec:** chose to amend `sstable.footer-encryption-scope`
   (bump v6 → v7) rather than author a separate `sstable.writer-construction-validation`
   spec. Rationale: R10c lives here and R10f/R10g are tightening of the same
   protocol — fragmenting the requirement set across a separate spec would
   create cross-reference drift and split the threat-model documentation.
   Aligns with project memory `feedback_spec_inplace_amendment` (in-place
   amendment over spec split) and matches RELAX-1's prior choice for the
   `metadata()` finality carve-out (R8h–R8j).

2. **Loud-fail vs documented escape hatch vs structural-fail-plus-named-opt-out:**
   the brief offered two resolutions; the author chose a third (R10f + R10g
   pair). Rationale: pure loud-fail breaks the byte-layer unit tests in
   `jlsm-core` that exercise v6 footer emit without an engine; pure
   documented-escape-hatch is the very `silent-fallthrough-integrity-defense-coupled-to-flag`
   pattern already in this spec's `kb_refs` and reproduces the bug class.
   The named-opt-out approach (R10g) preserves byte-layer testing while
   making every bypass site grep-able by method name — same trust-boundary
   shape as R13b's "deploying jlsm encryption on storage where write access
   to SSTable bytes is not authenticated is unsupported" and R8j's
   `--add-exports` flag carve-out. Project memory
   `feedback_exercise_processes_pre_ga` reinforces this: theoretical "soft"
   policies are worse than enforced ones; a named opt-out is enforceable
   (grep + audit), a comment-documented bypass is not.

3. **Sticky opt-out (R10g bullet 2):** chose to make the opt-out flag
   sticky — once invoked, a subsequent `commitHook(...)` call fails rather
   than silently rehydrating into the protocol path. Rationale: the
   alternative (last-call-wins composition) would let a reviewer miss the
   opt-out marker. A sticky flag forces the developer who wants to add a
   hook to first remove the opt-out call, which makes the intent change
   visible in the diff.

4. **Test contract change:** chose to authorise the test rewrite (replacing
   `scopedWriter_withoutHook_finishStillSucceeds_butWarnsOrCommitsOptionally`
   with the two new tests) as part of this amendment. Rationale: the
   prove-fix protocol reached FIX_IMPOSSIBLE precisely because the test
   encoded the bug as the contract; the spec is the source of truth for
   behaviour, not the test, and the test was authored as a lens-B
   "defensive" test with self-described weakness ("we assert the writer at
   minimum does not crash"). The test was a correctness-by-omission — it
   accepted whatever the writer did because the expected behaviour was
   under-specified at the time. v7 specifies the behaviour and the test
   must follow.

5. **No prerequisite stubs needed:** R10f and R10g reference R10c, R7b,
   R12, R13b, R8j, R8e, R9a-mono — all are existing APPROVED requirements
   in the spec corpus. R10g introduces a builder method by behavioural
   description, not by name (the spec mandates "an explicitly named opt-out
   method, e.g. `commitHookOmittedForTesting()`"); the implementation is
   free to choose the exact name.

6. **Adversarial passes (Pass 2 + Pass 3) run autonomously:**
   - **Boundary validation probe (Pass 2):** asked whether R10f could be
     bypassed by `--add-exports` access to the writer's package-private
     constructor. Answer: yes, but the package-private constructor is
     part of the same trust-boundary as R8h's `--add-exports` carve-out
     — production code must not use it, and the audit pipeline catches
     misuse. Recorded R10f's scoping ("Internal package-private
     constructors used only for byte-layer test harnesses are subject
     to R10g's narrow opt-out, not R10f").
   - **Cross-construct atomicity probe (Pass 2):** asked whether the
     opt-out interacts with R10b (FAILED state on RuntimeException).
     Answer: no — the opt-out is a construction-time decision; once the
     writer is constructed, R10b's failure handling is unchanged.
   - **Concurrency contract probe (Pass 2):** asked whether the sticky
     opt-out flag has thread-safety implications. Answer: no — Builder
     instances are not shared across threads (matches the wider
     non-thread-safe Builder contract in `jlsm-core`), so the sticky
     flag is a simple field with no synchronisation requirement.
   - **Trust boundary probe (Pass 3):** asked whether R10g's "production
     code must not invoke the opt-out" disclosure has the same
     enforceability problems as R8j. Answer: yes, identical — added
     the explicit cross-reference in R10g's final paragraph so the
     trust-boundary shape is consistent across R8j, R10g, and R13b.
   - **Error propagation probe (Pass 3):** asked whether R10f's
     `IllegalStateException` discloses scope identifiers in violation
     of R12. Answer: addressed in R10f's text by requiring the error
     message to identify which of the three values (scope, commitHook,
     tableNameForLock) is missing without revealing scope identifiers.

### Verified: v6 — 2026-04-25 (state: APPROVED — amendment, no new code required)

Audit relaxation work (audit run `implement-encryption-lifecycle--wd-02/audit/run-001`,
finding F-R1.dispatch_routing.3.1) closed the implicit boundary observation around
`CatalogClusteredTable.metadata()` non-finality. The prove-fix protocol attempted to
add `final` to `metadata()` and was blocked by five in-tree test sites that depend
on the override path (RELAX-1 escalation). v6 adds three new requirements (R8h, R8i,
R8j) that name the carve-out explicitly:

- **R8g (amended):** test stubs may use either the test-only factory pattern OR
  subclass-extension under R8h; both patterns are now permitted. The earlier
  "must migrate to test-only factory" language is struck through (partial
  displacement per project memory `feedback_spec_inplace_amendment`).
- **R8h (new):** trusted-export threat-model carve-out. Names the
  `--add-exports jlsm.engine/jlsm.engine.{internal,cluster.internal}=...` flag
  as the explicit boundary, equivalent in trust class to R8f bullet 4's
  reflection carve-out. Documents the residual cryptographic defence (R6b HKDF
  binding) and the audit-trail attribution gap that remains under the carve-out.
- **R8i (new):** declares `metadata()` non-final by design as a deliberate
  consequence of R8h. Constrains future code: implementations MUST NOT add
  internal logic that depends on `metadata()` being non-overridable as a
  defence; any internal need for the construction-bound metadata must read
  the private field directly.
- **R8j (new):** production deployment guidance. The library does not enforce
  the absence of the `--add-exports` flag at runtime; the trust boundary is
  the build/deploy configuration. Identical in shape to R13b's storage-substrate
  unauthenticated-write disclosure.

**Overall: APPROVED** — amendment-only; no implementation change required.
The current `CatalogClusteredTable.metadata()` implementation (line 668,
`@Override public TableMetadata metadata() { return tableMetadata; }`) and
the five test stub overrides (`TestTableStubs.MetadataOnlyStub`,
`TestTableStubs.PermissiveStub`, `TestTableStubs.RecordingTable`,
`ClusteredTableLocalShortCircuitTest.RecordingTable`, the
`ResourceLifecycleAdversarialTest` anonymous null-metadata subclass) are
all compliant with v6. The audit finding F-R1.dispatch_routing.3.1 is
resolved as ACCEPTED (carve-out documented), not FIXED.

#### Authoring log (autonomous decisions made under non-interactive subagent invocation)

This spec amendment was authored under a non-interactive subagent invocation
from the audit pipeline's spec-author dispatcher (RELAX-1). The protocol
normally prompts the user between Pass 1 and Pass 2 and during arbitration;
under the non-interactive constraint the author made the following best-judgment
decisions and recorded them here for review:

1. **Amendment vs new spec:** chose to amend `sstable.footer-encryption-scope`
   (bump v5 → v6) rather than author a separate `engine.cluster.clustered-table-construction`
   spec. Rationale: R8e/R8f/R8g already live here; fragmenting the threat-model
   carve-out across a separate spec would create cross-reference drift. Aligns
   with project memory `feedback_spec_inplace_amendment` (in-place amendment
   over spec split).

2. **Permanent non-finality vs deferred migration:** chose to declare
   `metadata()` permanently non-final (R8i) rather than deferring to a future
   migration that finalises it. Rationale: the test-only factory pattern of
   R8g(a) cannot inject deliberate-null metadata or per-call-mutating metadata
   without subclass extension or reflection — both of which are R8h-carved-out
   patterns. The "migration target" framing in v5's R8g was aspirational and
   not achievable without losing test-engineering capability that the
   audit-pipeline's adversarial tests rely on.

3. **Trust-boundary scope of R8h:** chose to scope R8h to BOTH internal
   packages (`jlsm.engine.internal` and `jlsm.engine.cluster.internal`)
   uniformly, even though the immediate finding F-R1.dispatch_routing.3.1
   touched only the cluster-internal package. Rationale: `CatalogTable` and
   `CatalogClusteredTable` are co-permits of the sealed `Table`; treating them
   asymmetrically would create a follow-up audit risk for the single-node
   path.

4. **Audit-trail attribution disclosure in R8h:** chose to spell out the
   residual gap that the cryptographic defence does NOT close (an attacker
   with `--add-exports` AND legitimate scope-X access can route scope-X bytes
   through a forged scope-Y handle, exposing only the per-table audit-trail
   attribution). Rationale: R6b's HKDF binding does NOT prevent this attack
   (the attacker has the scope-X DEK material legitimately); not disclosing
   the gap would violate the spec's threat-model honesty discipline.

5. **No prerequisite stubs needed:** R8h and R8i reference R6b, R8e, R8f,
   R10c, R11 (encryption.primitives-lifecycle), R13b — all are existing
   APPROVED requirements in the spec corpus. No new prerequisite stubs
   were introduced.

Adversarial passes (Pass 2 + Pass 3) were run autonomously by reasoning
about each new requirement against the falsification lenses in
`SKILL.md` (boundary validation, trust boundary, error propagation,
cross-construct atomicity, concurrency contract). The findings that
shaped the final wording are summarised in the "What was ruled out" entry
above for `metadata()`-final.
