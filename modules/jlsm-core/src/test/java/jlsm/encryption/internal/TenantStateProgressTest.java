package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.TenantId;
import jlsm.encryption.TenantState;

/**
 * Tests for {@link TenantStateProgress} (R76b-1a, R76b-2). Owner-only perms (R70a), atomic commit
 * (R20), CRC-32C trailer (R19a), conservative load on CRC mismatch (R76b-1a).
 *
 * @spec encryption.primitives-lifecycle R19a
 * @spec encryption.primitives-lifecycle R20
 * @spec encryption.primitives-lifecycle R70a
 * @spec encryption.primitives-lifecycle R76b-1a
 * @spec encryption.primitives-lifecycle R76b-2
 */
class TenantStateProgressTest {

    private static final TenantId TENANT_A = new TenantId("tenant-A");
    private static final TenantId TENANT_B = new TenantId("tenant-B");

    @Test
    void openWithNullRootThrowsNpe() {
        assertThrows(NullPointerException.class, () -> TenantStateProgress.open(null));
    }

    @Test
    void readMissingRecordReturnsEmpty(@TempDir Path tmp) throws IOException {
        final TenantStateProgress progress = TenantStateProgress.open(tmp);
        assertEquals(Optional.empty(), progress.read(TENANT_A));
    }

    @Test
    void readRejectsNullTenantId(@TempDir Path tmp) throws IOException {
        final TenantStateProgress progress = TenantStateProgress.open(tmp);
        assertThrows(NullPointerException.class, () -> progress.read(null));
    }

    @Test
    void commitRejectsNullTenantId(@TempDir Path tmp) throws IOException {
        final TenantStateProgress progress = TenantStateProgress.open(tmp);
        final TenantStateProgress.StateRecord rec = new TenantStateProgress.StateRecord(
                TenantState.HEALTHY, Instant.now(), 0L, 0L, null);
        assertThrows(NullPointerException.class, () -> progress.commit(null, rec));
    }

    @Test
    void commitRejectsNullRecord(@TempDir Path tmp) throws IOException {
        final TenantStateProgress progress = TenantStateProgress.open(tmp);
        assertThrows(NullPointerException.class, () -> progress.commit(TENANT_A, null));
    }

    @Test
    void commitThenReadRoundTripsHealthy(@TempDir Path tmp) throws IOException {
        final TenantStateProgress progress = TenantStateProgress.open(tmp);
        final Instant ts = Instant.parse("2026-04-27T10:00:00Z");
        final TenantStateProgress.StateRecord original = new TenantStateProgress.StateRecord(
                TenantState.HEALTHY, ts, 1L, 1L, null);
        progress.commit(TENANT_A, original);
        final Optional<TenantStateProgress.StateRecord> loaded = progress.read(TENANT_A);
        assertTrue(loaded.isPresent());
        assertEquals(TenantState.HEALTHY, loaded.get().state());
        assertEquals(ts, loaded.get().transitionAt());
        assertEquals(1L, loaded.get().eventSeq());
        assertEquals(1L, loaded.get().lastEmittedEventSeq());
        assertEquals(null, loaded.get().gracesEnteredAt());
    }

    @Test
    void commitThenReadRoundTripsGraceReadOnly(@TempDir Path tmp) throws IOException {
        final TenantStateProgress progress = TenantStateProgress.open(tmp);
        final Instant ts = Instant.parse("2026-04-27T10:00:00Z");
        final Instant graceTs = Instant.parse("2026-04-27T09:30:00Z");
        final TenantStateProgress.StateRecord original = new TenantStateProgress.StateRecord(
                TenantState.GRACE_READ_ONLY, ts, 5L, 4L, graceTs);
        progress.commit(TENANT_A, original);
        final Optional<TenantStateProgress.StateRecord> loaded = progress.read(TENANT_A);
        assertTrue(loaded.isPresent());
        assertEquals(TenantState.GRACE_READ_ONLY, loaded.get().state());
        assertEquals(graceTs, loaded.get().gracesEnteredAt());
        assertEquals(5L, loaded.get().eventSeq());
        assertEquals(4L, loaded.get().lastEmittedEventSeq());
    }

    @Test
    void commitThenReadRoundTripsFailed(@TempDir Path tmp) throws IOException {
        final TenantStateProgress progress = TenantStateProgress.open(tmp);
        final Instant ts = Instant.parse("2026-04-27T11:00:00Z");
        final TenantStateProgress.StateRecord original = new TenantStateProgress.StateRecord(
                TenantState.FAILED, ts, 10L, 9L, null);
        progress.commit(TENANT_A, original);
        final TenantStateProgress.StateRecord loaded = progress.read(TENANT_A).orElseThrow();
        assertEquals(TenantState.FAILED, loaded.state());
    }

    @Test
    void commitOverwritesPriorRecord(@TempDir Path tmp) throws IOException {
        final TenantStateProgress progress = TenantStateProgress.open(tmp);
        final Instant t1 = Instant.parse("2026-04-27T10:00:00Z");
        final Instant t2 = Instant.parse("2026-04-27T11:00:00Z");
        progress.commit(TENANT_A,
                new TenantStateProgress.StateRecord(TenantState.HEALTHY, t1, 1L, 1L, null));
        progress.commit(TENANT_A,
                new TenantStateProgress.StateRecord(TenantState.GRACE_READ_ONLY, t2, 2L, 2L, t2));
        final TenantStateProgress.StateRecord loaded = progress.read(TENANT_A).orElseThrow();
        assertEquals(TenantState.GRACE_READ_ONLY, loaded.state());
        assertEquals(t2, loaded.transitionAt());
    }

    @Test
    void perTenantIsolationSeparatesRecords(@TempDir Path tmp) throws IOException {
        final TenantStateProgress progress = TenantStateProgress.open(tmp);
        progress.commit(TENANT_A, new TenantStateProgress.StateRecord(TenantState.FAILED,
                Instant.parse("2026-04-27T10:00:00Z"), 1L, 1L, null));
        progress.commit(TENANT_B, new TenantStateProgress.StateRecord(TenantState.HEALTHY,
                Instant.parse("2026-04-27T10:00:00Z"), 1L, 1L, null));
        assertEquals(TenantState.FAILED, progress.read(TENANT_A).orElseThrow().state());
        assertEquals(TenantState.HEALTHY, progress.read(TENANT_B).orElseThrow().state());
    }

    @Test
    void commitWritesOwnerOnlyPermissionsOnPosix(@TempDir Path tmp) throws IOException {
        if (!isPosix(tmp)) {
            return; // R70a fallback path is best-effort on non-POSIX
        }
        final TenantStateProgress progress = TenantStateProgress.open(tmp);
        progress.commit(TENANT_A, new TenantStateProgress.StateRecord(TenantState.HEALTHY,
                Instant.now(), 0L, 0L, null));

        final Path file = findStateFile(tmp);
        final Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
        assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
        assertFalse(perms.contains(PosixFilePermission.GROUP_READ));
        assertFalse(perms.contains(PosixFilePermission.GROUP_WRITE));
        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
        assertFalse(perms.contains(PosixFilePermission.OTHERS_WRITE));
    }

    @Test
    void readDetectsCrcMismatch(@TempDir Path tmp) throws IOException {
        final TenantStateProgress progress = TenantStateProgress.open(tmp);
        progress.commit(TENANT_A, new TenantStateProgress.StateRecord(TenantState.HEALTHY,
                Instant.parse("2026-04-27T10:00:00Z"), 1L, 1L, null));

        final Path file = findStateFile(tmp);
        final byte[] bytes = Files.readAllBytes(file);
        // Flip a bit in the middle of the payload (before the CRC trailer).
        bytes[bytes.length / 2] ^= 0x01;
        Files.write(file, bytes);

        final IOException ex = assertThrows(IOException.class, () -> progress.read(TENANT_A));
        assertTrue(ex.getMessage().toLowerCase().contains("crc"),
                "CRC-mismatch IOException must mention crc, got: " + ex.getMessage());
    }

    @Test
    void readDetectsTruncation(@TempDir Path tmp) throws IOException {
        final TenantStateProgress progress = TenantStateProgress.open(tmp);
        progress.commit(TENANT_A, new TenantStateProgress.StateRecord(TenantState.HEALTHY,
                Instant.parse("2026-04-27T10:00:00Z"), 1L, 1L, null));

        final Path file = findStateFile(tmp);
        final byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, Math.max(1, bytes.length - 8)));

        assertThrows(IOException.class, () -> progress.read(TENANT_A));
    }

    @Test
    void emptyFileTreatedAsAbsent(@TempDir Path tmp) throws IOException {
        final TenantStateProgress progress = TenantStateProgress.open(tmp);
        progress.commit(TENANT_A, new TenantStateProgress.StateRecord(TenantState.HEALTHY,
                Instant.parse("2026-04-27T10:00:00Z"), 0L, 0L, null));
        final Path file = findStateFile(tmp);
        Files.write(file, new byte[0]);
        // Empty file is treated as no record (or surfaced as IOException — either is acceptable
        // per R76b-1a; the absent-record path is documented). We accept either.
        try {
            assertEquals(Optional.empty(), progress.read(TENANT_A));
        } catch (IOException expected) {
            // also acceptable
        }
    }

    @Test
    void stateRecordRejectsNullState() {
        assertThrows(NullPointerException.class,
                () -> new TenantStateProgress.StateRecord(null, Instant.now(), 0L, 0L, null));
    }

    @Test
    void stateRecordRejectsNullTransitionAt() {
        assertThrows(NullPointerException.class,
                () -> new TenantStateProgress.StateRecord(TenantState.HEALTHY, null, 0L, 0L, null));
    }

    @Test
    void stateRecordRejectsNegativeEventSeq() {
        assertThrows(IllegalArgumentException.class,
                () -> new TenantStateProgress.StateRecord(TenantState.HEALTHY, Instant.now(), -1L,
                        0L, null));
    }

    @Test
    void stateRecordRejectsNegativeLastEmittedEventSeq() {
        assertThrows(IllegalArgumentException.class,
                () -> new TenantStateProgress.StateRecord(TenantState.HEALTHY, Instant.now(), 0L,
                        -1L, null));
    }

    @Test
    void stateRecordPermitsNullGracesEnteredAt() {
        // Null grace-entry timestamp is documented as permitted (HEALTHY tenants never entered).
        new TenantStateProgress.StateRecord(TenantState.HEALTHY, Instant.now(), 0L, 0L, null);
    }

    @Test
    void atomicCommitDoesNotLeaveTempFiles(@TempDir Path tmp) throws IOException {
        final TenantStateProgress progress = TenantStateProgress.open(tmp);
        progress.commit(TENANT_A, new TenantStateProgress.StateRecord(TenantState.HEALTHY,
                Instant.parse("2026-04-27T10:00:00Z"), 1L, 1L, null));

        // Walk the tree; assert no .tmp files remain after the atomic commit completed.
        try (var stream = Files.walk(tmp)) {
            assertFalse(stream.anyMatch(p -> p.toString().endsWith(".tmp")),
                    "atomic commit must clean up its temp file (R20)");
        }
    }

    /**
     * Confirms that despite the file being hand-corrupted, we can detect via a sentinel byte — this
     * lets the higher-level state machine fall back to a conservative {@code FAILED} state on
     * R76b-1a CRC mismatch.
     */
    @Test
    void crcMismatchProducesIoExceptionThatHigherLayerMustHandle(@TempDir Path tmp)
            throws IOException {
        final TenantStateProgress progress = TenantStateProgress.open(tmp);
        progress.commit(TENANT_A, new TenantStateProgress.StateRecord(TenantState.HEALTHY,
                Instant.parse("2026-04-27T10:00:00Z"), 1L, 1L, null));
        final Path file = findStateFile(tmp);
        // Flip the CRC trailer bytes only.
        final byte[] bytes = Files.readAllBytes(file);
        ByteBuffer.wrap(bytes, bytes.length - 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(0xDEADBEEF);
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> progress.read(TENANT_A));
    }

    private static boolean isPosix(Path p) {
        return Files.getFileAttributeView(p, PosixFileAttributeView.class) != null;
    }

    private static Path findStateFile(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("state-progress.bin"))
                    .findFirst().orElseThrow(
                            () -> new IOException("state-progress.bin not found under " + root));
        }
    }
}
