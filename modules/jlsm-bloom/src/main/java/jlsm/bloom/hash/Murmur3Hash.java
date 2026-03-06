package jlsm.bloom.hash;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * Pure-Java implementation of MurmurHash3 x64-128, producing a 128-bit hash as two {@code long}
 * values. Used internally by the bloom filter module to generate double-hashing seeds.
 *
 * <p>This class is package-private; it is an internal utility and not part of the public API.
 *
 * <p><b>Thread-safety:</b> all methods are stateless and safe for concurrent use.
 */
public final class Murmur3Hash {

    private static final long C1 = 0x87c37b91114253d5L;
    private static final long C2 = 0x4cf5ad432745937fL;

    private Murmur3Hash() {}

    /**
     * Hashes the bytes of {@code key} using MurmurHash3 x64-128 with seed {@code 0}.
     *
     * @param key the key to hash; must not be null
     * @return a two-element array {@code [h1, h2]} where {@code h1} and {@code h2} are the two
     *     64-bit halves of the 128-bit hash output
     */
    public static long[] hash128(MemorySegment key) {
        Objects.requireNonNull(key, "key must not be null");
        byte[] data = key.toArray(ValueLayout.JAVA_BYTE);
        assert data != null : "toArray must not return null";
        int length = data.length;
        int nblocks = length >>> 4; // number of 16-byte blocks

        long h1 = 0L;
        long h2 = 0L;

        // Body: process 16-byte blocks
        for (int i = 0; i < nblocks; i++) {
            int base = i << 4;
            long k1 = getLongLE(data, base);
            long k2 = getLongLE(data, base + 8);

            k1 *= C1;
            k1 = rotl64(k1, 31);
            k1 *= C2;
            h1 ^= k1;

            h1 = rotl64(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729L;

            k2 *= C2;
            k2 = rotl64(k2, 33);
            k2 *= C1;
            h2 ^= k2;

            h2 = rotl64(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5L;
        }

        // Tail: remaining bytes
        int tail = nblocks << 4;
        int remaining = length & 15;

        long k1 = 0L;
        long k2 = 0L;

        @SuppressWarnings("fallthrough")
        int sw = remaining;
        switch (sw) {
            case 15: k2 ^= (long) (data[tail + 14] & 0xff) << 48;
            case 14: k2 ^= (long) (data[tail + 13] & 0xff) << 40;
            case 13: k2 ^= (long) (data[tail + 12] & 0xff) << 32;
            case 12: k2 ^= (long) (data[tail + 11] & 0xff) << 24;
            case 11: k2 ^= (long) (data[tail + 10] & 0xff) << 16;
            case 10: k2 ^= (long) (data[tail +  9] & 0xff) <<  8;
            case  9: k2 ^= (long) (data[tail +  8] & 0xff);
                k2 *= C2; k2 = rotl64(k2, 33); k2 *= C1; h2 ^= k2;

            case  8: k1 ^= (long) (data[tail +  7] & 0xff) << 56;
            case  7: k1 ^= (long) (data[tail +  6] & 0xff) << 48;
            case  6: k1 ^= (long) (data[tail +  5] & 0xff) << 40;
            case  5: k1 ^= (long) (data[tail +  4] & 0xff) << 32;
            case  4: k1 ^= (long) (data[tail +  3] & 0xff) << 24;
            case  3: k1 ^= (long) (data[tail +  2] & 0xff) << 16;
            case  2: k1 ^= (long) (data[tail +  1] & 0xff) <<  8;
            case  1: k1 ^= (long) (data[tail]       & 0xff);
                k1 *= C1; k1 = rotl64(k1, 31); k1 *= C2; h1 ^= k1;
            default: break;
        }

        h1 ^= length;
        h2 ^= length;

        h1 += h2;
        h2 += h1;

        h1 = fmix64(h1);
        h2 = fmix64(h2);

        h1 += h2;
        h2 += h1;

        return new long[]{h1, h2};
    }

    private static long getLongLE(byte[] data, int offset) {
        assert data != null && offset >= 0 && offset + 7 < data.length : "getLongLE offset out of bounds";
        return ((long) (data[offset]     & 0xff))
             | ((long) (data[offset + 1] & 0xff) <<  8)
             | ((long) (data[offset + 2] & 0xff) << 16)
             | ((long) (data[offset + 3] & 0xff) << 24)
             | ((long) (data[offset + 4] & 0xff) << 32)
             | ((long) (data[offset + 5] & 0xff) << 40)
             | ((long) (data[offset + 6] & 0xff) << 48)
             | ((long) (data[offset + 7] & 0xff) << 56);
    }

    private static long rotl64(long x, int r) {
        assert r > 0 && r < 64 : "rotation amount must be in [1,63], got " + r;
        return (x << r) | (x >>> (64 - r));
    }

    private static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }
}
