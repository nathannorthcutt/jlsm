package jlsm.table.internal;

import java.nio.ByteBuffer;
import java.util.Objects;

import jlsm.encryption.AesSivEncryptor;
import jlsm.encryption.BoldyrevaOpeEncryptor;

/**
 * Contract: Tier 2 position-aware posting format for encrypted full-text search. Extends inverted
 * index postings with OPE-encrypted term positions. Encodes a document ID and its term positions
 * into a byte sequence where positions are OPE-encrypted, enabling phrase queries (consecutive OPE
 * positions) and proximity queries (position difference within threshold).
 *
 * <p>
 * Encoding format: {@code [4-byte docId length][docId bytes][4-byte position count]
 * [OPE-encrypted positions as 8-byte big-endian longs]}.
 *
 * <p>
 * Governed by: .decisions/encrypted-index-strategy/adr.md
 */
public final class PositionalPostingCodec {

    /**
     * Minimum posting size: 4 (docId len) + 1 (min docId) + 4 (position count) + 8 (one position).
     */
    private static final int MIN_POSTING_SIZE = 4 + 1 + 4 + 8;

    private final AesSivEncryptor detEncryptor;
    private final BoldyrevaOpeEncryptor opeEncryptor;

    /**
     * Creates a positional posting codec with the given encryptors.
     *
     * @param detEncryptor the deterministic encryptor for term values
     * @param opeEncryptor the order-preserving encryptor for term positions
     */
    public PositionalPostingCodec(AesSivEncryptor detEncryptor,
            BoldyrevaOpeEncryptor opeEncryptor) {
        Objects.requireNonNull(detEncryptor, "detEncryptor must not be null");
        Objects.requireNonNull(opeEncryptor, "opeEncryptor must not be null");
        this.detEncryptor = detEncryptor;
        this.opeEncryptor = opeEncryptor;
    }

    /**
     * Encodes a document ID and its term positions into an encrypted posting entry. Each position
     * is encrypted using OPE to preserve ordering for phrase and proximity queries.
     *
     * @param docId the document identifier; must not be null
     * @param positions the plaintext term positions within the document; must not be null or empty
     * @return the encoded posting bytes
     * @throws NullPointerException if docId or positions is null
     * @throws IllegalArgumentException if positions is empty
     */
    public byte[] encode(byte[] docId, long[] positions) {
        Objects.requireNonNull(docId, "docId must not be null");
        Objects.requireNonNull(positions, "positions must not be null");
        if (positions.length == 0) {
            throw new IllegalArgumentException("positions must not be empty");
        }

        assert docId.length > 0 : "docId should have at least one byte";

        // OPE-encrypt each position
        final long[] encryptedPositions = new long[positions.length];
        for (int i = 0; i < positions.length; i++) {
            encryptedPositions[i] = opeEncryptor.encrypt(positions[i]);
        }

        // Build the encoded posting:
        // [4-byte docId length][docId bytes][4-byte position count][8-byte OPE positions...]
        final int totalSize = 4 + docId.length + 4 + (positions.length * 8);
        final ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.putInt(docId.length);
        buffer.put(docId);
        buffer.putInt(encryptedPositions.length);
        for (final long encPos : encryptedPositions) {
            buffer.putLong(encPos);
        }

        assert buffer.remaining() == 0 : "buffer should be fully written";
        return buffer.array();
    }

    /**
     * Decodes an encrypted posting entry.
     *
     * @param posting the encoded posting bytes; must not be null
     * @return the decoded posting with document ID and OPE-encrypted positions
     * @throws NullPointerException if posting is null
     * @throws IllegalArgumentException if posting is too short or malformed
     */
    public DecodedPosting decode(byte[] posting) {
        Objects.requireNonNull(posting, "posting must not be null");
        if (posting.length < MIN_POSTING_SIZE) {
            throw new IllegalArgumentException("Posting too short: minimum " + MIN_POSTING_SIZE
                    + " bytes, got " + posting.length);
        }

        final ByteBuffer buffer = ByteBuffer.wrap(posting);

        // Read docId
        final int docIdLength = buffer.getInt();
        if (docIdLength <= 0 || docIdLength > posting.length - 8) {
            throw new IllegalArgumentException("Invalid docId length: " + docIdLength);
        }
        final byte[] docId = new byte[docIdLength];
        buffer.get(docId);

        // Read encrypted positions
        final int positionCount = buffer.getInt();
        if (positionCount <= 0) {
            throw new IllegalArgumentException("Invalid position count: " + positionCount);
        }
        if (buffer.remaining() < positionCount * 8) {
            throw new IllegalArgumentException("Posting truncated: expected " + (positionCount * 8)
                    + " bytes for positions, got " + buffer.remaining());
        }

        final long[] encryptedPositions = new long[positionCount];
        for (int i = 0; i < positionCount; i++) {
            encryptedPositions[i] = buffer.getLong();
        }

        assert buffer.remaining() == 0 : "buffer should be fully consumed";
        return new DecodedPosting(docId, encryptedPositions);
    }

    /**
     * A decoded posting entry containing the document ID and OPE-encrypted positions.
     *
     * @param docId the document identifier
     * @param encryptedPositions the OPE-encrypted term positions (order-preserved)
     */
    public record DecodedPosting(byte[] docId, long[] encryptedPositions) {
        public DecodedPosting {
            Objects.requireNonNull(docId, "docId must not be null");
            Objects.requireNonNull(encryptedPositions, "encryptedPositions must not be null");
            docId = docId.clone();
            encryptedPositions = encryptedPositions.clone();
        }

        @Override
        public byte[] docId() {
            return docId.clone();
        }

        @Override
        public long[] encryptedPositions() {
            return encryptedPositions.clone();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof DecodedPosting other
                    && java.util.Arrays.equals(docId, other.docId)
                    && java.util.Arrays.equals(encryptedPositions, other.encryptedPositions);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Arrays.hashCode(docId)
                    + java.util.Arrays.hashCode(encryptedPositions);
        }
    }
}
