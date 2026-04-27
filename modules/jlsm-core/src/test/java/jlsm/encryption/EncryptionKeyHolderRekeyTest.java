package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.ContinuationToken.ContinuationKind;
import jlsm.encryption.internal.ShardStorage;
import jlsm.encryption.internal.TenantShardRegistry;
import jlsm.encryption.local.LocalKmsClient;

/**
 * Contract tests for
 * {@link EncryptionKeyHolder#rekey(TenantId, KekRef, KekRef, RekeySentinel, ContinuationToken)} —
 * the tenant-driven rekey API. Verifies R78a (dual-unwrap proof), R78b (paginated streaming), R78d
 * (active-ref update on completion), R78f (rekey-complete marker), and R78g-2 (start vs resume call
 * shapes).
 *
 * @spec encryption.primitives-lifecycle R78
 * @spec encryption.primitives-lifecycle R78a
 * @spec encryption.primitives-lifecycle R78b
 * @spec encryption.primitives-lifecycle R78f
 * @spec encryption.primitives-lifecycle R78g-2
 */
class EncryptionKeyHolderRekeyTest {

    private static final TenantId TENANT = new TenantId("tenant-A");
    private static final DomainId DOMAIN = new DomainId("domain-1");
    private static final KekRef OLD_REF = new KekRef("kek/v1");
    private static final KekRef NEW_REF = new KekRef("kek/v2");

    private static Path masterKeyFile(Path tempDir) throws IOException {
        final Path keyFile = tempDir.resolve("master.key");
        final byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        Files.write(keyFile, key);
        if (Files.getFileAttributeView(keyFile, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(keyFile,
                    EnumSet.copyOf(PosixFilePermissions.fromString("rw-------")));
        }
        return keyFile;
    }

    private static byte[] wrapNonce(KmsClient kms, KekRef ref, byte[] nonce) throws KmsException {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment seg = arena.allocate(nonce.length);
            MemorySegment.copy(nonce, 0, seg, ValueLayout.JAVA_BYTE, 0, nonce.length);
            final WrapResult wr = kms.wrapKek(seg, ref,
                    EncryptionContext.forRekeySentinel(TENANT, DOMAIN));
            final ByteBuffer bb = wr.wrappedBytes();
            final byte[] out = new byte[bb.remaining()];
            bb.get(out);
            return out;
        }
    }

    private static RekeySentinel freshSentinel(KmsClient kms) throws KmsException {
        final byte[] nonce = new byte[32];
        for (int i = 0; i < 32; i++) {
            nonce[i] = (byte) (i + 200);
        }
        final byte[] oldWrap = wrapNonce(kms, OLD_REF, nonce);
        final byte[] newWrap = wrapNonce(kms, NEW_REF, nonce);
        return new RekeySentinel(ByteBuffer.wrap(oldWrap), ByteBuffer.wrap(newWrap), Instant.now());
    }

    private static EncryptionKeyHolder buildHolder(Path tempDir, KmsClient kms) throws IOException {
        final TenantShardRegistry registry = new TenantShardRegistry(
                new ShardStorage(tempDir.resolve("registry")));
        return EncryptionKeyHolder.builder().kmsClient(kms).registry(registry)
                .activeTenantKekRef(OLD_REF).build();
    }

    // ── argument validation ────────────────────────────────────────────

    @Test
    void rekey_nullArgs_throwNpe(@TempDir Path tempDir) throws Exception {
        try (KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir));
                EncryptionKeyHolder holder = buildHolder(tempDir, kms)) {
            holder.openDomain(TENANT, DOMAIN);
            final RekeySentinel proof = freshSentinel(kms);
            assertThrows(NullPointerException.class,
                    () -> holder.rekey(null, OLD_REF, NEW_REF, proof, null));
            assertThrows(NullPointerException.class,
                    () -> holder.rekey(TENANT, null, NEW_REF, proof, null));
            assertThrows(NullPointerException.class,
                    () -> holder.rekey(TENANT, OLD_REF, null, proof, null));
            assertThrows(NullPointerException.class,
                    () -> holder.rekey(TENANT, OLD_REF, NEW_REF, null, null));
        }
    }

    // ── R78a — sentinel verification gate ─────────────────────────────

    @Test
    void rekey_invalidSentinel_isRejected(@TempDir Path tempDir) throws Exception {
        // R78a — a sentinel whose dual-unwrap nonces don't match must reject the rekey BEFORE
        // any shard mutation occurs. A bad sentinel is a permanent failure (KmsException).
        try (KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir));
                EncryptionKeyHolder holder = buildHolder(tempDir, kms)) {
            holder.openDomain(TENANT, DOMAIN);
            final byte[] nonce1 = new byte[32];
            final byte[] nonce2 = new byte[32];
            for (int i = 0; i < 32; i++) {
                nonce1[i] = (byte) i;
                nonce2[i] = (byte) (i + 1);
            }
            final RekeySentinel bad = new RekeySentinel(
                    ByteBuffer.wrap(wrapNonce(kms, OLD_REF, nonce1)),
                    ByteBuffer.wrap(wrapNonce(kms, NEW_REF, nonce2)), Instant.now());
            assertThrows(KmsException.class,
                    () -> holder.rekey(TENANT, OLD_REF, NEW_REF, bad, null));
        }
    }

    // ── R78b — initial call returns continuation token ─────────────────

    @Test
    void rekey_firstCall_terminalWhenNoOnDiskArtifacts(@TempDir Path tempDir) throws Exception {
        // R78b + R78e — when there are no on-disk artifacts dependent on oldRef, the witness
        // is satisfied immediately and the rekey terminates on the first call (null token).
        try (KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir));
                EncryptionKeyHolder holder = buildHolder(tempDir, kms)) {
            holder.openDomain(TENANT, DOMAIN);
            final RekeySentinel proof = freshSentinel(kms);
            final ContinuationToken token = holder.rekey(TENANT, OLD_REF, NEW_REF, proof, null);
            // Witness is at zero -> rekey completes -> null token.
            org.junit.jupiter.api.Assertions.assertNull(token,
                    "rekey must return null when no on-disk artifacts exist (R78e)");
        }
    }

    // ── R78b — drive to completion ─────────────────────────────────────

    @Test
    void rekey_drivenToCompletion_updatesActiveTenantKekRef(@TempDir Path tempDir)
            throws Exception {
        // Iterate until the API returns null (terminal). After completion, the registry shard
        // must record NEW_REF as active.
        try (KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir));
                EncryptionKeyHolder holder = buildHolder(tempDir, kms)) {
            holder.openDomain(TENANT, DOMAIN);
            final RekeySentinel proof1 = freshSentinel(kms);
            ContinuationToken token = holder.rekey(TENANT, OLD_REF, NEW_REF, proof1, null);
            int loops = 0;
            while (token != null) {
                if (token.kind() == ContinuationKind.AWAITING_LIVENESS_WITNESS) {
                    // No on-disk artifacts created in this test — the witness is at zero.
                    // Issue one more rekey call to advance past the awaiting state.
                }
                final RekeySentinel proof = freshSentinel(kms);
                token = holder.rekey(TENANT, OLD_REF, NEW_REF, proof, token);
                loops++;
                if (loops > 100) {
                    org.junit.jupiter.api.Assertions.fail("rekey did not terminate");
                }
            }
        }
        // Re-open registry to verify durable state
        final TenantShardRegistry reg = new TenantShardRegistry(
                new ShardStorage(tempDir.resolve("registry")));
        try (reg) {
            assertEquals(NEW_REF, reg.readSnapshot(TENANT).activeTenantKekRef(),
                    "rekey completion must update activeTenantKekRef (R78d)");
        }
    }

    // ── R78f — rekey-complete marker is written on terminal completion ─

    @Test
    void rekey_terminalCompletion_writesRekeyCompleteMarker(@TempDir Path tempDir)
            throws Exception {
        try (KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir));
                EncryptionKeyHolder holder = buildHolder(tempDir, kms)) {
            holder.openDomain(TENANT, DOMAIN);
            ContinuationToken token = holder.rekey(TENANT, OLD_REF, NEW_REF, freshSentinel(kms),
                    null);
            int loops = 0;
            while (token != null) {
                token = holder.rekey(TENANT, OLD_REF, NEW_REF, freshSentinel(kms), token);
                if (++loops > 100) {
                    org.junit.jupiter.api.Assertions.fail("rekey did not terminate");
                }
            }
        }
        final TenantShardRegistry reg = new TenantShardRegistry(
                new ShardStorage(tempDir.resolve("registry")));
        try (reg) {
            assertTrue(reg.readSnapshot(TENANT).rekeyCompleteMarker().isPresent(),
                    "rekey-complete marker must be present after completion (R78f)");
            assertEquals(NEW_REF,
                    reg.readSnapshot(TENANT).rekeyCompleteMarker().get().completedKekRef());
        }
    }

    // ── R78g — observer events ─────────────────────────────────────────

    @Test
    void rekey_emitsObserverEvents(@TempDir Path tempDir) throws Exception {
        // R78g — a mandatory KmsObserver receives rekeyStarted on the first call. We cannot
        // fully verify all event types without a complex infrastructure stub; this test
        // asserts the observer plumbing fires at least once over a complete rekey.
        final AtomicInteger rekeyEvents = new AtomicInteger(0);
        try (KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir));
                EncryptionKeyHolder holder = EncryptionKeyHolder.builder().kmsClient(kms)
                        .registry(new TenantShardRegistry(
                                new ShardStorage(tempDir.resolve("registry"))))
                        .activeTenantKekRef(OLD_REF).observer(new KmsObserver() {
                            @Override
                            public void onRekeyEvent(KmsObserver.RekeyEvent event) {
                                rekeyEvents.incrementAndGet();
                            }
                        }).build()) {
            holder.openDomain(TENANT, DOMAIN);
            ContinuationToken token = holder.rekey(TENANT, OLD_REF, NEW_REF, freshSentinel(kms),
                    null);
            int loops = 0;
            while (token != null) {
                token = holder.rekey(TENANT, OLD_REF, NEW_REF, freshSentinel(kms), token);
                if (++loops > 100) {
                    org.junit.jupiter.api.Assertions.fail("rekey did not terminate");
                }
            }
        }
        assertTrue(rekeyEvents.get() >= 1,
                "rekey must emit at least one observer event (R78g), got " + rekeyEvents.get());
    }

    // ── R78g-2 — start vs resume distinguishing ────────────────────────

    @Test
    void rekey_resumeWithMismatchedTokenRefs_throwsIae(@TempDir Path tempDir) throws Exception {
        // R78g-2 — resume must reject a token whose (oldRef, newRef) pair differs from the
        // call site's (oldRef, newRef) pair. Construct a synthetic "in-flight" continuation
        // token that disagrees with the call's refs and assert the holder rejects it.
        try (KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir));
                EncryptionKeyHolder holder = buildHolder(tempDir, kms)) {
            holder.openDomain(TENANT, DOMAIN);
            final KekRef wrongRef = new KekRef("kek/v3");
            // Token claims (OLD_REF -> wrongRef); the call asks for (OLD_REF -> NEW_REF).
            final ContinuationToken bogusToken = new ContinuationToken(TENANT, OLD_REF, wrongRef, 0,
                    1L, ContinuationKind.SHARD_BATCH);
            assertThrows(IllegalArgumentException.class,
                    () -> holder.rekey(TENANT, OLD_REF, NEW_REF, freshSentinel(kms), bogusToken));
        }
    }
}
