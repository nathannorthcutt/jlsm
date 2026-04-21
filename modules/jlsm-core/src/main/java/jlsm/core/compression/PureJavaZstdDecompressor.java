package jlsm.core.compression;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Objects;

/**
 * Pure-Java ZSTD frame decompressor supporting both plain and dictionary-compressed frames.
 *
 * <p>
 * This class provides Tier 2 fallback decompression when native libzstd is unavailable. It parses
 * ZSTD frame headers, decodes Huffman and FSE entropy-coded literals and sequences, and
 * reconstructs the original data. For dictionary-compressed frames, it loads pre-trained FSE and
 * Huffman tables from dictionary bytes, pre-seeds repeat offsets, and prepends dictionary content
 * as match history.
 *
 * <p>
 * This decompressor does <b>not</b> compress — compression in Tier 2/3 falls back to Deflate.
 *
 * <p>
 * Package-private — used only by {@link ZstdCodec} when Tier 2 is active.
 *
 * <p>
 * Implementation follows RFC 8878 (Zstandard Compression and the 'application/zstd' Media Type).
 *
 * @see ZstdCodec
 * @see ZstdNativeBindings.Tier#PURE_JAVA_DECOMPRESSOR
 * @see <a href="../../../../.spec/domains/serialization/F18-zstd-dictionary-compression.md">F18 R6,
 *      R8, R9</a>
 */
// @spec compression.zstd-dictionary.R6 — Tier 2 pure-Java ZSTD decompressor (plain + dictionary frames, no compression)
// @spec compression.zstd-dictionary.R8 — cross-tier: Tier 1 output decompresses here (plain and dict-bound)
final class PureJavaZstdDecompressor {

    private static final int ZSTD_MAGIC = 0xFD2FB528;
    private static final int DICT_MAGIC = 0xEC30A437;

    // Block types
    private static final int BLOCK_RAW = 0;
    private static final int BLOCK_RLE = 1;
    private static final int BLOCK_COMPRESSED = 2;

    // Literals block types
    private static final int LIT_RAW = 0;
    private static final int LIT_RLE = 1;
    private static final int LIT_COMPRESSED = 2;
    private static final int LIT_TREELESS = 3;

    // Sequence compression modes
    private static final int SEQ_PREDEFINED = 0;
    private static final int SEQ_RLE = 1;
    private static final int SEQ_FSE = 2;
    private static final int SEQ_TREELESS = 3;

    // Max accuracy log limits
    private static final int LL_MAX_LOG = 9;
    private static final int ML_MAX_LOG = 9;
    private static final int OF_MAX_LOG = 8;

    // Little-endian byte layout
    private static final ValueLayout.OfByte BYTE_LE = ValueLayout.JAVA_BYTE.withByteAlignment(1);
    private static final ValueLayout.OfInt INT_LE = ValueLayout.JAVA_INT_UNALIGNED
            .withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);

    // Literal Length baselines and extra bits (RFC 8878 Section 3.1.1.3.2.1.1)
    private static final int[] LL_BASE = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768,
            65536 };
    private static final int[] LL_BITS = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1,
            1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 };

    // Match Length baselines and extra bits (RFC 8878 Section 3.1.1.3.2.2.1)
    private static final int[] ML_BASE = { 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
            19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 37, 39, 41, 43, 47,
            51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771, 65539 };
    private static final int[] ML_BITS = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11,
            12, 13, 14, 15, 16 };

    // Predefined FSE decoding tables
    private static final FseTable PREDEFINED_LL;
    private static final FseTable PREDEFINED_ML;
    private static final FseTable PREDEFINED_OF;

    static {
        // LL predefined: accuracy log 6, distribution from RFC 8878
        short[] llDist = { 4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2,
                2, 3, 2, 1, 1, 1, 1, 1, -1, -1, -1, -1 };
        PREDEFINED_LL = FseTable.fromDistribution(llDist, 6, 35);

        // ML predefined: accuracy log 6
        short[] mlDist = { 1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1,
                -1, -1, -1 };
        PREDEFINED_ML = FseTable.fromDistribution(mlDist, 6, 52);

        // OF predefined: accuracy log 5
        short[] ofDist = { 1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                -1, -1, -1, -1, -1 };
        PREDEFINED_OF = FseTable.fromDistribution(ofDist, 5, 28);
    }

    PureJavaZstdDecompressor() {
    }

    MemorySegment decompress(MemorySegment src, MemorySegment dst, int uncompressedLength) {
        Objects.requireNonNull(src, "src must not be null");
        Objects.requireNonNull(dst, "dst must not be null");
        if (uncompressedLength < 0) {
            throw new IllegalArgumentException(
                    "uncompressedLength must be non-negative, got: " + uncompressedLength);
        }
        if (uncompressedLength == 0 && src.byteSize() == 0) {
            return dst.asSlice(0, 0);
        }
        return decompressFrame(src, dst, uncompressedLength, null);
    }

    MemorySegment decompress(MemorySegment src, MemorySegment dst, int uncompressedLength,
            MemorySegment dictionary) {
        Objects.requireNonNull(src, "src must not be null");
        Objects.requireNonNull(dst, "dst must not be null");
        Objects.requireNonNull(dictionary, "dictionary must not be null");
        if (uncompressedLength < 0) {
            throw new IllegalArgumentException(
                    "uncompressedLength must be non-negative, got: " + uncompressedLength);
        }
        if (uncompressedLength == 0 && src.byteSize() == 0) {
            return dst.asSlice(0, 0);
        }
        return decompressFrame(src, dst, uncompressedLength, dictionary);
    }

    // ==================== Frame decompression ====================

    private MemorySegment decompressFrame(MemorySegment src, MemorySegment dst,
            int uncompressedLength, MemorySegment dictionary) {
        try {
            byte[] input = segmentToBytes(src);
            int pos = parseFrameHeader(input);

            // Parse dictionary if provided
            DictTables dict = dictionary != null ? parseDictionary(segmentToBytes(dictionary))
                    : null;

            // Set up output with optional dictionary content prefix for match history
            byte[] output;
            int outStart;
            int outPos;
            if (dict != null && dict.content.length > 0) {
                output = new byte[dict.content.length + uncompressedLength];
                System.arraycopy(dict.content, 0, output, 0, dict.content.length);
                outStart = dict.content.length;
                outPos = dict.content.length;
            } else {
                output = new byte[uncompressedLength];
                outStart = 0;
                outPos = 0;
            }

            // Initial repeat offsets
            int rep1 = dict != null ? dict.rep1 : 1;
            int rep2 = dict != null ? dict.rep2 : 4;
            int rep3 = dict != null ? dict.rep3 : 8;

            // Carry tables across blocks within the frame
            HuffTable huff = dict != null ? dict.huff : null;
            FseTable llTable = dict != null ? dict.ll : null;
            FseTable mlTable = dict != null ? dict.ml : null;
            FseTable ofTable = dict != null ? dict.of : null;

            boolean lastBlock = false;
            while (!lastBlock && pos + 3 <= input.length) {
                int bh = (input[pos] & 0xFF) | ((input[pos + 1] & 0xFF) << 8)
                        | ((input[pos + 2] & 0xFF) << 16);
                pos += 3;
                lastBlock = (bh & 1) != 0;
                int blockType = (bh >> 1) & 3;
                int blockSize = bh >>> 3;

                switch (blockType) {
                    case BLOCK_RAW -> {
                        requireBytes(input, pos, blockSize, "raw block");
                        System.arraycopy(input, pos, output, outPos, blockSize);
                        outPos += blockSize;
                        pos += blockSize;
                    }
                    case BLOCK_RLE -> {
                        requireBytes(input, pos, 1, "RLE block");
                        Arrays.fill(output, outPos, outPos + blockSize, input[pos]);
                        outPos += blockSize;
                        pos += 1;
                    }
                    case BLOCK_COMPRESSED -> {
                        requireBytes(input, pos, blockSize, "compressed block");
                        var ctx = new BlockCtx(huff, llTable, mlTable, ofTable, rep1, rep2, rep3);
                        outPos = decompressBlock(input, pos, blockSize, output, outPos, ctx);
                        huff = ctx.huff;
                        llTable = ctx.ll;
                        mlTable = ctx.ml;
                        ofTable = ctx.of;
                        rep1 = ctx.rep1;
                        rep2 = ctx.rep2;
                        rep3 = ctx.rep3;
                        pos += blockSize;
                    }
                    default -> throw new IOException("Reserved block type: " + blockType);
                }
            }

            int written = outPos - outStart;
            if (written != uncompressedLength) {
                throw new IOException("Decompressed size mismatch: got %d, expected %d"
                        .formatted(written, uncompressedLength));
            }

            for (int i = 0; i < uncompressedLength; i++) {
                dst.set(BYTE_LE, i, output[outStart + i]);
            }
            return dst.asSlice(0, uncompressedLength);
        } catch (UncheckedIOException e) {
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("ZSTD decompression failed", e));
        }
    }

    private int parseFrameHeader(byte[] input) throws IOException {
        if (input.length < 4) {
            throw new IOException("ZSTD frame too short: " + input.length);
        }
        int magic = readLE32(input, 0);
        if (magic != ZSTD_MAGIC) {
            throw new IOException("Invalid ZSTD magic: 0x%08X".formatted(magic));
        }
        int pos = 4;
        requireBytes(input, pos, 1, "frame header descriptor");
        int fhd = input[pos] & 0xFF;
        pos++;
        int fcsFlag = (fhd >> 6) & 3;
        boolean singleSegment = ((fhd >> 5) & 1) != 0;
        int dictIdFlag = fhd & 3;

        // Window descriptor
        if (!singleSegment) {
            requireBytes(input, pos, 1, "window descriptor");
            pos++;
        }

        // Dictionary ID
        int dictIdSize = switch (dictIdFlag) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 4;
            default -> 0;
        };
        requireBytes(input, pos, dictIdSize, "dictionary ID");
        pos += dictIdSize;

        // Frame content size
        int fcsSize = switch (fcsFlag) {
            case 0 -> singleSegment ? 1 : 0;
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 8;
            default -> 0;
        };
        requireBytes(input, pos, fcsSize, "frame content size");
        pos += fcsSize;

        return pos;
    }

    // ==================== Compressed block ====================

    /** Mutable context passed through block decompression to carry tables and repeat offsets. */
    private static final class BlockCtx {

        HuffTable huff;
        FseTable ll;
        FseTable ml;
        FseTable of;
        int rep1;
        int rep2;
        int rep3;

        BlockCtx(HuffTable huff, FseTable ll, FseTable ml, FseTable of, int rep1, int rep2,
                int rep3) {
            this.huff = huff;
            this.ll = ll;
            this.ml = ml;
            this.of = of;
            this.rep1 = rep1;
            this.rep2 = rep2;
            this.rep3 = rep3;
        }
    }

    private int decompressBlock(byte[] data, int blockStart, int blockSize, byte[] output,
            int outPos, BlockCtx ctx) throws IOException {
        int pos = blockStart;
        int blockEnd = blockStart + blockSize;

        // ---- Literals section ----
        int litHeaderByte = data[pos] & 0xFF;
        int litType = litHeaderByte & 3;

        byte[] literals;
        int litEnd;

        switch (litType) {
            case LIT_RAW -> {
                var lr = parseLitSizeRawRle(data, pos);
                pos = lr.headerEnd;
                requireRange(data, pos, lr.regenSize, blockEnd, "raw literals");
                literals = new byte[lr.regenSize];
                System.arraycopy(data, pos, literals, 0, lr.regenSize);
                litEnd = pos + lr.regenSize;
            }
            case LIT_RLE -> {
                var lr = parseLitSizeRawRle(data, pos);
                pos = lr.headerEnd;
                requireRange(data, pos, 1, blockEnd, "RLE literal byte");
                literals = new byte[lr.regenSize];
                Arrays.fill(literals, data[pos]);
                litEnd = pos + 1;
            }
            case LIT_COMPRESSED, LIT_TREELESS -> {
                var lr = parseLitSizeCompressed(data, pos);
                pos = lr.headerEnd;
                requireRange(data, pos, lr.compSize, blockEnd, "compressed literals");

                int litDataStart = pos;
                if (litType == LIT_COMPRESSED) {
                    // Parse Huffman tree
                    var ht = HuffTable.decode(data, pos);
                    ctx.huff = ht.table;
                    pos += ht.headerSize;
                } else {
                    if (ctx.huff == null) {
                        throw new IOException("Treeless literals without preceding Huffman table");
                    }
                }

                int compLitDataStart = pos;
                int compLitDataSize = lr.compSize - (pos - litDataStart);
                literals = ctx.huff.decodeLiterals(data, compLitDataStart, compLitDataSize,
                        lr.regenSize, lr.numStreams);
                litEnd = litDataStart + lr.compSize;
            }
            default -> throw new IOException("Invalid literals type: " + litType);
        }

        pos = litEnd;

        // ---- Sequences section ----
        if (pos >= blockEnd) {
            // No sequences — just copy literals
            System.arraycopy(literals, 0, output, outPos, literals.length);
            return outPos + literals.length;
        }

        // Number of sequences
        int seqByte = data[pos] & 0xFF;
        pos++;
        int numSeq;
        if (seqByte < 128) {
            numSeq = seqByte;
        } else if (seqByte < 255) {
            requireRange(data, pos, 1, blockEnd, "sequence count");
            numSeq = ((seqByte - 128) << 8) + (data[pos] & 0xFF);
            pos++;
        } else {
            requireRange(data, pos, 2, blockEnd, "sequence count");
            numSeq = (data[pos] & 0xFF) + ((data[pos + 1] & 0xFF) << 8) + 0x7F00;
            pos += 2;
        }

        if (numSeq == 0) {
            System.arraycopy(literals, 0, output, outPos, literals.length);
            return outPos + literals.length;
        }

        // Symbol compression modes
        requireRange(data, pos, 1, blockEnd, "compression modes");
        int modes = data[pos] & 0xFF;
        pos++;
        int llMode = (modes >> 6) & 3;
        int ofMode = (modes >> 4) & 3;
        int mlMode = (modes >> 2) & 3;

        // Decode FSE tables
        pos = decodeSeqTable(data, pos, blockEnd, llMode, ctx, 'L');
        pos = decodeSeqTable(data, pos, blockEnd, ofMode, ctx, 'O');
        pos = decodeSeqTable(data, pos, blockEnd, mlMode, ctx, 'M');

        // Remaining data is the sequence bitstream
        int seqDataLen = blockEnd - pos;
        if (seqDataLen <= 0) {
            throw new IOException("No sequence bitstream data");
        }
        byte[] seqData = new byte[seqDataLen];
        System.arraycopy(data, pos, seqData, 0, seqDataLen);

        // Execute sequences
        outPos = executeSequences(seqData, literals, numSeq, output, outPos, ctx);

        return outPos;
    }

    // ---- Literal size parsing ----

    private record LitSizeResult(int regenSize, int headerEnd) {
    }

    private record LitSizeCompResult(int regenSize, int compSize, int numStreams, int headerEnd) {
    }

    private LitSizeResult parseLitSizeRawRle(byte[] data, int pos) throws IOException {
        int b0 = data[pos] & 0xFF;
        int sizeFormat = (b0 >> 2) & 3;
        if (sizeFormat == 0 || sizeFormat == 2) {
            return new LitSizeResult(b0 >>> 3, pos + 1);
        } else if (sizeFormat == 1) {
            requireBytes(data, pos, 2, "raw/rle lit header");
            int b1 = data[pos + 1] & 0xFF;
            return new LitSizeResult((b0 >>> 4) | (b1 << 4), pos + 2);
        } else {
            requireBytes(data, pos, 3, "raw/rle lit header");
            int b1 = data[pos + 1] & 0xFF;
            int b2 = data[pos + 2] & 0xFF;
            return new LitSizeResult((b0 >>> 4) | (b1 << 4) | (b2 << 12), pos + 3);
        }
    }

    private LitSizeCompResult parseLitSizeCompressed(byte[] data, int pos) throws IOException {
        int b0 = data[pos] & 0xFF;
        int sizeFormat = (b0 >> 2) & 3;
        int regenSize;
        int compSize;
        int streams;
        int headerEnd;

        if (sizeFormat == 0) {
            // 4 streams, 3 bytes total
            requireBytes(data, pos, 3, "comp lit header");
            streams = 4;
            int b1 = data[pos + 1] & 0xFF;
            int b2 = data[pos + 2] & 0xFF;
            int combined = (b0 >>> 4) | (b1 << 4) | (b2 << 12);
            regenSize = combined & 0x3FF;
            compSize = combined >>> 10;
            headerEnd = pos + 3;
        } else if (sizeFormat == 1) {
            // 1 stream, 3 bytes total
            requireBytes(data, pos, 3, "comp lit header");
            streams = 1;
            int b1 = data[pos + 1] & 0xFF;
            int b2 = data[pos + 2] & 0xFF;
            int combined = (b0 >>> 4) | (b1 << 4) | (b2 << 12);
            regenSize = combined & 0x3FF;
            compSize = combined >>> 10;
            headerEnd = pos + 3;
        } else if (sizeFormat == 2) {
            // 4 streams, 4 bytes total
            requireBytes(data, pos, 4, "comp lit header");
            streams = 4;
            int b1 = data[pos + 1] & 0xFF;
            int b2 = data[pos + 2] & 0xFF;
            int b3 = data[pos + 3] & 0xFF;
            int combined = (b0 >>> 4) | (b1 << 4) | (b2 << 12) | (b3 << 20);
            regenSize = combined & 0x3FFF;
            compSize = combined >>> 14;
            headerEnd = pos + 4;
        } else {
            // 4 streams, 5 bytes total
            requireBytes(data, pos, 5, "comp lit header");
            streams = 4;
            int b1 = data[pos + 1] & 0xFF;
            int b2 = data[pos + 2] & 0xFF;
            int b3 = data[pos + 3] & 0xFF;
            int b4 = data[pos + 4] & 0xFF;
            long combined = ((long) (b0 >>> 4)) | ((long) b1 << 4) | ((long) b2 << 12)
                    | ((long) b3 << 20) | ((long) b4 << 28);
            regenSize = (int) (combined & 0x3FFFF);
            compSize = (int) (combined >>> 18);
            headerEnd = pos + 5;
        }

        return new LitSizeCompResult(regenSize, compSize, streams, headerEnd);
    }

    // ---- Sequence table decoding ----

    private int decodeSeqTable(byte[] data, int pos, int limit, int mode, BlockCtx ctx, char which)
            throws IOException {
        return switch (mode) {
            case SEQ_PREDEFINED -> {
                switch (which) {
                    case 'L' -> ctx.ll = PREDEFINED_LL;
                    case 'O' -> ctx.of = PREDEFINED_OF;
                    case 'M' -> ctx.ml = PREDEFINED_ML;
                    default -> throw new IOException("Invalid table selector: " + which);
                }
                yield pos;
            }
            case SEQ_RLE -> {
                requireRange(data, pos, 1, limit, "RLE FSE symbol");
                int sym = data[pos] & 0xFF;
                FseTable t = FseTable.rle(sym);
                switch (which) {
                    case 'L' -> ctx.ll = t;
                    case 'O' -> ctx.of = t;
                    case 'M' -> ctx.ml = t;
                    default -> throw new IOException("Invalid table selector: " + which);
                }
                yield pos + 1;
            }
            case SEQ_FSE -> {
                int maxLog = switch (which) {
                    case 'L' -> LL_MAX_LOG;
                    case 'O' -> OF_MAX_LOG;
                    case 'M' -> ML_MAX_LOG;
                    default -> throw new IOException("Invalid table selector: " + which);
                };
                int maxSym = switch (which) {
                    case 'L' -> 35;
                    case 'O' -> 31;
                    case 'M' -> 52;
                    default -> 0;
                };
                var result = FseTable.decodeFromBitstream(data, pos, maxLog, maxSym);
                switch (which) {
                    case 'L' -> ctx.ll = result.table;
                    case 'O' -> ctx.of = result.table;
                    case 'M' -> ctx.ml = result.table;
                    default -> throw new IOException("Invalid table selector: " + which);
                }
                yield result.bytesConsumed + pos;
            }
            case SEQ_TREELESS -> {
                FseTable prev = switch (which) {
                    case 'L' -> ctx.ll;
                    case 'O' -> ctx.of;
                    case 'M' -> ctx.ml;
                    default -> null;
                };
                if (prev == null) {
                    throw new IOException("Treeless mode without preceding table for " + which);
                }
                yield pos;
            }
            default -> throw new IOException("Invalid FSE mode: " + mode);
        };
    }

    // ---- Sequence execution ----

    private int executeSequences(byte[] seqData, byte[] literals, int numSeq, byte[] output,
            int outPos, BlockCtx ctx) throws IOException {
        var bs = new BitReader(seqData);

        // Initialize FSE states
        int llState = bs.readBits(ctx.ll.log);
        int ofState = bs.readBits(ctx.of.log);
        int mlState = bs.readBits(ctx.ml.log);

        int litPos = 0;

        for (int seq = 0; seq < numSeq; seq++) {
            // Read symbols from current states
            int ofSym = ctx.of.symbol[ofState];
            int ofNb = ctx.of.nbBits[ofState];
            int ofNs = ctx.of.newState[ofState];

            int mlSym = ctx.ml.symbol[mlState];
            int mlNb = ctx.ml.nbBits[mlState];
            int mlNs = ctx.ml.newState[mlState];

            int llSym = ctx.ll.symbol[llState];
            int llNb = ctx.ll.nbBits[llState];
            int llNs = ctx.ll.newState[llState];

            // Extra bits order: offset, matchLen, litLen (RFC 8878 Section 3.1.1.4)
            int ofExtra = ofSym > 0 ? bs.readBits(ofSym) : 0;
            int rawOffset = (1 << ofSym) | ofExtra;

            if (mlSym >= ML_BASE.length) {
                throw new IOException("Invalid match length code: " + mlSym);
            }
            int mlExtraBits = ML_BITS[mlSym];
            int mlExtraVal = mlExtraBits > 0 ? bs.readBits(mlExtraBits) : 0;
            int matchLen = ML_BASE[mlSym] + mlExtraVal;

            if (llSym >= LL_BASE.length) {
                throw new IOException("Invalid literal length code: " + llSym);
            }
            int llExtraBits = LL_BITS[llSym];
            int llExtraVal = llExtraBits > 0 ? bs.readBits(llExtraBits) : 0;
            int litLen = LL_BASE[llSym] + llExtraVal;

            // Resolve offset (repeat offset system, RFC 8878 Section 3.1.2.2)
            int offset;
            if (rawOffset > 3) {
                offset = rawOffset - 3;
                ctx.rep3 = ctx.rep2;
                ctx.rep2 = ctx.rep1;
                ctx.rep1 = offset;
            } else if (rawOffset == 1) {
                if (litLen > 0) {
                    offset = ctx.rep1;
                } else {
                    offset = ctx.rep2;
                    ctx.rep2 = ctx.rep1;
                    ctx.rep1 = offset;
                }
            } else if (rawOffset == 2) {
                if (litLen > 0) {
                    offset = ctx.rep2;
                    ctx.rep3 = ctx.rep2;
                    ctx.rep2 = ctx.rep1;
                    ctx.rep1 = offset;
                } else {
                    offset = ctx.rep3;
                    ctx.rep3 = ctx.rep2;
                    ctx.rep2 = ctx.rep1;
                    ctx.rep1 = offset;
                }
            } else { // rawOffset == 3
                if (litLen > 0) {
                    offset = ctx.rep3;
                    ctx.rep3 = ctx.rep2;
                    ctx.rep2 = ctx.rep1;
                    ctx.rep1 = offset;
                } else {
                    offset = ctx.rep1 - 1;
                    ctx.rep3 = ctx.rep2;
                    ctx.rep2 = ctx.rep1;
                    ctx.rep1 = offset;
                }
            }

            // Update FSE states (except last sequence)
            if (seq < numSeq - 1) {
                llState = llNs + bs.readBits(llNb);
                mlState = mlNs + bs.readBits(mlNb);
                ofState = ofNs + bs.readBits(ofNb);
            }

            // Copy literals
            if (litPos + litLen > literals.length) {
                throw new IOException("Literal length %d exceeds available literals %d"
                        .formatted(litLen, literals.length - litPos));
            }
            System.arraycopy(literals, litPos, output, outPos, litLen);
            litPos += litLen;
            outPos += litLen;

            // Copy match
            if (offset <= 0) {
                throw new IOException("Invalid offset: " + offset);
            }
            if (outPos - offset < 0) {
                throw new IOException(
                        "Offset %d before output start at pos %d".formatted(offset, outPos));
            }
            // Byte-by-byte copy for overlapping case (offset < matchLen)
            for (int i = 0; i < matchLen; i++) {
                output[outPos] = output[outPos - offset];
                outPos++;
            }
        }

        // Trailing literals
        int remaining = literals.length - litPos;
        if (remaining > 0) {
            System.arraycopy(literals, litPos, output, outPos, remaining);
            outPos += remaining;
        }

        return outPos;
    }

    // ==================== FSE Table ====================

    private static final class FseTable {

        final int log;
        final int[] symbol;
        final int[] nbBits;
        final int[] newState;

        private FseTable(int log, int[] symbol, int[] nbBits, int[] newState) {
            this.log = log;
            this.symbol = symbol;
            this.nbBits = nbBits;
            this.newState = newState;
        }

        static FseTable rle(int sym) {
            return new FseTable(0, new int[]{ sym }, new int[]{ 0 }, new int[]{ 0 });
        }

        static FseTable fromDistribution(short[] dist, int accuracyLog, int maxSymbol) {
            int tableSize = 1 << accuracyLog;
            int[] sym = new int[tableSize];
            int[] nb = new int[tableSize];
            int[] ns = new int[tableSize];

            // symbolNext[s] tracks the next available state index for symbol s
            int[] symbolNext = new int[maxSymbol + 1];

            // Place -1 probability symbols at the high end of the table
            int highThreshold = tableSize - 1;
            for (int s = 0; s <= maxSymbol && s < dist.length; s++) {
                if (dist[s] == -1) {
                    sym[highThreshold] = s;
                    highThreshold--;
                    symbolNext[s] = 1; // -1 symbols have effective count 1
                } else if (dist[s] > 0) {
                    symbolNext[s] = dist[s];
                }
            }

            // Spread symbols with positive probability
            int step = (tableSize >>> 1) + (tableSize >>> 3) + 3;
            int mask = tableSize - 1;
            int pos = 0;
            for (int s = 0; s <= maxSymbol && s < dist.length; s++) {
                if (dist[s] <= 0) {
                    continue;
                }
                for (int i = 0; i < dist[s]; i++) {
                    sym[pos] = s;
                    do {
                        pos = (pos + step) & mask;
                    } while (pos > highThreshold);
                }
            }

            // Build numBits and newState baseline
            for (int state = 0; state < tableSize; state++) {
                int nextVal = symbolNext[sym[state]]++;
                int bits = accuracyLog - highBit(nextVal);
                nb[state] = bits;
                ns[state] = (nextVal << bits) - tableSize;
            }

            return new FseTable(accuracyLog, sym, nb, ns);
        }

        record DecodeResult(FseTable table, int bytesConsumed) {
        }

        /**
         * Decodes an FSE table from a forward bitstream in data starting at startPos. Returns the
         * table and the number of bytes consumed.
         */
        static DecodeResult decodeFromBitstream(byte[] data, int startPos, int maxAccuracyLog,
                int maxSymbol) throws IOException {
            // Forward bitstream reader
            int bitPos = 0; // absolute bit position from startPos

            // Read accuracy log (4 bits)
            int accuracyLog = readForwardBits(data, startPos, bitPos, 4) + 5;
            bitPos += 4;
            if (accuracyLog > maxAccuracyLog) {
                throw new IOException("FSE accuracy log %d exceeds max %d".formatted(accuracyLog,
                        maxAccuracyLog));
            }

            int tableSize = 1 << accuracyLog;
            short[] dist = new short[maxSymbol + 1];
            int remaining = tableSize + 1;
            int symbol = 0;
            int highSymbol = 0;

            boolean previousIs0 = false;
            while (remaining > 1 && symbol <= maxSymbol) {
                // Handle zero-probability runs (triggered when previous symbol had prob 0)
                if (previousIs0) {
                    int n0 = symbol;
                    // Read 2-bit repeat counts
                    while (true) {
                        int repeat = readForwardBits(data, startPos, bitPos, 2);
                        bitPos += 2;
                        n0 += repeat;
                        if (repeat < 3) {
                            break;
                        }
                    }
                    // Fill zero probabilities for symbols up to n0
                    while (symbol < n0 && symbol <= maxSymbol) {
                        dist[symbol] = 0;
                        symbol++;
                    }
                    if (symbol > maxSymbol || remaining <= 1) {
                        break;
                    }
                }

                // Calculate bits needed using reference algorithm:
                // nbBits = highBit(remaining + 1) + 1
                // threshold = 1 << nbBits
                // max = (2 * threshold - 1) - remaining
                int nbBits = highBit(remaining + 1) + 1;
                int threshold = 1 << nbBits;
                int max = (2 * threshold - 1) - remaining;

                int count;
                int lowBits = readForwardBits(data, startPos, bitPos, nbBits - 1);
                if (lowBits < max) {
                    count = lowBits;
                    bitPos += nbBits - 1;
                } else {
                    int fullBits = readForwardBits(data, startPos, bitPos, nbBits);
                    bitPos += nbBits;
                    if (fullBits >= threshold) {
                        count = fullBits - max;
                    } else {
                        count = fullBits;
                    }
                }

                count--; // probability offset: -1 means "less than 1"
                int proba = count;
                remaining -= proba < 0 ? -proba : proba;
                dist[symbol] = (short) proba;
                previousIs0 = (proba == 0);
                if (proba != 0) {
                    highSymbol = symbol;
                }

                symbol++;
            }

            // Assign remaining probability to last symbol
            if (remaining == 1 && symbol <= maxSymbol) {
                dist[symbol] = 1;
                highSymbol = Math.max(highSymbol, symbol);
            }

            // Round up to byte boundary
            int bytesConsumed = (bitPos + 7) / 8;

            FseTable table = fromDistribution(dist, accuracyLog, highSymbol);
            return new DecodeResult(table, bytesConsumed);
        }

        private static int readForwardBits(byte[] data, int basePos, int bitOff, int n)
                throws IOException {
            if (n == 0) {
                return 0;
            }
            int val = 0;
            for (int i = 0; i < n; i++) {
                int byteIdx = basePos + (bitOff + i) / 8;
                int bitIdx = (bitOff + i) % 8;
                if (byteIdx >= data.length) {
                    throw new IOException("Truncated FSE table bitstream");
                }
                val |= (((data[byteIdx] >>> bitIdx) & 1) << i);
            }
            return val;
        }

        private static int readForwardBit(byte[] data, int basePos, int bitOff) throws IOException {
            int byteIdx = basePos + bitOff / 8;
            int bitIdx = bitOff % 8;
            if (byteIdx >= data.length) {
                throw new IOException("Truncated FSE table bitstream");
            }
            return (data[byteIdx] >>> bitIdx) & 1;
        }
    }

    // ==================== Huffman Table ====================

    private static final class HuffTable {

        final int maxBits;
        final int[] symbols; // table[i] = symbol for lookup index i
        final int[] lengths; // table[i] = code length for lookup index i

        HuffTable(int maxBits, int[] symbols, int[] lengths) {
            this.maxBits = maxBits;
            this.symbols = symbols;
            this.lengths = lengths;
        }

        record DecodeHuffResult(HuffTable table, int headerSize) {
        }

        static DecodeHuffResult decode(byte[] data, int pos) throws IOException {
            if (pos >= data.length) {
                throw new IOException("Truncated Huffman tree");
            }
            int header = data[pos] & 0xFF;
            int[] weights = new int[256];
            int numSymbols;
            int headerSize;

            if (header < 128) {
                // FSE-compressed weights
                int compSize = header;
                headerSize = 1 + compSize;
                if (pos + headerSize > data.length) {
                    throw new IOException("Truncated Huffman FSE weights");
                }
                numSymbols = decodeFseWeights(data, pos + 1, compSize, weights);
            } else {
                // Direct 4-bit packed weights
                int numWeightBytes = header - 127;
                headerSize = 1 + numWeightBytes;
                if (pos + headerSize > data.length) {
                    throw new IOException("Truncated Huffman direct weights");
                }
                numSymbols = numWeightBytes * 2;
                for (int i = 0; i < numWeightBytes; i++) {
                    int b = data[pos + 1 + i] & 0xFF;
                    int idx = i * 2;
                    if (idx < 256) {
                        weights[idx] = (b >> 4) & 0x0F;
                    }
                    if (idx + 1 < 256) {
                        weights[idx + 1] = b & 0x0F;
                    }
                }
            }

            // Compute last weight to make total a power of 2
            int totalWeight = 0;
            int maxWeight = 0;
            for (int i = 0; i < numSymbols; i++) {
                if (weights[i] > 0) {
                    totalWeight += 1 << (weights[i] - 1);
                    maxWeight = Math.max(maxWeight, weights[i]);
                }
            }

            if (totalWeight > 0) {
                int targetPower = Integer.highestOneBit(totalWeight);
                if (targetPower < totalWeight) {
                    targetPower <<= 1;
                }
                int lastWeight = targetPower - totalWeight;
                if (lastWeight > 0) {
                    int w = highBit(lastWeight) + 1;
                    weights[numSymbols] = w;
                    maxWeight = Math.max(maxWeight, w);
                    numSymbols++;
                }
            }

            // Build decoding table
            int mBits = maxWeight;
            int tSize = 1 << mBits;
            int[] syms = new int[tSize];
            int[] lens = new int[tSize];

            // Assign table entries using canonical ordering
            int[] rankStart = new int[maxWeight + 2];
            int[] rankCount = new int[maxWeight + 2];
            for (int i = 0; i < numSymbols; i++) {
                if (weights[i] > 0) {
                    rankCount[weights[i]]++;
                }
            }
            rankStart[maxWeight] = 0;
            for (int w = maxWeight; w >= 1; w--) {
                rankStart[w - 1] = rankStart[w] + rankCount[w] * (1 << (mBits - w));
            }

            for (int i = 0; i < numSymbols; i++) {
                int w = weights[i];
                if (w == 0) {
                    continue;
                }
                int len = 1 << (mBits - w);
                int start = rankStart[w];
                rankStart[w] += len;
                for (int j = 0; j < len; j++) {
                    if (start + j < tSize) {
                        syms[start + j] = i;
                        lens[start + j] = w; // number of bits consumed
                    }
                }
            }

            return new DecodeHuffResult(new HuffTable(mBits, syms, lens), headerSize);
        }

        /**
         * Decodes Huffman-compressed literals using 1 or 4 streams. The Huffman bitstream is read
         * in reverse: bits are consumed from high to low, reading bytes backward from the end.
         */
        byte[] decodeLiterals(byte[] data, int start, int len, int regenSize, int numStreams)
                throws IOException {
            byte[] result = new byte[regenSize];
            if (regenSize == 0) {
                return result;
            }

            if (numStreams == 1) {
                decodeSingleStream(data, start, len, result, 0, regenSize);
            } else {
                // 4-stream: first 6 bytes are 3 LE uint16 sizes for streams 1-3
                if (len < 6) {
                    throw new IOException("4-stream Huffman: jump table too short");
                }
                int s1Len = (data[start] & 0xFF) | ((data[start + 1] & 0xFF) << 8);
                int s2Len = (data[start + 2] & 0xFF) | ((data[start + 3] & 0xFF) << 8);
                int s3Len = (data[start + 4] & 0xFF) | ((data[start + 5] & 0xFF) << 8);

                int s1Start = start + 6;
                int s2Start = s1Start + s1Len;
                int s3Start = s2Start + s2Len;
                int s4Start = s3Start + s3Len;
                int s4Len = (start + len) - s4Start;

                if (s4Start > start + len || s4Len < 0) {
                    throw new IOException("4-stream Huffman: jump table out of bounds");
                }

                // Divide output evenly, last stream gets remainder
                int quarter = (regenSize + 3) / 4;
                int o1 = Math.min(quarter, regenSize);
                int o2 = Math.min(quarter, regenSize - o1);
                int o3 = Math.min(quarter, regenSize - o1 - o2);
                int o4 = regenSize - o1 - o2 - o3;

                decodeSingleStream(data, s1Start, s1Len, result, 0, o1);
                decodeSingleStream(data, s2Start, s2Len, result, o1, o2);
                decodeSingleStream(data, s3Start, s3Len, result, o1 + o2, o3);
                decodeSingleStream(data, s4Start, s4Len, result, o1 + o2 + o3, o4);
            }
            return result;
        }

        private void decodeSingleStream(byte[] data, int streamStart, int streamLen, byte[] output,
                int outStart, int outLen) throws IOException {
            if (outLen == 0 || streamLen == 0) {
                return;
            }

            // Use BitReader (ZSTD LE bitstream convention)
            var bs = new BitReader(data, streamStart, streamLen);
            int tableSize = 1 << maxBits;
            int outPos = outStart;
            int outEnd = outStart + outLen;

            while (outPos < outEnd && bs.hasBits()) {
                int peekBits = Math.min(maxBits, 64 - bs.bitsConsumed);
                if (peekBits < 1) {
                    break;
                }
                int idx = bs.peekBits(peekBits);
                if (peekBits < maxBits) {
                    idx <<= (maxBits - peekBits);
                }
                if (idx >= tableSize) {
                    idx = tableSize - 1;
                }

                int sym = symbols[idx];
                int codeLen = lengths[idx];
                if (codeLen == 0 || codeLen > (64 - bs.bitsConsumed)) {
                    break;
                }

                bs.bitsConsumed += codeLen;
                bs.reload();

                output[outPos++] = (byte) sym;
            }
        }

        private static int decodeFseWeights(byte[] data, int pos, int compSize, int[] weights)
                throws IOException {
            // Decode the FSE table for Huffman weights
            var result = FseTable.decodeFromBitstream(data, pos, 7, 12);
            FseTable fseTable = result.table;
            int dataStart = pos + result.bytesConsumed;
            int dataLen = compSize - result.bytesConsumed;

            if (dataLen <= 0) {
                return 0;
            }

            // Decode weights using alternating FSE states from a reverse bitstream
            var bs = new BitReader(data, dataStart, dataLen);
            int state1 = bs.readBits(fseTable.log);
            int state2 = bs.readBits(fseTable.log);

            int idx = 0;

            while (idx < 255) {
                weights[idx++] = fseTable.symbol[state1];
                if (!bs.hasBits() && fseTable.nbBits[state1] == 0) {
                    break;
                }
                int nb1 = fseTable.nbBits[state1];
                if (nb1 > 0) {
                    if (!bs.hasBits()) {
                        break;
                    }
                    state1 = fseTable.newState[state1] + bs.readBits(nb1);
                }

                if (idx >= 255) {
                    break;
                }

                weights[idx++] = fseTable.symbol[state2];
                if (!bs.hasBits() && fseTable.nbBits[state2] == 0) {
                    break;
                }
                int nb2 = fseTable.nbBits[state2];
                if (nb2 > 0) {
                    if (!bs.hasBits()) {
                        break;
                    }
                    state2 = fseTable.newState[state2] + bs.readBits(nb2);
                }
            }

            return idx;
        }
    }

    // ==================== Reverse Bitstream Reader ====================

    /**
     * Reads bits from a ZSTD reverse bitstream. The last byte contains a sentinel (highest set
     * bit), and bits are read from below the sentinel toward byte 0.
     */
    /**
     * ZSTD reverse bitstream reader. Matches the reference BIT_DStream implementation: bytes are
     * loaded as a little-endian integer (byte[0] at LSB), bits are consumed from the MSB downward.
     * A sentinel bit in the last byte marks the boundary between padding and actual data.
     */
    private static final class BitReader {

        private final byte[] data;
        private final int start;
        private final int end;
        private long bitContainer; // current loaded word
        private int bitsConsumed; // how many bits consumed from the top of bitContainer
        private int ptr; // current reload pointer (moves toward start)

        BitReader(byte[] data) throws IOException {
            this(data, 0, data.length);
        }

        BitReader(byte[] data, int start, int len) throws IOException {
            this.data = data;
            this.start = start;
            this.end = start + len;
            if (len == 0) {
                this.bitContainer = 0;
                this.bitsConsumed = 64;
                this.ptr = start;
                return;
            }

            int lastByte = data[start + len - 1] & 0xFF;
            if (lastByte == 0) {
                throw new IOException("Reverse bitstream: last byte is 0 (no sentinel)");
            }

            int highBit = 31 - Integer.numberOfLeadingZeros(lastByte);

            // Load bitContainer as LE from the end of the buffer (up to 8 bytes)
            if (len >= 8) {
                ptr = start + len - 8;
                bitContainer = readLE64(data, ptr);
                bitsConsumed = 8 - highBit;
            } else {
                // Short buffer: load byte by byte into LE positions
                ptr = start;
                bitContainer = 0;
                for (int i = 0; i < len; i++) {
                    bitContainer |= ((long) (data[start + i] & 0xFF)) << (i * 8);
                }
                bitsConsumed = (8 - len) * 8 + (8 - highBit);
            }
        }

        int readBits(int n) throws IOException {
            if (n == 0) {
                return 0;
            }
            // Read n bits from the top of the container
            int val = peekBits(n);
            bitsConsumed += n;
            reload();
            return val;
        }

        private int peekBits(int n) {
            // Shift container left by bitsConsumed, then take top n bits
            long shifted = bitContainer << (bitsConsumed & 63);
            return (int) ((shifted >>> 1) >>> (63 - n));
        }

        private void reload() {
            // Reload when more than 56 bits consumed (need fresh bytes from the stream)
            if (bitsConsumed > 56 && ptr > start) {
                int bytesToLoad = Math.min((bitsConsumed >> 3), ptr - start);
                ptr -= bytesToLoad;
                bitContainer = readLE64Safe(data, ptr, end);
                bitsConsumed -= bytesToLoad * 8;
            }
        }

        boolean hasBits() {
            return bitsConsumed < 64 || ptr > start;
        }

        private static long readLE64(byte[] data, int off) {
            return readLE64Safe(data, off, off + 8);
        }

        private static long readLE64Safe(byte[] data, int off, int limit) {
            long val = 0;
            int available = Math.min(8, limit - off);
            for (int i = 0; i < available; i++) {
                val |= ((long) (data[off + i] & 0xFF)) << (i * 8);
            }
            return val;
        }
    }

    // ==================== Dictionary Parsing ====================

    private record DictTables(HuffTable huff, FseTable ll, FseTable ml, FseTable of, int rep1,
            int rep2, int rep3, byte[] content) {
    }

    private DictTables parseDictionary(byte[] dict) throws IOException {
        if (dict.length < 8) {
            throw new IOException("Dictionary too short: " + dict.length);
        }
        int magic = readLE32(dict, 0);
        if (magic != DICT_MAGIC) {
            throw new IOException("Invalid dictionary magic: 0x%08X".formatted(magic));
        }

        int pos = 8; // skip magic + dictID

        // Huffman table
        HuffTable huff = null;
        if (pos < dict.length) {
            try {
                var hr = HuffTable.decode(dict, pos);
                huff = hr.table;
                pos += hr.headerSize;
            } catch (IOException _) {
                // No valid Huffman table — continue without
            }
        }

        // FSE tables: offsets, match lengths, literal lengths
        FseTable of = PREDEFINED_OF;
        FseTable ml = PREDEFINED_ML;
        FseTable ll = PREDEFINED_LL;

        try {
            var ofResult = FseTable.decodeFromBitstream(dict, pos, OF_MAX_LOG, 31);
            of = ofResult.table;
            pos += ofResult.bytesConsumed;
        } catch (IOException _) {
        }

        try {
            var mlResult = FseTable.decodeFromBitstream(dict, pos, ML_MAX_LOG, 52);
            ml = mlResult.table;
            pos += mlResult.bytesConsumed;
        } catch (IOException _) {
        }

        try {
            var llResult = FseTable.decodeFromBitstream(dict, pos, LL_MAX_LOG, 35);
            ll = llResult.table;
            pos += llResult.bytesConsumed;
        } catch (IOException _) {
        }

        // Repeat offsets (3 x 4 bytes LE)
        int rep1 = 1;
        int rep2 = 4;
        int rep3 = 8;
        if (pos + 12 <= dict.length) {
            rep1 = readLE32(dict, pos);
            rep2 = readLE32(dict, pos + 4);
            rep3 = readLE32(dict, pos + 8);
            pos += 12;
            if (rep1 == 0) {
                rep1 = 1;
            }
            if (rep2 == 0) {
                rep2 = 4;
            }
            if (rep3 == 0) {
                rep3 = 8;
            }
        }

        // Remaining bytes are dictionary content
        byte[] content = pos < dict.length ? Arrays.copyOfRange(dict, pos, dict.length)
                : new byte[0];

        return new DictTables(huff, ll, ml, of, rep1, rep2, rep3, content);
    }

    // ==================== Utilities ====================

    private static int highBit(int val) {
        return val <= 0 ? 0 : 31 - Integer.numberOfLeadingZeros(val);
    }

    private static int readLE32(byte[] data, int off) {
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8) | ((data[off + 2] & 0xFF) << 16)
                | ((data[off + 3] & 0xFF) << 24);
    }

    private static byte[] segmentToBytes(MemorySegment seg) {
        byte[] result = new byte[(int) seg.byteSize()];
        MemorySegment.copy(seg, 0, MemorySegment.ofArray(result), 0, result.length);
        return result;
    }

    private static void requireBytes(byte[] data, int pos, int needed, String what)
            throws IOException {
        if (pos + needed > data.length) {
            throw new IOException("Truncated %s: need %d bytes at pos %d, have %d".formatted(what,
                    needed, pos, data.length));
        }
    }

    private static void requireRange(byte[] data, int pos, int needed, int limit, String what)
            throws IOException {
        if (pos + needed > limit) {
            throw new IOException("Truncated %s: need %d bytes at pos %d, limit %d".formatted(what,
                    needed, pos, limit));
        }
    }
}
