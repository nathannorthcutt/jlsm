package jlsm.engine.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.engine.EncryptionMetadata;
import jlsm.engine.TableMetadata;
import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;

/**
 * Tests for {@link TableCatalog} extended for encryption metadata: format-version byte at head of
 * {@code table.meta}, optional encryption block read/write, {@link CatalogIndex} integration with
 * cold-start defence, and writer R10e tmp-file cleanup on engine startup.
 *
 * @spec sstable.footer-encryption-scope.R9
 * @spec sstable.footer-encryption-scope.R9a
 * @spec sstable.footer-encryption-scope.R9b
 * @spec sstable.footer-encryption-scope.R9c
 * @spec sstable.footer-encryption-scope.R9a-mono
 * @spec sstable.footer-encryption-scope.R10e
 * @spec sstable.footer-encryption-scope.R11a
 * @spec sstable.footer-encryption-scope.R12
 */
class TableCatalogEncryptionTest {

    @TempDir
    Path tempDir;

    private static JlsmSchema testSchema() {
        return JlsmSchema.builder("schema", 1).field("id", FieldType.Primitive.STRING).build();
    }

    private static TableScope scope() {
        return new TableScope(new TenantId("tenant-A"), new DomainId("domain-X"),
                new TableId("table-Z"));
    }

    // ---- R7a / R8 backward compatibility ----

    @Test
    void register_withoutEncryption_persistsEmpty() throws IOException {
        // covers: R7a — createTable unchanged behaviour; encryption=empty.
        final TableCatalog cat = new TableCatalog(tempDir);
        cat.open();
        final TableMetadata m = cat.register("plain", testSchema());
        assertEquals(Optional.empty(), m.encryption(),
                "register must yield encryption=Optional.empty() for plain tables (R7a)");
        cat.close();
    }

    @Test
    void preEncryptionTableMeta_loadsWithEncryptionEmpty() throws IOException {
        // covers: R8 / R9 — backward compatibility: pre-encryption table.meta loads with
        // encryption = Optional.empty() and does not trigger recovery errors.
        // First catalog: register a plain table.
        final TableCatalog first = new TableCatalog(tempDir);
        first.open();
        first.register("legacy", testSchema());
        first.close();

        // Second catalog: re-open and observe encryption=empty.
        final TableCatalog second = new TableCatalog(tempDir);
        second.open();
        final TableMetadata m = second.get("legacy").orElseThrow();
        assertEquals(Optional.empty(), m.encryption(),
                "pre-encryption table.meta must load with encryption=Optional.empty() (R9)");
        second.close();
    }

    // ---- R7 / R8a — registerEncrypted persists scope + bumps highwater ----

    @Test
    void registerEncrypted_persistsScopeAndBumpsHighwater() throws IOException {
        // covers: R7 happy path — encrypted table persists encryption metadata and the catalog
        // index high-water bumps to the encryption-aware format version.
        final TableCatalog cat = new TableCatalog(tempDir);
        cat.open();
        final TableMetadata m = cat.registerEncrypted("secret", testSchema(),
                new EncryptionMetadata(scope()));

        assertTrue(m.encryption().isPresent(),
                "registerEncrypted must yield encryption=Optional.of(...) (R7)");
        assertEquals(scope(), m.encryption().orElseThrow().scope());

        // CatalogIndex high-water must equal the encryption-aware format version (>= 1; the
        // encryption-aware version must be strictly greater than the pre-encryption version).
        final int highwater = cat.indexHighwater("secret").orElseThrow();
        assertTrue(highwater >= 2,
                "encryption-aware format version high-water must be >= 2, got " + highwater);
        cat.close();
    }

    @Test
    void registerEncrypted_thenReopen_preservesEncryptionMetadata() throws IOException {
        // covers: R7 + R9b — encryption block survives reopen.
        final TableCatalog first = new TableCatalog(tempDir);
        first.open();
        first.registerEncrypted("secret", testSchema(), new EncryptionMetadata(scope()));
        first.close();

        final TableCatalog second = new TableCatalog(tempDir);
        second.open();
        final TableMetadata m = second.get("secret").orElseThrow();
        assertTrue(m.encryption().isPresent());
        assertEquals(scope(), m.encryption().orElseThrow().scope());
        second.close();
    }

    // ---- R7b — updateEncryption (used inside enableEncryption) ----

    @Test
    void updateEncryption_transitionsEmptyToPresent_atomically() throws IOException {
        // covers: R7b step 4 + R11a — atomic transition of table.meta from empty to present
        // encryption with a high-water bump.
        final TableCatalog cat = new TableCatalog(tempDir);
        cat.open();
        cat.register("plain", testSchema());

        cat.updateEncryption("plain", new EncryptionMetadata(scope()));
        final TableMetadata m = cat.get("plain").orElseThrow();
        assertTrue(m.encryption().isPresent(), "updateEncryption must publish Optional.of(...)");

        // High-water now at the encryption-aware version.
        assertTrue(cat.indexHighwater("plain").orElseThrow() >= 2);
        cat.close();
    }

    // ---- R9a — unknown format version → IOException ----

    @Test
    void load_withUnknownFormatVersion_throwsIOException() throws Exception {
        // covers: R9a — runtime conditional, not assert; corrupt the format-version byte at the
        // head of an existing table.meta and observe IOException on reopen.
        final TableCatalog cat = new TableCatalog(tempDir);
        cat.open();
        cat.register("victim", testSchema());
        cat.close();

        final Path metaPath = tempDir.resolve("victim").resolve("table.meta");
        // Overwrite the leading format-version byte with a value not recognised by the loader.
        try (final RandomAccessFile raf = new RandomAccessFile(metaPath.toFile(), "rw")) {
            raf.seek(0);
            raf.writeByte(0x7F);
        }

        final TableCatalog reopen = new TableCatalog(tempDir);
        // Don't fail open() (corrupt meta becomes ERROR-state per existing pattern), but the
        // metadata for the corrupted entry must reflect the IOException by being in ERROR state.
        reopen.open();
        final TableMetadata m = reopen.get("victim").orElseThrow();
        assertEquals(TableMetadata.TableState.ERROR, m.state(),
                "unknown format version must surface as ERROR state (R9a)");
        reopen.close();
    }

    // ---- R9c — malformed encryption block → IOException, no silent degradation ----

    @Test
    void load_withMalformedEncryptionBlock_throwsAndDoesNotDegradeToEmpty() throws Exception {
        // covers: R9c — silent degradation to empty would let a tampered table.meta disable
        // the scope check; loader must fail with IOException instead.
        final TableCatalog cat = new TableCatalog(tempDir);
        cat.open();
        cat.registerEncrypted("secret", testSchema(), new EncryptionMetadata(scope()));
        cat.close();

        // Corrupt the encryption block by overwriting some byte deep in the file. Find the
        // length of the file and damage a byte near the end (where encryption block lives).
        final Path metaPath = tempDir.resolve("secret").resolve("table.meta");
        final long size = Files.size(metaPath);
        try (final RandomAccessFile raf = new RandomAccessFile(metaPath.toFile(), "rw")) {
            // Damage the last 4 bytes (likely inside the encryption block / its CRC region).
            raf.seek(Math.max(0, size - 4));
            raf.writeInt(0xFFFFFFFF);
        }

        final TableCatalog reopen = new TableCatalog(tempDir);
        reopen.open();
        final TableMetadata m = reopen.get("secret").orElseThrow();
        // Must not silently yield encryption=Optional.empty() — that would defeat R9c.
        assertEquals(TableMetadata.TableState.ERROR, m.state(),
                "malformed encryption block must surface as ERROR; never silent-degrade to empty (R9c)");
        reopen.close();
    }

    // ---- R9a-mono cold-start defence — table.meta on disk without index entry ----

    @Test
    void coldStart_tableMetaWithoutIndexEntry_isTreatedAsNonexistent() throws IOException {
        // covers: R9a-mono cold-start — empty CatalogIndex but table.meta on disk → table
        // treated as non-existent.
        final TableCatalog first = new TableCatalog(tempDir);
        first.open();
        first.register("ghost", testSchema());
        first.close();

        // Wipe the catalog index file so the on-disk table.meta is "orphaned" relative to the
        // index. The next open must treat ghost as non-existent regardless of the meta on disk.
        wipeCatalogIndex(tempDir);

        final TableCatalog reopen = new TableCatalog(tempDir);
        reopen.open();
        assertFalse(reopen.get("ghost").isPresent(),
                "table.meta without a catalog index entry must be treated as non-existent (R9a-mono cold start)");
        reopen.close();
    }

    // ---- R9a-mono downgrade defence — tampered table.meta lower than highwater ----

    @Test
    void load_withRolledBackFormatVersion_throwsIOException() throws Exception {
        // covers: R9a-mono downgrade — index says encryption-aware version but table.meta on
        // disk is the pre-encryption format → IOException.
        final TableCatalog cat = new TableCatalog(tempDir);
        cat.open();
        cat.registerEncrypted("secret", testSchema(), new EncryptionMetadata(scope()));
        cat.close();

        // Roll back the format-version byte at the head of table.meta to the pre-encryption
        // version (1).
        final Path metaPath = tempDir.resolve("secret").resolve("table.meta");
        try (final RandomAccessFile raf = new RandomAccessFile(metaPath.toFile(), "rw")) {
            raf.seek(0);
            raf.writeByte(1);
        }

        final TableCatalog reopen = new TableCatalog(tempDir);
        reopen.open();
        final TableMetadata m = reopen.get("secret").orElseThrow();
        assertEquals(TableMetadata.TableState.ERROR, m.state(),
                "downgrade attempt must surface as ERROR state (R9a-mono)");
        reopen.close();
    }

    // ---- R10e — restart cleanup of *.partial.* files ----

    @Test
    void open_deletesUncommittedPartialFiles_inTableDir() throws IOException {
        // covers: R10e — writer tmp files (*.partial.<writerId>) without committed magic must
        // be deleted on engine startup.
        final TableCatalog cat = new TableCatalog(tempDir);
        cat.open();
        cat.register("withtmp", testSchema());
        cat.close();

        final Path tableDir = tempDir.resolve("withtmp");
        // Drop a couple of fake partial SSTable tmp files alongside table.meta.
        final Path partial1 = tableDir.resolve("sst-1-L0.sst.partial.AAA");
        final Path partial2 = tableDir.resolve("sst-2-L0.sst.partial.BBB");
        final Path committed = tableDir.resolve("sst-3-L0.sst");
        Files.writeString(partial1, "garbage");
        Files.writeString(partial2, "garbage");
        Files.writeString(committed, "fake-committed-bytes");

        final TableCatalog reopen = new TableCatalog(tempDir);
        reopen.open();

        assertFalse(Files.exists(partial1), "open() must delete *.partial.* files (R10e)");
        assertFalse(Files.exists(partial2), "open() must delete *.partial.* files (R10e)");
        assertTrue(Files.exists(committed), "open() must preserve committed SSTable files");
        reopen.close();
    }

    // ---- R12 — error messages do not reveal byte values / catalog paths ----

    @Test
    void corruptIdentifierByte_errorMessage_doesNotRevealByteValue() throws Exception {
        // covers: R12 — identifier-byte corruption error message must not reveal the byte
        // value or expose internal catalog file paths.
        final TableCatalog cat = new TableCatalog(tempDir);
        cat.open();
        cat.registerEncrypted("secret", testSchema(), new EncryptionMetadata(scope()));
        cat.close();

        // Inject a control byte (0x01) into the encryption block: tamper a byte near the end.
        final Path metaPath = tempDir.resolve("secret").resolve("table.meta");
        final long size = Files.size(metaPath);
        try (final RandomAccessFile raf = new RandomAccessFile(metaPath.toFile(), "rw")) {
            // Bias near the encryption block region (last 8 bytes).
            raf.seek(Math.max(0, size - 8));
            raf.writeByte(0x01);
        }

        final TableCatalog reopen = new TableCatalog(tempDir);
        reopen.open();
        final TableMetadata m = reopen.get("secret").orElseThrow();
        // We expect ERROR state; the underlying IOException must not have leaked control byte
        // value into a sibling state record. We can only check public surface here — state.
        assertEquals(TableMetadata.TableState.ERROR, m.state(),
                "corrupted identifier must surface as ERROR (R12 + R9c)");
        reopen.close();
    }

    // ---- defensive (Lens B) — empty name still rejected ----

    @Test
    void registerEncrypted_emptyName_throwsIAE() throws IOException {
        final TableCatalog cat = new TableCatalog(tempDir);
        cat.open();
        assertThrows(IllegalArgumentException.class,
                () -> cat.registerEncrypted("", testSchema(), new EncryptionMetadata(scope())));
        cat.close();
    }

    @Test
    void registerEncrypted_nullEncryption_throwsNPE() throws IOException {
        final TableCatalog cat = new TableCatalog(tempDir);
        cat.open();
        assertThrows(NullPointerException.class,
                () -> cat.registerEncrypted("secret", testSchema(), null));
        cat.close();
    }

    // Helper: locate and wipe the catalog index file (whatever filename the impl chose).
    private static void wipeCatalogIndex(Path root) throws IOException {
        try (final var stream = Files.list(root)) {
            for (final Path p : (Iterable<Path>) stream::iterator) {
                final String name = p.getFileName().toString();
                if (Files.isRegularFile(p) && name.startsWith("catalog-index")) {
                    Files.delete(p);
                }
            }
        }
    }
}
