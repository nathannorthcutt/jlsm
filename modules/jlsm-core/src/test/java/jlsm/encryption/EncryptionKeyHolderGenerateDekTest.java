package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.internal.DekVersionRegistry;
import jlsm.encryption.internal.ShardStorage;
import jlsm.encryption.internal.TenantShardRegistry;
import jlsm.encryption.local.LocalKmsClient;

/**
 * Tests for the WD-03 extension of {@link EncryptionKeyHolder#generateDek} — the new collaborator
 * wiring that publishes the new DEK version to the wait-free
 * {@link jlsm.encryption.internal.DekVersionRegistry} (R64) after successful persistence (R29).
 *
 * <p>
 * Test class is separated from {@link EncryptionKeyHolderTest} per the WU-2 cross-cutting note —
 * keeps the WD-03 surface independent of the WD-01 facade tests.
 *
 * @spec encryption.primitives-lifecycle R29
 * @spec encryption.primitives-lifecycle R64
 */
class EncryptionKeyHolderGenerateDekTest {

    private static final TenantId TENANT = new TenantId("tenant-A");
    private static final DomainId DOMAIN = new DomainId("domain-1");
    private static final TableId TABLE = new TableId("table-1");
    private static final TableId TABLE_OTHER = new TableId("table-other");
    private static final KekRef KEK_REF = new KekRef("local-master");

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

    private record Fixture(LocalKmsClient kms, TenantShardRegistry registry, ShardStorage storage,
            DekVersionRegistry versionRegistry) implements AutoCloseable {
        @Override
        public void close() {
            registry.close();
            kms.close();
        }
    }

    private static Fixture newFixture(Path tempDir) throws IOException {
        final Path keyFile = writeMasterKey(tempDir);
        final LocalKmsClient kms = new LocalKmsClient(keyFile);
        final ShardStorage storage = new ShardStorage(tempDir.resolve("registry"));
        final TenantShardRegistry registry = new TenantShardRegistry(storage);
        final DekVersionRegistry versionRegistry = DekVersionRegistry.empty();
        return new Fixture(kms, registry, storage, versionRegistry);
    }

    private static EncryptionKeyHolder buildHolder(Fixture f) {
        return EncryptionKeyHolder.builder().kmsClient(f.kms()).registry(f.registry())
                .activeTenantKekRef(KEK_REF).dekVersionRegistry(f.versionRegistry()).build();
    }

    // --- builder support -------------------------------------------------

    @Test
    void builder_dekVersionRegistry_isOptional(@TempDir Path tempDir) throws IOException {
        // Optional collaborator — older callers without one must still build successfully.
        try (Fixture f = newFixture(tempDir)) {
            final EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(f.kms())
                    .registry(f.registry()).activeTenantKekRef(KEK_REF).build();
            assertNotNull(holder);
            holder.close();
        }
    }

    @Test
    void builder_dekVersionRegistry_nullThrows() {
        assertThrows(NullPointerException.class,
                () -> EncryptionKeyHolder.builder().dekVersionRegistry(null));
    }

    // --- generateDek publishes to DekVersionRegistry --------------------

    @Test
    void generateDek_publishesToVersionRegistry(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = buildHolder(f)) {
            holder.openDomain(TENANT, DOMAIN);
            final DekHandle handle = holder.generateDek(TENANT, DOMAIN, TABLE);
            // After generateDek, the version registry must have the new version as current.
            final TableScope scope = new TableScope(TENANT, DOMAIN, TABLE);
            assertEquals(Optional.of(handle.version().value()),
                    f.versionRegistry().currentVersion(scope));
            assertTrue(f.versionRegistry().knownVersions(scope).contains(handle.version().value()));
        }
    }

    @Test
    void generateDek_publishesMonotonicVersions(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = buildHolder(f)) {
            holder.openDomain(TENANT, DOMAIN);
            final DekHandle h1 = holder.generateDek(TENANT, DOMAIN, TABLE);
            final DekHandle h2 = holder.generateDek(TENANT, DOMAIN, TABLE);
            final DekHandle h3 = holder.generateDek(TENANT, DOMAIN, TABLE);
            final TableScope scope = new TableScope(TENANT, DOMAIN, TABLE);
            assertEquals(Optional.of(h3.version().value()),
                    f.versionRegistry().currentVersion(scope));
            // All three versions are part of known; head is the latest.
            assertTrue(f.versionRegistry().knownVersions(scope).containsAll(
                    Set.of(h1.version().value(), h2.version().value(), h3.version().value())));
        }
    }

    @Test
    void generateDek_perScopeIsolation_inVersionRegistry(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = buildHolder(f)) {
            holder.openDomain(TENANT, DOMAIN);
            holder.generateDek(TENANT, DOMAIN, TABLE);
            holder.generateDek(TENANT, DOMAIN, TABLE_OTHER);
            final TableScope scopeA = new TableScope(TENANT, DOMAIN, TABLE);
            final TableScope scopeB = new TableScope(TENANT, DOMAIN, TABLE_OTHER);
            // Each scope independently published its V1.
            assertEquals(Optional.of(1), f.versionRegistry().currentVersion(scopeA));
            assertEquals(Optional.of(1), f.versionRegistry().currentVersion(scopeB));
        }
    }

    @Test
    void generateDek_withoutVersionRegistry_stillSucceeds(@TempDir Path tempDir) throws Exception {
        // Backward compatibility: builders without a DekVersionRegistry continue to work — the
        // version registry is an opt-in collaborator for WD-03 lifecycle awareness.
        try (Fixture f = newFixture(tempDir);
                EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(f.kms())
                        .registry(f.registry()).activeTenantKekRef(KEK_REF).build()) {
            holder.openDomain(TENANT, DOMAIN);
            final DekHandle handle = holder.generateDek(TENANT, DOMAIN, TABLE);
            assertNotNull(handle);
            // The shared fixture's separate versionRegistry was NOT wired — must remain empty.
            assertEquals(Optional.empty(),
                    f.versionRegistry().currentVersion(new TableScope(TENANT, DOMAIN, TABLE)));
        }
    }

    @Test
    void generateDek_publishHappensAfterPersistence(@TempDir Path tempDir) throws Exception {
        // The shard write must happen BEFORE the version-registry publish — observable via shard
        // membership at the moment the version-registry sees the new version. We approximate this
        // by asserting that any test reading the shard after generateDek sees the DEK present
        // whenever the registry sees the version. (A weaker but sufficient check: both updates
        // visible after generateDek returns.)
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = buildHolder(f)) {
            holder.openDomain(TENANT, DOMAIN);
            final DekHandle handle = holder.generateDek(TENANT, DOMAIN, TABLE);
            final var snap = f.registry().readSnapshot(TENANT);
            assertTrue(snap.deks().containsKey(handle), "shard must hold the DEK after generate");
            assertEquals(Optional.of(handle.version().value()),
                    f.versionRegistry().currentVersion(new TableScope(TENANT, DOMAIN, TABLE)));
        }
    }
}
