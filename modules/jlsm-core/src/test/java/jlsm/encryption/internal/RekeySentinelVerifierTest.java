package jlsm.encryption.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

import jlsm.encryption.DomainId;
import jlsm.encryption.EncryptionContext;
import jlsm.encryption.KekRef;
import jlsm.encryption.KmsClient;
import jlsm.encryption.KmsException;
import jlsm.encryption.KmsPermanentException;
import jlsm.encryption.RekeySentinel;
import jlsm.encryption.TenantId;
import jlsm.encryption.UnwrapResult;
import jlsm.encryption.WrapResult;
import jlsm.encryption.local.LocalKmsClient;

/**
 * Tests for {@link RekeySentinelVerifier} — dual-unwrap proof-of-control verification (R78a). The
 * verifier independently invokes {@code unwrapKek(oldSentinel, oldRef, ctx)} and
 * {@code unwrapKek(newSentinel, newRef, ctx)} and byte-compares the unwrapped nonces.
 *
 * @spec encryption.primitives-lifecycle R78a
 */
class RekeySentinelVerifierTest {

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

    @Test
    void verify_nullArgs_throwNpe(@TempDir Path tempDir) throws IOException {
        try (KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            final RekeySentinel sentinel = new RekeySentinel(ByteBuffer.wrap(new byte[]{ 1 }),
                    ByteBuffer.wrap(new byte[]{ 1 }), Instant.now());
            assertThrows(NullPointerException.class,
                    () -> RekeySentinelVerifier.verify(null, OLD_REF, NEW_REF, sentinel));
            assertThrows(NullPointerException.class,
                    () -> RekeySentinelVerifier.verify(kms, null, NEW_REF, sentinel));
            assertThrows(NullPointerException.class,
                    () -> RekeySentinelVerifier.verify(kms, OLD_REF, null, sentinel));
            assertThrows(NullPointerException.class,
                    () -> RekeySentinelVerifier.verify(kms, OLD_REF, NEW_REF, null));
        }
    }

    @Test
    void verify_matchingNonces_succeeds(@TempDir Path tempDir) throws IOException {
        // R78a happy path: same nonce wrapped under both refs (the local KMS shares its master
        // key across all KekRefs, so wrap(N, OLD_REF) and wrap(N, NEW_REF) both unwrap to N).
        try (KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            final byte[] nonce = new byte[32];
            for (int i = 0; i < 32; i++) {
                nonce[i] = (byte) (i + 100);
            }
            final byte[] oldWrapped = wrapNonce(kms, OLD_REF, nonce);
            final byte[] newWrapped = wrapNonce(kms, NEW_REF, nonce);
            final RekeySentinel sentinel = new RekeySentinel(ByteBuffer.wrap(oldWrapped),
                    ByteBuffer.wrap(newWrapped), Instant.now());
            assertDoesNotThrow(() -> RekeySentinelVerifier.verify(kms, OLD_REF, NEW_REF, sentinel));
        }
    }

    @Test
    void verify_mismatchedNonces_throwsKmsException(@TempDir Path tempDir) throws IOException {
        // R78a: when old sentinel and new sentinel decrypt to different plaintexts, verifier
        // must throw KmsException. We use two different nonces.
        try (KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            final byte[] nonce1 = new byte[32];
            final byte[] nonce2 = new byte[32];
            for (int i = 0; i < 32; i++) {
                nonce1[i] = (byte) i;
                nonce2[i] = (byte) (i + 1);
            }
            final byte[] oldWrapped = wrapNonce(kms, OLD_REF, nonce1);
            final byte[] newWrapped = wrapNonce(kms, NEW_REF, nonce2);
            final RekeySentinel sentinel = new RekeySentinel(ByteBuffer.wrap(oldWrapped),
                    ByteBuffer.wrap(newWrapped), Instant.now());
            assertThrows(KmsException.class,
                    () -> RekeySentinelVerifier.verify(kms, OLD_REF, NEW_REF, sentinel));
        }
    }

    @Test
    void verify_freshnessTooOld_throwsIae(@TempDir Path tempDir) throws IOException {
        // R78a: timestamp older than 5 minutes ago is rejected.
        try (KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            final byte[] nonce = new byte[32];
            final byte[] oldWrapped = wrapNonce(kms, OLD_REF, nonce);
            final byte[] newWrapped = wrapNonce(kms, NEW_REF, nonce);
            final Instant tooOld = Instant.now().minusSeconds(360); // 6 min ago
            final RekeySentinel sentinel = new RekeySentinel(ByteBuffer.wrap(oldWrapped),
                    ByteBuffer.wrap(newWrapped), tooOld);
            assertThrows(IllegalArgumentException.class,
                    () -> RekeySentinelVerifier.verify(kms, OLD_REF, NEW_REF, sentinel));
        }
    }

    @Test
    void verify_timestampInFutureBeyondClockSkew_throwsIae(@TempDir Path tempDir)
            throws IOException {
        // Defensive: a timestamp far in the future must also be rejected (operator clock-skew
        // attack would otherwise grant indefinite freshness). We test 6 minutes ahead.
        try (KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            final byte[] nonce = new byte[32];
            final byte[] oldWrapped = wrapNonce(kms, OLD_REF, nonce);
            final byte[] newWrapped = wrapNonce(kms, NEW_REF, nonce);
            final Instant tooFarFuture = Instant.now().plusSeconds(360);
            final RekeySentinel sentinel = new RekeySentinel(ByteBuffer.wrap(oldWrapped),
                    ByteBuffer.wrap(newWrapped), tooFarFuture);
            assertThrows(IllegalArgumentException.class,
                    () -> RekeySentinelVerifier.verify(kms, OLD_REF, NEW_REF, sentinel));
        }
    }

    @Test
    void verify_invokesBothUnwraps(@TempDir Path tempDir) throws IOException {
        // R78a: structural inspection alone is insufficient. Both unwrapKek calls must be made.
        // Use a counting wrapper around LocalKmsClient.
        try (KmsClient delegate = new LocalKmsClient(masterKeyFile(tempDir))) {
            final AtomicInteger unwrapCount = new AtomicInteger(0);
            final KmsClient counting = new CountingKmsClient(delegate, unwrapCount);
            final byte[] nonce = new byte[32];
            for (int i = 0; i < 32; i++) {
                nonce[i] = (byte) (i + 7);
            }
            final byte[] oldWrapped = wrapNonce(delegate, OLD_REF, nonce);
            final byte[] newWrapped = wrapNonce(delegate, NEW_REF, nonce);
            final RekeySentinel sentinel = new RekeySentinel(ByteBuffer.wrap(oldWrapped),
                    ByteBuffer.wrap(newWrapped), Instant.now());
            RekeySentinelVerifier.verify(counting, OLD_REF, NEW_REF, sentinel);
            assertTrue(unwrapCount.get() >= 2,
                    "verify must invoke unwrapKek at least twice, got " + unwrapCount.get());
        }
    }

    @Test
    void verify_kmsFailureOnOldUnwrap_propagatesAsKmsException(@TempDir Path tempDir)
            throws IOException {
        // If the KMS itself rejects the wrapped bytes (e.g., bad ciphertext), the verifier
        // must propagate as KmsException not eat-and-pass.
        try (KmsClient delegate = new LocalKmsClient(masterKeyFile(tempDir))) {
            // Garbage wrapped bytes for old sentinel
            final byte[] junk = new byte[40];
            for (int i = 0; i < junk.length; i++) {
                junk[i] = (byte) i;
            }
            final byte[] newWrapped = wrapNonce(delegate, NEW_REF, new byte[32]);
            final RekeySentinel sentinel = new RekeySentinel(ByteBuffer.wrap(junk),
                    ByteBuffer.wrap(newWrapped), Instant.now());
            assertThrows(KmsException.class,
                    () -> RekeySentinelVerifier.verify(delegate, OLD_REF, NEW_REF, sentinel));
        }
    }

    /** A KmsClient that counts unwrap calls, delegating to an underlying client. */
    private static final class CountingKmsClient implements KmsClient {
        private final KmsClient delegate;
        private final AtomicInteger unwrapCount;

        CountingKmsClient(KmsClient delegate, AtomicInteger unwrapCount) {
            this.delegate = delegate;
            this.unwrapCount = unwrapCount;
        }

        @Override
        public WrapResult wrapKek(MemorySegment plaintextKek, KekRef kekRef,
                EncryptionContext context) throws KmsException {
            return delegate.wrapKek(plaintextKek, kekRef, context);
        }

        @Override
        public UnwrapResult unwrapKek(ByteBuffer wrappedBytes, KekRef kekRef,
                EncryptionContext context) throws KmsException {
            unwrapCount.incrementAndGet();
            return delegate.unwrapKek(wrappedBytes, kekRef, context);
        }

        @Override
        public boolean isUsable(KekRef kekRef) throws KmsException {
            return delegate.isUsable(kekRef);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    @Test
    void verify_throwsKmsPermanentException_onMismatch(@TempDir Path tempDir) throws IOException {
        // R78a — a mismatch is a permanent failure (it represents a replay or fabrication
        // attempt). Specifically a KmsPermanentException so the caller's retry-classification
        // matches R76a.
        try (KmsClient kms = new LocalKmsClient(masterKeyFile(tempDir))) {
            final byte[] nonce1 = new byte[32];
            final byte[] nonce2 = new byte[32];
            for (int i = 0; i < 32; i++) {
                nonce1[i] = (byte) i;
                nonce2[i] = (byte) (i ^ 0xFF);
            }
            final byte[] oldWrapped = wrapNonce(kms, OLD_REF, nonce1);
            final byte[] newWrapped = wrapNonce(kms, NEW_REF, nonce2);
            final RekeySentinel sentinel = new RekeySentinel(ByteBuffer.wrap(oldWrapped),
                    ByteBuffer.wrap(newWrapped), Instant.now());
            assertThrows(KmsPermanentException.class,
                    () -> RekeySentinelVerifier.verify(kms, OLD_REF, NEW_REF, sentinel));
        }
    }
}
