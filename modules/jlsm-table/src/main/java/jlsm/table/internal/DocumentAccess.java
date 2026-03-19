package jlsm.table.internal;

import jlsm.table.JlsmDocument;
import jlsm.table.JlsmSchema;

/**
 * Friend-accessor bridge that grants {@code jlsm.table.internal} code access to package-private
 * members of {@link JlsmDocument}.
 *
 * <p>
 * {@link JlsmDocument} registers an implementation of {@link Accessor} via
 * {@link #setAccessor(Accessor)} during its static initialiser. Internal classes retrieve the
 * accessor via {@link #get()} and use it to call package-private operations.
 *
 * <p>
 * This pattern avoids widening the visibility of package-private members in the public API while
 * still allowing the {@code jlsm.table.internal} package to operate on document internals.
 */
public final class DocumentAccess {

    /** Provides access to package-private members of {@link JlsmDocument}. */
    public interface Accessor {

        /**
         * Returns the raw values array of the document (in schema-field order).
         *
         * @param doc the document; must not be null
         * @return the internal values array
         */
        Object[] values(JlsmDocument doc);

        /**
         * Constructs a {@link JlsmDocument} directly from a schema and pre-built values array.
         *
         * @param schema the schema; must not be null
         * @param values the values in schema-field order; must not be null
         * @return a new JlsmDocument
         */
        JlsmDocument create(JlsmSchema schema, Object[] values);

        /**
         * Returns whether the document was constructed with pre-encrypted ciphertext values.
         *
         * @param doc the document; must not be null
         * @return true if the document is pre-encrypted
         */
        boolean isPreEncrypted(JlsmDocument doc);
    }

    private static volatile Accessor accessor;

    private DocumentAccess() {
    }

    /**
     * Registers the accessor implementation. Must be called exactly once by {@link JlsmDocument}'s
     * static initialiser before any other use.
     *
     * @param a the accessor; must not be null
     * @throws IllegalStateException if called more than once
     */
    public static void setAccessor(Accessor a) {
        if (a == null) {
            throw new IllegalArgumentException("accessor must not be null");
        }
        if (accessor != null) {
            throw new IllegalStateException("DocumentAccess.accessor already set");
        }
        accessor = a;
    }

    /**
     * Returns the registered accessor.
     *
     * @return the accessor; never null after class initialisation completes
     * @throws IllegalStateException if the accessor has not been registered yet
     */
    public static Accessor get() {
        final Accessor a = accessor;
        if (a == null) {
            // Force JlsmDocument to load so it registers the accessor
            try {
                Class.forName("jlsm.table.JlsmDocument");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("JlsmDocument class not found", e);
            }
        }
        final Accessor loaded = accessor;
        if (loaded == null) {
            throw new IllegalStateException(
                    "DocumentAccess.accessor not initialised; JlsmDocument static init may have failed");
        }
        return loaded;
    }
}
