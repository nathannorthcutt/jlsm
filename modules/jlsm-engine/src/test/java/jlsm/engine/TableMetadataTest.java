package jlsm.engine;

import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;
import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

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

    // Updated by audit F-R1.cb.2.7: assert-only empty name check was a bug, now correctly throws
    // IllegalArgumentException
    @Test
    void testTableMetadataEmptyNameThrows() {
        assertThrows(IllegalArgumentException.class, () -> new TableMetadata("", testSchema(),
                Instant.now(), TableMetadata.TableState.READY));
    }

    // --- Encryption metadata extension (R8, R8a) ---

    private static TableScope testScope() {
        return new TableScope(new TenantId("t"), new DomainId("d"), new TableId("x"));
    }

    @Test
    void fourArgConstructor_defaultsEncryptionToEmpty() {
        // covers: R8 — backward-compatibility convenience constructor; existing 4-arg
        // call-sites must remain source-compatible. Absent encryption == "plaintext table".
        var metadata = new TableMetadata("users", testSchema(), Instant.now(),
                TableMetadata.TableState.READY);

        assertEquals(Optional.empty(), metadata.encryption());
    }

    @Test
    void fiveArgConstructor_acceptsExplicitEncryption() {
        // covers: R8 — TableMetadata extended with 5th component Optional<EncryptionMetadata>
        var em = new EncryptionMetadata(testScope());
        var metadata = new TableMetadata("users", testSchema(), Instant.now(),
                TableMetadata.TableState.READY, Optional.of(em));

        assertEquals(Optional.of(em), metadata.encryption());
    }

    @Test
    void fiveArgConstructor_acceptsExplicitEmptyEncryption() {
        // covers: R8 — Optional.empty() is a valid encryption value (means plaintext)
        var metadata = new TableMetadata("users", testSchema(), Instant.now(),
                TableMetadata.TableState.READY, Optional.empty());

        assertEquals(Optional.empty(), metadata.encryption());
    }

    @Test
    void fiveArgConstructor_rejectsNullEncryption() {
        // covers: R8 — Optional component is non-null; absent encryption is Optional.empty(),
        // not null. Eager null-rejection on canonical record constructor.
        assertThrows(NullPointerException.class, () -> new TableMetadata("users", testSchema(),
                Instant.now(), TableMetadata.TableState.READY, null));
    }

    @Test
    void encryptionMetadata_componentEqualityIncludesEncryption() {
        // covers: R8c — record equality is component-wise across all 5 components, so
        // distinct encryption values produce distinct TableMetadata records.
        var now = Instant.now();
        var schema = testSchema();
        var em = new EncryptionMetadata(testScope());

        var encrypted = new TableMetadata("u", schema, now, TableMetadata.TableState.READY,
                Optional.of(em));
        var plaintext = new TableMetadata("u", schema, now, TableMetadata.TableState.READY,
                Optional.empty());
        var encrypted2 = new TableMetadata("u", schema, now, TableMetadata.TableState.READY,
                Optional.of(new EncryptionMetadata(testScope())));

        assertNotEquals(encrypted, plaintext);
        assertEquals(encrypted, encrypted2);
    }
}
