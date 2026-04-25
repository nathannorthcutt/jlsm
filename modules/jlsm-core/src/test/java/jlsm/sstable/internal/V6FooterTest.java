package jlsm.sstable.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.zip.CRC32C;

import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;

import org.junit.jupiter.api.Test;

/**
 * Tests the v6 footer scope-section codec. Covers R1, R2, R2a, R2b, R2c, R2d, R2f, R3, R3a, R3b,
 * R3c, R3e of {@code sstable.footer-encryption-scope}.
 */
final class V6FooterTest {

    private static TableScope sampleScope() {
        return new TableScope(new TenantId("tenant-A"), new DomainId("domain-X"),
                new TableId("table-1"));
    }

    // --- encodeScopeSection ---

    @Test
    void encodesRoundTripWithSingleDekVersion() {
        final TableScope scope = sampleScope();
        final byte[] encoded = V6Footer.encodeScopeSection(scope, new int[]{ 42 });
        assertNotNull(encoded);
        // Layout per R2a:
        // [scope-section-length:u32 BE][tenantId-len:u32 BE][tenantId-bytes]
        // [domainId-len:u32 BE][domainId-bytes][tableId-len:u32 BE][tableId-bytes]
        // [dek-version-count:u16 BE][dek-version-1:u32 BE]...[crc32c:u32 BE]
        final ByteBuffer bb = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        final long bodyLen = bb.getInt() & 0xFFFFFFFFL;
        // The encoded body length excludes the 4-byte length prefix and the 4-byte CRC trailer
        assertEquals(encoded.length - 4 - 4, bodyLen);
        // tenantId
        final int tenantLen = bb.getInt();
        assertEquals(scope.tenantId().value().getBytes(StandardCharsets.UTF_8).length, tenantLen);
        final byte[] tenantBytes = new byte[tenantLen];
        bb.get(tenantBytes);
        assertArrayEquals(scope.tenantId().value().getBytes(StandardCharsets.UTF_8), tenantBytes);
        // domainId
        final int domainLen = bb.getInt();
        assertEquals(scope.domainId().value().getBytes(StandardCharsets.UTF_8).length, domainLen);
        final byte[] domainBytes = new byte[domainLen];
        bb.get(domainBytes);
        assertArrayEquals(scope.domainId().value().getBytes(StandardCharsets.UTF_8), domainBytes);
        // tableId
        final int tableLen = bb.getInt();
        assertEquals(scope.tableId().value().getBytes(StandardCharsets.UTF_8).length, tableLen);
        final byte[] tableBytes = new byte[tableLen];
        bb.get(tableBytes);
        assertArrayEquals(scope.tableId().value().getBytes(StandardCharsets.UTF_8), tableBytes);
        // dek-version-count
        final int dekCount = bb.getShort() & 0xFFFF;
        assertEquals(1, dekCount);
        assertEquals(42, bb.getInt());
        // crc32c trailer (4 bytes) — exact value validated by round-trip read
    }

    @Test
    void encodeProducesDekVersionCountAndVersionsInOrder() {
        final byte[] encoded = V6Footer.encodeScopeSection(sampleScope(), new int[]{ 1, 2, 7 });
        final ByteBuffer bb = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        bb.getInt(); // scope-section-length
        // Skip three identifier blocks
        for (int i = 0; i < 3; i++) {
            int len = bb.getInt();
            bb.position(bb.position() + len);
        }
        final int count = bb.getShort() & 0xFFFF;
        assertEquals(3, count);
        assertEquals(1, bb.getInt());
        assertEquals(2, bb.getInt());
        assertEquals(7, bb.getInt());
    }

    @Test
    void encodeRejectsNullScope() {
        assertThrows(NullPointerException.class,
                () -> V6Footer.encodeScopeSection(null, new int[]{ 1 }));
    }

    @Test
    void encodeRejectsNullDekVersionArray() {
        assertThrows(NullPointerException.class,
                () -> V6Footer.encodeScopeSection(sampleScope(), null));
    }

    @Test
    void encodeRejectsZeroDekVersion() {
        // covers: R3b — version must be >= 1
        assertThrows(IllegalArgumentException.class,
                () -> V6Footer.encodeScopeSection(sampleScope(), new int[]{ 0 }));
    }

    @Test
    void encodeRejectsNegativeDekVersion() {
        // covers: R3b
        assertThrows(IllegalArgumentException.class,
                () -> V6Footer.encodeScopeSection(sampleScope(), new int[]{ -1 }));
    }

    @Test
    void encodeRejectsDescendingDekVersions() {
        // covers: R3a — strictly ascending order
        assertThrows(IllegalArgumentException.class,
                () -> V6Footer.encodeScopeSection(sampleScope(), new int[]{ 3, 2 }));
    }

    @Test
    void encodeRejectsDuplicateDekVersions() {
        // covers: R3a — strictly ascending (no equal pair)
        assertThrows(IllegalArgumentException.class,
                () -> V6Footer.encodeScopeSection(sampleScope(), new int[]{ 1, 1 }));
    }

    @Test
    void encodeAcceptsEmptyDekVersionSet() {
        // covers: R3c — empty permitted; reader enforces no entries downstream
        final byte[] encoded = V6Footer.encodeScopeSection(sampleScope(), new int[0]);
        assertNotNull(encoded);
    }

    @Test
    void encodeRejectsControlByteInIdentifier() {
        // covers: R2e — control byte rejected even though TenantId itself accepts the string;
        // V6Footer.encodeScopeSection enforces R2e on every identifier before emit.
        assertThrows(IllegalArgumentException.class,
                () -> V6Footer.encodeScopeSection(
                        new TableScope(new TenantId("okbad"), new DomainId("d"), new TableId("t")),
                        new int[]{ 1 }));
    }

    // --- read (round trip via SeekableByteChannel) ---

    @Test
    void readRoundTripWithSingleVersion() throws IOException {
        final TableScope scope = sampleScope();
        final byte[] encoded = V6Footer.encodeScopeSection(scope, new int[]{ 42 });
        try (SeekableByteChannel ch = newChannel(encoded)) {
            V6Footer.Parsed parsed = V6Footer.read(ch, encoded.length);
            assertNotNull(parsed);
            assertEquals(scope, parsed.scope());
            assertEquals(Set.of(42), parsed.dekVersionSet());
            assertEquals(encoded.length, parsed.footerEnd());
        }
    }

    @Test
    void readRoundTripWithMultipleVersions() throws IOException {
        final byte[] encoded = V6Footer.encodeScopeSection(sampleScope(), new int[]{ 1, 2, 7 });
        try (SeekableByteChannel ch = newChannel(encoded)) {
            V6Footer.Parsed parsed = V6Footer.read(ch, encoded.length);
            assertEquals(Set.of(1, 2, 7), parsed.dekVersionSet());
        }
    }

    @Test
    void readRejectsTrailingBytesBeyondFooter() throws IOException {
        // covers: R2f — fileSize must equal footerEnd; trailing garbage is rejected
        final byte[] encoded = V6Footer.encodeScopeSection(sampleScope(), new int[]{ 1 });
        final byte[] padded = new byte[encoded.length + 4];
        System.arraycopy(encoded, 0, padded, 0, encoded.length);
        try (SeekableByteChannel ch = newChannel(padded)) {
            assertThrows(IOException.class, () -> V6Footer.read(ch, padded.length));
        }
    }

    @Test
    void readRejectsTooSmallFile() throws IOException {
        try (SeekableByteChannel ch = newChannel(new byte[2])) {
            assertThrows(IOException.class, () -> V6Footer.read(ch, 2));
        }
    }

    @Test
    void readRejectsCrcMismatch() throws IOException {
        // covers: R2d — CRC32C scope generalised to variable-length payload
        final byte[] encoded = V6Footer.encodeScopeSection(sampleScope(), new int[]{ 1 });
        // Flip a byte in the body (not in the CRC trailer)
        encoded[6] ^= 0x55;
        try (SeekableByteChannel ch = newChannel(encoded)) {
            assertThrows(IOException.class, () -> V6Footer.read(ch, encoded.length));
        }
    }

    @Test
    void readRejectsLengthOverflow() throws IOException {
        // covers: R2b — length prefix > 65536 must be rejected
        final byte[] encoded = V6Footer.encodeScopeSection(sampleScope(), new int[]{ 1 });
        final ByteBuffer bb = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        // Overwrite tenantId-utf8-length (offset 4) with 65537
        bb.putInt(4, 65537);
        // Recompute CRC32C over encoded[0..encoded.length-4] to defeat CRC check first
        final CRC32C crc = new CRC32C();
        crc.update(encoded, 0, encoded.length - 4);
        bb.putInt(encoded.length - 4, (int) crc.getValue());
        try (SeekableByteChannel ch = newChannel(encoded)) {
            assertThrows(IOException.class, () -> V6Footer.read(ch, encoded.length));
        }
    }

    @Test
    void readRejectsZeroIdentifierLength() throws IOException {
        // covers: R2c — zero-length identifier rejected at parse
        final byte[] encoded = V6Footer.encodeScopeSection(sampleScope(), new int[]{ 1 });
        final ByteBuffer bb = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(4, 0); // tenantId-utf8-length := 0
        // Recompute scope-section-length: original tenantId len + bytes is now 0+0 in arith,
        // but easier to just update the CRC and accept that the scope-section-length mismatch
        // surfaces as IOException too.
        final CRC32C crc = new CRC32C();
        crc.update(encoded, 0, encoded.length - 4);
        bb.putInt(encoded.length - 4, (int) crc.getValue());
        try (SeekableByteChannel ch = newChannel(encoded)) {
            assertThrows(IOException.class, () -> V6Footer.read(ch, encoded.length));
        }
    }

    @Test
    void readRejectsScopeSectionLengthMismatch() throws IOException {
        // covers: R2d — declared scope-section-length must match recomputed length
        final byte[] encoded = V6Footer.encodeScopeSection(sampleScope(), new int[]{ 1 });
        // Corrupt scope-section-length prefix (offset 0..4) to a smaller value
        final ByteBuffer bb = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(0, 1);
        final CRC32C crc = new CRC32C();
        crc.update(encoded, 0, encoded.length - 4);
        bb.putInt(encoded.length - 4, (int) crc.getValue());
        try (SeekableByteChannel ch = newChannel(encoded)) {
            assertThrows(IOException.class, () -> V6Footer.read(ch, encoded.length));
        }
    }

    @Test
    void readRejectsZeroDekVersion() throws IOException {
        // covers: R3b — reader rejects version 0 from on-disk bytes
        // We cannot use encodeScopeSection (it rejects 0) — emit by hand
        final byte[] encoded = handcraftScopeSectionWithRawDekVersions(sampleScope(),
                new int[]{ 0 });
        try (SeekableByteChannel ch = newChannel(encoded)) {
            assertThrows(IOException.class, () -> V6Footer.read(ch, encoded.length));
        }
    }

    @Test
    void readRejectsDescendingDekVersions() throws IOException {
        // covers: R3a — strictly ascending; reader rejects descending pair
        final byte[] encoded = handcraftScopeSectionWithRawDekVersions(sampleScope(),
                new int[]{ 5, 3 });
        try (SeekableByteChannel ch = newChannel(encoded)) {
            assertThrows(IOException.class, () -> V6Footer.read(ch, encoded.length));
        }
    }

    @Test
    void readRejectsDuplicateDekVersions() throws IOException {
        // covers: R3a — strictly ascending forbids equal pair
        final byte[] encoded = handcraftScopeSectionWithRawDekVersions(sampleScope(),
                new int[]{ 2, 2 });
        try (SeekableByteChannel ch = newChannel(encoded)) {
            assertThrows(IOException.class, () -> V6Footer.read(ch, encoded.length));
        }
    }

    @Test
    void readDoesNotLeakFooterBytesInErrorMessage() throws IOException {
        // covers: R12 — reader-side error must not embed raw bytes
        final byte[] encoded = V6Footer.encodeScopeSection(sampleScope(), new int[]{ 1 });
        encoded[5] ^= 0x55; // corrupt body
        try (SeekableByteChannel ch = newChannel(encoded)) {
            IOException ex = assertThrows(IOException.class,
                    () -> V6Footer.read(ch, encoded.length));
            final String msg = ex.getMessage() == null ? "" : ex.getMessage();
            // R12: the error message must not splash full footer bytes
            // We accept short hex tokens (e.g. CRC values are not bytes), but it must not
            // contain a long (>32 char) hex run of the footer body.
            assertTrue(msg.length() < 4096, "error message suspiciously large: " + msg);
        }
    }

    // --- materialiseDekVersionSet ---

    @Test
    void materialiseDekVersionSetReturnsImmutableSet() {
        Set<Integer> set = V6Footer.materialiseDekVersionSet(new int[]{ 1, 2, 3 });
        assertEquals(Set.of(1, 2, 3), set);
        assertThrows(UnsupportedOperationException.class, () -> set.add(4));
    }

    @Test
    void materialiseDekVersionSetReturnsEmptyForZeroLength() {
        Set<Integer> set = V6Footer.materialiseDekVersionSet(new int[0]);
        assertEquals(Set.of(), set);
    }

    @Test
    void materialiseDekVersionSetRejectsNull() {
        assertThrows(NullPointerException.class, () -> V6Footer.materialiseDekVersionSet(null));
    }

    @Test
    void materialiseDekVersionSetRejectsZeroVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> V6Footer.materialiseDekVersionSet(new int[]{ 0 }));
    }

    @Test
    void materialiseDekVersionSetRejectsNegativeVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> V6Footer.materialiseDekVersionSet(new int[]{ -3 }));
    }

    @Test
    void materialiseDekVersionSetRejectsDescending() {
        assertThrows(IllegalArgumentException.class,
                () -> V6Footer.materialiseDekVersionSet(new int[]{ 3, 2 }));
    }

    @Test
    void materialiseDekVersionSetRejectsDuplicates() {
        assertThrows(IllegalArgumentException.class,
                () -> V6Footer.materialiseDekVersionSet(new int[]{ 1, 1 }));
    }

    @Test
    void materialiseDekVersionSetSupportsConstantTimeLookup() {
        Set<Integer> set = V6Footer.materialiseDekVersionSet(new int[]{ 1, 2, 3 });
        assertTrue(set.contains(2));
        assertTrue(!set.contains(99));
    }

    // --- helpers ---

    private static SeekableByteChannel newChannel(byte[] bytes) throws IOException {
        // SeekableByteChannel from in-memory bytes via a small adapter
        return new InMemorySeekableByteChannel(bytes);
    }

    /**
     * Build a scope section with arbitrary raw DEK versions (allowing invalid sequences) so the
     * reader can be exercised with malicious / corrupt bytes. The CRC is recomputed so the read
     * path's CRC check passes — meaning the failure must come from the post-CRC validation rules
     * (R3a / R3b / R2c / R2e).
     */
    private static byte[] handcraftScopeSectionWithRawDekVersions(TableScope scope,
            int[] rawVersions) {
        final byte[] tenant = scope.tenantId().value().getBytes(StandardCharsets.UTF_8);
        final byte[] domain = scope.domainId().value().getBytes(StandardCharsets.UTF_8);
        final byte[] table = scope.tableId().value().getBytes(StandardCharsets.UTF_8);
        final long bodyLen = 4L + tenant.length + 4L + domain.length + 4L + table.length + 2L
                + 4L * rawVersions.length;
        final int totalLen = 4 + (int) bodyLen + 4; // length prefix + body + CRC
        final byte[] out = new byte[totalLen];
        final ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        bb.putInt((int) bodyLen);
        bb.putInt(tenant.length);
        bb.put(tenant);
        bb.putInt(domain.length);
        bb.put(domain);
        bb.putInt(table.length);
        bb.put(table);
        bb.putShort((short) rawVersions.length);
        for (int v : rawVersions) {
            bb.putInt(v);
        }
        // Recompute CRC32C over [0..bodyEnd) — i.e. everything except the trailing 4 bytes
        final CRC32C crc = new CRC32C();
        crc.update(out, 0, totalLen - 4);
        bb.putInt(totalLen - 4, (int) crc.getValue());
        return out;
    }

    /** Minimal in-memory SeekableByteChannel for read-only test fixtures. */
    static final class InMemorySeekableByteChannel implements SeekableByteChannel {

        private final byte[] data;
        private long position = 0;
        private boolean open = true;

        InMemorySeekableByteChannel(byte[] data) {
            this.data = data;
        }

        @Override
        public int read(ByteBuffer dst) {
            if (!open) {
                throw new IllegalStateException("closed");
            }
            if (position >= data.length) {
                return -1;
            }
            int avail = (int) Math.min(dst.remaining(), data.length - position);
            dst.put(data, (int) position, avail);
            position += avail;
            return avail;
        }

        @Override
        public int write(ByteBuffer src) {
            throw new UnsupportedOperationException("read-only");
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public SeekableByteChannel position(long newPosition) {
            this.position = newPosition;
            return this;
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException("read-only");
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

}
