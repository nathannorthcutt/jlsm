package jlsm.core.compression;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
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
 * concurrent use.
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

    @Override
    public byte[] compress(byte[] input, int offset, int length) {
        Objects.requireNonNull(input, "input must not be null");
        if (offset < 0 || length < 0 || offset + length > input.length) {
            throw new IllegalArgumentException(
                    "offset=%d, length=%d out of bounds for input.length=%d".formatted(offset,
                            length, input.length));
        }
        Deflater def = new Deflater(level);
        try {
            def.setInput(input, offset, length);
            def.finish();
            byte[] buf = new byte[Math.max(length + 64, 64)];
            int totalWritten = 0;
            while (!def.finished()) {
                int written = def.deflate(buf, totalWritten, buf.length - totalWritten);
                totalWritten += written;
                if (totalWritten == buf.length && !def.finished()) {
                    buf = Arrays.copyOf(buf, buf.length * 2);
                }
            }
            return Arrays.copyOf(buf, totalWritten);
        } finally {
            def.end();
        }
    }

    @Override
    public byte[] decompress(byte[] input, int offset, int length, int uncompressedLength) {
        Objects.requireNonNull(input, "input must not be null");
        if (offset < 0 || length < 0 || offset + length > input.length) {
            throw new IllegalArgumentException(
                    "offset=%d, length=%d out of bounds for input.length=%d".formatted(offset,
                            length, input.length));
        }
        Inflater inf = new Inflater();
        try {
            inf.setInput(input, offset, length);
            byte[] output = new byte[uncompressedLength];
            int totalRead = 0;
            while (totalRead < uncompressedLength && !inf.finished()) {
                int read = inf.inflate(output, totalRead, uncompressedLength - totalRead);
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
            return output;
        } catch (DataFormatException e) {
            throw new UncheckedIOException(new IOException("Deflate decompression failed", e));
        } finally {
            inf.end();
        }
    }
}
