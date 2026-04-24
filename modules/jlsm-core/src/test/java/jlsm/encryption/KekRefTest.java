package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Tests for {@link KekRef} — validates R78/R80 opaque KMS key reference shape. */
class KekRefTest {

    @Test
    void constructor_rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new KekRef(null));
    }

    @Test
    void constructor_rejectsEmptyValue() {
        assertThrows(IllegalArgumentException.class, () -> new KekRef(""));
    }

    @Test
    void constructor_acceptsArn() {
        final KekRef ref = new KekRef("arn:aws:kms:us-east-1:123:key/abc");
        assertEquals("arn:aws:kms:us-east-1:123:key/abc", ref.value());
    }

    @Test
    void constructor_acceptsProviderOpaqueString() {
        // The encryption layer treats the string as opaque; any non-empty value is OK.
        assertEquals("local:uuid-xyz", new KekRef("local:uuid-xyz").value());
    }

    @Test
    void valueEquality() {
        assertEquals(new KekRef("k1"), new KekRef("k1"));
    }
}
