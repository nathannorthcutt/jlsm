package jlsm.encryption;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import jlsm.encryption.internal.AesGcmContextWrap;
import jlsm.encryption.internal.Hkdf;
import jlsm.encryption.internal.KeyRegistryShard;
import jlsm.encryption.internal.TenantShardRegistry;

/**
 * Three-tier envelope facade for jlsm's at-rest encryption pipeline. This is the F41 v6 facade
 * introduced in WD-01 of {@code implement-encryption-lifecycle}; it supersedes the previous
 * per-field-key holder (renamed to {@link jlsm.encryption.internal.OffHeapKeyMaterial}) by adding
 * the tier-1 Tenant KEK and tier-2 Domain KEK layers between raw key material and the per-field
 * derivation already used by the encryptors.
 *
 * <h2>Three-tier envelope</h2>
 *
 * <pre>
 *   Tenant KEK (tier-1, KMS-managed)
 *     &#x2514;&#x2500; wraps &#x2500;&#x2500;&#x25B6; Domain KEK (tier-2, persisted wrapped, cached unwrapped)
 *                     &#x2514;&#x2500; wraps &#x2500;&#x2500;&#x25B6; DEK (tier-3, per scope + version)
 *                                     &#x2514;&#x2500; HKDF-derives &#x2500;&#x2500;&#x25B6; per-field subkey
 * </pre>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 * <li>{@link #openDomain} unwraps and caches the tier-2 Domain KEK for a given
 * {@code (tenant, domain)}. Must precede any DEK operation in that scope.</li>
 * <li>{@link #currentDek} / {@link #resolveDek} locate a DekHandle for an in-use or
 * specific-version DEK.</li>
 * <li>{@link #generateDek} produces a new DEK and persists its wrapped bytes; typical callers do
 * this at table-create or on policy-triggered rotation.</li>
 * <li>{@link #deriveFieldKey} turns a DekHandle plus field identifiers into a freshly-derived
 * per-field key in a caller-provided {@link Arena}.</li>
 * </ol>
 *
 * <h2>Zeroization</h2> {@link #close} zeroizes cached domain-KEK and DEK segments and closes the
 * registry. Callers holding field-key segments derived from this facade continue to own those
 * segments' lifetimes via their own {@code Arena}s (R66, R69).
 *
 * <p>
 * Governed by: spec {@code encryption.primitives-lifecycle} R9, R10a, R10b, R17, R21, R29, R55,
 * R62, R62a, R66–R69; ADRs {@code .decisions/three-tier-key-hierarchy/adr.md},
 * {@code .decisions/dek-scoping-granularity/adr.md},
 * {@code .decisions/kms-integration-model/adr.md}.
 */
public final class EncryptionKeyHolder implements AutoCloseable {

    /** DEK plaintext length in bytes (AES-256). */
    private static final int DEK_BYTES = 32;
    /** Domain KEK plaintext length in bytes (AES-256). */
    private static final int DOMAIN_KEK_BYTES = 32;

    private final KmsClient kmsClient;
    private final TenantShardRegistry registry;
    private final byte[] hkdfSalt;
    private final KekRef activeTenantKekRef;
    private final Duration cacheTtl;
    private final Clock clock;
    private final SecureRandom rng;

    private final Arena cacheArena;
    private final ConcurrentHashMap<DomainKey, CachedDomainKek> domainCache = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    // Separate flag: the set of tenants whose persisted hkdfSalt we have verified.
    private final ConcurrentHashMap<TenantId, Boolean> saltVerified = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock deriveGuard = new ReentrantReadWriteLock();

    private EncryptionKeyHolder(Builder builder) {
        this.kmsClient = builder.kmsClient;
        this.registry = builder.registry;
        this.hkdfSalt = builder.hkdfSalt != null ? builder.hkdfSalt.clone() : new byte[32];
        this.activeTenantKekRef = builder.activeTenantKekRef;
        this.cacheTtl = builder.cacheTtl != null ? builder.cacheTtl : Duration.ofMinutes(30);
        this.clock = builder.clock != null ? builder.clock : Clock.systemUTC();
        this.rng = builder.rng != null ? builder.rng : new SecureRandom();
        this.cacheArena = Arena.ofShared();
    }

    /** Start a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Ensure the tier-2 Domain KEK for {@code (tenantId, domainId)} is unwrapped and cached. Must
     * be called before any DEK operation in that scope (R55).
     *
     * @throws NullPointerException if either argument is null
     * @throws KmsException if the KMS call fails
     * @throws IOException on registry-shard I/O failure
     * @throws IllegalStateException if this holder has been closed
     */
    public void openDomain(TenantId tenantId, DomainId domainId) throws KmsException, IOException {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(domainId, "domainId must not be null");
        // Hold the derive read lock for the entire cache-mutating window so close() (which
        // takes the write lock and closes cacheArena) waits for this operation to finish
        // before releasing the arena (R62a). requireOpen is re-checked after the lock is
        // held to refuse work scheduled after close began.
        deriveGuard.readLock().lock();
        try {
            requireOpen();
            verifySaltForTenant(tenantId);
            final DomainKey key = new DomainKey(tenantId, domainId);
            final CachedDomainKek cached = domainCache.get(key);
            if (cached != null && !cached.isExpired(clock.instant())) {
                return; // idempotent hit
            }
            loadOrProvisionDomainKek(tenantId, domainId, key);
        } finally {
            deriveGuard.readLock().unlock();
        }
    }

    /**
     * Return the handle for the highest-version DEK in the given scope.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalStateException if no DEK exists for the scope or the holder is closed
     * @throws UncheckedIOException if the underlying registry I/O fails transiently (retryable);
     *             preserves the {@link IOException} category so callers can distinguish transient
     *             I/O failures from permanent state faults per {@code io-internals.md}
     */
    public DekHandle currentDek(TenantId tenantId, DomainId domainId, TableId tableId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(domainId, "domainId must not be null");
        Objects.requireNonNull(tableId, "tableId must not be null");
        requireOpen();
        final KeyRegistryShard shard;
        try {
            shard = registry.readSnapshot(tenantId);
        } catch (IOException e) {
            // Preserve the IOException category (F-R1.contract_boundaries.1.1): callers
            // distinguishing transient I/O failures (retryable) from permanent state faults
            // (e.g. closed holder, missing DEK) need a typed signal. IllegalStateException would
            // collapse both into the same category, forcing string-matching retry logic.
            throw new UncheckedIOException("failed to read tenant shard", e);
        }
        DekHandle best = null;
        for (DekHandle h : shard.deks().keySet()) {
            if (!h.tenantId().equals(tenantId) || !h.domainId().equals(domainId)
                    || !h.tableId().equals(tableId)) {
                continue;
            }
            if (best == null || h.version().value() > best.version().value()) {
                best = h;
            }
        }
        if (best == null) {
            throw new IllegalStateException("no DEK exists for scope (" + tenantId + ", " + domainId
                    + ", " + tableId + ")");
        }
        return best;
    }

    /**
     * Return the handle for a specific {@link DekVersion} in the given scope.
     *
     * @throws NullPointerException if any argument is null
     * @throws DekNotFoundException if no DEK exists for {@code version} (R57)
     * @throws IllegalStateException if the holder is closed
     */
    public DekHandle resolveDek(TenantId tenantId, DomainId domainId, TableId tableId,
            DekVersion version) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(domainId, "domainId must not be null");
        Objects.requireNonNull(tableId, "tableId must not be null");
        Objects.requireNonNull(version, "version must not be null");
        requireOpen();
        final DekHandle candidate = new DekHandle(tenantId, domainId, tableId, version);
        final KeyRegistryShard shard;
        try {
            shard = registry.readSnapshot(tenantId);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read tenant shard", e);
        }
        if (!shard.deks().containsKey(candidate)) {
            throw DekNotFoundException.forHandle(candidate);
        }
        return candidate;
    }

    /**
     * Generate a new random DEK for the scope, wrap it under the cached Domain KEK (R29), and
     * persist the {@link WrappedDek} in the tenant's registry shard. The new DEK's version is the
     * previous version + 1 (R18), rejecting {@link Integer#MAX_VALUE} (R18a).
     *
     * @return the handle for the newly-generated DEK
     * @throws NullPointerException if any argument is null
     * @throws KmsException if the KMS is not reachable for the domain-KEK lookup
     * @throws IOException on persistence failure
     * @throws IllegalStateException on version overflow, closed holder, or missing open domain
     */
    public DekHandle generateDek(TenantId tenantId, DomainId domainId, TableId tableId)
            throws KmsException, IOException {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(domainId, "domainId must not be null");
        Objects.requireNonNull(tableId, "tableId must not be null");
        // Hold the derive read lock across the wrap-under-domain-KEK window: the cached
        // domain-KEK plaintext lives in cacheArena, and close() closes cacheArena under
        // the write lock (R62a). Without this guard, a concurrent close could invalidate
        // domainKekSegment mid-wrap.
        deriveGuard.readLock().lock();
        try {
            requireOpen();
            final DomainKey dk = new DomainKey(tenantId, domainId);
            final CachedDomainKek domainKek = domainCache.get(dk);
            if (domainKek == null || domainKek.isExpired(clock.instant())) {
                throw new IllegalStateException("openDomain(" + tenantId + ", " + domainId
                        + ") must be called before generateDek");
            }
            final MemorySegment domainKekSegment = domainKek.plaintext();
            final int domainKekVersion = domainKek.version();

            // Pre-generate DEK plaintext — the mutator captures it via closure.
            final byte[] dekPlaintext = new byte[DEK_BYTES];
            rng.nextBytes(dekPlaintext);
            try {
                return registry.updateShard(tenantId, current -> {
                    final int nextVersion = nextDekVersion(current, tenantId, domainId, tableId);
                    final DekVersion ver = new DekVersion(nextVersion);
                    final DekHandle handle = new DekHandle(tenantId, domainId, tableId, ver);
                    final EncryptionContext ctx = EncryptionContext.forDek(tenantId, domainId,
                            tableId, ver);
                    final byte[] wrapped;
                    try (Arena scratch = Arena.ofConfined()) {
                        final MemorySegment dekSeg = scratch.allocate(DEK_BYTES);
                        MemorySegment.copy(dekPlaintext, 0, dekSeg, ValueLayout.JAVA_BYTE, 0,
                                DEK_BYTES);
                        wrapped = AesGcmContextWrap.wrap(domainKekSegment, dekSeg, ctx, rng);
                        dekSeg.fill((byte) 0);
                    }
                    final KekRef effectiveRef = current.activeTenantKekRef() != null
                            ? current.activeTenantKekRef()
                            : activeTenantKekRef;
                    final WrappedDek wd = new WrappedDek(handle, wrapped, domainKekVersion,
                            effectiveRef, clock.instant());
                    final KeyRegistryShard newShard = current.withDek(wd);
                    return new TenantShardRegistry.ShardUpdate<>(newShard, handle);
                });
            } finally {
                Arrays.fill(dekPlaintext, (byte) 0);
            }
        } finally {
            deriveGuard.readLock().unlock();
        }
    }

    /**
     * Derive a per-field key from the DEK identified by {@code handle} using HKDF-SHA256 with a
     * length-prefixed {@code info} built from {@code (tenantId, domainId, tableName, fieldName,
     * version)} (R9, R11). The returned segment is allocated in {@code callerArena} and is
     * independent of this facade's internal cache.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code tableName} or {@code fieldName} is empty
     * @throws IllegalStateException if the holder is closed or the domain has not been opened (R55)
     */
    public MemorySegment deriveFieldKey(DekHandle handle, String tableName, String fieldName,
            int outLenBytes, Arena callerArena) {
        Objects.requireNonNull(handle, "handle must not be null");
        Objects.requireNonNull(tableName, "tableName must not be null");
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        Objects.requireNonNull(callerArena, "callerArena must not be null");

        deriveGuard.readLock().lock();
        try {
            requireOpen();
            final DomainKey dk = new DomainKey(handle.tenantId(), handle.domainId());
            final CachedDomainKek domainKek = domainCache.get(dk);
            if (domainKek == null || domainKek.isExpired(clock.instant())) {
                throw new IllegalStateException("openDomain(" + handle.tenantId() + ", "
                        + handle.domainId() + ") must be called before deriveFieldKey");
            }

            try (Arena dekArena = Arena.ofConfined()) {
                final MemorySegment dekPlaintext = unwrapDek(handle, domainKek, dekArena);
                try {
                    final byte[] info = Hkdf.buildFieldKeyInfo(handle.tenantId(), handle.domainId(),
                            tableName, fieldName, handle.version());
                    return Hkdf.deriveKey(dekPlaintext, hkdfSalt, info, outLenBytes, callerArena);
                } finally {
                    // Zero the DEK plaintext before the arena closes. Arena close alone does not
                    // guarantee zeroization on all JVMs (deterministic release but GC timing
                    // variance), so we zero explicitly per R66/R69.
                    dekPlaintext.fill((byte) 0);
                }
            }
        } finally {
            deriveGuard.readLock().unlock();
        }
    }

    /**
     * Zeroize all cached key material, close the internal registry, and release the shared cache
     * arena. Idempotent. Waits for in-flight derive callers before closing the shared arena (R62a).
     *
     * <p>
     * Follows the deferred-close pattern per {@code coding-guidelines.md}: all resources are
     * attempted even when earlier steps throw, and accumulated failures are surfaced as a single
     * {@link IllegalStateException} (with additional failures attached as suppressed exceptions).
     * A failed zeroization (R66/R69) or arena close must not be silently swallowed — callers need
     * the signal so they know plaintext KEK bytes may remain in memory or off-heap storage may be
     * leaked until JVM exit.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Block new derives and wait for in-flight ones to complete (R62a).
        deriveGuard.writeLock().lock();
        Throwable primary = null;
        try {
            // Zero all cached domain-KEK segments explicitly before closing the arena. Accumulate
            // failures rather than swallowing — R66/R69 require callers be informed if
            // zeroization could not complete.
            for (CachedDomainKek cdk : domainCache.values()) {
                try {
                    cdk.plaintext().fill((byte) 0);
                } catch (RuntimeException e) {
                    primary = addFailure(primary, e);
                }
            }
            domainCache.clear();
            try {
                cacheArena.close();
            } catch (RuntimeException e) {
                primary = addFailure(primary, e);
            }
            try {
                registry.close();
            } catch (RuntimeException e) {
                primary = addFailure(primary, e);
            }
        } finally {
            deriveGuard.writeLock().unlock();
        }
        if (primary != null) {
            throw new IllegalStateException(
                    "EncryptionKeyHolder.close encountered failures during resource release "
                            + "(R66/R69 zeroization or arena/registry close) — see cause and "
                            + "suppressed exceptions",
                    primary);
        }
    }

    /**
     * Accumulate a failure during {@link #close}: the first failure becomes the primary cause;
     * subsequent failures are attached as suppressed exceptions so no signal is lost.
     */
    private static Throwable addFailure(Throwable primary, Throwable next) {
        if (primary == null) {
            return next;
        }
        primary.addSuppressed(next);
        return primary;
    }

    // --- internals ------------------------------------------------------

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("EncryptionKeyHolder is closed");
        }
    }

    private void verifySaltForTenant(TenantId tenantId) throws IOException {
        if (saltVerified.containsKey(tenantId)) {
            return;
        }
        final KeyRegistryShard shard = registry.readSnapshot(tenantId);
        final byte[] persistedSalt = shard.hkdfSalt();
        // Empty-shard case: the registry synthesizes a 32-zero-byte salt when no shard exists.
        // We accept either: (1) the persisted salt matches our configured salt, or (2) the
        // persisted salt is still the default-zero placeholder, in which case first-use persists
        // our configured salt to the shard on the next updateShard call. For (2), we mark
        // verified to avoid repeated work, and the subsequent generateDek/openDomain will
        // populate the salt alongside the first DEK/domain-KEK write.
        if (!Arrays.equals(persistedSalt, hkdfSalt)) {
            final boolean persistedIsDefault = isAllZero(persistedSalt);
            if (persistedIsDefault && shard.deks().isEmpty() && shard.domainKeks().isEmpty()) {
                // First-write scenario: no prior key material, so the salt is fresh-ours.
                // We will write it on the next shard mutation.
                persistConfiguredSalt(tenantId);
            } else {
                final String configuredHash = hashPrefix(hkdfSalt);
                final String persistedHash = hashPrefix(persistedSalt);
                throw new IllegalArgumentException("HKDF salt mismatch for tenant '"
                        + tenantId.value() + "': configured prefix=" + configuredHash
                        + " does not match registry prefix=" + persistedHash
                        + " (R10b — silent salt drift would corrupt derivations; "
                        + "raw bytes intentionally omitted)");
            }
        }
        saltVerified.put(tenantId, Boolean.TRUE);
    }

    private void persistConfiguredSalt(TenantId tenantId) throws IOException {
        registry.updateShard(tenantId, current -> {
            // R10b + F-R1.concurrency.1.5: re-check the CURRENT (post-lock) salt state
            // inside the serialized critical section. The pre-check in verifySaltForTenant
            // ran against a pre-lock snapshot that may have been invalidated by a concurrent
            // first-writer with a different salt. Without this re-check, the mutator would
            // silently overwrite a just-committed salt from another holder — splitting the
            // in-memory view (this holder thinks saltVerified=TRUE for hkdfSalt) from the
            // registry's persisted state (winning salt belongs to some other holder), which
            // is the exact silent-drift R10b forbids.
            final byte[] currentSalt = current.hkdfSalt();
            if (Arrays.equals(currentSalt, hkdfSalt)) {
                // Idempotent: another holder already persisted the same salt (or we did, on a
                // benign retry). Nothing to do — return the current shard unchanged.
                return new TenantShardRegistry.ShardUpdate<>(current, null);
            }
            if (!isAllZero(currentSalt) || !current.deks().isEmpty()
                    || !current.domainKeks().isEmpty()) {
                // Lost the race: the shard now holds a non-default salt that is NOT ours, or
                // key material is already present. Refuse to overwrite — surface the same
                // salt-mismatch signal the pre-check would have produced if we had read this
                // snapshot directly.
                final String configuredHash = hashPrefix(hkdfSalt);
                final String persistedHash = hashPrefix(currentSalt);
                throw new IllegalArgumentException("HKDF salt mismatch for tenant '"
                        + current.tenantId().value() + "': configured prefix=" + configuredHash
                        + " does not match registry prefix=" + persistedHash
                        + " (R10b — silent salt drift would corrupt derivations; "
                        + "raw bytes intentionally omitted)");
            }
            final KeyRegistryShard updated = new KeyRegistryShard(current.tenantId(),
                    current.deks(), current.domainKeks(), current.activeTenantKekRef(), hkdfSalt);
            return new TenantShardRegistry.ShardUpdate<>(updated, null);
        });
    }

    private static boolean isAllZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String hashPrefix(byte[] bytes) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(bytes);
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 2; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void loadOrProvisionDomainKek(TenantId tenantId, DomainId domainId, DomainKey key)
            throws KmsException, IOException {
        final KeyRegistryShard shard = registry.readSnapshot(tenantId);
        final WrappedDomainKek wrapped = shard.domainKeks().get(domainId);
        if (wrapped != null) {
            unwrapAndCacheDomainKek(wrapped, key);
            return;
        }
        // No domain KEK yet — provision one via generate + wrap-under-tenant-KEK + persist.
        provisionDomainKek(tenantId, domainId, key);
    }

    private void unwrapAndCacheDomainKek(WrappedDomainKek wrapped, DomainKey key)
            throws KmsException {
        final EncryptionContext ctx = EncryptionContext.forDomainKek(key.tenantId(),
                key.domainId());
        final UnwrapResult unwrap = kmsClient.unwrapKek(ByteBuffer.wrap(wrapped.wrappedBytes()),
                wrapped.tenantKekRef(), ctx);
        try {
            // R55: validate the KMS-boundary return value BEFORE allocating in the long-lived
            // cacheArena or publishing to domainCache. A misbehaving KMS adapter that returns a
            // wrong-length plaintext would otherwise poison the shared cache; the failure would
            // then surface at an unrelated wrap site (AesGcmContextWrap) as an opaque
            // InvalidKeyException rather than a KMS-contract violation at the boundary
            // (F-R1.concurrency.1.6).
            final long plaintextLen = unwrap.plaintext().byteSize();
            if (plaintextLen != DOMAIN_KEK_BYTES) {
                throw new KmsPermanentException("KmsClient.unwrapKek returned plaintext of "
                        + plaintextLen + " bytes; expected " + DOMAIN_KEK_BYTES
                        + " (R55: SPI boundary length validation)");
            }
            // Copy unwrapped plaintext into our long-lived cacheArena (the unwrap arena will be
            // closed shortly).
            final MemorySegment cached = cacheArena.allocate(DOMAIN_KEK_BYTES);
            MemorySegment.copy(unwrap.plaintext(), 0, cached, 0, DOMAIN_KEK_BYTES);
            final Instant expires = clock.instant().plus(cacheTtl);
            domainCache.put(key, new CachedDomainKek(cached, wrapped.version(), expires));
        } finally {
            unwrap.owner().close();
        }
    }

    private void provisionDomainKek(TenantId tenantId, DomainId domainId, DomainKey key)
            throws KmsException, IOException {
        // Generate a 32-byte domain KEK, wrap under the tenant KEK, persist, and cache.
        final byte[] domainKekPlaintext = new byte[DOMAIN_KEK_BYTES];
        rng.nextBytes(domainKekPlaintext);
        final EncryptionContext ctx = EncryptionContext.forDomainKek(tenantId, domainId);
        try (Arena scratch = Arena.ofConfined()) {
            final MemorySegment pt = scratch.allocate(DOMAIN_KEK_BYTES);
            MemorySegment.copy(domainKekPlaintext, 0, pt, ValueLayout.JAVA_BYTE, 0,
                    DOMAIN_KEK_BYTES);
            final WrapResult wrap = kmsClient.wrapKek(pt, activeTenantKekRef, ctx);
            final byte[] wrappedBytes = new byte[wrap.wrappedBytes().remaining()];
            wrap.wrappedBytes().duplicate().get(wrappedBytes);
            // Compute the new version INSIDE the per-tenant-serialized updateShard mutator so
            // two concurrent provisioners cannot both observe an empty shard and commit at the
            // same version (F-R1.concurrency.1.3). The mutator's view of {@code current} is the
            // post-lock snapshot — the later committer detects the earlier commit here.
            final KekRef wrappedKekRef = wrap.kekRef();
            final int version = registry.updateShard(tenantId, current -> {
                final int nextVersion = computeNextDomainKekVersion(current, tenantId, domainId);
                final WrappedDomainKek wdk = new WrappedDomainKek(domainId, nextVersion,
                        wrappedBytes, wrappedKekRef);
                KeyRegistryShard next = current.withDomainKek(wdk);
                if (next.activeTenantKekRef() == null) {
                    next = next.withTenantKekRef(activeTenantKekRef);
                }
                return new TenantShardRegistry.ShardUpdate<>(next, nextVersion);
            });

            // Cache the plaintext in cacheArena.
            final MemorySegment cached = cacheArena.allocate(DOMAIN_KEK_BYTES);
            MemorySegment.copy(pt, 0, cached, 0, DOMAIN_KEK_BYTES);
            final Instant expires = clock.instant().plus(cacheTtl);
            domainCache.put(key, new CachedDomainKek(cached, version, expires));
            pt.fill((byte) 0);
        } finally {
            Arrays.fill(domainKekPlaintext, (byte) 0);
        }
    }

    private static int computeNextDomainKekVersion(KeyRegistryShard shard, TenantId tenantId,
            DomainId domainId) {
        final WrappedDomainKek existing = shard.domainKeks().get(domainId);
        if (existing == null) {
            return 1;
        }
        final int current = existing.version();
        if (current == Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "domain KEK version exhausted for (" + tenantId + ", " + domainId + ")");
        }
        return current + 1;
    }

    private static int nextDekVersion(KeyRegistryShard shard, TenantId tenantId, DomainId domainId,
            TableId tableId) {
        int max = 0;
        for (DekHandle h : shard.deks().keySet()) {
            if (h.tenantId().equals(tenantId) && h.domainId().equals(domainId)
                    && h.tableId().equals(tableId)) {
                max = Math.max(max, h.version().value());
            }
        }
        if (max == Integer.MAX_VALUE) {
            throw new IllegalStateException("DEK version exhausted for scope (" + tenantId + ", "
                    + domainId + ", " + tableId + ") (R18a)");
        }
        return max + 1;
    }

    /**
     * Unwrap the DEK identified by {@code handle} into {@code dekArena}. The caller is responsible
     * for zeroing the returned segment before {@code dekArena} is closed.
     */
    private MemorySegment unwrapDek(DekHandle handle, CachedDomainKek domainKek, Arena dekArena) {
        final KeyRegistryShard shard;
        try {
            shard = registry.readSnapshot(handle.tenantId());
        } catch (IOException e) {
            throw new IllegalStateException("failed to read tenant shard", e);
        }
        final WrappedDek wd = shard.deks().get(handle);
        if (wd == null) {
            throw DekNotFoundException.forHandle(handle);
        }
        final EncryptionContext ctx = EncryptionContext.forDek(handle.tenantId(), handle.domainId(),
                handle.tableId(), handle.version());
        return AesGcmContextWrap.unwrap(domainKek.plaintext(), wd.wrappedBytes(), ctx, DEK_BYTES,
                dekArena);
    }

    /** Internal key: composite of (tenant, domain). */
    private record DomainKey(TenantId tenantId, DomainId domainId) {
    }

    /** Cached unwrapped domain-KEK with expiry and version. */
    private record CachedDomainKek(MemorySegment plaintext, int version, Instant expiresAt) {

        boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    /**
     * Fluent builder for {@link EncryptionKeyHolder}. {@code kmsClient}, {@code registry}, and
     * {@code activeTenantKekRef} are required; {@code hkdfSalt} defaults to 32 zero bytes (R10),
     * {@code cacheTtl} defaults to 30 minutes (kms-integration-model ADR), {@code clock} defaults
     * to {@link Clock#systemUTC}, {@code rng} defaults to a shared {@link SecureRandom}.
     */
    public static final class Builder {

        private KmsClient kmsClient;
        private TenantShardRegistry registry;
        private byte[] hkdfSalt;
        private Duration cacheTtl;
        private KekRef activeTenantKekRef;
        private Clock clock;
        private SecureRandom rng;

        private Builder() {
        }

        /** @throws NullPointerException if {@code kmsClient} is null */
        public Builder kmsClient(KmsClient kmsClient) {
            this.kmsClient = Objects.requireNonNull(kmsClient, "kmsClient must not be null");
            return this;
        }

        /** @throws NullPointerException if {@code registry} is null */
        public Builder registry(TenantShardRegistry registry) {
            this.registry = Objects.requireNonNull(registry, "registry must not be null");
            return this;
        }

        /**
         * Set the active tenant KEK reference — required. Identifies the tier-1 KEK used to
         * wrap/unwrap all domain KEKs in this tenant.
         *
         * @throws NullPointerException if {@code ref} is null
         */
        public Builder activeTenantKekRef(KekRef ref) {
            this.activeTenantKekRef = Objects.requireNonNull(ref,
                    "activeTenantKekRef must not be null");
            return this;
        }

        /**
         * Override the HKDF salt. Defensive copy taken.
         *
         * @throws NullPointerException if {@code hkdfSalt} is null
         * @throws IllegalArgumentException if {@code hkdfSalt} is empty
         */
        public Builder hkdfSalt(byte[] hkdfSalt) {
            Objects.requireNonNull(hkdfSalt, "hkdfSalt must not be null");
            if (hkdfSalt.length == 0) {
                throw new IllegalArgumentException("hkdfSalt must not be empty");
            }
            this.hkdfSalt = hkdfSalt.clone();
            return this;
        }

        /**
         * Override the cache TTL for unwrapped Domain KEKs.
         *
         * @throws NullPointerException if {@code ttl} is null
         * @throws IllegalArgumentException if {@code ttl} is zero or negative
         */
        public Builder cacheTtl(Duration ttl) {
            Objects.requireNonNull(ttl, "ttl must not be null");
            if (ttl.isZero() || ttl.isNegative()) {
                throw new IllegalArgumentException("cacheTtl must be positive");
            }
            this.cacheTtl = ttl;
            return this;
        }

        /**
         * Override the clock used for TTL checks. Primarily a test seam; production callers should
         * use the default system clock.
         *
         * @throws NullPointerException if {@code clock} is null
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
            return this;
        }

        /**
         * Override the {@link SecureRandom} used for DEK and domain-KEK generation plus GCM IVs.
         *
         * @throws NullPointerException if {@code rng} is null
         */
        public Builder rng(SecureRandom rng) {
            this.rng = Objects.requireNonNull(rng, "rng must not be null");
            return this;
        }

        /**
         * @throws IllegalStateException if {@code kmsClient}, {@code registry}, or
         *             {@code activeTenantKekRef} is unset
         */
        public EncryptionKeyHolder build() {
            if (kmsClient == null) {
                throw new IllegalStateException("kmsClient is required");
            }
            if (registry == null) {
                throw new IllegalStateException("registry is required");
            }
            if (activeTenantKekRef == null) {
                throw new IllegalStateException("activeTenantKekRef is required");
            }
            return new EncryptionKeyHolder(this);
        }
    }
}
