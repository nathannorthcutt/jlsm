package jlsm.core.compression;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * ZSTD compression codec implementing {@link CompressionCodec} and {@link AutoCloseable}.
 *
 * <p>
 * This codec wraps native libzstd via {@link ZstdNativeBindings} when available (Tier 1), falls
 * back to {@link PureJavaZstdDecompressor} for decompression-only (Tier 2), or delegates entirely
 * to {@link DeflateCodec} (Tier 3). The active tier is determined once at class-load time by
 * {@link ZstdNativeBindings#activeTier()}.
 *
 * <h3>Codec ID</h3>
 * <ul>
 * <li>{@code 0x03} when Tier 1 (native) is active — data is ZSTD-compressed</li>
 * <li>{@code 0x02} when Tier 2 or Tier 3 is active — data is Deflate-compressed (fallback)</li>
 * </ul>
 *
 * <h3>Dictionary support</h3>
 * <p>
 * When constructed with a non-null dictionary {@link MemorySegment}, the codec creates native CDict
 * (for compression) and DDict (for decompression) resources in a dedicated Arena whose lifetime is
 * tied to this codec instance. The codec must be {@linkplain #close() closed} to free these native
 * resources. Codecs without a dictionary allocate and free CCtx/DCtx within each
 * compress/decompress call.
 *
 * <h3>Thread safety</h3>
 * <p>
 * Codecs without a dictionary are stateless and thread-safe. Dictionary-bound codecs are
 * thread-safe for concurrent compress/decompress calls (CDict/DDict are immutable after creation),
 * but {@link #close()} must not be called while other threads are using the codec.
 *
 * <p>
 * Package-private — accessed via {@link CompressionCodec#zstd()} factory methods.
 *
 * @see CompressionCodec
 * @see ZstdNativeBindings
 * @see ZstdDictionaryTrainer
 * @see <a href="../../../../.spec/domains/serialization/F18-zstd-dictionary-compression.md">F18
 *      R1-R3a, R7, R7a, R8, R9, R17a-R17c, R26, R28</a>
 */
// @spec F17.R39b — tiered detection: native, pure-Java decomp, Deflate fallback
// @spec F17.R41 — dictionary is immutable shared state, thread-safe
// @spec F17.R42 — dictionary bytes not modified after construction
// @spec F17.R39d — CDict/DDict read-only, shareable across threads
final class ZstdCodec implements CompressionCodec, AutoCloseable {

    /** ZSTD codec ID for native Tier 1. */
    static final byte ZSTD_CODEC_ID = 0x03;

    /** Deflate codec ID used as fallback for Tier 2/3. */
    static final byte DEFLATE_CODEC_ID = 0x02;

    /** Minimum valid ZSTD compression level (inclusive). */
    static final int MIN_LEVEL = 1;

    /** Maximum valid ZSTD compression level (inclusive). */
    static final int MAX_LEVEL = 22;

    /** ZSTD CCtx parameter ID for compression level. */
    private static final int ZSTD_C_COMPRESSION_LEVEL = 100;

    /** Codec ID fixed at class-load time based on tier detection. */
    private static final byte ACTIVE_CODEC_ID = ZstdNativeBindings.isNativeAvailable()
            ? ZSTD_CODEC_ID
            : DEFLATE_CODEC_ID;

    private final int level;
    private final MemorySegment dictionary;
    private final DeflateCodec fallbackCodec;

    // Native resources for dictionary-bound codecs (Tier 1 only)
    private final Arena dictArena;
    private final MemorySegment cDict;
    private final MemorySegment dDict;

    private volatile boolean closed;

    /**
     * Creates a ZSTD codec with the specified compression level and optional dictionary.
     *
     * @param level compression level (1-22); validated at construction time
     * @param dictionary optional dictionary bytes; may be {@code null} for plain ZSTD
     * @throws IllegalArgumentException if {@code level} is outside the range 1-22
     */
    ZstdCodec(int level, MemorySegment dictionary) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException("ZSTD compression level must be %d–%d, got: %d"
                    .formatted(MIN_LEVEL, MAX_LEVEL, level));
        }
        this.level = level;
        this.dictionary = dictionary;
        this.fallbackCodec = ZstdNativeBindings.isNativeAvailable() ? null : new DeflateCodec(6);

        // Initialize dictionary resources for Tier 1
        if (dictionary != null && ZstdNativeBindings.isNativeAvailable()) {
            this.dictArena = Arena.ofShared();
            try {
                // Copy dictionary into arena-managed memory
                MemorySegment dictCopy = dictArena.allocate(dictionary.byteSize());
                MemorySegment.copy(dictionary, 0, dictCopy, 0, dictionary.byteSize());

                // Create CDict
                this.cDict = createCDict(dictCopy, level);
                if (this.cDict.equals(MemorySegment.NULL)) {
                    dictArena.close();
                    throw new UncheckedIOException(
                            new IOException("ZSTD_createCDict returned NULL"));
                }

                // Create DDict
                this.dDict = createDDict(dictCopy);
                if (this.dDict.equals(MemorySegment.NULL)) {
                    freeCDict(this.cDict);
                    dictArena.close();
                    throw new UncheckedIOException(
                            new IOException("ZSTD_createDDict returned NULL"));
                }
            } catch (UncheckedIOException e) {
                throw e;
            } catch (Throwable t) {
                dictArena.close();
                throw new UncheckedIOException(
                        new IOException("Failed to create dictionary resources", t));
            }
        } else {
            this.dictArena = null;
            this.cDict = null;
            this.dDict = null;
        }
    }

    @Override
    public byte codecId() {
        return ACTIVE_CODEC_ID;
    }

    @Override
    public MemorySegment compress(MemorySegment src, MemorySegment dst) {
        Objects.requireNonNull(src, "src must not be null");
        Objects.requireNonNull(dst, "dst must not be null");
        requireOpen();

        final long srcSize = src.byteSize();

        // Empty source -> zero-length slice
        if (srcSize == 0) {
            return dst.asSlice(0, 0);
        }

        // Undersized dst check
        final int maxLen = maxCompressedLength((int) srcSize);
        if (dst.byteSize() < maxLen) {
            throw new IllegalStateException("dst.byteSize()=%d < maxCompressedLength(%d)=%d"
                    .formatted(dst.byteSize(), srcSize, maxLen));
        }

        if (ZstdNativeBindings.isNativeAvailable()) {
            return compressNative(src, dst, srcSize);
        } else {
            assert fallbackCodec != null : "fallback codec must be set when native unavailable";
            return fallbackCodec.compress(src, dst);
        }
    }

    @Override
    public MemorySegment decompress(MemorySegment src, MemorySegment dst, int uncompressedLength) {
        Objects.requireNonNull(src, "src must not be null");
        Objects.requireNonNull(dst, "dst must not be null");
        if (uncompressedLength < 0) {
            throw new IllegalArgumentException(
                    "uncompressedLength must be non-negative, got: " + uncompressedLength);
        }
        requireOpen();

        if (ZstdNativeBindings.isNativeAvailable()) {
            return decompressNative(src, dst, uncompressedLength);
        } else if (ZstdNativeBindings
                .activeTier() == ZstdNativeBindings.Tier.PURE_JAVA_DECOMPRESSOR) {
            // Tier 2: pure-Java ZSTD decompression
            var decompressor = new PureJavaZstdDecompressor();
            if (dictionary != null) {
                return decompressor.decompress(src, dst, uncompressedLength, dictionary);
            }
            return decompressor.decompress(src, dst, uncompressedLength);
        } else {
            assert fallbackCodec != null : "fallback codec must be set when native unavailable";
            return fallbackCodec.decompress(src, dst, uncompressedLength);
        }
    }

    @Override
    public int maxCompressedLength(int inputLength) {
        if (inputLength < 0) {
            throw new IllegalArgumentException(
                    "inputLength must be non-negative, got: " + inputLength);
        }

        if (ZstdNativeBindings.isNativeAvailable()) {
            try {
                long bound = (long) ZstdNativeBindings.compressBound()
                        .invokeExact((long) inputLength);
                if (bound > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException(
                            "ZSTD compressBound(%d) = %d exceeds Integer.MAX_VALUE"
                                    .formatted(inputLength, bound));
                }
                return (int) bound;
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Throwable t) {
                throw new UncheckedIOException(new IOException("ZSTD_compressBound failed", t));
            }
        } else {
            // R3a: overflow guard for fallback formula inputLength * 2 + 64
            long result = (long) inputLength * 2 + 64;
            if (result > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "maxCompressedLength(%d) overflows int: %d".formatted(inputLength, result));
            }
            return (int) result;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (dictArena != null) {
            // Free CDict and DDict, then close the arena
            try {
                if (cDict != null) {
                    freeCDict(cDict);
                }
            } finally {
                try {
                    if (dDict != null) {
                        freeDDict(dDict);
                    }
                } finally {
                    dictArena.close();
                }
            }
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("ZstdCodec has been closed");
        }
    }

    // ---- Native compression ----

    private MemorySegment compressNative(MemorySegment src, MemorySegment dst, long srcSize) {
        try {
            long compressedSize;
            if (cDict != null) {
                // Dictionary compression: allocate per-call CCtx
                MemorySegment cctx = (MemorySegment) ZstdNativeBindings.createCCtx().invokeExact();
                if (cctx.equals(MemorySegment.NULL)) {
                    throw new IOException("ZSTD_createCCtx returned NULL");
                }
                try {
                    compressedSize = (long) ZstdNativeBindings.compressUsingCDict()
                            .invokeExact(cctx, dst, dst.byteSize(), src, srcSize, cDict);
                } finally {
                    long _ = (long) ZstdNativeBindings.freeCCtx().invokeExact(cctx);
                }
            } else {
                // Plain compression: allocate per-call CCtx, set level, compress
                MemorySegment cctx = (MemorySegment) ZstdNativeBindings.createCCtx().invokeExact();
                if (cctx.equals(MemorySegment.NULL)) {
                    throw new IOException("ZSTD_createCCtx returned NULL");
                }
                try {
                    // Set compression level on the CCtx (ZSTD_C_COMPRESSION_LEVEL = 100)
                    long setResult = (long) ZstdNativeBindings.setCCtxParameter().invokeExact(cctx,
                            ZSTD_C_COMPRESSION_LEVEL, level);
                    checkZstdError(setResult);
                    compressedSize = (long) ZstdNativeBindings.compress2().invokeExact(cctx, dst,
                            dst.byteSize(), src, srcSize);
                } finally {
                    long _ = (long) ZstdNativeBindings.freeCCtx().invokeExact(cctx);
                }
            }

            checkZstdError(compressedSize);
            assert compressedSize >= 0 && compressedSize <= dst.byteSize()
                    : "compressed size %d out of bounds [0, %d]".formatted(compressedSize,
                            dst.byteSize());
            return dst.asSlice(0, compressedSize);
        } catch (UncheckedIOException e) {
            throw e;
        } catch (Throwable t) {
            throw new UncheckedIOException(new IOException("ZSTD compression failed", t));
        }
    }

    private MemorySegment decompressNative(MemorySegment src, MemorySegment dst,
            int uncompressedLength) {
        try {
            long decompressedSize;
            if (dDict != null) {
                // Dictionary decompression: allocate per-call DCtx
                MemorySegment dctx = (MemorySegment) ZstdNativeBindings.createDCtx().invokeExact();
                if (dctx.equals(MemorySegment.NULL)) {
                    throw new IOException("ZSTD_createDCtx returned NULL");
                }
                try {
                    decompressedSize = (long) ZstdNativeBindings.decompressUsingDDict().invokeExact(
                            dctx, dst, (long) uncompressedLength, src, src.byteSize(), dDict);
                } finally {
                    long _ = (long) ZstdNativeBindings.freeDCtx().invokeExact(dctx);
                }
            } else {
                // Plain decompression
                decompressedSize = (long) ZstdNativeBindings.decompress().invokeExact(dst,
                        (long) uncompressedLength, src, src.byteSize());
            }

            checkZstdError(decompressedSize);

            if (decompressedSize != uncompressedLength) {
                throw new IOException("ZSTD decompression size mismatch: got %d bytes, expected %d"
                        .formatted(decompressedSize, uncompressedLength));
            }

            return dst.asSlice(0, decompressedSize);
        } catch (UncheckedIOException e) {
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Throwable t) {
            throw new UncheckedIOException(new IOException("ZSTD decompression failed", t));
        }
    }

    // ---- Native helper methods ----

    private static MemorySegment createCDict(MemorySegment dictData, int compressionLevel) {
        try {
            return (MemorySegment) ZstdNativeBindings.createCDict().invokeExact(dictData,
                    dictData.byteSize(), compressionLevel);
        } catch (Throwable t) {
            throw new UncheckedIOException(new IOException("ZSTD_createCDict failed", t));
        }
    }

    private static MemorySegment createDDict(MemorySegment dictData) {
        try {
            return (MemorySegment) ZstdNativeBindings.createDDict().invokeExact(dictData,
                    dictData.byteSize());
        } catch (Throwable t) {
            throw new UncheckedIOException(new IOException("ZSTD_createDDict failed", t));
        }
    }

    private static void freeCDict(MemorySegment cdictPtr) {
        try {
            long _ = (long) ZstdNativeBindings.freeCDict().invokeExact(cdictPtr);
        } catch (Throwable _) {
            // Best-effort cleanup — log and continue
        }
    }

    private static void freeDDict(MemorySegment ddictPtr) {
        try {
            long _ = (long) ZstdNativeBindings.freeDDict().invokeExact(ddictPtr);
        } catch (Throwable _) {
            // Best-effort cleanup — log and continue
        }
    }

    /**
     * Checks if a ZSTD return code indicates an error and throws with the error name.
     */
    private static void checkZstdError(long code) throws IOException {
        try {
            int err = (int) ZstdNativeBindings.isError().invokeExact(code);
            if (err != 0) {
                MemorySegment namePtr = (MemorySegment) ZstdNativeBindings.getErrorName()
                        .invokeExact(code);
                String errorName = namePtr.reinterpret(256).getString(0);
                throw new UncheckedIOException(new IOException("ZSTD error: " + errorName));
            }
        } catch (UncheckedIOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Failed to check ZSTD error code", t);
        }
    }
}
