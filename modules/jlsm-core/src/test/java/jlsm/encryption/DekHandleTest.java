package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Tests for {@link DekHandle} — validates R81/R81a opacity + component validation. */
class DekHandleTest {

    private static final TenantId TENANT = new TenantId("t1");
    private static final DomainId DOMAIN = new DomainId("d1");
    private static final TableId TABLE = new TableId("tbl");
    private static final DekVersion V1 = new DekVersion(1);

    @Test
    void constructor_rejectsNullTenant() {
        assertThrows(NullPointerException.class, () -> new DekHandle(null, DOMAIN, TABLE, V1));
    }

    @Test
    void constructor_rejectsNullDomain() {
        assertThrows(NullPointerException.class, () -> new DekHandle(TENANT, null, TABLE, V1));
    }

    @Test
    void constructor_rejectsNullTable() {
        assertThrows(NullPointerException.class, () -> new DekHandle(TENANT, DOMAIN, null, V1));
    }

    @Test
    void constructor_rejectsNullVersion() {
        assertThrows(NullPointerException.class, () -> new DekHandle(TENANT, DOMAIN, TABLE, null));
    }

    @Test
    void accessors_roundTripComponents() {
        final DekHandle handle = new DekHandle(TENANT, DOMAIN, TABLE, V1);
        assertEquals(TENANT, handle.tenantId());
        assertEquals(DOMAIN, handle.domainId());
        assertEquals(TABLE, handle.tableId());
        assertEquals(V1, handle.version());
    }

    @Test
    void equality_allComponentsMustMatch() {
        final DekHandle a = new DekHandle(TENANT, DOMAIN, TABLE, V1);
        final DekHandle b = new DekHandle(TENANT, DOMAIN, TABLE, V1);
        final DekHandle c = new DekHandle(TENANT, DOMAIN, TABLE, new DekVersion(2));
        assertEquals(a, b);
        assertNotEquals(a, c);
    }
}
