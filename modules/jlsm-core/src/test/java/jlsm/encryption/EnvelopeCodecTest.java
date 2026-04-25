package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EnvelopeCodec}: 4-byte big-endian DEK version prefix codec for the per-field
 * ciphertext envelope.
 *
 * <p>
 * Covers spec requirements:
 * <ul>
 * <li>R1 — every encrypted field begins with 4B BE DEK version tag</li>
 * <li>R1c — multi-byte integer fields encoded big-endian</li>
 * <li>R2 — readers reject 0 / negative version with {@link IOException}</li>
 * </ul>
 *
 * @spec encryption.ciphertext-envelope.R1
 * @spec encryption.ciphertext-envelope.R1c
 * @spec encryption.ciphertext-envelope.R2
 */
class EnvelopeCodecTest {

    // ── prefixVersion ─────────────────────────────────────────────────────────

    // @spec encryption.ciphertext-envelope.R1 — envelope output is exactly [4B BE version | body]
    // @spec encryption.ciphertext-envelope.R1c — version encoded big-endian
    @Test
    void prefixVersion_writesFourByteBigEndianHeader_thenBody() {
        final byte[] body = new byte[]{ (byte) 0xAA, (byte) 0xBB, (byte) 0xCC };
        final byte[] envelope = EnvelopeCodec.prefixVersion(0x01020304, body);

        assertEquals(4 + body.length, envelope.length, "envelope length is 4 + body.length");
        assertEquals((byte) 0x01, envelope[0], "byte 0 is BE high byte of version");
        assertEquals((byte) 0x02, envelope[1]);
        assertEquals((byte) 0x03, envelope[2]);
        assertEquals((byte) 0x04, envelope[3], "byte 3 is BE low byte of version");
        assertEquals((byte) 0xAA, envelope[4], "body bytes follow verbatim");
        assertEquals((byte) 0xBB, envelope[5]);
        assertEquals((byte) 0xCC, envelope[6]);
    }

    // @spec encryption.ciphertext-envelope.R2 — writer rejects zero version eagerly
    @Test
    void prefixVersion_zeroVersion_throwsIae() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EnvelopeCodec.prefixVersion(0, new byte[]{ 1, 2, 3 }));
        assertTrue(
                ex.getMessage().toLowerCase().contains("positive")
                        || ex.getMessage().toLowerCase().contains("dekversion")
                        || ex.getMessage().contains("0"),
                "message should reference positive constraint, got: " + ex.getMessage());
    }

    // @spec encryption.ciphertext-envelope.R2 — writer rejects negative version eagerly
    @Test
    void prefixVersion_negativeVersion_throwsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> EnvelopeCodec.prefixVersion(-1, new byte[]{ 1, 2, 3 }));
        assertThrows(IllegalArgumentException.class,
                () -> EnvelopeCodec.prefixVersion(Integer.MIN_VALUE, new byte[]{ 1, 2, 3 }));
    }

    @Test
    void prefixVersion_nullBody_throwsNpe() {
        assertThrows(NullPointerException.class, () -> EnvelopeCodec.prefixVersion(1, null));
    }

    // ── Boundary: empty body (legitimate for some variants) ─────────────────────

    @Test
    void prefixVersion_emptyBody_returnsFourByteEnvelope() {
        final byte[] envelope = EnvelopeCodec.prefixVersion(7, new byte[0]);
        assertEquals(4, envelope.length);
        assertEquals(0x00, envelope[0]);
        assertEquals(0x00, envelope[1]);
        assertEquals(0x00, envelope[2]);
        assertEquals(0x07, envelope[3]);
    }

    // @spec encryption.ciphertext-envelope.R1c — Integer.MAX_VALUE encoded big-endian
    @Test
    void prefixVersion_integerMaxValue_writesBigEndian() {
        final byte[] envelope = EnvelopeCodec.prefixVersion(Integer.MAX_VALUE, new byte[]{ 9 });
        assertEquals((byte) 0x7F, envelope[0]);
        assertEquals((byte) 0xFF, envelope[1]);
        assertEquals((byte) 0xFF, envelope[2]);
        assertEquals((byte) 0xFF, envelope[3]);
        assertEquals((byte) 9, envelope[4]);
    }

    // ── Defensive copy ───────────────────────────────────────────────────────

    @Test
    void prefixVersion_doesNotShareBackingArrayWithInput() {
        final byte[] body = new byte[]{ 1, 2, 3 };
        final byte[] envelope = EnvelopeCodec.prefixVersion(1, body);
        // Mutating the input must not change the envelope; the codec must have copied.
        body[0] = (byte) 0xFF;
        assertEquals((byte) 1, envelope[4],
                "envelope must hold a copy of the body — input mutation leaked into envelope");
    }

    // ── parseVersion ──────────────────────────────────────────────────────────

    // @spec encryption.ciphertext-envelope.R1c, R2 — reader parses BE version
    @Test
    void parseVersion_validPositive_returnsVersion() throws IOException {
        final byte[] envelope = new byte[]{ 0x01, 0x02, 0x03, 0x04, 0x55, 0x66 };
        assertEquals(0x01020304, EnvelopeCodec.parseVersion(envelope));
    }

    // @spec encryption.ciphertext-envelope.R2 — reader rejects zero version with IOException
    @Test
    void parseVersion_zeroBytes_throwsIo() {
        final byte[] envelope = new byte[]{ 0, 0, 0, 0, 99 };
        IOException ex = assertThrows(IOException.class,
                () -> EnvelopeCodec.parseVersion(envelope));
        assertTrue(
                ex.getMessage().toLowerCase().contains("version")
                        || ex.getMessage().toLowerCase().contains("corrupt"),
                "message should describe the failure, got: " + ex.getMessage());
    }

    // @spec encryption.ciphertext-envelope.R2 — reader rejects negative version with IOException
    @Test
    void parseVersion_negativeBytes_throwsIo() {
        final byte[] envelope = new byte[]{ (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 7 };
        assertThrows(IOException.class, () -> EnvelopeCodec.parseVersion(envelope));
    }

    // @spec encryption.ciphertext-envelope.R2 — Integer.MIN_VALUE bytes rejected
    @Test
    void parseVersion_minIntBytes_throwsIo() {
        final byte[] envelope = new byte[]{ (byte) 0x80, 0x00, 0x00, 0x00 };
        assertThrows(IOException.class, () -> EnvelopeCodec.parseVersion(envelope));
    }

    // Boundary: under-length envelope must IOException, not AIOOBE
    @Test
    void parseVersion_underLength_throwsIo() {
        assertThrows(IOException.class,
                () -> EnvelopeCodec.parseVersion(new byte[]{ 0x00, 0x00, 0x00 }));
        assertThrows(IOException.class, () -> EnvelopeCodec.parseVersion(new byte[0]));
    }

    @Test
    void parseVersion_nullEnvelope_throwsNpe() {
        assertThrows(NullPointerException.class, () -> EnvelopeCodec.parseVersion(null));
    }

    // ── stripPrefix ──────────────────────────────────────────────────────────

    @Test
    void stripPrefix_returnsBodyVerbatim() {
        final byte[] envelope = new byte[]{ 0x01, 0x02, 0x03, 0x04, (byte) 0xAA, (byte) 0xBB,
                (byte) 0xCC };
        final byte[] body = EnvelopeCodec.stripPrefix(envelope);
        assertArrayEquals(new byte[]{ (byte) 0xAA, (byte) 0xBB, (byte) 0xCC }, body);
    }

    @Test
    void stripPrefix_underLength_throwsIae() {
        assertThrows(IllegalArgumentException.class,
                () -> EnvelopeCodec.stripPrefix(new byte[]{ 1, 2, 3 }));
    }

    @Test
    void stripPrefix_exactlyFourBytes_returnsEmptyBody() {
        final byte[] body = EnvelopeCodec.stripPrefix(new byte[]{ 0, 0, 0, 1 });
        assertEquals(0, body.length);
    }

    @Test
    void stripPrefix_nullEnvelope_throwsNpe() {
        assertThrows(NullPointerException.class, () -> EnvelopeCodec.stripPrefix(null));
    }

    @Test
    void stripPrefix_doesNotShareBackingArrayWithInput() {
        final byte[] envelope = new byte[]{ 0, 0, 0, 1, 9, 8, 7 };
        final byte[] body = EnvelopeCodec.stripPrefix(envelope);
        envelope[4] = (byte) 0xFF;
        assertEquals((byte) 9, body[0],
                "stripPrefix must return a copy — input mutation leaked into result");
    }

    // ── Round-trip / structural ───────────────────────────────────────────────

    @Test
    void prefixThenStrip_isIdentity() {
        final byte[] body = new byte[]{ 1, 2, 3, 4, 5 };
        final byte[] envelope = EnvelopeCodec.prefixVersion(42, body);
        assertArrayEquals(body, EnvelopeCodec.stripPrefix(envelope));
    }

    @Test
    void prefixThenParseVersion_recoversVersion() throws IOException {
        for (int v : new int[]{ 1, 2, 0x12345678, Integer.MAX_VALUE }) {
            final byte[] envelope = EnvelopeCodec.prefixVersion(v, new byte[]{ (byte) 0xAA });
            assertEquals(v, EnvelopeCodec.parseVersion(envelope), "round-trip v=" + v);
        }
    }

    @Test
    void versionPrefixLength_isFour() {
        assertEquals(4, EnvelopeCodec.VERSION_PREFIX_LENGTH);
    }

    // Sanity: an envelope of all-zero bytes (the single most common corruption form)
    // must be rejected by parseVersion.
    @Test
    void parseVersion_envelopeOfAllZeros_throwsIo() {
        final byte[] envelope = new byte[20];
        Arrays.fill(envelope, (byte) 0);
        assertThrows(IOException.class, () -> EnvelopeCodec.parseVersion(envelope));
    }
}
