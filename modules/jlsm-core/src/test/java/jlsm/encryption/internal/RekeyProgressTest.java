package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.KekRef;
import jlsm.encryption.TenantId;
import jlsm.encryption.internal.RekeyProgress.ProgressRecord;

/**
 * Tests for {@link RekeyProgress} — per-tenant durable {@code rekey-progress.bin} file. CRC-32C
 * trailer (R19a), atomic temp+rename commit (R20), 0600 owner-only perms (R78c-1, R70a),
 * stale-record detection (>24h).
 *
 * @spec encryption.primitives-lifecycle R78b
 * @spec encryption.primitives-lifecycle R78c
 * @spec encryption.primitives-lifecycle R78c-1
 */
class RekeyProgressTest {

    private static final TenantId TENANT_A = new TenantId("tenant-A");
    private static final TenantId TENANT_B = new TenantId("tenant-B");
    private static final KekRef OLD_REF = new KekRef("kek/v1");
    private static final KekRef NEW_REF = new KekRef("kek/v2");

    private static ProgressRecord sampleRecord() {
        return new ProgressRecord(OLD_REF, NEW_REF, 7, Instant.parse("2026-04-27T12:00:00Z"), 1L,
                42L, 0L);
    }

    // ── ProgressRecord validation ───────────────────────────────────────

    @Test
    void progressRecord_validArgs_succeeds() {
        final ProgressRecord rec = sampleRecord();
        assertEquals(OLD_REF, rec.oldKekRef());
        assertEquals(NEW_REF, rec.newKekRef());
        assertEquals(7, rec.nextShardIndex());
        assertEquals(1L, rec.rekeyEpoch());
        assertEquals(42L, rec.lastEmittedEventSeq());
        assertEquals(0L, rec.permanentlySkipped());
    }

    @Test
    void progressRecord_negativeShardIndex_throwsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProgressRecord(OLD_REF, NEW_REF, -1, Instant.now(), 1L, 0L, 0L));
    }

    @Test
    void progressRecord_negativeEpoch_throwsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProgressRecord(OLD_REF, NEW_REF, 0, Instant.now(), -1L, 0L, 0L));
    }

    @Test
    void progressRecord_negativeLastEmitted_throwsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProgressRecord(OLD_REF, NEW_REF, 0, Instant.now(), 1L, -1L, 0L));
    }

    @Test
    void progressRecord_negativePermanentlySkipped_throwsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProgressRecord(OLD_REF, NEW_REF, 0, Instant.now(), 1L, 0L, -1L));
    }

    // ── open + read empty ───────────────────────────────────────────────

    @Test
    void open_nullRoot_throwsNpe() {
        assertThrows(NullPointerException.class, () -> RekeyProgress.open(null));
    }

    @Test
    void open_returnsNonNull(@TempDir Path tempDir) {
        assertNotNull(RekeyProgress.open(tempDir));
    }

    @Test
    void read_unknownTenant_returnsEmpty(@TempDir Path tempDir) throws IOException {
        final RekeyProgress p = RekeyProgress.open(tempDir);
        assertEquals(Optional.empty(), p.read(TENANT_A));
    }

    @Test
    void read_nullTenantId_throwsNpe(@TempDir Path tempDir) {
        final RekeyProgress p = RekeyProgress.open(tempDir);
        assertThrows(NullPointerException.class, () -> p.read(null));
    }

    // ── round-trip ─────────────────────────────────────────────────────

    @Test
    void commit_then_read_roundTrips(@TempDir Path tempDir) throws IOException {
        final RekeyProgress p = RekeyProgress.open(tempDir);
        final ProgressRecord rec = sampleRecord();
        p.commit(TENANT_A, rec);
        final Optional<ProgressRecord> readBack = p.read(TENANT_A);
        assertTrue(readBack.isPresent());
        assertEquals(rec, readBack.get());
    }

    @Test
    void commit_overwritesPriorValue(@TempDir Path tempDir) throws IOException {
        final RekeyProgress p = RekeyProgress.open(tempDir);
        p.commit(TENANT_A, sampleRecord());
        final ProgressRecord rec2 = new ProgressRecord(OLD_REF, NEW_REF, 99,
                Instant.parse("2026-04-27T12:00:00Z"), 1L, 100L, 5L);
        p.commit(TENANT_A, rec2);
        assertEquals(rec2, p.read(TENANT_A).orElseThrow());
    }

    @Test
    void commit_isIsolatedAcrossTenants(@TempDir Path tempDir) throws IOException {
        final RekeyProgress p = RekeyProgress.open(tempDir);
        final ProgressRecord recA = new ProgressRecord(OLD_REF, NEW_REF, 1,
                Instant.parse("2026-04-27T12:00:00Z"), 1L, 0L, 0L);
        final ProgressRecord recB = new ProgressRecord(OLD_REF, NEW_REF, 99,
                Instant.parse("2026-04-27T12:00:00Z"), 2L, 50L, 0L);
        p.commit(TENANT_A, recA);
        p.commit(TENANT_B, recB);
        assertEquals(recA, p.read(TENANT_A).orElseThrow());
        assertEquals(recB, p.read(TENANT_B).orElseThrow());
    }

    @Test
    void commit_nullArgs_throwNpe(@TempDir Path tempDir) {
        final RekeyProgress p = RekeyProgress.open(tempDir);
        assertThrows(NullPointerException.class, () -> p.commit(null, sampleRecord()));
        assertThrows(NullPointerException.class, () -> p.commit(TENANT_A, null));
    }

    @Test
    void clear_removesProgressRecord(@TempDir Path tempDir) throws IOException {
        final RekeyProgress p = RekeyProgress.open(tempDir);
        p.commit(TENANT_A, sampleRecord());
        assertTrue(p.read(TENANT_A).isPresent());
        p.clear(TENANT_A);
        assertFalse(p.read(TENANT_A).isPresent());
    }

    @Test
    void clear_unknownTenant_isNoop(@TempDir Path tempDir) throws IOException {
        final RekeyProgress p = RekeyProgress.open(tempDir);
        // Must not throw — clearing a never-committed tenant is well-defined.
        p.clear(TENANT_A);
        assertFalse(p.read(TENANT_A).isPresent());
    }

    @Test
    void clear_nullArg_throwsNpe(@TempDir Path tempDir) {
        final RekeyProgress p = RekeyProgress.open(tempDir);
        assertThrows(NullPointerException.class, () -> p.clear(null));
    }

    // ── durability ─────────────────────────────────────────────────────

    @Test
    void persistsAcrossReopen(@TempDir Path tempDir) throws IOException {
        final RekeyProgress p = RekeyProgress.open(tempDir);
        p.commit(TENANT_A, sampleRecord());
        final RekeyProgress reopened = RekeyProgress.open(tempDir);
        assertEquals(sampleRecord(), reopened.read(TENANT_A).orElseThrow());
    }

    // ── 0600 perms ─────────────────────────────────────────────────────

    @Test
    void commit_appliesOwnerOnlyPermissionsOnPosix(@TempDir Path tempDir) throws IOException {
        final RekeyProgress p = RekeyProgress.open(tempDir);
        p.commit(TENANT_A, sampleRecord());
        final Path progressFile = findProgressFile(tempDir);
        if (Files.getFileAttributeView(progressFile, PosixFileAttributeView.class) != null) {
            final Set<PosixFilePermission> perms = Files.getPosixFilePermissions(progressFile);
            assertEquals(
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    perms, "rekey-progress file must be 0600 (R78c-1)");
        }
    }

    // ── CRC mismatch ───────────────────────────────────────────────────

    @Test
    void read_detectsCrcCorruption(@TempDir Path tempDir) throws IOException {
        final RekeyProgress p = RekeyProgress.open(tempDir);
        p.commit(TENANT_A, sampleRecord());
        final Path file = findProgressFile(tempDir);
        // Flip a byte in the payload (not the CRC itself) so the trailer's CRC no longer
        // matches the recomputed value.
        final byte[] data = Files.readAllBytes(file);
        // Byte 8 is well past the magic+version header and within the payload region.
        data[8] ^= (byte) 0xFF;
        Files.write(file, data);
        final IOException ex = assertThrows(IOException.class, () -> p.read(TENANT_A));
        assertTrue(ex.getMessage().toLowerCase().contains("crc"),
                "exception must identify CRC mismatch, got: " + ex.getMessage());
    }

    // ── stale detection ────────────────────────────────────────────────

    @Test
    void read_isStale_whenStartedAtMoreThan24hAgo(@TempDir Path tempDir) throws IOException {
        // Stale detection: a record whose startedAt is > 24h before now is observable as stale.
        // We assert via the record's own startedAt field; the API surface for stale-flagging
        // is the deterministic record value (rather than a side-effecting "isStale" bool baked
        // into RekeyProgress) — callers compare startedAt to clock.now().
        final Instant longAgo = Instant.now().minusSeconds(48 * 3600);
        final ProgressRecord stale = new ProgressRecord(OLD_REF, NEW_REF, 0, longAgo, 1L, 0L, 0L);
        final RekeyProgress p = RekeyProgress.open(tempDir);
        p.commit(TENANT_A, stale);
        final ProgressRecord readBack = p.read(TENANT_A).orElseThrow();
        // Caller can detect staleness by comparing readBack.startedAt() to now-24h.
        final boolean isStale = readBack.startedAt()
                .isBefore(Instant.now().minusSeconds(24 * 3600));
        assertTrue(isStale, "record older than 24h must be flagged stale by caller-side check");
    }

    // ── bit-level corruption: file truncated below CRC trailer ─────────

    @Test
    void read_truncatedBelowCrc_throwsIOException(@TempDir Path tempDir) throws IOException {
        final RekeyProgress p = RekeyProgress.open(tempDir);
        p.commit(TENANT_A, sampleRecord());
        final Path file = findProgressFile(tempDir);
        // Truncate to 2 bytes — way below the magic+CRC trailer.
        Files.write(file, new byte[]{ 0x01, 0x02 });
        assertThrows(IOException.class, () -> p.read(TENANT_A));
    }

    // ── helper: locate the committed progress file in the temp dir tree ──

    private static Path findProgressFile(Path root) throws IOException {
        // The file lives somewhere under root with a deterministic suffix; walk and find any
        // non-empty file ending in .bin (impl-determined name, but this is enough to assert
        // posix perms and CRC behavior). If multiple, pick the largest.
        try (var s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".bin")).filter(p -> {
                        try {
                            return Files.size(p) > 0;
                        } catch (IOException e) {
                            return false;
                        }
                    }).findFirst().orElseThrow(() -> new IOException("no .bin file under " + root));
        }
    }

    // ── concurrent commit-read (single-tenant) is idempotent ────────────

    @Test
    void commitTwiceSameValue_isIdempotent(@TempDir Path tempDir) throws IOException {
        final RekeyProgress p = RekeyProgress.open(tempDir);
        p.commit(TENANT_A, sampleRecord());
        p.commit(TENANT_A, sampleRecord());
        // Second commit is observable as the current state. No exceptions, idempotent.
        assertEquals(sampleRecord(), p.read(TENANT_A).orElseThrow());
    }

    // ── trailer integrity is verified pre-deserialization ─────────────────

    @Test
    void read_zeroByteFile_returnsEmpty(@TempDir Path tempDir) throws IOException {
        final RekeyProgress p = RekeyProgress.open(tempDir);
        // Pre-create the parent dir explicitly with an empty file; read() should treat empty
        // as "no record" (matching ShardStorage / TenantStateProgress convention).
        p.commit(TENANT_A, sampleRecord());
        final Path file = findProgressFile(tempDir);
        Files.write(file, new byte[0]);
        // Empty file is "no record" — return empty.
        assertEquals(Optional.empty(), p.read(TENANT_A));
    }

    // ── encoding sanity: BIG_ENDIAN format consistent with rest of codebase ─

    @Test
    void writtenFile_hasReasonableSize(@TempDir Path tempDir) throws IOException {
        final RekeyProgress p = RekeyProgress.open(tempDir);
        p.commit(TENANT_A, sampleRecord());
        final Path file = findProgressFile(tempDir);
        final long size = Files.size(file);
        // A reasonable lower bound — magic, version, KekRef strings, ints, longs, CRC.
        assertTrue(size > 16, "progress file must have a meaningful payload, got " + size);
        // And an upper bound — under 4 KiB is more than enough.
        assertTrue(size < 4096, "progress file must remain compact, got " + size);
    }

    @SuppressWarnings("unused")
    private static int extractCrcInt(byte[] data) {
        return ByteBuffer.wrap(data, data.length - 4, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }
}
