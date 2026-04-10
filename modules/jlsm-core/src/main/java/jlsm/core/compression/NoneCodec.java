package jlsm.core.compression;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Passthrough codec that performs no compression or decompression.
 *
 * <p>
 * Compress returns a copy of the input slice; decompress returns a copy of the input slice. The
 * codec ID is {@code 0x00}.
 *
 * <p>
 * This codec is used as the default when no compression is configured, and is also used in the
 * compression map when a block's compressed output would be equal to or larger than the original
 * data.
 *
 * <p>
 * Package-private — accessed via {@link CompressionCodec#none()}.
 *
 * @see CompressionCodec
 * @see <a href="../../.decisions/compression-codec-api-design/adr.md">ADR: Compression Codec API
 *      Design</a>
 */
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
    public byte[] compress(byte[] input, int offset, int length) {
        Objects.requireNonNull(input, "input must not be null");
        if (offset < 0 || length < 0 || offset > input.length - length) {
            throw new IllegalArgumentException(
                    "offset=%d, length=%d out of bounds for input.length=%d".formatted(offset,
                            length, input.length));
        }
        return Arrays.copyOfRange(input, offset, offset + length);
    }

    @Override
    public byte[] decompress(byte[] input, int offset, int length, int uncompressedLength) {
        Objects.requireNonNull(input, "input must not be null");
        if (offset < 0 || length < 0 || offset > input.length - length) {
            throw new IllegalArgumentException(
                    "offset=%d, length=%d out of bounds for input.length=%d".formatted(offset,
                            length, input.length));
        }
        if (uncompressedLength < 0) {
            throw new IllegalArgumentException(
                    "uncompressedLength must be non-negative, got: " + uncompressedLength);
        }
        if (length != uncompressedLength) {
            throw new UncheckedIOException(new IOException(
                    "NoneCodec size mismatch: compressed length=%d, expected uncompressedLength=%d"
                            .formatted(length, uncompressedLength)));
        }
        return Arrays.copyOfRange(input, offset, offset + length);
    }
}
