package jlsm.table;

import jlsm.core.io.MemorySerializer;

/**
 * Placeholder stub for a {@link MemorySerializer} that handles {@link JlsmDocument} instances.
 *
 * <p>
 * The full implementation will be provided in Task 2.
 */
public final class DocumentSerializer {

    private DocumentSerializer() {
    }

    /**
     * Returns a serializer for {@link JlsmDocument} values conforming to the given schema.
     *
     * @param schema the schema describing the document structure; must not be null
     * @return a MemorySerializer for JlsmDocument
     * @throws UnsupportedOperationException always — not yet implemented
     */
    public static MemorySerializer<JlsmDocument> forSchema(JlsmSchema schema) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
