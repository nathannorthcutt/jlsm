package jlsm.core.compression;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Compression codec backed by {@link java.util.zip.Deflater} and {@link java.util.zip.Inflater}.
 *
 * <p>
 * The codec ID is {@code 0x02}. The constructor accepts a compression level (0–9) matching the
 * levels defined by {@link java.util.zip.Deflater}.
 *
 * <p>
 * Each call to {@link #compress} and {@link #decompress} creates a fresh
 * {@code Deflater}/{@code Inflater} and calls {@code end()} in a {@code finally} block to release
 * native memory immediately. This avoids retaining native resources across calls and is safe for
 * concurrent use (F17.R5, F17.R16).
 *
 * <p>
 * Both compress and decompress use the {@link MemorySegment#asByteBuffer()} zero-copy path:
 * Arena-allocated segments produce direct ByteBuffers, so data flows through native zlib without
 * heap intermediaries (F17.R14, F17.R15).
 *
 * <p>
 * Package-private — accessed via {@link CompressionCodec#deflate()} and
 * {@link CompressionCodec#deflate(int)}.
 *
 * @see CompressionCodec
 * @see <a href="../../.decisions/compression-codec-api-design/adr.md">ADR: Compression Codec API
 *      Design</a>
 * @see <a href="../../.kb/algorithms/compression/block-compression-algorithms.md">KB: Block
 *      Compression Algorithms</a>
 */
// @spec compression.codec-contract.R2 — configurable level 0-9
// @spec compression.codec-contract.R3 — Deflater/Inflater released in finally
// (formerly @spec F02.R9 — dropped during migration) — overflow-safe bounds validation
// (formerly @spec F02.R10 — dropped during migration) — rejects negative uncompressedLength
// @spec compression.deflate-codec.R3 — Deflater/Inflater per-call, released in finally
// @spec compression.deflate-codec.R4 — configurable level 0-9, rejects invalid
final class DeflateCodec implements CompressionCodec {

    private final int level;

    /**
     * Creates a Deflate codec with the specified compression level.
     *
     * @param level compression level (0–9)
     * @throws IllegalArgumentException if level is outside the range 0–9
     */
    DeflateCodec(int level) {
        if (level < 0 || level > 9) {
            throw new IllegalArgumentException("Deflate level must be 0–9, got: " + level);
        }
        this.level = level;
    }

    @Override
    public byte codecId() {
        return 0x02;
    }

    /**
     * Returns the worst-case compressed size for deflate.
     *
     * <p>
     * Uses the zlib {@code deflateBound} formula: accounts for block headers, Huffman overhead, and
     * stream framing.
     */
    @Override
    public int maxCompressedLength(int inputLength) {
        if (inputLength < 0) {
            throw new IllegalArgumentException(
                    "inputLength must be non-negative, got: " + inputLength);
        }
        // zlib deflateBound formula: input + (input >> 12) + (input >> 14) + (input >> 25) + 13
        // Add extra margin for Java's deflate wrapper bytes
        return inputLength + (inputLength >> 12) + (inputLength >> 14) + (inputLength >> 25) + 13;
    }

    // @spec compression.deflate-codec.R1 — zero-copy via asByteBuffer, no byte[] intermediary
    @Override
    public MemorySegment compress(MemorySegment src, MemorySegment dst) {
        Objects.requireNonNull(src, "src must not be null");
        Objects.requireNonNull(dst, "dst must not be null");

        final long srcSize = src.byteSize();

        // R7: empty source -> zero-length slice (before dst size check per R11)
        if (srcSize == 0) {
            return dst.asSlice(0, 0);
        }

        // R11: undersized dst check
        final int maxLen = maxCompressedLength((int) srcSize);
        if (dst.byteSize() < maxLen) {
            throw new IllegalStateException("dst.byteSize()=%d < maxCompressedLength(%d)=%d"
                    .formatted(dst.byteSize(), srcSize, maxLen));
        }

        // R14: zero-copy path via direct ByteBuffer
        final ByteBuffer srcBuf = src.asByteBuffer();
        final ByteBuffer dstBuf = dst.asByteBuffer();

        Deflater def = new Deflater(level);
        try {
            def.setInput(srcBuf);
            def.finish();

            int totalWritten = 0;
            while (!def.finished()) {
                dstBuf.position(totalWritten);
                int written = def.deflate(dstBuf);
                totalWritten += written;
                if (written == 0 && !def.finished()) {
                    throw new UncheckedIOException(new IOException(
                            "Deflate compression stalled after %d bytes".formatted(totalWritten)));
                }
            }

            assert totalWritten <= maxLen : "compressed output %d exceeds maxCompressedLength %d"
                    .formatted(totalWritten, maxLen);

            return dst.asSlice(0, totalWritten);
        } finally {
            def.end();
        }
    }

    // @spec compression.deflate-codec.R2 — zero-copy inflate via asByteBuffer
    @Override
    public MemorySegment decompress(MemorySegment src, MemorySegment dst, int uncompressedLength) {
        Objects.requireNonNull(src, "src must not be null");
        Objects.requireNonNull(dst, "dst must not be null");

        // R10: negative uncompressedLength
        if (uncompressedLength < 0) {
            throw new IllegalArgumentException(
                    "uncompressedLength must be non-negative, got: " + uncompressedLength);
        }

        // R8: empty src, zero length -> zero-length slice
        if (uncompressedLength == 0 && src.byteSize() == 0) {
            return dst.asSlice(0, 0);
        }

        // R9: non-empty src, zero length -> UncheckedIOException
        if (uncompressedLength == 0 && src.byteSize() > 0) {
            throw new UncheckedIOException(new IOException(
                    "Cannot decompress %d bytes into 0 bytes".formatted(src.byteSize())));
        }

        // R15: zero-copy path via direct ByteBuffer
        final ByteBuffer srcBuf = src.asByteBuffer();
        final ByteBuffer dstBuf = dst.asByteBuffer();
        dstBuf.limit(uncompressedLength);

        Inflater inf = new Inflater();
        try {
            inf.setInput(srcBuf);

            int totalRead = 0;
            while (totalRead < uncompressedLength && !inf.finished()) {
                dstBuf.position(totalRead);
                int read = inf.inflate(dstBuf);
                if (read == 0 && !inf.finished()) {
                    throw new UncheckedIOException(
                            new IOException("Deflate decompression stalled: inflated %d of %d bytes"
                                    .formatted(totalRead, uncompressedLength)));
                }
                totalRead += read;
            }

            if (totalRead != uncompressedLength) {
                throw new UncheckedIOException(new IOException(
                        "Deflate decompression size mismatch: got %d bytes, expected %d"
                                .formatted(totalRead, uncompressedLength)));
            }

            return dst.asSlice(0, uncompressedLength);
        } catch (DataFormatException e) {
            throw new UncheckedIOException(new IOException("Deflate decompression failed", e));
        } finally {
            inf.end();
        }
    }
}
