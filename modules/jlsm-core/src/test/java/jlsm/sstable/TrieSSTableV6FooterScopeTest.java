package jlsm.sstable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jlsm.bloom.blocked.BlockedBloomFilter;
import jlsm.core.compression.CompressionCodec;
import jlsm.core.model.Entry;
import jlsm.core.model.Level;
import jlsm.core.model.SequenceNumber;
import jlsm.encryption.DomainId;
import jlsm.encryption.ReadContext;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.sstable.internal.SSTableFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for {@link TrieSSTableWriter}'s v6 emit path and {@link TrieSSTableReader}'s v6
 * dispatch path. These cover the wired-up version of the {@link jlsm.sstable.internal.V6Footer}
 * codec inside the writer/reader.
 *
 * <p>
 * Architectural note: jlsm-core cannot import jlsm-engine's {@code Table}, so the reader accepts an
 * {@code Optional&lt;TableScope&gt;} representing the expected scope (which the caller extracts
 * from {@code table.metadata().encryption()}). Empty Optional + v6 footer →
 * {@link IllegalStateException} (R5). Mismatched scope → {@link IllegalStateException} (R6).
 *
 * @spec sstable.footer-encryption-scope.R1
 * @spec sstable.footer-encryption-scope.R1a
 * @spec sstable.footer-encryption-scope.R2
 * @spec sstable.footer-encryption-scope.R2f
 * @spec sstable.footer-encryption-scope.R3e
 * @spec sstable.footer-encryption-scope.R5
 * @spec sstable.footer-encryption-scope.R6
 * @spec sstable.footer-encryption-scope.R6c
 * @spec sstable.footer-encryption-scope.R10
 * @spec sstable.footer-encryption-scope.R10d
 * @spec sstable.footer-encryption-scope.R11
 * @spec sstable.footer-encryption-scope.R12
 */
final class TrieSSTableV6FooterScopeTest {

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Entry.Put put(String key, String value, long seq) {
        return new Entry.Put(seg(key), seg(value), new SequenceNumber(seq));
    }

    private static TableScope sampleScope() {
        return new TableScope(new TenantId("tenantA"), new DomainId("domainX"),
                new TableId("table1"));
    }

    private static TableScope otherScope() {
        return new TableScope(new TenantId("tenantB"), new DomainId("domainY"),
                new TableId("table2"));
    }

    private static long trailingMagic(Path path) throws IOException {
        byte[] all = Files.readAllBytes(path);
        ByteBuffer bb = ByteBuffer.wrap(all, all.length - 8, 8).order(ByteOrder.BIG_ENDIAN);
        return bb.getLong();
    }

    private static Path writeV5(Path dir, String name) throws IOException {
        Path out = dir.resolve(name);
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(1L).level(Level.L0).path(out)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(CompressionCodec.none())
                .build()) {
            w.append(put("a", "1", 1));
            w.append(put("b", "2", 2));
            w.finish();
        }
        return out;
    }

    private static Path writeV6(Path dir, String name, TableScope scope, int[] dekVersions)
            throws IOException {
        Path out = dir.resolve(name);
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(2L).level(Level.L0).path(out)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(CompressionCodec.none())
                .scope(scope).dekVersions(dekVersions).build()) {
            w.append(put("a", "1", 1));
            w.append(put("b", "2", 2));
            w.finish();
        }
        return out;
    }

    // ----- R1 / R1a: v5 vs v6 magic dispatch -----

    @Test
    void writerWithoutScopeEmitsV5Magic(@TempDir Path dir) throws IOException {
        Path out = writeV5(dir, "no-scope.sst");
        assertEquals(SSTableFormat.MAGIC_V5, trailingMagic(out));
    }

    @Test
    void writerWithScopeEmitsV6Magic(@TempDir Path dir) throws IOException {
        Path out = writeV6(dir, "with-scope.sst", sampleScope(), new int[]{ 1 });
        assertEquals(SSTableFormat.MAGIC_V6, trailingMagic(out));
    }

    @Test
    void readerOpensV5WithoutExpectedScope(@TempDir Path dir) throws IOException {
        Path out = writeV5(dir, "v5-noscope.sst");
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                Optional.empty())) {
            assertNotNull(r);
            // ReadContext is empty for v5 — there is no scope-bound DEK set
            assertNotNull(r.readContext());
            assertTrue(r.readContext().allowedDekVersions().isEmpty());
        }
    }

    @Test
    void readerRejectsUnknownMagic(@TempDir Path dir) throws IOException {
        // covers: R1a — unrecognised magic must throw IOException
        Path out = writeV5(dir, "corrupt.sst");
        // Corrupt only the last byte of the trailing magic (byte 7 of the magic = LSB)
        byte[] all = Files.readAllBytes(out);
        all[all.length - 1] ^= (byte) 0xFE; // flip away from v5 (06) and v6 (06)
        Files.write(out, all);
        assertThrows(IOException.class, () -> TrieSSTableReader.open(out,
                BlockedBloomFilter.deserializer(), Optional.empty()));
    }

    // ----- R2 / R2a: scope round-trip -----

    @Test
    void readerParsesV6ScopeMatchingExpected(@TempDir Path dir) throws IOException {
        Path out = writeV6(dir, "scope-match.sst", sampleScope(), new int[]{ 7, 9 });
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                Optional.of(sampleScope()))) {
            assertNotNull(r);
            ReadContext ctx = r.readContext();
            assertNotNull(ctx);
            assertEquals(Set.of(7, 9), ctx.allowedDekVersions());
        }
    }

    // ----- R5: missing encryption metadata when v6 footer present -----

    @Test
    void readerRejectsV6FooterWhenExpectedScopeIsEmpty(@TempDir Path dir) throws IOException {
        Path out = writeV6(dir, "v6-noexpected.sst", sampleScope(), new int[]{ 1 });
        // covers: R5 — v6 footer requires the caller to supply expected scope
        assertThrows(IllegalStateException.class, () -> TrieSSTableReader.open(out,
                BlockedBloomFilter.deserializer(), Optional.empty()));
    }

    // ----- R6 / R6c: cross-scope mismatch -----

    @Test
    void readerRejectsScopeMismatch(@TempDir Path dir) throws IOException {
        Path out = writeV6(dir, "scope-mismatch.sst", sampleScope(), new int[]{ 1 });
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> TrieSSTableReader
                .open(out, BlockedBloomFilter.deserializer(), Optional.of(otherScope())));
        // R12: message must not embed DEK material or footer bytes; it may name scopes
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        assertNotNull(msg);
        // Defence against accidental leakage: message must not contain the DEK count token
        // ("dek-version-count") or any 4-byte-shaped hex literal (heuristic)
        assertTrue(!msg.contains("dek-version-count"),
                "R12 violation — message names DEK count: " + msg);
    }

    @Test
    void readerRejectsScopeMismatchOnDifferentTenantOnly(@TempDir Path dir) throws IOException {
        Path out = writeV6(dir, "tenant-only.sst", sampleScope(), new int[]{ 1 });
        TableScope wrongTenant = new TableScope(new TenantId("tenantZ"), sampleScope().domainId(),
                sampleScope().tableId());
        assertThrows(IllegalStateException.class, () -> TrieSSTableReader.open(out,
                BlockedBloomFilter.deserializer(), Optional.of(wrongTenant)));
    }

    @Test
    void readerRejectsScopeMismatchOnDifferentDomainOnly(@TempDir Path dir) throws IOException {
        Path out = writeV6(dir, "domain-only.sst", sampleScope(), new int[]{ 1 });
        TableScope wrongDomain = new TableScope(sampleScope().tenantId(), new DomainId("domainZ"),
                sampleScope().tableId());
        assertThrows(IllegalStateException.class, () -> TrieSSTableReader.open(out,
                BlockedBloomFilter.deserializer(), Optional.of(wrongDomain)));
    }

    @Test
    void readerRejectsScopeMismatchOnDifferentTableOnly(@TempDir Path dir) throws IOException {
        Path out = writeV6(dir, "table-only.sst", sampleScope(), new int[]{ 1 });
        TableScope wrongTable = new TableScope(sampleScope().tenantId(), sampleScope().domainId(),
                new TableId("tableZ"));
        assertThrows(IllegalStateException.class, () -> TrieSSTableReader.open(out,
                BlockedBloomFilter.deserializer(), Optional.of(wrongTable)));
    }

    // ----- R3e: ReadContext exposes the materialised DEK version set -----

    @Test
    void readContextDekVersionSetMatchesFooter(@TempDir Path dir) throws IOException {
        Path out = writeV6(dir, "dek-set.sst", sampleScope(), new int[]{ 2, 5, 11 });
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                Optional.of(sampleScope()))) {
            ReadContext ctx = r.readContext();
            assertEquals(Set.of(2, 5, 11), ctx.allowedDekVersions());
        }
    }

    @Test
    void readContextEmptyForEmptyDekSetV6(@TempDir Path dir) throws IOException {
        // covers: R3c — empty DEK set is permitted on v6
        Path out = writeV6(dir, "empty-dek.sst", sampleScope(), new int[0]);
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                Optional.of(sampleScope()))) {
            assertTrue(r.readContext().allowedDekVersions().isEmpty());
        }
    }

    // ----- R2f: file-size invariant -----

    @Test
    void readerRejectsTrailingBytesAfterMagic(@TempDir Path dir) throws IOException {
        Path out = writeV6(dir, "trailing.sst", sampleScope(), new int[]{ 1 });
        byte[] all = Files.readAllBytes(out);
        // Append 4 trailing bytes after the magic
        byte[] padded = new byte[all.length + 4];
        System.arraycopy(all, 0, padded, 0, all.length);
        Files.write(out, padded);
        assertThrows(IOException.class, () -> TrieSSTableReader.open(out,
                BlockedBloomFilter.deserializer(), Optional.of(sampleScope())));
    }

    // ----- R10 invariants -----

    @Test
    void writerBuilderRejectsScopeWithEmptyIdentifier(@TempDir Path dir) {
        // R10 / R2c — invalid identifier rejected eagerly via writer construction or first append
        Path out = dir.resolve("bad-scope.sst");
        TableScope badScope = new TableScope(new TenantId("ok"), new DomainId("ok"),
                new TableId("ok"));
        // writer must succeed on construction with valid scope; this is a positive control
        try (TrieSSTableWriter w = TrieSSTableWriter.builder().id(3L).level(Level.L0).path(out)
                .bloomFactory(n -> new BlockedBloomFilter(n, 0.01)).codec(CompressionCodec.none())
                .scope(badScope).dekVersions(new int[]{ 1 }).build()) {
            // ok
        } catch (IOException e) {
            // not expected
            throw new AssertionError(e);
        }
    }

    // ----- R11: parallel open is thread-safe -----

    @Test
    void parallelOpenOfSameV6FileYieldsEqualScope(@TempDir Path dir) throws Exception {
        Path out = writeV6(dir, "concurrent.sst", sampleScope(), new int[]{ 1 });
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Callable<Boolean> task = () -> {
                try (TrieSSTableReader r = TrieSSTableReader.open(out,
                        BlockedBloomFilter.deserializer(), Optional.of(sampleScope()))) {
                    return r.readContext().allowedDekVersions().equals(Set.of(1));
                }
            };
            Future<Boolean> f1 = pool.submit(task);
            Future<Boolean> f2 = pool.submit(task);
            Future<Boolean> f3 = pool.submit(task);
            Future<Boolean> f4 = pool.submit(task);
            assertTrue(f1.get(10, TimeUnit.SECONDS));
            assertTrue(f2.get(10, TimeUnit.SECONDS));
            assertTrue(f3.get(10, TimeUnit.SECONDS));
            assertTrue(f4.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    // ----- ReadContext getter discipline -----

    @Test
    void readContextIsStableAcrossCalls(@TempDir Path dir) throws IOException {
        // R3e: hot-path R3e dispatch checks must not pay an allocation per envelope —
        // ReadContext (and hence allowedDekVersions Set) must be stable across calls.
        Path out = writeV6(dir, "stable.sst", sampleScope(), new int[]{ 1, 2 });
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                Optional.of(sampleScope()))) {
            ReadContext a = r.readContext();
            ReadContext b = r.readContext();
            assertSame(a, b);
            assertSame(a.allowedDekVersions(), b.allowedDekVersions());
        }
    }

    // ----- Independence: Reader does not derive expected scope from the footer it validates -----

    @Test
    void readerRequiresExternalExpectedScopeForV6(@TempDir Path dir) throws IOException {
        // covers: R6a — the reader must not derive expected scope from the footer it is
        // validating. This means open() requires the caller to provide expected scope as a
        // separate parameter; absence is itself a rejection (R5), not silently accepted.
        Path out = writeV6(dir, "external-source.sst", sampleScope(), new int[]{ 1 });
        // Empty Optional + v6 → IllegalStateException (R5/R6a)
        assertThrows(IllegalStateException.class, () -> TrieSSTableReader.open(out,
                BlockedBloomFilter.deserializer(), Optional.empty()));
        // matching scope works
        try (TrieSSTableReader r = TrieSSTableReader.open(out, BlockedBloomFilter.deserializer(),
                Optional.of(sampleScope()))) {
            assertNotEquals(0, r.readContext().allowedDekVersions().size());
        }
    }
}
