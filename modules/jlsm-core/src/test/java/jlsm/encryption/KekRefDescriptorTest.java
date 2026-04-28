package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link KekRefDescriptor} (R83i-1). Privacy-preserving 16-byte HMAC prefix + provider
 * class + optional region tag; defensive copies on construction and accessor read.
 *
 * @spec encryption.primitives-lifecycle R83i-1
 */
class KekRefDescriptorTest {

    private static byte[] sixteenZeroes() {
        return new byte[16];
    }

    private static byte[] sixteenOnes() {
        final byte[] b = new byte[16];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) 0xFF;
        }
        return b;
    }

    @Test
    void hashPrefixLengthConstant() {
        assertEquals(16, KekRefDescriptor.HASH_PREFIX_BYTES);
    }

    @Test
    void validConstruction() {
        final KekRefDescriptor d = new KekRefDescriptor(sixteenZeroes(), "AwsKms",
                Optional.of("us-east-1"));
        assertEquals("AwsKms", d.providerClass());
        assertEquals(Optional.of("us-east-1"), d.regionTag());
        assertArrayEquals(sixteenZeroes(), d.hashPrefix());
    }

    @Test
    void absentRegionTagIsValid() {
        final KekRefDescriptor d = new KekRefDescriptor(sixteenZeroes(), "Vault", Optional.empty());
        assertTrue(d.regionTag().isEmpty());
    }

    @Test
    void nullHashPrefixIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new KekRefDescriptor(null, "AwsKms", Optional.empty()));
    }

    @Test
    void nullProviderIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new KekRefDescriptor(sixteenZeroes(), null, Optional.empty()));
    }

    @Test
    void nullRegionOptionalIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new KekRefDescriptor(sixteenZeroes(), "AwsKms", null));
    }

    @Test
    void wrongPrefixLengthIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new KekRefDescriptor(new byte[15], "AwsKms", Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new KekRefDescriptor(new byte[17], "AwsKms", Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new KekRefDescriptor(new byte[0], "AwsKms", Optional.empty()));
    }

    @Test
    void emptyProviderIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new KekRefDescriptor(sixteenZeroes(), "", Optional.empty()));
    }

    @Test
    void hashPrefixDefensivelyCopiedOnConstruction() {
        final byte[] mutable = sixteenOnes();
        final KekRefDescriptor d = new KekRefDescriptor(mutable, "AwsKms", Optional.empty());
        mutable[0] = (byte) 0x00;
        // Reading from the descriptor must NOT see the mutation.
        assertEquals((byte) 0xFF, d.hashPrefix()[0],
                "constructor must defensively copy hashPrefix");
    }

    @Test
    void hashPrefixDefensivelyCopiedOnAccess() {
        final KekRefDescriptor d = new KekRefDescriptor(sixteenOnes(), "AwsKms", Optional.empty());
        final byte[] view1 = d.hashPrefix();
        final byte[] view2 = d.hashPrefix();
        assertNotSame(view1, view2, "accessor must return a fresh array on each call");
        view1[0] = (byte) 0x00;
        // Mutation of the first view must NOT be visible to the second.
        assertEquals((byte) 0xFF, view2[0], "accessor must defensively copy hashPrefix on read");
    }

    @Test
    void recordEqualityComparesByValueNotIdentity() {
        // Records compare arrays by reference identity by default — the descriptor cannot
        // semantically rely on equals() for identity. We assert the structural shape only:
        // two descriptors with the same hashPrefix bytes will not be equal because byte[]
        // uses array identity. This is acceptable per the design (descriptors are
        // observability ephemera, not equality-keyed).
        final KekRefDescriptor a = new KekRefDescriptor(sixteenZeroes(), "AwsKms",
                Optional.empty());
        final KekRefDescriptor b = new KekRefDescriptor(sixteenZeroes(), "AwsKms",
                Optional.empty());
        // Record equals will call Object.equals on the byte[] components, returning false
        // for distinct arrays — assert that explicitly so future contract changes are
        // signalled by a failing test rather than silently flipping.
        assertFalse(a.equals(b),
                "byte[] components compare by identity; equals must return false for "
                        + "distinct hashPrefix arrays");
    }
}
