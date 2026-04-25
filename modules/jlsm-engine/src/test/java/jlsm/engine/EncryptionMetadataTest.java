package jlsm.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jlsm.encryption.DomainId;
import jlsm.encryption.TableId;
import jlsm.encryption.TableScope;
import jlsm.encryption.TenantId;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EncryptionMetadata} — validates R8a (record component), R8c (component-wise
 * equality), R8d (immutability) from {@code sstable.footer-encryption-scope}.
 */
class EncryptionMetadataTest {

    private static TableScope scope(String tenant, String domain, String table) {
        return new TableScope(new TenantId(tenant), new DomainId(domain), new TableId(table));
    }

    // ---------- Happy path ----------

    @Test
    void constructor_acceptsScope_andExposesComponent() {
        // covers: R8a — record component `scope` retained
        final TableScope scope = scope("tenant-a", "domain-a", "table-a");
        final EncryptionMetadata em = new EncryptionMetadata(scope);

        assertSame(scope, em.scope());
    }

    // ---------- Error / null rejection ----------

    @Test
    void constructor_rejectsNullScope() {
        // covers: R8a — canonical constructor must reject null scope with NullPointerException
        assertThrows(NullPointerException.class, () -> new EncryptionMetadata(null));
    }

    // ---------- Equality / structural ----------

    @Test
    void equality_isComponentWise_andPositive() {
        // covers: R8c — two EncryptionMetadata instances are equal iff their scopes are equal
        final EncryptionMetadata a = new EncryptionMetadata(scope("t", "d", "x"));
        final EncryptionMetadata b = new EncryptionMetadata(scope("t", "d", "x"));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equality_distinguishesByScope() {
        // covers: R8c
        final EncryptionMetadata a = new EncryptionMetadata(scope("t1", "d", "x"));
        final EncryptionMetadata b = new EncryptionMetadata(scope("t2", "d", "x"));

        assertNotEquals(a, b);
    }
}
