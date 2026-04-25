package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.internal.KeyRegistryShard;
import jlsm.encryption.internal.ShardPathResolver;
import jlsm.encryption.internal.ShardStorage;
import jlsm.encryption.internal.TenantShardRegistry;
import jlsm.encryption.local.LocalKmsClient;

/**
 * Adversarial tests targeting contract-boundary violations in the encryption facade.
 *
 * <p>
 * Each test exercises a specific finding from the contract_boundaries lens: contract translations
 * that lose information (IOException→IllegalStateException), unvalidated SPI post-conditions, and
 * other boundary hygiene defects.
 */
class ContractBoundariesAdversarialTest {

    private static final TenantId TENANT = new TenantId("tenant-A");
    private static final DomainId DOMAIN = new DomainId("domain-1");
    private static final TableId TABLE = new TableId("table-1");
    private static final KekRef KEK_REF = new KekRef("local-master");

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static Path writeMasterKey(Path dir) throws IOException {
        final Path keyFile = dir.resolve("master.key");
        final byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            bytes[i] = (byte) (i + 7);
        }
        Files.write(keyFile, bytes);
        if (Files.getFileAttributeView(dir,
                java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(keyFile,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        return keyFile;
    }

    /**
     * Plant a corrupt shard on disk for {@code tenantId} rooted at {@code registryRoot}. The file
     * contains a CRC-32C trailer that fails verification, forcing {@link ShardStorage#loadShard} to
     * throw {@link IOException} on first access.
     */
    private static void plantCorruptShard(Path registryRoot, TenantId tenantId) throws IOException {
        final Path shardPath = ShardPathResolver.shardPath(registryRoot, tenantId);
        Files.createDirectories(shardPath.getParent());
        // Enough bytes to clear the "too short for CRC trailer" check (> 4 bytes), but the
        // payload is junk so CRC verification fails. Triggers the CRC-mismatch IOException path
        // inside ShardStorage.loadShard → propagates through TenantShardRegistry.readSnapshot.
        final byte[] garbage = new byte[]{ 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07 };
        Files.write(shardPath, garbage);
    }

    // ── F-R1.contract_boundaries.1.1 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.1.1
    // Bug: currentDek translates IOException from registry.readSnapshot into IllegalStateException,
    // collapsing "transient I/O failure" and "permanent state fault" into the same exception
    // category. Callers cannot distinguish retryable network conditions from missing-DEK state.
    // Correct behavior: preserve the IOException category via UncheckedIOException (the standard
    // Java idiom for surfacing a checked IOException through a method that does not declare
    // `throws IOException`). Callers can then catch UncheckedIOException to retry transient
    // failures and IllegalStateException to handle "closed" / "no DEK" permanent faults.
    // Fix location: EncryptionKeyHolder.currentDek — the catch (IOException e) block around the
    // registry.readSnapshot(tenantId) call.
    // Regression watch: the IllegalStateException paths that remain (closed holder, missing DEK)
    // must keep throwing IllegalStateException — only the IOException translation changes.
    @Test
    void test_currentDek_ioFailure_surfacesAsUncheckedIOException(@TempDir Path tempDir)
            throws IOException {
        final Path keyFile = writeMasterKey(tempDir);
        final Path registryRoot = tempDir.resolve("registry");
        // Pre-plant a corrupt shard file BEFORE the registry opens it so the first
        // readSnapshot lazy-load surfaces an IOException from CRC verification.
        plantCorruptShard(registryRoot, TENANT);

        try (LocalKmsClient kms = new LocalKmsClient(keyFile);
                TenantShardRegistry registry = new TenantShardRegistry(
                        new ShardStorage(registryRoot));
                EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(kms)
                        .registry(registry).activeTenantKekRef(KEK_REF).build()) {
            // Callers must be able to retry on transient I/O failures. Per io-internals.md,
            // non-idempotent or I/O-dependent operations must surface a descriptive IOException-
            // typed failure (UncheckedIOException here, since currentDek does not declare
            // `throws IOException`). Collapsing into IllegalStateException would force string-
            // matching retry logic — tested here as an anti-assertion.
            final UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
                    () -> holder.currentDek(TENANT, DOMAIN, TABLE),
                    "currentDek must surface registry IOException as UncheckedIOException, "
                            + "not IllegalStateException — callers need to distinguish I/O "
                            + "failures (retryable) from state faults (permanent)");
            assertTrue(thrown.getCause() instanceof IOException,
                    "UncheckedIOException must wrap the original IOException as its cause");
        }
    }

    // ── F-R1.contract_boundaries.1.2 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.1.2
    // Bug: resolveDek translates IOException from registry.readSnapshot into IllegalStateException,
    // mirroring the bug fixed in currentDek (F-R1.contract_boundaries.1.1). The public
    // Javadoc advertises DekNotFoundException (R57) + IllegalStateException (closed) but
    // collapses transient I/O failures into the same IllegalStateException category, forcing
    // callers to parse strings for retry decisions.
    // Correct behavior: preserve the IOException category via UncheckedIOException — the standard
    // Java idiom for surfacing a checked IOException through a method that does not declare
    // `throws IOException`. Callers catch UncheckedIOException to retry transient failures,
    // and IllegalStateException for "closed" or DekNotFoundException for "handle not in shard"
    // permanent faults.
    // Fix location: EncryptionKeyHolder.resolveDek — the catch (IOException e) block around the
    // registry.readSnapshot(tenantId) call (lines ~204-208).
    // Regression watch: the DekNotFoundException path (missing handle) and the closed-holder
    // IllegalStateException (requireOpen) must continue to fire as specified; only the
    // IOException translation changes.
    @Test
    void test_resolveDek_ioFailure_surfacesAsUncheckedIOException(@TempDir Path tempDir)
            throws IOException {
        final Path keyFile = writeMasterKey(tempDir);
        final Path registryRoot = tempDir.resolve("registry");
        plantCorruptShard(registryRoot, TENANT);

        try (LocalKmsClient kms = new LocalKmsClient(keyFile);
                TenantShardRegistry registry = new TenantShardRegistry(
                        new ShardStorage(registryRoot));
                EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(kms)
                        .registry(registry).activeTenantKekRef(KEK_REF).build()) {
            final DekVersion version = new DekVersion(1);
            final UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
                    () -> holder.resolveDek(TENANT, DOMAIN, TABLE, version),
                    "resolveDek must surface registry IOException as UncheckedIOException, "
                            + "not IllegalStateException — callers need to distinguish I/O "
                            + "failures (retryable) from state faults (permanent)");
            assertTrue(thrown.getCause() instanceof IOException,
                    "UncheckedIOException must wrap the original IOException as its cause");
        }
    }

    // ── F-R1.contract_boundaries.1.3 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.1.3
    // Bug: unwrapDek translates IOException from registry.readSnapshot into IllegalStateException,
    // mirroring the bugs fixed in currentDek (1.1) and resolveDek (1.2). unwrapDek is called
    // from deriveFieldKey — which does not declare `throws IOException` — so a transient I/O
    // failure during field-key derivation is collapsed into IllegalStateException, forcing
    // any retry wrapper to string-match on the message. Per io-internals.md, non-idempotent
    // operations must surface a descriptive IOException-typed failure so callers can
    // distinguish retryable from non-retryable failures.
    // Correct behavior: preserve the IOException category via UncheckedIOException — the standard
    // Java idiom for surfacing a checked IOException through a method that does not declare
    // `throws IOException`. Callers catch UncheckedIOException to retry transient I/O;
    // IllegalStateException remains reserved for permanent state faults (closed holder,
    // missing open domain); DekNotFoundException remains the R57 signal for a shard that
    // exists but lacks the requested handle.
    // Fix location: EncryptionKeyHolder.unwrapDek — the catch (IOException e) block around the
    // registry.readSnapshot(handle.tenantId()) call.
    // Regression watch: the DekNotFoundException path (shard loaded successfully but missing the
    // handle) must continue to fire for the "missing DEK" case; the public deriveFieldKey
    // Javadoc must be updated to mention UncheckedIOException mirroring currentDek/resolveDek.
    @Test
    void test_unwrapDek_ioFailure_surfacesAsUncheckedIOException(@TempDir Path tempDir)
            throws Exception {
        final Path keyFile = writeMasterKey(tempDir);
        final Path registryRoot = tempDir.resolve("registry");

        // Tenant-A has a valid-path shard (none planted — empty storage is fine for openDomain).
        // Tenant-B has a corrupt shard planted before any readSnapshot is attempted. The test
        // injects a domainCache entry for (TENANT_B, DOMAIN) via reflection so deriveFieldKey
        // reaches unwrapDek and triggers readSnapshot(TENANT_B) — forcing the slow-path
        // storage.loadShard that hits the CRC-mismatch IOException.
        final TenantId tenantA = TENANT;
        final TenantId tenantB = new TenantId("tenant-B-corrupt");
        plantCorruptShard(registryRoot, tenantB);

        try (LocalKmsClient kms = new LocalKmsClient(keyFile);
                TenantShardRegistry registry = new TenantShardRegistry(
                        new ShardStorage(registryRoot));
                EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(kms)
                        .registry(registry).activeTenantKekRef(KEK_REF).build();
                Arena callerArena = Arena.ofConfined()) {
            // Open domain for tenant-A to populate the domainCache with a real CachedDomainKek
            // (we cannot construct one directly — the plaintext segment must be a live cacheArena
            // segment). The entry we copy from (tenantA, DOMAIN) is reused keyed by (tenantB,
            // DOMAIN)
            // so deriveFieldKey's cache lookup succeeds and proceeds to unwrapDek.
            holder.openDomain(tenantA, DOMAIN);

            // Reflect into domainCache and inject (tenantB, DOMAIN) -> the same CachedDomainKek.
            final Field cacheField = EncryptionKeyHolder.class.getDeclaredField("domainCache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            final java.util.concurrent.ConcurrentHashMap<Object, Object> cache = (java.util.concurrent.ConcurrentHashMap<Object, Object>) cacheField
                    .get(holder);

            // Locate the existing (tenantA, DOMAIN) entry to clone its CachedDomainKek value.
            Object existingEntry = null;
            for (java.util.Map.Entry<Object, Object> e : cache.entrySet()) {
                existingEntry = e.getValue();
                break;
            }
            assertNotNull(existingEntry, "openDomain must have populated domainCache");

            // Build a new DomainKey(tenantB, DOMAIN) via the private record's canonical ctor.
            final Class<?> domainKeyClass = Class
                    .forName("jlsm.encryption.EncryptionKeyHolder$DomainKey");
            final java.lang.reflect.Constructor<?> dkCtor = domainKeyClass
                    .getDeclaredConstructor(TenantId.class, DomainId.class);
            dkCtor.setAccessible(true);
            final Object tenantBKey = dkCtor.newInstance(tenantB, DOMAIN);
            cache.put(tenantBKey, existingEntry);

            // Now derive a field key using a handle tied to tenantB. domainCache lookup hits the
            // injected entry; unwrapDek invokes registry.readSnapshot(tenantB) → loadShard →
            // CRC-mismatch IOException. Must surface as UncheckedIOException per io-internals.md.
            final DekHandle handle = new DekHandle(tenantB, DOMAIN, TABLE, new DekVersion(1));
            final UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
                    () -> holder.deriveFieldKey(handle, "tbl", "fld", 32, callerArena),
                    "unwrapDek (via deriveFieldKey) must surface registry IOException as "
                            + "UncheckedIOException, not IllegalStateException — a retry wrapper "
                            + "at the field-decryption layer needs to distinguish transient I/O "
                            + "failures (retryable) from permanent state faults");
            assertTrue(thrown.getCause() instanceof IOException,
                    "UncheckedIOException must wrap the original IOException as its cause");
        }
    }

    // ── F-R1.contract_boundaries.1.12 ────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.1.12
    // Bug: deriveFieldKey does not eagerly validate outLenBytes at the public API boundary.
    // Non-positive or oversize values are only rejected inside Hkdf.deriveKey — AFTER
    // read-lock acquisition, requireOpen, domain-cache lookup, and DEK unwrap have all
    // run. Per code-quality.md, "validate all inputs to public methods eagerly with
    // explicit exceptions ... never trust external callers." Delegating the check to an
    // internal helper means attribution is indirect and the facade wastes work (lock +
    // state checks + crypto) before reporting a caller error.
    // Correct behavior: deriveFieldKey must throw IllegalArgumentException for outLenBytes
    // that is non-positive or exceeds HKDF's 255*HashLen (8160 bytes) limit, and it
    // must do so BEFORE any state checks. A caller passing outLenBytes=-1 with no
    // domain opened must see IllegalArgumentException (bad caller input) — not
    // IllegalStateException (missing state), which would mis-attribute the failure.
    // Fix location: EncryptionKeyHolder.deriveFieldKey — add range guard immediately after
    // the null checks (lines 279-282), before deriveGuard.readLock().lock().
    // Regression watch: the existing null-arg checks must keep throwing NullPointerException;
    // the missing-domain IllegalStateException must still fire for valid outLenBytes;
    // the R62a read-lock ordering must be preserved (guards go before the lock, like
    // the null checks already do).
    @Test
    void test_deriveFieldKey_invalidOutLenBytes_throwsIllegalArgumentEagerly(@TempDir Path tempDir)
            throws IOException {
        final Path keyFile = writeMasterKey(tempDir);
        final Path registryRoot = tempDir.resolve("registry");

        try (LocalKmsClient kms = new LocalKmsClient(keyFile);
                TenantShardRegistry registry = new TenantShardRegistry(
                        new ShardStorage(registryRoot));
                EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(kms)
                        .registry(registry).activeTenantKekRef(KEK_REF).build();
                Arena callerArena = Arena.ofConfined()) {
            // No domain is opened — if outLenBytes were valid, the call would reach the
            // domain-cache check and throw IllegalStateException ("openDomain ... must be
            // called"). Eager validation of outLenBytes must fire FIRST, producing
            // IllegalArgumentException instead.
            final DekHandle handle = new DekHandle(TENANT, DOMAIN, TABLE, new DekVersion(1));

            assertThrows(IllegalArgumentException.class,
                    () -> holder.deriveFieldKey(handle, "tbl", "fld", -1, callerArena),
                    "deriveFieldKey must reject negative outLenBytes eagerly with "
                            + "IllegalArgumentException, not defer the check to Hkdf.deriveKey "
                            + "after lock + state checks");
            assertThrows(IllegalArgumentException.class,
                    () -> holder.deriveFieldKey(handle, "tbl", "fld", 0, callerArena),
                    "deriveFieldKey must reject zero outLenBytes eagerly with "
                            + "IllegalArgumentException");
            assertThrows(IllegalArgumentException.class,
                    () -> holder.deriveFieldKey(handle, "tbl", "fld", Integer.MAX_VALUE,
                            callerArena),
                    "deriveFieldKey must reject outLenBytes above HKDF's 255*HashLen limit "
                            + "eagerly with IllegalArgumentException");
        }
    }

    // ── F-R1.contract_boundaries.1.13 ────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.1.13
    // Bug: Builder.hkdfSalt only rejects length == 0 — a 1-byte (or any sub-32-byte) salt is
    // silently accepted. Per RFC 5869, HKDF salt SHOULD be at least HashLen bytes
    // (32 for SHA-256) to provide the extraction-strength and collision resistance
    // that R10/R10b spec intent depends on. A 1-byte salt provides neither, and R10's
    // own default is 32 zero bytes — so the Builder's implicit floor is 32 but the
    // guard does not enforce it. Per code-quality.md, "validate all inputs to public
    // methods eagerly with explicit exceptions ... never trust external callers."
    // Correct behavior: Builder.hkdfSalt must reject any salt shorter than 32 bytes with
    // IllegalArgumentException (RFC 5869 + R10 default). A 32-byte salt continues to
    // be accepted.
    // Fix location: EncryptionKeyHolder.Builder.hkdfSalt — the length guard at lines ~685-687.
    // Regression watch: the null-check must remain; the 32-byte-and-up accept path must
    // continue to work; existing tests that pass valid 32-byte salts must still pass.
    @Test
    void test_builderHkdfSalt_shortSaltRejectedWithMinimumLengthGuard() {
        final EncryptionKeyHolder.Builder b = EncryptionKeyHolder.builder();

        // 1-byte salt: far below HKDF-SHA256 HashLen (32). Current impl accepts; must reject.
        assertThrows(IllegalArgumentException.class, () -> b.hkdfSalt(new byte[1]),
                "hkdfSalt must reject 1-byte salt — RFC 5869 requires salt >= HashLen (32 "
                        + "bytes for SHA-256) to provide extraction strength per R10/R10b");

        // 16-byte salt: plausible-looking but still below the HashLen floor.
        assertThrows(IllegalArgumentException.class, () -> b.hkdfSalt(new byte[16]),
                "hkdfSalt must reject 16-byte salt — still below HashLen floor");

        // 31-byte salt: boundary just below the minimum. Must reject.
        assertThrows(IllegalArgumentException.class, () -> b.hkdfSalt(new byte[31]),
                "hkdfSalt must reject 31-byte salt — one byte shy of the HashLen minimum");

        // 32-byte salt: at the minimum. Must succeed (regression check).
        final byte[] validSalt = new byte[32];
        for (int i = 0; i < 32; i++) {
            validSalt[i] = (byte) (i + 1);
        }
        assertNotNull(b.hkdfSalt(validSalt), "32-byte salt must be accepted");

        // 64-byte salt: above the minimum. Must succeed (regression check).
        assertNotNull(b.hkdfSalt(new byte[64]), "salts longer than 32 bytes must be accepted");
    }

    // ── F-R1.contract_boundaries.1.14 ────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.1.14
    // Bug: Builder.cacheTtl has no upper bound. Durations on the order of
    // Duration.ofSeconds(Long.MAX_VALUE) overflow Instant.plus at the usage
    // sites (line ~529 in unwrapAndCacheDomainKek, ~568 in provisionDomainKek)
    // and surface as uncaught ArithmeticException through public methods that
    // do not declare that path. Merely-enormous-but-finite durations
    // (e.g. Duration.ofDays(365*100)) are effectively-infinite relative to the
    // kms-integration-model ADR's rotation window — the ADR's "paranoid 5-10
    // min, cost-sensitive hours" framing implies a sensible upper bound in
    // the hours-to-days range, and 24 hours is a conservative ceiling.
    // Correct behavior: Builder.cacheTtl must reject any Duration exceeding 24
    // hours with IllegalArgumentException. This bounds the rotation window
    // per the ADR and also prevents Instant-overflow ArithmeticException
    // at the usage sites. Negative, zero, and null continue to behave as
    // before. Durations up to and including 24 hours continue to be accepted.
    // Fix location: EncryptionKeyHolder.Builder.cacheTtl — add an upper-bound
    // guard after the null/zero/negative checks at lines ~711-718.
    // Regression watch: the existing null-arg and non-positive rejections must
    // remain; 30-minute default and 60-second test TTLs must continue to be
    // accepted; fix must not depend on runtime usage-site order (boundary
    // check belongs on the builder, not on Instant.plus).
    @Test
    void test_builderCacheTtl_unboundedLargeDurationRejected() {
        final EncryptionKeyHolder.Builder b = EncryptionKeyHolder.builder();

        // Pathological: Duration.ofSeconds(Long.MAX_VALUE) — overflows Instant.plus
        // at the cache-write site, surfacing as ArithmeticException through a
        // public method that does not declare that path.
        assertThrows(IllegalArgumentException.class,
                () -> b.cacheTtl(Duration.ofSeconds(Long.MAX_VALUE)),
                "cacheTtl must reject Duration.ofSeconds(Long.MAX_VALUE) eagerly "
                        + "with IllegalArgumentException — otherwise Instant.plus "
                        + "overflow leaks an ArithmeticException to callers");

        // Effectively-infinite but finite — 100 years. Defeats the rotation
        // window contemplated by kms-integration-model ADR.
        assertThrows(IllegalArgumentException.class, () -> b.cacheTtl(Duration.ofDays(365L * 100L)),
                "cacheTtl must reject a 100-year TTL — the kms-integration-model "
                        + "ADR's rotation framing implies a sensible upper bound "
                        + "in the hours-to-days range");

        // One second over the 24-hour ceiling. Must reject.
        assertThrows(IllegalArgumentException.class,
                () -> b.cacheTtl(Duration.ofHours(24).plusSeconds(1)),
                "cacheTtl must reject durations exceeding the 24-hour upper bound");

        // Exactly 24 hours — at the ceiling. Must accept (regression check).
        assertNotNull(b.cacheTtl(Duration.ofHours(24)),
                "24-hour cacheTtl must be accepted — it is the inclusive upper bound");

        // Typical production value — 30 minutes. Must accept (regression check).
        assertNotNull(b.cacheTtl(Duration.ofMinutes(30)),
                "30-minute default cacheTtl must continue to be accepted");

        // Short test-oriented TTL — 60 seconds. Must accept (regression check).
        assertNotNull(b.cacheTtl(Duration.ofSeconds(60)),
                "60-second cacheTtl must continue to be accepted");
    }

    // ── F-R1.contract_boundaries.1.10 ────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.1.10
    // Bug: close() silently swallows RuntimeException from fill() and arena.close() via
    // "catch (RuntimeException ignored)" at lines 328 and 335. R66/R69 mandate
    // zeroization of cached KEK plaintext and the silent swallow means a failed
    // zeroization (or a failed arena close that leaks off-heap memory until JVM exit)
    // is reported to no caller. Per coding-guidelines.md, close() must follow the
    // deferred-close pattern: accumulate exceptions from multiple resources and throw
    // after all resources are released; never suppress all exceptions silently.
    // Correct behavior: when fill() or arena.close() throws, close() must surface the
    // failure to the caller (as a RuntimeException, since close() does not declare
    // IOException). Other resources must still be closed (deferred close), but the
    // failure signal must not be lost.
    // Fix location: EncryptionKeyHolder.close — the two "catch (RuntimeException ignored)"
    // blocks at lines ~328 and ~335.
    // Regression watch: close() must remain idempotent (CAS guard); it must still release
    // all resources even when one throws; existing tests that successfully close the
    // holder must continue to pass.
    @Test
    void test_close_runtimeExceptionFromFillAndArenaClose_surfacesInsteadOfSilentSwallow(
            @TempDir Path tempDir) throws Exception {
        final Path keyFile = writeMasterKey(tempDir);
        final Path registryRoot = tempDir.resolve("registry");

        try (LocalKmsClient kms = new LocalKmsClient(keyFile);
                TenantShardRegistry registry = new TenantShardRegistry(
                        new ShardStorage(registryRoot))) {
            final EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(kms)
                    .registry(registry).activeTenantKekRef(KEK_REF).build();

            // Populate the domain cache so the for-loop in close() has at least one cached
            // KEK segment to call fill() on — this is the path that raises IllegalStateException
            // when the backing arena is already closed.
            holder.openDomain(TENANT, DOMAIN);

            // Reach into the private cacheArena field and close it externally. After this,
            // (a) cdk.plaintext().fill((byte) 0) will throw IllegalStateException because the
            // segment's backing arena is closed, and (b) the subsequent cacheArena.close() call
            // inside close() will also throw IllegalStateException ("already closed").
            // Both are currently silently swallowed — the fix must surface at least one.
            final Field arenaField = EncryptionKeyHolder.class.getDeclaredField("cacheArena");
            arenaField.setAccessible(true);
            final Arena cacheArena = (Arena) arenaField.get(holder);
            assertNotNull(cacheArena, "cacheArena must be accessible for the test");
            cacheArena.close();

            // The fix must surface the zeroization/arena failure — not silently swallow it.
            // Per io-internals.md and coding-guidelines.md deferred-close pattern, close()
            // should accumulate exceptions and throw after all resources are released.
            final RuntimeException thrown = assertThrows(RuntimeException.class, holder::close,
                    "close() must not silently swallow RuntimeException from fill() / "
                            + "arena.close() — R66/R69 zeroization failures must surface so "
                            + "callers know plaintext KEK bytes may remain in memory");
            assertNotNull(thrown, "close() must throw when the underlying arena is corrupted");
        }
    }

    // ── F-R1.contract_boundaries.1.5 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.1.5
    // Bug: provisionDomainKek does not validate wrap.wrappedBytes().remaining() > 0 before
    // allocating the byte[] that will be persisted into the tenant shard as the tier-2
    // WrappedDomainKek. A misbehaving/malicious KMS adapter whose wrapKek returns an
    // empty ByteBuffer produces a zero-length persisted ciphertext — un-unwrappable on
    // any subsequent unwrapAndCacheDomainKek call. The resulting partial-failure
    // footprint (WrappedDomainKek with empty wrappedBytes persisted to disk) is
    // permanent and breaks recovery for that (tenant, domain) pair silently.
    // Correct behavior: provisionDomainKek must validate
    // wrap.wrappedBytes().remaining() > 0 at the SPI boundary (R55-style, mirroring
    // the unwrapKek length guard added for F-R1.concurrency.1.6) and throw a
    // KmsPermanentException BEFORE allocating wrappedBytes, building WrappedDomainKek,
    // or calling registry.updateShard. The plaintext KEK in the confined Arena is still
    // zeroed by the surrounding try-with-resources / finally.
    // Fix location: EncryptionKeyHolder.provisionDomainKek — immediately after
    // kmsClient.wrapKek returns, before `new byte[wrap.wrappedBytes().remaining()]`.
    // Regression watch: the confined scratch Arena closes and the heap-array plaintext is
    // zeroed on the rejection path (existing try/finally must not be skipped); the
    // happy path (non-empty wrappedBytes from a real KMS) must continue unchanged;
    // registry state must NOT be mutated on rejection (no partial shard write).
    @Test
    void test_provisionDomainKek_emptyWrappedBytesRejectedAtKmsBoundary(@TempDir Path tempDir)
            throws Exception {
        final Path keyFile = writeMasterKey(tempDir);
        final Path registryDir = tempDir.resolve("registry");

        try (LocalKmsClient realKms = new LocalKmsClient(keyFile);
                EmptyWrapBytesKms badKms = new EmptyWrapBytesKms(realKms);
                TenantShardRegistry registry = new TenantShardRegistry(
                        new ShardStorage(registryDir));
                EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(badKms)
                        .registry(registry).activeTenantKekRef(KEK_REF).build()) {
            // openDomain → loadOrProvisionDomainKek → provisionDomainKek → wrapKek returns
            // empty ByteBuffer → facade must refuse at the SPI boundary, not silently persist
            // a zero-length ciphertext as the tier-2 WrappedDomainKek (un-unwrappable forever).
            assertThrows(KmsPermanentException.class, () -> holder.openDomain(TENANT, DOMAIN),
                    "provisionDomainKek must reject wrap.wrappedBytes().remaining()==0 at the "
                            + "KMS SPI boundary with KmsPermanentException — persisting a "
                            + "zero-length WrappedDomainKek produces a permanently "
                            + "un-unwrappable tier-2 KEK with no recovery path");

            // Registry must not have persisted a partial/garbage WrappedDomainKek on the
            // rejection path. A subsequent readSnapshot must reveal the (tenant, domain) pair
            // has no domain KEK entry — enabling a retry with a fixed KMS adapter.
            final var shard = registry.readSnapshot(TENANT);
            assertTrue(shard.domainKeks().isEmpty(),
                    "provisionDomainKek must not persist any WrappedDomainKek when the KMS "
                            + "returns empty wrappedBytes — otherwise recovery is blocked");
        }
    }

    // ── F-R1.contract_boundaries.2.1 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.2.1
    // Bug: LocalKmsClient.wrapKek invokes AesKeyWrap.wrap but does not catch the
    // IllegalStateException that AesKeyWrap.wrap can raise on any internal failure
    // (e.g., a JCE GeneralSecurityException mapped at AesKeyWrap.java:74-75, or a
    // MemorySegment access failure such as "Already closed" when the caller's
    // plaintext segment's arena is closed before the wrap runs). The KmsClient SPI
    // declares `throws KmsException` and callers per R76a match on the sealed
    // KmsException hierarchy to classify permanent vs transient failures. An
    // unchecked IllegalStateException escapes that contract entirely.
    // Correct behavior: LocalKmsClient.wrapKek must catch IllegalStateException from
    // AesKeyWrap.wrap and surface it as KmsPermanentException (a non-retryable
    // failure — the caller's inputs or the wrap subsystem are in a terminal state
    // that retrying will not recover). The original cause must be preserved.
    // Fix location: LocalKmsClient.java:125 — wrap the call to AesKeyWrap.wrap in a
    // try/catch and translate IllegalStateException into KmsPermanentException.
    // Regression watch: the IllegalArgumentException path from AesKeyWrap.wrap (zero-length
    // plaintext, invalid KEK size) is a separate finding (2.2) — the fix for 2.1 must
    // not suppress the IllegalArgumentException path. The null-argument guards and
    // requireOpen() must continue to throw their declared exception types.
    @Test
    void test_wrapKek_illegalStateFromAesKeyWrap_surfacesAsKmsException(@TempDir Path tempDir)
            throws IOException {
        final Path keyFile = writeMasterKey(tempDir);

        try (LocalKmsClient kms = new LocalKmsClient(keyFile)) {
            // Force AesKeyWrap.wrap to throw IllegalStateException by passing a plaintext
            // segment whose backing arena has already been closed. MemorySegment.copy on
            // a closed-arena segment raises IllegalStateException("Already closed") from
            // inside the try block that only catches GeneralSecurityException — so the
            // ISE escapes AesKeyWrap.wrap uncaught. That is the exact leak this finding
            // describes (same class as the JCE-failure leak at AesKeyWrap.java:74-75).
            final Arena victimArena = Arena.ofConfined();
            final MemorySegment plaintextKek = victimArena.allocate(32);
            for (int i = 0; i < 32; i++) {
                plaintextKek.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, (byte) (i + 1));
            }
            victimArena.close();

            final EncryptionContext ctx = EncryptionContext.forDomainKek(TENANT, DOMAIN);

            // Must surface as KmsException (sealed hierarchy: permanent or transient) so
            // callers matching on the SPI contract can classify. An IllegalStateException
            // escape bypasses the R76a partition entirely.
            assertThrows(KmsException.class, () -> kms.wrapKek(plaintextKek, KEK_REF, ctx),
                    "wrapKek must translate IllegalStateException from AesKeyWrap.wrap "
                            + "into KmsException — the SPI signature declares `throws "
                            + "KmsException` and callers per R76a must be able to "
                            + "classify permanent vs transient failures. Leaking an "
                            + "unchecked IllegalStateException breaks the failure "
                            + "partition required by R76/R76a.");
        }
    }

    // ── F-R1.contract_boundaries.2.2 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.2.2
    // Bug: LocalKmsClient.wrapKek does not catch IllegalArgumentException from
    // AesKeyWrap.wrap, which is thrown when plaintextKek.byteSize() == 0
    // (AesKeyWrap.java:53-55, "plaintext must not be zero-length (R58)"). A valid
    // zero-length MemorySegment reference satisfies the Objects.requireNonNull
    // guard at LocalKmsClient.java:121 but produces a runtime IllegalArgumentException
    // outside the declared `throws KmsException` contract. The existing try/catch at
    // lines 126-134 only covers IllegalStateException — IllegalArgumentException
    // escapes unchecked.
    // Correct behavior: LocalKmsClient.wrapKek must translate IllegalArgumentException
    // from AesKeyWrap.wrap (zero-length plaintext, or the >Integer.MAX_VALUE guard)
    // into KmsPermanentException. Caller-controlled bad input is a permanent,
    // non-retryable failure in the R76a partition.
    // Fix location: LocalKmsClient.java:125-134 — extend the existing try/catch to also
    // catch IllegalArgumentException (multi-catch or a second catch block) and
    // surface as KmsPermanentException preserving the original cause.
    // Regression watch: the NullPointerException path from Objects.requireNonNull must
    // continue to throw NPE (pre-boundary null guards are separate from the wrap
    // subsystem contract). The IllegalStateException path from finding 2.1 must
    // still be translated. The happy path with a valid plaintext must remain
    // unchanged. kekRef and context null checks must still throw NPE.
    @Test
    void test_wrapKek_zeroLengthPlaintext_surfacesAsKmsPermanentException(@TempDir Path tempDir)
            throws IOException {
        final Path keyFile = writeMasterKey(tempDir);

        try (LocalKmsClient kms = new LocalKmsClient(keyFile); Arena arena = Arena.ofConfined()) {
            // A zero-byteSize MemorySegment is a caller-controlled adversarial input that
            // passes the null check but fails the R58 zero-length guard inside
            // AesKeyWrap.wrap. Allocate a segment and take a zero-length slice.
            final MemorySegment zeroLenPlaintext = arena.allocate(0);

            final EncryptionContext ctx = EncryptionContext.forDomainKek(TENANT, DOMAIN);

            // Must surface as KmsPermanentException (sealed KmsException subtype) so
            // R76a-conformant callers can classify this as a non-retryable failure.
            // An unchecked IllegalArgumentException escape bypasses the failure
            // partition entirely — same class of defect as 2.1, triggered by a
            // caller-controlled argument that the SPI should treat as invalid input.
            assertThrows(KmsPermanentException.class,
                    () -> kms.wrapKek(zeroLenPlaintext, KEK_REF, ctx),
                    "wrapKek must translate IllegalArgumentException from AesKeyWrap.wrap "
                            + "(zero-length plaintext) into KmsPermanentException — the "
                            + "SPI signature declares `throws KmsException` and R76a "
                            + "requires caller-facing failures to be classified as "
                            + "permanent or transient. Leaking IllegalArgumentException "
                            + "breaks the failure partition.");
        }
    }

    // ── F-R1.contract_boundaries.2.3 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.2.3
    // Bug: LocalKmsClient.unwrapKek catches only IllegalArgumentException from
    // AesKeyWrap.unwrap (line 167). AesKeyWrap.unwrap can also raise
    // IllegalStateException — explicitly at line 123 ("unwrap produced a key with
    // no encoded form"), and implicitly via any MemorySegment access on the
    // masterKeySegment when its backing arena has been closed ("Already closed").
    // The KmsClient SPI declares `throws KmsException`, and R76a requires callers
    // to classify permanent vs transient failures on the sealed KmsException
    // hierarchy. Leaking an unchecked IllegalStateException breaks that partition,
    // mirroring the wrapKek leaks fixed in 2.1/2.2.
    // Correct behavior: unwrapKek must translate IllegalStateException from
    // AesKeyWrap.unwrap into KmsPermanentException (non-retryable — the wrap
    // subsystem or KEK-segment lifetime is in a terminal state). The original
    // cause must be preserved.
    // Fix location: LocalKmsClient.java:167 — extend the existing single-catch on
    // IllegalArgumentException to a multi-catch (IllegalArgumentException |
    // IllegalStateException) as already done in wrapKek (line 128).
    // Regression watch: the IllegalArgumentException path (malformed ciphertext, length
    // mismatch) must still translate to KmsPermanentException. The existing
    // caller-arena close-on-failure finally must continue to run. The
    // too-short-wrappedBytes guard (length <= 8) must still pre-empt before
    // reaching AesKeyWrap.unwrap. requireOpen() must continue to throw
    // IllegalStateException (that is the documented closed-client signal, not a
    // wrap-subsystem failure — the fix must not over-catch).
    @Test
    void test_unwrapKek_illegalStateFromAesKeyWrap_surfacesAsKmsException(@TempDir Path tempDir)
            throws Exception {
        final Path keyFile = writeMasterKey(tempDir);

        try (LocalKmsClient kms = new LocalKmsClient(keyFile)) {
            // Force AesKeyWrap.unwrap to throw IllegalStateException by closing the
            // masterArena OUT-OF-BAND via reflection. The client's `closed` AtomicBoolean
            // stays false so requireOpen() passes, but the first MemorySegment access on
            // masterKeySegment inside AesKeyWrap.unwrap (MemorySegment.copy on the kek
            // argument) raises IllegalStateException("Already closed"). That ISE escapes
            // AesKeyWrap.unwrap uncaught — same class of leak as finding 2.1, but on the
            // unwrap path.
            final Field arenaField = LocalKmsClient.class.getDeclaredField("masterArena");
            arenaField.setAccessible(true);
            final Arena masterArena = (Arena) arenaField.get(kms);
            masterArena.close();

            // Valid-length wrapped payload (>8 bytes) so the too-short guard at
            // LocalKmsClient.java:152 does not pre-empt the AesKeyWrap.unwrap call.
            // The content is irrelevant — the failure fires before any integrity check.
            final ByteBuffer wrapped = ByteBuffer.wrap(new byte[40]);
            final EncryptionContext ctx = EncryptionContext.forDomainKek(TENANT, DOMAIN);

            // Must surface as KmsException (sealed: permanent or transient). Leaking
            // IllegalStateException bypasses the R76a partition that callers depend on.
            assertThrows(KmsException.class, () -> kms.unwrapKek(wrapped, KEK_REF, ctx),
                    "unwrapKek must translate IllegalStateException from AesKeyWrap.unwrap "
                            + "into KmsException — the SPI signature declares `throws "
                            + "KmsException` and callers per R76a must be able to classify "
                            + "permanent vs transient failures. Leaking an unchecked "
                            + "IllegalStateException breaks the failure partition, same "
                            + "class of defect as 2.1/2.2 on wrapKek.");
        } catch (RuntimeException ignoredOnClose) {
            // try-with-resources on a LocalKmsClient whose masterArena was externally
            // closed may surface "Already closed" from the client's own close() — that
            // is an artefact of the test's adversarial setup, not a product defect.
        }
    }

    // ── F-R1.contract_boundaries.2.6 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.2.6
    // Bug: WrapResult stores the caller-supplied ByteBuffer directly. Two consumers of the
    // same WrapResult observe order-dependent results: the first consumer that drains
    // the buffer (e.g., via get(byte[])) advances position to limit, and the second
    // consumer sees an "empty" buffer. Equally, a caller that mutates position/limit
    // between accesses can make the record's logical content change without the record
    // itself changing — breaking the value-semantics contract implied by records
    // (equals/hashCode rely on component state, and WrapResult is documented to carry
    // "opaque wrapped key material"). Per coding-guidelines.md "Prefer Records", a
    // record should be a pure value holder — ByteBuffer's consumable cursor state
    // makes WrapResult stateful in a way that two holders of identical wrapped bytes
    // are not equal if their positions differ.
    // Correct behavior: WrapResult must defensively isolate the buffer so that (a) the
    // record does not observe caller-side mutations to the original buffer's
    // position/limit after construction, and (b) consumers reading wrappedBytes()
    // cannot cause each other to observe empty / different content through shared
    // cursor state. The canonical Java idiom is asReadOnlyBuffer() (which also gives
    // each accessor its own independent position/limit view when combined with
    // duplicate-on-construction), or a defensive slice+asReadOnlyBuffer captured at
    // construction time. A subsequent full drain by consumer A must NOT starve
    // consumer B of bytes.
    // Fix location: WrapResult.java:17-26 — compact ctor must defensively copy-or-slice
    // the incoming ByteBuffer (e.g., .duplicate().asReadOnlyBuffer() or copy to a
    // new backing array) so that wrappedBytes() always returns a buffer with a
    // consistent, caller-independent view of the wrapped content.
    // Regression watch: null-check must remain (WrapResultTest covers this). The
    // constructor accessor round-trip test in WrapResultTest asserts equality of
    // the original buffer and wrappedBytes() — that test must be re-examined, but
    // ByteBuffer.equals() compares remaining content, so a defensively-duplicated
    // buffer of identical content will still equal the original (as long as positions
    // align). kekRef handling is unchanged.
    @Test
    void test_wrapResult_sharedBufferConsumption_doesNotStarveSecondReader() {
        final byte[] payload = new byte[]{ 10, 20, 30, 40, 50 };
        final ByteBuffer original = ByteBuffer.wrap(payload);
        final WrapResult result = new WrapResult(original, new KekRef("k"));

        // Consumer A drains the buffer (typical ByteBuffer consumer pattern — e.g., a
        // bulk get into a heap byte[] for persistence).
        final ByteBuffer viewA = result.wrappedBytes();
        final byte[] drainedA = new byte[viewA.remaining()];
        viewA.get(drainedA);

        // Consumer B asks the record for the wrapped bytes and expects to see the same
        // payload A saw. With a mutable shared ByteBuffer, B sees remaining()==0 —
        // i.e., the record's content has silently been "consumed" by A's read.
        final ByteBuffer viewB = result.wrappedBytes();
        assertTrue(viewB.remaining() == payload.length,
                "WrapResult.wrappedBytes() must not be starved by a prior consumer's "
                        + "drain — records are value-like and two consumers reading the "
                        + "wrapped bytes must observe the same content independently. "
                        + "Mutable shared-cursor state violates the record value-semantics "
                        + "contract (F-R1.contract_boundaries.2.6).");
        final byte[] drainedB = new byte[viewB.remaining()];
        viewB.get(drainedB);
        for (int i = 0; i < payload.length; i++) {
            assertTrue(drainedA[i] == payload[i], "Consumer A must see the original payload bytes");
            assertTrue(drainedB[i] == payload[i],
                    "Consumer B must see the original payload bytes — not a post-drain "
                            + "empty view produced by A's consumption");
        }

        // Additionally: external mutation of the original buffer's position/limit after
        // construction must not change what the record reports. A defensive duplicate
        // captured at construction isolates the record's view from caller-side cursor
        // edits.
        original.position(original.limit());
        final ByteBuffer viewC = result.wrappedBytes();
        assertTrue(viewC.remaining() == payload.length,
                "WrapResult.wrappedBytes() must not reflect caller-side mutations to the "
                        + "original ByteBuffer's position after construction — a record's "
                        + "components should behave as stable values.");
    }

    // ── F-R1.contract_boundaries.2.7 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.2.7
    // Bug: LocalKmsClient.wrapKek and LocalKmsClient.unwrapKek call requireOpen() BEFORE
    // the try/catch blocks that translate unchecked exceptions into KmsException.
    // requireOpen() throws IllegalStateException("LocalKmsClient is closed") when
    // close() has been invoked. Because the call site is outside the try/catch, the
    // unchecked IllegalStateException escapes the wrapKek/unwrapKek signatures that
    // declare `throws KmsException`. A closed KMS client is a permanent, non-retryable
    // failure and must surface as KmsPermanentException per R76/R76a — callers
    // classifying failures by the sealed hierarchy cannot catch IllegalStateException.
    // Correct behavior: after-close calls to wrapKek or unwrapKek must throw
    // KmsPermanentException so the failure routes through the SPI's declared sealed
    // partition. The original IllegalStateException may be preserved as the cause.
    // Fix location: LocalKmsClient.java:124 (wrapKek requireOpen call) and LocalKmsClient.java:147
    // (unwrapKek requireOpen call), or inside requireOpen itself (preferable — one fix
    // point covers both callers). Either rethrow as KmsPermanentException or move the
    // requireOpen call inside the try/catch and extend catch to convert.
    // Regression watch: null-argument guards must still throw NullPointerException (they run
    // BEFORE requireOpen in both methods, and NPE is the documented pre-boundary
    // failure — must not be over-caught). The close() itself must remain idempotent.
    // Subsequent isUsable(kekRef) on a closed client must still return false (separate
    // signal, not the SPI failure-partition). The happy-path (open client) tests and
    // the IllegalStateException-from-AesKeyWrap translation (2.1/2.3) must remain green.
    @Test
    void test_wrapKek_afterClose_surfacesAsKmsPermanentException(@TempDir Path tempDir)
            throws IOException {
        final Path keyFile = writeMasterKey(tempDir);
        final LocalKmsClient kms = new LocalKmsClient(keyFile);
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment plaintextKek = arena.allocate(32);
            for (int i = 0; i < 32; i++) {
                plaintextKek.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, (byte) (i + 1));
            }
            final EncryptionContext ctx = EncryptionContext.forDomainKek(TENANT, DOMAIN);

            kms.close();

            assertThrows(KmsPermanentException.class, () -> kms.wrapKek(plaintextKek, KEK_REF, ctx),
                    "wrapKek on a closed LocalKmsClient must surface as KmsPermanentException "
                            + "— a closed client is a permanent, non-retryable failure per "
                            + "R76/R76a, and the SPI signature declares `throws KmsException`. "
                            + "Leaking IllegalStateException from requireOpen() bypasses the "
                            + "sealed failure partition.");
        }
    }

    // Finding: F-R1.contract_boundaries.2.7 (unwrap path)
    // Bug/Correct behavior/Fix location/Regression watch: see wrapKek test above.
    @Test
    void test_unwrapKek_afterClose_surfacesAsKmsPermanentException(@TempDir Path tempDir)
            throws IOException {
        final Path keyFile = writeMasterKey(tempDir);
        final LocalKmsClient kms = new LocalKmsClient(keyFile);
        final ByteBuffer wrapped = ByteBuffer.wrap(new byte[40]);
        final EncryptionContext ctx = EncryptionContext.forDomainKek(TENANT, DOMAIN);

        kms.close();

        assertThrows(KmsPermanentException.class, () -> kms.unwrapKek(wrapped, KEK_REF, ctx),
                "unwrapKek on a closed LocalKmsClient must surface as KmsPermanentException "
                        + "— a closed client is a permanent, non-retryable failure per "
                        + "R76/R76a, and the SPI signature declares `throws KmsException`. "
                        + "Leaking IllegalStateException from requireOpen() bypasses the "
                        + "sealed failure partition.");
    }

    // ── F-R1.contract_boundaries.3.1 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.3.1
    // Bug: ShardStorage.deserialize reads a 4-byte numDomainKeks (line ~569) and numDeks
    // (line ~581) then constructs `new HashMap<>(count)` directly. The existing guards
    // only reject negative counts — Integer.MAX_VALUE is permitted, and HashMap rounds
    // the requested capacity up to a power of two (2^30 internal array) triggering a
    // multi-gigabyte allocation that exhausts the heap. OutOfMemoryError is an Error,
    // not caught by the `catch (BufferUnderflowException | IllegalArgumentException
    // | NullPointerException)` block at line ~600, so it propagates out of deserialize,
    // out of loadShard, and up through TenantShardRegistry.entry's computeIfAbsent
    // lambda — leaving the registry in an inconsistent state (tenants map may have a
    // half-built entry, and the thread tears down).
    // Correct behavior: numDomainKeks and numDeks must be bounded against the remaining
    // bytes in the buffer. A domain KEK entry requires at least MIN_DOMAIN_KEK_BYTES
    // (4B domainId length + 0 bytes + 4B version + 4B wrapped length + 0 bytes +
    // 4B ref length + 0 bytes = 16 bytes); a DEK entry requires at least MIN_DEK_BYTES
    // (4B tenantId + 4B domainId + 4B tableId + 4B version + 4B wrapped length +
    // 4B domainKekVersion + 4B ref length + 8B createdAt = 36 bytes). Any count
    // exceeding `buf.remaining() / MIN_ENTRY_BYTES` cannot possibly be honored and
    // must be rejected with IOException BEFORE HashMap allocation. This bounds reader-
    // side allocation to what the file actually carries — the existing
    // requireRemaining-per-entry check is too late (allocation happens first).
    // Fix location: ShardStorage.deserialize — immediately after the `numDomainKeks < 0`
    // check (line ~570) and the `numDeks < 0` check (line ~582), add an upper-bound
    // check against `buf.remaining() / MIN_ENTRY_BYTES`.
    // Regression watch: legitimate shard files (tens to thousands of entries) must still
    // load; the CRC-verify and magic/version checks must still fire first; the happy
    // path with an actual valid serialized shard (round-trip) must remain unchanged.
    @Test
    void test_deserialize_attackerControlledCount_rejectsBeforeOomAmplification(
            @TempDir Path tempDir) throws IOException {
        // Craft a shard file: valid MAGIC + version + tenantId + salt + null-sentinel ref,
        // numDomainKeks = Integer.MAX_VALUE, AND ONE complete valid domain-KEK entry so
        // the deserialize loop succeeds on iteration 0 and calls dKeks.put(..). HashMap
        // does not allocate its backing table in the constructor (modern Java) — it
        // allocates on the first put, sizing the Node[] to tableSizeFor(Integer.MAX_VALUE)
        // = 1<<30 slots = 4-8 GB. That first-put request exceeds the test JVM heap and
        // throws OutOfMemoryError — which is an Error, not caught by deserialize's
        // `catch (BufferUnderflowException | IllegalArgumentException | NullPointerException)`
        // and not caught by loadShard's `throws IOException` contract. The fix must
        // reject numDomainKeks BEFORE any HashMap.put can run.
        final TenantId oomTenant = new TenantId("oom-attack-A");
        // Build one minimal domain-KEK entry: domainId("d"), version=0, wrapped=[] (empty),
        // tenantKekRef("r"). Minimum bytes = 4+1 + 4 + 4+0 + 4+1 = 18 bytes.
        final ByteBuffer payload = ByteBuffer.allocate(128).order(ByteOrder.BIG_ENDIAN);
        payload.put(new byte[]{ 'K', 'R', 'S', 'H' });
        payload.putShort((short) 1); // FORMAT_VERSION
        final byte[] tenantBytes = oomTenant.value().getBytes(StandardCharsets.UTF_8);
        payload.putInt(tenantBytes.length);
        payload.put(tenantBytes);
        payload.putInt(0); // hkdfSalt length (empty — adversarial-minimal)
        payload.putInt(-1); // NULL_SENTINEL for activeTenantKekRef
        payload.putInt(Integer.MAX_VALUE); // ATTACK: attacker-controlled numDomainKeks
        // One valid entry so the loop reaches dKeks.put (triggering HashMap backing-table
        // allocation sized by the constructor's capacity argument). WrappedDomainKek
        // requires version > 0 — any lower value is rejected by the compact ctor with
        // IllegalArgumentException (caught by deserialize's outer try/catch and wrapped
        // as IOException, short-circuiting the attack). We supply version=1.
        final byte[] domainIdBytes = "d".getBytes(StandardCharsets.UTF_8);
        payload.putInt(domainIdBytes.length);
        payload.put(domainIdBytes);
        payload.putInt(1); // version (must be positive per WrappedDomainKek compact ctor)
        payload.putInt(0); // wrappedBytes length (empty array is accepted)
        final byte[] refBytes = "r".getBytes(StandardCharsets.UTF_8);
        payload.putInt(refBytes.length);
        payload.put(refBytes);
        payload.flip();
        final byte[] payloadBytes = new byte[payload.remaining()];
        payload.get(payloadBytes);

        // Append a VALID CRC-32C over the payload so the CRC guard passes and execution
        // reaches the vulnerable `new HashMap<>(numDomainKeks)` line. Without a valid CRC,
        // the IOException fires earlier (unrelated to this finding) and masks the bug.
        final CRC32C crc = new CRC32C();
        crc.update(payloadBytes, 0, payloadBytes.length);
        final int crcValue = (int) crc.getValue();
        final byte[] shardBytes = new byte[payloadBytes.length + 4];
        System.arraycopy(payloadBytes, 0, shardBytes, 0, payloadBytes.length);
        ByteBuffer.wrap(shardBytes, payloadBytes.length, 4).order(ByteOrder.BIG_ENDIAN)
                .putInt(crcValue);

        final Path registryRoot = tempDir.resolve("registry");
        final Path shardPath = ShardPathResolver.shardPath(registryRoot, oomTenant);
        Files.createDirectories(shardPath.getParent());
        Files.write(shardPath, shardBytes);

        final ShardStorage storage = new ShardStorage(registryRoot);

        // The fix must reject the absurd count with IOException (malformed shard) BEFORE
        // attempting HashMap allocation. The current code DOES eventually throw IOException
        // (the loop's second iteration hits requireRemaining underflow) — but only AFTER
        // the first iteration triggers a HashMap.put() which forces the JVM to allocate a
        // Node[1<<30] backing array (~4-8 GiB). On test JVMs with small heap this
        // surfaces as OutOfMemoryError (escaping the declared `throws IOException`
        // contract); on larger heaps it transiently commits multi-GB of heap for a
        // 4-byte adversarial input — a trivial-to-exploit DOS amplification.
        //
        // The assertion: the call must complete in <2 seconds. A 4-8 GB allocation +
        // GC pressure under adversarial input takes orders of magnitude longer than
        // the early-reject path (sub-millisecond). Using assertTimeoutPreemptively also
        // protects the test JVM from an OOM-induced hang during the measurement window.
        final IOException thrown = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertThrows(IOException.class, () -> storage.loadShard(oomTenant),
                        "deserialize must reject a numDomainKeks that cannot be honored "
                                + "by the remaining bytes — HashMap allocation amplifies "
                                + "a 4-byte attacker write into a multi-GB heap request."),
                "deserialize must not spend seconds allocating a multi-GB HashMap "
                        + "backing array before rejecting a malformed shard — an eager "
                        + "bound on numDomainKeks (against buf.remaining() / "
                        + "MIN_DOMAIN_KEK_BYTES) is required");

        assertNotNull(thrown.getMessage(),
                "IOException must carry a diagnostic message — operators reading the log "
                        + "need to distinguish a corrupted shard from an adversarial write");
    }

    // ── F-R1.contract_boundaries.3.2 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.3.2
    // Bug: ShardStorage.serialize iterates shard.domainKeks().entrySet() and
    // shard.deks().entrySet() directly. The underlying Map is built via Map.copyOf
    // (ImmutableCollections.MapN) whose iteration order is hash-bucket-probe order —
    // NOT insertion order, NOT key-sorted order. Two semantically-equal shards can
    // produce different byte orderings (cross-JVM the SALT32L differs; within a JVM
    // the order is unsorted and therefore not reproducible against any external
    // canonical representation). This breaks byte-diff tooling, breaks any content-
    // addressed shard hash, and produces different CRC-32Cs for logically-identical
    // shards — operators reading "CRC changed" see that as corruption.
    // Correct behavior: serialize must emit domain-KEK and DEK entries in a canonical,
    // reproducible order. The natural canonical order is lexicographic-by-key:
    // DomainId.value() (String) for domain KEKs, and for DEKs a tuple order of
    // (tenantId.value(), domainId.value(), tableId.value(), version.value()) — i.e.,
    // the lexicographic order of the handle fields. Sorting the entrySet() stream
    // before writing makes the on-wire order a pure function of the shard's logical
    // content — identical shards produce byte-identical serialization and identical
    // CRCs.
    // Fix location: ShardStorage.serialize (lines 459-480) — wrap both map iterations with
    // a sorted stream (entries.stream().sorted(Comparator.comparing(...)).forEach(...))
    // or copy entries to a List and Collections.sort with an explicit Comparator.
    // Regression watch: the deserialize round-trip must still succeed (order is irrelevant
    // to correctness on the read side — the receiver rebuilds a Map); CRC-appending
    // still works over the sorted payload; estimateSize result is unaffected (only
    // ordering changes, not byte count); existing round-trip tests must remain green.
    @Test
    void test_serialize_sameLogicalShard_producesCanonicalLexicographicOrder(@TempDir Path tempDir)
            throws IOException {
        // Construct a shard with multiple domain KEKs whose keys (DomainId.value()) when
        // inserted in natural order do NOT hash into the same MapN probe order. Using a
        // deliberately "unsorted-looking" insertion order (e.g., "z", "a", "m") exposes the
        // bug: current code emits entries in MapN.entrySet() probe order — which is neither
        // insertion order nor lexicographic order. The assertion checks the on-disk bytes
        // are in lexicographic-by-key order, which is the canonical reproducible form
        // mandated by R19b's byte-for-byte fidelity intent.
        final TenantId tenant = new TenantId("canonical-order-tenant");
        final KekRef ref = new KekRef("ref");
        final byte[] salt = new byte[32];
        for (int i = 0; i < 32; i++) {
            salt[i] = (byte) (i + 1);
        }

        // Three domain KEKs keyed by deliberately unsorted names.
        final java.util.Map<DomainId, WrappedDomainKek> domainKeks = new java.util.HashMap<>();
        final String[] domainNames = { "zeta", "alpha", "mike" };
        for (String name : domainNames) {
            final DomainId did = new DomainId(name);
            domainKeks.put(did, new WrappedDomainKek(did, 1,
                    ("wrap-" + name).getBytes(StandardCharsets.UTF_8), ref));
        }

        // Three DEKs keyed by deliberately unsorted handle tuples (distinct table IDs).
        final java.util.Map<DekHandle, WrappedDek> deks = new java.util.HashMap<>();
        final String[] tableNames = { "zulu", "alpha", "mike" };
        for (String tbl : tableNames) {
            final DekHandle h = new DekHandle(tenant, new DomainId("d"), new TableId(tbl),
                    new DekVersion(1));
            deks.put(h, new WrappedDek(h, ("dek-" + tbl).getBytes(StandardCharsets.UTF_8), 1, ref,
                    java.time.Instant.EPOCH));
        }

        final KeyRegistryShard shard = new KeyRegistryShard(tenant, deks, domainKeks, ref, salt);

        final Path registryRoot = tempDir.resolve("registry");
        final ShardStorage storage = new ShardStorage(registryRoot);
        storage.writeShard(tenant, shard);

        final Path shardPath = ShardPathResolver.shardPath(registryRoot, tenant);
        final byte[] bytes = Files.readAllBytes(shardPath);

        // Parse the serialized payload to extract domain-KEK and DEK key order.
        final ByteBuffer buf = ByteBuffer.wrap(bytes, 0, bytes.length - 4)
                .order(ByteOrder.BIG_ENDIAN);
        // magic (4) + version (2)
        buf.position(buf.position() + 4 + 2);
        // tenantId lp-string
        final int tidLen = buf.getInt();
        buf.position(buf.position() + tidLen);
        // salt lp-bytes
        final int saltLen = buf.getInt();
        buf.position(buf.position() + saltLen);
        // activeTenantKekRef — non-null path writes a length-prefixed string (ref.value="ref").
        final int refLen = buf.getInt();
        buf.position(buf.position() + refLen);

        // numDomainKeks then entries — record the on-wire order of DomainId.value() strings.
        final int numDomainKeks = buf.getInt();
        final java.util.List<String> observedDomainOrder = new java.util.ArrayList<>();
        for (int i = 0; i < numDomainKeks; i++) {
            final int dLen = buf.getInt();
            final byte[] d = new byte[dLen];
            buf.get(d);
            observedDomainOrder.add(new String(d, StandardCharsets.UTF_8));
            buf.getInt(); // version
            final int wLen = buf.getInt();
            buf.position(buf.position() + wLen);
            final int rLen = buf.getInt();
            buf.position(buf.position() + rLen);
        }

        // numDeks then entries — record the on-wire order of the DEK tuple key (as a
        // canonical string "tid|did|tbl|ver").
        final int numDeks = buf.getInt();
        final java.util.List<String> observedDekOrder = new java.util.ArrayList<>();
        for (int i = 0; i < numDeks; i++) {
            final int tidELen = buf.getInt();
            final byte[] tidE = new byte[tidELen];
            buf.get(tidE);
            final int didELen = buf.getInt();
            final byte[] didE = new byte[didELen];
            buf.get(didE);
            final int tblELen = buf.getInt();
            final byte[] tblE = new byte[tblELen];
            buf.get(tblE);
            final int ver = buf.getInt();
            observedDekOrder.add(new String(tidE, StandardCharsets.UTF_8) + "|"
                    + new String(didE, StandardCharsets.UTF_8) + "|"
                    + new String(tblE, StandardCharsets.UTF_8) + "|" + ver);
            final int wLen = buf.getInt();
            buf.position(buf.position() + wLen);
            buf.getInt(); // domainKekVersion
            final int refELen = buf.getInt();
            buf.position(buf.position() + refELen);
            // createdAt: 8B epochSeconds + 4B nanosOfSecond (updated by audit F-R1.dt.1.01 —
            // widening from 8B epochMilli to 12B lossless Instant encoding).
            buf.getLong(); // createdAt epochSeconds
            buf.getInt(); // createdAt nanosOfSecond
        }

        // Expected canonical order: sorted ascending by natural String order.
        final java.util.List<String> expectedDomainOrder = new java.util.ArrayList<>(
                observedDomainOrder);
        java.util.Collections.sort(expectedDomainOrder);
        assertTrue(observedDomainOrder.equals(expectedDomainOrder),
                "serialize must emit domain-KEK entries in canonical lexicographic order "
                        + "by DomainId.value() — observed " + observedDomainOrder + ", expected "
                        + expectedDomainOrder
                        + ". Map.copyOf iteration order is hash-bucket probe order; "
                        + "relying on it produces non-reproducible byte output and breaks "
                        + "byte-for-byte shard fidelity (R19b) and any content-addressed "
                        + "shard hash.");

        final java.util.List<String> expectedDekOrder = new java.util.ArrayList<>(observedDekOrder);
        java.util.Collections.sort(expectedDekOrder);
        assertTrue(observedDekOrder.equals(expectedDekOrder),
                "serialize must emit DEK entries in canonical lexicographic order by "
                        + "DekHandle tuple (tenantId, domainId, tableId, version) — observed "
                        + observedDekOrder + ", expected " + expectedDekOrder
                        + ". Non-deterministic order breaks CRC-matching on logically-"
                        + "identical shards.");
    }

    // ── F-R1.contract_boundaries.3.3 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.3.3
    // Bug: ShardStorage.estimateSize accumulates byte-budget in an `int` local. When a shard
    // carries enough DEKs (or a sufficiently-large wrappedBytes payload) to push the sum
    // past Integer.MAX_VALUE, the accumulator wraps to a negative value. serialize then
    // calls `ByteBuffer.allocate(negative)` which throws `IllegalArgumentException` —
    // an unchecked exception that escapes the declared `throws IOException` contract on
    // writeShard. Callers such as TenantShardRegistry.updateShard catch IOException only;
    // an IAE leak bypasses their error-handling path entirely.
    // Correct behavior: estimateSize must use a `long` accumulator and writeShard/serialize
    // must reject any shard whose serialized size would exceed Integer.MAX_VALUE with an
    // IOException (malformed or over-size shard) rather than an IAE. The declared
    // `throws IOException` contract on writeShard must be honored for every overflow
    // condition, not just I/O errors.
    // Fix location: ShardStorage.estimateSize (int accumulator → long) and ShardStorage.serialize
    // (check size ≤ Integer.MAX_VALUE - 16 before ByteBuffer.allocate; if not, throw
    // IOException from serialize, propagating naturally through writeShard).
    // Regression watch: legitimate shards (tens of thousands of entries, KB-scale wrappedBytes)
    // must continue to serialize/deserialize correctly; the round-trip test must remain
    // green; the estimate+16 slack must still bound the allocation correctly for
    // non-overflow cases.
    @Test
    void test_writeShard_estimateSizeOverflow_throwsIOExceptionNotIAE(@TempDir Path tempDir)
            throws Exception {
        // Trigger the `int size` overflow in estimateSize by constructing two DEKs whose
        // wrappedBytes arrays, summed, exceed Integer.MAX_VALUE (~2.15 GB). With 2 × 1.1 GB
        // = 2.2 GB in wrappedBytes alone, the accumulator wraps negative and
        // ByteBuffer.allocate(negative) throws IllegalArgumentException — unchecked and
        // outside the declared `throws IOException` contract of writeShard. After the fix,
        // serialize must reject the over-size shard with an IOException.
        //
        // Record component fields are final and cannot be redirected via reflection on
        // recent JDKs (Unsafe.objectFieldOffset throws UnsupportedOperationException for
        // records), so we allocate two real arrays. Peak steady heap ≈ 2.2 GB; during
        // estimateSize the `d.wrappedBytes()` accessor clones once, pushing peak to ≈
        // 3.3 GB — within the 6 GB test JVM heap configured in
        // modules/jlsm-core/build.gradle. The input arrays are dereferenced (`w = null`)
        // between constructions with a suggestion to GC, minimizing the doubling window
        // during the compact ctor's defensive clone.
        final TenantId tenant = new TenantId("overflow-tenant");
        final KekRef ref = new KekRef("overflow-ref");
        final byte[] salt = new byte[32];

        final DekHandle h1 = new DekHandle(tenant, new DomainId("d1"), new TableId("t1"),
                new DekVersion(1));
        final DekHandle h2 = new DekHandle(tenant, new DomainId("d2"), new TableId("t2"),
                new DekVersion(1));

        byte[] w1 = new byte[1_100_000_000]; // 1.1 GB
        final WrappedDek d1 = new WrappedDek(h1, w1, 1, ref, java.time.Instant.EPOCH);
        // Release the caller-side reference so GC can reclaim the input array; only the
        // WrappedDek's internal clone is live from this point.
        w1 = null;
        System.gc();

        byte[] w2 = new byte[1_100_000_000]; // 1.1 GB → sum = 2.2 GB > Integer.MAX_VALUE
        final WrappedDek d2 = new WrappedDek(h2, w2, 1, ref, java.time.Instant.EPOCH);
        w2 = null;
        System.gc();

        final java.util.Map<DekHandle, WrappedDek> deks = new java.util.HashMap<>();
        deks.put(h1, d1);
        deks.put(h2, d2);
        final KeyRegistryShard shard = new KeyRegistryShard(tenant, deks, java.util.Map.of(), ref,
                salt);

        final Path registryRoot = tempDir.resolve("registry");
        final ShardStorage storage = new ShardStorage(registryRoot);

        // writeShard declares `throws IOException`. Under the bug, estimateSize wraps to a
        // negative int and ByteBuffer.allocate throws IllegalArgumentException — an
        // unchecked exception leaking past the declared contract. After the fix, serialize
        // must detect the over-size condition and throw IOException; this test passes iff
        // IOException (not IAE) is surfaced.
        assertThrows(IOException.class, () -> storage.writeShard(tenant, shard),
                "writeShard declares `throws IOException` — a shard whose serialized size "
                        + "overflows Integer.MAX_VALUE must surface as IOException, not "
                        + "IllegalArgumentException from ByteBuffer.allocate(negative). The "
                        + "int accumulator in estimateSize must be promoted to long and the "
                        + "size bound must be enforced before allocation.");
    }

    // ── F-R1.contract_boundaries.3.4 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.3.4
    // Bug: ShardStorage.isStrictlyNewer compares only maxDekVersion and maxDomainKekVersion
    // between candidate and existing shards. If a tenant performs
    // `shard.withTenantKekRef(newRef)` (activating a different tier-1 KEK) without
    // bumping any DEK or domain-KEK version, a crash between temp-write and rename
    // leaves an orphan whose maxDekVersion and maxDomainKekVersion are identical to
    // the prior shard. On recovery, `isStrictlyNewer` returns false and the orphan
    // is deleted — silently discarding a legitimate committed-to-disk write. R20a
    // mandates "recover the latest valid write"; activeTenantKekRef changes are
    // valid writes that currently don't participate in the "newer" predicate.
    // Correct behavior: isStrictlyNewer must also treat a change in activeTenantKekRef as
    // making the candidate newer when DEK and domain-KEK versions tie. A candidate
    // whose activeTenantKekRef differs from the existing shard's activeTenantKekRef
    // (under equal max versions) represents a legitimate update that must be
    // promoted by orphan recovery, not deleted.
    // Fix location: ShardStorage.isStrictlyNewer — add a final tier that compares
    // activeTenantKekRef when both version pairs tie; treat "differs" as "newer".
    // Regression watch: the existing "strictly older by versions" fast-reject (cDek<eDek
    // or cDomain<eDomain under cDek==eDek) must still delete the orphan; the
    // "strictly newer by versions" path (cDek>eDek or cDomain>eDomain under tie)
    // must still promote; the new branch fires only when BOTH version pairs tie.
    @Test
    void test_recoverOrphanTemps_tenantKekRefChange_promotesOrphan(@TempDir Path tempDir)
            throws IOException {
        // Scenario: tenant T has a committed shard with activeTenantKekRef = refA. An
        // operator rotates the tier-1 KEK: shard.withTenantKekRef(refB). The write
        // commits the temp file (valid CRC, fsync complete) but crashes before the
        // atomic rename. Orphan recovery runs on restart. Under the bug,
        // isStrictlyNewer sees equal maxDekVersion and maxDomainKekVersion across the
        // orphan and the existing shard — and returns false, silently deleting the
        // legitimate KEK-rotation write. R20a requires the "latest valid write" to
        // survive recovery; the KEK rotation is such a write.
        final TenantId tenant = new TenantId("kek-rotation-tenant");
        final KekRef refA = new KekRef("kek-A");
        final KekRef refB = new KekRef("kek-B");
        final byte[] salt = new byte[32];
        for (int i = 0; i < 32; i++) {
            salt[i] = (byte) (i + 1);
        }

        // Build a shard with one DEK and one domain KEK. Both versions are pinned at 1
        // so neither `maxDekVersion` nor `maxDomainKekVersion` changes across the two
        // shards — the only field that differs is `activeTenantKekRef`.
        final DomainId d = new DomainId("d");
        final TableId t = new TableId("t");
        final DekHandle handle = new DekHandle(tenant, d, t, new DekVersion(1));
        final WrappedDek dek = new WrappedDek(handle, "dek-bytes".getBytes(StandardCharsets.UTF_8),
                1, refA, java.time.Instant.EPOCH);
        final WrappedDomainKek dKek = new WrappedDomainKek(d, 1,
                "dkek-bytes".getBytes(StandardCharsets.UTF_8), refA);

        final java.util.Map<DekHandle, WrappedDek> deksA = new java.util.HashMap<>();
        deksA.put(handle, dek);
        final java.util.Map<DomainId, WrappedDomainKek> dKeksA = new java.util.HashMap<>();
        dKeksA.put(d, dKek);

        final KeyRegistryShard shardA = new KeyRegistryShard(tenant, deksA, dKeksA, refA, salt);
        final KeyRegistryShard shardB = shardA.withTenantKekRef(refB);

        // Stage 1: commit shardA as the final shard in registry1.
        final Path registryRoot = tempDir.resolve("registry1");
        final ShardStorage storage = new ShardStorage(registryRoot);
        storage.writeShard(tenant, shardA);

        // Stage 2: serialize shardB into a valid on-disk orphan next to shard.bin.
        // Rather than simulate a partial write directly (which would require package-
        // private hooks), use a sibling registry to produce a valid CRC-sealed shard
        // file and then copy its bytes into the primary registry's tenant dir with a
        // .tmp suffix. This mirrors the observable state after a crash between
        // fsync+close and the atomic rename: a well-formed CRC-valid temp file whose
        // only content difference from the committed shard is activeTenantKekRef.
        final Path registryRoot2 = tempDir.resolve("registry2");
        final ShardStorage storage2 = new ShardStorage(registryRoot2);
        storage2.writeShard(tenant, shardB);
        final byte[] orphanBytes = Files
                .readAllBytes(ShardPathResolver.shardPath(registryRoot2, tenant));
        final Path shardPathPrimary = ShardPathResolver.shardPath(registryRoot, tenant);
        final Path orphanPath = ShardPathResolver.tempPath(shardPathPrimary, "abcdef1234567890");
        Files.write(orphanPath, orphanBytes);
        if (Files.getFileAttributeView(orphanPath.getParent(),
                java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(orphanPath,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }

        // Stage 3: run orphan recovery. The current final shard has refA; the orphan
        // has refB. Under the fix, isStrictlyNewer must treat the refB orphan as newer
        // when DEK/domain versions tie, so it is promoted (atomic rename) onto
        // shard.bin — preserving the committed-to-disk KEK rotation. Under the bug,
        // isStrictlyNewer returns false and the orphan is deleted, reverting the
        // tenant's active KEK to refA and silently dropping a legitimate write.
        storage.recoverOrphanTemps();

        // Assert: the final shard now reflects shardB (refB), not shardA (refA).
        final java.util.Optional<KeyRegistryShard> loaded = storage.loadShard(tenant);
        assertTrue(loaded.isPresent(),
                "final shard must exist after recovery — recovery must not leave the "
                        + "tenant's shard directory empty");
        final KeyRegistryShard recovered = loaded.get();
        assertNotNull(recovered.activeTenantKekRef(),
                "recovered shard must have a non-null activeTenantKekRef — both shardA "
                        + "and shardB have one set, and recovery must preserve it");
        assertTrue(refB.equals(recovered.activeTenantKekRef()),
                "recovered shard's activeTenantKekRef must be refB (from the orphan), "
                        + "not refA (from the prior committed shard). isStrictlyNewer "
                        + "must treat activeTenantKekRef changes as a legitimate update "
                        + "worth promoting — otherwise R20a's 'latest valid write wins' "
                        + "contract is violated for any KEK rotation that does not also "
                        + "bump a DEK or domain-KEK version. Observed ref="
                        + recovered.activeTenantKekRef());
        // Under the bug, the orphan is deleted rather than promoted. The fix path
        // atomically moves the orphan onto shard.bin — either way, the .tmp file
        // must be absent after recovery completes.
        assertTrue(!Files.exists(orphanPath),
                "orphan temp file must be consumed by recovery (either promoted or "
                        + "deleted) — recovery must not leave a .tmp file on disk");
    }

    // ── F-R1.contract_boundaries.3.5 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.3.5
    // Bug: TenantShardRegistry.close invokes `Arrays.fill(snap.hkdfSalt(), (byte) 0)`,
    // but KeyRegistryShard.hkdfSalt() returns a fresh clone (defensive copy).
    // Arrays.fill zeros the clone — which is immediately discarded — while the
    // authoritative byte[] stored inside the record is never touched. R69 requires
    // best-effort zeroization of salt bytes; the current code produces zero effort
    // on the authoritative copy, leaving plaintext salt material live in memory
    // until GC happens to reclaim the KeyRegistryShard.
    // Correct behavior: close() must zero the actual backing salt array held by the
    // cached shard, not a throwaway clone. A post-close inspection of the record's
    // internal `hkdfSalt` field (via reflection) must observe an all-zero array.
    // Fix location: TenantShardRegistry.close (line ~158) and/or KeyRegistryShard —
    // add a package-private `zeroizeSalt()` method on KeyRegistryShard that zeroes
    // the internal field directly, and call it from close() instead of filling
    // the cloned accessor result.
    // Regression watch: close() must remain idempotent (double-close safe); the salt
    // field's immutable contract must still prevent external mutation by normal
    // callers — only the package-private zeroize path may mutate.
    @Test
    void test_close_zeroizesAuthoritativeSaltBytes_notMerelyClone(@TempDir Path tempDir)
            throws Exception {
        // Build a shard with a known, non-zero salt pattern so we can distinguish a
        // truly-zeroized array from random leftover memory. Commit it to disk so the
        // registry loads it back as the cached snapshot.
        final TenantId tenant = new TenantId("zeroize-tenant");
        final KekRef ref = new KekRef("kek-zero");
        final byte[] salt = new byte[32];
        for (int i = 0; i < 32; i++) {
            salt[i] = (byte) (0xA0 | (i & 0x0F)); // all non-zero (0xA0..0xAF repeating)
        }
        final KeyRegistryShard seed = new KeyRegistryShard(tenant, java.util.Map.of(),
                java.util.Map.of(), ref, salt);

        final Path registryRoot = tempDir.resolve("registry");
        final ShardStorage storage = new ShardStorage(registryRoot);
        storage.writeShard(tenant, seed);

        final TenantShardRegistry registry = new TenantShardRegistry(storage);
        try {
            // Trigger a lazy load so the registry caches a KeyRegistryShard with its
            // own internal salt byte[] (ShardStorage.deserialize builds a fresh record,
            // so the cached shard's internal hkdfSalt is independent of `salt` above).
            final KeyRegistryShard loaded = registry.readSnapshot(tenant);
            // Sanity: what the accessor returns must match what we wrote.
            final byte[] accessorCopy = loaded.hkdfSalt();
            assertTrue(java.util.Arrays.equals(accessorCopy, salt),
                    "precondition: loaded shard's salt must round-trip the written bytes");

            // Reach the authoritative internal byte[] inside the record. This is the
            // array that close() must zero — NOT the clone the public accessor returns.
            final Field saltField = KeyRegistryShard.class.getDeclaredField("hkdfSalt");
            saltField.setAccessible(true);
            final byte[] internalSalt = (byte[]) saltField.get(loaded);
            assertNotNull(internalSalt,
                    "KeyRegistryShard.hkdfSalt internal field must be reflectively readable");
            // Authoritative array must identically hold the non-zero pattern before close.
            assertTrue(java.util.Arrays.equals(internalSalt, salt),
                    "precondition: authoritative internal salt must hold the non-zero "
                            + "pattern before close()");

            // Act: close the registry. Per R69 this must zero cached salt bytes
            // best-effort — and "best-effort" at minimum means touching the real array,
            // not a discarded clone.
            registry.close();

            // Assert: the captured authoritative internal array is now all zero.
            // Under the bug, close() fills a clone and internalSalt remains 0xA0..0xAF.
            // Under the fix, internalSalt[i] == 0 for all i.
            for (int i = 0; i < internalSalt.length; i++) {
                assertTrue(internalSalt[i] == 0,
                        "TenantShardRegistry.close must zero the authoritative salt byte[" + i
                                + "] — Arrays.fill on the accessor clone is a no-op "
                                + "because KeyRegistryShard.hkdfSalt() defensively copies. "
                                + "R69 'best-effort zeroization' requires touching the real "
                                + "backing array. Observed byte=0x"
                                + String.format("%02X", internalSalt[i] & 0xFF));
            }
        } finally {
            // Idempotent close — safe even if the test body already closed.
            registry.close();
        }
    }

    // ── F-R1.contract_boundaries.3.6 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.3.6
    // Bug: KeyRegistryShard's compact ctor delegates null-entry validation to
    // Map.copyOf(deks) / Map.copyOf(domainKeks). When a HashMap containing a null
    // value is passed, Map.copyOf throws NullPointerException with a generic message
    // (e.g. "element cannot be null" on HotSpot / OpenJDK). The message names neither
    // which field was offending (deks vs domainKeks) nor which key's value was null.
    // Propagated through ShardStorage.deserialize's catch-and-wrap (IOException with
    // path context), the operator sees "malformed shard file at <path>: <generic>"
    // and cannot tell whether DEKs, domain KEKs, or a specific handle produced the
    // corruption. Troubleshooting requires hex-diffing the shard file.
    // Correct behavior: the compact ctor must perform per-entry null checks explicitly,
    // naming the offending field ("deks" or "domainKeks") AND the key whose value
    // was null, before delegating to Map.copyOf. The NullPointerException message
    // must contain BOTH tokens so downstream log lines are actionable.
    // Fix location: KeyRegistryShard.java:45-46 — iterate deks.entrySet() and
    // domainKeks.entrySet() with explicit null checks before Map.copyOf.
    // Regression watch: the existing non-null contract (NPE on null-valued entry) must
    // still fire; the existing Map.copyOf defensive-copy semantics must remain;
    // null-KEY entries must still be rejected (Map.copyOf's null-key check is
    // acceptable because HashMap.put(null, v) itself stores a legal-but-rare entry,
    // but the finding focuses on null VALUES which are the reported failure mode).
    @Test
    void test_compactCtor_nullDekValue_messageNamesFieldAndKey() {
        final TenantId tenant = new TenantId("msg-tenant");
        final KekRef ref = new KekRef("kek-msg");
        final byte[] salt = new byte[32];
        for (int i = 0; i < 32; i++) {
            salt[i] = (byte) 0xAA;
        }

        // Build a deks map containing a null value for a known handle. Using HashMap
        // directly because Map.of rejects nulls at build time — the bug fires at the
        // compact ctor's Map.copyOf call, not at map construction.
        final DomainId domain = new DomainId("dom-msg");
        final TableId table = new TableId("tab-msg");
        final DekHandle handle = new DekHandle(tenant, domain, table, new DekVersion(1));
        final java.util.Map<DekHandle, WrappedDek> deksWithNull = new java.util.HashMap<>();
        deksWithNull.put(handle, null);
        final java.util.Map<DomainId, WrappedDomainKek> emptyDomainKeks = new java.util.HashMap<>();

        final NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> new KeyRegistryShard(tenant, deksWithNull, emptyDomainKeks, ref, salt),
                "compact ctor must reject a deks map containing a null value");

        final String msg = thrown.getMessage();
        assertNotNull(msg,
                "NPE from null-valued deks entry must carry a message — operators rely on "
                        + "the message for corrupt-shard triage");
        assertTrue(msg.contains("deks"),
                "NPE message must name the offending field 'deks' so operators can tell "
                        + "whether the DEK map or the domain-KEK map produced the null. "
                        + "Observed message: " + msg);
        assertTrue(msg.contains(handle.toString()) || msg.contains(String.valueOf(handle)),
                "NPE message must name the key whose value was null (" + handle + ") so "
                        + "operators can locate the offending entry without hex-diffing "
                        + "the shard file. Observed message: " + msg);
    }

    @Test
    void test_compactCtor_nullDomainKekValue_messageNamesFieldAndKey() {
        final TenantId tenant = new TenantId("msg-tenant2");
        final KekRef ref = new KekRef("kek-msg2");
        final byte[] salt = new byte[32];
        for (int i = 0; i < 32; i++) {
            salt[i] = (byte) 0xBB;
        }

        // Empty deks map; a domainKeks map containing a null value for a known DomainId.
        final DomainId domain = new DomainId("dom-msg2");
        final java.util.Map<DekHandle, WrappedDek> emptyDeks = new java.util.HashMap<>();
        final java.util.Map<DomainId, WrappedDomainKek> domainKeksWithNull = new java.util.HashMap<>();
        domainKeksWithNull.put(domain, null);

        final NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> new KeyRegistryShard(tenant, emptyDeks, domainKeksWithNull, ref, salt),
                "compact ctor must reject a domainKeks map containing a null value");

        final String msg = thrown.getMessage();
        assertNotNull(msg, "NPE from null-valued domainKeks entry must carry a message — operators "
                + "rely on the message for corrupt-shard triage");
        assertTrue(msg.contains("domainKeks"),
                "NPE message must name the offending field 'domainKeks' so operators can "
                        + "tell whether the DEK map or the domain-KEK map produced the "
                        + "null. Observed message: " + msg);
        assertTrue(msg.contains(domain.toString()) || msg.contains(String.valueOf(domain)),
                "NPE message must name the key whose value was null (" + domain + ") so "
                        + "operators can locate the offending entry without hex-diffing "
                        + "the shard file. Observed message: " + msg);
    }

    // ── F-R1.contract_boundaries.3.7 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.3.7
    // Bug: ShardPathResolver.tempPath concatenates the caller-supplied suffix into the temp
    // file name without validating it for path-separator or path-traversal characters.
    // A suffix like "../../../etc/passwd" makes resolveSibling(tempName) interpret the
    // ".." segments as navigation and produces a path outside the shard's directory.
    // Correct behavior: reject any suffix containing path separators ('/', '\'), '.',
    // ':', or control characters with IllegalArgumentException before building the
    // temp path. The returned path MUST be a sibling of shardPath — never an escape.
    // Fix location: ShardPathResolver.tempPath — add validation between the isEmpty check
    // and the temp-name construction (lines 63-70).
    // Regression watch: the in-tree caller (ShardStorage) passes UUID hex strings which are
    // exclusively [0-9a-f]; that must keep working. Uppercase hex and alphanumerics
    // must also remain valid to avoid over-restricting legitimate callers.
    @Test
    void test_tempPath_pathTraversalSuffix_rejectedWithIAE(@TempDir Path tempDir) {
        final Path shardPath = tempDir.resolve("shards").resolve("AAAAAAAA").resolve("shard.bin");

        // Primary attack: relative-parent traversal from the finding.
        assertThrows(IllegalArgumentException.class,
                () -> ShardPathResolver.tempPath(shardPath, "../../../etc/passwd"),
                "tempPath must reject suffix containing '..' path-traversal segments");

        // Forward slash path separator: even without '..', a separator turns the temp path
        // into a multi-segment path that is not a sibling of shardPath.
        assertThrows(IllegalArgumentException.class,
                () -> ShardPathResolver.tempPath(shardPath, "a/b"),
                "tempPath must reject suffix containing forward slash");

        // Backslash path separator (Windows-style).
        assertThrows(IllegalArgumentException.class,
                () -> ShardPathResolver.tempPath(shardPath, "a\\b"),
                "tempPath must reject suffix containing backslash");

        // Dot-only segment — allows another form of traversal injection.
        assertThrows(IllegalArgumentException.class,
                () -> ShardPathResolver.tempPath(shardPath, ".."),
                "tempPath must reject '..' suffix");

        // Regression: a UUID-hex suffix (what ShardStorage passes) must still be accepted.
        final Path legitTemp = ShardPathResolver.tempPath(shardPath,
                "0123456789abcdef0123456789abcdef");
        assertNotNull(legitTemp, "legitimate hex suffix must still be accepted");
        assertTrue(legitTemp.getParent().equals(shardPath.getParent()),
                "legitimate temp path must remain a sibling of shardPath");
    }

    // ── F-R1.contract_boundaries.5.3 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.5.3
    // Bug: TenantId/DomainId/TableId default record toString leaks the raw value
    // string verbatim. A caller embedding PII or a hex-encoded key fingerprint
    // in one of these opaque identifiers leaks it to any downstream
    // toString/logging path (WrappedDek.toString, DekHandle.toString, etc.).
    // Correct behavior: toString() must NOT contain the raw value string. An
    // explicit .value() call remains the sole sanctioned accessor for the
    // raw string. The toString form must unambiguously indicate that the
    // value has been redacted (e.g., contain a "redacted" marker) so that
    // developers reading a log line know the value is withheld.
    // Fix location: TenantId.java, DomainId.java, TableId.java — add an explicit
    // toString() override on each record.
    // Regression watch: DekNotFoundException.forHandle and the existing NPE-message
    // tests at lines ~1460/~1494 use .value() or handle.toString() directly;
    // redacting identifier toString does not affect .value() and does not
    // affect tests that only assert handle.toString() equals itself.
    @Test
    void test_scopeIdentifiers_toString_doesNotLeakRawValue() {
        // A caller embeds what LOOKS like a hex-encoded key fingerprint or PII
        // in an opaque scope identifier. These strings would be a privacy /
        // secret-leak concern if they flow verbatim into logs or exception
        // messages via any implicit toString path.
        final String sensitiveTenant = "pii-0123456789abcdef-tenant-secret";
        final String sensitiveDomain = "secret-domain-deadbeefcafebabe";
        final String sensitiveTable = "user-pii-table-feedface01234567";

        final TenantId tenant = new TenantId(sensitiveTenant);
        final DomainId domain = new DomainId(sensitiveDomain);
        final TableId table = new TableId(sensitiveTable);

        // Direct toString must redact the raw string.
        assertTrue(!tenant.toString().contains(sensitiveTenant),
                "TenantId.toString() must NOT contain the raw value string — default "
                        + "record toString leaks identifiers verbatim to logs and "
                        + "exception messages. Observed: " + tenant.toString());
        assertTrue(!domain.toString().contains(sensitiveDomain),
                "DomainId.toString() must NOT contain the raw value string. Observed: "
                        + domain.toString());
        assertTrue(!table.toString().contains(sensitiveTable),
                "TableId.toString() must NOT contain the raw value string. Observed: "
                        + table.toString());

        // .value() must still return the raw string — toString is the leak path,
        // .value() is the explicit accessor.
        assertTrue(tenant.value().equals(sensitiveTenant),
                "TenantId.value() must still return the raw string — .value() is "
                        + "the sanctioned accessor, redaction must apply only to toString");
        assertTrue(domain.value().equals(sensitiveDomain),
                "DomainId.value() must still return the raw string");
        assertTrue(table.value().equals(sensitiveTable),
                "TableId.value() must still return the raw string");

        // Transitive leak: DekHandle's default record toString embeds the three
        // identifier toString forms. If any of the three leak, handle.toString
        // leaks.
        final DekHandle handle = new DekHandle(tenant, domain, table, new DekVersion(1));
        final String handleStr = handle.toString();
        assertTrue(!handleStr.contains(sensitiveTenant),
                "DekHandle.toString() must not transitively expose tenant raw value. "
                        + "Observed: " + handleStr);
        assertTrue(!handleStr.contains(sensitiveDomain),
                "DekHandle.toString() must not transitively expose domain raw value. "
                        + "Observed: " + handleStr);
        assertTrue(!handleStr.contains(sensitiveTable),
                "DekHandle.toString() must not transitively expose table raw value. " + "Observed: "
                        + handleStr);

        // Transitive leak through WrappedDek.toString (the record masks wrappedBytes
        // but embeds handle verbatim via toString).
        final byte[] anyBytes = new byte[]{ 0x01, 0x02, 0x03, 0x04 };
        final KekRef kekRef = new KekRef("kek-for-leak-test");
        final WrappedDek wrapped = new WrappedDek(handle, anyBytes, 1, kekRef,
                java.time.Instant.EPOCH);
        final String wrappedStr = wrapped.toString();
        assertTrue(!wrappedStr.contains(sensitiveTenant),
                "WrappedDek.toString() must not transitively expose tenant raw value. "
                        + "Observed: " + wrappedStr);
        assertTrue(!wrappedStr.contains(sensitiveDomain),
                "WrappedDek.toString() must not transitively expose domain raw value. "
                        + "Observed: " + wrappedStr);
        assertTrue(!wrappedStr.contains(sensitiveTable),
                "WrappedDek.toString() must not transitively expose table raw value. "
                        + "Observed: " + wrappedStr);

        // Transitive leak through WrappedDomainKek.toString.
        final WrappedDomainKek wrappedDomain = new WrappedDomainKek(domain, 1, anyBytes, kekRef);
        final String wrappedDomainStr = wrappedDomain.toString();
        assertTrue(!wrappedDomainStr.contains(sensitiveDomain),
                "WrappedDomainKek.toString() must not transitively expose domain raw "
                        + "value. Observed: " + wrappedDomainStr);
    }

    /**
     * Wraps a real {@link KmsClient} but strips the {@code wrappedBytes} to an empty
     * {@link ByteBuffer} on every {@link #wrapKek} call — simulating a misbehaving or misconfigured
     * KMS adapter that returns zero-length ciphertext. Unwrap and the other operations pass through
     * unchanged.
     */
    private static final class EmptyWrapBytesKms implements KmsClient, AutoCloseable {

        private final KmsClient delegate;

        EmptyWrapBytesKms(KmsClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public WrapResult wrapKek(MemorySegment plaintextKek, KekRef kekRef,
                EncryptionContext context) throws KmsException {
            final WrapResult real = delegate.wrapKek(plaintextKek, kekRef, context);
            // Return an empty ByteBuffer, preserving kekRef so the "kekRef non-null" check
            // (finding 1.6, separate concern) is not the rejecting condition here.
            return new WrapResult(ByteBuffer.allocate(0), real.kekRef());
        }

        @Override
        public UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef,
                EncryptionContext context) throws KmsException {
            return delegate.unwrapKek(wrappedBytes, kekRef, context);
        }

        @Override
        public boolean isUsable(KekRef kekRef) throws KmsException {
            return delegate.isUsable(kekRef);
        }

        @Override
        public void close() {
            // Delegate close is the test's try-with-resources' responsibility — do not
            // double-close.
        }
    }

    // ── F-R1.contract_boundaries.4.1 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.4.1
    // Bug: AesGcmContextWrap.encodeAad uses Purpose.ordinal() for the 4-byte purpose field
    // in the canonical AAD. The ordinal of an enum constant is implicit — it depends
    // on declaration order in Purpose.java and carries no source-level stability
    // guarantee. A future refactor that reorders the Purpose constants silently shifts
    // the AAD purpose bytes, invalidating every persisted wrap produced by the older
    // binary. GCM authentication then fails on every subsequent unwrap, rendering all
    // wrapped DEKs/KEKs undecryptable across the upgrade boundary — with no compile-
    // time or unit-test signal because both sides of a single-process round-trip agree
    // on the new ordinal.
    // Correct behavior: Purpose exposes an explicit, stable {@code code()} accessor whose
    // int values are pinned at specific constants (not derived from ordinal()). The
    // AAD encoder writes {@code code()}, never {@code ordinal()}, so reordering the
    // enum is harmless for persisted ciphertext. The code() values for the existing
    // constants are chosen to differ from their current ordinals (so a bugged encoder
    // that reverted to ordinal() would be caught by a golden-vector test).
    // Fix location: jlsm.encryption.Purpose — add {@code int code()} with explicit pinned
    // values; jlsm.encryption.internal.AesGcmContextWrap.encodeAad line 214 — replace
    // {@code ctx.purpose().ordinal()} with {@code ctx.purpose().code()}.
    // Regression watch: the code() values must be treated as a persistent-format contract —
    // once shipped they cannot be changed without a compatibility plan. New Purpose
    // constants must receive a fresh (unused, non-colliding) code() value rather than
    // reuse.
    @Test
    void test_Purpose_codeAccessor_pinsStableValuesAndAadEmbedsCodeNotOrdinal() throws Exception {
        // 1. Purpose.code() must exist and return the pinned values — these bind the
        // persistent-format contract that ordinal() did not.
        final java.lang.reflect.Method codeMethod;
        try {
            codeMethod = Purpose.class.getDeclaredMethod("code");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Purpose.code() accessor is missing — ordinal() is "
                    + "load-bearing for AAD encoding (R80a) and must be replaced with a "
                    + "stable explicit-valued accessor. See "
                    + "AesGcmContextWrap.encodeAad line 214.", e);
        }
        assertTrue(codeMethod.getReturnType() == int.class,
                "Purpose.code() must return primitive int (the AAD field is 4-byte BE int)");

        // Pinned values: chosen to DIFFER from ordinals so a reverted encoder (ordinal())
        // would still fail this test. If these values need to change, treat it as a
        // persistent-format break.
        final java.util.Map<Purpose, Integer> expectedCodes = new java.util.LinkedHashMap<>();
        expectedCodes.put(Purpose.DOMAIN_KEK, 1);
        expectedCodes.put(Purpose.DEK, 2);
        expectedCodes.put(Purpose.REKEY_SENTINEL, 3);
        expectedCodes.put(Purpose.HEALTH_CHECK, 4);
        for (final var entry : expectedCodes.entrySet()) {
            final int actual = (int) codeMethod.invoke(entry.getKey());
            final int expected = entry.getValue();
            assertTrue(actual == expected,
                    "Purpose." + entry.getKey().name() + ".code() must be pinned to " + expected
                            + " (explicit stability contract — not ordinal()). " + "Observed: "
                            + actual);
            assertTrue(actual != entry.getKey().ordinal(),
                    "Purpose." + entry.getKey().name() + ".code() must be distinct from "
                            + "ordinal() so an accidental revert of AesGcmContextWrap."
                            + "encodeAad to ordinal() is detectable via the golden-vector "
                            + "check below. code()=" + actual + ", ordinal()="
                            + entry.getKey().ordinal());
        }

        // 2. AesGcmContextWrap.encodeAad must embed code(), not ordinal(). Invoke the
        // private method reflectively and verify the first 4 bytes (big-endian int) equal
        // code() for each purpose.
        final Class<?> wrapClass = Class.forName("jlsm.encryption.internal.AesGcmContextWrap");
        final java.lang.reflect.Method encodeAad = wrapClass.getDeclaredMethod("encodeAad",
                EncryptionContext.class);
        encodeAad.setAccessible(true);

        // Build an EncryptionContext per purpose via the factory methods (valid attribute
        // shapes — factories guarantee R80a-1 consistency).
        final java.util.Map<Purpose, EncryptionContext> ctxs = new java.util.EnumMap<>(
                Purpose.class);
        ctxs.put(Purpose.DOMAIN_KEK, EncryptionContext.forDomainKek(TENANT, DOMAIN));
        ctxs.put(Purpose.DEK, EncryptionContext.forDek(TENANT, DOMAIN, TABLE, new DekVersion(7)));
        ctxs.put(Purpose.REKEY_SENTINEL, EncryptionContext.forRekeySentinel(TENANT, DOMAIN));
        ctxs.put(Purpose.HEALTH_CHECK, EncryptionContext.forHealthCheck(TENANT, DOMAIN));

        for (final var entry : ctxs.entrySet()) {
            final byte[] aad = (byte[]) encodeAad.invoke(null, entry.getValue());
            assertTrue(aad.length >= 4,
                    "encodeAad output must contain at least the 4-byte purpose field");
            final int purposeField = ByteBuffer.wrap(aad, 0, 4).order(ByteOrder.BIG_ENDIAN)
                    .getInt();
            final int expected = (int) codeMethod.invoke(entry.getKey());
            assertTrue(purposeField == expected,
                    "encodeAad must embed Purpose.code() (not ordinal()) as the 4-byte BE "
                            + "purpose field to survive enum reordering. Purpose="
                            + entry.getKey().name() + ", expected code=" + expected
                            + ", observed AAD[0..4]=" + purposeField + " (ordinal="
                            + entry.getKey().ordinal() + "). "
                            + "Fix AesGcmContextWrap.encodeAad line 214.");
        }
    }

    // ── F-R1.contract_boundaries.4.2 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.4.2
    // Bug: EncryptionContext compact ctor only validates non-null; it does NOT
    // enforce that the attributes map matches the Purpose's required-attribute
    // set documented in R80a-1. A caller can construct
    // new EncryptionContext(Purpose.DEK, Map.of("tenantId","t","domainId","d"))
    // omitting the tableId and dekVersion attributes that the forDek factory
    // hardcodes. The malformed context then round-trips through
    // AesGcmContextWrap.wrap/unwrap without detection, defeating R80a-1 semantic
    // scoping ("a DEK wrap is bound to a table and a specific version").
    // Correct behavior: the compact ctor must reject any (Purpose, Map) pair whose
    // attribute keys do not exactly match the required-attribute set for the
    // given Purpose, raising IllegalArgumentException. The factories must still
    // succeed (they always supply the exact set).
    // Fix location: EncryptionContext.java:26-30 — compact ctor body; add a per-
    // Purpose required-attribute switch and reject mismatches. The factories
    // already construct contexts with exactly the right shape, so tightening
    // the compact ctor does not require changing them.
    // Regression watch: the existing null-purpose / null-attributes NPE contracts
    // must still fire with NullPointerException (not IAE); the defensive-copy
    // semantics (Map.copyOf) must still apply so attributes are immutable; all
    // factory methods (forDomainKek, forDek, forRekeySentinel, forHealthCheck)
    // must continue to succeed without modification.
    @Test
    void test_compactCtor_dekPurposeMissingRequiredAttrs_rejectsWithIAE() {
        // R80a-1: DEK purpose requires {tenantId, domainId, tableId, dekVersion}.
        // Omitting tableId+dekVersion — as a misguided direct-ctor caller or a
        // record-deserializer might — must be rejected at construction.
        final java.util.Map<String, String> dekPurposeWithOnlyTwoAttrs = java.util.Map
                .of("tenantId", "t", "domainId", "d");
        assertThrows(IllegalArgumentException.class,
                () -> new EncryptionContext(Purpose.DEK, dekPurposeWithOnlyTwoAttrs));
    }

    // ── F-R1.contract_boundaries.4.3 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.4.3
    // Bug: Hkdf.expand leaves secret OKM bytes in the `result` byte[] on the
    // exception path. When mac.doFinal() throws mid-loop after at least
    // one successful iteration, `result` contains T(1)..T(n-1) fully
    // plus a partial T(n). The finally block zeros previousBlock and
    // currentBlock but NOT `result`. The array becomes GC-garbage with
    // secret-derived bytes lingering on the heap until the slab is
    // overwritten — violating R16c ("zero every intermediate heap
    // buffer that held secret material").
    // Correct behavior: on any exception path out of expand (including
    // unchecked ProviderException / RuntimeException from a faulty JCE
    // provider, not just the declared NoSuchAlgorithmException /
    // InvalidKeyException), the `result` byte[] must be zeroed before
    // the exception propagates. The happy path (return result) must
    // leave `result` untouched — the caller is responsible for
    // subsequent zeroization via Hkdf.deriveKey's finally.
    // Fix location: Hkdf.java:147-182 — add `result` zeroization to the
    // finally block, guarded by a success flag so the returned bytes
    // are not corrupted on the happy path.
    // Regression watch: happy-path expand must still return correct OKM
    // bytes (no zeroization of the returned array); previousBlock and
    // currentBlock must continue to be zeroed unconditionally in
    // finally; the declared checked-exception translation
    // (NoSuchAlgorithmException / InvalidKeyException → IllegalStateException)
    // must be preserved; R16c compliance must extend to ALL exception
    // paths, not only the declared ones.
    @Test
    void test_Hkdf_expand_zeroesResultOnExceptionPath_R16c() throws IOException {
        // Behavioral component: drive expand down the exception path using a
        // malicious JCE provider, confirm the exception type is preserved.
        // Source-structural component: verify R16c compliance by asserting
        // the expand method's finally block zeros the `result` byte[].
        //
        // R16c residue on `result` is not directly observable through Java's
        // public API after a throw (the array becomes unreachable GC-garbage
        // with no caller-visible reference), so the test asserts the
        // structural defense at the source level. The behavioral assertions
        // confirm the exception path is actually reachable and the malicious
        // provider setup is sound.

        // ── Behavioral: malicious MacSpi forces mid-loop failure ──
        final String providerName = "MaliciousHkdfExpandProvider";
        final java.security.Provider malicious = new java.security.Provider(providerName, "1.0",
                "Adversarial HmacSHA256 that throws mid-loop to exercise Hkdf.expand exception path") {
            private static final long serialVersionUID = 1L;
            {
                put("Mac.HmacSHA256",
                        "jlsm.encryption.ContractBoundariesAdversarialTest$ThrowingMacSpi");
            }
        };
        final int insertedAt;
        java.security.Security.insertProviderAt(malicious, 1);
        insertedAt = 1;
        try {
            ThrowingMacSpi.reset();
            // outLenBytes = 64 → numBlocks = 2, so doFinal() is called twice;
            // the malicious provider succeeds on call 1 and throws on call 2,
            // exercising the exception path after at least one partial write
            // to `result`.
            final byte[] prk = new byte[32];
            java.util.Arrays.fill(prk, (byte) 0x11);
            final byte[] info = new byte[]{ 'h', 'k', 'd', 'f' };
            final RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> jlsm.encryption.internal.Hkdf.expand(prk, info, 64),
                    "Hkdf.expand must propagate the mid-loop provider failure");
            // Accept either ProviderException (our injected type) or its
            // unwrapped form; the translator for doFinal() only covers
            // NoSuchAlgorithmException/InvalidKeyException, so ProviderException
            // (which extends RuntimeException) propagates unwrapped.
            assertTrue(
                    thrown instanceof java.security.ProviderException
                            || thrown.getCause() instanceof java.security.ProviderException
                            || thrown.getMessage() != null
                                    && thrown.getMessage().contains("mid-loop"),
                    "the thrown exception must be or wrap the injected ProviderException, got "
                            + thrown);
            assertTrue(ThrowingMacSpi.doFinalCallCount >= 2,
                    "malicious provider must have been invoked for at least two doFinal calls "
                            + "to exercise the partial-write exception path; got "
                            + ThrowingMacSpi.doFinalCallCount);
            // Sanity: the hash1 array reference captured on iter 1 must be
            // all-zero post-exception (confirming the existing previousBlock
            // zeroization in finally runs — baseline R16c for previousBlock).
            final byte[] capturedHash1 = ThrowingMacSpi.firstDoFinalResultCapture;
            assertNotNull(capturedHash1,
                    "malicious MacSpi must have captured the first doFinal return for "
                            + "baseline R16c verification");
            for (int i = 0; i < capturedHash1.length; i++) {
                assertTrue(capturedHash1[i] == 0,
                        "previousBlock (captured hash1) must be zeroed by expand's finally "
                                + "at index " + i + " — baseline R16c check, got 0x"
                                + String.format("%02x", capturedHash1[i]));
            }
        } finally {
            java.security.Security.removeProvider(providerName);
        }

        // ── Source-structural: verify R16c zeroization of `result` ──
        // `result` residue cannot be observed behaviorally after expand
        // throws (the reference is local and becomes unreachable), so we
        // verify the source-level defense directly. This check is narrow:
        // it looks for an explicit Arrays.fill(result, ...) inside the
        // expand method's body. If the fix uses an equivalent mechanism
        // (e.g., overwriting via a loop or MemorySegment.fill), this check
        // can be updated; the intent is that `result` is unambiguously
        // zeroed on the exception path.
        final Path hkdfSource = Path.of("src/main/java/jlsm/encryption/internal/Hkdf.java");
        assertTrue(Files.exists(hkdfSource),
                "Hkdf.java source must be locatable for R16c source-structural check; looked at "
                        + hkdfSource.toAbsolutePath());
        final String source = Files.readString(hkdfSource);
        final int expandStart = source.indexOf("public static byte[] expand(");
        assertTrue(expandStart >= 0, "expand method must be present in Hkdf.java");
        // Isolate the expand method body: from the method signature through
        // the next top-level method declaration (looks for the next javadoc
        // comment opening at indent level 4, which delimits the next public
        // method in this class).
        final int nextMethodStart = source.indexOf("\n    /**", expandStart + 1);
        final String expandBody = nextMethodStart > 0
                ? source.substring(expandStart, nextMethodStart)
                : source.substring(expandStart);
        // R16c fix must zero `result` on the exception path. Accept any of:
        // Arrays.fill(result, (byte) 0)
        // Arrays.fill(result, (byte)0)
        // java.util.Arrays.fill(result, (byte) 0)
        final boolean zeroesResult = expandBody
                .matches("(?s).*Arrays\\.fill\\s*\\(\\s*result\\s*,\\s*\\(byte\\)\\s*0\\s*\\).*");
        assertTrue(zeroesResult,
                "Hkdf.expand must zero the `result` byte[] on the exception path (R16c). "
                        + "Expected Arrays.fill(result, (byte) 0) inside the expand method's "
                        + "exception-handler or finally region. The finding is: on mac.doFinal() "
                        + "mid-loop failure, T(1)..T(n-1) + partial T(n) OKM bytes linger in "
                        + "`result` until GC reclaims the slab, violating R16c.");
    }

    // MacSpi implementation used by the adversarial R16c test. Public + public
    // no-arg ctor so Provider.put("Mac.HmacSHA256", classname) can instantiate
    // it via reflection. First engineDoFinal succeeds with a sentinel;
    // second throws ProviderException (a RuntimeException) to simulate
    // mid-operation provider failure (e.g., token revocation, OOM, hardware
    // fault) between HKDF-Expand T(1) and T(2).
    public static final class ThrowingMacSpi extends javax.crypto.MacSpi {
        static int doFinalCallCount;
        static byte[] firstDoFinalResultCapture;

        public ThrowingMacSpi() {
        }

        static void reset() {
            doFinalCallCount = 0;
            firstDoFinalResultCapture = null;
        }

        @Override
        protected int engineGetMacLength() {
            return 32;
        }

        @Override
        protected void engineInit(java.security.Key key,
                java.security.spec.AlgorithmParameterSpec params) {
            // no-op — malicious provider ignores key material
        }

        @Override
        protected void engineUpdate(byte input) {
            // no-op
        }

        @Override
        protected void engineUpdate(byte[] input, int offset, int len) {
            // no-op — we don't need to accumulate input to simulate the
            // mid-operation failure; the test only observes exception
            // semantics and the captured first-block reference.
        }

        @Override
        protected byte[] engineDoFinal() {
            doFinalCallCount++;
            if (doFinalCallCount == 1) {
                final byte[] sentinel = new byte[32];
                java.util.Arrays.fill(sentinel, (byte) 0x5A);
                firstDoFinalResultCapture = sentinel;
                return sentinel;
            }
            throw new java.security.ProviderException(
                    "simulated mid-loop provider failure (HKDF-Expand T(2))");
        }

        @Override
        protected void engineReset() {
            // no-op — after a throw the Mac instance is unusable anyway
        }
    }

    // ── F-R1.contract_boundaries.4.4 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.4.4
    // Bug: AesGcmContextWrap.unwrap catches GeneralSecurityException and unconditionally
    // translates every GSE to IllegalArgumentException with the message "AES-GCM
    // unwrap failed (authentication or context mismatch)". This is correct for
    // AEADBadTagException (the AEAD tag-mismatch subclass of BadPaddingException)
    // because a tag failure does signal tamper / context drift / forged ciphertext —
    // a caller-recoverable programmer / input fault. But non-tag GSE types
    // (InvalidKeyException from a third-party JCE provider that rejects the KEK
    // encoding, NoSuchAlgorithmException from a stripped JDK that lacks AES/GCM,
    // etc.) signal environmental / infrastructure faults — not tamper. Translating
    // them to IAE with an authentication-failure message mis-tells callers and
    // ops tooling: any downstream logic that branches on IAE to trigger rekey,
    // security alerting, or "possible attack" paths fires a false positive for an
    // ordinary environment bug. The wrap path handles the identical situation
    // correctly (line 108-109: GSE → IllegalStateException "AES-GCM wrap failed"),
    // so the unwrap catch is inconsistent with wrap.
    // Correct behavior: narrow the catch. Only AEADBadTagException (javax.crypto) — the
    // AEAD tag-mismatch type — should translate to IllegalArgumentException with
    // the authentication-failure message. Every other GeneralSecurityException
    // (including NoSuchAlgorithmException, InvalidKeyException, any provider-
    // specific GSE subclass that does not signal a forged ciphertext) must
    // translate to IllegalStateException, matching wrap's "AES-GCM ... failed"
    // convention. BadPaddingException that is NOT AEADBadTagException cannot occur
    // for AES/GCM/NoPadding from a conforming provider, but for defensive safety
    // it is acceptable to treat plain BadPaddingException the same as
    // AEADBadTagException (tamper-like) rather than environmental, since a
    // provider that signals padding failure is already misbehaving and the
    // conservative branch is to assume the ciphertext is suspect.
    // Fix location: AesGcmContextWrap.java:175-178 — replace the single broad
    // catch (GeneralSecurityException e) { throw new IllegalArgumentException(...) }
    // with a narrow catch for AEADBadTagException → IAE and a broad catch for
    // GeneralSecurityException → ISE.
    // Regression watch: the existing path "real GCM tag failure → IllegalArgumentException"
    // (exercised by every tamper-detection test) must still throw IAE; the
    // existing "wrapped length mismatch pre-cipher-init" path throws IAE before
    // the catch block so is unaffected; the finally block's zeroization must
    // still run for both narrow and broad catch branches (it already does —
    // finally is unconditional).
    @Test
    void test_unwrap_nonTagGSE_surfacesAsISE_notIAE() throws Exception {
        // Strategy: produce a real wrapped blob via the default provider, then inject
        // a malicious Cipher provider at position 1 so the unwrap's Cipher.getInstance
        // call returns a malicious CipherSpi whose engineInit throws InvalidKeyException
        // (a GeneralSecurityException that is NOT AEADBadTagException). Under the buggy
        // code the broad catch translates this to IAE with the authentication-failure
        // message; the correct behavior is to translate non-tag GSE to ISE, matching
        // wrap's translation at AesGcmContextWrap.java:108-109.

        final byte[] kekPlain = new byte[32];
        for (int i = 0; i < 32; i++) {
            kekPlain[i] = (byte) (0x20 + i);
        }
        final EncryptionContext ctx = EncryptionContext.forDomainKek(TENANT, DOMAIN);

        // 1. Produce a valid wrapped blob using the default (SunJCE) provider. No
        // malicious provider is installed yet, so Cipher.getInstance resolves to the
        // default AES/GCM/NoPadding implementation.
        final byte[] wrapped;
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kek = arena.allocate(kekPlain.length);
            MemorySegment.copy(kekPlain, 0, kek, java.lang.foreign.ValueLayout.JAVA_BYTE, 0,
                    kekPlain.length);
            final MemorySegment plaintext = arena.allocate(32);
            for (int i = 0; i < 32; i++) {
                plaintext.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, (byte) (0x40 + i));
            }
            wrapped = jlsm.encryption.internal.AesGcmContextWrap.wrap(kek, plaintext, ctx,
                    new java.security.SecureRandom());
        }

        // 2. Install the malicious AES/GCM/NoPadding Cipher provider at position 1 so
        // the unwrap's Cipher.getInstance resolves to our CipherSpi that throws
        // InvalidKeyException from engineInit (a non-tag GSE).
        final String providerName = "MaliciousAesGcmCipherProvider";
        final java.security.Provider malicious = new java.security.Provider(providerName, "1.0",
                "Adversarial AES/GCM/NoPadding that throws non-tag GSE from init — F-R1."
                        + "contract_boundaries.4.4") {
            private static final long serialVersionUID = 1L;
            {
                put("Cipher.AES/GCM/NoPadding",
                        "jlsm.encryption.ContractBoundariesAdversarialTest$InvalidKeyCipherSpi");
            }
        };
        // Snapshot existing providers so we can selectively remove the AES-capable
        // ones (SunJCE) and restore them in finally. Keep SUN / etc. so JCE
        // infrastructure (SHA-1 etc.) continues to function. With SunJCE absent
        // there is no fallback path for AES/GCM/NoPadding, so Cipher.getInstance
        // MUST resolve to the malicious provider.
        final java.util.List<java.security.Provider> snapshot = new java.util.ArrayList<>(
                java.util.Arrays.asList(java.security.Security.getProviders()));
        final java.util.List<java.security.Provider> removedForTest = new java.util.ArrayList<>();
        for (final java.security.Provider existing : snapshot) {
            // Remove any provider that claims to implement AES/GCM/NoPadding — SunJCE
            // in practice, but do this by service lookup so the test is robust across
            // JDK vendors.
            if (existing.getService("Cipher", "AES/GCM/NoPadding") != null
                    || existing.getService("Cipher", "AES") != null) {
                removedForTest.add(existing);
                java.security.Security.removeProvider(existing.getName());
            }
        }
        final int insertedPos = java.security.Security.insertProviderAt(malicious, 1);
        try {
            // Sanity: the malicious provider must actually be the one returned for
            // AES/GCM/NoPadding. With every other provider removed, there is no
            // fallback path.
            final javax.crypto.Cipher probe = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            assertTrue(probe.getProvider().getName().equals(providerName),
                    "test setup: AES/GCM/NoPadding must resolve to the malicious provider "
                            + "(inserted at position " + insertedPos + ") — got "
                            + probe.getProvider().getName() + ". The adversarial setup for "
                            + "F-R1.contract_boundaries.4.4 requires the malicious CipherSpi to "
                            + "take priority so its engineInit can throw InvalidKeyException.");
            // Additional sanity: probe.init with a real SecretKeySpec MUST throw
            // InvalidKeyException from the malicious engineInit. If it doesn't, the
            // test setup is failing to exercise the non-tag GSE path.
            Throwable probeInitFailure = null;
            try {
                probe.init(javax.crypto.Cipher.DECRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(kekPlain, "AES"),
                        new javax.crypto.spec.GCMParameterSpec(128, new byte[12]));
            } catch (Throwable t) {
                probeInitFailure = t;
            }
            assertTrue(probeInitFailure instanceof java.security.InvalidKeyException,
                    "probe.init must throw InvalidKeyException from malicious engineInit; got "
                            + probeInitFailure);
            try (Arena callerArena = Arena.ofConfined()) {
                final MemorySegment kek = callerArena.allocate(kekPlain.length);
                MemorySegment.copy(kekPlain, 0, kek, java.lang.foreign.ValueLayout.JAVA_BYTE, 0,
                        kekPlain.length);
                // The malicious provider's engineInit throws InvalidKeyException. The
                // current (buggy) unwrap catches this as a GeneralSecurityException and
                // translates it to IllegalArgumentException. The fix must translate
                // non-tag GSE to IllegalStateException instead.
                Throwable captured = null;
                try {
                    jlsm.encryption.internal.AesGcmContextWrap.unwrap(kek, wrapped, ctx, 32,
                            callerArena);
                } catch (Throwable t) {
                    captured = t;
                }
                assertNotNull(captured,
                        "unwrap must throw when the cipher init fails with a non-tag GSE. "
                                + "Malicious provider resolved: " + probe.getProvider().getName()
                                + ". engineInit overloads should " + "throw InvalidKeyException.");
                final RuntimeException thrown = (RuntimeException) captured;
                // The expected, correct type is IllegalStateException (environmental
                // failure — matches wrap's GSE→ISE at line 108-109). The buggy code
                // throws IllegalArgumentException with the authentication-failure
                // message; this test asserts correct behavior.
                assertTrue(thrown instanceof IllegalStateException,
                        "non-tag GeneralSecurityException (InvalidKeyException from a "
                                + "malicious / misconfigured JCE provider) must surface as "
                                + "IllegalStateException — signalling an environmental / "
                                + "infrastructure failure — not IllegalArgumentException which "
                                + "would be read by callers/ops tooling as a forged-ciphertext "
                                + "(authentication) failure and trigger false-positive security "
                                + "alerting. Got: " + thrown.getClass().getName() + " — "
                                + thrown.getMessage());
                // The message must NOT claim authentication/context mismatch — that
                // message is reserved for real tag failures.
                assertTrue(
                        thrown.getMessage() == null
                                || !thrown.getMessage().contains("authentication"),
                        "non-tag GSE translation must not use the "
                                + "'authentication or context mismatch' message — that message "
                                + "is reserved for AEADBadTagException (real tag failure). "
                                + "Got message: " + thrown.getMessage());
                // Cause must be the original GSE, preserved for diagnostics.
                assertTrue(thrown.getCause() instanceof java.security.InvalidKeyException,
                        "translated exception must preserve the original non-tag GSE as "
                                + "its cause for diagnostics. Got cause: " + thrown.getCause());
            }
        } finally {
            java.security.Security.removeProvider(providerName);
            // Restore the removed AES-capable providers. We append them at the end of
            // the list (any position >= current list size) — their relative order is
            // preserved from removedForTest which was iterated over the original
            // snapshot in priority order.
            for (final java.security.Provider restored : removedForTest) {
                // Re-add if absent (some other test may have re-installed it).
                if (java.security.Security.getProvider(restored.getName()) == null) {
                    java.security.Security.addProvider(restored);
                }
            }
        }
    }

    // ── F-R1.contract_boundaries.5.1 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.5.1
    // Bug: DomainId compact-constructor does not reserve the "_wal" synthetic domain.
    // A user-code caller can do `new DomainId("_wal")` and collide with the
    // WAL-encryption scope inside the per-tenant key registry. Two logically
    // distinct scopes (user data + WAL state) resolve to the same
    // (tenantId, DomainId("_wal"), tableId) triple.
    // Correct behavior: the public compact constructor must reject "_wal" with
    // IllegalArgumentException. Internal WAL callers obtain the synthetic
    // DomainId via a dedicated factory (DomainId.forWal()) that is the only
    // sanctioned way to construct it.
    // Fix location: modules/jlsm-core/src/main/java/jlsm/encryption/DomainId.java —
    // compact ctor (lines 27-32) and add a static factory forWal().
    // Regression watch: the pre-existing DomainIdTest.constructor_accepts_walReservedName
    // asserted the OLD behavior and must be updated to reflect the new contract
    // (that is a Test Writer responsibility — the new contract comes from this
    // adversarial finding). Internal callers that need the _wal domain must
    // route through DomainId.forWal(), never the public constructor.
    @Test
    void test_domainId_publicCtor_rejects_walReservedName() {
        // User-code caller supplies "_wal" as a data-domain name for unrelated user
        // data. The public compact constructor must reject this because "_wal" is
        // reserved per spec R75 for the synthetic WAL encryption domain.
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new DomainId("_wal"));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("_wal"),
                "exception message should identify the reserved name; got: " + ex.getMessage());

        // The internal factory must still be able to construct it (the synthetic
        // _wal domain IS a DomainId — per R71 — just internal).
        final DomainId wal = DomainId.forWal();
        assertNotNull(wal, "DomainId.forWal() must return a non-null instance");
        assertTrue("_wal".equals(wal.value()),
                "DomainId.forWal() must yield the reserved value; got: " + wal.value());
    }

    // ── F-R1.contract_boundaries.5.4 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.5.4
    // Bug: WrappedDek and WrappedDomainKek compact ctors only null-check wrappedBytes;
    // a zero-length byte[] is accepted and persisted as a registry record. This
    // defers the "malformed wrapped ciphertext" failure from the record boundary
    // to the deferred unwrap crypto operation, past all persistence/serialization
    // paths. A record-level minimum-length guard (length > 0) would fail-fast.
    // Correct behavior: the compact ctor must reject a zero-length wrappedBytes with
    // IllegalArgumentException — a wrapped ciphertext cannot be empty under any
    // supported scheme (AES-GCM minimum = IV+tag = 28 bytes; AES-KWP minimum = 24).
    // Fix location: WrappedDek.java compact ctor (lines 34-44) and WrappedDomainKek.java
    // compact ctor (lines 29-37) — add `if (wrappedBytes.length == 0) throw` after
    // the requireNonNull null-check and before the defensive clone.
    // Regression watch: existing tests that construct these records with short but
    // non-empty payloads (e.g., "wrap-zeta".getBytes(), "dek-tbl".getBytes()) must
    // continue to pass — only the zero-length case is newly rejected.
    @Test
    void test_compactCtor_zeroLengthWrappedBytes_rejectedAtRecordBoundary() {
        // WrappedDek: zero-length wrappedBytes must be rejected at construction.
        final DekHandle handle = new DekHandle(TENANT, DOMAIN, TABLE, new DekVersion(1));
        final IllegalArgumentException dekEx = assertThrows(IllegalArgumentException.class,
                () -> new WrappedDek(handle, new byte[0], 1, KEK_REF, java.time.Instant.EPOCH),
                "WrappedDek must reject zero-length wrappedBytes at the record boundary — "
                        + "a wrapped ciphertext cannot be empty under any supported scheme");
        assertTrue(dekEx.getMessage() != null && dekEx.getMessage().contains("wrappedBytes"),
                "exception message should identify the field; got: " + dekEx.getMessage());

        // WrappedDomainKek: zero-length wrappedBytes must be rejected at construction.
        final IllegalArgumentException kekEx = assertThrows(IllegalArgumentException.class,
                () -> new WrappedDomainKek(DOMAIN, 1, new byte[0], KEK_REF),
                "WrappedDomainKek must reject zero-length wrappedBytes at the record "
                        + "boundary — a wrapped ciphertext cannot be empty under AES-KWP");
        assertTrue(kekEx.getMessage() != null && kekEx.getMessage().contains("wrappedBytes"),
                "exception message should identify the field; got: " + kekEx.getMessage());
    }

    // Finding: F-R1.contract_boundaries.3.3
    // Bug: EnvelopeCodec.stripPrefix throws IllegalArgumentException for under-length
    // envelopes, but its sibling EnvelopeCodec.parseVersion throws IOException for the
    // same on-disk corruption shape. Identical input failure (under-length envelope)
    // surfaces as different exception types depending on which entry point a caller
    // touches. Caller error-mapping (e.g., translating to CorruptSectionException) is
    // forced to know which sibling was called, and a refactor that swaps the call
    // order would silently change the caller-visible error type.
    // Correct behavior: stripPrefix's under-length rejection must surface as IOException
    // (matching parseVersion) — both methods speak the same dialect for the same shape
    // of on-disk corruption. The NPE-on-null contract remains; only the under-length
    // exception type is harmonized.
    // Fix location: EnvelopeCodec.stripPrefix (modules/jlsm-core/src/main/java/jlsm/
    // encryption/EnvelopeCodec.java:110-115) — change the under-length throw from
    // IllegalArgumentException to IOException; declare `throws IOException` on the
    // method. Update the small set of callers (FieldEncryptionDispatch, DocumentSerializer)
    // that already either declare IOException or wrap via UncheckedIOException.
    // Regression watch: existing well-formed-envelope round-trip tests must keep passing.
    // The legacy EnvelopeCodecTest.stripPrefix_underLength_throwsIae documented the
    // current (buggy) IAE behavior — that test will need to be updated to match the
    // harmonized IOException contract. This is an intentional contract correction.
    @Test
    void test_stripPrefix_underLengthEnvelope_throwsIOException_consistentWithParseVersion()
            throws IOException {
        // Sibling-method symmetry — both entry points reject under-length input
        // with the same exception type, so a caller's catch block does not need
        // to know which sibling was invoked.
        final byte[] underLength = new byte[]{ 1, 2, 3 };
        assertThrows(IOException.class, () -> EnvelopeCodec.parseVersion(underLength),
                "parseVersion must reject under-length envelope with IOException (control)");
        assertThrows(IOException.class, () -> EnvelopeCodec.stripPrefix(underLength),
                "stripPrefix must reject under-length envelope with IOException to match "
                        + "parseVersion's contract — identical on-disk corruption shape "
                        + "must surface as identical exception type across siblings");

        // Empty envelope — same shape, same exception type.
        assertThrows(IOException.class, () -> EnvelopeCodec.stripPrefix(new byte[0]),
                "stripPrefix(empty) must throw IOException to match parseVersion's contract");

        // The NPE-on-null contract is unchanged — null is a caller-side bug, not
        // an on-disk corruption, so it stays an unchecked exception.
        assertThrows(NullPointerException.class, () -> EnvelopeCodec.stripPrefix(null),
                "stripPrefix(null) must continue to throw NPE — null is a caller bug, "
                        + "not on-disk corruption");
    }

    // Malicious CipherSpi used by F-R1.contract_boundaries.4.4. Public + public no-arg
    // ctor so Provider.put("Cipher.AES/GCM/NoPadding", classname) can instantiate it
    // via reflection. engineInit throws InvalidKeyException — a GSE subclass that is
    // NOT AEADBadTagException — to simulate a third-party / stripped JDK provider
    // that rejects the KEK shape during init (an environmental failure, not a forged
    // ciphertext).
    public static final class InvalidKeyCipherSpi extends javax.crypto.CipherSpi {
        public InvalidKeyCipherSpi() {
        }

        @Override
        protected void engineSetMode(String mode) {
            // no-op — we only install one mode (GCM) via the provider property
        }

        @Override
        protected void engineSetPadding(String padding) {
            // no-op — NoPadding only
        }

        @Override
        protected int engineGetBlockSize() {
            return 16;
        }

        @Override
        protected int engineGetOutputSize(int inputLen) {
            return inputLen + 16;
        }

        @Override
        protected byte[] engineGetIV() {
            return new byte[12];
        }

        @Override
        protected java.security.AlgorithmParameters engineGetParameters() {
            return null;
        }

        @Override
        protected void engineInit(int opmode, java.security.Key key,
                java.security.SecureRandom random) throws java.security.InvalidKeyException {
            throw new java.security.InvalidKeyException(
                    "simulated third-party provider: KEK encoding rejected "
                            + "(non-tag GSE — environmental fault, not forged ciphertext)");
        }

        @Override
        protected void engineInit(int opmode, java.security.Key key,
                java.security.spec.AlgorithmParameterSpec params, java.security.SecureRandom random)
                throws java.security.InvalidKeyException {
            throw new java.security.InvalidKeyException(
                    "simulated third-party provider: KEK encoding rejected "
                            + "(non-tag GSE — environmental fault, not forged ciphertext)");
        }

        @Override
        protected void engineInit(int opmode, java.security.Key key,
                java.security.AlgorithmParameters params, java.security.SecureRandom random)
                throws java.security.InvalidKeyException {
            throw new java.security.InvalidKeyException(
                    "simulated third-party provider: KEK encoding rejected "
                            + "(non-tag GSE — environmental fault, not forged ciphertext)");
        }

        @Override
        protected byte[] engineUpdate(byte[] input, int inputOffset, int inputLen) {
            return new byte[0];
        }

        @Override
        protected int engineUpdate(byte[] input, int inputOffset, int inputLen, byte[] output,
                int outputOffset) {
            return 0;
        }

        @Override
        protected byte[] engineDoFinal(byte[] input, int inputOffset, int inputLen) {
            return new byte[0];
        }

        @Override
        protected int engineDoFinal(byte[] input, int inputOffset, int inputLen, byte[] output,
                int outputOffset) {
            return 0;
        }
    }
}
