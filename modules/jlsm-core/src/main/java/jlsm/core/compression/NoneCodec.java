package jlsm.core.compression;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Passthrough codec that performs no compression or decompression.
 *
 * <p>
 * Compress copies the source segment to the destination using
 * {@link MemorySegment#copy(MemorySegment, long, MemorySegment, long, long)} (F17.R12). Decompress
 * copies the source to the destination the same way (F17.R13). No byte[] intermediary is used.
 *
 * <p>
 * The codec ID is {@code 0x00}. This codec is used as the default when no compression is
 * configured, and is also used in the compression map when a block's compressed output would be
 * equal to or larger than the original data.
 *
 * <p>
 * Package-private — accessed via {@link CompressionCodec#none()}.
 *
 * @see CompressionCodec
 * @see <a href="../../.decisions/compression-codec-api-design/adr.md">ADR: Compression Codec API
 *      Design</a>
 */
// @spec compression.codec-contract.R10 — rejects null segments
final class NoneCodec implements CompressionCodec {

    /** Singleton instance. */
    static final NoneCodec INSTANCE = new NoneCodec();

    private NoneCodec() {
    }

    @Override
    public byte codecId() {
        return 0x00;
    }

    @Override
    public int maxCompressedLength(int inputLength) {
        if (inputLength < 0) {
            throw new IllegalArgumentException(
                    "inputLength must be non-negative, got: " + inputLength);
        }
        return inputLength;
    }

    @Override
    public MemorySegment compress(MemorySegment src, MemorySegment dst) {
        Objects.requireNonNull(src, "src must not be null");
        Objects.requireNonNull(dst, "dst must not be null");

        final long srcSize = src.byteSize();

        // @spec compression.codec-contract.R11 — zero-length source returns zero-length slice
        if (srcSize == 0) {
            return dst.asSlice(0, 0);
        }

        // @spec compression.codec-contract.R15 — throws if destination too small
        if (dst.byteSize() < srcSize) {
            throw new IllegalStateException("dst.byteSize()=%d < maxCompressedLength(%d)=%d"
                    .formatted(dst.byteSize(), srcSize, srcSize));
        }

        // @spec compression.none-codec.R1 — MemorySegment.copy, no byte[] intermediary
        MemorySegment.copy(src, 0, dst, 0, srcSize);
        return dst.asSlice(0, srcSize);
    }

    @Override
    public MemorySegment decompress(MemorySegment src, MemorySegment dst, int uncompressedLength) {
        Objects.requireNonNull(src, "src must not be null");
        Objects.requireNonNull(dst, "dst must not be null");

        // @spec compression.codec-contract.R14 — rejects negative uncompressedLength
        if (uncompressedLength < 0) {
            throw new IllegalArgumentException(
                    "uncompressedLength must be non-negative, got: " + uncompressedLength);
        }

        // @spec compression.codec-contract.R12 — empty source + zero length returns zero-length
        if (uncompressedLength == 0 && src.byteSize() == 0) {
            return dst.asSlice(0, 0);
        }

        // @spec compression.codec-contract.R13 — non-empty source + zero length throws
        if (uncompressedLength == 0 && src.byteSize() > 0) {
            throw new UncheckedIOException(new IOException(
                    "Cannot decompress %d bytes into 0 bytes".formatted(src.byteSize())));
        }

        // @spec compression.none-codec.R2 — MemorySegment.copy, size must match
        if (src.byteSize() != uncompressedLength) {
            throw new UncheckedIOException(new IOException(
                    "NoneCodec size mismatch: src.byteSize()=%d, expected uncompressedLength=%d"
                            .formatted(src.byteSize(), uncompressedLength)));
        }

        MemorySegment.copy(src, 0, dst, 0, uncompressedLength);
        return dst.asSlice(0, uncompressedLength);
    }
}
