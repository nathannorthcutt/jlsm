package jlsm.encryption.internal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

import jlsm.encryption.TenantId;

/**
 * Deterministic derivation of per-tenant shard paths within the key registry root. Documented for
 * operational tooling so ops can locate a given tenant's shard file without consulting internal
 * data structures (R82b).
 *
 * <p>
 * The tenant identifier is encoded as URL-safe base32 (RFC 4648, no padding, alphabet
 * {@code A-Z2-7}) over its UTF-8 bytes. This encoding is deterministic, filesystem-safe on all
 * supported platforms (Linux / macOS / Windows), case-preserving across case-insensitive
 * filesystems (letters are always uppercase in the alphabet), and free of {@code /}, {@code \\},
 * {@code .}, {@code ..}, {@code :}, {@code ?}, and other path-traversal or reserved characters.
 * Operational tools decoding the path back to the original tenant ID can use any standard base32
 * decoder over the alphabet above.
 *
 * <p>
 * Layout under {@code registryRoot}:
 *
 * <pre>
 * &lt;registryRoot&gt;/shards/&lt;base32-tenant-id&gt;/shard.bin
 * &lt;registryRoot&gt;/shards/&lt;base32-tenant-id&gt;/shard.bin.&lt;suffix&gt;.tmp   (during commit)
 * </pre>
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R82, R82b.
 */
public final class ShardPathResolver {

    /** RFC 4648 base32 alphabet (uppercase, no padding). */
    private static final char[] BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private ShardPathResolver() {
    }

    /**
     * Resolve the shard file for a tenant within a registry root.
     *
     * @throws NullPointerException if either argument is null
     */
    public static Path shardPath(Path registryRoot, TenantId tenantId) {
        Objects.requireNonNull(registryRoot, "registryRoot must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        final String encoded = encodeBase32(tenantId.value().getBytes(StandardCharsets.UTF_8));
        return registryRoot.resolve("shards").resolve(encoded).resolve("shard.bin");
    }

    /**
     * Resolve a temp path alongside {@code shardPath} used during atomic commit.
     *
     * <p>
     * The {@code suffix} MUST be a non-empty alphanumeric string (ASCII {@code [A-Za-z0-9]+}). Path
     * separators ({@code /}, {@code \\}), dots, colons, and control characters are rejected to
     * guarantee the returned path is a sibling of {@code shardPath} — preventing caller-supplied
     * traversal (e.g. {@code ../../../etc/passwd}) from escaping the shards directory.
     *
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if {@code suffix} is empty or contains any character outside
     *             {@code [A-Za-z0-9]}
     */
    public static Path tempPath(Path shardPath, String suffix) {
        Objects.requireNonNull(shardPath, "shardPath must not be null");
        Objects.requireNonNull(suffix, "suffix must not be null");
        if (suffix.isEmpty()) {
            throw new IllegalArgumentException("suffix must not be empty");
        }
        for (int i = 0; i < suffix.length(); i++) {
            final char c = suffix.charAt(i);
            final boolean alphanumeric = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z');
            if (!alphanumeric) {
                throw new IllegalArgumentException(
                        "suffix must be alphanumeric [A-Za-z0-9] — path separators, dots, and "
                                + "other characters are rejected to prevent path traversal "
                                + "(suffix='" + suffix + "', offending char index=" + i + ")");
            }
        }
        final Path fileName = shardPath.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("shardPath must have a file name component");
        }
        final String tempName = fileName + "." + suffix + ".tmp";
        return shardPath.resolveSibling(tempName);
    }

    /** RFC 4648 base32 encoder, uppercase, no padding. */
    private static String encodeBase32(byte[] input) {
        assert input != null : "input must not be null";
        if (input.length == 0) {
            return "";
        }
        // Output length = ceil(input.length * 8 / 5)
        final int outLen = (input.length * 8 + 4) / 5;
        final char[] out = new char[outLen];
        int bitBuffer = 0;
        int bitsInBuffer = 0;
        int outPos = 0;
        for (byte b : input) {
            bitBuffer = (bitBuffer << 8) | (b & 0xFF);
            bitsInBuffer += 8;
            while (bitsInBuffer >= 5) {
                bitsInBuffer -= 5;
                final int idx = (bitBuffer >> bitsInBuffer) & 0x1F;
                out[outPos++] = BASE32_ALPHABET[idx];
            }
        }
        if (bitsInBuffer > 0) {
            final int idx = (bitBuffer << (5 - bitsInBuffer)) & 0x1F;
            out[outPos++] = BASE32_ALPHABET[idx];
        }
        assert outPos == outLen
                : "base32 output length mismatch: got " + outPos + " expected " + outLen;
        return new String(out);
    }
}
