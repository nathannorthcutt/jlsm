package jlsm.encryption;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TableScope} — validates R8b (record components), R8c (component-wise equality),
 * R8d (immutability/thread-safety) from {@code sstable.footer-encryption-scope}.
 */
class TableScopeTest {

    private static final TenantId TENANT_A = new TenantId("tenant-a");
    private static final TenantId TENANT_B = new TenantId("tenant-b");
    private static final DomainId DOMAIN_A = new DomainId("domain-a");
    private static final DomainId DOMAIN_B = new DomainId("domain-b");
    private static final TableId TABLE_A = new TableId("table-a");
    private static final TableId TABLE_B = new TableId("table-b");

    // ---------- Happy path ----------

    @Test
    void constructor_acceptsThreeIdentities_andExposesComponents() {
        // covers: R8b (record components), R8d (immutability — components retained)
        final TableScope scope = new TableScope(TENANT_A, DOMAIN_A, TABLE_A);

        assertAll(() -> assertSame(TENANT_A, scope.tenantId()),
                () -> assertSame(DOMAIN_A, scope.domainId()),
                () -> assertSame(TABLE_A, scope.tableId()));
    }

    // ---------- Error / null rejection ----------

    @Test
    void constructor_rejectsNullTenantId() {
        // covers: R8b — canonical constructor must reject null tenantId with NullPointerException
        assertThrows(NullPointerException.class, () -> new TableScope(null, DOMAIN_A, TABLE_A));
    }

    @Test
    void constructor_rejectsNullDomainId() {
        // covers: R8b
        assertThrows(NullPointerException.class, () -> new TableScope(TENANT_A, null, TABLE_A));
    }

    @Test
    void constructor_rejectsNullTableId() {
        // covers: R8b
        assertThrows(NullPointerException.class, () -> new TableScope(TENANT_A, DOMAIN_A, null));
    }

    // ---------- Equality / structural ----------

    @Test
    void equality_isComponentWise_andPositive() {
        // covers: R8c — two TableScope instances are equal iff their three component records
        // are equal (default record semantics)
        final TableScope a = new TableScope(TENANT_A, DOMAIN_A, TABLE_A);
        final TableScope b = new TableScope(new TenantId("tenant-a"), new DomainId("domain-a"),
                new TableId("table-a"));

        assertAll(() -> assertEquals(a, b), () -> assertEquals(a.hashCode(), b.hashCode()));
    }

    @Test
    void equality_distinguishesByTenantId() {
        // covers: R8c — mismatch on tenantId yields not-equal
        final TableScope a = new TableScope(TENANT_A, DOMAIN_A, TABLE_A);
        final TableScope b = new TableScope(TENANT_B, DOMAIN_A, TABLE_A);

        assertNotEquals(a, b);
    }

    @Test
    void equality_distinguishesByDomainId() {
        // covers: R8c
        final TableScope a = new TableScope(TENANT_A, DOMAIN_A, TABLE_A);
        final TableScope b = new TableScope(TENANT_A, DOMAIN_B, TABLE_A);

        assertNotEquals(a, b);
    }

    @Test
    void equality_distinguishesByTableId() {
        // covers: R8c
        final TableScope a = new TableScope(TENANT_A, DOMAIN_A, TABLE_A);
        final TableScope b = new TableScope(TENANT_A, DOMAIN_A, TABLE_B);

        assertNotEquals(a, b);
    }

    // ---------- Defensive (Lens B — error-message discipline) ----------

    @Test
    void toString_doesNotRevealRawTenantValue() {
        // R12 / Lens B SPEC-BOUNDARY: TenantId redacts its value in toString. The default
        // record toString interpolates components — verify that delegating to component
        // toStrings preserves redaction (does not leak the raw "secret-tenant-name" text).
        // This pins the expectation that TableScope's default toString uses TenantId's
        // redacted form, not the raw value.
        final TableScope scope = new TableScope(new TenantId("secret-tenant-name"),
                new DomainId("domain-a"), new TableId("table-a"));

        final String text = scope.toString();
        // The raw secret value must not appear; the redaction marker must.
        assertAll(
                () -> org.junit.jupiter.api.Assertions.assertFalse(
                        text.contains("secret-tenant-name"),
                        "TableScope.toString() must not leak raw TenantId value: " + text),
                () -> org.junit.jupiter.api.Assertions.assertTrue(text.contains("redacted"),
                        "TableScope.toString() must propagate TenantId redaction: " + text));
    }
}
