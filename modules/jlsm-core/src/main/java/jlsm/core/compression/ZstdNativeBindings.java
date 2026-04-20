package jlsm.core.compression;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

/**
 * Panama FFM downcall bindings for native libzstd.
 *
 * <p>
 * This class detects the system-provided libzstd at class-load time via
 * {@link Linker#nativeLinker()} and {@link SymbolLookup#libraryLookup(String, Arena)}, caches the
 * detection result in a static final field, and exposes downcall method handles for all required
 * ZSTD C functions. Detection catches all {@link Throwable} subclasses (including
 * {@link LinkageError}, {@link UnsatisfiedLinkError}) and falls through to the next tier.
 *
 * <h3>Tier model</h3>
 * <ul>
 * <li>{@link Tier#NATIVE} — full native ZSTD via Panama FFM (compress + decompress + dictionary
 * training)</li>
 * <li>{@link Tier#PURE_JAVA_DECOMPRESSOR} — pure-Java ZSTD decompression only; compression falls
 * back to Deflate</li>
 * <li>{@link Tier#DEFLATE_FALLBACK} — no ZSTD support; Deflate for both compress and
 * decompress</li>
 * </ul>
 *
 * <p>
 * Package-private — accessed only by {@link ZstdCodec} and {@link ZstdDictionaryTrainer}.
 *
 * @see <a href="../../../../.spec/domains/serialization/F18-zstd-dictionary-compression.md">F18 R4,
 *      R4a, R5</a>
 */
// @spec F18.R4 — class-load detection via Panama FFM, catches all Throwable, cached
// @spec F18.R4a — activeTier() exposes Tier enum for operator visibility
// @spec F18.R5 — binds all required ZSTD downcall handles; fall through to Tier 2 on failure
final class ZstdNativeBindings {

    /**
     * Runtime tier for ZSTD support, determined at class-load time.
     *
     * @see <a href="../../../../.spec/domains/serialization/F18-zstd-dictionary-compression.md">
     *      F18.R4a</a>
     */
    enum Tier {

        /**
         * Full native ZSTD via Panama FFM downcall handles. Compress, decompress, dictionary
         * training all available.
         */
        NATIVE,

        /**
         * Pure-Java ZSTD decompressor only. Compression uses Deflate fallback. Dictionary
         * decompression supported. Dictionary training unavailable.
         */
        PURE_JAVA_DECOMPRESSOR,

        /**
         * No ZSTD support. Both compress and decompress use Deflate.
         */
        DEFLATE_FALLBACK
    }

    // Cached tier and handles, determined once at class-load time
    private static final Tier ACTIVE_TIER;
    private static final MethodHandle COMPRESS2;
    private static final MethodHandle DECOMPRESS;
    private static final MethodHandle COMPRESS_BOUND;
    private static final MethodHandle IS_ERROR;
    private static final MethodHandle GET_ERROR_NAME;
    private static final MethodHandle CREATE_CCTX;
    private static final MethodHandle FREE_CCTX;
    private static final MethodHandle CREATE_DCTX;
    private static final MethodHandle FREE_DCTX;
    private static final MethodHandle CREATE_CDICT;
    private static final MethodHandle FREE_CDICT;
    private static final MethodHandle CREATE_DDICT;
    private static final MethodHandle FREE_DDICT;
    private static final MethodHandle COMPRESS_USING_CDICT;
    private static final MethodHandle DECOMPRESS_USING_DDICT;
    private static final MethodHandle TRAIN_FROM_BUFFER;
    private static final MethodHandle SET_CCTX_PARAMETER;

    static {
        Tier detectedTier;
        MethodHandle compress2 = null;
        MethodHandle decompress = null;
        MethodHandle compressBound = null;
        MethodHandle isError = null;
        MethodHandle getErrorName = null;
        MethodHandle createCCtx = null;
        MethodHandle freeCCtx = null;
        MethodHandle createDCtx = null;
        MethodHandle freeDCtx = null;
        MethodHandle createCDict = null;
        MethodHandle freeCDict = null;
        MethodHandle createDDict = null;
        MethodHandle freeDDict = null;
        MethodHandle compressUsingCDict = null;
        MethodHandle decompressUsingDDict = null;
        MethodHandle trainFromBuffer = null;
        MethodHandle setCCtxParameter = null;

        try {
            Linker linker = Linker.nativeLinker();
            // Try loading libzstd — the library name varies by platform
            Arena arena = Arena.ofAuto(); // lives for the lifetime of the JVM
            SymbolLookup lookup = loadZstdLibrary(arena);

            // size_t = JAVA_LONG on 64-bit (C long / size_t)
            ValueLayout sizeT = ValueLayout.JAVA_LONG;
            ValueLayout pointer = ValueLayout.ADDRESS;

            // Bind all required symbols — if any is missing, fall through to next tier
            // ZSTD_compress2(ZSTD_CCtx*, void*, size_t, const void*, size_t) -> size_t
            compress2 = downcall(linker, lookup, "ZSTD_compress2",
                    FunctionDescriptor.of(sizeT, pointer, pointer, sizeT, pointer, sizeT));

            // ZSTD_decompress(void*, size_t, const void*, size_t) -> size_t
            decompress = downcall(linker, lookup, "ZSTD_decompress",
                    FunctionDescriptor.of(sizeT, pointer, sizeT, pointer, sizeT));

            // ZSTD_compressBound(size_t) -> size_t
            compressBound = downcall(linker, lookup, "ZSTD_compressBound",
                    FunctionDescriptor.of(sizeT, sizeT));

            // ZSTD_isError(size_t) -> unsigned int
            isError = downcall(linker, lookup, "ZSTD_isError",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, sizeT));

            // ZSTD_getErrorName(size_t) -> const char*
            getErrorName = downcall(linker, lookup, "ZSTD_getErrorName",
                    FunctionDescriptor.of(pointer, sizeT));

            // ZSTD_createCCtx() -> ZSTD_CCtx*
            createCCtx = downcall(linker, lookup, "ZSTD_createCCtx",
                    FunctionDescriptor.of(pointer));

            // ZSTD_freeCCtx(ZSTD_CCtx*) -> size_t
            freeCCtx = downcall(linker, lookup, "ZSTD_freeCCtx",
                    FunctionDescriptor.of(sizeT, pointer));

            // ZSTD_createDCtx() -> ZSTD_DCtx*
            createDCtx = downcall(linker, lookup, "ZSTD_createDCtx",
                    FunctionDescriptor.of(pointer));

            // ZSTD_freeDCtx(ZSTD_DCtx*) -> size_t
            freeDCtx = downcall(linker, lookup, "ZSTD_freeDCtx",
                    FunctionDescriptor.of(sizeT, pointer));

            // ZSTD_createCDict(const void*, size_t, int) -> ZSTD_CDict*
            createCDict = downcall(linker, lookup, "ZSTD_createCDict",
                    FunctionDescriptor.of(pointer, pointer, sizeT, ValueLayout.JAVA_INT));

            // ZSTD_freeCDict(ZSTD_CDict*) -> size_t
            freeCDict = downcall(linker, lookup, "ZSTD_freeCDict",
                    FunctionDescriptor.of(sizeT, pointer));

            // ZSTD_createDDict(const void*, size_t) -> ZSTD_DDict*
            createDDict = downcall(linker, lookup, "ZSTD_createDDict",
                    FunctionDescriptor.of(pointer, pointer, sizeT));

            // ZSTD_freeDDict(ZSTD_DDict*) -> size_t
            freeDDict = downcall(linker, lookup, "ZSTD_freeDDict",
                    FunctionDescriptor.of(sizeT, pointer));

            // ZSTD_compress_usingCDict(ZSTD_CCtx*, void*, size_t, const void*, size_t,
            // const ZSTD_CDict*) -> size_t
            compressUsingCDict = downcall(linker, lookup, "ZSTD_compress_usingCDict",
                    FunctionDescriptor.of(sizeT, pointer, pointer, sizeT, pointer, sizeT, pointer));

            // ZSTD_decompress_usingDDict(ZSTD_DCtx*, void*, size_t, const void*, size_t,
            // const ZSTD_DDict*) -> size_t
            decompressUsingDDict = downcall(linker, lookup, "ZSTD_decompress_usingDDict",
                    FunctionDescriptor.of(sizeT, pointer, pointer, sizeT, pointer, sizeT, pointer));

            // ZDICT_trainFromBuffer(void*, size_t, const void*, const size_t*, unsigned) -> size_t
            trainFromBuffer = downcall(linker, lookup, "ZDICT_trainFromBuffer", FunctionDescriptor
                    .of(sizeT, pointer, sizeT, pointer, pointer, ValueLayout.JAVA_INT));

            // ZSTD_CCtx_setParameter(ZSTD_CCtx*, ZSTD_cParameter, int) -> size_t
            setCCtxParameter = downcall(linker, lookup, "ZSTD_CCtx_setParameter", FunctionDescriptor
                    .of(sizeT, pointer, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

            detectedTier = Tier.NATIVE;
        } catch (Throwable _) {
            // @spec F02.R39b — native detection failed; fall through to Tier 2 (pure-Java
            // decompressor) rather than Tier 3. PureJavaZstdDecompressor ships with this
            // module and needs no native support; DEFLATE_FALLBACK is reserved for cases
            // where even the pure-Java path is unusable.
            detectedTier = Tier.PURE_JAVA_DECOMPRESSOR;
        }

        ACTIVE_TIER = detectedTier;
        COMPRESS2 = compress2;
        DECOMPRESS = decompress;
        COMPRESS_BOUND = compressBound;
        IS_ERROR = isError;
        GET_ERROR_NAME = getErrorName;
        CREATE_CCTX = createCCtx;
        FREE_CCTX = freeCCtx;
        CREATE_DCTX = createDCtx;
        FREE_DCTX = freeDCtx;
        CREATE_CDICT = createCDict;
        FREE_CDICT = freeCDict;
        CREATE_DDICT = createDDict;
        FREE_DDICT = freeDDict;
        COMPRESS_USING_CDICT = compressUsingCDict;
        DECOMPRESS_USING_DDICT = decompressUsingDDict;
        TRAIN_FROM_BUFFER = trainFromBuffer;
        SET_CCTX_PARAMETER = setCCtxParameter;
    }

    private ZstdNativeBindings() {
        // static utility class
    }

    /**
     * Attempts to load libzstd via platform-specific library names.
     */
    private static SymbolLookup loadZstdLibrary(Arena arena) {
        // Try common library names across platforms
        String[] candidates = { "libzstd.so", "libzstd.so.1", "libzstd.dylib", "zstd", "libzstd" };
        Throwable lastError = null;
        for (String name : candidates) {
            try {
                return SymbolLookup.libraryLookup(name, arena);
            } catch (Throwable t) {
                lastError = t;
            }
        }
        // Also try the default loader lookup (system libraries)
        try {
            SymbolLookup defaultLookup = Linker.nativeLinker().defaultLookup();
            // Verify at least one ZSTD symbol exists
            if (defaultLookup.find("ZSTD_compress2").isPresent()) {
                return defaultLookup;
            }
        } catch (Throwable _) {
            // fall through
        }
        throw new RuntimeException("Could not load libzstd", lastError);
    }

    /**
     * Creates a downcall handle for a named native function. Throws if the symbol is not found.
     */
    private static MethodHandle downcall(Linker linker, SymbolLookup lookup, String name,
            FunctionDescriptor descriptor) {
        Optional<MemorySegment> symbol = lookup.find(name);
        if (symbol.isEmpty()) {
            throw new RuntimeException("Symbol not found: " + name);
        }
        return linker.downcallHandle(symbol.get(), descriptor);
    }

    /**
     * Throws ISE if native is not available; used as guard for method handle accessors.
     */
    private static void requireNative() {
        if (ACTIVE_TIER != Tier.NATIVE) {
            throw new IllegalStateException(
                    "Native libzstd is not available (active tier: " + ACTIVE_TIER + ")");
        }
    }

    /**
     * Returns the active ZSTD tier, determined once at class-load time.
     *
     * @return the active tier; never null
     */
    static Tier activeTier() {
        return ACTIVE_TIER;
    }

    /**
     * Returns whether native libzstd is available (i.e., active tier is {@link Tier#NATIVE}).
     *
     * @return {@code true} if Tier 1 is active
     */
    static boolean isNativeAvailable() {
        return ACTIVE_TIER == Tier.NATIVE;
    }

    static MethodHandle compress2() {
        requireNative();
        return COMPRESS2;
    }

    static MethodHandle decompress() {
        requireNative();
        return DECOMPRESS;
    }

    static MethodHandle compressBound() {
        requireNative();
        return COMPRESS_BOUND;
    }

    static MethodHandle isError() {
        requireNative();
        return IS_ERROR;
    }

    static MethodHandle getErrorName() {
        requireNative();
        return GET_ERROR_NAME;
    }

    static MethodHandle createCCtx() {
        requireNative();
        return CREATE_CCTX;
    }

    static MethodHandle freeCCtx() {
        requireNative();
        return FREE_CCTX;
    }

    static MethodHandle createDCtx() {
        requireNative();
        return CREATE_DCTX;
    }

    static MethodHandle freeDCtx() {
        requireNative();
        return FREE_DCTX;
    }

    static MethodHandle createCDict() {
        requireNative();
        return CREATE_CDICT;
    }

    static MethodHandle freeCDict() {
        requireNative();
        return FREE_CDICT;
    }

    static MethodHandle createDDict() {
        requireNative();
        return CREATE_DDICT;
    }

    static MethodHandle freeDDict() {
        requireNative();
        return FREE_DDICT;
    }

    static MethodHandle compressUsingCDict() {
        requireNative();
        return COMPRESS_USING_CDICT;
    }

    static MethodHandle decompressUsingDDict() {
        requireNative();
        return DECOMPRESS_USING_DDICT;
    }

    static MethodHandle trainFromBuffer() {
        requireNative();
        return TRAIN_FROM_BUFFER;
    }

    static MethodHandle setCCtxParameter() {
        requireNative();
        return SET_CCTX_PARAMETER;
    }
}
