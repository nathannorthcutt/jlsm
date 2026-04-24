package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.internal.ShardPathResolver;
import jlsm.encryption.internal.ShardStorage;
import jlsm.encryption.internal.TenantShardRegistry;
import jlsm.encryption.local.LocalKmsClient;

/**
 * Adversarial tests targeting contract-boundary violations in the encryption facade.
 *
 * <p>
 * Each test exercises a specific finding from the contract_boundaries lens: contract translations
 * that lose information (IOException→IllegalStateException), unvalidated SPI post-conditions,
 * and other boundary hygiene defects.
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
     * contains a CRC-32C trailer that fails verification, forcing {@link ShardStorage#loadShard}
     * to throw {@link IOException} on first access.
     */
    private static void plantCorruptShard(Path registryRoot, TenantId tenantId) throws IOException {
        final Path shardPath = ShardPathResolver.shardPath(registryRoot, tenantId);
        Files.createDirectories(shardPath.getParent());
        // Enough bytes to clear the "too short for CRC trailer" check (> 4 bytes), but the
        // payload is junk so CRC verification fails. Triggers the CRC-mismatch IOException path
        // inside ShardStorage.loadShard → propagates through TenantShardRegistry.readSnapshot.
        final byte[] garbage = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};
        Files.write(shardPath, garbage);
    }

    // ── F-R1.contract_boundaries.1.1 ─────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.1.1
    // Bug: currentDek translates IOException from registry.readSnapshot into IllegalStateException,
    //      collapsing "transient I/O failure" and "permanent state fault" into the same exception
    //      category. Callers cannot distinguish retryable network conditions from missing-DEK state.
    // Correct behavior: preserve the IOException category via UncheckedIOException (the standard
    //      Java idiom for surfacing a checked IOException through a method that does not declare
    //      `throws IOException`). Callers can then catch UncheckedIOException to retry transient
    //      failures and IllegalStateException to handle "closed" / "no DEK" permanent faults.
    // Fix location: EncryptionKeyHolder.currentDek — the catch (IOException e) block around the
    //      registry.readSnapshot(tenantId) call.
    // Regression watch: the IllegalStateException paths that remain (closed holder, missing DEK)
    //      must keep throwing IllegalStateException — only the IOException translation changes.
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

    // ── F-R1.contract_boundaries.1.10 ────────────────────────────────────────────

    // Finding: F-R1.contract_boundaries.1.10
    // Bug: close() silently swallows RuntimeException from fill() and arena.close() via
    //      "catch (RuntimeException ignored)" at lines 328 and 335. R66/R69 mandate
    //      zeroization of cached KEK plaintext and the silent swallow means a failed
    //      zeroization (or a failed arena close that leaks off-heap memory until JVM exit)
    //      is reported to no caller. Per coding-guidelines.md, close() must follow the
    //      deferred-close pattern: accumulate exceptions from multiple resources and throw
    //      after all resources are released; never suppress all exceptions silently.
    // Correct behavior: when fill() or arena.close() throws, close() must surface the
    //      failure to the caller (as a RuntimeException, since close() does not declare
    //      IOException). Other resources must still be closed (deferred close), but the
    //      failure signal must not be lost.
    // Fix location: EncryptionKeyHolder.close — the two "catch (RuntimeException ignored)"
    //      blocks at lines ~328 and ~335.
    // Regression watch: close() must remain idempotent (CAS guard); it must still release
    //      all resources even when one throws; existing tests that successfully close the
    //      holder must continue to pass.
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
}
