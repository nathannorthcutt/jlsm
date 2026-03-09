package jlsm.core.io;

import java.lang.foreign.MemorySegment;

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
}
