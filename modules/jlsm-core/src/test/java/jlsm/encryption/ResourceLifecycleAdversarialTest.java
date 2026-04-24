package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.internal.ShardStorage;
import jlsm.encryption.internal.TenantShardRegistry;
import jlsm.encryption.local.LocalKmsClient;

/**
 * Adversarial tests targeting resource-lifecycle violations in the encryption facade.
 *
 * <p>
 * Each test exercises a specific finding from the resource_lifecycle lens: arena ordering,
 * zero-before-close discipline (R66/R69), close-chain propagation, and similar lifecycle hygiene
 * defects.
 */
class ResourceLifecycleAdversarialTest {

    private static final TenantId TENANT = new TenantId("tenant-A");
    private static final DomainId DOMAIN = new DomainId("domain-1");
    private static final TableId TABLE = new TableId("table-1");
    private static final KekRef KEK_REF = new KekRef("local-master");

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static Path writeMasterKey(Path dir) throws IOException {
        final Path keyFile = dir.resolve("master.key");
        final byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            bytes[i] = (byte) (i + 7);
        }
        Files.write(keyFile, bytes);
        if (Files.getFileAttributeView(dir,
                java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(keyFile,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        return keyFile;
    }

    /**
     * {@link SecureRandom} subclass that delegates every {@code nextBytes} call to a real
     * {@link SecureRandom} until {@link #armed} is set, after which the next invocation throws a
     * {@link RuntimeException}. Used to exercise exception paths through
     * {@link jlsm.encryption.internal.AesGcmContextWrap#wrap} without modifying the cipher provider
     * chain.
     */
    private static final class ArmableThrowingRandom extends SecureRandom {
        private static final long serialVersionUID = 1L;
        private final SecureRandom delegate = new SecureRandom();
        private final AtomicInteger callCount = new AtomicInteger(0);
        volatile boolean armed;

        @Override
        public void nextBytes(byte[] bytes) {
            callCount.incrementAndGet();
            if (armed) {
                throw new RuntimeException(
                        "adversarial SecureRandom: forced failure to exercise wrap exception path");
            }
            delegate.nextBytes(bytes);
        }
    }

    // ── F-R1.resource_lifecycle.1.1 ──────────────────────────────────────────────

    // Finding: F-R1.resource_lifecycle.1.1
    // Bug: generateDek allocates a scratch off-heap DEK segment inside a try-with-resources
    // `scratch` arena, copies DEK plaintext into it, then calls
    // AesGcmContextWrap.wrap(...). The dekSeg.fill((byte) 0) zeroization call sits
    // AFTER the wrap invocation in the same try block, not in a finally. When wrap
    // throws (cipher error, provider failure, RNG exception on IV generation), the
    // fill is skipped and the scratch arena closes with plaintext DEK bytes still
    // present in off-heap memory — violating R69 "zero before arena close".
    // Correct behavior: the scratch DEK segment must be zeroized on every path out of the
    // try block, including the wrap-throws exception path. The fix is to move
    // dekSeg.fill((byte) 0) into a finally clause (inside or around the
    // try-with-resources), matching the discipline in deriveFieldKey (lines ~331-343).
    // Fix location: EncryptionKeyHolder.generateDek — the inner
    // try (Arena scratch = Arena.ofConfined()) { ... } block around
    // AesGcmContextWrap.wrap(domainKekSegment, dekSeg, ctx, rng).
    // Regression watch: the happy path must still zeroize scratch (unchanged); the outer
    // finally that zeros the heap dekPlaintext byte[] must remain; the exception
    // must continue to propagate to callers (no swallow); confined-arena close
    // semantics must not interact badly with the new finally (fill must run BEFORE
    // scratch.close(), not after).
    @Test
    void test_generateDek_scratchDekZeroedWhenWrapThrows_R69(@TempDir Path tempDir)
            throws IOException, KmsException {
        // ── Behavioral: force wrap to throw and confirm exception propagates ──
        // Arm a throwing SecureRandom so the wrap-internal rng.nextBytes(iv) call raises,
        // exercising the scratch-fill-skipped code path. openDomain is driven first, then
        // the rng is armed so only the generateDek wrap invocation fails.
        final Path keyFile = writeMasterKey(tempDir);
        final ArmableThrowingRandom rng = new ArmableThrowingRandom();
        try (LocalKmsClient kms = new LocalKmsClient(keyFile);
                TenantShardRegistry registry = new TenantShardRegistry(
                        new ShardStorage(tempDir.resolve("registry")));
                EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(kms)
                        .registry(registry).activeTenantKekRef(KEK_REF).rng(rng).build()) {
            // openDomain must use the rng to provision a fresh domain KEK. Keep the rng
            // unarmed so openDomain succeeds; arm it only for the generateDek call.
            holder.openDomain(TENANT, DOMAIN);
            rng.armed = true;
            final RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> holder.generateDek(TENANT, DOMAIN, TABLE),
                    "generateDek must propagate the wrap-path RuntimeException rather than "
                            + "swallow it — otherwise the test cannot prove the scratch-fill "
                            + "path is reached");
            // The injected exception must surface (possibly wrapped by wrap's internal
            // IllegalStateException translator if the throw happens inside the declared
            // GeneralSecurityException catch; here it comes from rng.nextBytes which is
            // not caught, so we get the raw RuntimeException).
            assertTrue(
                    thrown.getMessage() != null && (thrown.getMessage()
                            .contains("adversarial SecureRandom")
                            || (thrown.getCause() != null && thrown.getCause().getMessage() != null
                                    && thrown.getCause().getMessage()
                                            .contains("adversarial SecureRandom"))),
                    "the thrown exception must originate from the adversarial SecureRandom, "
                            + "confirming the wrap exception path was actually exercised; got "
                            + thrown);
        }

        // ── Source-structural: verify R69 scratch zeroization on exception path ──
        // The scratch dekSeg is a method-local MemorySegment backed by a confined arena
        // whose close is entangled with the try-with-resources. After wrap throws and the
        // arena closes, the segment is unreachable and its native region is released, so
        // residue cannot be observed behaviorally — the asserter instead verifies the
        // source-level defense (a finally clause that fills dekSeg before scratch.close).
        // This mirrors the R16c source-structural pattern established for Hkdf.expand in
        // ContractBoundariesAdversarialTest.test_Hkdf_expand_zeroesResultOnExceptionPath_R16c.
        final Path holderSource = Path.of("src/main/java/jlsm/encryption/EncryptionKeyHolder.java");
        assertTrue(Files.exists(holderSource),
                "EncryptionKeyHolder.java source must be locatable for R69 source-structural "
                        + "check; looked at " + holderSource.toAbsolutePath());
        final String source = Files.readString(holderSource);
        final int generateDekStart = source
                .indexOf("public DekHandle generateDek(TenantId tenantId");
        assertTrue(generateDekStart >= 0,
                "generateDek method must be present in EncryptionKeyHolder.java");
        final int nextMethodStart = source.indexOf("\n    /**", generateDekStart + 1);
        final String generateDekBody = nextMethodStart > 0
                ? source.substring(generateDekStart, nextMethodStart)
                : source.substring(generateDekStart);
        // The scratch try-with-resources block contains the wrap call and the fill. The R69
        // fix requires dekSeg.fill((byte) 0) to sit in a finally clause (or equivalent
        // unconditionally-executed region) tied to the scratch arena — not in the straight-
        // line try body where a wrap throw skips it.
        final int scratchTryIdx = generateDekBody
                .indexOf("try (Arena scratch = Arena.ofConfined()");
        assertTrue(scratchTryIdx >= 0,
                "generateDek body must contain the scratch try-with-resources declaration; "
                        + "the structural check relies on it to anchor the search region");
        // Find the matching closing brace of the try-with-resources by brace-counting.
        int depth = 0;
        int scratchBlockStart = -1;
        int scratchBlockEnd = -1;
        for (int i = scratchTryIdx; i < generateDekBody.length(); i++) {
            final char c = generateDekBody.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    scratchBlockStart = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    scratchBlockEnd = i;
                    break;
                }
            }
        }
        assertTrue(scratchBlockStart > 0 && scratchBlockEnd > scratchBlockStart,
                "structural check must locate the scratch try-with-resources block body "
                        + "(brace-matched region); got start=" + scratchBlockStart + " end="
                        + scratchBlockEnd);
        final String scratchBlock = generateDekBody.substring(scratchBlockStart,
                scratchBlockEnd + 1);
        // The R69 fix must place dekSeg.fill((byte) 0) in a finally region within the
        // scratch block. The regex accepts multi-line finally clauses with arbitrary
        // whitespace around the fill call.
        final boolean hasFinallyFill = scratchBlock.matches(
                "(?s).*finally\\s*\\{[^}]*dekSeg\\.fill\\s*\\(\\s*\\(byte\\)\\s*0\\s*\\).*\\}.*");
        assertTrue(hasFinallyFill,
                "generateDek must zero the scratch dekSeg inside a finally clause of the "
                        + "scratch try-with-resources (R69 zero-before-close). Current source "
                        + "places dekSeg.fill((byte) 0) in the try body where a wrap throw "
                        + "skips it, leaving DEK plaintext in off-heap memory at arena close. "
                        + "Expected pattern: try { ... wrap ... } finally { dekSeg.fill((byte) 0); } "
                        + "inside the scratch arena try-with-resources. Scratch block was:\n"
                        + scratchBlock);
    }

    // ── F-R1.resource_lifecycle.1.2 ──────────────────────────────────────────────

    // Finding: F-R1.resource_lifecycle.1.2
    // Bug: unwrapAndCacheDomainKek receives an UnwrapResult from kmsClient.unwrapKek,
    // allocates a cached copy in cacheArena, and then (in the finally block)
    // calls unwrap.owner().close() which releases the arena backing
    // unwrap.plaintext(). The Domain KEK plaintext returned by the KMS is NEVER
    // explicitly zeroed before that arena close. Panama FFM Arena.close does not
    // zero memory on all JVMs — R66/R69 require explicit fill(0) before close.
    // The provisionDomainKek path (line ~572) and deriveFieldKey (line ~278) both
    // apply this discipline; unwrapAndCacheDomainKek does not.
    // Correct behavior: zero unwrap.plaintext() with fill((byte) 0) inside the finally
    // block, BEFORE unwrap.owner().close(). Swallow any RuntimeException from the
    // fill (best-effort per R69) so owner().close() still runs.
    // Fix location: EncryptionKeyHolder.unwrapAndCacheDomainKek — the finally block
    // that closes unwrap.owner().
    // Regression watch: happy-path behavior must remain unchanged (cache still
    // published, owner still closed); the fill must tolerate the rare case where
    // unwrap.plaintext() is a read-only or otherwise unfillable segment
    // (best-effort; catch and ignore RuntimeException); the close call must still
    // be reached even if the fill throws.
    @Test
    void test_unwrapAndCacheDomainKek_zeroesPlaintextBeforeOwnerClose_R69(@TempDir Path tempDir)
            throws IOException, KmsException {
        // ── Behavioral: exercise the unwrap path on a happy-path reopen ──
        // First session: openDomain provisions a domain KEK and persists the shard.
        // Second session on the same registry path: openDomain hits the
        // loadOrProvisionDomainKek → unwrapAndCacheDomainKek branch because the shard
        // already has a WrappedDomainKek for (TENANT, DOMAIN). This confirms the bug-
        // path code is actually executed by the test harness.
        final Path keyFile = writeMasterKey(tempDir);
        final Path registryDir = tempDir.resolve("registry");
        try (LocalKmsClient kms = new LocalKmsClient(keyFile);
                TenantShardRegistry registry = new TenantShardRegistry(
                        new ShardStorage(registryDir));
                EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(kms)
                        .registry(registry).activeTenantKekRef(KEK_REF).build()) {
            holder.openDomain(TENANT, DOMAIN);
        }
        // Reopen — this session takes the unwrapAndCacheDomainKek path.
        try (LocalKmsClient kms = new LocalKmsClient(keyFile);
                TenantShardRegistry registry = new TenantShardRegistry(
                        new ShardStorage(registryDir));
                EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(kms)
                        .registry(registry).activeTenantKekRef(KEK_REF).build()) {
            holder.openDomain(TENANT, DOMAIN);
        }

        // ── Source-structural: verify R69 zero-before-owner-close on the unwrap path ──
        // The unwrap.plaintext() segment is owned by the KMS adapter's arena and is
        // released when unwrap.owner().close() runs in the finally block. After close,
        // the native region is unreachable and residue cannot be observed behaviorally
        // — this mirrors the source-structural pattern used for F-R1.resource_lifecycle.1.1
        // and R16c Hkdf.expand. The structural check asserts that the finally block
        // contains an unwrap.plaintext().fill((byte) 0) call before owner().close().
        final Path holderSource = Path.of("src/main/java/jlsm/encryption/EncryptionKeyHolder.java");
        assertTrue(Files.exists(holderSource),
                "EncryptionKeyHolder.java source must be locatable for R69 source-structural "
                        + "check; looked at " + holderSource.toAbsolutePath());
        final String source = Files.readString(holderSource);
        final int methodStart = source.indexOf("private void unwrapAndCacheDomainKek(");
        assertTrue(methodStart >= 0,
                "unwrapAndCacheDomainKek method must be present in EncryptionKeyHolder.java");
        final int nextMethodStart = source.indexOf("\n    private ", methodStart + 1);
        final String methodBody = nextMethodStart > 0
                ? source.substring(methodStart, nextMethodStart)
                : source.substring(methodStart);
        // Locate the finally block containing unwrap.owner().close().
        final int finallyIdx = methodBody.indexOf("} finally {");
        assertTrue(finallyIdx >= 0,
                "unwrapAndCacheDomainKek body must contain a finally block that closes "
                        + "unwrap.owner(); structural check relies on it to anchor the search "
                        + "region");
        final String finallyBlock = methodBody.substring(finallyIdx);
        // The R69 fix must place unwrap.plaintext().fill((byte) 0) inside the finally
        // block BEFORE unwrap.owner().close(). The regex tolerates whitespace, line
        // breaks, and an optional try/catch around the fill (best-effort swallowing
        // of RuntimeException per the card assumption).
        final int fillIdx = finallyBlock.indexOf("unwrap.plaintext().fill");
        final int closeIdx = finallyBlock.indexOf("unwrap.owner().close()");
        assertTrue(fillIdx >= 0 && closeIdx >= 0 && fillIdx < closeIdx,
                "unwrapAndCacheDomainKek must zero unwrap.plaintext() BEFORE "
                        + "unwrap.owner().close() in the finally block (R69 zero-before-close). "
                        + "Current source closes the KMS owner arena without first zeroing the "
                        + "32-byte Domain KEK plaintext — leaving it in off-heap memory at the "
                        + "instant of release. Expected pattern: finally { try { "
                        + "unwrap.plaintext().fill((byte) 0); } catch (RuntimeException ignored) {} "
                        + "unwrap.owner().close(); }. Finally block was:\n" + finallyBlock);
    }

    // ── F-R1.resource_lifecycle.1.4 ──────────────────────────────────────────────

    /**
     * {@link Clock} that throws on its first {@code instant()} call, then delegates to a real UTC
     * clock for subsequent calls. Used to inject a failure between
     * {@code cacheArena.allocate(DOMAIN_KEK_BYTES)} and {@code domainCache.put(...)} in
     * {@code provisionDomainKek}, exercising the orphan-segment window.
     */
    private static final class ArmableThrowingClock extends Clock {
        private final Clock delegate = Clock.systemUTC();
        private final AtomicInteger instantCalls = new AtomicInteger(0);
        volatile boolean armed;

        @Override
        public ZoneId getZone() {
            return delegate.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return delegate.withZone(zone);
        }

        @Override
        public Instant instant() {
            final int n = instantCalls.incrementAndGet();
            if (armed) {
                armed = false; // one-shot so subsequent close() bookkeeping does not cascade
                throw new RuntimeException("adversarial Clock: forced failure to exercise "
                        + "provisionDomainKek orphan-allocation window (call #" + n + ")");
            }
            return delegate.instant();
        }
    }

    // Finding: F-R1.resource_lifecycle.1.4
    // Bug: provisionDomainKek allocates a Domain-KEK-sized segment in cacheArena, copies the
    // 32-byte plaintext into it, then computes `clock.instant().plus(cacheTtl)` to build
    // the cache entry, then publishes via domainCache.put(...). If any exception is thrown
    // between the allocate and the put (e.g., clock.instant() failure, OOM, TTL overflow
    // in plus), the allocated `cached` segment is live in cacheArena but NOT referenced
    // by domainCache. close()'s zeroize loop walks domainCache.values(), so the orphan
    // segment is never zeroed before cacheArena.close() releases it — R69 zero-before-
    // close is violated for that segment.
    // Correct behavior: the allocate→publish window must be guarded so that any exception on
    // this path zeroes the allocated cached segment before propagating, matching the
    // card lifecycle guarantee "close zeroes each cached domain-KEK segment before
    // cacheArena.close()" for ALL allocated segments, not only those that made it into
    // the map.
    // Fix location: EncryptionKeyHolder.provisionDomainKek — the region between
    // `cacheArena.allocate(DOMAIN_KEK_BYTES)` and `domainCache.put(...)`.
    // Regression watch: happy path must remain unchanged (cache still published, plaintext
    // still zeroed in the existing outer finally); the guard must fill(cached) BEFORE
    // rethrowing; the pt.fill((byte) 0) at the end of the try block must still execute
    // on success (it is independent of the cached allocation).
    @Test
    void test_provisionDomainKek_zeroesOrphanedCachedSegmentOnPublishFailure_R69(
            @TempDir Path tempDir) throws IOException, KmsException {
        // ── Behavioral: force the publish window to throw and confirm propagation ──
        // Arm a throwing Clock so `clock.instant().plus(cacheTtl)` at the publish site raises,
        // exercising the orphan-allocation code path. openDomain drives provisionDomainKek on
        // a fresh registry (no prior WrappedDomainKek for this tenant/domain), so the branch
        // taken is loadOrProvisionDomainKek → provisionDomainKek.
        final Path keyFile = writeMasterKey(tempDir);
        final ArmableThrowingClock clock = new ArmableThrowingClock();
        clock.armed = true;
        try (LocalKmsClient kms = new LocalKmsClient(keyFile);
                TenantShardRegistry registry = new TenantShardRegistry(
                        new ShardStorage(tempDir.resolve("registry")));
                EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(kms)
                        .registry(registry).activeTenantKekRef(KEK_REF).clock(clock).build()) {
            final RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> holder.openDomain(TENANT, DOMAIN),
                    "openDomain must propagate the clock-path RuntimeException rather than "
                            + "swallow it — otherwise the test cannot prove the orphan-window "
                            + "code path was reached");
            // Confirm the injected exception (or its wrapper) propagated — proves the
            // clock.instant() call on the publish side actually executed.
            Throwable t = thrown;
            boolean found = false;
            while (t != null) {
                if (t.getMessage() != null && t.getMessage().contains("adversarial Clock")) {
                    found = true;
                    break;
                }
                t = t.getCause();
            }
            assertTrue(found,
                    "the thrown exception must originate from the adversarial Clock, "
                            + "confirming the publish-window exception path was actually "
                            + "exercised; got " + thrown);
        }

        // ── Source-structural: verify R69 orphan-zeroization on publish-failure path ──
        // The orphan `cached` segment is a MemorySegment allocated in the shared cacheArena.
        // After cacheArena.close() during holder close, its native region is released and
        // residue cannot be observed behaviorally — the asserter instead verifies the
        // source-level defense (a try/catch around the allocate→publish window that fills
        // cached before rethrowing). Mirrors F-R1.resource_lifecycle.1.1 / 1.2 pattern.
        final Path holderSource = Path.of("src/main/java/jlsm/encryption/EncryptionKeyHolder.java");
        assertTrue(Files.exists(holderSource),
                "EncryptionKeyHolder.java source must be locatable for R69 source-structural "
                        + "check; looked at " + holderSource.toAbsolutePath());
        final String source = Files.readString(holderSource);
        final int methodStart = source.indexOf("private void provisionDomainKek(");
        assertTrue(methodStart >= 0,
                "provisionDomainKek method must be present in EncryptionKeyHolder.java");
        final int nextMethodStart = source.indexOf("\n    private ", methodStart + 1);
        final String methodBody = nextMethodStart > 0
                ? source.substring(methodStart, nextMethodStart)
                : source.substring(methodStart);
        // Locate the cacheArena.allocate call — the start of the orphan window.
        final int allocIdx = methodBody.indexOf("cacheArena.allocate(DOMAIN_KEK_BYTES)");
        final int putIdx = methodBody.indexOf("domainCache.put(");
        assertTrue(allocIdx >= 0 && putIdx > allocIdx,
                "provisionDomainKek body must contain both cacheArena.allocate and "
                        + "domainCache.put calls in that order; structural check relies on "
                        + "them to anchor the orphan-window search region");
        // The R69 fix must wrap the allocate→publish region in a try/catch (or equivalent)
        // that zeroes the allocated `cached` segment on failure before rethrowing. The
        // region from allocIdx to the end of the method body should contain a catch block
        // that fills `cached` with zero bytes.
        final String orphanRegion = methodBody.substring(allocIdx);
        // Accept any catch(…) variant that fills cached with zero before rethrow; the regex
        // tolerates whitespace, line breaks, and optional try/catch wrapping of the fill
        // itself (best-effort per R69, mirroring the close() discipline at lines ~383-389).
        final boolean hasOrphanZero = orphanRegion.matches(
                "(?s).*catch\\s*\\([^)]*\\)\\s*\\{[^}]*cached\\.fill\\s*\\(\\s*\\(byte\\)\\s*0\\s*\\)[^}]*\\}.*");
        assertTrue(hasOrphanZero,
                "provisionDomainKek must zero the allocated `cached` cacheArena segment if "
                        + "publication to domainCache fails (R69 zero-before-close for "
                        + "orphaned segments). Current source allocates cached and copies the "
                        + "32-byte Domain KEK plaintext into it, then computes the expiry and "
                        + "publishes — if any step between allocate and put throws, cached is "
                        + "live in cacheArena but unreferenced, so close()'s zeroize loop "
                        + "(which walks domainCache.values) skips it and cacheArena.close() "
                        + "releases the region with plaintext still present. Expected pattern: "
                        + "try { <compute expiry>; domainCache.put(...); } catch (RuntimeException e) { "
                        + "try { cached.fill((byte) 0); } catch (RuntimeException ignored) {} "
                        + "throw e; } — around the allocate→publish window. Orphan region was:\n"
                        + orphanRegion);
    }

    // ── F-R1.resource_lifecycle.C2.1 ─────────────────────────────────────────────

    // Finding: F-R1.resource_lifecycle.C2.1
    // Bug: OffHeapKeyMaterial.of allocates a shared Arena (line 60), then calls
    // arena.allocate(keyMaterial.length) (line 61) and MemorySegment.copy
    // (line 62) before reaching Arrays.fill(keyMaterial, 0) on line 65. If
    // any step between the arena allocation and the zeroize throws
    // (OutOfMemoryError from arena.allocate under off-heap pressure, any
    // unchecked exception from MemorySegment.copy), the caller's keyMaterial
    // byte[] is never zeroed AND the shared Arena is never closed — raw key
    // bytes remain visible on-heap to any other reference-holder, and the
    // off-heap arena leaks permanently (Arena.ofShared is not GC-reclaimed).
    // Correct behavior: the allocate/copy region must be wrapped so that on any
    // exception path, Arrays.fill(keyMaterial, 0) runs AND arena.close() runs
    // before the exception propagates. Matches the discipline in
    // LocalKmsClient constructor (lines 93-100) which wraps allocate+copy in
    // try/finally for heap-byte zeroization, though for R68a the arena must
    // also be closed on the exception path.
    // Fix location: OffHeapKeyMaterial.of — the region from Arena.ofShared() on
    // line 60 through Arrays.fill on line 65.
    // Regression watch: happy path must remain unchanged (caller bytes still
    // zeroed, arena lives inside the returned holder); the fix must NOT
    // close the arena on the happy path (the holder owns it); the fill must
    // always run even if arena.close fails; the exception must continue to
    // propagate to callers (no swallow).
    @Test
    void test_OffHeapKeyMaterial_of_zeroesCallerAndClosesArenaOnAllocateOrCopyFailure_R68a()
            throws IOException {
        // ── Source-structural: verify R68a defense on the allocate/copy exception path ──
        // Behavioral exercise of OutOfMemoryError from Arena.allocate requires exhausting
        // the JVM off-heap budget, which is flaky under test harness constraints. The
        // orphaned arena and un-zeroed caller bytes cannot be observed behaviorally once
        // the JVM is stressed to OOM (heap corruption, other failures swamp the signal).
        // Instead we verify the source-level defense — an exception-safe region around
        // Arena.ofShared → allocate → copy → caller-byte fill that guarantees both
        // Arrays.fill(keyMaterial, 0) and arena.close() execute on every exit path.
        // This mirrors F-R1.resource_lifecycle.1.1/1.2/1.4 source-structural pattern.
        final Path holderSource = Path
                .of("src/main/java/jlsm/encryption/internal/OffHeapKeyMaterial.java");
        assertTrue(Files.exists(holderSource),
                "OffHeapKeyMaterial.java source must be locatable for R68a source-structural "
                        + "check; looked at " + holderSource.toAbsolutePath());
        final String source = Files.readString(holderSource);
        final int methodStart = source
                .indexOf("public static OffHeapKeyMaterial of(byte[] keyMaterial)");
        assertTrue(methodStart >= 0,
                "OffHeapKeyMaterial.of(byte[]) factory must be present in OffHeapKeyMaterial.java");
        // Find the method body by brace-matching from methodStart onward.
        final int bodyOpen = source.indexOf('{', methodStart);
        assertTrue(bodyOpen > methodStart,
                "of() method body must have an opening brace after the signature");
        int depth = 0;
        int bodyClose = -1;
        for (int i = bodyOpen; i < source.length(); i++) {
            final char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    bodyClose = i;
                    break;
                }
            }
        }
        assertTrue(bodyClose > bodyOpen, "of() method body must have a matching closing brace");
        final String methodBody = source.substring(bodyOpen, bodyClose + 1);
        // Locate the three key sites: Arena.ofShared(), arena.allocate(...), and the
        // caller-byte fill. The exception-safe region must enclose the allocate/copy
        // between ofShared and the caller-byte fill.
        final int ofSharedIdx = methodBody.indexOf("Arena.ofShared()");
        final int allocateIdx = methodBody.indexOf("arena.allocate(");
        final int copyIdx = methodBody.indexOf("MemorySegment.copy(");
        final int callerFillIdx = methodBody.indexOf("Arrays.fill(keyMaterial");
        assertTrue(
                ofSharedIdx >= 0 && allocateIdx > ofSharedIdx && copyIdx > allocateIdx
                        && callerFillIdx > copyIdx,
                "of() body must contain Arena.ofShared(), arena.allocate(...), "
                        + "MemorySegment.copy(...), and Arrays.fill(keyMaterial, ...) in that "
                        + "order; structural check relies on them to anchor the exception-safe "
                        + "region");
        // The R68a fix must wrap the allocate/copy region (between ofShared and caller-
        // byte fill) in a try-catch that (1) zeroes the caller's keyMaterial before
        // rethrowing and (2) closes the orphaned arena before rethrowing. Scan from
        // ofSharedIdx to the end of the method body so that a catch block whose body
        // contains another Arrays.fill(keyMaterial) statement (the exception-path
        // zeroization) is not truncated out of the search region.
        final String exceptionRegion = methodBody.substring(ofSharedIdx);
        // Accept any catch(…) variant that both fills keyMaterial with zero AND closes
        // the arena before rethrowing. The regex tolerates whitespace, line breaks, and
        // best-effort try/catch around each defense.
        final boolean hasCallerFillInCatch = exceptionRegion.matches(
                "(?s).*catch\\s*\\([^)]*\\)\\s*\\{[^}]*Arrays\\.fill\\s*\\(\\s*keyMaterial[^}]*\\}.*");
        final boolean hasArenaCloseInCatch = exceptionRegion.matches(
                "(?s).*catch\\s*\\([^)]*\\)\\s*\\{[^}]*arena\\.close\\s*\\(\\s*\\)[^}]*\\}.*");
        assertTrue(hasCallerFillInCatch && hasArenaCloseInCatch,
                "OffHeapKeyMaterial.of must, on any exception between Arena.ofShared() and "
                        + "the caller-byte zeroization, (1) zero the caller's keyMaterial byte[] "
                        + "AND (2) close the orphaned shared Arena before rethrowing "
                        + "(R68a/R69 caller-zeroization + arena close on exception). Current "
                        + "source leaves both defenses out of the allocate/copy region — "
                        + "OOM/copy exceptions would leave raw key bytes on-heap and leak the "
                        + "shared arena off-heap. Expected pattern: Arena arena = Arena.ofShared(); "
                        + "try { segment = arena.allocate(len); MemorySegment.copy(...); } "
                        + "catch (RuntimeException | Error e) { Arrays.fill(keyMaterial, (byte) 0); "
                        + "arena.close(); throw e; } — around the allocate/copy window. "
                        + "hasCallerFillInCatch=" + hasCallerFillInCatch + " hasArenaCloseInCatch="
                        + hasArenaCloseInCatch + "\nRegion was:\n" + exceptionRegion);
    }

    // ── F-R1.resource_lifecycle.C2.3 ─────────────────────────────────────────────

    // Finding: F-R1.resource_lifecycle.C2.3
    // Bug: LocalKmsClient constructor calls `this.masterArena = Arena.ofShared()` (line 94),
    // then `this.masterKeySegment = masterArena.allocate(MASTER_KEY_SIZE)` (line 96),
    // then `MemorySegment.copy(bytes, 0, masterKeySegment, ...)` (lines 97-98). The
    // surrounding `try { ... } finally { Arrays.fill(bytes, 0); }` only zeroes the heap
    // byte[] — if `allocate` or `copy` throws (OutOfMemoryError from off-heap pressure,
    // any RuntimeException from MemorySegment.copy), the shared Arena allocated on
    // line 94 is NEVER closed. The constructor throws, `this` never escapes, so no
    // caller can ever invoke close() to release the arena. Because Arena.ofShared is
    // NOT GC-reclaimed, the off-heap region leaks permanently.
    // Correct behavior: the allocate/copy region must close `masterArena` on any exception
    // before rethrowing, matching the discipline already present in OffHeapKeyMaterial.of
    // (post-C2.1 fix) and the cleanup pattern in LocalKmsClient.unwrapKek (ok-flag
    // around callerArena, lines 148-162). Heap-byte zeroization must still run on all
    // paths (the existing finally already guarantees that).
    // Fix location: LocalKmsClient constructor — the region from Arena.ofShared() on line 94
    // through MemorySegment.copy on line 98.
    // Regression watch: happy path must remain unchanged (arena lives on in the constructed
    // LocalKmsClient, close() releases it as today); heap-byte zeroization must still
    // run on all paths (existing outer finally); the exception must continue to
    // propagate to callers (no swallow); the masterArena field is final, so the fix
    // must preserve final-field semantics (single definite assignment).
    @Test
    void test_LocalKmsClient_ctor_closesMasterArenaOnAllocateOrCopyFailure_R69()
            throws IOException {
        // ── Source-structural: verify R69 defense on the allocate/copy exception path ──
        // Behavioral exercise of OutOfMemoryError from arena.allocate requires exhausting the
        // JVM off-heap budget, which is flaky under test harness constraints. The orphaned
        // arena cannot be observed behaviorally once the JVM is stressed to OOM (heap
        // corruption, other failures swamp the signal). Instead we verify the source-level
        // defense — an exception-safe region around masterArena.allocate → MemorySegment.copy
        // that guarantees masterArena.close() executes on every exit path where the arena
        // was allocated but the constructor did not complete. This mirrors
        // F-R1.resource_lifecycle.1.1/1.2/1.4/C2.1 source-structural pattern.
        final Path kmsSource = Path.of("src/main/java/jlsm/encryption/local/LocalKmsClient.java");
        assertTrue(Files.exists(kmsSource),
                "LocalKmsClient.java source must be locatable for R69 source-structural "
                        + "check; looked at " + kmsSource.toAbsolutePath());
        final String source = Files.readString(kmsSource);
        final int ctorStart = source.indexOf("public LocalKmsClient(Path masterKeyPath)");
        assertTrue(ctorStart >= 0,
                "LocalKmsClient(Path) constructor must be present in LocalKmsClient.java");
        // Find the constructor body by brace-matching from ctorStart onward.
        final int bodyOpen = source.indexOf('{', ctorStart);
        assertTrue(bodyOpen > ctorStart,
                "LocalKmsClient constructor body must have an opening brace after the signature");
        int depth = 0;
        int bodyClose = -1;
        for (int i = bodyOpen; i < source.length(); i++) {
            final char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    bodyClose = i;
                    break;
                }
            }
        }
        assertTrue(bodyClose > bodyOpen,
                "LocalKmsClient constructor body must have a matching closing brace");
        final String ctorBody = source.substring(bodyOpen, bodyClose + 1);
        // Locate the three key sites: Arena.ofShared(), masterArena.allocate(...), and the
        // MemorySegment.copy call. The exception-safe region must enclose the allocate/copy
        // between ofShared and the end of the copy invocation.
        final int ofSharedIdx = ctorBody.indexOf("Arena.ofShared()");
        final int allocateIdx = ctorBody.indexOf("masterArena.allocate(");
        final int copyIdx = ctorBody.indexOf("MemorySegment.copy(");
        assertTrue(ofSharedIdx >= 0 && allocateIdx > ofSharedIdx && copyIdx > allocateIdx,
                "LocalKmsClient constructor body must contain Arena.ofShared(), "
                        + "masterArena.allocate(...), and MemorySegment.copy(...) in that order; "
                        + "structural check relies on them to anchor the exception-safe region");
        // The R69 fix must arrange for masterArena.close() to run on any exception between
        // Arena.ofShared() and the successful completion of MemorySegment.copy. Scan from
        // ofSharedIdx to the end of the constructor body so a catch block whose body closes
        // the arena is not truncated out of the search region.
        final String exceptionRegion = ctorBody.substring(ofSharedIdx);
        // Accept any catch(…) variant that closes masterArena before rethrowing. The regex
        // tolerates whitespace, line breaks, and best-effort try/catch around the close
        // itself.
        final boolean hasArenaCloseInCatch = exceptionRegion.matches(
                "(?s).*catch\\s*\\([^)]*\\)\\s*\\{[^}]*masterArena\\.close\\s*\\(\\s*\\)[^}]*\\}.*");
        assertTrue(hasArenaCloseInCatch,
                "LocalKmsClient constructor must, on any exception between Arena.ofShared() "
                        + "and the successful MemorySegment.copy (i.e., allocate failure OR copy "
                        + "failure), close the orphaned masterArena before rethrowing (R69 "
                        + "arena close on exception). Current source wraps the allocate/copy in "
                        + "a try/finally that only zeros the heap byte[] — if allocate or copy "
                        + "throws, `this` never escapes to a caller, so the shared Arena is "
                        + "permanently leaked (Arena.ofShared is not GC-reclaimed). Expected "
                        + "pattern: this.masterArena = Arena.ofShared(); MemorySegment seg = null; "
                        + "try { seg = masterArena.allocate(MASTER_KEY_SIZE); "
                        + "MemorySegment.copy(...); } catch (RuntimeException | Error e) { "
                        + "masterArena.close(); throw e; } finally { Arrays.fill(bytes, (byte) 0); } "
                        + "— around the allocate/copy window. hasArenaCloseInCatch="
                        + hasArenaCloseInCatch + "\nRegion was:\n" + exceptionRegion);
    }

    // ── F-R1.resource_lifecycle.C2.5 ─────────────────────────────────────────────

    // Finding: F-R1.resource_lifecycle.C2.5
    // Bug: ShardStorage.writeShard performs the atomic Files.move (line ~211) that
    // publishes the new shard at shardPath, then calls
    // Files.setPosixFilePermissions(shardPath, OWNER_ONLY) (line ~216), and only
    // THEN sets `committed = true` (line ~220). If the post-rename chmod throws
    // IOException (transient FS error, SELinux label conflict, unusual FS without
    // chmod re-assert), the `committed = true` statement is never reached. The
    // outer finally (line ~221-228) observes committed==false and attempts
    // Files.deleteIfExists(tempPath) — a no-op since the temp was renamed away.
    // The exception propagates to the caller, who reasonably assumes the write did
    // NOT commit. But the atomic move already completed; the shard is live at
    // shardPath with temp-file perms (OWNER_ONLY from line 175 or best-effort ACL
    // on non-POSIX). Semantic inconsistency: caller sees failure, FS reflects
    // successful commit. Violates R20 — "caller's view of success must match FS
    // state" — for the post-rename chmod-failure path.
    // Correct behavior: once Files.move completes, the commit is observable on disk;
    // the `committed` marker must be set immediately after the atomic move and
    // before any statement that can throw. Post-rename chmod must either run
    // after `committed = true` (so its failure does not trigger the cleanup
    // branch) or be wrapped so a chmod failure does not misrepresent the commit
    // state. The temp file already carries OWNER_ONLY perms (line 175), so the
    // post-rename chmod is a defense-in-depth re-assert — its failure is
    // recoverable, but the caller must see a commit that matches FS state.
    // Fix location: ShardStorage.writeShard — the region between Files.move
    // (line ~211) and the post-rename Files.setPosixFilePermissions(shardPath,
    // OWNER_ONLY) / narrowAclBestEffort(shardPath) call and the existing
    // `committed = true` statement.
    // Regression watch: happy path must remain unchanged (shard still committed with
    // 0600 perms on POSIX); the temp-file cleanup branch must still run on
    // PRE-rename failures (payload write, fsync, pre-rename chmod); post-rename
    // chmod should still run (best-effort re-assert) but its failure must not
    // cause the caller to observe a misleading "failed" state when FS shows the
    // shard in place; rename failure itself must still leave committed=false so
    // the temp cleanup runs.
    @Test
    void test_ShardStorage_writeShard_commitMarkerMatchesFsStateOnPostRenameFailure_R20()
            throws IOException {
        // ── Source-structural: verify R20 caller-view-matches-FS-state on the
        // post-rename chmod failure path ──
        // Behavioral exercise of a chmod IOException on a file we just renamed is not
        // reliably reproducible in a test harness — the OS call succeeds on every
        // normal ext4/tmpfs path used by @TempDir. The semantic corruption (caller
        // sees failure, FS shows commit) cannot be observed in the common case because
        // the failure mode requires an adversarial filesystem. Instead we verify the
        // source-level defense — that the `committed = true` marker is set
        // immediately after Files.move (the moment FS state becomes observable as
        // committed) and before any post-rename statement that can throw, OR that the
        // post-rename chmod is wrapped so its failure cannot propagate as "commit
        // failed" when the rename already succeeded. This mirrors the source-structural
        // pattern established for F-R1.resource_lifecycle.1.1 / 1.2 / 1.4 / C2.1 / C2.3.
        final Path shardSource = Path
                .of("src/main/java/jlsm/encryption/internal/ShardStorage.java");
        assertTrue(Files.exists(shardSource),
                "ShardStorage.java source must be locatable for R20 source-structural "
                        + "check; looked at " + shardSource.toAbsolutePath());
        final String source = Files.readString(shardSource);
        final int methodStart = source.indexOf("public void writeShard(TenantId tenantId");
        assertTrue(methodStart >= 0, "writeShard method must be present in ShardStorage.java");
        // Find the method body by brace-matching from the method signature onward.
        final int bodyOpen = source.indexOf('{', methodStart);
        assertTrue(bodyOpen > methodStart,
                "writeShard body must have an opening brace after the signature");
        int depth = 0;
        int bodyClose = -1;
        for (int i = bodyOpen; i < source.length(); i++) {
            final char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    bodyClose = i;
                    break;
                }
            }
        }
        assertTrue(bodyClose > bodyOpen, "writeShard body must have a matching closing brace");
        final String methodBody = source.substring(bodyOpen, bodyClose + 1);
        // Locate the three key sites: Files.move (atomic rename), the post-rename
        // setPosixFilePermissions(shardPath, ...) call, and `committed = true`.
        final int moveIdx = methodBody.indexOf("Files.move(tempPath, shardPath");
        final int postRenameChmodIdx = methodBody
                .indexOf("Files.setPosixFilePermissions(shardPath");
        final int committedTrueIdx = methodBody.indexOf("committed = true");
        assertTrue(moveIdx >= 0 && postRenameChmodIdx > moveIdx && committedTrueIdx > moveIdx,
                "writeShard body must contain Files.move(tempPath, shardPath, ...), "
                        + "Files.setPosixFilePermissions(shardPath, ...) (the post-rename "
                        + "chmod re-assert), and `committed = true` in a region after the "
                        + "atomic rename; structural check relies on them to anchor the "
                        + "post-rename window. Got moveIdx=" + moveIdx + " postRenameChmodIdx="
                        + postRenameChmodIdx + " committedTrueIdx=" + committedTrueIdx);
        // Two valid defenses:
        // (A) `committed = true` appears BEFORE the post-rename chmod, so a chmod
        // failure does NOT trigger the temp-cleanup branch (and correctly
        // represents the already-observable FS state of a successful commit).
        // (B) the post-rename chmod is wrapped in try/catch that swallows the
        // failure (best-effort re-assert), keeping the committed flag and
        // caller-visible success aligned with FS state.
        final boolean committedBeforePostRenameChmod = committedTrueIdx < postRenameChmodIdx;
        // For (B), search the region from postRenameChmodIdx forward for a try/catch
        // whose body contains the setPosixFilePermissions(shardPath, ...) call.
        // Conservatively, match a try { ... setPosixFilePermissions(shardPath ... } catch
        // pattern in the post-rename region.
        final String postRenameRegion = methodBody.substring(moveIdx);
        final boolean postRenameChmodWrapped = postRenameRegion.matches(
                "(?s).*try\\s*\\{[^}]*Files\\.setPosixFilePermissions\\s*\\(\\s*shardPath[^}]*\\}\\s*catch\\s*\\([^)]*\\)\\s*\\{[^}]*\\}.*");
        assertTrue(committedBeforePostRenameChmod || postRenameChmodWrapped,
                "writeShard must align the `committed` marker with the observable FS "
                        + "state after Files.move. Current source sets `committed = true` "
                        + "AFTER the post-rename Files.setPosixFilePermissions(shardPath, "
                        + "OWNER_ONLY) call — if that chmod throws, the committed flag stays "
                        + "false, the outer finally attempts a no-op deleteIfExists on the "
                        + "(already-renamed) temp, and the caller receives IOException while "
                        + "the shard is live on disk. Expected either: (A) set `committed = "
                        + "true` immediately after Files.move and before the post-rename "
                        + "chmod, or (B) wrap the post-rename setPosixFilePermissions(shardPath, "
                        + "...) in a try/catch that swallows the failure (best-effort "
                        + "re-assert, since the temp already has OWNER_ONLY perms). "
                        + "committedBeforePostRenameChmod=" + committedBeforePostRenameChmod
                        + " postRenameChmodWrapped=" + postRenameChmodWrapped
                        + "\nPost-rename region was:\n" + postRenameRegion);
    }
}
