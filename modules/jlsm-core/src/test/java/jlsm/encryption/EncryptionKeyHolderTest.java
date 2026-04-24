package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.internal.ShardStorage;
import jlsm.encryption.internal.TenantShardRegistry;
import jlsm.encryption.local.LocalKmsClient;

/**
 * Tests for the {@link EncryptionKeyHolder} F41 facade — three-tier envelope glue.
 *
 * <p>
 * Governed by: R9, R10a, R10b, R17, R21, R29, R55, R62, R62a, R66–R69, R80a-1.
 */
class EncryptionKeyHolderTest {

    private static final TenantId TENANT = new TenantId("tenant-A");
    private static final DomainId DOMAIN = new DomainId("domain-1");
    private static final TableId TABLE = new TableId("table-1");
    private static final KekRef KEK_REF = new KekRef("local-master");

    // --- helpers ----------------------------------------------------------

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

    private record Fixture(LocalKmsClient kms, TenantShardRegistry registry,
            ShardStorage storage) implements AutoCloseable {
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
        return new Fixture(kms, registry, storage);
    }

    private static EncryptionKeyHolder defaultHolder(Fixture f) {
        return EncryptionKeyHolder.builder().kmsClient(f.kms()).registry(f.registry())
                .activeTenantKekRef(KEK_REF).build();
    }

    // --- builder validation ---------------------------------------------

    @Test
    void builder_requiresKmsClient(@TempDir Path tempDir) throws IOException {
        try (Fixture f = newFixture(tempDir)) {
            final EncryptionKeyHolder.Builder b = EncryptionKeyHolder.builder()
                    .registry(f.registry()).activeTenantKekRef(KEK_REF);
            assertThrows(IllegalStateException.class, b::build);
        }
    }

    @Test
    void builder_requiresRegistry(@TempDir Path tempDir) throws IOException {
        try (Fixture f = newFixture(tempDir)) {
            final EncryptionKeyHolder.Builder b = EncryptionKeyHolder.builder().kmsClient(f.kms())
                    .activeTenantKekRef(KEK_REF);
            assertThrows(IllegalStateException.class, b::build);
        }
    }

    @Test
    void builder_requiresActiveTenantKekRef(@TempDir Path tempDir) throws IOException {
        try (Fixture f = newFixture(tempDir)) {
            final EncryptionKeyHolder.Builder b = EncryptionKeyHolder.builder().kmsClient(f.kms())
                    .registry(f.registry());
            assertThrows(IllegalStateException.class, b::build);
        }
    }

    @Test
    void builder_nullArgsThrow() {
        final EncryptionKeyHolder.Builder b = EncryptionKeyHolder.builder();
        assertThrows(NullPointerException.class, () -> b.kmsClient(null));
        assertThrows(NullPointerException.class, () -> b.registry(null));
        assertThrows(NullPointerException.class, () -> b.hkdfSalt(null));
        assertThrows(NullPointerException.class, () -> b.cacheTtl(null));
        assertThrows(NullPointerException.class, () -> b.activeTenantKekRef(null));
        assertThrows(IllegalArgumentException.class, () -> b.hkdfSalt(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> b.cacheTtl(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> b.cacheTtl(Duration.ofSeconds(-1)));
    }

    // --- openDomain ------------------------------------------------------

    @Test
    void openDomain_firstCall_unwrapsAndCaches(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = defaultHolder(f)) {
            // Before any DEK exists, openDomain must at least succeed by provisioning the domain
            // KEK.
            holder.openDomain(TENANT, DOMAIN);
            // Second call is a no-op (doesn't throw, doesn't re-unwrap fatally).
            holder.openDomain(TENANT, DOMAIN);
        }
    }

    @Test
    void openDomain_nullArgs_throwNpe(@TempDir Path tempDir) throws IOException {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = defaultHolder(f)) {
            assertThrows(NullPointerException.class, () -> holder.openDomain(null, DOMAIN));
            assertThrows(NullPointerException.class, () -> holder.openDomain(TENANT, null));
        }
    }

    @Test
    void openDomain_cacheTtlExpiry_reunwraps(@TempDir Path tempDir) throws Exception {
        final AtomicReference<Instant> nowRef = new AtomicReference<>(
                Instant.parse("2026-04-23T12:00:00Z"));
        final Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return nowRef.get();
            }
        };
        try (Fixture f = newFixture(tempDir);
                EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(f.kms())
                        .registry(f.registry()).activeTenantKekRef(KEK_REF)
                        .cacheTtl(Duration.ofSeconds(60)).clock(clock).build()) {
            holder.openDomain(TENANT, DOMAIN);
            // Advance past TTL
            nowRef.set(Instant.parse("2026-04-23T13:00:00Z"));
            // Should re-unwrap without error
            holder.openDomain(TENANT, DOMAIN);
        }
    }

    // --- generateDek -----------------------------------------------------

    @Test
    void generateDek_firstInvocation_returnsVersionOne(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = defaultHolder(f)) {
            holder.openDomain(TENANT, DOMAIN);
            final DekHandle handle = holder.generateDek(TENANT, DOMAIN, TABLE);
            assertNotNull(handle);
            assertEquals(DekVersion.FIRST, handle.version());
            assertEquals(TENANT, handle.tenantId());
            assertEquals(DOMAIN, handle.domainId());
            assertEquals(TABLE, handle.tableId());
        }
    }

    @Test
    void generateDek_secondInvocation_returnsMonotonicVersion(@TempDir Path tempDir)
            throws Exception {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = defaultHolder(f)) {
            holder.openDomain(TENANT, DOMAIN);
            final DekHandle first = holder.generateDek(TENANT, DOMAIN, TABLE);
            final DekHandle second = holder.generateDek(TENANT, DOMAIN, TABLE);
            assertEquals(1, first.version().value());
            assertEquals(2, second.version().value());
        }
    }

    @Test
    void generateDek_persistsInRegistry(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = defaultHolder(f)) {
            holder.openDomain(TENANT, DOMAIN);
            final DekHandle handle = holder.generateDek(TENANT, DOMAIN, TABLE);
            final var snapshot = f.registry().readSnapshot(TENANT);
            assertTrue(snapshot.deks().containsKey(handle),
                    "generated DEK must be in the tenant's shard");
            final WrappedDek wd = snapshot.deks().get(handle);
            assertNotNull(wd);
            assertEquals(handle, wd.handle());
        }
    }

    @Test
    void generateDek_withoutOpen_throwsIllegalState(@TempDir Path tempDir) throws IOException {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = defaultHolder(f)) {
            assertThrows(IllegalStateException.class,
                    () -> holder.generateDek(TENANT, DOMAIN, TABLE));
        }
    }

    @Test
    void generateDek_nullArgs_throwNpe(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = defaultHolder(f)) {
            holder.openDomain(TENANT, DOMAIN);
            assertThrows(NullPointerException.class, () -> holder.generateDek(null, DOMAIN, TABLE));
            assertThrows(NullPointerException.class, () -> holder.generateDek(TENANT, null, TABLE));
            assertThrows(NullPointerException.class,
                    () -> holder.generateDek(TENANT, DOMAIN, null));
        }
    }

    // --- currentDek / resolveDek ----------------------------------------

    @Test
    void currentDek_returnsHighestVersion(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = defaultHolder(f)) {
            holder.openDomain(TENANT, DOMAIN);
            holder.generateDek(TENANT, DOMAIN, TABLE);
            holder.generateDek(TENANT, DOMAIN, TABLE);
            final DekHandle third = holder.generateDek(TENANT, DOMAIN, TABLE);
            final DekHandle current = holder.currentDek(TENANT, DOMAIN, TABLE);
            assertEquals(third, current);
            assertEquals(3, current.version().value());
        }
    }

    @Test
    void currentDek_noDekExists_throwsIllegalState(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = defaultHolder(f)) {
            holder.openDomain(TENANT, DOMAIN);
            assertThrows(IllegalStateException.class,
                    () -> holder.currentDek(TENANT, DOMAIN, TABLE));
        }
    }

    @Test
    void resolveDek_presentVersion_returnsHandle(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = defaultHolder(f)) {
            holder.openDomain(TENANT, DOMAIN);
            final DekHandle first = holder.generateDek(TENANT, DOMAIN, TABLE);
            final DekHandle resolved = holder.resolveDek(TENANT, DOMAIN, TABLE, first.version());
            assertEquals(first, resolved);
        }
    }

    @Test
    void resolveDek_missingVersion_throwsDekNotFound(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = defaultHolder(f)) {
            holder.openDomain(TENANT, DOMAIN);
            holder.generateDek(TENANT, DOMAIN, TABLE); // version 1 only
            final DekNotFoundException ex = assertThrows(DekNotFoundException.class,
                    () -> holder.resolveDek(TENANT, DOMAIN, TABLE, new DekVersion(42)));
            assertNotNull(ex.getMessage());
        }
    }

    // --- deriveFieldKey --------------------------------------------------

    @Test
    void deriveFieldKey_deterministic(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = defaultHolder(f)) {
            holder.openDomain(TENANT, DOMAIN);
            final DekHandle handle = holder.generateDek(TENANT, DOMAIN, TABLE);
            try (Arena a1 = Arena.ofConfined(); Arena a2 = Arena.ofConfined()) {
                final MemorySegment k1 = holder.deriveFieldKey(handle, "table-1", "field-X", 32,
                        a1);
                final MemorySegment k2 = holder.deriveFieldKey(handle, "table-1", "field-X", 32,
                        a2);
                assertArrayEquals(toArray(k1), toArray(k2), "same inputs must produce same key");
            }
        }
    }

    @Test
    void deriveFieldKey_differentFields_produceDifferentKeys(@TempDir Path tempDir)
            throws Exception {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = defaultHolder(f)) {
            holder.openDomain(TENANT, DOMAIN);
            final DekHandle handle = holder.generateDek(TENANT, DOMAIN, TABLE);
            try (Arena a = Arena.ofConfined()) {
                final MemorySegment kA = holder.deriveFieldKey(handle, "table-1", "field-A", 32, a);
                final MemorySegment kB = holder.deriveFieldKey(handle, "table-1", "field-B", 32, a);
                assertFalse(java.util.Arrays.equals(toArray(kA), toArray(kB)),
                        "different field names must differentiate (R13)");
            }
        }
    }

    @Test
    void deriveFieldKey_crossTenantDomainTable_differentiates(@TempDir Path tempDir)
            throws Exception {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = defaultHolder(f)) {
            holder.openDomain(TENANT, DOMAIN);
            final TenantId otherTenant = new TenantId("tenant-B");
            final DomainId otherDomain = new DomainId("domain-2");
            final TableId otherTable = new TableId("table-2");
            holder.openDomain(otherTenant, otherDomain);
            final DekHandle h1 = holder.generateDek(TENANT, DOMAIN, TABLE);
            final DekHandle h2 = holder.generateDek(otherTenant, otherDomain, otherTable);
            try (Arena a = Arena.ofConfined()) {
                final MemorySegment kA = holder.deriveFieldKey(h1, "table-1", "field", 32, a);
                final MemorySegment kB = holder.deriveFieldKey(h2, "table-1", "field", 32, a);
                assertFalse(java.util.Arrays.equals(toArray(kA), toArray(kB)),
                        "different (tenant,domain,table) must differentiate (R14)");
            }
        }
    }

    @Test
    void deriveFieldKey_nullArgs_throwNpe(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = defaultHolder(f)) {
            holder.openDomain(TENANT, DOMAIN);
            final DekHandle handle = holder.generateDek(TENANT, DOMAIN, TABLE);
            try (Arena a = Arena.ofConfined()) {
                assertThrows(NullPointerException.class,
                        () -> holder.deriveFieldKey(null, "t", "f", 32, a));
                assertThrows(NullPointerException.class,
                        () -> holder.deriveFieldKey(handle, null, "f", 32, a));
                assertThrows(NullPointerException.class,
                        () -> holder.deriveFieldKey(handle, "t", null, 32, a));
                assertThrows(NullPointerException.class,
                        () -> holder.deriveFieldKey(handle, "t", "f", 32, null));
            }
        }
    }

    @Test
    void deriveFieldKey_emptyNames_throwIllegalArg(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir); EncryptionKeyHolder holder = defaultHolder(f)) {
            holder.openDomain(TENANT, DOMAIN);
            final DekHandle handle = holder.generateDek(TENANT, DOMAIN, TABLE);
            try (Arena a = Arena.ofConfined()) {
                assertThrows(IllegalArgumentException.class,
                        () -> holder.deriveFieldKey(handle, "", "f", 32, a));
                assertThrows(IllegalArgumentException.class,
                        () -> holder.deriveFieldKey(handle, "t", "", 32, a));
            }
        }
    }

    @Test
    void deriveFieldKey_afterClose_throwsIllegalState(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir)) {
            final EncryptionKeyHolder holder = defaultHolder(f);
            holder.openDomain(TENANT, DOMAIN);
            final DekHandle handle = holder.generateDek(TENANT, DOMAIN, TABLE);
            holder.close();
            try (Arena a = Arena.ofConfined()) {
                assertThrows(IllegalStateException.class,
                        () -> holder.deriveFieldKey(handle, "t", "f", 32, a));
            }
        }
    }

    // --- R62a atomic close vs derive ------------------------------------

    @Test
    void close_waitsForInflightDerive(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir)) {
            final EncryptionKeyHolder holder = defaultHolder(f);
            holder.openDomain(TENANT, DOMAIN);
            final DekHandle handle = holder.generateDek(TENANT, DOMAIN, TABLE);

            final ExecutorService exec = Executors.newFixedThreadPool(2);
            final CountDownLatch started = new CountDownLatch(1);
            final CountDownLatch proceed = new CountDownLatch(1);
            try {
                final Future<byte[]> derive = exec.submit(() -> {
                    try (Arena a = Arena.ofConfined()) {
                        started.countDown();
                        proceed.await(2, TimeUnit.SECONDS);
                        final MemorySegment seg = holder.deriveFieldKey(handle, "t", "f", 32, a);
                        return toArray(seg);
                    }
                });
                assertTrue(started.await(2, TimeUnit.SECONDS));
                // Schedule close; it must wait for in-flight derive to complete (R62a).
                final Future<Void> closer = exec.submit(() -> {
                    holder.close();
                    return null;
                });
                // Release the derive thread; it should complete successfully using the still-valid
                // key material.
                proceed.countDown();
                final byte[] key = derive.get(5, TimeUnit.SECONDS);
                assertNotNull(key);
                assertEquals(32, key.length);
                closer.get(5, TimeUnit.SECONDS);
            } finally {
                exec.shutdownNow();
            }
        }
    }

    // --- close -----------------------------------------------------------

    @Test
    void close_idempotent(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir)) {
            final EncryptionKeyHolder holder = defaultHolder(f);
            holder.close();
            holder.close(); // must not throw
        }
    }

    @Test
    void postClose_allOperations_throwIllegalState(@TempDir Path tempDir) throws Exception {
        try (Fixture f = newFixture(tempDir)) {
            final EncryptionKeyHolder holder = defaultHolder(f);
            holder.close();
            assertThrows(IllegalStateException.class, () -> holder.openDomain(TENANT, DOMAIN));
            assertThrows(IllegalStateException.class,
                    () -> holder.currentDek(TENANT, DOMAIN, TABLE));
            assertThrows(IllegalStateException.class,
                    () -> holder.resolveDek(TENANT, DOMAIN, TABLE, DekVersion.FIRST));
            assertThrows(IllegalStateException.class,
                    () -> holder.generateDek(TENANT, DOMAIN, TABLE));
        }
    }

    // --- hkdfSalt mismatch (R10b) ---------------------------------------

    @Test
    void builder_hkdfSaltMismatch_throwsWithHashPrefix(@TempDir Path tempDir) throws Exception {
        // First: generate the registry with default salt (32 zero bytes) and write a DEK,
        // which persists the salt implicitly (first shard write uses current salt).
        try (Fixture f = newFixture(tempDir)) {
            final EncryptionKeyHolder holder = defaultHolder(f);
            holder.openDomain(TENANT, DOMAIN);
            holder.generateDek(TENANT, DOMAIN, TABLE);
            holder.close();
        }

        // Second: open the SAME registry with a DIFFERENT salt — must reject.
        final byte[] otherSalt = new byte[32];
        for (int i = 0; i < 32; i++) {
            otherSalt[i] = (byte) (i + 1);
        }
        try (Fixture f = newFixture(tempDir)) {
            final EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(f.kms())
                    .registry(f.registry()).activeTenantKekRef(KEK_REF).hkdfSalt(otherSalt).build();
            final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> holder.openDomain(TENANT, DOMAIN));
            final String msg = ex.getMessage();
            assertNotNull(msg);
            assertTrue(msg.toLowerCase().contains("salt"), "message must mention salt: " + msg);
            // Must contain a hash-prefix but NOT raw bytes. Raw bytes would be
            // "0x01020304..." or similar long hex. We assert that the raw salt hex
            // (first 16 bytes, 32 hex chars) does not appear in the message.
            final StringBuilder rawHex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                rawHex.append(String.format("%02x", otherSalt[i]));
            }
            assertFalse(msg.toLowerCase().contains(rawHex.toString()),
                    "message must not contain raw salt bytes: " + msg);
            holder.close();
        }
    }

    // --- helpers ---------------------------------------------------------

    private static byte[] toArray(MemorySegment seg) {
        final byte[] out = new byte[(int) seg.byteSize()];
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0, out, 0, out.length);
        return out;
    }
}
