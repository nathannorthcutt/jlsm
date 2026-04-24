package jlsm.encryption.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.DomainId;
import jlsm.encryption.EncryptionContext;
import jlsm.encryption.KekRef;
import jlsm.encryption.KmsException;
import jlsm.encryption.TenantId;
import jlsm.encryption.UnwrapResult;
import jlsm.encryption.WrapResult;

/**
 * Tests for {@link LocalKmsClient} — reference KMS impl backed by a local 32-byte master key file.
 * Governed by R71, R71b.
 */
class LocalKmsClientTest {

    private static final KekRef KEK_REF = new KekRef("local-master");
    private static final TenantId TENANT = new TenantId("tenant-A");
    private static final DomainId DOMAIN = new DomainId("domain-1");

    private static Path writeMasterKey(Path dir, byte[] bytes) throws IOException {
        final Path keyFile = dir.resolve("master.key");
        Files.write(keyFile, bytes);
        if (isPosix(keyFile)) {
            Files.setPosixFilePermissions(keyFile,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        return keyFile;
    }

    private static boolean isPosix(Path p) {
        return Files.getFileAttributeView(p.getParent(),
                java.nio.file.attribute.PosixFileAttributeView.class) != null;
    }

    private static byte[] validMasterKey() {
        final byte[] b = new byte[32];
        for (int i = 0; i < 32; i++) {
            b[i] = (byte) (i + 1);
        }
        return b;
    }

    // --- construction / validation --------------------------------------

    @Test
    void ctor_nullPath_throwsNpe() {
        assertThrows(NullPointerException.class, () -> new LocalKmsClient(null));
    }

    @Test
    void ctor_missingFile_throwsIoException(@TempDir Path tempDir) {
        final Path missing = tempDir.resolve("nope.key");
        assertThrows(IOException.class, () -> new LocalKmsClient(missing));
    }

    @Test
    void ctor_wrongSize_throwsIoException(@TempDir Path tempDir) throws IOException {
        final Path badSize = tempDir.resolve("bad.key");
        Files.write(badSize, new byte[31]);
        assertThrows(IOException.class, () -> new LocalKmsClient(badSize));
    }

    @Test
    void ctor_nonOwnerOnlyPerms_throwsIoException(@TempDir Path tempDir) throws IOException {
        if (!isPosix(tempDir)) {
            // Skip on non-POSIX — permission enforcement is POSIX-only in the ref impl.
            return;
        }
        final Path keyFile = tempDir.resolve("perms.key");
        Files.write(keyFile, validMasterKey());
        final Set<PosixFilePermission> tooOpen = PosixFilePermissions.fromString("rw-r--r--");
        Files.setPosixFilePermissions(keyFile, tooOpen);
        assertThrows(IOException.class, () -> new LocalKmsClient(keyFile));
    }

    @Test
    void ctor_validFile_succeeds(@TempDir Path tempDir) throws IOException {
        final Path keyFile = writeMasterKey(tempDir, validMasterKey());
        try (LocalKmsClient client = new LocalKmsClient(keyFile)) {
            assertEquals(keyFile, client.masterKeyPath());
        }
    }

    @Test
    void staticFactory_works(@TempDir Path tempDir) throws IOException {
        final Path keyFile = writeMasterKey(tempDir, validMasterKey());
        try (LocalKmsClient client = LocalKmsClient.fromMasterKeyFile(keyFile)) {
            assertNotNull(client);
        }
    }

    // --- production readiness -------------------------------------------

    @Test
    void isProductionReady_returnsFalse(@TempDir Path tempDir) throws IOException {
        final Path keyFile = writeMasterKey(tempDir, validMasterKey());
        try (LocalKmsClient client = new LocalKmsClient(keyFile)) {
            assertFalse(client.isProductionReady(),
                    "LocalKmsClient must report not production-ready");
        }
    }

    // --- wrap / unwrap ---------------------------------------------------

    @Test
    void wrapUnwrap_roundTrip(@TempDir Path tempDir) throws IOException, KmsException {
        final Path keyFile = writeMasterKey(tempDir, validMasterKey());
        try (LocalKmsClient client = new LocalKmsClient(keyFile);
                Arena arena = Arena.ofConfined()) {
            final MemorySegment plaintext = arena.allocate(32);
            for (int i = 0; i < 32; i++) {
                plaintext.set(ValueLayout.JAVA_BYTE, i, (byte) (i + 100));
            }
            final EncryptionContext ctx = EncryptionContext.forDomainKek(TENANT, DOMAIN);
            final WrapResult wrap = client.wrapKek(plaintext, KEK_REF, ctx);
            assertNotNull(wrap);
            assertNotNull(wrap.wrappedBytes());
            assertNotNull(wrap.kekRef());

            final UnwrapResult unwrap = client.unwrapKek(wrap.wrappedBytes(), KEK_REF, ctx);
            try {
                final byte[] recovered = new byte[(int) unwrap.plaintext().byteSize()];
                MemorySegment.copy(unwrap.plaintext(), ValueLayout.JAVA_BYTE, 0, recovered, 0,
                        recovered.length);
                final byte[] expected = new byte[32];
                for (int i = 0; i < 32; i++) {
                    expected[i] = (byte) (i + 100);
                }
                assertArrayEquals(expected, recovered);
            } finally {
                unwrap.owner().close();
            }
        }
    }

    @Test
    void isUsable_returnsTrueWhenOpen(@TempDir Path tempDir) throws IOException, KmsException {
        final Path keyFile = writeMasterKey(tempDir, validMasterKey());
        try (LocalKmsClient client = new LocalKmsClient(keyFile)) {
            assertTrue(client.isUsable(KEK_REF));
        }
    }

    // --- close -----------------------------------------------------------

    @Test
    void close_idempotent(@TempDir Path tempDir) throws IOException {
        final Path keyFile = writeMasterKey(tempDir, validMasterKey());
        final LocalKmsClient client = new LocalKmsClient(keyFile);
        client.close();
        client.close(); // must not throw
    }

    @Test
    void postClose_wrap_throwsIllegalState(@TempDir Path tempDir) throws IOException {
        final Path keyFile = writeMasterKey(tempDir, validMasterKey());
        final LocalKmsClient client = new LocalKmsClient(keyFile);
        client.close();
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment pt = arena.allocate(32);
            assertThrows(IllegalStateException.class, () -> client.wrapKek(pt, KEK_REF,
                    EncryptionContext.forDomainKek(TENANT, DOMAIN)));
        }
    }

    @Test
    void postClose_unwrap_throwsIllegalState(@TempDir Path tempDir) throws IOException {
        final Path keyFile = writeMasterKey(tempDir, validMasterKey());
        final LocalKmsClient client = new LocalKmsClient(keyFile);
        client.close();
        assertThrows(IllegalStateException.class,
                () -> client.unwrapKek(ByteBuffer.wrap(new byte[40]), KEK_REF,
                        EncryptionContext.forDomainKek(TENANT, DOMAIN)));
    }

    @Test
    void postClose_isUsable_returnsFalse(@TempDir Path tempDir) throws IOException, KmsException {
        final Path keyFile = writeMasterKey(tempDir, validMasterKey());
        final LocalKmsClient client = new LocalKmsClient(keyFile);
        client.close();
        assertFalse(client.isUsable(KEK_REF));
    }

    // --- null arg validation --------------------------------------------

    @Test
    void wrap_nullArgs_throwNpe(@TempDir Path tempDir) throws IOException {
        final Path keyFile = writeMasterKey(tempDir, validMasterKey());
        try (LocalKmsClient client = new LocalKmsClient(keyFile);
                Arena arena = Arena.ofConfined()) {
            final MemorySegment pt = arena.allocate(32);
            final EncryptionContext ctx = EncryptionContext.forDomainKek(TENANT, DOMAIN);
            assertThrows(NullPointerException.class, () -> client.wrapKek(null, KEK_REF, ctx));
            assertThrows(NullPointerException.class, () -> client.wrapKek(pt, null, ctx));
            assertThrows(NullPointerException.class, () -> client.wrapKek(pt, KEK_REF, null));
        }
    }

    @Test
    void unwrap_nullArgs_throwNpe(@TempDir Path tempDir) throws IOException {
        final Path keyFile = writeMasterKey(tempDir, validMasterKey());
        try (LocalKmsClient client = new LocalKmsClient(keyFile)) {
            final EncryptionContext ctx = EncryptionContext.forDomainKek(TENANT, DOMAIN);
            final ByteBuffer buf = ByteBuffer.wrap(new byte[40]);
            assertThrows(NullPointerException.class, () -> client.unwrapKek(null, KEK_REF, ctx));
            assertThrows(NullPointerException.class, () -> client.unwrapKek(buf, null, ctx));
            assertThrows(NullPointerException.class, () -> client.unwrapKek(buf, KEK_REF, null));
        }
    }
}
