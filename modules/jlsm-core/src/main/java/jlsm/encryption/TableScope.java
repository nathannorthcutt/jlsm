package jlsm.encryption;

import java.util.Objects;

/**
 * Triple-identity scope binding an SSTable / Table to its three-tier key hierarchy address:
 * {@code (tenantId, domainId, tableId)}. Used by the SSTable footer scope-signalling layer to
 * identify which DEK family a file's ciphertext belongs to, and by the encryption read path to
 * reject cross-scope SSTables before any DEK lookup fires.
 *
 * <p>
 * Receives: three identity records — {@link TenantId}, {@link DomainId}, {@link TableId}.<br>
 * Returns: an immutable record value usable as a map key, in {@code equals} comparisons, and inside
 * encryption AAD.<br>
 * Side effects: none.<br>
 * Error conditions: {@link NullPointerException} on any null component.<br>
 * Shared state: none.
 *
 * <p>
 * Governing spec: {@code sstable.footer-encryption-scope} R8b, R8c, R8d.<br>
 * Governing ADR: {@code .decisions/table-handle-scope-exposure/adr.md} v2.
 *
 * @param tenantId non-null tenant identifier
 * @param domainId non-null data-domain identifier
 * @param tableId non-null table identifier
 *
 * @spec sstable.footer-encryption-scope.R8b
 * @spec sstable.footer-encryption-scope.R8c
 * @spec sstable.footer-encryption-scope.R8d
 */
public record TableScope(TenantId tenantId, DomainId domainId, TableId tableId) {

    /**
     * @throws NullPointerException if {@code tenantId}, {@code domainId}, or {@code tableId} is
     *             null
     * @spec sstable.footer-encryption-scope.R8b
     */
    public TableScope {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(domainId, "domainId must not be null");
        Objects.requireNonNull(tableId, "tableId must not be null");
    }

    /**
     * Returns a redacted string form that does NOT expose the raw component values directly.
     * Delegates to each component's own {@code toString()} — {@link TenantId}, {@link DomainId},
     * and {@link TableId} all redact their underlying values, so the composite scope inherits that
     * redaction. Closes the R12 error-message-leak avenue: a {@code TableScope} embedded in an
     * exception or log line never surfaces tenant/domain/table raw text via the default record
     * {@code toString} interpolation.
     *
     * @spec sstable.footer-encryption-scope.R12
     */
    @Override
    public String toString() {
        return "TableScope[tenantId=" + tenantId + ", domainId=" + domainId + ", tableId=" + tableId
                + "]";
    }
}
