package jlsm.encryption;

import java.util.Objects;
import java.util.Set;

/**
 * Per-read context value threaded from the SSTable reader through the document deserializer into
 * the field-encryption dispatch. Carries the materialised, constant-time-lookup set of DEK versions
 * declared in the SSTable v6 footer; the dispatch checks every envelope's DEK-version field against
 * this set BEFORE invoking the DEK resolver, closing the attack where envelope-header reads could
 * touch DEK material for a version not declared in the footer.
 *
 * <p>
 * Receives: non-null {@code Set<Integer>} of allowed DEK versions (typically produced by
 * {@code V6Footer.materialiseDekVersionSet}).<br>
 * Returns: an immutable record value safe to share across threads for the lifetime of an SSTable
 * read pass.<br>
 * Side effects: none.<br>
 * Error conditions: {@link NullPointerException} on null {@code allowedDekVersions}.<br>
 * Shared state: none.
 *
 * <p>
 * The accessor returns the same defensively-copied unmodifiable view across calls — hot-path R3e
 * dispatch checks must not pay an allocation per envelope.
 *
 * <p>
 * <b>Producer / consumer:</b> WU-2 ({@code TrieSSTableReader}) constructs and returns this record
 * after parsing the v6 footer. WU-4 ({@code FieldEncryptionDispatch} via
 * {@code DocumentSerializer}) consumes {@link #allowedDekVersions()} as the R3e gate. <!--
 * TODO(WU-2): SSTable reader produces ReadContext from V6Footer.materialiseDekVersionSet --> <!--
 * TODO(WU-4): FieldEncryptionDispatch consumes allowedDekVersions for the R3e dispatch gate -->
 *
 * <p>
 * Governing spec: {@code sstable.footer-encryption-scope} R3e.
 *
 * @param allowedDekVersions defensively-copied unmodifiable set of declared DEK versions
 *
 * @spec sstable.footer-encryption-scope.R3e
 */
public record ReadContext(Set<Integer> allowedDekVersions) {

    /**
     * Canonical constructor — eagerly null-checks and defensively copies the input set into an
     * unmodifiable view. The copy decouples the record's invariant from any subsequent mutation of
     * the caller-supplied set; the unmodifiable wrapper prevents downstream consumers from mutating
     * it through the accessor.
     *
     * @throws NullPointerException if {@code allowedDekVersions} is null
     * @spec sstable.footer-encryption-scope.R3e
     */
    public ReadContext(Set<Integer> allowedDekVersions) {
        Objects.requireNonNull(allowedDekVersions, "allowedDekVersions must not be null");
        // Defensive copy via Set.copyOf — produces an unmodifiable, snapshot Set<Integer>.
        // Accessor returns this same instance across calls so hot-path R3e checks pay zero
        // allocation per envelope dispatch.
        this.allowedDekVersions = Set.copyOf(allowedDekVersions);
    }
}
