package jlsm.engine;

import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TableMetadataTest {

    private static JlsmSchema testSchema() {
        return JlsmSchema.builder("test", 1).field("name", FieldType.Primitive.STRING).build();
    }

    // --- Happy path ---

    @Test
    void testTableMetadataCreation() {
        var schema = testSchema();
        var now = Instant.now();

        var metadata = new TableMetadata("users", schema, now, TableMetadata.TableState.READY);

        assertEquals("users", metadata.name());
        assertSame(schema, metadata.schema());
        assertEquals(now, metadata.createdAt());
        assertEquals(TableMetadata.TableState.READY, metadata.state());
    }

    @Test
    void testTableMetadataAllStates() {
        var schema = testSchema();
        var now = Instant.now();

        for (var state : TableMetadata.TableState.values()) {
            var metadata = new TableMetadata("t", schema, now, state);
            assertEquals(state, metadata.state());
        }
    }

    // --- Error cases ---

    @Test
    void testTableMetadataNullNameThrows() {
        assertThrows(NullPointerException.class, () -> new TableMetadata(null, testSchema(),
                Instant.now(), TableMetadata.TableState.READY));
    }

    @Test
    void testTableMetadataNullSchemaThrows() {
        assertThrows(NullPointerException.class,
                () -> new TableMetadata("t", null, Instant.now(), TableMetadata.TableState.READY));
    }

    @Test
    void testTableMetadataNullCreatedAtThrows() {
        assertThrows(NullPointerException.class,
                () -> new TableMetadata("t", testSchema(), null, TableMetadata.TableState.READY));
    }

    @Test
    void testTableMetadataNullStateThrows() {
        assertThrows(NullPointerException.class,
                () -> new TableMetadata("t", testSchema(), Instant.now(), null));
    }

    // --- Boundary ---

    @Test
    void testTableMetadataEmptyNameThrows() {
        assertThrows(AssertionError.class, () -> new TableMetadata("", testSchema(), Instant.now(),
                TableMetadata.TableState.READY));
    }
}
