package jlsm.core.compression;

/**
 * Pluggable compression codec for SSTable data blocks.
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
     * Compresses a region of the input byte array.
     *
     * <p>
     * If the compressed output would be equal to or larger than the input, the caller should store
     * the block uncompressed and record {@code NoneCodec} in the compression map.
     *
     * @param input source byte array; must not be null
     * @param offset start offset within {@code input}; must be non-negative
     * @param length number of bytes to compress; must be non-negative,
     *            {@code offset + length <= input.length}
     * @return a new byte array containing the compressed data
     * @throws IllegalArgumentException if offset or length are out of bounds
     * @throws java.io.UncheckedIOException if compression fails
     */
    byte[] compress(byte[] input, int offset, int length);

    /**
     * Decompresses a region of the input byte array.
     *
     * @param input compressed byte array; must not be null
     * @param offset start offset within {@code input}; must be non-negative
     * @param length number of compressed bytes; must be non-negative,
     *            {@code offset + length <= input.length}
     * @param uncompressedLength expected size of the decompressed output
     * @return a new byte array of exactly {@code uncompressedLength} bytes
     * @throws IllegalArgumentException if offset or length are out of bounds
     * @throws java.io.UncheckedIOException if decompression fails or output size does not match
     *             {@code uncompressedLength}
     */
    byte[] decompress(byte[] input, int offset, int length, int uncompressedLength);

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
}
