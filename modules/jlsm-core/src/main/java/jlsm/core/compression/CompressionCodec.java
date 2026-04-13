package jlsm.core.compression;

import java.lang.foreign.MemorySegment;

/**
 * Pluggable compression codec for SSTable data blocks and WAL records.
 *
 * <p>
 * Each codec is identified by a unique {@link #codecId()} stored in the SSTable compression map so
 * that readers can select the correct decompression algorithm without external configuration.
 *
 * <p>
 * Implementations must be stateless and thread-safe. Per-call native resources (e.g.
 * {@code Deflater}/{@code Inflater}) must be created and released within each method invocation.
 *
 * <p>
 * This is an open (non-sealed) interface so that consumers may provide custom codecs beyond the
 * built-in ones.
 *
 * <h3>Built-in codecs</h3>
 * <ul>
 * <li>{@link #none()} — passthrough, no compression (codec ID 0x00)</li>
 * <li>{@link #deflate()} — Deflate level 6 via {@code java.util.zip} (codec ID 0x02)</li>
 * <li>{@link #deflate(int)} — Deflate with custom level (codec ID 0x02)</li>
 * </ul>
 *
 * @see <a href="../../.decisions/compression-codec-api-design/adr.md">ADR: Compression Codec API
 *      Design</a>
 */
public interface CompressionCodec {

    /**
     * Returns the unique byte identifier for this codec.
     *
     * <p>
     * The ID is stored per-block in the compression map so that the reader can dispatch to the
     * correct decompression implementation.
     *
     * @return codec identifier byte (e.g. 0x00 for none, 0x02 for deflate)
     */
    byte codecId();

    /**
     * Compresses the source segment into the destination segment.
     *
     * <p>
     * The caller must provide a destination segment sized to at least
     * {@link #maxCompressedLength(int)} bytes for the source size. The returned segment is a slice
     * of {@code dst} containing the compressed output. The source segment is not mutated.
     *
     * <p>
     * If the source segment has zero byte size, a zero-length slice of {@code dst} is returned
     * without checking the destination size.
     *
     * @param src source data segment; must not be null
     * @param dst caller-provided destination segment; must not be null and sized
     *            {@code >= maxCompressedLength(src.byteSize())} when {@code src.byteSize() > 0}
     * @return a slice of {@code dst} containing the compressed output
     * @throws NullPointerException if {@code src} or {@code dst} is null
     * @throws IllegalStateException if {@code src.byteSize() > 0} and
     *             {@code dst.byteSize() < maxCompressedLength(src.byteSize())}
     * @throws java.io.UncheckedIOException if compression fails
     */
    MemorySegment compress(MemorySegment src, MemorySegment dst);

    /**
     * Decompresses the source segment into the destination segment.
     *
     * <p>
     * The returned segment is a slice of {@code dst} containing exactly {@code uncompressedLength}
     * bytes. The source segment is not mutated.
     *
     * @param src compressed data segment; must not be null
     * @param dst caller-provided destination segment; must not be null and sized
     *            {@code >= uncompressedLength}
     * @param uncompressedLength expected decompressed size; must be {@code >= 0}
     * @return a slice of {@code dst} containing the decompressed output
     * @throws NullPointerException if {@code src} or {@code dst} is null
     * @throws IllegalArgumentException if {@code uncompressedLength < 0}
     * @throws java.io.UncheckedIOException if decompression fails or output size does not match
     *             {@code uncompressedLength}
     */
    MemorySegment decompress(MemorySegment src, MemorySegment dst, int uncompressedLength);

    /**
     * Returns the maximum possible compressed size for the given input length.
     *
     * <p>
     * Callers can use this to pre-allocate output buffers of the correct size. The returned value
     * is a tight upper bound — actual compressed output will never exceed this length.
     *
     * <p>
     * The default implementation returns a conservative bound of {@code inputLength * 2 + 64}.
     * Built-in codecs override with tighter, algorithm-specific bounds.
     *
     * @param inputLength the length of the uncompressed input; must be non-negative
     * @return the worst-case compressed size; always {@code >= inputLength}
     * @throws IllegalArgumentException if {@code inputLength < 0}
     * @see <a href="../../.decisions/max-compressed-length/adr.md">ADR: Max Compressed Length</a>
     */
    default int maxCompressedLength(int inputLength) {
        if (inputLength < 0) {
            throw new IllegalArgumentException(
                    "inputLength must be non-negative, got: " + inputLength);
        }
        return inputLength + inputLength + 64;
    }

    /**
     * Returns the passthrough (no-op) codec. Codec ID 0x00.
     *
     * @return singleton {@code NoneCodec} instance
     */
    static CompressionCodec none() {
        return NoneCodec.INSTANCE;
    }

    /**
     * Returns a Deflate codec with the default compression level (6). Codec ID 0x02.
     *
     * @return a new {@code DeflateCodec} instance
     */
    static CompressionCodec deflate() {
        return new DeflateCodec(6);
    }

    /**
     * Returns a Deflate codec with the specified compression level. Codec ID 0x02.
     *
     * @param level compression level (0–9); 0 = no compression, 9 = best compression
     * @return a new {@code DeflateCodec} instance
     * @throws IllegalArgumentException if level is outside the range 0–9
     */
    static CompressionCodec deflate(int level) {
        return new DeflateCodec(level);
    }

    /**
     * Returns a ZSTD codec with the default compression level (3) and no dictionary. When native
     * libzstd is available (Tier 1), the codec ID is {@code 0x03}; otherwise falls back to Deflate
     * with codec ID {@code 0x02}.
     *
     * @return a new {@code ZstdCodec} instance
     * @see ZstdCodec
     */
    static CompressionCodec zstd() {
        return new ZstdCodec(3, null);
    }

    /**
     * Returns a ZSTD codec with the specified compression level and no dictionary.
     *
     * @param level compression level (1–22)
     * @return a new {@code ZstdCodec} instance
     * @throws IllegalArgumentException if level is outside the range 1–22
     */
    static CompressionCodec zstd(int level) {
        return new ZstdCodec(level, null);
    }

    /**
     * Returns a ZSTD codec with the default compression level (3) and the specified dictionary.
     *
     * @param dictionary trained dictionary bytes; must not be null
     * @return a new {@code ZstdCodec} instance
     * @throws NullPointerException if {@code dictionary} is null
     */
    static CompressionCodec zstd(MemorySegment dictionary) {
        return new ZstdCodec(3, dictionary);
    }

    /**
     * Returns a ZSTD codec with the specified compression level and dictionary.
     *
     * @param level compression level (1–22)
     * @param dictionary trained dictionary bytes; must not be null
     * @return a new {@code ZstdCodec} instance
     * @throws IllegalArgumentException if level is outside the range 1–22
     * @throws NullPointerException if {@code dictionary} is null
     */
    static CompressionCodec zstd(int level, MemorySegment dictionary) {
        return new ZstdCodec(level, dictionary);
    }
}
