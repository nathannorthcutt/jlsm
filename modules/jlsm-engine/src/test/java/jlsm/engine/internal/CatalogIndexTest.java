package jlsm.engine.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link CatalogIndex} — persistent (tableName -> meta_format_version_highwater) index
 * used to defend against R9a-mono format-version downgrade across cold starts. The index is the
 * authoritative answer to "does this table exist": a missing entry means the table does not exist
 * regardless of any {@code table.meta} on disk.
 *
 * @spec sstable.footer-encryption-scope.R9a-mono
 */
class CatalogIndexTest {

    @TempDir
    Path tempDir;

    // ---- happy-path: cold start with empty catalog ----

    @Test
    void coldStart_emptyDirectory_yieldsEmptyHighwaterForEveryName() throws IOException {
        // covers: R9a-mono — cold start with no index file means "no tables exist"; never
        // "all tables exist".
        final CatalogIndex index = new CatalogIndex(tempDir);

        final Optional<Integer> highwater = index.highwater("does-not-exist");

        assertFalse(highwater.isPresent(),
                "missing catalog index entry must mean 'table does not exist' (R9a-mono)");
    }

    // ---- setHighwater + highwater roundtrip ----

    @Test
    void setHighwater_thenHighwater_returnsSetValue() throws IOException {
        // covers: R9a-mono — setHighwater + highwater roundtrip
        final CatalogIndex index = new CatalogIndex(tempDir);

        index.setHighwater("users", 1);
        final Optional<Integer> hw = index.highwater("users");

        assertTrue(hw.isPresent(), "highwater must be present after setHighwater");
        assertEquals(1, hw.get(), "highwater must equal value passed to setHighwater");
    }

    // ---- monotonicity defence ----

    @Test
    void setHighwater_strictlyLowerVersion_isRejectedWithIOException() throws IOException {
        // covers: R9a-mono — strict monotonicity; a downgrade write must be rejected with
        // IOException so a tampered index cannot silently lower the high-water.
        final CatalogIndex index = new CatalogIndex(tempDir);
        index.setHighwater("users", 2);

        assertThrows(IOException.class, () -> index.setHighwater("users", 1),
                "strictly lower version must be rejected (R9a-mono)");
    }

    @Test
    void setHighwater_sameVersion_isPermitted() throws IOException {
        // covers: R9a-mono — same version is not a downgrade; idempotent re-write is allowed.
        final CatalogIndex index = new CatalogIndex(tempDir);
        index.setHighwater("users", 2);
        index.setHighwater("users", 2);

        assertEquals(2, index.highwater("users").orElseThrow());
    }

    @Test
    void setHighwater_higherVersion_replacesPriorValue() throws IOException {
        // covers: R9a-mono — strictly higher version is a normal high-water bump.
        final CatalogIndex index = new CatalogIndex(tempDir);
        index.setHighwater("users", 1);
        index.setHighwater("users", 2);

        assertEquals(2, index.highwater("users").orElseThrow());
    }

    // ---- atomic write-temp + fsync + rename persistence ----

    @Test
    void setHighwater_persistsAcrossReopen() throws IOException {
        // covers: R9a-mono — high-water survives reopen (write-temp + fsync + rename).
        final CatalogIndex first = new CatalogIndex(tempDir);
        first.setHighwater("users", 3);
        first.setHighwater("orders", 1);

        final CatalogIndex second = new CatalogIndex(tempDir);

        assertEquals(3, second.highwater("users").orElseThrow());
        assertEquals(1, second.highwater("orders").orElseThrow());
    }

    // ---- remove ----

    @Test
    void remove_clearsHighwater() throws IOException {
        // covers: R9a-mono — remove drops the entry; subsequent highwater reports empty.
        final CatalogIndex index = new CatalogIndex(tempDir);
        index.setHighwater("doomed", 5);

        index.remove("doomed");

        assertFalse(index.highwater("doomed").isPresent(),
                "remove must clear the high-water entry");
    }

    @Test
    void remove_persistsAcrossReopen() throws IOException {
        // covers: R9a-mono — remove is durable, not in-memory only.
        final CatalogIndex first = new CatalogIndex(tempDir);
        first.setHighwater("doomed", 7);
        first.remove("doomed");

        final CatalogIndex second = new CatalogIndex(tempDir);
        assertFalse(second.highwater("doomed").isPresent());
    }

    // ---- input validation ----

    @Test
    void constructor_nullRoot_throwsNPE() {
        // covers: R9a-mono — eager input validation.
        assertThrows(NullPointerException.class, () -> new CatalogIndex(null));
    }

    @Test
    void highwater_nullName_throwsNPE() throws IOException {
        // covers: R9a-mono — eager input validation.
        final CatalogIndex index = new CatalogIndex(tempDir);
        assertThrows(NullPointerException.class, () -> index.highwater(null));
    }

    @Test
    void setHighwater_nullName_throwsNPE() throws IOException {
        // covers: R9a-mono — eager input validation.
        final CatalogIndex index = new CatalogIndex(tempDir);
        assertThrows(NullPointerException.class, () -> index.setHighwater(null, 1));
    }

    @Test
    void setHighwater_negativeVersion_throwsIAE() throws IOException {
        // covers: R9a-mono — version is non-negative.
        final CatalogIndex index = new CatalogIndex(tempDir);
        assertThrows(IllegalArgumentException.class, () -> index.setHighwater("users", -1));
    }

    @Test
    void remove_nullName_throwsNPE() throws IOException {
        // covers: R9a-mono — eager input validation.
        final CatalogIndex index = new CatalogIndex(tempDir);
        assertThrows(NullPointerException.class, () -> index.remove(null));
    }

    // ---- structural — multiple tables coexist independently ----

    @Test
    void multipleTables_independentHighwaters() throws IOException {
        // covers: R9a-mono — per-table independent versions; one table's bump does not affect
        // another.
        final CatalogIndex index = new CatalogIndex(tempDir);
        index.setHighwater("users", 1);
        index.setHighwater("orders", 2);
        index.setHighwater("orders", 3);

        assertEquals(1, index.highwater("users").orElseThrow());
        assertEquals(3, index.highwater("orders").orElseThrow());
    }

    // ---- defensive (Lens B) — atomic write should not leave temp files behind on success ----

    @Test
    void setHighwater_doesNotLeaveTempFilesBehind() throws IOException {
        // finding: IMPL-RISK — atomic write-temp + rename should clean up after itself.
        final CatalogIndex index = new CatalogIndex(tempDir);
        index.setHighwater("users", 1);

        try (var stream = Files.list(tempDir)) {
            final boolean leftTmp = stream
                    .anyMatch(p -> p.getFileName().toString().endsWith(".tmp"));
            assertFalse(leftTmp, "atomic-rename pattern must not leave .tmp files");
        }
    }

    // ---- defensive (Lens B) — second instance sees first's writes (no in-memory-only state) ----

    @Test
    void setHighwater_visibleToFreshInstanceImmediately() throws IOException {
        // finding: IMPL-RISK — write-temp + rename must flush on each setHighwater so a peer
        // process / restart sees the value without close().
        final CatalogIndex writer = new CatalogIndex(tempDir);
        writer.setHighwater("table-a", 4);

        // No close on writer — fresh reader still sees it (durable on every set).
        final CatalogIndex reader = new CatalogIndex(tempDir);
        assertNotNull(reader.highwater("table-a"));
        assertEquals(4, reader.highwater("table-a").orElseThrow());
    }
}
