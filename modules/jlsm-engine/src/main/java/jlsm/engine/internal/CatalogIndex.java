package jlsm.engine.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persistent index of {@code (tableName -> meta_format_version_highwater)} entries used to defend
 * against R9a-mono format downgrade across cold starts. The index is the authoritative answer to
 * "does this table exist" — a missing entry means the table does not exist regardless of any
 * {@code table.meta} file that may still be on disk; that blocks an attacker (or a buggy
 * backup-restore flow) from reintroducing a stale lower-version {@code table.meta} after the
 * catalog has advanced.
 *
 * <p>
 * On-disk format: a small binary file at {@code <root>/catalog-index.bin} containing
 * {@code [magic:u32 BE][entryCount:u32 BE]} followed by {@code [nameLen:u16 BE][nameBytes]
 * [version:u32 BE]} per entry. Persistence is atomic via write-to-temp + fsync + rename, with a
 * non-atomic fallback for filesystems that do not support {@code ATOMIC_MOVE}.
 *
 * <p>
 * Receives: a catalog root {@link Path} (constructor); table names + version ints (mutators).<br>
 * Returns: {@code Optional<Integer>} from {@link #highwater}; void from mutators.<br>
 * Side effects: write-temp + fsync + atomic rename for every mutation; idempotent cold-start load
 * on construction.<br>
 * Error conditions: {@link IOException} on I/O failure; {@link NullPointerException} on null
 * inputs.<br>
 * Shared state: the on-disk index file under the catalog root.
 *
 * <p>
 * Governing spec: {@code sstable.footer-encryption-scope} R9a-mono.
 *
 * @spec sstable.footer-encryption-scope.R9a-mono
 */
final class CatalogIndex {

    private static final String INDEX_FILENAME = "catalog-index.bin";
    private static final int MAGIC = 0x4A4C434C; // "JLCL" — JLsm Catalog index
    private static final int MAX_NAME_BYTES = 65535;

    private final Path indexPath;
    private final ConcurrentHashMap<String, Integer> entries = new ConcurrentHashMap<>();
    /** Serialises persistence to keep write-temp + rename atomic across concurrent mutations. */
    private final ReentrantLock persistLock = new ReentrantLock();

    /**
     * Loads (or initialises) the catalog index under the given root directory. Cold-start pass
     * treats a missing index file as "no tables exist," not "all tables exist".
     *
     * @param rootDir non-null catalog root directory
     * @throws NullPointerException if {@code rootDir} is null
     * @throws IOException on I/O failure parsing an existing index
     */
    CatalogIndex(Path rootDir) throws IOException {
        Objects.requireNonNull(rootDir, "rootDir must not be null");
        if (!Files.exists(rootDir)) {
            Files.createDirectories(rootDir);
        }
        this.indexPath = rootDir.resolve(INDEX_FILENAME);
        loadIfExists();
    }

    private void loadIfExists() throws IOException {
        if (!Files.exists(indexPath)) {
            return; // cold start — empty index = "no tables exist" (R9a-mono)
        }
        final byte[] bytes = Files.readAllBytes(indexPath);
        if (bytes.length < 8) {
            throw new IOException("catalog index file too short to be valid");
        }
        final ByteBuffer in = ByteBuffer.wrap(bytes);
        final int magic = in.getInt();
        if (magic != MAGIC) {
            throw new IOException("catalog index file has unexpected magic");
        }
        final int count = in.getInt();
        if (count < 0) {
            throw new IOException("catalog index file declares a negative entry count");
        }
        final Map<String, Integer> loaded = new HashMap<>(count * 2);
        for (int i = 0; i < count; i++) {
            if (in.remaining() < 2) {
                throw new IOException("catalog index file truncated at entry " + i);
            }
            final int nameLen = Short.toUnsignedInt(in.getShort());
            if (nameLen < 1 || nameLen > MAX_NAME_BYTES) {
                throw new IOException("catalog index file declares an invalid name length");
            }
            if (in.remaining() < nameLen + 4) {
                throw new IOException("catalog index file truncated reading entry " + i);
            }
            final byte[] nameBytes = new byte[nameLen];
            in.get(nameBytes);
            final String name = new String(nameBytes, StandardCharsets.UTF_8);
            final int version = in.getInt();
            if (version < 0) {
                throw new IOException(
                        "catalog index file declares a negative version for table '" + name + "'");
            }
            loaded.put(name, version);
        }
        entries.putAll(loaded);
    }

    /**
     * Returns the high-water meta-format-version for {@code tableName}, or {@link Optional#empty()}
     * if no entry exists.
     *
     * @param tableName non-null logical table name
     * @return high-water version, or empty if the table is unknown to the index
     * @throws NullPointerException if {@code tableName} is null
     * @spec sstable.footer-encryption-scope.R9a-mono
     */
    Optional<Integer> highwater(String tableName) {
        Objects.requireNonNull(tableName, "tableName must not be null");
        final Integer v = entries.get(tableName);
        return v == null ? Optional.empty() : Optional.of(v);
    }

    /**
     * Sets the high-water for {@code tableName} atomically (write-temp + fsync + rename). Calls
     * with a strictly-lower {@code version} than the current entry must be rejected to enforce
     * monotonicity.
     *
     * @param tableName non-null logical table name
     * @param version non-negative meta-format-version high-water
     * @throws IOException on I/O failure or attempted downgrade
     * @throws NullPointerException if {@code tableName} is null
     * @throws IllegalArgumentException if {@code version} is negative
     * @spec sstable.footer-encryption-scope.R9a-mono
     */
    void setHighwater(String tableName, int version) throws IOException {
        Objects.requireNonNull(tableName, "tableName must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative; got " + version);
        }
        persistLock.lock();
        try {
            final Integer prev = entries.get(tableName);
            if (prev != null && version < prev) {
                throw new IOException("catalog index downgrade rejected for table '" + tableName
                        + "' (R9a-mono)");
            }
            entries.put(tableName, version);
            try {
                persistAtomically();
            } catch (IOException | RuntimeException e) {
                // Roll back the in-memory mutation so memory and disk stay in sync.
                if (prev == null) {
                    entries.remove(tableName);
                } else {
                    entries.put(tableName, prev);
                }
                throw e;
            }
        } finally {
            persistLock.unlock();
        }
    }

    /**
     * Removes the entry for {@code tableName} atomically.
     *
     * @param tableName non-null logical table name
     * @throws IOException on I/O failure
     * @throws NullPointerException if {@code tableName} is null
     */
    void remove(String tableName) throws IOException {
        Objects.requireNonNull(tableName, "tableName must not be null");
        persistLock.lock();
        try {
            final Integer prev = entries.remove(tableName);
            if (prev == null) {
                return;
            }
            try {
                persistAtomically();
            } catch (IOException | RuntimeException e) {
                // Roll back to keep memory consistent with the on-disk state.
                entries.put(tableName, prev);
                throw e;
            }
        } finally {
            persistLock.unlock();
        }
    }

    private void persistAtomically() throws IOException {
        final byte[] payload = encode();
        final Path temp = indexPath.resolveSibling(INDEX_FILENAME + ".tmp");
        try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            final ByteBuffer buf = ByteBuffer.wrap(payload);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }
        try {
            Files.move(temp, indexPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, indexPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private byte[] encode() {
        // Pre-compute capacity.
        int total = 4 + 4;
        for (Map.Entry<String, Integer> e : entries.entrySet()) {
            final byte[] nameBytes = e.getKey().getBytes(StandardCharsets.UTF_8);
            total += 2 + nameBytes.length + 4;
        }
        final ByteBuffer out = ByteBuffer.allocate(total);
        out.putInt(MAGIC);
        out.putInt(entries.size());
        for (Map.Entry<String, Integer> e : entries.entrySet()) {
            final byte[] nameBytes = e.getKey().getBytes(StandardCharsets.UTF_8);
            if (nameBytes.length < 1 || nameBytes.length > MAX_NAME_BYTES) {
                throw new IllegalStateException(
                        "catalog index name has invalid length: " + nameBytes.length);
            }
            out.putShort((short) nameBytes.length);
            out.put(nameBytes);
            out.putInt(e.getValue());
        }
        return out.array();
    }
}
