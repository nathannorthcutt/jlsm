package jlsm.sstable;

import java.io.IOException;
import java.util.Objects;

/**
 * Thrown when an SSTable file is detected to be incomplete at open time.
 *
 * <p>
 * A file is incomplete if either:
 * </p>
 * <ul>
 * <li>the trailing bytes do not match any known magic in the {@code v1..v5} vocabulary, or</li>
 * <li>the file is shorter than the minimum footer size for the declared version.</li>
 * </ul>
 *
 * <p>
 * This exception is distinct from {@link CorruptSectionException}: an incomplete file is the
 * expected outcome of a partial write (crash between data write and footer write), whereas a
 * section-CRC mismatch indicates real on-disk bit corruption and warrants different operator
 * handling.
 * </p>
 *
 * @spec sstable.end-to-end-integrity.R40
 */
public final class IncompleteSSTableException extends IOException {

    private static final long serialVersionUID = 1L;

    private final String detectedMagic;
    private final long actualFileSize;
    private final long expectedMinimumSize;
    private final String diagnosticDetail;

    /**
     * Constructs a new {@link IncompleteSSTableException}.
     *
     * @param detectedMagic the hex string of the trailing 8 bytes, or the sentinel
     *            {@code "no-magic"} when the file is too short to read a magic; must not be
     *            {@code null}
     * @param actualFileSize the actual on-disk byte length of the file
     * @param expectedMinimumSize the minimum size the footer version would require; {@code 0} if
     *            unknown
     * @param diagnosticDetail human-readable detail for logs; must not be {@code null}
     * @throws NullPointerException if {@code detectedMagic} or {@code diagnosticDetail} is
     *             {@code null}
     * @spec sstable.end-to-end-integrity.R40
     */
    public IncompleteSSTableException(String detectedMagic, long actualFileSize,
            long expectedMinimumSize, String diagnosticDetail) {
        super("SSTable file is incomplete: detectedMagic=%s, actualSize=%d, expectedMinimumSize=%d — %s"
                .formatted(Objects.requireNonNull(detectedMagic, "detectedMagic"), actualFileSize,
                        expectedMinimumSize,
                        Objects.requireNonNull(diagnosticDetail, "diagnosticDetail")));
        this.detectedMagic = detectedMagic;
        this.actualFileSize = actualFileSize;
        this.expectedMinimumSize = expectedMinimumSize;
        this.diagnosticDetail = diagnosticDetail;
    }

    /**
     * Returns the detected magic (hex-encoded trailing 8 bytes) or the sentinel {@code "no-magic"}
     * if the file was shorter than 8 bytes.
     *
     * @spec sstable.end-to-end-integrity.R40
     */
    public String detectedMagic() {
        return detectedMagic;
    }

    /**
     * Returns the actual on-disk byte length of the file at open time.
     *
     * @spec sstable.end-to-end-integrity.R40
     */
    public long actualFileSize() {
        return actualFileSize;
    }

    /**
     * Returns the minimum file size the declared version would require, or {@code 0} if no version
     * could be detected.
     *
     * @spec sstable.end-to-end-integrity.R40
     */
    public long expectedMinimumSize() {
        return expectedMinimumSize;
    }
}
