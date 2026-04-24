package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

/** Tests for {@link WrapResult} — validates R80/R80b KMS wrap-result shape. */
class WrapResultTest {

    @Test
    void constructor_rejectsNullWrappedBytes() {
        assertThrows(NullPointerException.class, () -> new WrapResult(null, new KekRef("k")));
    }

    @Test
    void constructor_rejectsNullKekRef() {
        assertThrows(NullPointerException.class,
                () -> new WrapResult(ByteBuffer.wrap(new byte[]{ 1, 2 }), null));
    }

    @Test
    void accessors_roundTripComponents() {
        final ByteBuffer bytes = ByteBuffer.wrap(new byte[]{ 9, 8, 7 });
        final KekRef ref = new KekRef("k");
        final WrapResult r = new WrapResult(bytes, ref);
        assertEquals(bytes, r.wrappedBytes());
        assertEquals(ref, r.kekRef());
    }
}
