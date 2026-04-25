package jlsm.engine;

import java.util.Objects;

import jlsm.encryption.TableScope;

/**
 * Per-table encryption metadata held inside {@link TableMetadata}. Currently carries the
 * {@link TableScope} only; future encryption-related per-table facts (allowed-DEK-version window,
 * KEK alias, rotation policy, etc.) compose into this record without breaking the
 * {@code TableMetadata} surface.
 *
 * <p>
 * Receives: a non-null {@link TableScope}.<br>
 * Returns: an immutable record value safe to publish via {@code AtomicReference} during the
 * {@code enableEncryption} 5-step protocol (R7b step 5).<br>
 * Side effects: none.<br>
 * Error conditions: {@link NullPointerException} on null scope.<br>
 * Shared state: none.
 *
 * <p>
 * Governing spec: {@code sstable.footer-encryption-scope} R8a.<br>
 * Governing ADR: {@code .decisions/table-handle-scope-exposure/adr.md} v2.
 *
 * @param scope non-null triple-identity scope for the table
 *
 * @spec sstable.footer-encryption-scope.R8a
 */
public record EncryptionMetadata(TableScope scope) {

    /**
     * @throws NullPointerException if {@code scope} is null
     * @spec sstable.footer-encryption-scope.R8a
     */
    public EncryptionMetadata {
        Objects.requireNonNull(scope, "scope must not be null");
    }
}
