package jlsm.sstable.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32C;

import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.encryption.internal.IdentifierValidator;

/**
 * v6 footer scope-section codec. The v6 footer extends v5 with a fixed-position scope section
 * appended after the v5 sections. The scope section records the {@link TableScope} identifier
 * triple plus the set of DEK versions referenced by the SSTable's ciphertext, and is covered by a
 * CRC32C generalised to the variable-length scope payload.
 *
 * <p>
 * Receives: byte streams off a {@link SeekableByteChannel} (read), or a {@link TableScope} + sorted
 * DEK-version array (encode).<br>
 * Returns: encoded scope bytes (encode), or a {@link Parsed} view containing scope, materialised
 * DEK-version {@link Set}, and footer end offset (read).<br>
 * Side effects: read advances channel position.<br>
 * Error conditions: {@link IOException} on malformed footer, identifier rule violations, file-size
 * invariant breach (R2f), or DEK-set ordering invariant breach.<br>
 * Shared state: none.
 *
 * <p>
 * Governing spec: {@code sstable.footer-encryption-scope} R1, R2, R2a, R2b, R2c, R2d, R2f, R3, R3a,
 * R3b, R3c, R3e.
 *
 * @spec sstable.footer-encryption-scope.R1
 * @spec sstable.footer-encryption-scope.R2
 * @spec sstable.footer-encryption-scope.R2a
 * @spec sstable.footer-encryption-scope.R2b
 * @spec sstable.footer-encryption-scope.R2c
 * @spec sstable.footer-encryption-scope.R2d
 * @spec sstable.footer-encryption-scope.R2f
 * @spec sstable.footer-encryption-scope.R3
 * @spec sstable.footer-encryption-scope.R3a
 * @spec sstable.footer-encryption-scope.R3b
 * @spec sstable.footer-encryption-scope.R3c
 * @spec sstable.footer-encryption-scope.R3e
 */
public final class V6Footer {

    /** Maximum permissible DEK-version-count (u16 BE — R3 ceiling). */
    public static final int MAX_DEK_VERSION_COUNT = 0xFFFF;

    private V6Footer() {
    }

    /**
     * Encodes a v6 footer scope section to bytes. The encoded form starts with the three
     * length-prefixed UTF-8 identifiers, followed by the sorted DEK-version array, and ends with
     * the CRC32C of all preceding scope-section bytes.
     *
     * @param scope non-null table scope identity triple
     * @param sortedDekVersions non-null, strictly-ascending array of positive DEK versions
     *            referenced by the SSTable
     * @return scope-section byte array suitable for appending to a v5 footer
     * @throws NullPointerException if {@code scope} or {@code sortedDekVersions} is null
     * @throws IllegalArgumentException if any identifier fails
     *             {@link jlsm.encryption.internal.IdentifierValidator#validateForWrite}, if
     *             {@code sortedDekVersions} contains non-positive entries, or is not strictly
     *             ascending
     * @spec sstable.footer-encryption-scope.R2
     * @spec sstable.footer-encryption-scope.R2a
     * @spec sstable.footer-encryption-scope.R10d
     */
    public static byte[] encodeScopeSection(TableScope scope, int[] sortedDekVersions) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(sortedDekVersions, "sortedDekVersions must not be null");
        // Snapshot the caller-supplied array so validation and encoding observe an
        // identical sequence of values (closes a TOCTOU window: without the clone, a
        // caller mutating the array between validateDekVersionsForWrite and the encode
        // loop below could ship a footer whose CRC covers non-ascending or non-positive
        // versions, violating R3a/R3b at the read side).
        final int[] dekVersions = sortedDekVersions.clone();
        // Validate identifiers (R2b/R2c/R2e via IdentifierValidator).
        IdentifierValidator.validateForWrite(scope.tenantId().value(), "tenantId");
        IdentifierValidator.validateForWrite(scope.domainId().value(), "domainId");
        IdentifierValidator.validateForWrite(scope.tableId().value(), "tableId");
        // Validate DEK versions (R3a/R3b) on the snapshot.
        validateDekVersionsForWrite(dekVersions);
        if (dekVersions.length > MAX_DEK_VERSION_COUNT) {
            throw new IllegalStateException("DEK-version-count exceeds u16 maximum: "
                    + dekVersions.length + " > " + MAX_DEK_VERSION_COUNT);
        }
        // Encode bytes.
        final byte[] tenant = scope.tenantId().value().getBytes(StandardCharsets.UTF_8);
        final byte[] domain = scope.domainId().value().getBytes(StandardCharsets.UTF_8);
        final byte[] table = scope.tableId().value().getBytes(StandardCharsets.UTF_8);
        // Body length = (4 + tenant.length) + (4 + domain.length) + (4 + table.length) + 2
        // + 4 * dekCount. Compute with long arithmetic per R2d.
        final long bodyLen = 4L + tenant.length + 4L + domain.length + 4L + table.length + 2L
                + 4L * dekVersions.length;
        if (bodyLen > Integer.MAX_VALUE - 8L) {
            // Reserve room for length-prefix (4) and CRC trailer (4)
            throw new IllegalStateException("scope-section body too large: " + bodyLen + " bytes");
        }
        final int totalLen = 4 + (int) bodyLen + 4;
        final byte[] out = new byte[totalLen];
        final ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        bb.putInt((int) bodyLen);
        bb.putInt(tenant.length);
        bb.put(tenant);
        bb.putInt(domain.length);
        bb.put(domain);
        bb.putInt(table.length);
        bb.put(table);
        bb.putShort((short) dekVersions.length);
        for (int v : dekVersions) {
            bb.putInt(v);
        }
        // Compute CRC32C over [0..totalLen-4) (everything except the trailing CRC field).
        final CRC32C crc = new CRC32C();
        crc.update(out, 0, totalLen - 4);
        bb.putInt(totalLen - 4, (int) crc.getValue());
        return out;
    }

    private static void validateDekVersionsForWrite(int[] versions) {
        for (int i = 0; i < versions.length; i++) {
            if (versions[i] <= 0) {
                throw new IllegalArgumentException("DEK version must be positive (R3b); got "
                        + versions[i] + " at index " + i);
            }
            if (i > 0 && versions[i] <= versions[i - 1]) {
                throw new IllegalArgumentException(
                        "DEK versions must be strictly ascending (R3a); index " + i
                                + " breaks order");
            }
        }
    }

    /**
     * Parsed v6 footer view returned by {@link #read}.
     *
     * <p>
     * The compact constructor takes a defensive snapshot of {@code dekVersionSet} via
     * {@link Set#copyOf(java.util.Collection)} so that subsequent mutations of the source
     * collection are not visible through {@link #dekVersionSet()}. This preserves R3e's
     * "materialised at footer-parse time" guarantee for the per-record dispatch hot path: the
     * membership-check view is immutable for the lifetime of the {@code Parsed} instance.
     *
     * @param scope the parsed table scope
     * @param dekVersionSet pre-materialised set for constant-time lookup; snapshotted on entry
     * @param footerEnd absolute byte offset of the footer's last byte (exclusive)
     * @spec sstable.footer-encryption-scope.R3e
     */
    public record Parsed(TableScope scope, Set<Integer> dekVersionSet, long footerEnd) {
        public Parsed {
            Objects.requireNonNull(scope, "scope must not be null");
            Objects.requireNonNull(dekVersionSet, "dekVersionSet must not be null");
            // Set.copyOf returns an unmodifiable snapshot regardless of source mutability —
            // closes the TOCTOU between Parsed construction and dispatch-side membership
            // pre-check (R3e). Set.copyOf is a no-op when the argument is already an
            // unmodifiable Set produced by Set.copyOf or Set.of.
            dekVersionSet = Set.copyOf(dekVersionSet);
        }
    }

    /**
     * Reads and validates a v6 footer scope section from a {@link SeekableByteChannel}. Verifies
     * the CRC32C, identifier rules, DEK-set ordering, and the R2f file-size invariant (footer
     * trailer position == file size).
     *
     * @param channel non-null seekable channel positioned anywhere; this method seeks internally
     * @param fileSize total file size in bytes
     * @return non-null parsed footer view
     * @throws IOException on truncation, CRC mismatch, identifier-rule violation, DEK-set ordering
     *             breach, or file-size invariant breach
     * @throws NullPointerException if {@code channel} is null
     * @spec sstable.footer-encryption-scope.R2a
     * @spec sstable.footer-encryption-scope.R2b
     * @spec sstable.footer-encryption-scope.R2c
     * @spec sstable.footer-encryption-scope.R2d
     * @spec sstable.footer-encryption-scope.R2f
     * @spec sstable.footer-encryption-scope.R3a
     * @spec sstable.footer-encryption-scope.R3b
     * @spec sstable.footer-encryption-scope.R3c
     */
    public static Parsed read(SeekableByteChannel channel, long fileSize) throws IOException {
        Objects.requireNonNull(channel, "channel must not be null");
        // Minimum bytes: 4 (length prefix) + 14 (3 identifier-len + 2 dek-count) + 4 (CRC) = 22,
        // and three single-byte identifiers add 3 more = 25 minimum. Be generous: enforce a
        // soft minimum of 4 + 4 + 1 + 4 + 1 + 4 + 1 + 2 + 4 = 25 here. The test suite covers
        // small-file rejection.
        if (fileSize < 22) {
            throw new IOException("v6 footer scope section underflow: file too small");
        }
        // Read the leading 4-byte scope-section-length prefix from offset 0 (the channel is
        // expected to be positioned at the start of the v6 scope section by the caller — this
        // codec does not assume anything beyond that).
        // We always read scope bytes from absolute offset 0 to fileSize.
        return readFromAbsolute(channel, fileSize);
    }

    private static Parsed readFromAbsolute(SeekableByteChannel channel, long fileSize)
            throws IOException {
        // Read entire footer payload.
        final byte[] all = readAll(channel, fileSize);
        return parseInternal(all, fileSize, /* enforceFileSizeMatch */ true);
    }

    /**
     * Parse a scope section from a pre-read byte array. Used by the SSTable reader integration path
     * which extracts the scope-section bytes from the trailing footer region of a v6 file and then
     * validates them via this method. The {@code expectedFooterEnd} is recorded into the returned
     * {@link Parsed} record but R2f's file-size invariant is not enforced here — the caller
     * (reader) enforces the equivalent invariant at the file level (last-8-bytes magic +
     * scope-total-size cross check).
     *
     * @param scopeBytes non-null byte array containing exactly the scope section (length prefix +
     *            body + CRC trailer)
     * @param expectedFooterEnd the absolute offset of the byte after the scope-section CRC trailer;
     *            passed through into {@link Parsed#footerEnd}
     * @return the parsed footer view
     * @throws IOException on any structural / CRC / identifier / DEK violation
     * @spec sstable.footer-encryption-scope.R2a
     * @spec sstable.footer-encryption-scope.R2d
     */
    public static Parsed parse(byte[] scopeBytes, long expectedFooterEnd) throws IOException {
        Objects.requireNonNull(scopeBytes, "scopeBytes must not be null");
        return parseInternal(scopeBytes, expectedFooterEnd, /* enforceFileSizeMatch */ false);
    }

    private static Parsed parseInternal(byte[] all, long fileSize, boolean enforceFileSizeMatch)
            throws IOException {
        if (all.length < 22) {
            throw new IOException(
                    "v6 footer scope section underflow: read " + all.length + " bytes");
        }
        final ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN);
        final long bodyLen = bb.getInt() & 0xFFFFFFFFL;
        // Total = 4 (len prefix) + bodyLen + 4 (CRC) — must equal all.length, which in turn must
        // equal fileSize (R2f).
        final long expectedTotal = 4L + bodyLen + 4L;
        if (expectedTotal > Integer.MAX_VALUE) {
            throw new IOException("v6 footer scope-section-length overflow: declared " + bodyLen);
        }
        if (expectedTotal != all.length) {
            throw new IOException("v6 footer scope-section-length mismatch: declared bodyLen="
                    + bodyLen + ", actual footer bytes=" + all.length + " (R2d/R2f)");
        }
        if (enforceFileSizeMatch && all.length != fileSize) {
            throw new IOException("v6 footer R2f violated: footer-end != fileSize (footer="
                    + all.length + ", fileSize=" + fileSize + ")");
        }
        // Verify CRC32C over [0..all.length-4).
        final int storedCrc = bb.getInt(all.length - 4);
        final CRC32C crc = new CRC32C();
        crc.update(all, 0, all.length - 4);
        final int computedCrc = (int) crc.getValue();
        if (storedCrc != computedCrc) {
            throw new IOException("v6 footer CRC32C mismatch (R2d) — corrupt footer");
        }
        // Parse identifiers.
        bb.position(4); // skip length prefix
        final byte[] tenantBytes = readIdentifierBytes(bb, "tenantId", all.length);
        IdentifierValidator.validateForRead(tenantBytes, "tenantId");
        final byte[] domainBytes = readIdentifierBytes(bb, "domainId", all.length);
        IdentifierValidator.validateForRead(domainBytes, "domainId");
        final byte[] tableBytes = readIdentifierBytes(bb, "tableId", all.length);
        IdentifierValidator.validateForRead(tableBytes, "tableId");
        // Parse DEK version count and versions.
        if (bb.remaining() < 2 + 4) {
            // 2 bytes for u16 count + 4 bytes for trailing CRC
            throw new IOException("v6 footer truncated before DEK-version-count — corrupt footer");
        }
        final int dekCount = bb.getShort() & 0xFFFF;
        final long needed = 4L * dekCount;
        if (bb.remaining() - 4 < needed) {
            // 4 bytes reserved for trailing CRC
            throw new IOException("v6 footer truncated: declared dek-version-count=" + dekCount
                    + " requires " + needed + " bytes, available " + (bb.remaining() - 4));
        }
        final int[] versions = new int[dekCount];
        int prev = 0;
        for (int i = 0; i < dekCount; i++) {
            int v = bb.getInt();
            if (v <= 0) {
                throw new IOException("v6 footer DEK version " + (i + 1)
                        + " is non-positive (R3b) — corrupt footer");
            }
            if (i > 0 && v <= prev) {
                throw new IOException("v6 footer DEK versions not strictly ascending at index " + i
                        + " (R3a) — corrupt footer");
            }
            versions[i] = v;
            prev = v;
        }
        // Decode identifiers as String for TableScope.
        final TenantId tenantId = new TenantId(new String(tenantBytes, StandardCharsets.UTF_8));
        final DomainId domainId = new DomainId(new String(domainBytes, StandardCharsets.UTF_8));
        final TableId tableId = new TableId(new String(tableBytes, StandardCharsets.UTF_8));
        final TableScope scope = new TableScope(tenantId, domainId, tableId);
        final Set<Integer> dekVersionSet = materialiseDekVersionSet(versions);
        // footerEnd is the offset of the byte after the last footer byte (exclusive) — for
        // unit-test usage where channel.size() == all.length, this is all.length; for the
        // integration parse() path, the caller passed the absolute footer-end via fileSize.
        final long footerEnd = enforceFileSizeMatch ? all.length : fileSize;
        return new Parsed(scope, dekVersionSet, footerEnd);
    }

    private static byte[] readIdentifierBytes(ByteBuffer bb, String fieldName, int totalLen)
            throws IOException {
        if (bb.remaining() < 4) {
            throw new IOException(
                    "v6 footer truncated before " + fieldName + "-utf8-length — corrupt footer");
        }
        final long len = bb.getInt() & 0xFFFFFFFFL;
        if (len < IdentifierValidator.MIN_LENGTH) {
            throw new IOException(fieldName + "-utf8-length below minimum (R2c) — corrupt footer");
        }
        if (len > IdentifierValidator.MAX_LENGTH) {
            throw new IOException(
                    fieldName + "-utf8-length exceeds maximum (R2b) — corrupt footer");
        }
        if (bb.remaining() < len + 4) {
            // 4 reserved for trailing CRC; the remaining bytes after this identifier must still
            // accommodate the rest of the footer.
            throw new IOException(
                    "v6 footer truncated reading " + fieldName + " bytes — corrupt footer");
        }
        final byte[] buf = new byte[(int) len];
        bb.get(buf);
        return buf;
    }

    private static byte[] readAll(SeekableByteChannel channel, long fileSize) throws IOException {
        if (fileSize > Integer.MAX_VALUE) {
            throw new IOException("v6 footer too large: " + fileSize + " bytes");
        }
        final byte[] data = new byte[(int) fileSize];
        channel.position(0);
        final ByteBuffer bb = ByteBuffer.wrap(data);
        while (bb.hasRemaining()) {
            int n = channel.read(bb);
            if (n < 0) {
                throw new IOException(
                        "v6 footer channel exhausted: read " + bb.position() + " of " + fileSize);
            }
        }
        return data;
    }

    /**
     * Materialises the on-disk DEK-version-set (encoded as a strictly-ascending int array) into a
     * {@link Set} sized for constant-time lookup. Called once at footer-parse time so that
     * subsequent per-record dispatch path (R3e) does not re-scan the array.
     *
     * @param sortedVersions non-null, strictly-ascending positive ints
     * @return non-null immutable {@link Set} view
     * @throws NullPointerException if {@code sortedVersions} is null
     * @throws IllegalArgumentException if {@code sortedVersions} contains non-positive entries or
     *             is not strictly ascending
     * @spec sstable.footer-encryption-scope.R3e
     */
    public static Set<Integer> materialiseDekVersionSet(int[] sortedVersions) {
        Objects.requireNonNull(sortedVersions, "sortedVersions must not be null");
        validateDekVersionsForWrite(sortedVersions);
        if (sortedVersions.length == 0) {
            return Set.of();
        }
        // HashSet sized for ~75% load factor → O(1) contains for the R3e dispatch hot path.
        final Set<Integer> hashed = new HashSet<>(Math.max(16, sortedVersions.length * 4 / 3 + 1));
        for (int v : sortedVersions) {
            hashed.add(v);
        }
        return java.util.Collections.unmodifiableSet(hashed);
    }
}
