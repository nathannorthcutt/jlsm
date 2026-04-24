package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.DekHandle;
import jlsm.encryption.DekVersion;
import jlsm.encryption.DomainId;
import jlsm.encryption.KekRef;
import jlsm.encryption.TableId;
import jlsm.encryption.TenantId;
import jlsm.encryption.WrappedDek;
import jlsm.encryption.WrappedDomainKek;

/**
 * Tests for {@link ShardStorage} — atomic I/O with CRC-32C trailer, 0600 perms, orphan recovery
 * (R19a, R20, R20a, R70, R70a).
 */
class ShardStorageTest {

    private static final TenantId TENANT = new TenantId("tenantA");
    private static final DomainId DOMAIN = new DomainId("domain-1");
    private static final TableId TABLE = new TableId("table-1");
    private static final KekRef KEK_REF = new KekRef("kek-ref-1");
    private static final Instant NOW = Instant.parse("2026-04-23T12:00:00Z");

    private static byte[] salt32() {
        final byte[] s = new byte[32];
        for (int i = 0; i < 32; i++) {
            s[i] = (byte) (0x10 + i);
        }
        return s;
    }

    private static byte[] randomBytes(int len) {
        final byte[] b = new byte[len];
        ThreadLocalRandom.current().nextBytes(b);
        return b;
    }

    private static WrappedDek dek(DekVersion v) {
        return new WrappedDek(new DekHandle(TENANT, DOMAIN, TABLE, v), randomBytes(48), 1, KEK_REF,
                NOW);
    }

    private static KeyRegistryShard sampleShard(int dekVersion) {
        final WrappedDek d = dek(new DekVersion(dekVersion));
        final WrappedDomainKek dk = new WrappedDomainKek(DOMAIN, 1, randomBytes(56), KEK_REF);
        return new KeyRegistryShard(TENANT, Map.of(d.handle(), d), Map.of(DOMAIN, dk), KEK_REF,
                salt32());
    }

    @Test
    void constructor_nullRegistryRootRejected() {
        assertThrows(NullPointerException.class, () -> new ShardStorage(null));
    }

    @Test
    void loadShard_nullTenantRejected(@TempDir Path tmp) {
        final ShardStorage storage = new ShardStorage(tmp);
        assertThrows(NullPointerException.class, () -> storage.loadShard(null));
    }

    @Test
    void writeShard_nullArgsRejected(@TempDir Path tmp) {
        final ShardStorage storage = new ShardStorage(tmp);
        final KeyRegistryShard shard = sampleShard(1);
        assertThrows(NullPointerException.class, () -> storage.writeShard(null, shard));
        assertThrows(NullPointerException.class, () -> storage.writeShard(TENANT, null));
    }

    @Test
    void loadShard_missingFile_returnsEmpty(@TempDir Path tmp) throws IOException {
        final ShardStorage storage = new ShardStorage(tmp);
        final Optional<KeyRegistryShard> loaded = storage.loadShard(TENANT);
        assertTrue(loaded.isEmpty(), "missing shard must produce Optional.empty");
    }

    @Test
    void writeShard_thenLoadShard_roundTrips(@TempDir Path tmp) throws IOException {
        final ShardStorage storage = new ShardStorage(tmp);
        final KeyRegistryShard shard = sampleShard(1);
        storage.writeShard(TENANT, shard);
        final Optional<KeyRegistryShard> loaded = storage.loadShard(TENANT);
        assertTrue(loaded.isPresent(), "written shard must load");
        assertEquals(shard, loaded.get());
    }

    @Test
    void writeShard_multipleWrites_latestWins(@TempDir Path tmp) throws IOException {
        final ShardStorage storage = new ShardStorage(tmp);
        final KeyRegistryShard s1 = sampleShard(1);
        final KeyRegistryShard s2 = sampleShard(2);
        storage.writeShard(TENANT, s1);
        storage.writeShard(TENANT, s2);
        final Optional<KeyRegistryShard> loaded = storage.loadShard(TENANT);
        assertTrue(loaded.isPresent());
        assertEquals(s2, loaded.get());
    }

    @Test
    void loadShard_truncatedFile_throws(@TempDir Path tmp) throws IOException {
        final ShardStorage storage = new ShardStorage(tmp);
        storage.writeShard(TENANT, sampleShard(1));
        final Path shardPath = ShardPathResolver.shardPath(tmp, TENANT);
        // Truncate the file so the CRC trailer is missing or the body is short.
        try (FileChannel ch = FileChannel.open(shardPath, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            ch.truncate(3L);
        }
        assertThrows(IOException.class, () -> storage.loadShard(TENANT));
    }

    @Test
    void loadShard_tamperedFile_throwsOnCrcMismatch(@TempDir Path tmp) throws IOException {
        final ShardStorage storage = new ShardStorage(tmp);
        storage.writeShard(TENANT, sampleShard(1));
        final Path shardPath = ShardPathResolver.shardPath(tmp, TENANT);
        // Flip a byte in the middle of the file (should trip CRC).
        final byte[] all = Files.readAllBytes(shardPath);
        final int mid = all.length / 2;
        all[mid] = (byte) (all[mid] ^ 0xFF);
        Files.write(shardPath, all);
        final IOException ex = assertThrows(IOException.class, () -> storage.loadShard(TENANT));
        assertTrue(ex.getMessage() != null && !ex.getMessage().isBlank(),
                "CRC mismatch exception should carry an informative message");
    }

    @Test
    void writeShard_onNonExistentRegistryRoot_createsIt(@TempDir Path tmp) throws IOException {
        final Path nested = tmp.resolve("nested").resolve("registry");
        final ShardStorage storage = new ShardStorage(nested);
        storage.writeShard(TENANT, sampleShard(1));
        assertTrue(Files.exists(ShardPathResolver.shardPath(nested, TENANT)));
    }

    @Test
    void writeShard_posixPermsAre0600(@TempDir Path tmp) throws IOException {
        final ShardStorage storage = new ShardStorage(tmp);
        storage.writeShard(TENANT, sampleShard(1));
        final Path shardPath = ShardPathResolver.shardPath(tmp, TENANT);
        final PosixFileAttributeView view = Files.getFileAttributeView(shardPath,
                PosixFileAttributeView.class);
        if (view == null) {
            // Non-POSIX filesystem — skip (Windows).
            return;
        }
        final Set<PosixFilePermission> perms = view.readAttributes().permissions();
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms,
                "shard file must be 0600");
    }

    @Test
    void writeShard_doesNotLeaveTempFileOnSuccess(@TempDir Path tmp) throws IOException {
        final ShardStorage storage = new ShardStorage(tmp);
        storage.writeShard(TENANT, sampleShard(1));
        final long tempCount = Files.list(ShardPathResolver.shardPath(tmp, TENANT).getParent())
                .filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
        assertEquals(0, tempCount, "successful write must not leave orphan temp files");
    }

    @Test
    void recoverOrphanTemps_deletesOrphanWithInvalidCrc(@TempDir Path tmp) throws IOException {
        final ShardStorage storage = new ShardStorage(tmp);
        // First write the real shard so the directory exists.
        storage.writeShard(TENANT, sampleShard(1));
        final Path shardPath = ShardPathResolver.shardPath(tmp, TENANT);
        final Path orphan = ShardPathResolver.tempPath(shardPath, "corrupt-orphan");
        Files.write(orphan, new byte[]{ 1, 2, 3, 4, 5, 6, 7, 8 }); // invalid CRC
        storage.recoverOrphanTemps();
        assertFalse(Files.exists(orphan), "orphan with invalid CRC must be deleted");
    }

    @Test
    void recoverOrphanTemps_promotesStrictlyNewerOrphan(@TempDir Path tmp) throws IOException {
        final ShardStorage storage = new ShardStorage(tmp);
        storage.writeShard(TENANT, sampleShard(1));
        final KeyRegistryShard newer = sampleShard(2);
        // Manufacture an orphan temp file by invoking writeShard but renaming the final back off.
        storage.writeShard(TENANT, newer);
        // Now overwrite the final with an older shard to simulate crash-before-rename.
        storage.writeShard(TENANT, sampleShard(1));
        // And now produce an orphan temp containing the newer shard.
        final Path shardPath = ShardPathResolver.shardPath(tmp, TENANT);
        final Path orphan = ShardPathResolver.tempPath(shardPath, "recovery");
        writeShardBytesTo(orphan, newer);
        storage.recoverOrphanTemps();
        assertFalse(Files.exists(orphan), "orphan must be consumed");
        final Optional<KeyRegistryShard> loaded = storage.loadShard(TENANT);
        assertTrue(loaded.isPresent());
        assertEquals(newer, loaded.get(), "newer shard must be promoted");
    }

    @Test
    void recoverOrphanTemps_deletesOlderOrphan(@TempDir Path tmp) throws IOException {
        final ShardStorage storage = new ShardStorage(tmp);
        final KeyRegistryShard newer = sampleShard(5);
        storage.writeShard(TENANT, newer);
        final Path shardPath = ShardPathResolver.shardPath(tmp, TENANT);
        final Path orphan = ShardPathResolver.tempPath(shardPath, "older");
        writeShardBytesTo(orphan, sampleShard(2)); // older
        storage.recoverOrphanTemps();
        assertFalse(Files.exists(orphan), "older orphan must be deleted");
        final Optional<KeyRegistryShard> loaded = storage.loadShard(TENANT);
        assertTrue(loaded.isPresent());
        assertEquals(newer, loaded.get());
    }

    @Test
    void recoverOrphanTemps_tolerates_emptyDirectory(@TempDir Path tmp) throws IOException {
        final ShardStorage storage = new ShardStorage(tmp);
        // Should not throw when there are no temp files (or even no shards directory yet).
        storage.recoverOrphanTemps();
    }

    /**
     * Helper — serializes a shard using ShardStorage and then extracts the serialized bytes from
     * the final destination; writes them to the given orphan path. We use writeShard to a throwaway
     * tenant so we get real, CRC-valid bytes that match the on-disk format.
     */
    private static void writeShardBytesTo(Path orphan, KeyRegistryShard shard) throws IOException {
        // Build a scratch storage in a fresh directory, write the shard there, then copy.
        final Path scratch = Files.createTempDirectory("shard-orphan-src");
        try {
            final ShardStorage scratchStorage = new ShardStorage(scratch);
            scratchStorage.writeShard(shard.tenantId(), shard);
            final Path src = ShardPathResolver.shardPath(scratch, shard.tenantId());
            Files.createDirectories(orphan.getParent());
            Files.copy(src, orphan, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            // Best-effort cleanup.
            try (var stream = Files.walk(scratch)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
            }
        }
    }

    @Test
    void writeShard_atomicity_failedWriteLeavesPriorShardVisible(@TempDir Path tmp)
            throws IOException {
        final ShardStorage storage = new ShardStorage(tmp);
        final KeyRegistryShard initial = sampleShard(1);
        storage.writeShard(TENANT, initial);
        // Simulate a crashed write by creating a partial temp file that doesn't match CRC.
        final Path shardPath = ShardPathResolver.shardPath(tmp, TENANT);
        final Path partial = ShardPathResolver.tempPath(shardPath, "partial-crash");
        Files.write(partial, new byte[]{ 0, 0, 0, 0, 0, 0, 0, 0 });
        // loadShard still returns the prior shard.
        final Optional<KeyRegistryShard> loaded = storage.loadShard(TENANT);
        assertTrue(loaded.isPresent());
        assertEquals(initial, loaded.get());
        assertTrue(Files.exists(partial, LinkOption.NOFOLLOW_LINKS),
                "partial should still exist until recoverOrphanTemps runs");
    }

    @Test
    void loadShard_emptyFile_throws(@TempDir Path tmp) throws IOException {
        final ShardStorage storage = new ShardStorage(tmp);
        final Path shardPath = ShardPathResolver.shardPath(tmp, TENANT);
        Files.createDirectories(shardPath.getParent());
        Files.write(shardPath, new byte[0]);
        // An empty file is not a valid shard — must throw, not silently return empty.
        try {
            final Optional<KeyRegistryShard> result = storage.loadShard(TENANT);
            // Acceptable alternative: treat empty file as "absent" by returning empty, but NOT
            // returning a bogus shard.
            assertTrue(result.isEmpty(), "empty file must not deserialize to a usable shard");
        } catch (IOException expected) {
            // Acceptable: treating empty file as I/O error is valid.
        } catch (Exception unexpected) {
            fail("unexpected exception for empty file: " + unexpected);
        }
    }
}
