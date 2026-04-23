package jlsm.sstable.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.UnaryOperator;

/**
 * Test-only bridge exposing package-private {@link V5Footer} helpers to tests that live outside
 * {@code jlsm.sstable.internal} (i.e., the v5 reader/writer/corruption/concurrency test suite).
 *
 * <p>
 * This class is deliberately public so sibling test packages can reach it; the {@code V5Footer} and
 * {@code VarInt} types themselves remain package-private.
 * </p>
 */
public final class V5TestSupport {

    private V5TestSupport() {
    }

    /**
     * Returns the size of the v5 footer in bytes.
     */
    public static int footerSize() {
        return V5Footer.FOOTER_SIZE;
    }

    /**
     * Decode a v5 footer from the final {@link V5Footer#FOOTER_SIZE} bytes of {@code path}.
     */
    public static FooterView readFooter(Path path) throws IOException {
        byte[] all = Files.readAllBytes(path);
        V5Footer f = V5Footer.decode(all, all.length - V5Footer.FOOTER_SIZE);
        return FooterView.of(f);
    }

    /**
     * Apply {@code mutate} to the footer of {@code path}, recompute the footerChecksum, and rewrite
     * the trailing {@link V5Footer#FOOTER_SIZE} bytes.
     */
    public static void patchFooter(Path path, UnaryOperator<FooterView> mutate) throws IOException {
        byte[] all = Files.readAllBytes(path);
        int footerStart = all.length - V5Footer.FOOTER_SIZE;
        V5Footer original = V5Footer.decode(all, footerStart);
        FooterView mutated = mutate.apply(FooterView.of(original));
        V5Footer asFooter = new V5Footer(mutated.mapOffset, mutated.mapLength, mutated.dictOffset,
                mutated.dictLength, mutated.idxOffset, mutated.idxLength, mutated.fltOffset,
                mutated.fltLength, mutated.entryCount, mutated.blockSize, mutated.blockCount,
                mutated.mapChecksum, mutated.dictChecksum, mutated.idxChecksum, mutated.fltChecksum,
                /* footerChecksum */ 0, mutated.magic);
        byte[] footerBytes = new byte[V5Footer.FOOTER_SIZE];
        V5Footer.encode(asFooter, footerBytes, 0);
        int recomputed = V5Footer.computeFooterChecksum(footerBytes);
        V5Footer finalFooter = new V5Footer(asFooter.mapOffset(), asFooter.mapLength(),
                asFooter.dictOffset(), asFooter.dictLength(), asFooter.idxOffset(),
                asFooter.idxLength(), asFooter.fltOffset(), asFooter.fltLength(),
                asFooter.entryCount(), asFooter.blockSize(), asFooter.blockCount(),
                asFooter.mapChecksum(), asFooter.dictChecksum(), asFooter.idxChecksum(),
                asFooter.fltChecksum(), recomputed, asFooter.magic());
        V5Footer.encode(finalFooter, footerBytes, 0);
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(footerBytes), footerStart);
        }
    }

    /**
     * Walk the data section sequentially via VarInt prefixes using the same decoder as the reader.
     * Returns the number of blocks walked; throws if the walk ends before reaching
     * {@code mapOffset} from the footer.
     */
    public static int walkVarIntBlocks(Path path) throws IOException {
        byte[] all = Files.readAllBytes(path);
        V5Footer footer = V5Footer.decode(all, all.length - V5Footer.FOOTER_SIZE);
        long cursor = 0L;
        int walked = 0;
        while (cursor < footer.mapOffset() && walked < footer.blockCount()) {
            ByteBuffer view = ByteBuffer.wrap(all, (int) cursor,
                    (int) (footer.mapOffset() - cursor));
            VarInt.DecodedVarInt decoded = VarInt.decode(view, cursor);
            if (decoded.value() <= 0) {
                throw new IOException("non-positive VarInt payload length at cursor=" + cursor);
            }
            cursor += decoded.bytesConsumed() + decoded.value();
            walked++;
        }
        if (cursor != footer.mapOffset() || walked != footer.blockCount()) {
            throw new IOException("walk did not reach mapOffset tightly: cursor=" + cursor
                    + ", mapOffset=" + footer.mapOffset() + ", walked=" + walked + ", blockCount="
                    + footer.blockCount());
        }
        return walked;
    }

    /**
     * Recompute the footer's self-checksum from the bytes on disk.
     */
    public static int recomputeFooterChecksum(Path path) throws IOException {
        byte[] all = Files.readAllBytes(path);
        int footerStart = all.length - V5Footer.FOOTER_SIZE;
        byte[] footerBytes = new byte[V5Footer.FOOTER_SIZE];
        System.arraycopy(all, footerStart, footerBytes, 0, V5Footer.FOOTER_SIZE);
        return V5Footer.computeFooterChecksum(footerBytes);
    }

    /**
     * Validate tight packing of the footer loaded from {@code path}.
     */
    public static String tightPackingViolation(Path path) throws IOException {
        byte[] all = Files.readAllBytes(path);
        V5Footer f = V5Footer.decode(all, all.length - V5Footer.FOOTER_SIZE);
        return V5Footer.validateTightPacking(f);
    }

    /**
     * Public mirror of {@link V5Footer}'s fields — passes through tests without leaking the
     * package-private record.
     */
    public static final class FooterView {
        public final long mapOffset;
        public final long mapLength;
        public final long dictOffset;
        public final long dictLength;
        public final long idxOffset;
        public final long idxLength;
        public final long fltOffset;
        public final long fltLength;
        public final long entryCount;
        public final long blockSize;
        public final int blockCount;
        public final int mapChecksum;
        public final int dictChecksum;
        public final int idxChecksum;
        public final int fltChecksum;
        public final int footerChecksum;
        public final long magic;

        public FooterView(long mapOffset, long mapLength, long dictOffset, long dictLength,
                long idxOffset, long idxLength, long fltOffset, long fltLength, long entryCount,
                long blockSize, int blockCount, int mapChecksum, int dictChecksum, int idxChecksum,
                int fltChecksum, int footerChecksum, long magic) {
            this.mapOffset = mapOffset;
            this.mapLength = mapLength;
            this.dictOffset = dictOffset;
            this.dictLength = dictLength;
            this.idxOffset = idxOffset;
            this.idxLength = idxLength;
            this.fltOffset = fltOffset;
            this.fltLength = fltLength;
            this.entryCount = entryCount;
            this.blockSize = blockSize;
            this.blockCount = blockCount;
            this.mapChecksum = mapChecksum;
            this.dictChecksum = dictChecksum;
            this.idxChecksum = idxChecksum;
            this.fltChecksum = fltChecksum;
            this.footerChecksum = footerChecksum;
            this.magic = magic;
        }

        static FooterView of(V5Footer f) {
            return new FooterView(f.mapOffset(), f.mapLength(), f.dictOffset(), f.dictLength(),
                    f.idxOffset(), f.idxLength(), f.fltOffset(), f.fltLength(), f.entryCount(),
                    f.blockSize(), f.blockCount(), f.mapChecksum(), f.dictChecksum(),
                    f.idxChecksum(), f.fltChecksum(), f.footerChecksum(), f.magic());
        }

        public FooterView withBlockCount(int n) {
            return new FooterView(mapOffset, mapLength, dictOffset, dictLength, idxOffset,
                    idxLength, fltOffset, fltLength, entryCount, blockSize, n, mapChecksum,
                    dictChecksum, idxChecksum, fltChecksum, footerChecksum, magic);
        }

        public FooterView withMapLength(long n) {
            return new FooterView(mapOffset, n, dictOffset, dictLength, idxOffset, idxLength,
                    fltOffset, fltLength, entryCount, blockSize, blockCount, mapChecksum,
                    dictChecksum, idxChecksum, fltChecksum, footerChecksum, magic);
        }

        public FooterView withIdxOffset(long n) {
            return new FooterView(mapOffset, mapLength, dictOffset, dictLength, n, idxLength,
                    fltOffset, fltLength, entryCount, blockSize, blockCount, mapChecksum,
                    dictChecksum, idxChecksum, fltChecksum, footerChecksum, magic);
        }
    }
}
