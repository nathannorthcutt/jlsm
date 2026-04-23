package jlsm.encryption.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Contract: Arena-backed off-heap storage for encryption key material. Accepts a {@code byte[]}
 * key, copies it to an off-heap {@link MemorySegment}, and zeros the caller's copy. On
 * {@link #close()}, the key segment is zeroed ({@code fill(0)}) and the arena is closed.
 *
 * <p>
 * Uses {@link Arena#ofShared()} to allow concurrent read access from multiple table reader threads.
 * Close is idempotent via an {@link AtomicBoolean} CAS guard.
 *
 * <p>
 * Governed by: .kb/systems/security/jvm-key-handling-patterns.md
 */
public final class OffHeapKeyMaterial implements AutoCloseable {

    private static final int KEY_256_BYTES = 32;
    private static final int KEY_512_BYTES = 64;

    private final Arena arena;
    private final MemorySegment keySegment;
    private final int keyLength;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    private OffHeapKeyMaterial(Arena arena, MemorySegment keySegment, int keyLength) {
        assert arena != null : "arena must not be null";
        assert keySegment != null : "keySegment must not be null";
        assert keyLength == KEY_256_BYTES || keyLength == KEY_512_BYTES
                : "keyLength must be 32 or 64";
        this.arena = arena;
        this.keySegment = keySegment;
        this.keyLength = keyLength;
    }

    /**
     * Creates a new key holder from the given key material. The caller's array is zeroed after the
     * key is copied to off-heap memory.
     *
     * @param keyMaterial the raw key bytes; must be 32 or 64 bytes
     * @return a new key holder owning the off-heap copy
     * @throws NullPointerException if keyMaterial is null
     * @throws IllegalArgumentException if keyMaterial is not 32 or 64 bytes
     */
    public static OffHeapKeyMaterial of(byte[] keyMaterial) {
        Objects.requireNonNull(keyMaterial, "keyMaterial must not be null");
        if (keyMaterial.length != KEY_256_BYTES && keyMaterial.length != KEY_512_BYTES) {
            throw new IllegalArgumentException(
                    "Key must be 32 or 64 bytes (256 or 512 bit), got " + keyMaterial.length);
        }

        final Arena arena = Arena.ofShared();
        final MemorySegment segment = arena.allocate(keyMaterial.length);
        MemorySegment.copy(keyMaterial, 0, segment, ValueLayout.JAVA_BYTE, 0, keyMaterial.length);

        // Zero the caller's copy
        Arrays.fill(keyMaterial, (byte) 0);

        return new OffHeapKeyMaterial(arena, segment, keyMaterial.length);
    }

    /**
     * Returns a temporary byte[] copy of the key material. Callers must zero this array after use.
     *
     * @return a fresh byte[] copy of the key
     * @throws IllegalStateException if this holder has been closed
     */
    public byte[] getKeyBytes() {
        rwLock.readLock().lock();
        try {
            ensureOpen();
            final byte[] copy = new byte[keyLength];
            MemorySegment.copy(keySegment, ValueLayout.JAVA_BYTE, 0, copy, 0, keyLength);
            return copy;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Returns a read-only copy of the key segment. The returned segment is heap-backed and
     * independent of this holder's arena — it remains valid after {@link #close()}.
     *
     * @return a heap-backed read-only copy of the key memory segment
     * @throws IllegalStateException if this holder has been closed
     */
    public MemorySegment keySegment() {
        rwLock.readLock().lock();
        try {
            ensureOpen();
            final MemorySegment copy = Arena.ofAuto().allocate(keyLength);
            copy.copyFrom(keySegment.asSlice(0, keyLength));
            return copy.asReadOnly();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Returns the key length in bytes.
     *
     * @return key length
     * @throws IllegalStateException if this holder has been closed
     */
    public int keyLength() {
        rwLock.readLock().lock();
        try {
            ensureOpen();
            return keyLength;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // another thread already closed
        }
        rwLock.writeLock().lock();
        try {
            // Zero the key material before releasing the arena
            keySegment.fill((byte) 0);
            arena.close();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("OffHeapKeyMaterial has been closed");
        }
    }
}
