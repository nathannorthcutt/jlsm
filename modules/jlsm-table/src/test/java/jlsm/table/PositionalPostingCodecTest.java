package jlsm.table;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jlsm.encryption.AesSivEncryptor;
import jlsm.encryption.BoldyrevaOpeEncryptor;
import jlsm.encryption.EncryptionKeyHolder;
import jlsm.table.internal.PositionalPostingCodec;

/**
 * Tests for {@link PositionalPostingCodec} — Tier 2 position-aware postings.
 *
 * <p>
 * Verifies: round-trip encode/decode, OPE position encryption preserves order, phrase query
 * detection (consecutive positions), proximity query (position difference within threshold), and
 * error cases.
 */
class PositionalPostingCodecTest {

    private static final long DOMAIN_SIZE = 10_000L;
    private static final long RANGE_SIZE = 1_000_000L;

    private EncryptionKeyHolder keyHolder;
    private AesSivEncryptor detEncryptor;
    private BoldyrevaOpeEncryptor opeEncryptor;
    private PositionalPostingCodec codec;

    @BeforeEach
    void setUp() {
        final byte[] keyMaterial = new byte[64];
        for (int i = 0; i < 64; i++) {
            keyMaterial[i] = (byte) (i + 1);
        }
        keyHolder = EncryptionKeyHolder.of(keyMaterial);
        detEncryptor = new AesSivEncryptor(keyHolder);
        opeEncryptor = new BoldyrevaOpeEncryptor(keyHolder, DOMAIN_SIZE, RANGE_SIZE);
        codec = new PositionalPostingCodec(detEncryptor, opeEncryptor);
    }

    @AfterEach
    void tearDown() {
        keyHolder.close();
    }

    // ── Round-trip encode/decode ────────────────────────────────────────────

    @Test
    void roundTripPreservesDocIdAndPositionCount() {
        final byte[] docId = "doc-42".getBytes();
        final long[] positions = { 1L, 5L, 10L, 100L };

        final byte[] encoded = codec.encode(docId, positions);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        final PositionalPostingCodec.DecodedPosting decoded = codec.decode(encoded);
        assertNotNull(decoded);
        assertArrayEquals(docId, decoded.docId());
        assertEquals(positions.length, decoded.encryptedPositions().length);
    }

    @Test
    void roundTripSinglePosition() {
        final byte[] docId = "single".getBytes();
        final long[] positions = { 42L };

        final byte[] encoded = codec.encode(docId, positions);
        final PositionalPostingCodec.DecodedPosting decoded = codec.decode(encoded);

        assertArrayEquals(docId, decoded.docId());
        assertEquals(1, decoded.encryptedPositions().length);
    }

    @Test
    void differentDocIdsProduceDifferentEncodings() {
        final long[] positions = { 1L, 2L, 3L };
        final byte[] encoded1 = codec.encode("doc-A".getBytes(), positions);
        final byte[] encoded2 = codec.encode("doc-B".getBytes(), positions);

        assertFalse(Arrays.equals(encoded1, encoded2));
    }

    // ── OPE position encryption preserves order ────────────────────────────

    @Test
    void encryptedPositionsPreserveOrder() {
        final byte[] docId = "doc".getBytes();
        final long[] positions = { 10L, 50L, 100L, 500L, 1000L };

        final byte[] encoded = codec.encode(docId, positions);
        final PositionalPostingCodec.DecodedPosting decoded = codec.decode(encoded);
        final long[] encPositions = decoded.encryptedPositions();

        // OPE preserves order: enc(10) < enc(50) < enc(100) < enc(500) < enc(1000)
        for (int i = 0; i < encPositions.length - 1; i++) {
            assertTrue(encPositions[i] < encPositions[i + 1],
                    "OPE must preserve order: enc(" + positions[i] + ")=" + encPositions[i]
                            + " should be < enc(" + positions[i + 1] + ")=" + encPositions[i + 1]);
        }
    }

    @Test
    void encryptedPositionsAreDifferentFromPlaintext() {
        final byte[] docId = "doc".getBytes();
        final long[] positions = { 5L, 10L, 15L };

        final byte[] encoded = codec.encode(docId, positions);
        final PositionalPostingCodec.DecodedPosting decoded = codec.decode(encoded);
        final long[] encPositions = decoded.encryptedPositions();

        // At least one position should differ from its plaintext
        boolean anyDiffers = false;
        for (int i = 0; i < positions.length; i++) {
            if (encPositions[i] != positions[i]) {
                anyDiffers = true;
                break;
            }
        }
        assertTrue(anyDiffers, "Encrypted positions should differ from plaintext");
    }

    // ── Phrase query: consecutive OPE positions ────────────────────────────

    @Test
    void phraseQueryDetectedByConsecutiveEncryptedPositions() {
        // Consecutive plaintext positions: 10, 11, 12 — OPE preserves order,
        // so enc(10) < enc(11) < enc(12). We can verify the relative differences
        // by checking that for consecutive positions a < b, enc(a) < enc(b).
        final byte[] docId = "phrase-doc".getBytes();
        final long[] phrasePositions = { 10L, 11L, 12L };
        final long[] scatteredPositions = { 10L, 50L, 200L };

        final PositionalPostingCodec.DecodedPosting phraseDecoded = codec
                .decode(codec.encode(docId, phrasePositions));
        final PositionalPostingCodec.DecodedPosting scatteredDecoded = codec
                .decode(codec.encode(docId, scatteredPositions));

        // Phrase: all consecutive diffs should be equal in OPE space (enc(n+1) - enc(n) is constant
        // for consecutive inputs in a well-behaved OPE). At minimum, order is preserved.
        final long[] phraseEnc = phraseDecoded.encryptedPositions();
        assertTrue(phraseEnc[0] < phraseEnc[1]);
        assertTrue(phraseEnc[1] < phraseEnc[2]);

        // Scattered positions should have much larger gaps
        final long[] scatteredEnc = scatteredDecoded.encryptedPositions();
        final long phraseDiff = phraseEnc[2] - phraseEnc[0];
        final long scatteredDiff = scatteredEnc[2] - scatteredEnc[0];
        assertTrue(scatteredDiff > phraseDiff,
                "Scattered positions should have larger OPE gaps than consecutive positions");
    }

    // ── Proximity query: position difference within threshold ──────────────

    @Test
    void proximityQueryPositionDifferenceWithinThreshold() {
        final byte[] docId = "prox-doc".getBytes();
        // Positions 100 and 103 — within proximity 5
        final long[] nearPositions = { 100L, 103L };
        // Positions 100 and 500 — far apart
        final long[] farPositions = { 100L, 500L };

        final PositionalPostingCodec.DecodedPosting nearDecoded = codec
                .decode(codec.encode(docId, nearPositions));
        final PositionalPostingCodec.DecodedPosting farDecoded = codec
                .decode(codec.encode(docId, farPositions));

        final long nearGap = nearDecoded.encryptedPositions()[1]
                - nearDecoded.encryptedPositions()[0];
        final long farGap = farDecoded.encryptedPositions()[1] - farDecoded.encryptedPositions()[0];

        // OPE preserves ordering, so nearGap < farGap
        assertTrue(nearGap < farGap,
                "Near positions should have smaller encrypted gap than far positions");
        assertTrue(nearGap > 0, "Encrypted gap for near positions must be positive");
    }

    // ── Deterministic encoding ─────────────────────────────────────────────

    @Test
    void encodingIsDeterministicForSameInputs() {
        final byte[] docId = "det-doc".getBytes();
        final long[] positions = { 1L, 2L, 3L };

        final byte[] encoded1 = codec.encode(docId, positions);
        final byte[] encoded2 = codec.encode(docId, positions);

        assertArrayEquals(encoded1, encoded2,
                "Same docId + positions must produce identical encodings (DET + OPE are deterministic)");
    }

    // ── Error cases ────────────────────────────────────────────────────────

    @Test
    void encodeRejectsNullDocId() {
        assertThrows(NullPointerException.class, () -> codec.encode(null, new long[]{ 1L }));
    }

    @Test
    void encodeRejectsNullPositions() {
        assertThrows(NullPointerException.class, () -> codec.encode("doc".getBytes(), null));
    }

    @Test
    void encodeRejectsEmptyPositions() {
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode("doc".getBytes(), new long[0]));
    }

    @Test
    void decodeRejectsNullPosting() {
        assertThrows(NullPointerException.class, () -> codec.decode(null));
    }

    @Test
    void decodeRejectsTooShortPosting() {
        // A valid posting needs at least 4 (docId length) + 1 (min docId) + 4 (position count) + 8
        // (one position)
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[3]));
    }

    @Test
    void constructorRejectsNullDetEncryptor() {
        assertThrows(NullPointerException.class,
                () -> new PositionalPostingCodec(null, opeEncryptor));
    }

    @Test
    void constructorRejectsNullOpeEncryptor() {
        assertThrows(NullPointerException.class,
                () -> new PositionalPostingCodec(detEncryptor, null));
    }
}
