package jlsm.core.io;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

import jlsm.encryption.ReadContext;

/**
 * Serializes objects of type {@code T} to and from {@link MemorySegment}.
 *
 * @param <T> the type to serialize
 */
public interface MemorySerializer<T> {

    /**
     * Serializes {@code value} into a {@link MemorySegment}.
     *
     * @param value the object to serialize; must not be null
     * @return a {@link MemorySegment} containing the serialized bytes; never null
     */
    MemorySegment serialize(T value);

    /**
     * Deserializes a {@link MemorySegment} into an object of type {@code T}.
     *
     * @param segment the segment to deserialize; must not be null
     * @return the deserialized object; never null
     */
    T deserialize(MemorySegment segment);

    /**
     * Deserializes a {@link MemorySegment} into an object of type {@code T}, threading the SSTable
     * read-time {@link ReadContext} through to any encryption dispatch on the read path.
     * Implementations carrying field-level encryption MUST consult
     * {@code readContext.allowedDekVersions()} BEFORE invoking the underlying DEK resolver, per
     * {@code sstable.footer-encryption-scope.R3e}/{@code R3f}.
     *
     * <p>
     * The default implementation delegates to {@link #deserialize(MemorySegment)}; serializers that
     * have no encryption dispatch (or whose dispatch is intentionally context-free) are unaffected.
     * Serializers that do encryption must override this method to enforce the R3e gate.
     *
     * @param segment the segment to deserialize; must not be null
     * @param readContext per-read context whose {@code allowedDekVersions} bounds which DEK
     *            versions may be observed on this read pass; must not be null
     * @return the deserialized object; never null
     * @throws NullPointerException if either argument is null
     * @spec sstable.footer-encryption-scope.R3e
     * @spec sstable.footer-encryption-scope.R3f
     */
    default T deserialize(MemorySegment segment, ReadContext readContext) {
        Objects.requireNonNull(segment, "segment must not be null");
        Objects.requireNonNull(readContext, "readContext must not be null");
        return deserialize(segment);
    }
}
