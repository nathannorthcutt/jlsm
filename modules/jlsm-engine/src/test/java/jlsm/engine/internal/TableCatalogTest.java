package jlsm.engine.internal;

import jlsm.engine.TableMetadata;
import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TableCatalogTest {

    @TempDir
    Path tempDir;

    private static JlsmSchema testSchema() {
        return JlsmSchema.builder("test-schema", 1).field("name", FieldType.Primitive.STRING)
                .field("age", FieldType.Primitive.INT32).build();
    }

    // ---- open() on empty directory ----

    @Test
    void openOnEmptyDirectoryDiscoversNoTables() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();

        assertTrue(catalog.list().isEmpty());
        assertFalse(catalog.isLoading());
        catalog.close();
    }

    // ---- open() discovers existing tables ----

    @Test
    void openDiscoversExistingTableSubdirectories() throws IOException {
        // First catalog: register a table
        var catalog1 = new TableCatalog(tempDir);
        catalog1.open();
        catalog1.register("users", testSchema());
        catalog1.close();

        // Second catalog: open should discover the table
        var catalog2 = new TableCatalog(tempDir);
        catalog2.open();

        assertEquals(1, catalog2.list().size());
        Optional<TableMetadata> metadata = catalog2.get("users");
        assertTrue(metadata.isPresent());
        assertEquals("users", metadata.get().name());
        // Updated by audit F-R1.cb.2.2: recovered tables now preserve persisted state (READY), not
        // skeleton LOADING
        assertEquals(TableMetadata.TableState.READY, metadata.get().state());
        catalog2.close();
    }

    // ---- register() ----

    @Test
    void registerCreatesSubdirectoryAndMetadataFile() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();

        TableMetadata metadata = catalog.register("users", testSchema());

        assertNotNull(metadata);
        assertEquals("users", metadata.name());
        assertEquals(TableMetadata.TableState.READY, metadata.state());
        assertNotNull(metadata.createdAt());
        assertTrue(Files.isDirectory(tempDir.resolve("users")));
        catalog.close();
    }

    @Test
    void registerDuplicateNameThrowsIOException() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();
        catalog.register("users", testSchema());

        assertThrows(IOException.class, () -> catalog.register("users", testSchema()));
        catalog.close();
    }

    @Test
    void registerReturnsMetadataWithPassedSchema() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();
        JlsmSchema schema = testSchema();

        TableMetadata metadata = catalog.register("users", schema);

        assertSame(schema, metadata.schema());
        catalog.close();
    }

    // ---- unregister() ----

    @Test
    void unregisterRemovesDirectoryTree() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();
        catalog.register("users", testSchema());
        assertTrue(Files.isDirectory(tempDir.resolve("users")));

        catalog.unregister("users");

        assertFalse(Files.exists(tempDir.resolve("users")));
        assertTrue(catalog.get("users").isEmpty());
        assertTrue(catalog.list().isEmpty());
        catalog.close();
    }

    @Test
    void unregisterUnknownNameThrowsIOException() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();

        assertThrows(IOException.class, () -> catalog.unregister("nonexistent"));
        catalog.close();
    }

    // ---- get() ----

    @Test
    void getReturnsPresentForRegisteredTable() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();
        catalog.register("users", testSchema());

        Optional<TableMetadata> result = catalog.get("users");

        assertTrue(result.isPresent());
        assertEquals("users", result.get().name());
        catalog.close();
    }

    @Test
    void getReturnsEmptyForUnknownTable() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();

        Optional<TableMetadata> result = catalog.get("nonexistent");

        assertTrue(result.isEmpty());
        catalog.close();
    }

    // ---- list() ----

    @Test
    void listReturnsAllRegisteredTables() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();
        catalog.register("users", testSchema());
        catalog.register("orders", testSchema());

        Collection<TableMetadata> all = catalog.list();

        assertEquals(2, all.size());
        catalog.close();
    }

    @Test
    void listReturnsUnmodifiableCollection() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();
        catalog.register("users", testSchema());

        Collection<TableMetadata> all = catalog.list();

        assertThrows(UnsupportedOperationException.class, () -> all.clear());
        catalog.close();
    }

    // ---- tableDirectory() ----

    @Test
    void tableDirectoryReturnsCorrectPath() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();

        Path dir = catalog.tableDirectory("users");

        assertEquals(tempDir.resolve("users"), dir);
        catalog.close();
    }

    // ---- isLoading() ----

    @Test
    void isLoadingReturnsFalseAfterOpen() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();

        assertFalse(catalog.isLoading());
        catalog.close();
    }

    @Test
    void isLoadingReturnsTrueBeforeOpen() {
        var catalog = new TableCatalog(tempDir);
        // Before open(), isLoading should return true (catalog is not yet ready)
        assertTrue(catalog.isLoading());
    }

    // ---- Partial creation cleanup ----

    @Test
    void openCleansUpDirectoryWithoutMetadataFile() throws IOException {
        // Create a directory but no metadata file — simulates partial creation
        Files.createDirectory(tempDir.resolve("orphan"));

        var catalog = new TableCatalog(tempDir);
        catalog.open();

        // The orphan directory should be cleaned up
        assertTrue(catalog.get("orphan").isEmpty());
        assertFalse(Files.exists(tempDir.resolve("orphan")));
        catalog.close();
    }

    // ---- Null/empty validation ----

    @Test
    void constructorNullRootDirThrows() {
        assertThrows(NullPointerException.class, () -> new TableCatalog(null));
    }

    @Test
    void registerNullNameThrows() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();
        assertThrows(NullPointerException.class, () -> catalog.register(null, testSchema()));
        catalog.close();
    }

    @Test
    void registerNullSchemaThrows() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();
        assertThrows(NullPointerException.class, () -> catalog.register("users", null));
        catalog.close();
    }

    @Test
    void registerEmptyNameThrows() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();
        assertThrows(IllegalArgumentException.class, () -> catalog.register("", testSchema()));
        catalog.close();
    }

    @Test
    void unregisterNullNameThrows() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();
        assertThrows(NullPointerException.class, () -> catalog.unregister(null));
        catalog.close();
    }

    @Test
    void unregisterEmptyNameThrows() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();
        assertThrows(IllegalArgumentException.class, () -> catalog.unregister(""));
        catalog.close();
    }

    @Test
    void getNullNameThrows() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();
        assertThrows(NullPointerException.class, () -> catalog.get(null));
        catalog.close();
    }

    @Test
    void tableDirectoryNullNameThrows() {
        var catalog = new TableCatalog(tempDir);
        assertThrows(NullPointerException.class, () -> catalog.tableDirectory(null));
    }

    // ---- close() is idempotent ----

    @Test
    void closeIsIdempotent() throws IOException {
        var catalog = new TableCatalog(tempDir);
        catalog.open();
        catalog.close();
        // Second close should not throw
        catalog.close();
    }

    // ---- open() creates root directory if it does not exist ----

    @Test
    void openCreatesRootDirectoryIfMissing() throws IOException {
        Path nestedDir = tempDir.resolve("sub").resolve("dir");
        var catalog = new TableCatalog(nestedDir);
        catalog.open();

        assertTrue(Files.isDirectory(nestedDir));
        catalog.close();
    }
}
